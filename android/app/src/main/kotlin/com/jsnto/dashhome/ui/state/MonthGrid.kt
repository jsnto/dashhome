package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.ApiEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Pure calendar-grid and event-formatting logic for the Calendar section -
 * the mobile counterpart of the web dashboard's MonthGrid. All functions
 * are side-effect free (and unit-testable): the device's [ZoneId] is
 * passed in rather than read, mirroring the web, which renders event
 * times in the viewer's local zone.
 */
object MonthGrid {

    /** One day cell of the grid; [date] is the day shown in the cell. */
    data class Cell(val date: LocalDate, val inMonth: Boolean)

    private val dayKeyFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val shortDateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val longDateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)

    /**
     * The 42 cells (6 weeks, Sunday-first) covering [month] (1-based),
     * padded with adjacent-month days at both ends - same as the web grid.
     */
    fun cells(year: Int, month: Int): List<Cell> {
        val first = LocalDate.of(year, month, 1)
        val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        return List(42) { i ->
            val d = start.plusDays(i.toLong())
            Cell(d, d.monthValue == month)
        }
    }

    /** "YYYY-MM-DD" key for a local date (the web's dayKey). */
    fun dayKey(date: LocalDate): String = date.format(dayKeyFmt)

    /** The local-day key an event's RFC3339 [start] falls on in [zone]. */
    fun startKey(iso: String, zone: ZoneId): String =
        toLocalDate(iso, zone)?.format(dayKeyFmt) ?: ""

    /**
     * The last inclusive day of an all-day event: the web treats the all-day
     * end date as exclusive, so the event's last day is end minus one.
     * Null when the end cannot be parsed.
     */
    fun allDayEndKey(event: ApiEvent, zone: ZoneId): String? {
        if (!event.allDay) return null
        return toLocalDate(event.end, zone)?.minusDays(1)?.format(dayKeyFmt)
    }

    /** Whether the event's detail row should show an end time. */
    fun showEnd(event: ApiEvent, zone: ZoneId): Boolean =
        when {
            !event.allDay -> toLocalDate(event.end, zone) != toLocalDate(event.start, zone)
            else -> allDayEndKey(event, zone) != startKey(event.start, zone)
        }

    /** "9:30 AM" for a timed event's start, or "Oct 5" for an all-day one. */
    fun fmtWhen(event: ApiEvent, zone: ZoneId): String =
        if (event.allDay) fmtDate(event.start, zone) else fmtTime(event.start, zone)

    /** The end side of the detail row: date for all-day, time otherwise. */
    fun fmtWhenEnd(event: ApiEvent, zone: ZoneId): String =
        if (event.allDay) (allDayEndKey(event, zone)?.let { fmtDate(it, zone) } ?: "")
        else fmtTime(event.end, zone)

    /** "10:30 AM" from an RFC3339 timestamp in [zone]. */
    fun fmtTime(iso: String, zone: ZoneId): String =
        toInstant(iso)?.atZone(zone)?.toLocalTime()?.format(timeFmt) ?: ""

    /** "Oct 5" from an RFC3339 timestamp in [zone] (day key also works). */
    fun fmtDate(iso: String, zone: ZoneId): String =
        toLocalDate(iso, zone)?.format(shortDateFmt) ?: ""

    /** Full detail heading: "Thursday, October 5, 2026" (input: day key). */
    fun fmtLongDate(key: String, zone: ZoneId): String =
        toLocalDate(key, zone)?.format(longDateFmt) ?: key

    /** Groups events by the local day their start falls on (start order kept). */
    fun eventsByDay(events: List<ApiEvent>, zone: ZoneId): Map<String, List<ApiEvent>> =
        events.groupBy { startKey(it.start, zone) }

    /**
     * Calendar IDs in first-appearance order (the web assigns chip colors
     * by this index into its palette).
     */
    fun calendarOrder(events: List<ApiEvent>): List<String> =
        events.map { it.calendarId }.distinct()

    /**
     * A local date from an RFC3339 timestamp (converted into [zone]) or a
     * plain "YYYY-MM-DD" day key. Null when the input is unparseable.
     */
    fun toLocalDate(iso: String, zone: ZoneId): LocalDate? =
        try {
            if (iso.length <= 10 && !iso.contains('T')) LocalDate.parse(iso)
            else Instant.parse(iso).atZone(zone).toLocalDate()
        } catch (e: Exception) {
            null
        }

    private fun toInstant(iso: String): Instant? =
        try {
            Instant.parse(iso)
        } catch (e: Exception) {
            null
        }
}

/**
 * The per-calendar chip palette, in the web dashboard's order (the app
 * indexes it by [MonthGrid.calendarOrder]).
 */
object Palette {
    val colors = listOf(
        0xFF4CC2FFL, 0xFFFFB84CL, 0xFF8DE04CL, 0xFFFF6B81L, 0xFFC792EAL,
        0xFFFFD166L, 0xFF7BDFF2L, 0xFFF78C6CL, 0xFF95E6C9L, 0xFFD7A7FFL,
    )

    fun colorFor(calendarId: String, order: List<String>): Long {
        val i = order.indexOf(calendarId)
        return colors[(if (i < 0) 0 else i) % colors.size]
    }
}

/** The first word of a label, truncated the way the web chips do. */
fun shortLabel(label: String): String {
    val word = label.split(Regex("\\s+")).firstOrNull().orEmpty()
    return if (label.isBlank()) "" else word.take(9)
}
