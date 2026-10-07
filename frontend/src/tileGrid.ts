import type { TileGeometry, TileSize } from './types'
import { GRID_COLS, STEP_WIDTH, rectsOverlap } from './utils'

/**
 * Pure board-grid logic (no DOM): automatic placement, bounds, and the
 * pointer → cell math that drives drag/resize snapping. The layout rules
 * mirror both the backend's geometry repair (config.normalizeGeometry)
 * and the browser's CSS grid auto flow for unpositioned items.
 */

/** The column span an auto-placed tile takes at each size step. */
export function stepWidth(size: TileSize | string | undefined): number {
  if (size === 's' || size === 'm' || size === 'l' || size === 'xl') return STEP_WIDTH[size]
  return STEP_WIDTH.m
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
  cols = GRID_COLS,
): Record<string, TileGeometry> {
  const placed: TileGeometry[] = []
  const out: Record<string, TileGeometry> = {}
  const free = (y: number, x: number, w: number) =>
    !placed.some((p) => p.x < x + w && x < p.x + p.w && p.y < y + 1 && y < p.y + p.h)
  let cursorY = 1
  let cursorX = 1
  for (const id of order) {
    const f = fixed[id]
    if (f) {
      placed.push(f)
      out[id] = f
      continue
    }
    const w = Math.min(stepWidth(sizes[id]), cols)
    let y = cursorY
    let cell: TileGeometry | null = null
    while (!cell) {
      // A width-w tile starting at x occupies columns x..x+w-1, so x may
      // reach cols-w+1 (an xl tile fills the row from column 1). Rows
      // beyond the cursor restart the scan at column 1.
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

/** The total grid rows a computed layout needs (1 when nothing is placed). */
export function boardRows(layout: Record<string, TileGeometry>): number {
  let rows = 1
  for (const cell of Object.values(layout)) {
    rows = Math.max(rows, cell.y + cell.h - 1)
  }
  return rows
}

/**
 * A cell the user tried to drag/resize to, clamped back into the grid:
 * the width can never exceed the board, the column can never cross the
 * right edge, and rows start at 1. (Out-of-board drops are refused at the
 * drop, never partially applied — this clamp keeps the LIVE PREVIEW
 * sensible while the pointer is still outside the board.)
 */
export function clampCell(g: TileGeometry, cols = GRID_COLS): TileGeometry {
  const w = Math.max(1, Math.min(Math.round(g.w), cols))
  const h = Math.max(1, Math.round(g.h))
  const x = Math.max(1, Math.min(Math.round(g.x), cols - w + 1))
  const y = Math.max(1, Math.round(g.y))
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
 * the content width splits into `cols` columns with `gap` between them.
 * Degenerate (zero-size) boxes yield a null metrics object — callers must
 * treat that as "cannot measure" (the jsdom tests stub getBoundingClientRect
 * to exercise the real math).
 */
export function measureGrid(board: HTMLElement, rows: number, cols = GRID_COLS): GridMetrics | null {
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
  cols = GRID_COLS,
): TileGeometry {
  const dx = Math.round(dxPx / (m.colW + m.gap))
  const dy = Math.round(dyPx / (m.rowH + m.gap))
  if (mode === 'move') {
    return clampCell({ x: origin.x + dx, y: origin.y + dy, w: origin.w, h: origin.h }, cols)
  }
  return clampCell({ x: origin.x, y: origin.y, w: origin.w + dx, h: origin.h + dy }, cols)
}

/**
 * Whether a drop at `cell` is refused: the cell would overlap any other
 * placed tile (their cells come from the board's effective layout —
 * geometry entries plus the auto flow for the rest). Bounds are
 * guaranteed by clampCell/dragCell.
 */
export function dropRefused(
  id: string,
  cell: TileGeometry,
  order: string[],
  sizes: Record<string, TileSize | string | undefined>,
  geometry: Record<string, TileGeometry>,
  cols = GRID_COLS,
): boolean {
  const layout = autoLayout(order, sizes, geometry, cols)
  for (const other of order) {
    if (other === id) continue
    const c = layout[other]
    if (c && rectsOverlap(c, cell)) return true
  }
  return false
}
