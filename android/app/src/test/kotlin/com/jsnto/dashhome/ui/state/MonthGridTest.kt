package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.ApiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class MonthGridTest {

    private val utc = ZoneOffset.UTC

    private fun ev(
        start: String,
        end: String,
        allDay: Boolean = false,
        calendarId: String = "a",
        calendarLabel: String = "Cal A",
        summary: String = "Ev",
        id: String = "1",
    ) = ApiEvent(
        calendarId = calendarId,
        calendarLabel = calendarLabel,
        id = id,
        summary = summary,
        start = start,
        end = end,
        allDay = allDay,
    )

    // --- grid cells -----------------------------------------------------------

    @Test
    fun `october 2026 starts on a padded sunday and has 31 in-month days`() {
        // 2026-10-01 is a Thursday: the grid leads with Sun 2026-09-27.
        val cells = MonthGrid.cells(2026, 10)
        assertEquals(42, cells.size)
        assertEquals("2026-09-27", MonthGrid.dayKey(cells.first().date))
        assertEquals("2026-11-07", MonthGrid.dayKey(cells.last().date))
        assertEquals(31, cells.count { it.inMonth })
        // The 5th cell is the first in-month day.
        assertEquals(4, cells.indexOfFirst { it.inMonth })
    }

    @Test
    fun `months starting on sunday have no lead padding`() {
        // 2026-02-01 is a Sunday.
        val cells = MonthGrid.cells(2026, 2)
        assertEquals("2026-02-01", MonthGrid.dayKey(cells.first().date))
        assertEquals(28, cells.count { it.inMonth })
    }

    @Test
    fun `leap february has 29 in-month days`() {
        assertEquals(29, MonthGrid.cells(2028, 2).count { it.inMonth })
        assertEquals(28, MonthGrid.cells(2027, 2).count { it.inMonth })
    }

    @Test
    fun `year boundary wraps correctly`() {
        // Dec 2026 -> Jan 2027: last in-month cell is 2026-12-31.
        val dec = MonthGrid.cells(2026, 12)
        assertTrue(dec.any { it.inMonth && it.date.year == 2026 && it.date.dayOfMonth == 31 })
        val jan = MonthGrid.cells(2027, 1)
        assertTrue(jan.any { it.inMonth && it.date.year == 2027 && it.date.dayOfMonth == 1 })
    }

    // --- day keys and grouping --------------------------------------------------

    @Test
    fun `dayKey formats zero padded`() {
        assertEquals("2026-01-05", MonthGrid.dayKey(java.time.LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `startKey converts rfc3339 into the zone local day`() {
        // 23:30 UTC on Oct 3 is already Oct 4 in UTC+8.
        assertEquals(
            "2026-10-04",
            MonthGrid.startKey("2026-10-03T23:30:00Z", java.time.ZoneId.ofOffset("UTC", java.time.ZoneOffset.ofHours(8))),
        )
        assertEquals("2026-10-03", MonthGrid.startKey("2026-10-03T23:30:00Z", utc))
    }

    @Test
    fun `eventsByDay groups by local start day`() {
        val events = listOf(
            ev("2026-10-03T09:00:00Z", "2026-10-03T10:00:00Z"),
            ev("2026-10-03T23:30:00Z", "2026-10-04T00:00:00Z"),
            ev("2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z"),
        )
        val byDay = MonthGrid.eventsByDay(events, utc)
        assertEquals(2, byDay.keys.size)
        assertEquals(2, byDay["2026-10-03"]!!.size)
        assertEquals(1, byDay["2026-10-04"]!!.size)
    }

    // --- all-day semantics (web: end date is exclusive) ----------------------------

    @Test
    fun `all-day end key is the last inclusive day`() {
        val e = ev("2026-10-05T00:00:00Z", "2026-10-08T00:00:00Z", allDay = true)
        assertEquals("2026-10-07", MonthGrid.allDayEndKey(e, utc))
    }

    @Test
    fun `all-day end key is null for timed events`() {
        assertEquals(null, MonthGrid.allDayEndKey(ev("2026-10-05T09:00:00Z", "2026-10-05T10:00:00Z"), utc))
    }

    @Test
    fun `single day all-day event does not show an end`() {
        val e = ev("2026-10-05T00:00:00Z", "2026-10-06T00:00:00Z", allDay = true)
        assertFalse(MonthGrid.showEnd(e, utc))
        assertEquals("Oct 5", MonthGrid.fmtWhen(e, utc))
        assertEquals("Oct 5", MonthGrid.fmtWhenEnd(e, utc))
    }

    @Test
    fun `multi-day all-day event shows an end`() {
        val e = ev("2026-10-05T00:00:00Z", "2026-10-08T00:00:00Z", allDay = true)
        assertTrue(MonthGrid.showEnd(e, utc))
        assertEquals("Oct 5 – Oct 7", "${MonthGrid.fmtWhen(e, utc)} – ${MonthGrid.fmtWhenEnd(e, utc)}")
    }

    @Test
    fun `timed event shows end only when it crosses local days`() {
        val sameDay = ev("2026-10-03T09:00:00Z", "2026-10-03T10:00:00Z")
        assertFalse(MonthGrid.showEnd(sameDay, utc))
        assertEquals("9:00 AM", MonthGrid.fmtWhen(sameDay, utc))
        val twoDay = ev("2026-10-03T23:00:00Z", "2026-10-04T01:00:00Z")
        assertTrue(MonthGrid.showEnd(twoDay, utc))
        assertEquals("11:00 PM – 1:00 AM", "${MonthGrid.fmtWhen(twoDay, utc)} – ${MonthGrid.fmtWhenEnd(twoDay, utc)}")
    }

    // --- ordering and palette -----------------------------------------------------

    @Test
    fun `calendarOrder is first appearance order`() {
        val events = listOf(
            ev("2026-10-03T09:00:00Z", "2026-10-03T10:00:00Z", calendarId = "b"),
            ev("2026-10-03T08:00:00Z", "2026-10-03T09:00:00Z", calendarId = "a"),
            ev("2026-10-03T07:00:00Z", "2026-10-03T08:00:00Z", calendarId = "b"),
        )
        assertEquals(listOf("b", "a"), MonthGrid.calendarOrder(events))
    }

    @Test
    fun `palette colors are stable and wrap`() {
        val order = listOf("a", "b")
        assertEquals(Palette.colors[0], Palette.colorFor("a", order))
        assertEquals(Palette.colors[1], Palette.colorFor("b", order))
        assertEquals(Palette.colors[0], Palette.colorFor("unknown", order))
        // The 11th calendar (index 10) wraps back to the first color.
        val big = List(11) { "c$it" }
        assertEquals(Palette.colors[0], Palette.colorFor(big[10], big))
    }

    // --- labels -----------------------------------------------------------------

    @Test
    fun `shortLabel takes the first word capped at nine chars`() {
        assertEquals("Front", shortLabel("Front door open"))
        assertEquals("Thermosta", shortLabel("Thermostat"))
        assertEquals("", shortLabel(""))
    }

    @Test
    fun `unparseable timestamps degrade to empty instead of throwing`() {
        assertEquals("", MonthGrid.fmtTime("not-a-time", utc))
        assertEquals("", MonthGrid.fmtDate("not-a-date", utc))
        assertEquals(null, MonthGrid.toLocalDate("garbage", utc))
    }
}
