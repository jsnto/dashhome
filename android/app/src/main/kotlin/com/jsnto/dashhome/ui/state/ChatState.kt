package com.jsnto.dashhome.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Conversation state for the Hermes chat - the mobile counterpart of the
 * web's ChatPanel. The thread is kept in order (oldest first) and sent in
 * full as the `conversation` payload on every message, same as the web.
 *
 * The message list and [busy] flag are Compose snapshot state, so screens
 * re-render as the thread grows; [send] runs on a caller-supplied scope and
 * is unit-testable on the JVM with a test coroutine scope.
 */
class ChatState {

    enum class Role { USER, ASSISTANT }

    data class Msg(
        val role: Role,
        val content: String,
        val error: Boolean = false,
    )

    private val _messages = mutableStateListOf<Msg>()

    /** The thread (oldest first); observable by Compose. */
    val messages: List<Msg>
        get() = _messages

    /** True while a reply is in flight (the input is disabled then). */
    var busy by mutableStateOf(false)

    /** The in-flight [send], if any; a newer send supersedes it. */
    private var sendJob: Job? = null

    fun canSend(text: String): Boolean = !busy && text.isNotBlank()

    /**
     * Appends the newest user message. Call [history] FIRST when building
     * the request: the web captures the conversation before adding the
     * current user turn.
     */
    fun addUserMessage(text: String) {
        _messages.add(Msg(Role.USER, text))
    }

    /** An assistant reply (or an error note, styled but kept in the thread). */
    fun addAssistantMessage(text: String, error: Boolean = false) {
        _messages.add(Msg(Role.ASSISTANT, text, error))
    }

    /**
     * The thread as sent in the `conversation` payload (oldest first,
     * including any error notes - the web sends its whole msg list).
     */
    fun history(): List<ChatMessage> =
        _messages.map { ChatMessage(it.role.name.lowercase(), it.content) }

    /**
     * Sends [text] on [scope], which must outlive the chat pane so an
     * in-flight reply survives the pane leaving composition. The thread is
     * captured BEFORE the user turn is appended (web parity), and the reply
     * - or an error note - is appended when it lands. A second send
     * supersedes the in-flight one: the old request is cancelled, so only
     * the newest reply lands.
     *
     * [fromVoice] marks a voice-originated message (the web's `fromVoice`
     * flag on ChatPanel.sendText); for those, [onVoiceReply] is called with
     * the reply when it lands (the web reads the reply aloud only for
     * voice-originated messages).
     */
    fun send(
        text: String,
        scope: CoroutineScope,
        request: suspend (history: List<ChatMessage>) -> String,
        fromVoice: Boolean = false,
        onVoiceReply: (String) -> Unit = {},
    ) {
        if (text.isBlank()) return
        sendJob?.cancel()
        val history = history()
        addUserMessage(text)
        busy = true
        val job = scope.launch {
            try {
                val reply = request(history)
                addAssistantMessage(reply)
                if (fromVoice) onVoiceReply(reply)
            } catch (e: ApiException) {
                addAssistantMessage(
                    if (e.detail.isNullOrBlank()) e.code else "${e.code} — ${e.detail}",
                    error = true,
                )
            } finally {
                // Only the newest send clears [busy]; a superseded one must
                // not clear the flag its replacement just set.
                if (sendJob == coroutineContext[Job]) {
                    busy = false
                }
            }
        }
        sendJob = job
    }
}
