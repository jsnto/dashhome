package com.jsnto.dashhome.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jsnto.dashhome.api.ChatMessage

/**
 * Conversation state for the Hermes chat - the mobile counterpart of the
 * web's ChatPanel. The thread is kept in order (oldest first) and sent in
 * full as the `conversation` payload on every message, same as the web.
 *
 * The message list and [busy] flag are Compose snapshot state, so screens
 * re-render as the thread grows; the logic itself is side-effect free and
 * unit-testable on the JVM.
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

    fun clear() {
        _messages.clear()
        busy = false
    }

    /**
     * The thread as sent in the `conversation` payload (oldest first,
     * including any error notes - the web sends its whole msg list).
     */
    fun history(): List<ChatMessage> =
        _messages.map { ChatMessage(it.role.name.lowercase(), it.content) }

    fun beginSend() {
        busy = true
    }

    fun endSend() {
        busy = false
    }
}
