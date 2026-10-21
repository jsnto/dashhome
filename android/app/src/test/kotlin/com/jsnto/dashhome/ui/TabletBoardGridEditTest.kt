package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tablet board's Edit-tiles mode: the per-tile controls (the move
 * grip, the tile-settings button, the remove button, the corner resize
 * handle) and the drag/resize pointer path — the grip/resize handle
 * snaps to the board's unit grid (the port of the web's tileGrid.ts
 * pointer math), a refused drop (onto another tile) snaps back without
 * a save, and an accepted drop/resize saves through PUT
 * /api/config/views, adopting the server's answer. The tests run
 * against the in-test FakeDashServer, which records the writes.
 *
 * The drag helper dispatches its touch on the root node, so the
 * synthesized finger holds its root-space position while the preview
 * moves the tile (a real finger's travel, the web's client coordinates);
 * a drag in the handle's own local space would ride the moving tile and
 * double its own travel.
 */
@RunWith(RobolectricTestRunner::class)
class TabletBoardGridEditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: FakeDashServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private fun startApp() {
        server = FakeDashServer()
        DashApi.baseUrl = server.baseUrl
        Prefs.setBase(composeRule.activity, server.baseUrl)
        composeRule.setContent {
            DashhomeRoot()
        }
        composeRule.waitForIdle()
        waitForBoardLoad()
    }

    /** Wait until the board has adopted its first GET /api/config: the
     *  fake board's 8-row grid gives a ~99px tile (the app's 12-row
     *  default, shown before the load lands, gives a 61px one). */
    private fun waitForBoardLoad() {
        composeRule.waitUntil(10_000) { size("boardWin-calendar").height > 80 }
    }

    private fun size(tag: String) =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().size

    private fun rootX(tag: String): Float =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x

    private fun rootY(tag: String): Float =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    /** The horizontal pitch of one grid unit (unit width + the 16px gap). */
    private fun pitchX(): Float {
        val density = composeRule.activity.resources.displayMetrics.density
        val gap = 16f * density
        val unitW = (size("boardLayout").width - 11f * gap) / 12f
        return unitW + gap
    }

    private fun pitchY(): Float {
        val density = composeRule.activity.resources.displayMetrics.density
        val gap = 16f * density
        // The fake board's grid has 8 rows.
        val unitH = (size("boardLayout").height - 7f * gap) / 8f
        return unitH + gap
    }

    /**
     * Drag [tag] (its own center) by (dx, dy) px, in small steps, in the
     * root coordinate space (the touch rides the root node): the finger
     * holds its root position as the preview moves the tile.
     */
    private fun drag(tag: String, dx: Float, dy: Float) {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val start = node.positionInRoot + Offset(node.size.width / 2f, node.size.height / 2f)
        val steps = 20
        composeRule.onRoot().performTouchInput {
            down(start)
            for (i in 1..steps) {
                moveTo(start + Offset(dx * i / steps, dy * i / steps))
            }
            up()
        }
        composeRule.waitForIdle()
    }

    // --- edit mode shows the per-tile controls -----------------------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun enteringEditModeShowsThePerTileControls() {
        startApp()

        // Before edit mode: no grips, no handles.
        composeRule.onNodeWithTag("boardGrip-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("boardResize-calendar").assertDoesNotExist()

        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()

        // Every visible tile carries the web's tile-edit row (the move
        // grip, the settings button, the remove button) and the corner
        // resize handle.
        for (id in listOf("calendar", "chat", "home", "push")) {
            composeRule.onNodeWithTag("boardEditRow-$id").assertExists()
            composeRule.onNodeWithTag("boardGrip-$id").assertExists()
            composeRule.onNodeWithTag("boardEdit-$id").assertExists()
            composeRule.onNodeWithTag("boardRemove-$id").assertExists()
            composeRule.onNodeWithTag("boardResize-$id").assertExists()
        }

        // The per-tile settings modal opens from the settings button
        // (the web's tile edit modal), carrying the tile's name and the
        // Save/Cancel actions.
        composeRule.onNodeWithTag("boardEdit-calendar").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    // --- dragging a grip moves the tile and saves the geometry --------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun draggingAGripMovesTheTileAndSavesTheGeometry() {
        startApp()

        val beforeY = rootY("boardWin-calendar")
        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()

        // Row 1 is full (calendar 1-6, chat 7-12), so the move goes down:
        // two rows into the empty space below.
        drag("boardGrip-calendar", 0f, 2 * pitchY())

        // The drop saved through the board-views geometry patch (the
        // calendar's default cell (1,1,6,1) moved two rows to (1,3,6,1)).
        var body: String? = null
        composeRule.waitUntil(15_000) {
            val b = server.lastBodyTo("/api/config/views")
            b != null && JSONObject(b).optJSONObject("geometry")?.optJSONObject("calendar")?.optInt("y", -1) == 3
        }
        server.lastBodyTo("/api/config/views")?.let { b ->
            if (JSONObject(b).optJSONObject("geometry")?.optJSONObject("calendar")?.optInt("y", -1) == 3) body = b
        }
        body?.let {
            val cell = JSONObject(it).getJSONObject("geometry").getJSONObject("calendar")
            assertEquals(1, cell.getInt("x"))
            assertEquals(3, cell.getInt("y"))
            assertEquals(6, cell.getInt("w"))
            assertEquals(1, cell.getInt("h"))
        }
        org.junit.Assert.assertNotNull("the geometry save never reached the server", body)

        // The board adopted the server's answer: the tile now sits two
        // rows lower.
        composeRule.waitForIdle()
        assertEquals(beforeY + 2 * pitchY(), rootY("boardWin-calendar"), 4f)
    }

    // --- a second drag in the same session uses the adopted layout ---------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun secondDragInSameSessionUsesTheAdoptedLayout() {
        startApp()

        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()
        val beforeY = rootY("boardWin-push")

        // First drag: push (7,2,6,1) down two rows to (7,4,6,1). The save is
        // adopted, so the board re-derives its layout from the new answer.
        drag("boardGrip-push", 0f, 2f * pitchY())
        composeRule.waitUntil(15_000) {
            rootY("boardWin-push") > beforeY + 1.5f * pitchY()
        }

        // Second drag, same edit session: push down one more row. Its pointer
        // math must read the ADOPTED layout (push now at (7,4)) - not the
        // stale edit-entry layout (push at (7,2)), which would save (7,3).
        val savesBefore = server.requestsTo("/api/config/views").size
        drag("boardGrip-push", 0f, pitchY())
        composeRule.waitUntil(15_000) {
            server.requestsTo("/api/config/views").size > savesBefore
        }

        val cell = JSONObject(server.lastBodyTo("/api/config/views")!!)
            .getJSONObject("geometry").getJSONObject("push")
        assertEquals(7, cell.getInt("x"))
        assertEquals(5, cell.getInt("y"))
        assertEquals(6, cell.getInt("w"))
        assertEquals(1, cell.getInt("h"))
    }

    // --- a board write attempted before the first config is a no-op --------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun preloadBoardWriteIsANoOp() {
        // Hold the first GET /api/config so the board stays pre-load
        // (apiConfig null) while the UI is interactive: the config load
        // runs on Dispatchers.IO, so the main looper is free to process a
        // drop during the (unanswered) round-trip.
        server = FakeDashServer()
        server.holdConfig = true
        DashApi.baseUrl = server.baseUrl
        Prefs.setBase(composeRule.activity, server.baseUrl)
        composeRule.setContent {
            DashhomeRoot()
        }
        composeRule.waitForIdle()

        // The board renders its default layout pre-load; edit mode is
        // reachable during the startup load window.
        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()

        // A drop attempted while the first config is still outstanding is a
        // no-op (the web's `if (!cfg) return` in commitViews): no board
        // write goes out at all. The pre-load grid is the 12-row default.
        val density = composeRule.activity.resources.displayMetrics.density
        val gap = 16f * density
        val unitH = (size("boardLayout").height - 11f * gap) / 12f
        drag("boardGrip-calendar", 0f, 2f * (unitH + gap))

        // An unguarded save goes out on Dispatchers.IO, so give any write a
        // bounded window to reach the server; with the guard none is issued,
        // so the window elapses with nothing recorded.
        var writeIssued = false
        try {
            composeRule.waitUntil(1500) {
                server.requestsTo("/api/config/views").isNotEmpty()
            }
            writeIssued = true
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            writeIssued = false
        }
        org.junit.Assert.assertFalse(
            "a board write attempted before the first config must be a no-op",
            writeIssued,
        )
    }

    // --- a refused drop snaps back without a save ---------------------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun draggingOntoAnotherTileIsRefusedAndSnapsBack() {
        startApp()

        val before = rootX("boardWin-calendar")
        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()

        // The chat tile's auto cell is (7,1): dragging the calendar six
        // columns right lands on it - the drop is refused (overlap) and
        // the tile snaps back to its stored placement with no save.
        drag("boardGrip-calendar", 6 * pitchX(), 0f)

        // No geometry write went out at all...
        org.junit.Assert.assertTrue(server.requestsTo("/api/config/views").isEmpty())
        // ...and the tile is back on its original cell.
        assertEquals(before, rootX("boardWin-calendar"), 4f)
    }

    // --- the resize handle grows the tile and saves the span ----------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun resizingWithTheCornerHandleChangesTheTileSpan() {
        startApp()

        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()

        val before = size("boardWin-push").height
        // A near-vertical drag: the x delta rounds to zero columns (the
        // push tile's six columns already run to the board's right edge,
        // so a width growth would clamp back), the y delta is one row.
        drag("boardResize-push", 0.3f * pitchX(), pitchY())

        // The push tile's default cell (7,2,6,1) grew one unit in each
        // direction to (7,2,6,2), saved through the geometry patch.
        var body: String? = null
        composeRule.waitUntil(15_000) {
            val b = server.lastBodyTo("/api/config/views")
            b != null && JSONObject(b).optJSONObject("geometry")?.optJSONObject("push")?.optInt("h", -1) == 2
        }
        server.lastBodyTo("/api/config/views")?.let { b ->
            if (JSONObject(b).optJSONObject("geometry")?.optJSONObject("push")?.optInt("h", -1) == 2) body = b
        }
        body?.let {
            val cell = JSONObject(it).getJSONObject("geometry").getJSONObject("push")
            assertEquals(7, cell.getInt("x"))
            assertEquals(2, cell.getInt("y"))
            assertEquals(6, cell.getInt("w"))
            assertEquals(2, cell.getInt("h"))
        }
        org.junit.Assert.assertNotNull("the geometry save never reached the server", body)

        // The adopted answer stretched the tile's height by one unit
        // (a unit row plus the gap).
        composeRule.waitForIdle()
        val density = composeRule.activity.resources.displayMetrics.density
        val gap = 16f * density
        val unitH = (size("boardLayout").height - 7f * gap) / 8f
        assertEquals(before + unitH + gap, size("boardWin-push").height.toFloat(), 4f)
    }

    // --- leaving edit mode clears the per-tile controls ---------------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun leavingEditModeClearsThePerTileControls() {
        startApp()

        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("boardGrip-calendar").assertExists()

        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("boardGrip-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("boardResize-calendar").assertDoesNotExist()
        composeRule.onNodeWithText("Edit tiles").assertExists()
    }
}
