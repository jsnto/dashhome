import type {
  BoardViews,
  CalendarMode,
  HABoardTile,
  NormalizedViews,
  TileGeometry,
  TileSize,
  ViewId,
} from './types'
import { VIEW_IDS } from './types'

/** The human labels for the four built-in views. */
export const VIEW_LABELS: Record<ViewId, string> = {
  calendar: 'Calendar',
  chat: 'Hermes chat',
  home: 'Home',
  push: 'Hermes push',
}

/** The human labels for the tile size steps. */
export const TILE_SIZE_LABELS: Record<TileSize, string> = {
  s: 'Small tile',
  m: 'Medium tile',
  l: 'Large tile',
  xl: 'Full width tile',
}

/**
 * The board's 6-column grid: the number of columns and the column span a
 * size step places an (auto-placed) tile at. Mirrors the backend's
 * config.normalizeViews + the CSS grid spans.
 */
export const GRID_COLS = 6
export const STEP_WIDTH: Record<TileSize, number> = { s: 2, m: 3, l: 4, xl: 6 }

/**
 * A valid grid placement on the 6-column grid: 1-based integers, the span
 * fits the columns (1 <= w <= 6, 1 <= x <= 7-w), the row and height are
 * >= 1 (rows are unbounded — the board grows downward).
 */
export function isValidGeometry(g: unknown): g is TileGeometry {
  if (typeof g !== 'object' || g === null) return false
  const { x, y, w, h } = g as Record<string, unknown>
  return (
    typeof x === 'number' && Number.isInteger(x) &&
    typeof y === 'number' && Number.isInteger(y) &&
    typeof w === 'number' && Number.isInteger(w) &&
    typeof h === 'number' && Number.isInteger(h) &&
    w >= 1 && w <= GRID_COLS && x >= 1 && x <= GRID_COLS - w + 1 && y >= 1 && h >= 1
  )
}

/** Two grid cells overlap (the same test the backend uses to repair overlaps). */
export function rectsOverlap(a: TileGeometry, b: TileGeometry): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
}

/**
 * Repair the raw geometry map to valid, in-bounds, overlap-free entries.
 * Entries are considered in board order (the first tile keeps its
 * placement; a later tile that overlaps an earlier one is dropped back to
 * auto placement). Unknown tile IDs, unknown units (anything that is not
 * a valid {x, y, w, h} object) and out-of-bounds values are dropped.
 */
export function normalizeGeometry(
  raw: unknown,
  order: string[],
  validIds: Set<string>,
): Record<string, TileGeometry> {
  const source: Record<string, unknown> =
    typeof raw === 'object' && raw !== null ? (raw as Record<string, unknown>) : {}
  const out: Record<string, TileGeometry> = {}
  const kept: TileGeometry[] = []
  for (const id of order) {
    if (!validIds.has(id)) continue
    const g = source[id]
    if (!isValidGeometry(g)) continue
    const cell = g as TileGeometry
    if (kept.some((k) => rectsOverlap(k, cell))) continue
    kept.push(cell)
    out[id] = cell
  }
  return out
}

/**
 * A valid Home Assistant board tile: id "ha-" + 1–16 lowercase alphanumerics,
 * a non-empty entity id, and one of the two kinds (button/visual).
 */
const HA_TILE_ID = /^ha-[a-z0-9]{1,16}$/

function isHABoardTile(t: unknown): t is HABoardTile {
  if (typeof t !== 'object' || t === null) return false
  const o = t as Record<string, unknown>
  if (typeof o.id !== 'string' || !HA_TILE_ID.test(o.id)) return false
  if (typeof o.entity !== 'string' || o.entity.trim() === '') return false
  if (o.kind !== 'button' && o.kind !== 'visual') return false
  if (o.label !== undefined && typeof o.label !== 'string') return false
  return true
}

