package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.GridDims
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.TileGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin port of the web dashboard's `frontend/src/tileGrid.test.ts`
 * and the board-layout cases of `frontend/src/utils.test.ts`: the same
 * inputs, the same expected cells. (The web suite is the reference:
 * `npm run test` in frontend/.)
 */
class BoardLayoutTest {

    private val ORDER = listOf("calendar", "chat", "home", "push")

    // The measured board box the pointer math runs against: 12 columns of
    // 100px with a 16px gap (1376px wide), 12 rows of 150px (1976px tall).
    private val M = GridMetrics(colW = 100f, rowH = 150f, gap = 16f)

    private fun cell(x: Int, y: Int, w: Int, h: Int) = TileGeometry(x, y, w, h)

    // ------------------------------------------------------------------
    // firstFreeCell (new tiles default to 2x2)
    // ------------------------------------------------------------------

    @Test
    fun firstFreeCellReturnsTheDefaultNewTileSpan() {
        assertEquals(Span(2, 2), NEW_TILE_DEFAULT_SIZE)
    }

    @Test
    fun firstFreeCellFindsTheFirstFreeSpanInReadingOrder() {
        // The default medium flow fills rows 1-2 (calendar/chat row 1,
        // home/push row 2), so the first free 2x2 cell is (1,3).
        assertEquals(cell(1, 3, 2, 2), firstFreeCell(ORDER, emptyMap(), emptyMap(), 2, 2))
    }

    @Test
    fun firstFreeCellReturnsTheTopLeftCellOnAnEmptyBoard() {
        assertEquals(cell(1, 1, 2, 2), firstFreeCell(emptyList(), emptyMap(), emptyMap(), 2, 2))
    }

    @Test
    fun firstFreeCellSteersAroundStoredPlacementsAndTheAutoFlow() {
        // home is pinned at (1,2,3,1); the auto flow around it is
        // calendar (1,1,6,1), chat (7,1,6,1), push (4,2,6,1). Row 1 is
        // full, and in row 2 the cells x<=9 clash with home or push — the
        // first free 2x2 cell is (10,2).
        val geometry = mapOf("home" to cell(1, 2, 3, 1))
        assertEquals(cell(10, 2, 2, 2), firstFreeCell(ORDER, emptyMap(), geometry, 2, 2))
    }

    @Test
    fun firstFreeCellSteersAroundAutoFlowedCellsOfEveryTileInBoardOrder() {
        // The caller passes the full board order (hidden tiles keep their
        // effective cells), so a hidden tile's auto cell is an obstacle.
        // calendar (xl) fills row 1; chat (m) flows to (1,2): the first
        // free 2x2 cell is (7,2), beside chat, not below calendar's row.
        assertEquals(cell(7, 2, 2, 2), firstFreeCell(listOf("calendar", "chat"), mapOf("calendar" to "xl"), emptyMap(), 2, 2))
    }

    @Test
    fun firstFreeCellReturnsNullWhenTheGridIsTooSmall() {
        assertNull(firstFreeCell(ORDER, emptyMap(), emptyMap(), 2, 2, 1, 12))
        assertNull(firstFreeCell(ORDER, emptyMap(), emptyMap(), 2, 2, 12, 1))
        assertNull(firstFreeCell(emptyList(), emptyMap(), emptyMap(), 2, 2, 1, 1))
    }

    @Test
    fun firstFreeCellReturnsNullWhenNoCellIsFree() {
        val full = mapOf(
            "calendar" to cell(1, 1, 12, 6),
            "chat" to cell(1, 7, 12, 6),
        )
        assertNull(firstFreeCell(ORDER, emptyMap(), full, 2, 2))
        // A 2x2 grid fully covered by the default flow of medium tiles.
        assertNull(firstFreeCell(ORDER, emptyMap(), emptyMap(), 2, 2, 2, 2))
    }

    // ------------------------------------------------------------------
    // stepWidth
    // ------------------------------------------------------------------

    @Test
    fun stepWidthMapsTheSizeStepsToFractionsOfTheGridWidth() {
        assertEquals(4, stepWidth("s"))
        assertEquals(6, stepWidth("m"))
        assertEquals(8, stepWidth("l"))
        assertEquals(12, stepWidth("xl"))
        assertEquals(6, stepWidth(null))
        assertEquals(6, stepWidth("bogus"))
    }

