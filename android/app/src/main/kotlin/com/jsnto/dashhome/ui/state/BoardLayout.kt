package com.jsnto.dashhome.ui.state

import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.GridDims
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.HAEntity
import com.jsnto.dashhome.api.TileGeometry

/**
 * The pure board-grid logic (no Android dependencies) - the Kotlin port of
 * the web dashboard's `frontend/src/tileGrid.ts` plus the board-layout
 * helpers in `frontend/src/utils.ts`: the board's view/layout
 * normalization, automatic placement, bounds, and the pointer-to-cell
 * math that drives drag/resize snapping. Every layout function takes the
 * grid's dimensions as parameters (defaulting to the 12x12 default
 * grid), so the math follows the board's effective grid.
 */

/** The board's tile grid: 12x12 units by default (the web's GRID_DEFAULTS). */
val GRID_DEFAULTS = GridDims(cols = 12, rows = 12)

/** The usable range of a grid dimension (settings validates to this). */
const val GRID_MIN = 1
const val GRID_MAX = 24

/** The four built-in dashboard views, in the default board order. */
val VIEW_IDS = listOf("calendar", "chat", "home", "push")

/** The human labels for the four built-in views (the web's VIEW_LABELS). */
val VIEW_LABELS = mapOf(
    "calendar" to "Calendar",
    "chat" to "Hermes chat",
    "home" to "Home",
    "push" to "Hermes push",
)

/** The human labels for the tile size steps (the web's TILE_SIZE_LABELS). */
val TILE_SIZE_LABELS = mapOf(
    "s" to "Small tile",
    "m" to "Medium tile",
    "l" to "Large tile",
    "xl" to "Full width tile",
)

private val STEP_FRAC = mapOf("s" to 1.0 / 3, "m" to 1.0 / 2, "l" to 2.0 / 3, "xl" to 1.0)

/**
 * The column span an auto-placed tile takes at each size step (rounded to
 * whole units of the [cols]-column grid, clamped into it). An unknown step
 * takes the default (medium) width.
 */
fun stepWidth(size: String?, cols: Int = GRID_DEFAULTS.cols): Int {
    val frac = STEP_FRAC[size] ?: STEP_FRAC["m"]!!
    return Math.round(frac * cols).toInt().coerceIn(1, cols)
}

/** A plain span (no position), e.g. the default new-tile size. */
data class Span(val w: Int, val h: Int)

/**
 * The default span of a newly created tile: a 2x2 cell on the board's
 * unit grid. New tiles are placed (explicitly) at the first free 2x2 cell
 * rather than flowing in at their size step's width.
 */
val NEW_TILE_DEFAULT_SIZE = Span(2, 2)

/**
 * The first cell in reading order (row by row, left to right) on the
 * [cols] x [rows] grid that fits a [w] x [h] tile and does not overlap any
 * cell of the board's effective layout - the stored placements plus the
 * auto flow of the rest, computed over the full board order (hidden tiles
 * included: they stay in the board's layout, so a new tile that claimed
 * one of their cells would be demoted to auto placement when they are
 * re-shown). Null when the grid is too small for the span or no cell is
 * free (the caller then leaves the tile to auto placement).
 */
fun firstFreeCell(
    order: List<String>,
    sizes: Map<String, String?>,
    geometry: Map<String, TileGeometry>,
    w: Int,
    h: Int,
    cols: Int = GRID_DEFAULTS.cols,
    rows: Int = GRID_DEFAULTS.rows,
): TileGeometry? {
    if (w < 1 || h < 1) return null
    if (w > cols || h > rows) return null
    val layout = autoLayout(order, sizes, geometry, cols)
    for (y in 1..(rows - h + 1)) {
        for (x in 1..(cols - w + 1)) {
            val cell = TileGeometry(x, y, w, h)
            if (order.none { id -> layout[id]?.let { rectsOverlap(it, cell) } == true }) return cell
        }
    }
    return null
}

/**
 * CSS-style automatic placement: tiles flow in board order with the
 * grid's default sparse auto flow - the placement cursor only moves
 * forward (each tile scans from the cursor; when that row runs out, the
 * wrap to the next row restarts at column 1, and gaps left behind the
 * cursor are never backfilled). [fixed] placements (the board file's
 * geometry) act as obstacles without advancing the cursor, exactly like
 * the board's explicitly positioned tiles. Deterministic: the same
 * inputs always produce the same cells (the browser's own auto flow
 * produces the same result).
 */
