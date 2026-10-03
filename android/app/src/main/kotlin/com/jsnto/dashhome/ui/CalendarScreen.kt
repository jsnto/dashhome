package com.jsnto.dashhome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiEvent
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.CalendarInfo
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.ui.state.MonthGrid
import com.jsnto.dashhome.ui.state.Palette
import com.jsnto.dashhome.ui.state.shortLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The web dashboard's Summary tab id. */
private const val SUMMARY = "__summary__"

private const val MAX_CHIPS = 3

private const val WEEKS = 6

/**
 * The Calendar section: the same tabs/summary the web dashboard shows -
 * a Summary tab combining every calendar plus one tab per visible
 * calendar, over a month grid with day detail. On wide displays the
 * month grid and the selected day's events sit side by side instead of
 * stacked.
 */
@Composable
fun CalendarScreen(wide: Boolean = false) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }

    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var month by rememberSaveable { mutableIntStateOf(today.monthValue) } // 1-based
    var active by rememberSaveable { mutableStateOf(SUMMARY) }
    // On wide displays the day detail lives in its own pane, so start with
    // today selected for an immediately useful two-pane view.
    var selectedDay by rememberSaveable { mutableStateOf(if (wide) MonthGrid.dayKey(today) else null) }

    // Visible calendars (the tabs).
    var calendars by remember { mutableStateOf<List<CalendarInfo>?>(null) }
    var calErr by remember { mutableStateOf<ApiException?>(null) }
    LaunchedEffect(DashApi.baseUrl) {
        try {
            calendars = DashApi.getCalendars()
            calErr = null
        } catch (e: ApiException) {
            calErr = e
        }
    }

    // Events for the visible month (summary = all configured calendars).
    val firstDay = LocalDate.of(year, month, 1)
    val startDay = MonthGrid.dayKey(firstDay)
    val endDay = MonthGrid.dayKey(firstDay.withDayOfMonth(firstDay.lengthOfMonth()))
    var events by remember { mutableStateOf<List<ApiEvent>?>(null) }
    var eventsErr by remember { mutableStateOf<ApiException?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(DashApi.baseUrl, startDay, endDay, active) {
        if (calErr != null) return@LaunchedEffect
        loading = true
        try {
            events = DashApi.getEvents(startDay, endDay, if (active == SUMMARY) null else active)
            eventsErr = null
        } catch (e: ApiException) {
            eventsErr = e
        } finally {
            loading = false
        }
    }

    val shift = { delta: Int ->
        val (y, m) = shiftedMonth(year, month, delta)
        year = y
        month = m
        selectedDay = null
    }

    val eventList = events ?: emptyList()
    val byDay = remember(eventList, zone) { MonthGrid.eventsByDay(eventList, zone) }
    val order = remember(eventList) { MonthGrid.calendarOrder(eventList) }
    val noCalendars = calendars != null && calendars!!.isEmpty()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Shared top: the calendar tabs plus month navigation.
        if (calErr != null) {
            ErrorNote(calErr!!)
        } else {
            val tabs = listOf(SUMMARY to "Summary") +
                (calendars ?: emptyList<CalendarInfo>()).map { it.id to it.label.ifEmpty { it.id } }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                tabs.forEach { (id, label) ->
                    TabChip(
                        label = label,
                        selected = active == id,
                        onClick = {
                            active = id
                            selectedDay = null
                        },
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            TextButton(onClick = { shift(-1) }) { Text("‹") }
            Text(
                text = monthTitle(year, month),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = { shift(1) }) { Text("›") }
            TextButton(
                onClick = {
                    year = today.year
                    month = today.monthValue
                    selectedDay = null
                },
            ) {
                Text("Today")
            }
        }

        if (calErr != null) {
            // The calendar area is unusable; the note above explains why.
            return@Column
        }
        if (noCalendars) {
            Note("No calendars configured.")
            return@Column
        }
        if (loading && events == null) {
            LoadingRow()
        }

        if (wide) {
            // Two-pane tablet layout: month grid left, selected day right.
            Row(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                        .padding(end = 8.dp),
                ) {
                    if (eventsErr != null) {
                        ErrorNote(eventsErr!!)
                    } else {
                        MonthGridBody(
                            year = year,
                            month = month,
                            zone = zone,
                            today = today,
                            byDay = byDay,
                            multi = active == SUMMARY,
                            order = order,
                            selectedDay = selectedDay,
                            onDayClick = { key -> selectedDay = if (selectedDay == key) null else key },
                        )
                    }
                }
                CalendarDetailPane(
                    key = selectedDay,
                    byDay = byDay,
                    eventsErr = eventsErr,
                    multi = active == SUMMARY,
                    order = order,
                    zone = zone,
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight(),
                )
            }
        } else {
            if (eventsErr != null) {
                ErrorNote(eventsErr!!)
            }
            MonthGridBody(
                year = year,
                month = month,
                zone = zone,
                today = today,
                byDay = byDay,
                multi = active == SUMMARY,
                order = order,
                selectedDay = selectedDay,
                withDetail = true,
                onDayClick = { key -> selectedDay = if (selectedDay == key) null else key },
            )
        }
    }
}

