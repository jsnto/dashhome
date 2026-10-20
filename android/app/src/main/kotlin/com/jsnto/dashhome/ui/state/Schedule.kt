package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.ApiEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The schedule-summary logic (the web's `CalendarSection` schedule
 * mode): filter a loaded event range down to the events still upcoming
 * from now, and group those by day (in the viewer's zone) for the
 * chronological list. Pure, so it is testable like the month grid.
 *
 * The range filter (the range's start date is >= today means the whole
 * range is upcoming) and the per-event comparison mirror the web's
 * `upcoming` exactly: all-day events stay while their end day is after
 * today; timed events stay while their end instant is the start of
 * today or later.
 */
object Schedule {

    /** One day group in the schedule list: the day label and its events, in load order. */
    data class Day(val key: String, val label: String, val items: List<ApiEvent>)

    private val dayKeyFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val dayLabelFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

    /**
     * The loaded range's events that are still upcoming (the web's
     * `upcoming`): if the range starts on/after today the whole range is
     * upcoming; otherwise an all-day event is upcoming while its end
     * day is after today, and a timed event while its end instant is
     * the start of today (in [zone]) or later.
     */
    fun upcoming(
        events: List<ApiEvent>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): List<ApiEvent> {
        val includesToday = today in rangeStart..rangeEnd
        if (!includesToday) return events
        val todayKey = today.format(dayKeyFmt)
        val todayStartInstant = java.time.Instant.from(today.atStartOfDay(zone))
        return events.filter { e ->
            val end = MonthGrid.parseInstant(e.end)
            if (e.allDay) e.end.take(10) > todayKey else end != null && end >= todayStartInstant
        }
    }

    /**
     * Group the (already filtered) events by day in the viewer's zone,
     * keeping load order (the web's `ScheduleList` grouping: events are
     * in start order from the server, so each day's group is already
     * chronological).
     */
    fun days(events: List<ApiEvent>, zone: ZoneId): List<Day> {
        val out = mutableListOf<Day>()
        events.forEach { e ->
            val d = MonthGrid.toLocalDate(e.start, zone) ?: return@forEach
            val key = d.format(dayKeyFmt)
            val last = out.lastIndex
            if (last >= 0 && out[last].key == key) {
                out[last] = out[last].copy(items = out[last].items + e)
            } else {
                out += Day(key, d.format(dayLabelFmt), listOf(e))
            }
        }
        return out
    }

    /**
     * An event's when-line (the web's `fmtWhen`): "All day" for all-day
     * events, otherwise the local start time, a dash, and the local end
     * time.
     */
    fun whenText(e: ApiEvent, zone: ZoneId): String {
        if (e.allDay) return "All day"
        return "${MonthGrid.fmtTime(e.start, zone)} – ${MonthGrid.fmtTime(e.end, zone)}"
    }
}

