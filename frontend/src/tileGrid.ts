import type { TileGeometry, TileSize } from './types'
import { GRID_DEFAULTS, rectsOverlap } from './utils'


/**
 * Pure board-grid logic (no DOM): automatic placement, bounds, and the
 * pointer → cell math that drives drag/resize snapping. The layout rules
 * mirror both the backend's geometry repair (config.normalizeGeometry)
 * and the browser's CSS grid auto flow for unpositioned items. Every
 * function takes the grid's dimensions as a parameter (defaulting to the
 * 12x12 default grid), so the math follows the board's effective grid.
 */

/** The fraction of the grid's width a size step spans (the documented step
 *  meanings: a third, half, two thirds, or the full width). */
const STEP_FRAC: Record<TileSize, number> = { s: 1 / 3, m: 1 / 2, l: 2 / 3, xl: 1 }

/** The column span an auto-placed tile takes at each size step (rounded
 *  to whole units of the `cols`-column grid, clamped into it). */
export function stepWidth(size: TileSize | string | undefined, cols = GRID_DEFAULTS.cols): number {
  const frac =
    size === 's' || size === 'm' || size === 'l' || size === 'xl' ? STEP_FRAC[size] : STEP_FRAC.m
  return Math.max(1, Math.min(Math.round(frac * cols), cols))
}

/** The default span of a newly created tile: a 2x2 cell on the board's
 *  unit grid. New tiles are placed (explicitly) at the first free 2x2
 *  cell rather than flowing in at their size step's width. */
export const NEW_TILE_DEFAULT_SIZE: { w: number; h: number } = { w: 2, h: 2 }

/**
 * The first cell in reading order (row by row, left to right) on the
 * cols x rows grid that fits a w x h tile and does not overlap any cell
 * of the board's effective layout — the stored placements plus the auto
 * flow of the rest, computed over the full board order (hidden tiles
 * included: they stay in the board's layout, so a new tile that claimed
 * one of their cells would be demoted to auto placement when they are
 * re-shown). Null when the grid is too small for the span or no cell is
 * free (the caller then leaves the tile to auto placement).
 */
export function firstFreeCell(
  order: string[],
  sizes: Record<string, TileSize | string | undefined>,
  geometry: Record<string, TileGeometry>,
  w: number,
  h: number,
  cols = GRID_DEFAULTS.cols,
  rows = GRID_DEFAULTS.rows,
): TileGeometry | null {
  if (!Number.isInteger(w) || !Number.isInteger(h) || w < 1 || h < 1) return null
  if (w > cols || h > rows) return null
  const layout = autoLayout(order, sizes, geometry, cols)
  const taken = (c: TileGeometry | undefined, cell: TileGeometry) =>
    c !== undefined && rectsOverlap(c, cell)
  for (let y = 1; y <= rows - h + 1; y++) {
    for (let x = 1; x <= cols - w + 1; x++) {
      const cell: TileGeometry = { x, y, w, h }
      if (!order.some((id) => taken(layout[id], cell))) return cell
    }
  }
  return null
}

/**
 * CSS-style automatic placement: tiles flow in board order with the
 * grid's default sparse auto flow — the placement cursor only moves
 * forward (each tile scans from the cursor; when that row runs out, the
 * wrap to the next row restarts at column 1, and gaps left behind the
 * cursor are never backfilled). `fixed` placements (the board file's
 * geometry) act as obstacles without advancing the cursor, exactly like
 * the board's explicitly positioned tiles. Deterministic: the same
 * inputs always produce the same cells (the jsdom tests rely on that —
 * the browser's own auto flow produces the same result).
 */
export function autoLayout(
  order: string[],
  sizes: Record<string, TileSize | string | undefined>,
  fixed: Record<string, TileGeometry> = {},
  cols = GRID_DEFAULTS.cols,
): Record<string, TileGeometry> {
  const placed: TileGeometry[] = []
  for (const id of order) {
    const f = fixed[id]
    if (f) placed.push(f)
  }
  const out: Record<string, TileGeometry> = {}
  const free = (y: number, x: number, w: number) =>
    !placed.some((p) => p.x < x + w && x < p.x + p.w && p.y < y + 1 && y < p.y + p.h)
  let cursorY = 1
  let cursorX = 1
  for (const id of order) {
    const f = fixed[id]
    if (f) {
      out[id] = f
      continue
    }
    const w = Math.min(stepWidth(sizes[id], cols), cols)
    let y = cursorY
    let cell: TileGeometry | null = null
    while (!cell) {
      // A width-w tile starting at x occupies columns x..x+w-1, so x may
      // reach cols-w+1 (an xl tile fills the row from column 1). Rows
      // beyond the cursor restart the scan at column 1. (Rows are open
      // for the auto flow: if the board overflows its grid rows the grid
      // stretches; user-placed cells stay bounded by clampCell/dropRefused.)
      for (let x = y === cursorY ? cursorX : 1; x <= cols - w + 1; x++) {
        if (free(y, x, w)) {
          cell = { x, y, w, h: 1 }
          break
        }
      }
      if (!cell) y++
    }
    placed.push(cell)
    out[id] = cell
    cursorY = cell.y
    cursorX = cell.x + cell.w
  }
  return out
}

