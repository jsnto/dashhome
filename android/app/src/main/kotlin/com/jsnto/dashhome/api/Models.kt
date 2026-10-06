package com.jsnto.dashhome.api

/**
 * Typed models mirroring the dashhome backend API JSON shapes (see the
 * README's API table). All times are RFC3339 strings straight from the
 * wire; formatting happens in ui/state.
 */

/** The board (dashboard config file) portion of GET /api/config. No secrets. */
data class Board(
    val haEntities: List<String> = emptyList(),
    val haLabels: Map<String, String> = emptyMap(),
    val calendars: List<CalendarRef> = emptyList(),
    val refreshSeconds: Int = 30,
    /** Dashboard url_path the HA embed shows; null = the default dashboard. */
    val haEmbedUrlPath: String? = null,
    /** Whether the embed URL carries the long-lived token. */
    val haEmbedTokenUrl: Boolean = false,
)

/** One calendar reference in the board config. */
data class CalendarRef(val id: String, val label: String)

/** Google Calendar integration status from /api/config (booleans only). */
data class GoogleStatus(
    val configured: Boolean,
    val mode: String? = null,
    val loginRequired: Boolean = false,
    val loginUrl: String? = null,
)

/** GET /api/config: board config plus per-integration status booleans. */
data class ApiConfig(
    val board: Board,
    val homeAssistant: Boolean,
    val google: GoogleStatus,
    val hermesChat: Boolean,
    val hermesPush: Boolean,
)

/** One calendar tab as returned by GET /api/calendars. */
data class CalendarInfo(
    val id: String,
    val summary: String = "",
    val label: String = "",
    val primary: Boolean = false,
)

/**
 * One calendar event (GET /api/events). [start]/[end] are RFC3339; for
 * all-day events the backend emits the day at local midnight.
 */
data class ApiEvent(
    val calendarId: String,
    val calendarLabel: String = "",
    val id: String,
    val summary: String = "",
    val description: String? = null,
    val location: String? = null,
    val start: String,
    val end: String,
    val allDay: Boolean,
)

/** One Hermes markdown push (GET /api/hermes/pushes[/...]). */
data class Push(
    val id: String,
    val receivedAt: String,
    val source: String? = null,
    val markdown: String,
)

/** One chat turn in the conversation sent to /api/hermes/chat. */
data class ChatMessage(val role: String, val content: String)
