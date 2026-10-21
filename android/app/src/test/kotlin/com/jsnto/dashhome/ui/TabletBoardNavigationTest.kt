package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Navigation-structure pins for the tablet one-page board:
 *
 *  - a wide display (screenWidthDp >= 600) shows the one-page board as a
 *    single screen of live content tiles on the unit grid - the web
 *    dashboard's composition: the four live windows (Calendar, Hermes
 *    chat, Home, Hermes push) auto-flowed into the grid - with every
 *    tile's real section view rendered inline (no placeholder cards) and
 *    Settings reachable from the top bar;
 *  - tapping a tile's title (the maximize affordance, like the web's
 *    card-head title) opens that section's existing full-screen view
 *    (the wide Calendar layout, the Home embed view, the wide Hermes
 *    layout, or the Settings screen); the push tile's title resolves to
 *    the existing Hermes full-screen view, which carries the push feed;
 *    back returns to the board;
 *  - the board stays composed while a drill-down is open (it sits under
 *    the drill-down), so the board's live tiles - the HA WebView, the
 *    push poll, the calendar's month - survive the round trip without
 *    reload or re-prompt;
 *  - the board and the drill-down share the section's live state: a
 *    month navigated in the full-screen calendar is what the board's
 *    calendar tile shows when you return (and the drill-down opens on
 *    the month the tile was showing);
 *  - the phone layout keeps its bottom navigation bar and never shows
 *    the board.
 *
 * The tests point the app at an unreachable test server, so every
 * section settles into its deterministic offline state - no live
 * dashhome stack is needed.
 */