/**
 * A cell the user tried to drag/resize to, clamped back into the grid:
 * the width can never exceed the board's columns, the height its rows,
 * and the cell can never cross the right or bottom edge. (Out-of-board
 * drops are refused at the drop, never partially applied — this clamp
 * keeps the LIVE PREVIEW sensible while the pointer is still outside the
 * board.)
 */
export function clampCell(g: TileGeometry, cols = GRID_DEFAULTS.cols, rows = GRID_DEFAULTS.rows): TileGeometry {
  const w = Math.max(1, Math.min(Math.round(g.w), cols))
  const h = Math.max(1, Math.min(Math.round(g.h), rows))
  const x = Math.max(1, Math.min(Math.round(g.x), cols - w + 1))
  const y = Math.max(1, Math.min(Math.round(g.y), rows - h + 1))
  return { x, y, w, h }
}

/** The board's grid metrics in client pixels (measured from the board box). */
export interface GridMetrics {
  /** Client x of column 1's left edge. */
  left: number
  /** Client y of row 1's top edge. */
  top: number
  /** Column width in px (excluding the grid gap). */
  colW: number
  /** Row height in px (excluding the grid gap). */
  rowH: number
  /** The grid gap in px. */
  gap: number
}

/**
 * Measure the board grid in client pixels from the board element's box:
 * the content width splits into `cols` columns and the content height
 * into `rows` rows, each with `gap` between the units. `rows` is the
 * number of rows the board actually renders — a layout that overflows
 * the grid's rows stretches the board with 1fr auto rows, so callers
 * pass that rendered count, not the configured grid's row count.
 * Degenerate (zero-size) boxes yield a null metrics object — callers
 * must treat that as "cannot measure" (the jsdom tests stub
 * getBoundingClientRect to exercise the real math).
 */
export function measureGrid(board: HTMLElement, rows: number, cols = GRID_DEFAULTS.cols): GridMetrics | null {
  const r = board.getBoundingClientRect()
  if (r.width <= 0 || r.height <= 0 || rows < 1) return null
  let gap = 0
  const parsed = parseFloat(getComputedStyle(board).columnGap)
  if (Number.isFinite(parsed)) gap = parsed
  const colW = (r.width - (cols - 1) * gap) / cols
  const rowH = (r.height - (rows - 1) * gap) / rows
  if (colW <= 0 || rowH <= 0) return null
  return { left: r.left, top: r.top, colW, rowH, gap }
}

/**
 * The new cell while dragging, from the grab offset: `origin` moved by
 * whole cells (rounded from the pointer delta, so the tile snaps cell to
 * cell), clamped into the grid. For resize, only w/h change the same way.
 */
export function dragCell(
  mode: 'move' | 'resize',
  origin: TileGeometry,
  dxPx: number,
  dyPx: number,
  m: GridMetrics,
  cols = GRID_DEFAULTS.cols,
  rows = GRID_DEFAULTS.rows,
): TileGeometry {
  const dx = Math.round(dxPx / (m.colW + m.gap))
  const dy = Math.round(dyPx / (m.rowH + m.gap))
  if (mode === 'move') {
    return clampCell({ x: origin.x + dx, y: origin.y + dy, w: origin.w, h: origin.h }, cols, rows)
  }
  return clampCell({ x: origin.x, y: origin.y, w: origin.w + dx, h: origin.h + dy }, cols, rows)
}

/**
 * Whether a drop at `cell` is refused: the cell is outside the cols x
 * rows grid, or it would overlap any other placed tile (their cells come
 * from the board's effective layout — geometry entries plus the auto flow
 * for the rest). Cells from clampCell/dragCell are already in bounds; the
 * bounds test keeps the contract (out-of-board drops are refused, never
 * partially applied) for callers that build cells directly.
 */
export function dropRefused(
  id: string,
  cell: TileGeometry,
  order: string[],
  sizes: Record<string, TileSize | string | undefined>,
  geometry: Record<string, TileGeometry>,
  cols = GRID_DEFAULTS.cols,
  rows = GRID_DEFAULTS.rows,
): boolean {
  if (cell.x + cell.w - 1 > cols || cell.y + cell.h - 1 > rows) return true
  const layout = autoLayout(order, sizes, geometry, cols)
  for (const other of order) {
    if (other === id) continue
    const c = layout[other]
    if (c && rectsOverlap(c, cell)) return true
  }
  return false
}