    @Test
    fun stepWidthScalesTheFractionsToTheGridWidth() {
        assertEquals(2, stepWidth("s", 6))
        assertEquals(3, stepWidth("m", 6))
        assertEquals(4, stepWidth("l", 6))
        assertEquals(6, stepWidth("xl", 6))
        assertEquals(1, stepWidth("s", 3))
        assertEquals(3, stepWidth("xl", 3))
    }

    // ------------------------------------------------------------------
    // autoLayout
    // ------------------------------------------------------------------

    @Test
    fun autoLayoutFlowsTheDefaultBoardAsTwoRowsOfMediumTiles() {
        val layout = autoLayout(ORDER, emptyMap())
        assertEquals(cell(1, 1, 6, 1), layout["calendar"])
        assertEquals(cell(7, 1, 6, 1), layout["chat"])
        assertEquals(cell(1, 2, 6, 1), layout["home"])
        assertEquals(cell(7, 2, 6, 1), layout["push"])
    }

    @Test
    fun autoLayoutWrapsToTheNextRowWhenAWidthDoesNotFit() {
        // The regression the xl tile exposed: a full-width tile starts a
        // new row; the packer must terminate and keep flowing around it.
        val layout = autoLayout(ORDER, mapOf("chat" to "xl"))
        assertEquals(cell(1, 2, 12, 1), layout["chat"])
        assertEquals(cell(1, 1, 6, 1), layout["calendar"])
        assertEquals(cell(1, 3, 6, 1), layout["home"])
        assertEquals(cell(7, 3, 6, 1), layout["push"])
    }

    @Test
    fun autoLayoutDoesNotBackfillGapsBehindTheAutoFlowCursor() {
        // The regression the mixed-size board exposed: the board's CSS
        // grid uses the default sparse auto flow (the cursor never moves
        // backwards), so the columns 9-12 that calendar (l) leaves in row
        // 1 are not revisited — push (s) lands at (1,3), a third row, not
        // at (9,1).
        val layout = autoLayout(ORDER, mapOf("calendar" to "l", "push" to "s"))
        assertEquals(cell(1, 1, 8, 1), layout["calendar"])
        assertEquals(cell(1, 2, 6, 1), layout["chat"])
        assertEquals(cell(7, 2, 6, 1), layout["home"])
        assertEquals(cell(1, 3, 4, 1), layout["push"])
    }

    @Test
    fun autoLayoutRespectsBoardOrderForTheAutoCells() {
        val layout = autoLayout(listOf("push", "home", "chat", "calendar"), emptyMap())
        assertEquals(cell(1, 1, 6, 1), layout["push"])
        assertEquals(cell(7, 1, 6, 1), layout["home"])
    }

    @Test
    fun autoLayoutKeepsStoredPlacementsAndFlowsTheRestAroundThem() {
        val layout = autoLayout(ORDER, emptyMap(), mapOf("calendar" to cell(1, 1, 8, 1)))
        assertEquals(cell(1, 1, 8, 1), layout["calendar"])
        // m=6 cannot fit the 4 remaining columns of row 1, so chat wraps:
        assertEquals(cell(1, 2, 6, 1), layout["chat"])
        assertEquals(cell(7, 2, 6, 1), layout["home"])
        assertEquals(cell(1, 3, 6, 1), layout["push"])
    }

    @Test
    fun autoLayoutFlowsAutoTilesAroundStoredEntriesThatSitLaterInBoardOrder() {
        // CSS places every explicitly positioned tile before the auto flow
        // starts, so home's stored cell at (4,1) (columns 4-6 of row 1)
        // must not be used by chat even though home comes after chat in
        // board order: calendar (w=6) does not fit row 1 columns 1-6 and
        // takes the open columns 7-12 instead.
        val layout = autoLayout(ORDER, emptyMap(), mapOf("home" to cell(4, 1, 3, 1)))
        assertEquals(cell(4, 1, 3, 1), layout["home"])
        assertEquals(cell(7, 1, 6, 1), layout["calendar"])
        assertEquals(cell(1, 2, 6, 1), layout["chat"])
        assertEquals(cell(7, 2, 6, 1), layout["push"])
    }

