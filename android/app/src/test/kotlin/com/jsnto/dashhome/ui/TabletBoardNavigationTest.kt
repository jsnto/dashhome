package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
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

/**
 * Navigation-structure pins for the tablet one-page board:
 *
 *  - a wide display (screenWidthDp >= 600) shows the one-page board with
 *    every item (the navigation rail's entries) as tappable cards, and no
 *    section content until one is opened;
 *  - tapping a board item opens that section's existing full-screen view
 *    (the wide Calendar layout, the Home embed view, the wide Hermes
 *    layout, or the Settings screen) and back returns to the board;
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

    // --- the one-page board ------------------------------------------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun wideDisplayShowsTheOnePageBoardWithEveryItem() {
        connectServer()
        startApp()

        // Every rail item of today is a tappable card on the one page.
        composeRule.onNodeWithTag("boardItem-calendar").assertExists()
        composeRule.onNodeWithTag("boardItem-home").assertExists()
        composeRule.onNodeWithTag("boardItem-hermes").assertExists()
        composeRule.onNodeWithTag("boardItem-settings").assertExists()

        // The board is a navigation surface: no drill-down view (and no
        // section content) is composed until an item is tapped.
        composeRule.onNodeWithTag("drilldown-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("drilldown-home").assertDoesNotExist()
        composeRule.onNodeWithTag("drilldown-hermes").assertDoesNotExist()
        composeRule.onNodeWithTag("drilldown-settings").assertDoesNotExist()
    }

    // --- drill-down and back ------------------------------------------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingCalendarOpensTheFullScreenCalendarAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        composeRule.onNodeWithTag("boardItem-calendar").performClick()
        composeRule.waitForIdle()

        // The drill-down wraps the existing full-screen calendar view:
        // back app bar title plus the month navigation row.
        composeRule.onNodeWithTag("drilldown-calendar").assertExists()
        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Today").assertExists()
        // The test server is unreachable; the view settles on its error
        // note, which proves the real calendar section is live here.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("network", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        tapBack()

        composeRule.onNodeWithTag("boardItem-calendar").assertExists()
        composeRule.onNodeWithTag("drilldown-calendar").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingHomeOpensTheFullScreenHomeViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        composeRule.onNodeWithTag("boardItem-home").performClick()
        composeRule.waitForIdle()

        // The drill-down wraps the existing full-screen Home view (the
        // WebView embed section), with the title in the back app bar
        // instead of the content row.
        composeRule.onNodeWithTag("drilldown-home").assertExists()
        composeRule.onNodeWithText("Home").assertExists()
        // The embed fetch runs against the unreachable test server and the
        // view settles on its error note.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("network", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        tapBack()

        composeRule.onNodeWithTag("boardItem-home").assertExists()
        composeRule.onNodeWithTag("drilldown-home").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingHermesOpensTheFullScreenHermesViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        composeRule.onNodeWithTag("boardItem-hermes").performClick()
        composeRule.waitForIdle()

        // The drill-down wraps the existing wide Hermes view: the back
        // app bar title plus the chat and push panes side by side.
        composeRule.onNodeWithTag("drilldown-hermes").assertExists()
        composeRule.onNodeWithText("Hermes").assertExists()
        composeRule.onNodeWithText("Chat").assertExists()
        composeRule.onNodeWithText("Push feed").assertExists()
        composeRule
            .onNodeWithText("Say hi to Hermes — the conversation thread stays on this page.")
            .assertExists()

        tapBack()

        composeRule.onNodeWithTag("boardItem-hermes").assertExists()
        composeRule.onNodeWithTag("drilldown-hermes").assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun tappingSettingsOpensTheFullScreenSettingsViewAndBackReturnsToTheBoard() {
        connectServer()
        startApp()

        composeRule.onNodeWithTag("boardItem-settings").performClick()
        composeRule.waitForIdle()

        // The drill-down is the existing Settings screen, which brings its
        // own back app bar and system-back handling.
        composeRule.onNodeWithTag("drilldown-settings").assertExists()
        composeRule.onNodeWithText("Server").assertExists()
        composeRule.onNodeWithText("Danger zone").assertExists()

        tapBack()

        composeRule.onNodeWithTag("boardItem-settings").assertExists()
        composeRule.onNodeWithTag("drilldown-settings").assertDoesNotExist()
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
        composeRule.onNodeWithTag("boardItem-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("boardItem-home").assertDoesNotExist()
        composeRule.onNodeWithTag("boardItem-hermes").assertDoesNotExist()
        composeRule.onNodeWithTag("boardItem-settings").assertDoesNotExist()
    }
}
