package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `send captures the thread before the user turn and appends the reply when it lands`() =
        runTest {
            val s = ChatState()
            s.addUserMessage("first")
            s.addAssistantMessage("reply one")

            val gate = CompletableDeferred<Unit>()
            var sent: List<String>? = null
            s.send("second", this, {
                sent = it.map { m -> m.content }
                gate.await()
                "reply two"
            })
            advanceUntilIdle() // the request starts, captures the thread, suspends on the gate
            // The captured conversation excludes the new user turn (web
            // parity); the turn itself is already in the thread.
            assertEquals(listOf("first", "reply one"), sent)
            assertEquals(3, s.messages.size)
            assertEquals("second", s.messages.last().content)
            assertTrue(s.busy)
            assertFalse(s.canSend("third"))

            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(s.busy)
            assertTrue(s.canSend("third"))
            assertEquals(4, s.messages.size)
            assertEquals("reply two", s.messages.last().content)
        }

    @Test
    fun `a second send supersedes the in-flight one instead of racing`() = runTest {
        val s = ChatState()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var started = 0

        s.send("one", this, {
            started++
            entered.complete(Unit)
            gate.await()
            "reply one"
        })
        entered.await()
        assertTrue(s.busy)

        s.send("two", this, {
            started++
            "reply two"
        })
        gate.complete(Unit)
        advanceUntilIdle()

        // The superseded request was cancelled mid-flight, so its reply
        // never lands and the newest reply is the only assistant turn.
        assertEquals(
            listOf(
                ChatState.Role.USER to "one",
                ChatState.Role.USER to "two",
                ChatState.Role.ASSISTANT to "reply two",
            ),
            s.messages.map { it.role to it.content },
        )
        assertFalse(s.messages.any { it.content == "reply one" })
        assertEquals(2, started)
        assertFalse(s.busy)
        assertTrue(s.canSend("three"))
    }

    @Test
    fun `a failed request appends an error note and clears busy`() = runTest {
        val s = ChatState()
        s.send("hi", this, { throw ApiException(502, "hermes_unavailable", "no hermes") })
        advanceUntilIdle()

        assertFalse(s.busy)
        assertEquals(2, s.messages.size)
        val note = s.messages.last()
        assertTrue(note.error)
        assertEquals("hermes_unavailable — no hermes", note.content)
        assertEquals("assistant", s.history().last().role)
    }

    @Test
    fun `blank text is a no-op`() = runTest {
        val s = ChatState()
        s.send("   ", this, { "reply" })
        advanceUntilIdle()

        assertTrue(s.messages.isEmpty())
        assertFalse(s.busy)
        assertTrue(s.canSend("hi"))
    }

    @Test
    fun `a voice-originated reply fires onVoiceReply and a typed one does not`() = runTest {
        val s = ChatState()
        var spoken = mutableListOf<String>()

        // Typed message: the reply lands but nothing is spoken.
        s.send("typed", this, { "typed reply" })
        advanceUntilIdle()
        assertTrue(spoken.isEmpty())

        // Voice-originated message (the web's fromVoice flag): the reply is
        // read aloud through the hook when it lands.
        s.send("voiced", this, { "voiced reply" }, fromVoice = true, onVoiceReply = { spoken += it })
        advanceUntilIdle()

        assertEquals(listOf("voiced reply"), spoken)
        assertEquals("voiced reply", s.messages.last().content)
    }
}