/** The visible month shifted by [delta] months as (year, month 1-based). */
private fun shiftedMonth(year: Int, month: Int, delta: Int): Pair<Int, Int> {
    val d = LocalDate.of(year, month, 1).plusMonths(delta.toLong())
    return d.year to d.monthValue
}

/**
 * The month grid (day-of-week header + six week rows). On phones the
 * selected day's detail follows the grid; on wide displays it lives in
 * its own pane instead.
 */
@Composable
private fun MonthGridBody(
    year: Int,
    month: Int,
    zone: ZoneId,
    today: LocalDate,
    byDay: Map<String, List<ApiEvent>>,
    multi: Boolean,
    order: List<String>,
    selectedDay: String?,
    withDetail: Boolean = false,
    onDayClick: (String) -> Unit,
) {
    val cells = remember(year, month) { MonthGrid.cells(year, month) }
    val todayKey = MonthGrid.dayKey(today)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { DayOfWeekRow() }
        items(WEEKS) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val cell = cells[week * 7 + i]
                    val key = MonthGrid.dayKey(cell.date)
                    DayCell(
                        modifier = Modifier.weight(1f),
                        date = cell.date,
                        inMonth = cell.inMonth,
                        today = key == todayKey,
                        selected = key == selectedDay,
                        events = byDay[key].orEmpty(),
                        multi = multi,
                        order = order,
                        zone = zone,
                        onClick = { onDayClick(key) },
                    )
                }
            }
        }
        if (withDetail) {
            selectedDay?.let { key ->
                item {
                    DayDetail(
                        key = key,
                        events = byDay[key].orEmpty(),
                        multi = multi,
                        order = order,
                        zone = zone,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * The wide-display right pane: the selected day's events (a SectionCard
 * so it reads as a card next to the grid), with a hint when nothing is
 * selected and the events error shown here instead of above the grid.
 */
@Composable
private fun CalendarDetailPane(
    key: String?,
    byDay: Map<String, List<ApiEvent>>,
    eventsErr: ApiException?,
    multi: Boolean,
    order: List<String>,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = if (key != null) MonthGrid.fmtLongDate(key, zone) else "Events",
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                eventsErr != null -> ErrorNote(eventsErr)
                key == null -> Note("Tap a day in the month grid to see its events here.")
                else -> {
                    val events = byDay[key].orEmpty()
                    if (events.isEmpty()) {
                        Note("No events.")
                    } else {
                        events.forEach { e -> EventDetailRow(e, multi, order, zone) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayOfWeekRow() {
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { d ->
            Text(
                text = d,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DayCell(
    modifier: Modifier,
    date: LocalDate,
    inMonth: Boolean,
    today: Boolean,
    selected: Boolean,
    events: List<ApiEvent>,
    multi: Boolean,
    order: List<String>,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val dayColor = if (inMonth) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Column(
        modifier = dayCellBackground(
            modifier,
            today,
            selected,
        )
            .clickable(onClick = onClick)
            .padding(3.dp),
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (today) MaterialTheme.colorScheme.primary else dayColor,
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
        )
        events.take(MAX_CHIPS).forEach { e ->
            EventChip(
                label = chipLabel(e, multi, zone),
                background = Color(Palette.colorFor(e.calendarId, order)),
            )
        }
        if (events.size > MAX_CHIPS) {
            Text(
                text = "+${events.size - MAX_CHIPS} more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The web's chip text: [short calendar label in summary] time, summary. */
private fun chipLabel(e: ApiEvent, multi: Boolean, zone: ZoneId): String {
    val whenText = MonthGrid.fmtWhen(e, zone)
    val label = if (multi) shortLabel(e.calendarLabel).let { if (it.isEmpty()) "" else "$it · " } else ""
    return "$label$whenText ${if (e.summary.isBlank()) "(no title)" else e.summary}"
}

@Composable
private fun DayDetail(
    key: String,
    events: List<ApiEvent>,
    multi: Boolean,
    order: List<String>,
    zone: ZoneId,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = MonthGrid.fmtLongDate(key, zone),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (events.isEmpty()) {
            Note("No events.")
            return@Column
        }
        events.forEach { e -> EventDetailRow(e, multi, order, zone) }
    }
}

@Composable
private fun EventDetailRow(
    e: ApiEvent,
    multi: Boolean,
    order: List<String>,
    zone: ZoneId,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (multi) {
                CalendarLabelChip(
                    label = e.calendarLabel.ifEmpty { e.calendarId },
                    background = Color(Palette.colorFor(e.calendarId, order)),
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Text(
                text = if (e.summary.isBlank()) "(no title)" else e.summary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        val endSide =
            if (MonthGrid.showEnd(e, zone)) " – ${MonthGrid.fmtWhenEnd(e, zone)}" else ""
        Text(
            text = "${MonthGrid.fmtWhen(e, zone)}$endSide" +
                if (e.location.isNullOrBlank()) "" else " @ ${e.location}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!e.description.isNullOrBlank()) {
            Text(
                text = e.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun monthTitle(year: Int, month: Int): String =
    LocalDate.of(year, month, 1).format(DateTimeFormatter.ofPattern("MMMM yyyy"))
