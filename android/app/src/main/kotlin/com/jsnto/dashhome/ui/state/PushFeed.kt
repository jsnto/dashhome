package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.Push
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * State logic for the Hermes markdown push feed - the mobile counterpart
 * of the web's PushPanel. Pure and unit-testable.
 */
object PushFeed {

    /** How many history tabs the web shows (plus the Latest tab). */
    const val HISTORY_TABS = 12

    /** How many history pushes the app fetches from the API. */
    const val FETCH_LIMIT = 50

    data class Tab(val id: String?, val label: String)

    private val shortDateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val metaFmt = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US)

    /** Sort pushes newest first: received_at descending, id as tiebreak. */
    fun newestFirst(pushes: List<Push>): List<Push> =
        pushes.sortedWith(
            compareByDescending<Push> { parseInstant(it.receivedAt) }
                .thenByDescending { it.id },
        )

    /** The feed's tab row: "Latest" first, then short dates, newest first. */
    fun tabs(history: List<Push>): List<Tab> =
        listOf(Tab(null, "Latest")) +
            newestFirst(history).take(HISTORY_TABS).map { Tab(it.id, shortDate(it.receivedAt)) }

    /**
     * The push the pane shows: with no tab selected, the latest push;
     * otherwise the fetched detail for the selected tab when it is
     * still current, else the selected push from the history list (the
     * list carries the same markdown). A fetched push for a different
     * tab is ignored, so the pane never renders content under the wrong
     * tab - even when the selected tab moved in a shared pane while this
     * one's fetch is still in flight.
     */
    fun shown(open: Push?, openTab: String?, history: List<Push>): Push? =
        if (openTab == null) {
            history.firstOrNull()
        } else {
            (open?.takeIf { it.id == openTab }) ?: history.firstOrNull { it.id == openTab }
        }

    /** "Oct 3" from an RFC3339 timestamp. */
    fun shortDate(iso: String): String =
        parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(shortDateFmt) ?: iso

    /**
     * The push's meta line, e.g.
     * "Oct 3, 2026, 9:41 AM · hermes (latest)".
     */
    fun metaLine(push: Push, latest: Boolean): String {
        val whenText = parseInstant(push.receivedAt)
            ?.atZone(ZoneId.systemDefault())?.format(metaFmt)
            ?: push.receivedAt
        val source = push.source?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return "$whenText$source${if (latest) " (latest)" else " (previous push)"}"
    }

    private fun parseInstant(iso: String): Instant? =
        try {
            Instant.parse(iso)
        } catch (e: Exception) {
            null
        }
}