fun autoLayout(
    order: List<String>,
    sizes: Map<String, String?>,
    fixed: Map<String, TileGeometry> = emptyMap(),
    cols: Int = GRID_DEFAULTS.cols,
): Map<String, TileGeometry> {
    // The web's single pass, ported 1:1 (frontend/src/tileGrid.ts):
    // stored placements are placed as-is (the server's normalization
    // already resolved overlaps), and the rest flow in board order
    // behind a cursor that never moves backwards. Rows are open for
    // the auto flow: if the board overflows its grid rows the grid
    // stretches (renderedRows models that); user-placed cells stay
    // bounded by clampCell/dropRefused.
    val placed = mutableListOf<TileGeometry>()
    for (id in order) fixed[id]?.let { placed.add(it) }
    val layout = LinkedHashMap<String, TileGeometry>()
    fun free(y: Int, x: Int, w: Int): Boolean =
        placed.none { it.x < x + w && x < it.x + it.w && it.y < y + 1 && y < it.y + it.h }
    var cursorY = 1
    var cursorX = 1
    for (id in order) {
        // A stored placement is placed as-is; the rest of the loop body
        // is skipped for it (the web's `continue`).
        val stored = fixed[id]
        if (stored != null) {
            layout[id] = stored
            continue
        }
        val w = minOf(stepWidth(sizes[id], cols), cols)
        var y = cursorY
        var cell: TileGeometry? = null
        while (cell == null) {
            // A width-w tile starting at x occupies columns x..x+w-1, so
            // x may reach cols-w+1 (an xl tile fills the row from column
            // 1). Rows beyond the cursor restart the scan at column 1.
            val xStart = if (y == cursorY) cursorX else 1
            for (x in xStart..(cols - w + 1)) {
                if (free(y, x, w)) {
                    cell = TileGeometry(x, y, w, 1)
                    break
                }
            }
            if (cell == null) y++
        }
        placed.add(cell!!)
        layout[id] = cell!!
        cursorY = cell!!.y
        cursorX = cell!!.x + cell!!.w
    }
    return layout
}

/**
 * A cell the user tried to drag/resize to, clamped back into the grid:
 * the width can never exceed the board's columns, the height its rows,
 * and the cell can never cross the right or bottom edge. (Out-of-board
 * drops are refused at the drop, never partially applied - this clamp
 * keeps the LIVE PREVIEW sensible while the pointer is still outside the
 * board.)
 */
fun clampCell(g: TileGeometry, cols: Int = GRID_DEFAULTS.cols, rows: Int = GRID_DEFAULTS.rows): TileGeometry {
    val w = g.w.coerceIn(1, cols)
    val h = g.h.coerceIn(1, rows)
    val x = g.x.coerceIn(1, cols - w + 1)
    val y = g.y.coerceIn(1, rows - h + 1)
    return TileGeometry(x, y, w, h)
}

/** The board's grid metrics in pixels (measured from the board box). */
data class GridMetrics(
    val colW: Float,
    val rowH: Float,
    val gap: Float,
)

/**
 * The board's grid metrics in pixels: the content width splits into
 * [cols] columns and the content height into [rows] rows, each with
 * [gapPx] between the units. [rows] is the number of rows the board
 * actually renders - a layout that overflows the grid's rows stretches
 * the board with auto rows, so callers pass that rendered count, not the
 * configured grid's row count. Degenerate (zero-size) boxes yield null -
 * callers treat that as "cannot measure".
 */
fun gridMetrics(
    boardWidthPx: Float,
    boardHeightPx: Float,
    rows: Int,
    cols: Int = GRID_DEFAULTS.cols,
    gapPx: Float = 0f,
): GridMetrics? {
    if (boardWidthPx <= 0f || boardHeightPx <= 0f || rows < 1) return null
    val colW = (boardWidthPx - (cols - 1) * gapPx) / cols
    val rowH = (boardHeightPx - (rows - 1) * gapPx) / rows
    if (colW <= 0f || rowH <= 0f) return null
    return GridMetrics(colW, rowH, gapPx)
}

/** A drag/resize session's kind (the web's 'move' | 'resize'). */
enum class DragMode { MOVE, RESIZE }

/**
 * The new cell while dragging, from the grab offset: [origin] moved by
 * whole cells (rounded from the pointer delta, so the tile snaps cell to
 * cell), clamped into the grid. For resize, only w/h change the same way.
 */
