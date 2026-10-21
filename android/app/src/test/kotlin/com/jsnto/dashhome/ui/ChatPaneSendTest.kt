package com.jsnto.dashhome.ui

import android.Manifest
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.ui.state.ChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The chat pane's Send button tracks the field the send action actually
 * sends - the typed draft - never the live interim transcript (the web's
 * `disabled={busy || !input.trim()}`): while listening with an empty field
 * the button stays disabled even as interim text appears, and with a typed
 * draft it stays enabled (the draft is what a tap sends).
 */
@RunWith(RobolectricTestRunner::class)
class ChatPaneSendTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun startPane() {
        DashApi.baseUrl = "http://127.0.0.1:1"
        val chat = ChatState()
        composeRule.setContent {
            ChatPane(state = chat, scope = CoroutineScope(Dispatchers.Main), configured = true)
        }
        composeRule.waitForIdle()
        // Robolectric denies manifest permissions by default; the pane's
        // mic needs RECORD_AUDIO to start a session.
        Shadows.shadowOf(composeRule.activity)
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    /** Fires an interim transcript through the pane's live session. */
    private fun interim(text: String) {
        val bundle = Bundle()
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(listOf(text)))
        FakeSpeechRecognizer.listener.onPartialResults(bundle)
        composeRule.waitForIdle()
    }

    @Config(sdk = [35], shadows = [FakeSpeechRecognizer::class])
    @Test
    fun sendButtonIsDisabledWhileListeningWithAnEmptyField() {
        startPane()
        // At rest with an empty field there is nothing to send.
        composeRule.onNodeWithText("Send").assertIsNotEnabled()
        // Start a session with the field empty (the web's start).
        composeRule.onNodeWithTag("chatMic").performClick()
        composeRule.waitForIdle()
        // An interim transcript appears while the field is still empty:
        // the send action sends the typed field, so the button stays
        // disabled (the interim never feeds it - the web's parity).
        interim("turn on the lights")
        composeRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Config(sdk = [35], shadows = [FakeSpeechRecognizer::class])
    @Test
    fun sendButtonStaysEnabledWhileListeningWithATypedDraft() {
        startPane()
        // The ordinary path: a typed message makes Send available.
        composeRule.onNodeWithText("Message Hermes…").performTextInput("hello")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Send").assertIsEnabled()
        // Listening over the typed draft freezes the field on it, and the
        // button stays enabled - a tap sends the draft (the web's
        // stale-draft parity).
        composeRule.onNodeWithTag("chatMic").performClick()
        composeRule.waitForIdle()
        interim("hello there")
        composeRule.onNodeWithText("Send").assertIsEnabled()
    }
}