    @Test
    fun autoLayoutNeverOverlaps() {
        val layout = autoLayout(ORDER, mapOf("calendar" to "xl", "chat" to "xl", "home" to "s", "push" to "s"))
        val cells = layout.values.toList()
        for (i in cells.indices) for (j in (i + 1) until cells.size) {
            assertFalse(rectsOverlap(cells[i], cells[j]))
        }
    }

    @Test
    fun autoLayoutFlowsTheRestOfTheBoardAroundAFullWidthStoredEntry() {
        val layout = autoLayout(ORDER, emptyMap(), mapOf("calendar" to cell(1, 1, 12, 1)))
        assertEquals(cell(1, 1, 12, 1), layout["calendar"])
        assertEquals(cell(1, 2, 6, 1), layout["chat"])
    }

    // ------------------------------------------------------------------
    // clampCell
    // ------------------------------------------------------------------

    @Test
    fun clampCellClampsACellBackIntoTheGrid() {
        assertEquals(cell(1, 1, 3, 1), clampCell(cell(0, 1, 3, 1)))
        assertEquals(cell(10, 1, 3, 1), clampCell(cell(13, 1, 3, 1)))
        assertEquals(cell(1, 1, 3, 1), clampCell(cell(1, -3, 3, 1)))
        assertEquals(cell(1, 1, 9, 1), clampCell(cell(1, 1, 9, 1)))
        assertEquals(cell(1, 1, 12, 1), clampCell(cell(1, 1, 20, 1)))
        assertEquals(cell(1, 1, 1, 1), clampCell(cell(1, 1, 1, 0)))
    }

    @Test
    fun clampCellClampsTheBottomEdgeByTheGridRows() {
        assertEquals(cell(1, 11, 3, 2), clampCell(cell(1, 12, 3, 2)))
        assertEquals(cell(1, 11, 3, 2), clampCell(cell(1, 15, 3, 2)))
        assertEquals(cell(1, 1, 3, 12), clampCell(cell(1, 1, 3, 40)))
    }

    @Test
    fun clampCellFollowsTheConfiguredGridDimensions() {
        assertEquals(cell(1, 3, 6, 2), clampCell(cell(1, 5, 8, 2), 6, 4))
        assertEquals(cell(1, 1, 3, 1), clampCell(cell(1, 1, 3, 1), 3, 3))
    }

    // ------------------------------------------------------------------
    // dragCell
    // ------------------------------------------------------------------

    @Test
    fun dragCellSnapsAMoveToWholeCellsFromTheGrabOffset() {
        // One column pitch = 100 + 16 = 116; half a pitch rounds to the
        // same cell.
        assertEquals(cell(1, 1, 3, 1), dragCell(DragMode.MOVE, cell(1, 1, 3, 1), 0f, 0f, M))
        assertEquals(cell(2, 1, 3, 1), dragCell(DragMode.MOVE, cell(1, 1, 3, 1), 116f, 0f, M))
        assertEquals(cell(1, 1, 3, 1), dragCell(DragMode.MOVE, cell(1, 1, 3, 1), 50f, 0f, M))
        assertEquals(cell(1, 2, 3, 1), dragCell(DragMode.MOVE, cell(1, 1, 3, 1), 0f, 166f, M))
    }

    @Test
    fun dragCellClampsAMoveIntoTheGrid() {
        val g = cell(1, 1, 4, 1)
        assertEquals(cell(4, 1, 4, 1), dragCell(DragMode.MOVE, g, 3 * 116f, 0f, M)) // 1+3=4
        assertEquals(cell(9, 1, 4, 1), dragCell(DragMode.MOVE, g, 9 * 116f, 0f, M)) // clamped (12-4+1)
        assertEquals(cell(1, 1, 4, 1), dragCell(DragMode.MOVE, g, 0f, -3 * 166f, M)) // row 1 floor
    }

    @Test
    fun dragCellClampsAMoveAtTheBottomEdge() {
        assertEquals(cell(1, 11, 4, 2), dragCell(DragMode.MOVE, cell(1, 11, 4, 2), 0f, 3 * 166f, M))
        assertEquals(cell(1, 11, 4, 2), dragCell(DragMode.MOVE, cell(1, 1, 4, 2), 0f, 10 * 166f, M))
    }