@RunWith(RobolectricTestRunner::class)
class TabletBoardNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Seeds the server the way MainActivity restores it on startup. */
    private fun connectServer() {
        Prefs.setBase(composeRule.activity, "http://127.0.0.1:1")
        DashApi.baseUrl = "http://127.0.0.1:1"
    }

    private fun startApp() {
        composeRule.setContent {
            DashhomeRoot()
        }
        composeRule.waitForIdle()
    }

    private fun tapBack() {
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
    }

    /**
     * Clicks the drill-down's month-advance "›": the still-mounted board
     * tile renders the same "›" too (a zero-height sliver in its
     * one-row-tall tile), so the tall one is the drill-down's. The touch
     * is dispatched through the drill-down surface's own interaction in
     * its local coordinate space (the performTouchInput coordinate space
     * is the target node's local system, mapped to the root by the
     * runtime).
     */
    private fun clickDrillDownNextMonth() {
        val arrow = composeRule.onAllNodesWithText("›").fetchSemanticsNodes()
            .first { it.size.height > 0 }
        val cx = arrow.positionInRoot.x + arrow.size.width / 2f
        val cy = arrow.positionInRoot.y + arrow.size.height / 2f
        val surf = composeRule.onNodeWithTag("drilldown-calendar").fetchSemanticsNode()
        composeRule.onNodeWithTag("drilldown-calendar").performTouchInput {
            down(Offset(cx - surf.positionInRoot.x, cy - surf.positionInRoot.y))
            up()
        }
        composeRule.waitForIdle()
    }

    // --- the one-page board of live content tiles -------------------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun wideDisplayShowsTheOnePageBoardWithLiveWindows() {
        connectServer()
        startApp()

        // The web board's four live tiles, auto-flowed into the unit
        // grid (calendar/chat on row 1, home/push on row 2 by default).
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        composeRule.onNodeWithTag("boardWin-chat").assertExists()
        composeRule.onNodeWithTag("boardWin-home").assertExists()
        composeRule.onNodeWithTag("boardWin-push").assertExists()

        // Each tile carries its card-head title (the maximize
        // affordance, mirroring the web's clickable h2).
        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Hermes chat").assertExists()
        composeRule.onNodeWithText("Home").assertExists()
        composeRule.onNodeWithText("Hermes push").assertExists()

        // Settings is reachable from the board's top bar (the web header
        // carries its Settings button the same way), as is edit mode.
        composeRule.onNodeWithContentDescription("Settings").assertExists()
        composeRule.onNodeWithText("Edit tiles").assertExists()

        // The tiles render the real live section content, not
        // placeholder cards: the chat pane shows its real thread note,
        // and the calendar/home/push sections settle on their error
        // notes against the unreachable test server.
        composeRule
            .onNodeWithText("Say hi to Hermes — the conversation thread stays on this page.")
            .assertExists()
        composeRule.waitUntil(15_000) {
            composeRule
                .onAllNodesWithText("network", substring = true)
                .fetchSemanticsNodes()
                .size >= 3
        }

        // No drill-down view is open until a tile title is tapped.
        composeRule.onNodeWithTag("drilldown-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("drilldown-home").assertDoesNotExist()
        composeRule.onNodeWithTag("drilldown-hermes").assertDoesNotExist()
        composeRule.onNodeWithTag("drilldown-settings").assertDoesNotExist()
    }

    // --- title tap: drill-down and back ------------------------------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingTheCalendarTitleOpensTheFullScreenCalendarAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        composeRule.onNodeWithTag("boardTitle-calendar").performClick()
        composeRule.waitForIdle()

        // The drill-down wraps the existing full-screen calendar view:
        // back app bar title plus the month navigation row. The section
        // title is visible twice - the (still mounted) board tile and
        // the drill-down's back bar - as is the Today button.
        composeRule.onNodeWithTag("drilldown-calendar").assertExists()
        composeRule.onAllNodesWithText("Calendar").assertCountEquals(2)
        composeRule.onAllNodesWithText("Today").assertCountEquals(2)
        // The board stays composed under the drill-down, so its live
        // tiles survive the round trip.
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        composeRule.onNodeWithTag("boardWin-home").assertExists()
        // The test server is unreachable; the view settles on its error
        // note, which proves the real calendar section is live here.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("network", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        tapBack()

        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        composeRule.onNodeWithTag("drilldown-calendar").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingTheHomeTitleOpensTheFullScreenHomeViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        composeRule.onNodeWithTag("boardTitle-home").performClick()
        composeRule.waitForIdle()

        // The drill-down wraps the existing full-screen Home view (the
        // WebView embed section), with the title in the back app bar
        // instead of the content row - so "Home" is visible in the still
        // mounted board tile and the back bar.
        composeRule.onNodeWithTag("drilldown-home").assertExists()
        composeRule.onAllNodesWithText("Home").assertCountEquals(2)
        // The board stays composed under the drill-down, so the board's
        // own Home tile (and its live WebView on a reachable server)
        // survives the round trip without reload.
        composeRule.onNodeWithTag("boardWin-home").assertExists()
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        // The embed fetch runs against the unreachable test server and
        // the view settles on its error note.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("network", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        tapBack()

        composeRule.onNodeWithTag("boardWin-home").assertExists()
        composeRule.onNodeWithTag("drilldown-home").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingTheChatTitleOpensTheFullScreenHermesViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        composeRule.onNodeWithTag("boardTitle-chat").performClick()
        composeRule.waitForIdle()

        // The drill-down wraps the existing wide Hermes view: the back
        // app bar title plus the chat and push panes side by side. The
        // back bar's "Hermes" title is the only exact "Hermes" node
        // (the board tiles are titled "Hermes chat" / "Hermes push").
        composeRule.onNodeWithTag("drilldown-hermes").assertExists()
        composeRule.onAllNodesWithText("Hermes").assertCountEquals(1)
        composeRule.onNodeWithText("Chat").assertExists()
        composeRule.onNodeWithText("Push feed").assertExists()
        // The board stays composed under the drill-down.
        composeRule.onNodeWithTag("boardWin-chat").assertExists()
        composeRule.onNodeWithTag("boardWin-push").assertExists()
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        // The chat thread note shows in both the still mounted board
        // chat tile and the drill-down's chat pane (the thread is shared
        // at the root).
        composeRule
            .onAllNodesWithText("Say hi to Hermes — the conversation thread stays on this page.")
            .assertCountEquals(2)

        tapBack()

        composeRule.onNodeWithTag("boardWin-chat").assertExists()
        composeRule.onNodeWithTag("drilldown-hermes").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingThePushTitleOpensTheExistingFullScreenHermesViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        // The push tile's content is the push feed; its existing
        // full-screen view is the Hermes section (chat + push side by
        // side), so the push tile's title resolves to it.
        composeRule.onNodeWithTag("boardTitle-push").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("drilldown-hermes").assertExists()
        composeRule.onNodeWithText("Push feed").assertExists()
        // The board stays composed under the drill-down.
        composeRule.onNodeWithTag("boardWin-push").assertExists()

        tapBack()

        composeRule.onNodeWithTag("boardWin-push").assertExists()
        composeRule.onNodeWithTag("drilldown-hermes").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingTheSettingsActionOpensTheFullScreenSettingsViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        // Settings is carried in the board's top bar (like the phone's
        // and the web header's); it opens the existing Settings screen,
        // which brings its own back app bar and system-back handling.
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("drilldown-settings").assertExists()
        composeRule.onNodeWithText("Server").assertExists()
        composeRule.onNodeWithText("Danger zone").assertExists()

        tapBack()

        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        composeRule.onNodeWithTag("drilldown-settings").assertDoesNotExist()
    }

    // --- shared state: the board and the drill-down share the live section -

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun theBoardTileFollowsTheMonthNavigatedInTheDrillDown() {
        // A reachable in-test server: the calendar settles on a clean
        // (empty) month instead of its error note.
        val server = FakeDashServer()
        try {
            DashApi.baseUrl = server.baseUrl
            Prefs.setBase(composeRule.activity, server.baseUrl)
            composeRule.setContent { DashhomeRoot() }
            composeRule.waitForIdle()

            val nextMonth = LocalDate.now().plusMonths(1)
                .format(DateTimeFormatter.ofPattern("MMMM yyyy"))

            // Open the calendar drill-down and advance it to the next
            // month with the full-screen view's own month navigation
            // (the board tile's own › is a sliver in the one-row-tall
            // tile, like the web's zero-height card-scroll; the shared
            // state is the point).
            composeRule.onNodeWithTag("boardTitle-calendar").performClick()
            composeRule.waitForIdle()
            clickDrillDownNextMonth()

            // Both the still-mounted board tile and the drill-down show
            // the advanced month: the section state they share moved.
            composeRule.onNodeWithTag("drilldown-calendar").assertExists()
            composeRule.onAllNodesWithText(nextMonth).assertCountEquals(2)

            tapBack()

            // Back returns to the board, still on that month.
            composeRule.onNodeWithTag("boardWin-calendar").assertExists()
            composeRule.onNodeWithText(nextMonth).assertExists()
            composeRule.onNodeWithTag("drilldown-calendar").assertDoesNotExist()
        } finally {
            server.stop()
        }
    }

    // --- the phone layout is untouched --------------------------------------

    @Config(sdk = [35], qualifiers = "w400dp-h800dp")
    @Test
    fun phoneKeepsBottomNavigationAndTheBoardIsNotShown() {
        connectServer()
        startApp()

        // Phone routing is unchanged: the bottom navigation bar carries
        // the three sections and the top bar carries the settings icon.
        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Home").assertExists()
        composeRule.onNodeWithText("Hermes").assertExists()
        composeRule.onNodeWithContentDescription("Settings").assertExists()

        // The tablet board does not leak into the phone layout.
        composeRule.onNodeWithTag("boardWin-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-home").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-chat").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-push").assertDoesNotExist()
    }
}
