package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.jsnto.dashhome.api.ApiEvent
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.ui.state.Schedule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar's schedule summary rows match the web's ScheduleList item:
 * the per-event calendar label the web shows on every item
 * (`e.calendar_label && <span class="schedule-cal">`), no location (the
 * web's item has none - the day detail does), and the web's item order
 * (time, title, label).
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

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun scheduleRowsMatchTheWebItem() {
        val d = LocalDate.now().plusDays(1)
        server = FakeDashServer().also {
            it.calendarsJson = """
                [
                  {"id": "cal-1", "label": "Work", "primary": true}
                ]
            """.trimIndent()
            // An upcoming event with a location: the web's schedule item
            // renders no location (the day detail does).
            it.eventsJson = """
                [
                  {"id": "e1", "calendar_id": "cal-1", "calendar_label": "Work", "summary": "Standup", "location": "Gym", "start": "${d}T09:00:00Z", "end": "${d}T10:00:00Z"}
                ]
            """.trimIndent()
        }
        DashApi.baseUrl = server.baseUrl
        composeRule.setContent {
            CalendarScreen(calendarMode = "schedule")
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Standup").fetchSemanticsNodes().isNotEmpty()
        }

        // No node renders the location (the web's item has none).
        composeRule.onAllNodesWithText("Gym", substring = true).assertCountEquals(0)

        // The item order is time-first: the when-line sits to the left of
        // the title (the web's schedule-time, schedule-title).
        val ev = ApiEvent(
            calendarId = "cal-1",
            calendarLabel = "Work",
            id = "e1",
            summary = "Standup",
            location = "Gym",
            start = "${d}T09:00:00Z",
            end = "${d}T10:00:00Z",
            allDay = false,
        )
        val whenText = Schedule.whenText(ev, ZoneId.systemDefault())
        val whenNode = composeRule.onNodeWithText(whenText).fetchSemanticsNode()
        val titleNode = composeRule.onNodeWithText("Standup").fetchSemanticsNode()
        assertTrue(whenNode.positionInRoot.x < titleNode.positionInRoot.x)
    }
}
