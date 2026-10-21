package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.ui.state.ChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat pane's mic degrades the way the web's mic button does
 * (ChatPanel's micDisabled = !supported || blocked !== null || busy):
 * disabled with the reason shown from the start when recognition is
 * unavailable, and disabled while a reply is in flight so a spoken
 * transcript cannot supersede it.
 */
@RunWith(RobolectricTestRunner::class)
class ChatPaneMicTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun startPane(): ChatState {
        DashApi.baseUrl = "http://127.0.0.1:1"
        val chat = ChatState()
        composeRule.setContent {
            ChatPane(state = chat, scope = CoroutineScope(Dispatchers.Main), configured = true)
        }
        composeRule.waitForIdle()
        return chat
    }

    @Config(sdk = [35])
    @Test
    fun theMicIsDisabledWithTheReasonWhenRecognitionIsUnsupported() {
        startPane()
        // Robolectric has no speech recognition service: the platform
        // reports it unavailable, so the mic is disabled and the reason
        // shows from the start (the web's disabled button + title).
        composeRule.onNodeWithTag("chatMic").assertIsNotEnabled()
        composeRule
            .onNodeWithText("Voice recognition is unavailable on this device")
            .assertExists()
    }

    @Config(sdk = [35], shadows = [RecognitionAvailableShadow::class])
    @Test
    fun theMicIsDisabledWhileAReplyIsInFlight() {
        val chat = startPane()
        // Recognition is available (the shadow), so the mic is operable
        // at rest.
        composeRule.onNodeWithTag("chatMic").assertIsEnabled()
        // While a reply is in flight the mic is disabled (the web's busy
        // disjunct) - a spoken transcript cannot supersede the reply.
        chat.busy = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chatMic").assertIsNotEnabled()
        // The reply lands: the mic is operable again.
        chat.busy = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chatMic").assertIsEnabled()
    }
}
