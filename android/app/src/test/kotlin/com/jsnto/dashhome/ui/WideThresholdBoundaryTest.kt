package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import com.jsnto.dashhome.ui.theme.DashhomeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins for the exact screenWidthDp >= 600 routing boundary:
 *  - w600dp (landscape) is wide: the one-page board is shown (the board's
 *    top bar and its live content windows composed, not the phone bottom
 *    nav; the fixed two-column layout composes every window at once, as
 *    it does on the real 1280x800dp tablet where all four fit);
 *  - w599dp (portrait) is not wide: the phone bottom nav is shown and no
 *    board window leaks in.
 */
@RunWith(RobolectricTestRunner::class)
class WideThresholdBoundaryTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun startApp() {
        Prefs.setBase(composeRule.activity, "http://127.0.0.1:1")
        DashApi.baseUrl = "http://127.0.0.1:1"
        composeRule.setContent { DashhomeTheme { DashhomeRoot() } }
        composeRule.waitForIdle()
    }

    @Config(sdk = [35], qualifiers = "w600dp-h400dp")
    @Test
    fun exactlySixHundredDpRoutesToTheBoard() {
        startApp()
        // Board routing at the boundary: the board's own top bar and its
        // live content windows (all four compose at once in the fixed
        // two-column layout, as on the real 1280x800dp tablet).
        composeRule.onNodeWithText("dashhome").assertExists()
        composeRule.onNodeWithTag("boardWin-calendar").assertExists()
        composeRule.onNodeWithTag("boardWin-home").assertExists()
        composeRule.onNodeWithTag("boardWin-hermes").assertExists()
        composeRule.onNodeWithTag("boardWin-push").assertExists()
    }

    @Config(sdk = [35], qualifiers = "w599dp-h800dp")
    @Test
    fun justUnderSixHundredDpRoutesToThePhoneLayout() {
        startApp()
        // Phone routing: the bottom navigation bar carries the sections.
        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Home").assertExists()
        composeRule.onNodeWithText("Hermes").assertExists()
        // No board window leaks into the phone layout.
        composeRule.onNodeWithTag("boardWin-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-home").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-hermes").assertDoesNotExist()
        composeRule.onNodeWithTag("boardWin-push").assertDoesNotExist()
    }
}
