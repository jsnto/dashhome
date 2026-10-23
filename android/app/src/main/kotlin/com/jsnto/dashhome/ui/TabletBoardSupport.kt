package com.jsnto.dashhome.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.GridDims
import com.jsnto.dashhome.api.TileGeometry
import com.jsnto.dashhome.ui.state.DragMode

/**
 * The sections the tablet board can drill down into: the three dashboard
 * sections plus Settings (what the previous navigation rail showed).
 * Every built-in board tile maps to one of these existing full-screen
 * views - no new sections.
 */
enum class TabletItem(val title: String) {
    CALENDAR("Calendar"),
    HOME("Home"),
    HERMES("Hermes"),
    SETTINGS("Settings"),
}

/** The section a built-in view tile's title opens (the web's maximize
 *  affordance resolves to the section's full-screen view; the chat and
 *  push tiles both resolve to the Hermes section, like the previous
 *  board's windows). */
internal fun itemForView(id: String): TabletItem = when (id) {
    "calendar" -> TabletItem.CALENDAR
    "home" -> TabletItem.HOME
    else -> TabletItem.HERMES
}

/** The board's window gap: the web's 16px grid gap. */
internal val BOARD_GAP = 16.dp

/** How often the board polls Home Assistant entity states (the web's
 *  POLL_MS: 10 s while any HA tile is visible). */
internal const val HA_POLL_MS = 10_000L

/** The web's drag threshold: below this the press is a click, not a drag. */
internal const val DRAG_MIN_PX = 4f

/** A live drag/resize preview (the web's {id, cell, refused} preview). */
internal data class DragPreview(
    val id: String,
    val cell: TileGeometry,
    val refused: Boolean,
)

/** One drag/resize session (the web's dragRef: the live drag data). */
internal class DragSession(
    val mode: DragMode,
    val id: String,
    val origin: TileGeometry,
    val start: Offset,
    val metrics: com.jsnto.dashhome.ui.state.GridMetrics,
    val cols: Int,
    val rows: Int,
    var moved: Boolean,
)

/** The board state the drag/resize pointer math reads from: the current
 *  effective layout, the normalized views, the visible order, the grid, and
 *  the rendered row count. It is refreshed on the drag holder every
 *  composition, because the grip/resize pointer blocks (pointerInput keyed
 *  on Unit) run once and keep their attach-time closures - so the math
 *  reads the live values here rather than the captured vals (the web holds
 *  this by re-attaching its window listeners per composition). */
internal class DragContext(
    val layout: Map<String, TileGeometry>,
    val views: BoardViews,
    val visible: List<String>,
    val grid: GridDims,
    val rows: Int,
)