    @Test
    fun dragCellGrowsAndShrinksWidthAndHeightFromTheCorner() {
        assertEquals(cell(1, 1, 4, 1), dragCell(DragMode.RESIZE, cell(1, 1, 3, 1), 116f, 0f, M))
        assertEquals(cell(1, 1, 12, 1), dragCell(DragMode.RESIZE, cell(1, 1, 3, 1), 9 * 116f, 0f, M))
        assertEquals(cell(1, 1, 12, 1), dragCell(DragMode.RESIZE, cell(1, 1, 3, 1), 20 * 116f, 0f, M))
        assertEquals(cell(1, 1, 3, 2), dragCell(DragMode.RESIZE, cell(1, 1, 3, 1), 0f, 166f, M))
        assertEquals(cell(1, 1, 3, 12), dragCell(DragMode.RESIZE, cell(1, 1, 3, 1), 0f, 20 * 166f, M))
        assertEquals(cell(1, 1, 1, 1), dragCell(DragMode.RESIZE, cell(1, 1, 3, 1), -2 * 116f, -166f, M))
    }

    // ------------------------------------------------------------------
    // dropRefused
    // ------------------------------------------------------------------

    @Test
    fun dropRefusedRefusesADropOntoAnAutoPlacedNeighbour() {
        // Default 12-column layout: calendar (1,1,w6), chat (7,1,w6),
        // home (1,2,w6), push (7,2,w6).
        assertTrue(dropRefused("calendar", cell(7, 1, 6, 1), listOf("calendar", "chat", "home"), emptyMap(), emptyMap()))
        assertTrue(dropRefused("calendar", cell(1, 2, 6, 1), listOf("calendar", "chat", "home"), emptyMap(), emptyMap()))
        // The empty cells of the default board accept the drop:
        assertFalse(dropRefused("calendar", cell(7, 3, 6, 1), listOf("calendar", "chat", "home"), emptyMap(), emptyMap()))
    }

    @Test
    fun dropRefusedRefusesADropOntoAStoredPlacementOfAnotherTile() {
        val geometry = mapOf("chat" to cell(1, 1, 6, 1))
        assertTrue(dropRefused("calendar", cell(2, 1, 4, 1), listOf("calendar", "chat"), emptyMap(), geometry))
        assertFalse(dropRefused("calendar", cell(7, 2, 6, 1), listOf("calendar", "chat"), emptyMap(), geometry))
    }

    @Test
    fun dropRefusedRefusesADropOutsideTheGrid() {
        // Beyond the right edge:
        assertTrue(dropRefused("calendar", cell(13, 1, 1, 1), listOf("calendar"), emptyMap(), emptyMap()))
        // Beyond the bottom edge:
        assertTrue(dropRefused("calendar", cell(1, 13, 1, 1), listOf("calendar"), emptyMap(), emptyMap()))
        // A cell taller than the grid:
        assertTrue(dropRefused("calendar", cell(1, 1, 12, 13), listOf("calendar"), emptyMap(), emptyMap()))
        // The same bounds against a configured grid:
        assertTrue(dropRefused("calendar", cell(7, 1, 1, 1), listOf("calendar"), emptyMap(), emptyMap(), 6, 12))
        assertTrue(dropRefused("calendar", cell(1, 7, 1, 1), listOf("calendar"), emptyMap(), emptyMap(), 12, 6))
    }

    @Test
    fun dropRefusedNeverRefusesADropOfATileOntoItsOwnCurrentCell() {
        val geometry = mapOf("calendar" to cell(1, 1, 4, 2))
        assertFalse(dropRefused("calendar", cell(1, 1, 4, 2), ORDER, emptyMap(), geometry))
    }

    // ------------------------------------------------------------------
    // gridMetrics
    // ------------------------------------------------------------------

    @Test
    fun gridMetricsDerivesTheUnitSizesFromTheBoardBox() {
        // 12 columns of 100px with a 16px gap (1376px wide), 12 rows of
        // 150px (1976px tall).
        val m = gridMetrics(1376f, 1976f, rows = 12, gapPx = 16f)
        assertEquals(100f, m!!.colW, 0.001f)
        assertEquals(150f, m.rowH, 0.001f)
        assertEquals(16f, m.gap, 0.001f)
        // Degenerate box: a zero width cannot be measured.
        assertNull(gridMetrics(0f, 1976f, rows = 12, gapPx = 16f))
        assertNull(gridMetrics(1376f, 1976f, rows = 0, gapPx = 16f))
    }

