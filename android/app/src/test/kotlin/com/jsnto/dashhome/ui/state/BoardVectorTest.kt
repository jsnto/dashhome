package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.GridDims
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.Parsers
import com.jsnto.dashhome.api.TileGeometry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared board-layout golden vectors (frontend/test-fixtures/board-layout/): the same
 * input/expected-output pairs the Go (backend/config/board_vectors_test.go)
 * and web (frontend/src/boardVectors.test.ts) suites assert against the
 * same domain. The fixtures ride the unit-test classpath (the resources
 * srcDir in build.gradle.kts), so this suite reads the very files the
 * other languages read (see the directory's README for the per-file
 * contract and which suite asserts which group).
 */
class BoardVectorTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("board-layout/$name")
            ?.bufferedReader()?.readText()
            ?: error("missing fixture board-layout/$name")

    /** One board object through the production parser (the JSON boundary). */
    private fun board(body: String) = Parsers.board(body)

    /** A raw geometry map through the production parser (unknown units
     *  dropped at the parse boundary, the same rule the other languages
     *  apply to the same JSON). */
    private fun geometryMap(raw: JSONObject): Map<String, TileGeometry> =
        board("""{"board":{"views":{"geometry":${raw.toString()}}}}""").views!!.geometry

    /** A raw ha_tiles list through the production parser. */
    private fun haTiles(raw: JSONArray): List<HABoardTile> =
        board("""{"board":{"views":{"ha_tiles":${raw.toString()}}}}""").views!!.haTiles

    private fun stringList(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getString(it) }

    private fun cells(o: JSONObject): Map<String, TileGeometry> =
        o.keys().asSequence().associateWith { k ->
            val c = o.getJSONObject(k)
            TileGeometry(c.getInt("x"), c.getInt("y"), c.getInt("w"), c.getInt("h"))
        }

    @Test
    fun gridVectorsRepairTheStoredGrid() {
        val vectors = JSONObject(fixture("grid.json")).getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val in_ = v.optJSONObject("input")
            val grid = if (in_ == null) null else GridDims(in_.getInt("cols"), in_.getInt("rows"))
            val exp = v.getJSONObject("expected")
            assertEquals(
                v.getString("name"),
                GridDims(exp.getInt("cols"), exp.getInt("rows")),
                normalizeGrid(grid),
            )
        }
    }

    @Test
    fun geometryVectorsRepairTheStoredPlacements() {
        val vectors = JSONObject(fixture("geometry.json")).getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val order = stringList(v.getJSONArray("order"))
            val ids = stringList(v.getJSONArray("ids")).toSet()
            val got = normalizeGeometry(
                geometryMap(v.getJSONObject("raw")),
                order,
                ids,
                v.getInt("cols"),
                v.getInt("rows"),
            )
            assertEquals(v.getString("name"), geometryMap(v.getJSONObject("expected")), got)
        }
    }

    @Test
    fun viewVectorsRepairARawBoardFile() {
        val vectors = JSONObject(fixture("views.json")).getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val input = v.getJSONObject("input")
            val b = board("""{"board":${input.toString()}}""")
            val grid = normalizeGrid(b.grid)
            val exp = v.getJSONObject("expected")
            val expGrid = exp.getJSONObject("grid")
            assertEquals(v.getString("name"), GridDims(expGrid.getInt("cols"), expGrid.getInt("rows")), grid)
            val want = board("""{"board":{"views":${exp.getJSONObject("views").toString()}}}""").views!!
            assertEquals(v.getString("name"), want, normalizeViews(b.views, grid))
        }
    }

    @Test
    fun haTileIdShapeVectors() {
        val shapes = JSONObject(fixture("ha-tiles.json")).getJSONArray("id_shape")
        for (i in 0 until shapes.length()) {
            val v = shapes.getJSONObject(i)
            assertEquals(v.getString("id"), v.getBoolean("valid"), isHATileId(v.getString("id")))
        }
    }

    @Test
    fun normalizeHATilesVectors() {
        val vectors = JSONObject(fixture("ha-tiles.json")).getJSONArray("normalize")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            assertEquals(
                v.getString("name"),
                haTiles(v.getJSONArray("expected")),
                normalizeHATiles(haTiles(v.getJSONArray("input"))),
            )
        }
    }

    @Test
    fun nextHATileIdVectors() {
        val vectors = JSONObject(fixture("ha-tiles.json")).getJSONArray("next_id")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            assertEquals(
                v.getString("name"),
                v.getString("expected"),
                nextHATileId(haTiles(v.getJSONArray("tiles"))),
            )
        }
    }

    @Test
    fun tileNameVectorsConvergeOnTheServerNoTrimRule() {
        val vectors = JSONObject(fixture("ha-tiles.json")).getJSONArray("display_names")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val tile = v.optJSONObject("tile")
            val views = BoardViews(
                haTiles = if (tile != null) listOf(tileFrom(tile)) else emptyList(),
            )
            assertEquals(v.getString("name"), v.getString("expected"), tileName(v.getString("id"), views))
        }
    }

    /** The well-formed fixture tiles (the display-name group) parsed directly. */
    private fun tileFrom(o: JSONObject): HABoardTile = HABoardTile(
        id = o.getString("id"),
        entity = o.getString("entity"),
        label = o.optString("label", "").ifEmpty { null },
        kind = o.getString("kind"),
    )

    @Test
    fun stepVocabularyVectors() {
        val f = JSONObject(fixture("step-vocabulary.json"))
        val steps = f.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val v = steps.getJSONObject(i)
            assertEquals(v.getString("step"), v.getString("label"), TILE_SIZE_LABELS[v.getString("step")])
        }
        val rejects = f.getJSONArray("rejects")
        for (i in 0 until rejects.length()) {
            assertEquals(rejects.getString(i), null, TILE_SIZE_LABELS[rejects.getString(i)])
        }
        val widths = f.getJSONArray("widths")
        for (i in 0 until widths.length()) {
            val v = widths.getJSONObject(i)
            val size: String? = if (v.isNull("size")) null else v.getString("size")
            assertEquals(v.getInt("expected"), stepWidth(size, v.getInt("cols")))
        }
    }

    @Test
    fun autoLayoutVectorsFlowTheBoardOrderAroundTheFixedPlacements() {
        val vectors = JSONObject(fixture("auto-layout.json")).getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val order = stringList(v.getJSONArray("order"))
            val sizes = v.getJSONObject("sizes").let { o ->
                o.keys().asSequence().associateWith { k -> o.getString(k) }
            }
            val fixed = cells(v.getJSONObject("fixed"))
            assertEquals(
                v.getString("name"),
                cells(v.getJSONObject("expected")),
                autoLayout(order, sizes, fixed, v.getInt("cols")),
            )
        }
    }
}
