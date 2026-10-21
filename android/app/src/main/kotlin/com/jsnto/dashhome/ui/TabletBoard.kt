package com.jsnto.dashhome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.Board
import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.api.GridDims
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.HAEntity
import com.jsnto.dashhome.api.TileGeometry
import com.jsnto.dashhome.ui.state.BoardState
import com.jsnto.dashhome.ui.state.ChatState
import com.jsnto.dashhome.ui.state.DragMode
import com.jsnto.dashhome.ui.state.TabletSectionState
import com.jsnto.dashhome.ui.state.VIEW_IDS
import com.jsnto.dashhome.ui.state.autoLayout
import com.jsnto.dashhome.ui.state.dragCell
import com.jsnto.dashhome.ui.state.dropRefused
import com.jsnto.dashhome.ui.state.gridMetrics
import com.jsnto.dashhome.ui.state.haTileLabel
import com.jsnto.dashhome.ui.state.normalizeGrid
import com.jsnto.dashhome.ui.state.normalizeViews
import com.jsnto.dashhome.ui.state.renderedRows
import com.jsnto.dashhome.ui.state.tileName
import com.jsnto.dashhome.ui.state.visibleOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

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
private fun itemForView(id: String): TabletItem = when (id) {
    "calendar" -> TabletItem.CALENDAR
    "home" -> TabletItem.HOME
    else -> TabletItem.HERMES
}

/** The board's window gap: the web's 16px grid gap. */
private val BOARD_GAP = 16.dp

/** How often the board polls Home Assistant entity states (the web's
 *  POLL_MS: 10 s while any HA tile is visible). */
private const val HA_POLL_MS = 10_000L

/** The web's drag threshold: below this the press is a click, not a drag. */
private const val DRAG_MIN_PX = 4f

/** A live drag/resize preview (the web's {id, cell, refused} preview). */
private data class DragPreview(
    val id: String,
    val cell: TileGeometry,
    val refused: Boolean,
)