    @Test
    fun renderedRowsFollowsTheLayoutPastTheGridRows() {
        assertEquals(12, renderedRows(autoLayout(ORDER, emptyMap()), 12))
        assertEquals(13, renderedRows(mapOf("calendar" to cell(1, 13, 6, 1)), 12))
    }

    // ------------------------------------------------------------------
    // board-file normalization (the web's utils.test.ts)
    // ------------------------------------------------------------------

    @Test
    fun theBoardGridDefaultsTo12x12AndTheUsableRangeIsOneTo24() {
        assertEquals(GridDims(12, 12), GRID_DEFAULTS)
        assertEquals(1, GRID_MIN)
        assertEquals(24, GRID_MAX)
    }

    @Test
    fun normalizeGridRepairsAStoredGrid() {
        assertEquals(GridDims(12, 12), normalizeGrid(null))
        assertEquals(GridDims(8, 5), normalizeGrid(GridDims(8, 5)))
        // An out-of-range dimension falls back per axis (freehand-editable
        // board file surface).
        assertEquals(GridDims(12, 4), normalizeGrid(GridDims(99, 4)))
        assertEquals(GridDims(6, 12), normalizeGrid(GridDims(6, 0)))
        assertEquals(GridDims(12, 12), normalizeGrid(GridDims(25, 25)))
    }

    @Test
    fun isValidGeometryAcceptsExactlyTheInBoundsCells() {
        assertTrue(isValidGeometry(cell(1, 1, 1, 1)))
        assertTrue(isValidGeometry(cell(12, 12, 1, 1)))
        assertTrue(isValidGeometry(cell(1, 1, 12, 12)))
        assertFalse(isValidGeometry(cell(0, 1, 1, 1)))
        assertFalse(isValidGeometry(cell(13, 1, 1, 1)))
        assertFalse(isValidGeometry(cell(1, 1, 0, 1)))
        assertFalse(isValidGeometry(cell(1, 1, 13, 1)))
        assertFalse(isValidGeometry(cell(2, 1, 12, 1))) // 2+12 > 12
        assertFalse(isValidGeometry(cell(1, 0, 1, 1)))
        assertFalse(isValidGeometry(cell(1, 13, 1, 1)))
        assertFalse(isValidGeometry(cell(1, 12, 1, 2))) // 12+2 > 12
        assertFalse(isValidGeometry(cell(1, 1, 1, 0)))
        assertFalse(isValidGeometry(cell(1, 1, 1, 13)))
        // The configured grid dimensions:
        assertFalse(isValidGeometry(cell(7, 1, 1, 1), 6, 12))
        assertTrue(isValidGeometry(cell(6, 1, 1, 1), 6, 12))
        assertFalse(isValidGeometry(cell(1, 7, 1, 1), 12, 6))
        assertTrue(isValidGeometry(cell(1, 6, 1, 1), 12, 6))
    }

    @Test
    fun rectsOverlapUsesTheHalfOpenIntervalTest() {
        assertFalse(rectsOverlap(cell(1, 1, 3, 1), cell(4, 1, 3, 1))) // edge to edge
        assertTrue(rectsOverlap(cell(1, 1, 3, 1), cell(3, 1, 3, 1)))
        assertFalse(rectsOverlap(cell(1, 1, 3, 1), cell(1, 2, 3, 1)))
        assertTrue(rectsOverlap(cell(1, 1, 3, 2), cell(1, 2, 3, 1)))
    }

    @Test
    fun normalizeGeometryKeepsValidEntriesAndDropsTheRest() {
        val out = normalizeGeometry(
            mapOf(
                "calendar" to cell(1, 1, 3, 1),
                "push" to cell(1, 1, 1, 1), // unknown tile id
            ),
            listOf("calendar", "chat", "home"),
            setOf("calendar", "chat", "home"),
        )
        assertEquals(mapOf("calendar" to cell(1, 1, 3, 1)), out)
    }

    @Test
    fun normalizeGeometryDropsOutOfBoundsEntriesAndOverlaps() {
        val out = normalizeGeometry(
            mapOf(
                "calendar" to cell(1, 1, 3, 1),
                "chat" to cell(2, 1, 3, 1), // overlaps calendar
                "home" to cell(13, 1, 1, 1), // out of bounds (past the right edge)
            ),
            listOf("calendar", "chat", "home"),
            setOf("calendar", "chat", "home"),
        )
        assertEquals(mapOf("calendar" to cell(1, 1, 3, 1)), out)
    }

