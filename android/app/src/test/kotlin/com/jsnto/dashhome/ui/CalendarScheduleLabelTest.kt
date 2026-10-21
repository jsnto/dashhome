package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.jsnto.dashhome.api.DashApi
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The calendar's schedule summary carries the per-event calendar label
 * the web's ScheduleList shows on every item (`e.calendar_label &&
 * <span class="schedule-cal">`): with two or more calendars, each row of
 * the schedule mode says which calendar its event belongs to.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarScheduleLabelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: FakeDashServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun scheduleRowsCarryTheEventCalendarLabel() {
        server = FakeDashServer().also {
            it.calendarsJson = """
                [
                  {"id": "cal-1", "label": "Work", "primary": true},
                  {"id": "cal-2", "label": "Home"}
                ]
            """.trimIndent()
            // One upcoming event per calendar, each wearing its
            // calendar's label (the web's per-event calendar_label).
            val a = LocalDate.now().plusDays(1)
            val b = LocalDate.now().plusDays(2)
            it.eventsJson = """
                [
                  {"id": "e1", "calendar_id": "cal-1", "calendar_label": "Work", "summary": "Standup", "start": "${a}T09:00:00Z", "end": "${a}T10:00:00Z"},
                  {"id": "e2", "calendar_id": "cal-2", "calendar_label": "Home", "summary": "Groceries", "start": "${b}T09:00:00Z", "end": "${b}T10:00:00Z"}
                ]
            """.trimIndent()
        }
        DashApi.baseUrl = server.baseUrl
        composeRule.setContent {
            CalendarScreen(calendarMode = "schedule")
        }
        composeRule.waitForIdle()

        // Both events settle in the schedule list.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Standup").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Groceries").fetchSemanticsNodes().isNotEmpty()
        }
        // Each label now appears twice: once in the calendar tabs, once
        // in its event's row (the web's .schedule-cal) - before the row
        // label existed, each appeared only in the tabs.
        composeRule.onAllNodesWithText("Work").assertCountEquals(2)
        composeRule.onAllNodesWithText("Home").assertCountEquals(2)
    }
}
