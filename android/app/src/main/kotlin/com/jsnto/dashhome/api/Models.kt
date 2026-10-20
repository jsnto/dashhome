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
    /** The tile grid's dimensions (cols x rows units); null = the 12x12 default. */
    val grid: GridDims? = null,
    /** The tile layout; the backend always sends the complete effective layout. */
    val views: BoardViews? = null,
)

/** The board's tile grid dimensions: cols x rows units, each unit split
 *  evenly (1/cols of the width, 1/rows of the height). */
data class GridDims(val cols: Int, val rows: Int)

/**
 * A tile's explicit placement on the board's tile grid (cols x rows
 * units). 1-based: [x] is the starting column, [y] the starting row, [w]
 * and [h] the spans in columns and rows. A tile without a placement is
 * placed automatically (it flows in board order at its size step's
 * width).
 */
data class TileGeometry(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * The board's tile layout (the board file's `views` section): the tile
 * order, which tiles are hidden, how the Calendar tile renders, the
 * per-tile size steps, the explicit per-tile grid placements, and the
 * Home Assistant board tiles.
 */
data class BoardViews(
    /** Tile IDs in board order (the four view IDs plus every ha_tiles id). */
    val order: List<String> = emptyList(),
    /** Tile IDs hidden from the board. */
    val hidden: List<String> = emptyList(),
    /** How the Calendar tile renders: "month" (grid) or "schedule" (list). */
    val calendarMode: String = "month",
    /** Per-tile size step ("s"/"m"/"l"/"xl"); missing = "m". */
    val sizes: Map<String, String> = emptyMap(),
    /** Explicit per-tile grid placements; missing = automatic placement. */
    val geometry: Map<String, TileGeometry> = emptyMap(),
    /** Home Assistant board tiles (button or visual), bound to entities. */
    val haTiles: List<HABoardTile> = emptyList(),
)

/** A Home Assistant board tile: bound to exactly one HA entity. */
data class HABoardTile(
    /** Tile id: "ha-" + 1-16 lowercase alphanumerics (e.g. "ha-1"). */
    val id: String,
    /** The HA entity_id the tile is bound to (e.g. "light.kitchen"). */
    val entity: String,
    /** Display label; falls back to the entity's own label. */
    val label: String? = null,
    /** The tile kind: "button" or "visual". */
    val kind: String,
)

/** A Home Assistant entity from GET /api/ha/entities (state snapshot). */
data class HAEntity(
    val entityId: String,
    val label: String = "",
    val friendly: String = "",
    val state: String = "",
    val lastUpdated: String? = null,
    val lastChanged: String? = null,
)

/** The result of POST /api/ha/service (an allowlisted action on one entity). */
data class HAServiceResult(
    val ok: Boolean,
    val entityId: String,
    val action: String,
    val result: Map<String, Any?>? = null,
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