    @Test
    fun normalizeGeometryDropsRowOutOfBoundsEntries() {
        val out = normalizeGeometry(
            mapOf(
                "calendar" to cell(1, 13, 1, 1), // below the bottom edge (12 rows)
                "chat" to cell(1, 12, 1, 2), // crosses the bottom edge
                "home" to cell(1, 12, 1, 1), // the bottom row itself is fine
            ),
            listOf("calendar", "chat", "home"),
            setOf("calendar", "chat", "home"),
        )
        assertEquals(mapOf("home" to cell(1, 12, 1, 1)), out)
    }

    @Test
    fun normalizeHATilesKeepsValidTilesAndDropsMalformedOnes() {
        val out = normalizeHATiles(
            listOf(
                HABoardTile(id = "ha-livingroom", entity = "light.living_room", kind = "button"),
                HABoardTile(id = "ha-temp", entity = "sensor.living_room_temperature", kind = "visual", label = "Temp"),
                HABoardTile(id = "nope", entity = "light.x", kind = "button"), // bad id shape
                HABoardTile(id = "ha-x", entity = "", kind = "button"), // empty entity
                HABoardTile(id = "ha-y", entity = "light.x", kind = "toggle"), // bad kind
                HABoardTile(id = "ha-livingroom", entity = "light.dup", kind = "button"), // duplicate id
            ),
        )
        assertEquals(
            listOf(
                HABoardTile(id = "ha-livingroom", entity = "light.living_room", kind = "button", label = null),
                HABoardTile(id = "ha-temp", entity = "sensor.living_room_temperature", kind = "visual", label = "Temp"),
            ),
            out,
        )
    }

    @Test
    fun isHATileIdRecognizesTheHaIdShape() {
        assertTrue(isHATileId("ha-1"))
        assertTrue(isHATileId("ha-livingroom"))
        assertFalse(isHATileId("ha-living_room"))
        assertFalse(isHATileId("ha-"))
        assertFalse(isHATileId("home"))
        assertFalse(isHATileId("HA-1"))
        assertFalse(isHATileId("ha-abcdefghijklmnopq"))
    }

    @Test
    fun haTileLabelPrefersTheTileLabelThenTheEntityLabel() {
        val entity = com.jsnto.dashhome.api.HAEntity(
            entityId = "light.living_room",
            label = "Living room",
            friendly = "Living Room",
            state = "off",
        )
        assertEquals("Living room", haTileLabel(HABoardTile(id = "ha-1", entity = "light.living_room", label = null, kind = "button"), entity))
        assertEquals("Ceiling", haTileLabel(HABoardTile(id = "ha-1", entity = "light.living_room", label = "Ceiling", kind = "button"), entity))
        assertEquals("light.kitchen", haTileLabel(HABoardTile(id = "ha-2", entity = "light.kitchen", label = null, kind = "button"), null))
    }

    @Test
    fun haTileStateUppercasesOnOffAndPassesEverythingElseThrough() {
        assertEquals("ON", haTileState("on"))
        assertEquals("OFF", haTileState("off"))
        assertEquals("19.5°C", haTileState("19.5°C"))
        assertEquals("unknown", haTileState("unknown"))
    }

    @Test
    fun normalizeViewsFillsDefaultsForAMissingSection() {
        val v = normalizeViews(null)
        assertEquals(ORDER, v.order)
        assertEquals(emptyList<String>(), v.hidden)
        assertEquals("month", v.calendarMode)
        assertEquals(4, v.sizes.size)
        v.sizes.values.forEach { assertEquals("m", it) }
        assertTrue(v.geometry.isEmpty())
        assertTrue(v.haTiles.isEmpty())
    }

