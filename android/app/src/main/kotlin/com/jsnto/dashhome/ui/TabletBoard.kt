package com.jsnto.dashhome.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi
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

    // Home Assistant entity states (TabletBoardHa.kt): the entity data
    // the tiles read, the honest "not configured" flag, and the on-demand
    // refresh (the web's useHAEntities).
    val ha = boardHaEntities(visible, scope)

    // A board tile's display name: a Home Assistant tile wears the
    // entity's friendly label (the web's HATile card head: the tile's
    // label, else the entity's own label, else the id); view tiles wear
    // their view/label name.
    fun displayName(id: String): String {
        val ht = views.haTiles.firstOrNull { it.id == id }
        return if (ht != null) {
            haTileLabel(ht, ha.entities[ht.entity])
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
                            HaContent(maxId, views, ha.entities, ha.off, ha.load, dark)
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
                                    ha.entities,
                                    ha.off,
                                    ha.load,
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
            entities = ha.entities,
            haOff = ha.off,
            onBoard = { board ->
                state.adopt(board)
                editingTile = null
            },
            onClose = { editingTile = null },
        )
    }
}