/** One drag/resize session (the web's dragRef: the live drag data). */
private class DragSession(
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
private class DragContext(
    val layout: Map<String, TileGeometry>,
    val views: BoardViews,
    val visible: List<String>,
    val grid: GridDims,
    val rows: Int,
)

/**
 * The tablet one-page board - the Android counterpart of the web
 * dashboard's board (frontend/src/App.tsx): the four dashboard views
 * (plus any Home Assistant tiles) are the tiles of a grid of cols x rows
 * units (12x12 by default, editable in Settings and persisted to the
 * board file as board.grid). The layout comes from the board file -
 * `board.grid`/`board.views` from GET /api/config, which the backend
 * returns normalized - rendered as the stored placements
 * (views.geometry) plus the auto flow of the rest (the pure math in
 * [BoardLayout], the port of the web's tileGrid.ts). A tile either flows
 * automatically (its size step's fraction of the grid's width) or sits at
 * its stored placement.
 *
 * Edit mode exposes the placement affordances, mirroring the web: drag
 * the grip or the corner resize handle (both snapping to the grid, with a
 * live preview and a refusal snap-back), the settings button (the
 * per-tile modal), and the remove button. The title tap keeps the board's
 * drill-down affordance as the web's maximize: a built-in view's title
 * opens that section's full-screen view, and a Home Assistant tile's
 * title fills the board with that tile (the web's data-max; its live
 * content keeps composing). Saves go through PUT /api/config/views
 * (partial update; the server re-normalized layout is adopted).
 *
 * [state] is the shared board state the Settings drill-down writes back
 * through (the board adopts every board the settings screen persists,
 * the web's onBoard), so both always agree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletBoard(
    state: BoardState,
    chat: ChatState,
    chatScope: CoroutineScope,
    sections: TabletSectionState,
    onOpen: (TabletItem) -> Unit,
    dark: Boolean,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val gapPx = with(density) { BOARD_GAP.roundToPx() }

    // The board's GET /api/config load: the grid, the views, and the
    // integration flags below all derive from that answer (the web loads
    // the config on mount and retries while it is missing). Keyed on the
    // server base: when the user points the app at a different server,
    // the previous server's board answer is dropped and re-fetched.
    LaunchedEffect(DashApi.baseUrl) {
        state.reset()
        while (state.apiConfig == null) {
            try {
                state.load(DashApi.getConfig())
            } catch (e: ApiException) {
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    // The board's effective layout, derived from the server's answer the
    // same way the web derives it (App.tsx): normalize the grid (default
    // 12x12) and views (defaults filled in), the visible order (hidden
    // omitted), and the effective layout (stored placements plus the auto
    // flow of the rest).
    val grid = remember(state.apiConfig) { normalizeGrid(state.apiConfig?.board?.grid) }
    val views = remember(state.apiConfig, grid) {
        normalizeViews(state.apiConfig?.board?.views, grid)
    }
    val visible = remember(views, grid) { visibleOrder(views, grid) }
    val layout = remember(visible, views, grid) {
        autoLayout(visible, views.sizes, views.geometry, grid.cols)
    }
    // The rows the board actually renders: the auto flow is open on rows,
    // so a layout that overflows the grid's rows stretches the board with
    // auto rows (the web's renderedRows).
    val rows = remember(layout, grid) { renderedRows(layout, grid.rows) }

    // Integration flags from /api/config drive the panes' friendly setup
    // notes (defaulting to configured, like the previous board).
    val cfg = state.apiConfig
    val hermesChat = cfg?.hermesChat ?: true
    val hermesPush = cfg?.hermesPush ?: true

    // The board's rendered size, for the pointer-to-cell math (the web's
    // measureGrid runs off the board element's box).
    var boardSize by remember { mutableStateOf(IntSize.Zero) }

    var editMode by remember { mutableStateOf(false) }
    // The tile being edited in the per-tile settings modal, if any.
    var editingTile by remember { mutableStateOf<String?>(null) }
    // The Home Assistant tile filling the board (the web's maximize), if
    // any. Built-in views drill down instead.
    var maximized by remember { mutableStateOf<String?>(null) }
    // A layout save that failed (shown above the board, cleared on the
    // next attempt, like the web's board-err note).
    var layoutErr by remember { mutableStateOf<ApiException?>(null) }
    // A board write in flight (the web's savingSize): the remove control is
    // disabled and removeTile is gated while one runs.
    var saving by remember { mutableStateOf(false) }
    // The live drag/resize preview (null while no drag is in flight).
    var preview by remember { mutableStateOf<DragPreview?>(null) }

    // Home Assistant entity states: polled while any HA tile is visible
    // (the web's useHAEntities: on mount, on a 10 s timer; the 503
    // "not configured" is reported via haOff rather than an error, and a
    // transient 502 keeps the last good states).
    val haVisible = remember(visible) { visible.filter { it !in VIEW_IDS } }
    var haEntities by remember { mutableStateOf<Map<String, HAEntity>>(emptyMap()) }
    var haOff by remember { mutableStateOf(false) }
    val haKey = haVisible.joinToString(",")

    val haLoad: () -> Unit = remember {
        {
            scope.launch {
                try {
                    haEntities = DashApi.getHAEntities().associateBy { it.entityId }
                    haOff = false
                } catch (e: ApiException) {
                    if (e.code == "homeassistant_not_configured") haOff = true
                }
            }
        }
    }
    LaunchedEffect(DashApi.baseUrl, haKey) {
        if (haKey.isEmpty()) return@LaunchedEffect
        haLoad()
        while (true) {
            delay(HA_POLL_MS)
            haLoad()
        }
    }

    // A board tile's display name: a Home Assistant tile wears the
    // entity's friendly label (the web's HATile card head: the tile's
    // label, else the entity's own label, else the id); view tiles wear
    // their view/label name.
    fun displayName(id: String): String {
        val ht = views.haTiles.firstOrNull { it.id == id }
        return if (ht != null) {
            haTileLabel(ht, haEntities[ht.entity])
        } else {
            tileName(id, views)
        }
    }


    // The live drag data (the web's dragRef, kept out of composition).
    // [ctx] carries the board state the pointer math reads from, refreshed
    // every composition so the attach-time drag closures always compute from
    // the current layout, not the first composition's.
    val dragHolder = remember {
        object {
            var session: DragSession? = null
            var ctx = DragContext(layout, views, visible, grid, rows)
        }
    }
    dragHolder.ctx = DragContext(layout, views, visible, grid, rows)

    // A board-views patch through the guarded write path (the same
    // PUT /api/config/views save the geometry changes go through); the
    // server's answer (the saved, re-normalized layout) is adopted.
    fun commitViews(
        order: List<String>? = null,
        hidden: List<String>? = null,
        calendarMode: String? = null,
        sizes: Map<String, String>? = null,
        haTiles: List<HABoardTile>? = null,
        geometry: Map<String, TileGeometry?>? = null,
    ) {
        if (state.apiConfig == null) return
        saving = true
        layoutErr = null
        scope.launch {
            try {
                state.adopt(DashApi.setViews(order, hidden, calendarMode, sizes, geometry, haTiles))
            } catch (e: ApiException) {
                layoutErr = e
            } finally {
                saving = false
            }
        }
    }

    // Edit mode's per-tile remove control: a single press drops the tile
    // from the board (no confirm - a removed tile can be added back from
    // Settings). A Home Assistant tile is dropped from views.ha_tiles
    // (only the board's reference goes - its entity and data are
    // untouched); a built-in view is hidden (it keeps its stored
    // placement, so re-showing it restores the tile).
    fun removeTile(id: String) {
        if (saving) return
        if (id in VIEW_IDS) {
            if (id in views.hidden) return
            commitViews(hidden = views.hidden + id)
        } else {
            commitViews(haTiles = views.haTiles.filter { it.id != id })
        }
    }

    // Entering edit mode clears a maximized tile: with the other tiles
    // hidden there would be nothing to move or size.
    fun toggleEditMode() {
        if (!editMode) maximized = null
        editMode = !editMode
    }

    // Leaving edit mode cancels any live drag (the web's editMode effect).
    LaunchedEffect(editMode) {
        if (!editMode) {
            dragHolder.session = null
            preview = null
        }
    }

    // A maximized tile that gets hidden must not leave the board
    // maximized to nothing: restore the full grid (the web's effect).
    LaunchedEffect(visible.joinToString(",")) {
        maximized?.let { m ->
            if (m !in visible) maximized = null
        }
    }

    // --- Drag & resize (edit mode only). The pointer-to-cell math is the
    // web's: the grip/resize handle reports its pointer travel (in whole
    // cells via dragCell, the port of tileGrid.ts), the preview is
    // checked with dropRefused (overlap/off-board refused with a
    // snap-back), and an accepted drop saves through the board-views
    // geometry patch.

    fun beginDrag(id: String, mode: DragMode, start: Offset) {
        if (!editMode || boardSize == IntSize.Zero) return
        val ctx = dragHolder.ctx
        val metrics = gridMetrics(
            boardWidthPx = boardSize.width.toFloat(),
            boardHeightPx = boardSize.height.toFloat(),
            rows = ctx.rows,
            cols = ctx.grid.cols,
            gapPx = gapPx.toFloat(),
        ) ?: return
        val origin = ctx.layout[id] ?: return
        dragHolder.session = DragSession(
            mode = mode,
            id = id,
            origin = origin,
            start = start,
            metrics = metrics,
            cols = ctx.grid.cols,
            rows = ctx.grid.rows,
            moved = mode == DragMode.RESIZE, // a resize press is intentional
        )
    }

    fun onDragMove(current: Offset) {
        val d = dragHolder.session ?: return
        val ctx = dragHolder.ctx
        val dxPx = (current.x - d.start.x).toFloat()
        val dyPx = (current.y - d.start.y).toFloat()
        if (!d.moved && d.mode == DragMode.MOVE && hypot(dxPx, dyPx) < DRAG_MIN_PX) return
        d.moved = true
        val cell = dragCell(d.mode, d.origin, dxPx, dyPx, d.metrics, d.cols, d.rows)
        val refused = dropRefused(
            d.id,
            cell,
            ctx.visible,
            ctx.views.sizes,
            ctx.views.geometry,
            d.cols,
            d.rows,
        )
        preview = DragPreview(d.id, cell, refused)
    }

    fun endDrag(commit: Boolean) {
        val d = dragHolder.session
        dragHolder.session = null
        if (d == null || !commit || !d.moved) {
            preview = null
            return
        }
        val pv = preview
        if (pv == null || pv.refused || pv.id != d.id) {
            preview = null // refused (or unchanged): snap back to the stored placement
            return
        }
        val cur = dragHolder.ctx.layout[d.id] ?: return
        if (pv.cell == cur) {
            preview = null
            return
        }
        preview = null
        commitViews(geometry = mapOf(d.id to pv.cell))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("dashhome") },
                actions = {
                    TextButton(
                        onClick = { toggleEditMode() },
                        modifier = Modifier.testTag("boardEditToggle"),
                    ) {
                        Text("Edit tiles")
                    }
                    IconButton(onClick = { onOpen(TabletItem.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            layoutErr?.let { err ->
                Text(
                    text = if (err.detail.isNullOrBlank()) err.code else "${err.code} — ${err.detail}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boardSize = it }
                    .testTag("boardLayout"),
            ) {
                val unitWDp = if (boardSize.width > 0) {
                    with(density) {
                        ((boardSize.width - (grid.cols - 1) * gapPx).toFloat() / grid.cols).toDp()
                    }
                } else 0.dp
                val unitHDp = if (boardSize.height > 0) {
                    with(density) {
                        ((boardSize.height - (rows - 1) * gapPx).toFloat() / rows).toDp()
                    }
                } else 0.dp
                val maxId = maximized
                if (boardSize != IntSize.Zero && visible.isNotEmpty()) {
                    if (maxId != null && maxId in visible && maxId !in VIEW_IDS) {
                        // A Home Assistant tile fills the board (the web's
                        // data-max): the other tiles are hidden, its live
                        // content keeps composing, the title restores the
                        // grid.
                        BoardTile(
                            id = maxId,
                            name = displayName(maxId),
                            cell = TileGeometry(1, 1, grid.cols, rows),
                            unitW = unitWDp,
                            unitH = unitHDp,
                            editMode = false,
                            onTitleClick = { maximized = null },
                        ) {
                            HaContent(maxId, views, haEntities, haOff, haLoad, dark)
                        }
                    } else {
                        visible.forEach { id ->
                            val base = layout[id] ?: return@forEach
                            val cell = preview?.takeIf { it.id == id }?.cell ?: base
                            BoardTile(
                                id = id,
                                name = displayName(id),
                                cell = cell,
                                unitW = unitWDp,
                                unitH = unitHDp,
                                editMode = editMode,
                                saving = saving,
                                dragging = preview?.id == id,
                                refused = preview?.id == id && preview?.refused == true,
                                onTitleClick = {
                                    when {
                                        editMode -> Unit
                                        id in VIEW_IDS -> onOpen(itemForView(id))
                                        else -> maximized = if (maxId == id) null else id
                                    }
                                },
                                onEdit = { editingTile = id },
                                onRemove = { removeTile(id) },
                                onGripDown = { o -> beginDrag(id, DragMode.MOVE, o) },
                                onGripDrag = { _, cur -> onDragMove(cur) },
                                onGripEnd = { endDrag(true) },
                                onGripCancel = { endDrag(false) },
                                onResizeDown = { o -> beginDrag(id, DragMode.RESIZE, o) },
                                onResizeDrag = { _, cur -> onDragMove(cur) },
                                onResizeEnd = { endDrag(true) },
                                onResizeCancel = { endDrag(false) },
                            ) {
                                TileContent(
                                    id,
                                    views,
                                    chat,
                                    chatScope,
                                    sections,
                                    haEntities,
                                    haOff,
                                    haLoad,
                                    hermesChat,
                                    hermesPush,
                                    dark,
                                )
                            }
                        }
                    }
                    // The dragged tile renders at its live preview cell
                    // (the web's .tile.dragging: the tile itself moves,
                    // opacity 0.9; a refused drop reads as invalid with
                    // the danger outline, and snaps back on the drop).
                } else if (visible.isEmpty()) {
                    // The web's board-empty state.
                    Note(
                        text = "All dashboard tiles are hidden. Open Settings → Tiles to show one again.",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }

    // The per-tile settings modal (the web's TileEditModal): the size
    // step for view tiles, the Calendar tile's view mode, and an HA
    // tile's entity/kind/label - saved through the same board-views path.
    if (editingTile != null && editingTile in visible) {
        val id = editingTile!!
        TileEditModal(
            id = id,
            views = views,
            entities = haEntities,
            haOff = haOff,
            onBoard = { board ->
                state.adopt(board)
                editingTile = null
            },
            onClose = { editingTile = null },
        )
    }
}

/** The content of one board tile (the web's winNode). */
@Composable
private fun TileContent(
    id: String,
    views: BoardViews,
    chat: ChatState,
    chatScope: CoroutineScope,
    sections: TabletSectionState,
    haEntities: Map<String, HAEntity>,
    haOff: Boolean,
    haLoad: () -> Unit,
    hermesChat: Boolean,
    hermesPush: Boolean,
    dark: Boolean,
) {
    when (id) {
        "calendar" -> CalendarScreen(
            wide = true,
            shared = sections,
            calendarMode = views.calendarMode,
        )
        "chat" -> ChatPane(state = chat, scope = chatScope, configured = hermesChat)
        "home" -> HomeScreen(showTitle = false)
        "push" -> PushPane(configured = hermesPush, shared = sections)
        else -> HaContent(id, views, haEntities, haOff, haLoad, dark)
    }
}

/** The content of a Home Assistant board tile (the web's HATile). */
@Composable
private fun HaContent(
    id: String,
    views: BoardViews,
    haEntities: Map<String, HAEntity>,
    haOff: Boolean,
    haLoad: () -> Unit,
    dark: Boolean,
) {
    val tile = views.haTiles.firstOrNull { it.id == id } ?: return
    HATileView(
        tile = tile,
        entity = haEntities[tile.entity],
        haOff = haOff,
        onService = haLoad,
        dark = dark,
    )
}

/**
 * One board tile: the web's `.tile`/`.card` - a card placed on the grid
 * by its effective cell (the stored placement or the auto flow, the
 * live preview cell while a drag is in flight), filled with the section's
 * live content under a title bar (the web's card-head title; the maximize
 * affordance there). A drag preview renders the tile at the candidate
 * cell at 0.9 opacity, with the danger outline when the drop would be
 * refused (the web's `.tile.dragging` / `.tile.preview-invalid`). In edit
 * mode the tile carries the web's tile-edit row (the move label, the drag
 * grip, the settings and remove buttons) and the corner resize handle.
 */
@Composable
private fun BoardTile(
    id: String,
    name: String,
    cell: TileGeometry,
    unitW: androidx.compose.ui.unit.Dp,
    unitH: androidx.compose.ui.unit.Dp,
    editMode: Boolean,
    saving: Boolean = false,
    dragging: Boolean = false,
    refused: Boolean = false,
    onTitleClick: () -> Unit,
    onEdit: () -> Unit = {},
    onRemove: () -> Unit = {},
    onGripDown: (Offset) -> Unit = {},
    onGripDrag: (Offset, Offset) -> Unit = { _, _ -> },
    onGripEnd: () -> Unit = {},
    onGripCancel: () -> Unit = {},
    onResizeDown: (Offset) -> Unit = {},
    onResizeDrag: (Offset, Offset) -> Unit = { _, _ -> },
    onResizeEnd: () -> Unit = {},
    onResizeCancel: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gap = BOARD_GAP
    val wDp = maxOf(unitW * cell.w + gap * (cell.w - 1), 1.dp)
    val hDp = maxOf(unitH * cell.h + gap * (cell.h - 1), 1.dp)
    val xPx = with(density) { ((cell.x - 1) * (unitW + gap)).roundToPx() }
    val yPx = with(density) { ((cell.y - 1) * (unitH + gap)).roundToPx() }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(surface = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 0.dp,
            modifier = Modifier
                .offset { IntOffset(xPx, yPx) }
                .size(width = wDp, height = hDp)
                .then(
                    if (dragging && refused) {
                        // The refused drop's danger outline (the web's
                        // .preview-invalid).
                        Modifier.border(
                            2.dp,
                            Color(0xFFE5534B),
                            shape = MaterialTheme.shapes.medium,
                        )
                    } else Modifier,
                )
                .testTag("boardWin-$id")
                .semantics { contentDescription = "$name tile" }
                .then(if (dragging) Modifier.alpha(0.9f) else Modifier),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !editMode, onClick = onTitleClick)
                        .testTag("boardTitle-$id")
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (editMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                            .testTag("boardEditRow-$id"),
                    ) {
                        Text(
                            text = "Move",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        // The move grip (the web's .tile-grip): drag it to
                        // move the tile; the pointer math snaps to the
                        // grid's cells.
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .pointerInput(Unit) {
                                    var down: Offset? = null
                                    detectDragGestures(
                                        onDragStart = { o ->
                                            down = o
                                            onGripDown(o)
                                        },
                                        onDrag = { change, _ ->
                                            down?.let { onGripDrag(it, change.position) }
                                        },
                                        onDragEnd = onGripEnd,
                                        onDragCancel = onGripCancel,
                                    )
                                }
                                .testTag("boardGrip-$id")
                                .semantics { contentDescription = "Drag to move this tile" },
                        ) {
                            Text(
                                text = "⠿",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        TextButton(
                            onClick = onEdit,
                            modifier = Modifier
                                .testTag("boardEdit-$id")
                                .semantics { contentDescription = "Edit tile settings" },
                        ) {
                            Text("✎")
                        }
                        TextButton(
                            onClick = onRemove,
                            enabled = !saving,
                            modifier = Modifier
                                .testTag("boardRemove-$id")
                                .semantics { contentDescription = "Remove tile from the board" },
                        ) {
                            Text("✕")
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    content()
                }
                }
                if (editMode) {
                    // The corner resize handle (the web's .tile-resize,
                    // bottom-right): drag it to resize width + height.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 6.dp, bottom = 6.dp)
                            .size(18.dp)
                            .pointerInput(Unit) {
                                var down: Offset? = null
                                detectDragGestures(
                                    onDragStart = { o ->
                                        down = o
                                        onResizeDown(o)
                                    },
                                    onDrag = { change, _ ->
                                        down?.let { onResizeDrag(it, change.position) }
                                    },
                                    onDragEnd = onResizeEnd,
                                    onDragCancel = onResizeCancel,
                                )
                            }
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(bottomEnd = 8.dp),
                            )
                            .testTag("boardResize-$id")
                            .semantics { contentDescription = "Drag to resize this tile" },
                    )
                }
            }
        }
    }
}

