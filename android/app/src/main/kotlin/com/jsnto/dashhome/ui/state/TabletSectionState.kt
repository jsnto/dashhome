package com.jsnto.dashhome.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.ZoneId

/** The web dashboard's Summary tab id. */
internal const val SUMMARY = "__summary__"

/**
 * The section state the tablet board and its full-screen drill-downs
 * share: the board stays composed under a drill-down, so a tapped
 * window's full-screen view opens on exactly the state the window was
 * showing (the web maximizes the same live window, so its month, tab,
 * and day carry over).
 *
 * Owned once at the tablet root and passed to every board window and
 * its drill-down, so both always agree.
 */
class TabletSectionState(
    year: Int,
    month: Int,
    calendarTab: String,
    selectedDay: String?,
) {
    var year by mutableIntStateOf(year)
    /** 1-based month. */
    var month by mutableIntStateOf(month)
    /** The calendar's active tab ([SUMMARY] or a calendar id). */
    var calendarTab by mutableStateOf(calendarTab)
    /** The calendar's selected day ("YYYY-MM-DD"), or null. */
    var selectedDay by mutableStateOf(selectedDay)
    /** The push pane's open history tab (a push id, or null = latest). */
    var pushTab by mutableStateOf<String?>(null)

    companion object {
        val Saver: Saver<TabletSectionState, Array<Any?>> = Saver(
            save = {
                arrayOf<Any?>(it.year, it.month, it.calendarTab, it.selectedDay, it.pushTab)
            },
            restore = { a ->
                TabletSectionState(
                    year = a[0] as Int,
                    month = a[1] as Int,
                    calendarTab = a[2] as String,
                    selectedDay = a[3] as String?,
                ).apply { pushTab = a[4] as String? }
            },
        )
    }
}

/** The tablet's shared section state, seeded on the current month and day. */
@Composable
fun rememberTabletSectionState(): TabletSectionState {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    return rememberSaveable(saver = TabletSectionState.Saver) {
        TabletSectionState(
            year = today.year,
            month = today.monthValue,
            calendarTab = SUMMARY,
            selectedDay = MonthGrid.dayKey(today),
        )
    }
}