/** Repair a raw ha_tiles list: valid entries only, duplicate ids deduped (first wins). */
export function normalizeHATiles(raw: unknown): HABoardTile[] {
  if (!Array.isArray(raw)) return []
  const seen = new Set<string>()
  const out: HABoardTile[] = []
  for (const t of raw) {
    if (!isHABoardTile(t) || seen.has(t.id)) continue
    seen.add(t.id)
    out.push({
      id: t.id,
      entity: t.entity,
      ...(typeof t.label === 'string' && t.label !== '' ? { label: t.label } : {}),
      kind: t.kind,
    })
  }
  return out
}

export function isHATileId(id: string): boolean {
  return HA_TILE_ID.test(id)
}

/** The display label for an HA tile: the tile's label, else the entity's
 *  own label, else the entity id. */
export function haTileLabel(tile: HABoardTile, entity: { label?: string } | null): string {
  if (tile.label && tile.label.trim() !== '') return tile.label.trim()
  if (entity?.label) return entity.label
  return tile.entity
}

/** The human state for an HA tile: ON/OFF when that is the vocabulary, else the raw state. */
export function haTileState(state: string): string {
  if (state === 'on' || state === 'off') return state.toUpperCase()
  return state
}

/**
 * Repair a raw `views` section (a board file or the API answer) into a
 * valid layout: order is a permutation of the board tile IDs (the four
 * view IDs plus every valid ha_tiles id), hidden is a subset of it, sizes
 * are valid steps for the tiles in order (missing = 'm'), calendar_mode
 * is one of the two modes, geometry is repaired to in-bounds and
 * overlap-free placements, and ha_tiles keeps only valid entries.
 * Anything else is repaired, not crashed on.
 */
export function normalizeViews(views: BoardViews | null): NormalizedViews {
  const haTiles = normalizeHATiles(views?.ha_tiles)
  const validIds = new Set<string>([...VIEW_IDS, ...haTiles.map((t) => t.id)])

  const rawOrder =
    typeof views?.order === 'object' && views !== null && Array.isArray(views.order)
      ? views.order
      : [...VIEW_IDS]
  const seen = new Set<string>()
  const order: string[] = []
  for (const v of rawOrder) {
    if (typeof v === 'string' && validIds.has(v) && !seen.has(v)) {
      seen.add(v)
      order.push(v)
    }
  }
  for (const id of [...VIEW_IDS, ...haTiles.map((t) => t.id)]) {
    if (!seen.has(id)) {
      seen.add(id)
      order.push(id)
    }
  }

  const rawHidden = Array.isArray(views?.hidden) ? views.hidden : []
  const hidden: string[] = []
  const hiddenSeen = new Set<string>()
  for (const h of rawHidden) {
    if (typeof h === 'string' && validIds.has(h) && !hiddenSeen.has(h)) {
      hiddenSeen.add(h)
      hidden.push(h)
    }
  }

  const calendar_mode: CalendarMode =
    views?.calendar_mode === 'schedule' ? 'schedule' : 'month'

  const rawSizes =
    typeof views?.sizes === 'object' && views !== null && views.sizes !== null
      ? (views.sizes as Record<string, unknown>)
      : {}
  const sizes: Record<string, TileSize> = {}
  for (const id of order) {
    const s = rawSizes[id]
    sizes[id] = s === 's' || s === 'l' || s === 'xl' ? s : 'm'
  }

  const geometry = normalizeGeometry(views?.geometry, order, validIds)

  return { order, hidden, calendar_mode, sizes, geometry, ha_tiles: haTiles }
}

/** The effective visible order: board order minus the hidden tiles.
 * (The board file is freehand-editable, so the input is normalized first:
 * this helper is the single source of truth for what renders.)
 */
export function visibleOrder(views: BoardViews | null): string[] {
  const v = normalizeViews(views)
  return v.order.filter((id) => !v.hidden.includes(id))
}

/** The day key (local Y-M-D) for a date. */
export function dayKey(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