fun dragCell(
    mode: DragMode,
    origin: TileGeometry,
    dxPx: Float,
    dyPx: Float,
    m: GridMetrics,
    cols: Int = GRID_DEFAULTS.cols,
    rows: Int = GRID_DEFAULTS.rows,
): TileGeometry {
    val dx = Math.round(dxPx / (m.colW + m.gap))
    val dy = Math.round(dyPx / (m.rowH + m.gap))
    return if (mode == DragMode.MOVE) {
        clampCell(TileGeometry(origin.x + dx, origin.y + dy, origin.w, origin.h), cols, rows)
    } else {
        clampCell(TileGeometry(origin.x, origin.y, origin.w + dx, origin.h + dy), cols, rows)
    }
}

/**
 * Whether a drop at [cell] is refused: the cell is outside the [cols] x
 * [rows] grid, or it would overlap any other placed tile (their cells come
 * from the board's effective layout - geometry entries plus the auto flow
 * for the rest). Cells from clampCell/dragCell are already in bounds; the
 * bounds test keeps the contract (out-of-board drops are refused, never
 * partially applied) for callers that build cells directly.
 */
fun dropRefused(
    id: String,
    cell: TileGeometry,
    order: List<String>,
    sizes: Map<String, String?>,
    geometry: Map<String, TileGeometry>,
    cols: Int = GRID_DEFAULTS.cols,
    rows: Int = GRID_DEFAULTS.rows,
): Boolean {
    if (cell.x + cell.w - 1 > cols || cell.y + cell.h - 1 > rows) return true
    val layout = autoLayout(order, sizes, geometry, cols)
    for (other in order) {
        if (other == id) continue
        val c = layout[other] ?: continue
        if (rectsOverlap(c, cell)) return true
    }
    return false
}

/**
 * The rows the board actually renders: the auto flow is open on rows, so
 * a layout that overflows the grid's rows stretches the board with auto
 * rows, and the drag/resize row pitch follows that rendered count.
 */
fun renderedRows(layout: Map<String, TileGeometry>, gridRows: Int): Int {
    var last = 1
    for (c in layout.values) last = maxOf(last, c.y + c.h - 1)
    return maxOf(gridRows, last)
}

// --- board-file normalization (the web's utils.ts) -------------------------

/**
 * Repair a stored grid (board.grid, a freehand-editable surface) to valid
 * dimensions: an absent grid, or an out-of-range dimension, falls back to
 * the per-axis default (12x12 when nothing is stored).
 */
fun normalizeGrid(grid: GridDims?): GridDims = grid?.let {
    GridDims(
        cols = if (it.cols in GRID_MIN..GRID_MAX) it.cols else GRID_DEFAULTS.cols,
        rows = if (it.rows in GRID_MIN..GRID_MAX) it.rows else GRID_DEFAULTS.rows,
    )
} ?: GRID_DEFAULTS

/**
 * A valid grid placement on the [cols] x [rows] grid: 1-based integers,
 * the span fits the grid (1 <= w <= cols, 1 <= x <= cols-w+1,
 * 1 <= h <= rows, 1 <= y <= rows-h+1).
 */
fun isValidGeometry(g: TileGeometry, cols: Int = GRID_DEFAULTS.cols, rows: Int = GRID_DEFAULTS.rows): Boolean =
    g.w in 1..cols && g.x in 1..(cols - g.w + 1) &&
        g.h in 1..rows && g.y in 1..(rows - g.h + 1)

/** Two grid cells overlap (the same test the backend uses to repair overlaps). */
fun rectsOverlap(a: TileGeometry, b: TileGeometry): Boolean =
    a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

/**
 * Repair the raw geometry map to valid, in-bounds, overlap-free entries on
 * the [cols] x [rows] grid. Entries are considered in board order (the
 * first tile keeps its placement; a later tile that overlaps an earlier
 * one is dropped back to auto placement). Unknown tile IDs and
 * out-of-bounds values are dropped.
 */
fun normalizeGeometry(
    raw: Map<String, TileGeometry>,
    order: List<String>,
    validIds: Set<String>,
    cols: Int = GRID_DEFAULTS.cols,
    rows: Int = GRID_DEFAULTS.rows,
): Map<String, TileGeometry> {
    val out = mutableMapOf<String, TileGeometry>()
    val kept = mutableListOf<TileGeometry>()
    for (id in order) {
        if (id !in validIds) continue
        val g = raw[id] ?: continue
        if (!isValidGeometry(g, cols, rows)) continue
        if (kept.any { rectsOverlap(it, g) }) continue
        kept.add(g)
        out[id] = g
    }
    return out
}

/** The valid Home Assistant board-tile id shape: "ha-" + 1-16 alphanumerics. */
val HA_TILE_ID = Regex("^ha-[a-z0-9]{1,16}$")

