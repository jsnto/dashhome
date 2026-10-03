package com.jsnto.dashhome.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateTest {

    @Test
    fun `fresh state has an empty thread and can send`() {
        val s = ChatState()
        assertEquals(0, s.messages.size)
        assertTrue(s.history().isEmpty())
        assertTrue(s.canSend("hi"))
        assertFalse(s.canSend("   "))
        assertFalse(s.busy)
    }

    @Test
    fun `user and assistant turns append in order`() {
        val s = ChatState()
        s.addUserMessage("how's the house?")
        s.addAssistantMessage("all quiet")
        assertEquals(2, s.messages.size)
        assertEquals(ChatState.Role.USER, s.messages[0].role)
        assertEquals(ChatState.Role.ASSISTANT, s.messages[1].role)
        assertEquals(
            listOf(
                "user" to "how's the house?",
                "assistant" to "all quiet",
            ),
            s.history().map { it.role to it.content },
        )
    }

    @Test
    fun `history is captured before the new user turn is appended`() {
        // The web sends the thread WITHOUT the current user message:
        // capture history, then append, then send (message + conversation).
        val s = ChatState()
        s.addUserMessage("first")
        s.addAssistantMessage("reply one")

        s.addUserMessage("second")
        s.addAssistantMessage("reply two")

        // For the NEXT message, the conversation is the whole thread so far.
        val conversation = s.history()
        s.addUserMessage("third")
        assertEquals(
            listOf(
                "user" to "first",
                "assistant" to "reply one",
                "user" to "second",
                "assistant" to "reply two",
            ),
            conversation.map { it.role to it.content },
        )
        // And the current user message is NOT part of that conversation.
        assertFalse(conversation.any { it.content == "third" })
        assertEquals(5, s.messages.size)
    }

    @Test
    fun `error notes are kept in the thread and sent with it, web parity`() {
        val s = ChatState()
        s.addUserMessage("hi")
        s.addAssistantMessage("hermes_unavailable — no hermes", error = true)
        assertTrue(s.messages.last().error)
        assertEquals(2, s.history().size)
        assertEquals("assistant", s.history().last().role)
    }

    @Test
    fun `busy blocks sending until the reply lands`() {
        val s = ChatState()
        s.beginSend()
        assertTrue(s.busy)
        assertFalse(s.canSend("hi"))
        s.endSend()
        assertFalse(s.busy)
        assertTrue(s.canSend("hi"))
    }

    @Test
    fun `clear resets the thread and the busy flag`() {
        val s = ChatState()
        s.beginSend()
        s.addUserMessage("hi")
        s.clear()
        assertEquals(0, s.messages.size)
        assertFalse(s.busy)
    }
}
