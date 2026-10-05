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
 *  - w600dp (landscape) is wide: the one-page board is shown (top row of
 *    cards composed; the board's top bar, not the phone bottom nav);
 *  - w599dp (portrait) is not wide: the phone bottom nav is shown and no
 *    board card leaks in.
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
        // Board routing at the boundary: the board's own top bar and the
        // top row of cards (both fit in the 400dp-tall landscape canvas;
        // the second row composes lazily on scroll, as on the real
        // 1280x800dp tablet where all four cards fit).
        composeRule.onNodeWithText("dashhome").assertExists()
        composeRule.onNodeWithTag("boardItem-calendar").assertExists()
        composeRule.onNodeWithTag("boardItem-home").assertExists()
    }

    @Config(sdk = [35], qualifiers = "w599dp-h800dp")
    @Test
    fun justUnderSixHundredDpRoutesToThePhoneLayout() {
        startApp()
        // Phone routing: the bottom navigation bar carries the sections.
        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Home").assertExists()
        composeRule.onNodeWithText("Hermes").assertExists()
        // No board card leaks into the phone layout.
        composeRule.onNodeWithTag("boardItem-calendar").assertDoesNotExist()
        composeRule.onNodeWithTag("boardItem-home").assertDoesNotExist()
        composeRule.onNodeWithTag("boardItem-hermes").assertDoesNotExist()
        composeRule.onNodeWithTag("boardItem-settings").assertDoesNotExist()
    }
}