fun isHATileId(id: String): Boolean = HA_TILE_ID.matches(id)

/**
 * Repair a raw ha_tiles list: valid entries only (id shape, non-empty
 * entity, one of the two kinds), duplicate ids deduped (first wins). A
 * blank label is dropped (the entity's own label stands in).
 */
fun normalizeHATiles(raw: List<HABoardTile>): List<HABoardTile> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<HABoardTile>()
    for (t in raw) {
        if (!HA_TILE_ID.matches(t.id)) continue
        if (t.entity.trim().isEmpty()) continue
        if (t.kind != "button" && t.kind != "visual") continue
        if (t.id in seen) continue
        seen.add(t.id)
        out.add(t.copy(label = t.label?.takeIf { it.isNotEmpty() }))
    }
    return out
}

/** The display label for an HA tile: the tile's label, else the entity's
 *  own label, else the entity id. */
fun haTileLabel(tile: HABoardTile, entity: HAEntity?): String {
    tile.label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    entity?.label?.takeIf { it.isNotEmpty() }?.let { return it }
    return tile.entity
}

/** The human state for an HA tile: ON/OFF when that is the vocabulary,
 *  else the raw state. */
fun haTileState(state: String): String =
    if (state == "on" || state == "off") state.uppercase() else state

/**
 * Repair a raw `views` section (a board file or the API answer) into a
 * valid layout on the [cols] x [rows] grid: order is a permutation of the
 * board tile IDs (the four view IDs plus every valid ha_tiles id), hidden
 * is a subset of it, sizes are valid steps for the tiles in order (missing
 * = 'm'), calendar_mode is one of the two modes, and geometry is repaired
 * to in-bounds and overlap-free placements. Anything else is repaired,
 * not crashed on.
 */
fun normalizeViews(views: BoardViews?, grid: GridDims = GRID_DEFAULTS): BoardViews {
    val haTiles = normalizeHATiles(views?.haTiles ?: emptyList())
    val validIds = (VIEW_IDS + haTiles.map { it.id }).toSet()

    val seen = mutableSetOf<String>()
    val order = mutableListOf<String>()
    for (v in views?.order ?: VIEW_IDS) {
        if (v in validIds && v !in seen) {
            seen.add(v)
            order.add(v)
        }
    }
    for (id in VIEW_IDS + haTiles.map { it.id }) {
        if (id !in seen) {
            seen.add(id)
            order.add(id)
        }
    }

    val hidden = mutableListOf<String>()
    val hiddenSeen = mutableSetOf<String>()
    for (h in views?.hidden ?: emptyList()) {
        if (h in validIds && h !in hiddenSeen) {
            hiddenSeen.add(h)
            hidden.add(h)
        }
    }

    val calendarMode = if (views?.calendarMode == "schedule") "schedule" else "month"

    val rawSizes = views?.sizes ?: emptyMap()
    val sizes = order.associateWith { id ->
        val s = rawSizes[id]
        if (s == "s" || s == "l" || s == "xl") s else "m"
    }

    val geometry = normalizeGeometry(views?.geometry ?: emptyMap(), order, validIds, grid.cols, grid.rows)

    return BoardViews(order, hidden, calendarMode, sizes, geometry, haTiles)
}

/** The effective visible order: board order minus the hidden tiles
 *  (the board file is freehand-editable, so the input is normalized
 *  first: this helper is the single source of truth for what renders). */
fun visibleOrder(views: BoardViews?, grid: GridDims = GRID_DEFAULTS): List<String> {
    val v = normalizeViews(views, grid)
    return v.order.filter { it !in v.hidden }
}

// --- display names (the web's tileName) -------------------------------------

/** The next free Home Assistant tile id (ha-1, ha-2, ...). */
fun nextHATileId(tiles: List<HABoardTile>): String {
    val used = tiles.map { it.id }.toSet()
    var n = 1
    while ("ha-$n" in used) n++
    return "ha-$n"
}

/** The display name of any board tile (a built-in view or an HA tile).
 *  The label is used as stored, no trim - the server's label rule
 *  (config.HAEntityLabel) only rejects the empty label, and the copies
 *  that trimmed blank labels diverged from that source of truth. The
 *  convergence is pinned by frontend/test-fixtures/board-layout/ha-tiles.json. */
fun tileName(id: String, views: BoardViews): String {
    VIEW_LABELS[id]?.let { return it }
    val t = views.haTiles.firstOrNull { it.id == id }
    if (t != null) {
        return t.label?.takeIf { it.isNotEmpty() } ?: t.entity
    }
    return id
}
