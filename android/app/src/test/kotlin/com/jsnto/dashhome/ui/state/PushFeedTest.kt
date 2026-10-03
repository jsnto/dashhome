package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.Push
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class PushFeedTest {

    @Before
    fun utc() {
        // The feed formats in the device's zone; pin UTC for determinism.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun push(id: String, receivedAt: String, source: String? = null, md: String = "md") =
        Push(id = id, receivedAt = receivedAt, source = source, markdown = md)

    @Test
    fun `newestFirst sorts by received_at then id`() {
        val pushes = listOf(
            push("100", "2026-10-01T10:00:00Z"),
            push("102", "2026-10-03T09:00:00Z"),
            push("101", "2026-10-02T09:00:00Z"),
        )
        val sorted = PushFeed.newestFirst(pushes)
        assertEquals(listOf("102", "101", "100"), sorted.map { it.id })
        // Same timestamp: higher id first.
        val tied = PushFeed.newestFirst(
            listOf(push("a", "2026-10-03T09:00:00Z"), push("b", "2026-10-03T09:00:00Z")),
        )
        assertEquals(listOf("b", "a"), tied.map { it.id })
    }

    @Test
    fun `tabs lead with Latest and cap at twelve history entries`() {
        val history = List(15) { i -> push("p$i", "2026-10-0${i % 9 + 1}T0${i % 10 + 1}:00:00Z") }
        val tabs = PushFeed.tabs(history)
        assertEquals(1 + PushFeed.HISTORY_TABS, tabs.size)
        assertEquals("Latest", tabs[0].label)
        assertEquals(null, tabs[0].id)
        tabs.drop(1).forEach { assertTrue(it.id != null) }
        // The newest push appears as the first history tab.
        val newest = PushFeed.newestFirst(history).first()
        assertEquals(newest.id, tabs[1].id)
    }

    @Test
    fun `tabs for an empty history is just Latest`() {
        val tabs = PushFeed.tabs(emptyList())
        assertEquals(1, tabs.size)
        assertEquals("Latest", tabs[0].label)
    }

    @Test
    fun `shortDate is a medium date label`() {
        assertEquals("Oct 3", PushFeed.shortDate("2026-10-03T09:41:00Z"))
    }

    @Test
    fun `shortDate falls back to the raw string when unparseable`() {
        assertEquals("when?", PushFeed.shortDate("when?"))
    }

    @Test
    fun `metaLine marks latest and carries the source`() {
        val p = push("1", "2026-10-03T09:41:00Z", source = "hermes")
        assertEquals("Oct 3, 2026, 9:41 AM · hermes (latest)", PushFeed.metaLine(p, latest = true))
        assertEquals(
            "Oct 3, 2026, 9:41 AM (previous push)",
            PushFeed.metaLine(p.copy(source = null), latest = false),
        )
    }

    @Test
    fun `metaLine tolerates unparseable timestamps`() {
        assertTrue(PushFeed.metaLine(push("1", "soon"), latest = true).contains("soon"))
    }
}
