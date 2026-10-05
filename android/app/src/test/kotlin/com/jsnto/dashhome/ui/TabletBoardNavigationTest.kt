package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import com.jsnto.dashhome.ui.theme.DashhomeTheme
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
 *    single screen of live content windows - the web dashboard's
 *    composition: Calendar over the Hermes chat panel in the main column,
 *    the Home/HA embed over the Hermes push panel in the side column -
 *    with every window's real section view rendered inline (no
 *    placeholder cards) and Settings reachable from the top bar;
 *  - tapping a window's title (the maximize affordance, like the web's
 *    card-head title) opens that section's existing full-screen view
 *    (the wide Calendar layout, the Home embed view, the wide Hermes
 *    layout, or the Settings screen); the push window's title resolves to
 *    the existing Hermes full-screen view, which carries the push feed;
 *    back returns to the board;
 *  - the board stays composed while a drill-down is open (it sits under
 *    the drill-down), so the board's live windows - the HA WebView, the
 *    push poll, the calendar's month - survive the round trip without
 *    reload or re-prompt;
 *  - the drill-down opens on exactly the state the tapped board window
 *    was showing (the shared section state the board and the drill-down
 *    both read and write);
 *  - the phone layout keeps its bottom navigation bar and never shows the
 *    board.
 *
 * The tests point the app at an unreachable test server, so every section
 * settles into its deterministic offline state - no live dashhome stack is
 * needed.
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
            DashhomeTheme {
                DashhomeRoot()
            }
        }
        composeRule.waitForIdle()
    }

    private fun tapBack() {
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
    }

    // --- the one-page board of live content windows -----------------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun wideDisplayShowsTheOnePageBoardWithLiveWindows() {
        connectServer()
        startApp()

        // The web board's four windows, composed in two columns: main =
        // Calendar over the Hermes chat panel, side = Home over the
        // Hermes push panel.
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        composeRule.onNodeWithTag("boardWin-hermes").assertExists()
        composeRule.onNodeWithTag("boardWin-home").assertExists()
        composeRule.onNodeWithTag("boardWin-push").assertExists()

        // Each window carries its card-head title (the maximize
        // affordance, mirroring the web's clickable h2).
        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Hermes").assertExists()
        composeRule.onNodeWithText("Home").assertExists()
        composeRule.onNodeWithText("Hermes push").assertExists()

        // Settings is reachable from the board's top bar (the web header
        // carries its Settings button the same way).
        composeRule.onNodeWithContentDescription("Settings").assertExists()

        // The windows render the real live section content, not
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

        // No drill-down view is open until a window title is tapped.
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
        // title is visible twice - the (still mounted) board window and
        // the drill-down's back bar - as is the Today button.
        composeRule.onNodeWithTag("drilldown-calendar").assertExists()
        composeRule.onAllNodesWithText("Calendar").assertCountEquals(2)
        composeRule.onAllNodesWithText("Today").assertCountEquals(2)
        // The board stays composed under the drill-down, so its live
        // windows survive the round trip.
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
        // mounted board window and the back bar.
        composeRule.onNodeWithTag("drilldown-home").assertExists()
        composeRule.onAllNodesWithText("Home").assertCountEquals(2)
        // The board stays composed under the drill-down, so the board's
        // own Home window (and its live WebView on a reachable server)
        // survives the round trip without reload.
        composeRule.onNodeWithTag("boardWin-home").assertExists()
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        // The embed fetch runs against the unreachable test server and the
        // view settles on its error note.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("network", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        tapBack()

        composeRule.onNodeWithTag("boardWin-home").assertExists()
        composeRule.onNodeWithTag("drilldown-home").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingTheHermesTitleOpensTheFullScreenHermesViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        composeRule.onNodeWithTag("boardTitle-hermes").performClick()
        composeRule.waitForIdle()

        // The drill-down wraps the existing wide Hermes view: the back
        // app bar title plus the chat and push panes side by side; the
        // title is visible in the still mounted board window too.
        composeRule.onNodeWithTag("drilldown-hermes").assertExists()
        composeRule.onAllNodesWithText("Hermes").assertCountEquals(2)
        composeRule.onNodeWithText("Chat").assertExists()
        composeRule.onNodeWithText("Push feed").assertExists()
        // The board stays composed under the drill-down.
        composeRule.onNodeWithTag("boardWin-hermes").assertExists()
        composeRule.onNodeWithTag("boardWin-push").assertExists()
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        // The chat thread note shows in both the still mounted board chat
        // window and the drill-down's chat pane (the thread is shared at
        // the root).
        composeRule
            .onAllNodesWithText("Say hi to Hermes — the conversation thread stays on this page.")
            .assertCountEquals(2)

        tapBack()

        composeRule.onNodeWithTag("boardWin-hermes").assertExists()
        composeRule.onNodeWithTag("drilldown-hermes").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingThePushTitleOpensTheExistingFullScreenHermesViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        // The push window's content is the push feed; its existing
        // full-screen view is the Hermes section (chat + push side by
        // side), so the push window's title resolves to it.
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

    // --- shared state: the drill-down opens where the window was -----------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun theDrillDownOpensOnTheMonthTheBoardWindowWasShowing() {
        connectServer()
        startApp()

        // Advance the board's calendar window to the next month (its
        // month-navigation row is live in the window), then maximize it.
        composeRule.onNodeWithText("›").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("boardTitle-calendar").performClick()
        composeRule.waitForIdle()

        // The drill-down opens over the still-mounted board, and both
        // show the same month: the full-screen view opens exactly where
        // the tapped window was showing, not on the default current
        // month.
        val nextMonth = LocalDate.now().plusMonths(1)
            .format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        composeRule.onNodeWithTag("drilldown-calendar").assertExists()
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        composeRule.onAllNodesWithText(nextMonth).assertCountEquals(2)

        tapBack()

        // Back returns to the board, still on that month.
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        composeRule.onNodeWithText(nextMonth).assertExists()
        composeRule.onNodeWithTag("drilldown-calendar").assertDoesNotExist()
    }

    // --- the phone layout is untouched --------------------------------------

    @Config(sdk = [35], qualifiers = "w400dp-h800dp")
    @Test
    fun phoneKeepsBottomNavigationAndTheBoardIsNotShown() {
        connectServer()
        startApp()

        // Phone routing is unchanged: the bottom navigation bar carries the
        // three sections and the top bar carries the settings icon.
        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Home").assertExists()
        composeRule.onNodeWithText("Hermes").assertExists()
        composeRule.onNodeWithContentDescription("Settings").assertExists()

        // The tablet board does not leak into the phone layout.
        composeRule.onNodeWithTag("boardWin-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-home").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-hermes").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-push").assertDoesNotExist()
    }
}