/**
 * The full-screen drill-down for a tapped board window: the same view the
 * app has today - the Calendar's wide two-pane layout, the Home WebView,
 * the Hermes wide layout, or the Settings screen - now with a back
 * button (and system back) that returns to the board. It composes over
 * the still-mounted board and reads the shared [sections], so it opens
 * on the state the tapped window was showing, and the board's live
 * windows survive the round trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletDrillDown(
    item: TabletItem,
    chat: ChatState,
    chatScope: CoroutineScope,
    sections: TabletSectionState,
    theme: String,
    onThemePick: (String) -> Unit,
    onBoard: (Board) -> Unit,
    onBack: () -> Unit,
    onClearServer: () -> Unit,
) {
    if (item == TabletItem.SETTINGS) {
        // Settings brings its own Scaffold, BackAppBar, and BackHandler.
        Box(modifier = Modifier.fillMaxSize().testTag("drilldown-settings")) {
            SettingsScreen(
                onBack = onBack,
                onClearServer = onClearServer,
                theme = theme,
                onThemePick = onThemePick,
                onBoard = onBoard,
            )
        }
        return
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = { BackAppBar(title = item.title, onBack = onBack) },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("drilldown-${item.name.lowercase()}"),
        ) {
            when (item) {
                TabletItem.CALENDAR -> CalendarScreen(wide = true, shared = sections)
                TabletItem.HOME -> HomeScreen(showTitle = false)
                TabletItem.HERMES -> HermesScreen(
                    chat = chat,
                    chatScope = chatScope,
                    wide = true,
                    shared = sections,
                )
                TabletItem.SETTINGS -> Unit
            }
        }
    }
}
