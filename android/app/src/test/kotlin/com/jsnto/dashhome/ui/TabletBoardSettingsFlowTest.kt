package com.jsnto.dashhome.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The settings-driven board behaviors, end to end on the real UI: the
 * app runs against the in-test FakeDashServer (which records every
 * request and follows the backend's merge semantics), so each test
 * drives the actual screen a user would - Settings → Tiles / Appearance
 * / Home Assistant - and asserts both what the app sent and what the
 * board underneath then renders:
 *
 *  - the Calendar tile's month-grid vs schedule-summary toggle: picked
 *    in Settings → Tiles, saved through PUT /api/config/views, and
 *    reflected in the board's calendar tile in the same session (the
 *    board adopts the saved mode - no stale mount-time fetch);
 *  - the light/dark theme pick in Settings → Appearance: persisted
 *    client-side (SharedPreferences, the web's localStorage mirror),
 *    reflected in the tabs live, and re-resolved over the OS preference
 *    by a fresh app composition (startup);
 *  - the per-tile settings modal: a tap on the card never closes it
 *    (the web's pressOnBackdrop guard), a scrim tap does, and a size
 *    save persists through the board-views path and re-lays the board;
 *  - the board grid dimensions saved from Settings → Tiles (PUT
 *    /api/config/grid);
 *  - HA board tiles added from Settings → Home Assistant: each default-
 *    placed at the first free 2x2 cell (the second add in the same
 *    session sees the first's saved cell, not the same one), wearing
 *    the entity's label on the board.
 */
@RunWith(RobolectricTestRunner::class)
class TabletBoardSettingsFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

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

    private fun openSettings() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
    }

    private fun tapBack() {
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
    }

    private fun lastBody(path: String): JSONObject? {
        val body = server.lastBodyTo(path) ?: return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    // --- the Calendar tile's month-grid vs schedule-summary toggle ----------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun calendarModePickInSettingsSwapsTheBoardTile() {
        // A configured (but event-free) calendar, so the section renders
        // its real month grid / schedule summary instead of the
        // no-calendars note.
        server = FakeDashServer().also {
            it.calendarsJson = """[{"id": "test@cal.com", "summary": "Test", "label": "Test", "primary": true}]"""
        }
        DashApi.baseUrl = server.baseUrl
        Prefs.setBase(composeRule.activity, server.baseUrl)
        composeRule.setContent {
            DashhomeRoot()
        }
        composeRule.waitForIdle()
        waitForBoardLoad()

        // The default mode is the month grid: the board's calendar tile
        // shows the current month and its selected day's (empty) events.
        val month = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        composeRule.onNodeWithText(month).assertExists()
        composeRule.onNodeWithText("No events.").assertExists()
        composeRule.onNodeWithText("No upcoming events in this range.").assertDoesNotExist()

        // Settings → Tiles → the schedule-summary mode (like the web's
        // settings panel, the pick saves immediately).
        openSettings()
        composeRule.onNodeWithTag("drilldown-settings").assertExists()
        composeRule.onNodeWithText("Tiles").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Calendar view").assertExists()
        composeRule.onNodeWithTag("calendarMode-schedule").performClick()

        // The mode persisted server-side through the board-views path.
        composeRule.waitUntil(10_000) {
            server.lastBodyTo("/api/config/views")?.contains(""""calendar_mode":"schedule"""") == true
        }

        // Back to the board: the tile re-renders the schedule summary -
        // the board adopted the saved mode in the same session (no
        // restart, no stale mount-time fetch).
        tapBack()
        composeRule.onNodeWithTag("drilldown-settings").assertDoesNotExist()
        composeRule.onNodeWithText("No upcoming events in this range.").assertExists()
        composeRule.onNodeWithText("No events.").assertDoesNotExist()
    }

    // --- the light/dark theme pick, persisted and re-applied -----------------

    /** The chip's selection state (TabChip exposes SemanticsProperties.Selected). */
    private fun selected(tag: String): Boolean {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Selected) == true
    }

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun themePickFromSettingsPersistsClientSide() {
        startApp()

        // Settings → Appearance: no explicit pick yet, so the effective
        // theme (the light OS preference) is the selected tab, and
        // nothing is persisted.
        assertNull(Prefs.theme(composeRule.activity))
        openSettings()
        composeRule.onNodeWithText("Theme").assertExists()
        assertTrue(selected("themeTab-light"))
        assertFalse(selected("themeTab-dark"))

        // Pick Dark: the pick persists client-side (SharedPreferences,
        // the web's localStorage mirror) and the tab reflects it live.
        composeRule.onNodeWithTag("themeTab-dark").performClick()
        composeRule.waitForIdle()
        assertEquals("dark", Prefs.theme(composeRule.activity))
        assertTrue(selected("themeTab-dark"))
        assertFalse(selected("themeTab-light"))
    }

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun persistedThemePickWinsOverOsPreferenceOnStartup() {
        // A pick persisted by a previous session (Robolectric's OS
        // preference is light): the app's startup composition re-reads
        // it and the explicit choice wins.
        Prefs.setTheme(composeRule.activity, "dark")
        startApp()

        openSettings()
        assertTrue(selected("themeTab-dark"))
        assertFalse(selected("themeTab-light"))
    }

    // --- the per-tile settings modal -----------------------------------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun tileEditModalCardTapKeepsItOpenAndScrimTapClosesIt() {
        startApp()

        // Edit tiles mode → the calendar tile's settings button.
        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("boardEdit-calendar").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tileEditModal").assertExists()

        // A tap on a non-interactive part of the card (its description
        // text) never closes the modal: the card absorbs taps, so an
        // unsaved draft survives the misclick (the web's
        // pressOnBackdrop guard).
        composeRule
            .onNodeWithText("keeps its own span", substring = true)
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tileEditModal").assertExists()

        // A tap on the scrim, outside the card, closes it: dispatch a
        // touch at the top-bar "Edit tiles" node - above the centered
        // card, so the topmost layer there is the scrim itself.
        composeRule.onNodeWithText("Edit tiles").performTouchInput {
            down(Offset(2f, 2f))
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tileEditModal").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun tileEditModalSizeSavePersistsAndRelayoutsTheBoard() {
        startApp()

        val calendarWidth = size("boardWin-calendar").width
        val boardWidth = size("boardLayout").width
        assertTrue(calendarWidth < boardWidth)

        // Edit tiles mode → the calendar tile's modal → Full width → Save.
        composeRule.onNodeWithText("Edit tiles").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("boardEdit-calendar").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tileEditModal").assertExists()
        composeRule.onNodeWithText("Full width").performClick()
        composeRule.onNodeWithText("Save").performClick()

        // The size step persisted through the board-views path (the
        // per-tile sizes merge).
        composeRule.waitUntil(10_000) {
            lastBody("/api/config/views")
                ?.optJSONObject("sizes")?.optString("calendar") == "xl"
        }

        // A successful save closes the modal, and the board re-lays the
        // calendar tile across the full grid width.
        composeRule.onNodeWithTag("tileEditModal").assertDoesNotExist()
        assertTrue(size("boardWin-calendar").width >= size("boardLayout").width - 4f)
    }

    // --- HA board tiles from settings ----------------------------------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun haTileAddsFromSettingsLandOnSuccessiveFreeCellsAndWearTheEntityLabel() {
        server = FakeDashServer().also {
            it.haEntitiesJson = """
                [
                  {"entity_id": "light.kitchen", "label": "Kitchen", "friendly": "Kitchen Light", "state": "off"},
                  {"entity_id": "light.bedroom", "label": "Bedroom", "friendly": "Bedroom light", "state": "on"}
                ]
            """.trimIndent()
        }
        DashApi.baseUrl = server.baseUrl
        Prefs.setBase(composeRule.activity, server.baseUrl)
        composeRule.setContent {
            DashhomeRoot()
        }
        composeRule.waitForIdle()
        waitForBoardLoad()

        // Settings → Home Assistant: the entity picker lists the
        // entities HA reports.
        openSettings()
        composeRule.onNodeWithText("Home Assistant").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Entity").assertExists()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Kitchen").fetchSemanticsNodes().isNotEmpty()
        }

        // Add the first tile: it default-places at the first free 2x2
        // cell of the board's effective layout (rows 1-2 hold the four
        // auto-flowed views, so (1,3)). The entity chip comes first in
        // the tree (the Label field's "Kitchen" placeholder follows).
        composeRule.onAllNodesWithText("Kitchen").onFirst().performClick()
        composeRule.onNodeWithText("Add tile").performClick()
        composeRule.waitUntil(15_000) {
            val b = lastBody("/api/config/views") ?: return@waitUntil false
            b.optJSONArray("ha_tiles")?.optJSONObject(0)?.optString("entity") == "light.kitchen"
        }
        var body = lastBody("/api/config/views")!!
        val g1 = body.getJSONObject("geometry").getJSONObject("ha-1")
        assertEquals(1, g1.getInt("x"))
        assertEquals(3, g1.getInt("y"))
        assertEquals(2, g1.getInt("w"))
        assertEquals(2, g1.getInt("h"))

        // Add the second tile in the same session: it lands at the NEXT
        // free cell, not the first's (the screen re-derives from the
        // board it last adopted, so the first add's saved cell is an
        // obstacle - the web's re-derive-after-save).
        composeRule.onNodeWithText("Bedroom").performClick()
        composeRule.onNodeWithText("Add tile").performClick()
        composeRule.waitUntil(15_000) {
            val b = lastBody("/api/config/views") ?: return@waitUntil false
            b.optJSONArray("ha_tiles")?.optJSONObject(1)?.optString("entity") == "light.bedroom"
        }
        body = lastBody("/api/config/views")!!
        val g2 = body.getJSONObject("geometry").getJSONObject("ha-2")
        assertEquals(3, g2.getInt("x"))
        assertEquals(3, g2.getInt("y"))
        assertEquals(2, g2.getInt("w"))
        assertEquals(2, g2.getInt("h"))

        // Back to the board: both tiles render, wearing the entities'
        // labels (tile label, else the entity's own label - the web's
        // haTileLabel) in the tile titles. (The entity id still shows in
        // each tile's footer note, the web's .ha-entity note.)
        tapBack()
        composeRule.onNodeWithTag("boardWin-ha-1").assertExists()
        composeRule.onNodeWithTag("boardWin-ha-2").assertExists()
        composeRule.onNodeWithContentDescription("Kitchen tile").assertExists()
        composeRule.onNodeWithContentDescription("Bedroom tile").assertExists()
    }

    // --- the HA entity poll refreshes on foregrounding -----------------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun haEntitiesRefreshWhenTheAppRegainsVisibility() {
        server = FakeDashServer().also {
            it.haEntitiesJson = """
                [
                  {"entity_id": "light.kitchen", "label": "Kitchen", "friendly": "Kitchen Light", "state": "off"}
                ]
            """.trimIndent()
        }
        DashApi.baseUrl = server.baseUrl
        Prefs.setBase(composeRule.activity, server.baseUrl)
        composeRule.setContent {
            DashhomeRoot()
        }
        composeRule.waitForIdle()
        waitForBoardLoad()

        // Settings → Home Assistant: add a tile so an HA tile is visible
        // (the poll's active gate).
        openSettings()
        composeRule.onNodeWithText("Home Assistant").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Kitchen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Kitchen").onFirst().performClick()
        composeRule.onNodeWithText("Add tile").performClick()
        composeRule.waitUntil(15_000) {
            val b = lastBody("/api/config/views") ?: return@waitUntil false
            b.optJSONArray("ha_tiles")?.optJSONObject(0)?.optString("entity") == "light.kitchen"
        }
        tapBack()
        composeRule.onNodeWithTag("boardWin-ha-1").assertExists()

        // The board has polled the entity states (the adopt load).
        composeRule.waitUntil(10_000) {
            server.requestsTo("/api/ha/entities").isNotEmpty()
        }
        val pollsBefore = server.requestsTo("/api/ha/entities").size

        // The app backgrounds and regains visibility: the entity states
        // refresh immediately, not on the next 10 s tick (the web's
        // visibilitychange reload).
        val registry = composeRule.activity.lifecycle as LifecycleRegistry
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        composeRule.waitUntil(5_000) {
            server.requestsTo("/api/ha/entities").size > pollsBefore
        }
    }

    // --- the board grid dimensions --------------------------------------------

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun boardGridSavePersistsTheGridDimensions() {
        startApp()

        // Settings → Tiles → Board grid: change the rows and save.
        openSettings()
        composeRule.onNodeWithText("Tiles").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Board grid").assertExists()
        // The grid form's second field is the rows one (columns, rows).
        val rowsField = composeRule
            .onAllNodes(
                SemanticsMatcher("editable text field") {
                    it.config.getOrNull(SemanticsProperties.IsEditable) == true
                },
            )
            .onLast()
        rowsField.performTextClearance()
        rowsField.performTextInput("10")
        composeRule.onNodeWithText("Save").performClick()

        // The dimensions persisted through the grid endpoint (the fake
        // board's 12x8 grid becomes 12x10), and the screen reports it.
        composeRule.waitUntil(10_000) {
            lastBody("/api/config/grid")?.optInt("rows", -1) == 10
        }
        assertEquals(10, server.boardJson.getJSONObject("grid").getInt("rows"))
        composeRule.onNodeWithText("Saved — the board grid is now 12 by 10 units.").assertExists()
    }
}