    @Test
    fun normalizeViewsRepairsAMalformedSection() {
        val v = normalizeViews(
            BoardViews(
                order = listOf("calendar", "bogus", "calendar", "chat"), // dup + unknown
                hidden = listOf("push", "bogus"),
                calendarMode = "week", // not a mode
                sizes = mapOf("home" to "xl", "calendar" to "gigantic"),
                geometry = mapOf("calendar" to cell(40, 1, 6, 1)), // out of bounds
                haTiles = listOf(HABoardTile(id = "ha-1", entity = "light.x", label = null, kind = "button")),
            ),
        )
        assertEquals(listOf("calendar", "chat", "home", "push", "ha-1"), v.order)
        assertEquals(listOf("push"), v.hidden)
        assertEquals("month", v.calendarMode)
        assertEquals("xl", v.sizes["home"])
        assertEquals("m", v.sizes["calendar"])
        assertTrue(v.geometry.isEmpty())
        assertEquals(listOf(HABoardTile(id = "ha-1", entity = "light.x", label = null, kind = "button")), v.haTiles)
    }

    @Test
    fun normalizeViewsDropsHATileIdsThatAreNotValidTiles() {
        val v = normalizeViews(
            BoardViews(
                order = listOf("calendar", "ha-nope", "chat", "home", "push"),
                hidden = listOf("ha-nope"),
                calendarMode = "month",
                sizes = emptyMap(),
                geometry = emptyMap(),
                haTiles = emptyList(),
            ),
        )
        assertEquals(ORDER, v.order)
        assertEquals(emptyList<String>(), v.hidden)
    }

    @Test
    fun normalizeViewsKeepsAValidGeometryEntryForAnHATile() {
        val v = normalizeViews(
            BoardViews(
                order = listOf("calendar", "chat", "home", "push", "ha-1"),
                hidden = emptyList(),
                calendarMode = "month",
                sizes = emptyMap(),
                geometry = mapOf("ha-1" to cell(1, 3, 2, 2)),
                haTiles = listOf(HABoardTile(id = "ha-1", entity = "light.x", label = null, kind = "button")),
            ),
        )
        assertEquals(cell(1, 3, 2, 2), v.geometry["ha-1"])
    }

    @Test
    fun visibleOrderIsTheBoardOrderMinusTheHiddenTiles() {
        val v = BoardViews(
            order = listOf("calendar", "chat", "home", "push"),
            hidden = listOf("chat", "push"),
            calendarMode = "month",
            sizes = emptyMap(),
            geometry = emptyMap(),
            haTiles = emptyList(),
        )
        assertEquals(listOf("calendar", "home"), visibleOrder(v))
        assertEquals(ORDER, visibleOrder(null))
    }

    @Test
    fun viewLabelsLabelTheFourViews() {
        assertEquals("Calendar", VIEW_LABELS["calendar"])
        assertEquals("Hermes chat", VIEW_LABELS["chat"])
        assertEquals("Home", VIEW_LABELS["home"])
        assertEquals("Hermes push", VIEW_LABELS["push"])
    }

    @Test
    fun nextHATileIdFillsGapsLikeTheWeb() {
        assertEquals("ha-1", nextHATileId(emptyList()))
        assertEquals("ha-2", nextHATileId(listOf(HABoardTile(id = "ha-1", entity = "light.x", label = null, kind = "button"))))
        // A gap is filled: with ha-1 and ha-3 used the next id is ha-2.
        assertEquals("ha-2", nextHATileId(listOf(HABoardTile(id = "ha-1", entity = "light.x", label = null, kind = "button"), HABoardTile(id = "ha-3", entity = "light.y", label = null, kind = "visual"))))
    }

    @Test
    fun tileNameNamesBuiltInViewsAndHATiles() {
        val v = BoardViews(
            order = ORDER + "ha-1",
            hidden = emptyList(),
            calendarMode = "month",
            sizes = emptyMap(),
            geometry = emptyMap(),
            haTiles = listOf(HABoardTile(id = "ha-1", entity = "light.kitchen", label = "Kitchen light", kind = "button")),
        )
        assertEquals("Calendar", tileName("calendar", v))
        assertEquals("Kitchen light", tileName("ha-1", v))
        assertEquals("sensor.ambient", tileName("ha-9", BoardViews(ORDER + "ha-9", emptyList(), "month", emptyMap(), emptyMap(), listOf(HABoardTile(id = "ha-9", entity = "sensor.ambient", label = null, kind = "visual")))))
        assertEquals("mystery", tileName("mystery", BoardViews(ORDER, emptyList(), "month", emptyMap(), emptyMap(), emptyList())))
    }
}
