import {
  DEFAULT_TILE_SIZE,
  TILE_SIZES,
  VIEW_IDS,
  type BoardViews,
  type CalendarMode,
  type TileSize,
  type ViewId,
} from './types'

export function dayKey(d: Date): string {
  const m = `${d.getMonth() + 1}`.padStart(2, '0')
  const day = `${d.getDate()}`.padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

/** Display names for the four dashboard views (the board's tiles). */
export const VIEW_LABELS: Record<ViewId, string> = {
  calendar: 'Calendar',
  chat: 'Hermes chat',
  home: 'Home',
  push: 'Hermes push',
}

/** Display names for the tile size steps. */
export const TILE_SIZE_LABELS: Record<TileSize, string> = {
  s: 'Small',
  m: 'Medium',
  l: 'Large',
  xl: 'Full width',
}

/**
 * Fills in defaults and repairs stored view configuration (the board file is
 * a freehand-editable config surface; the backend normalizes the same way):
 * order is always a permutation of the four view IDs (known IDs in stored
 * order first, missing IDs appended in default order), hidden holds only
 * known distinct IDs, calendar_mode is 'month' or 'schedule', and sizes is
 * always a complete map of the four view IDs to valid size steps (unknown
 * keys dropped, absent or invalid values fall back to the default step).
 * Absent input means exactly today's board: all four views, original order,
 * every tile at the default size.
 */
export function normalizeViews(
  raw: {
    order?: string[]
    hidden?: string[]
    calendar_mode?: string
    sizes?: Record<string, string>
  } | null | undefined,
): BoardViews {
  const isViewID = (id: string): id is ViewId => (VIEW_IDS as readonly string[]).includes(id)

  const order: ViewId[] = []
  const seen = new Set<string>()
  for (const id of raw?.order ?? []) {
    if (isViewID(id) && !seen.has(id)) {
      order.push(id)
      seen.add(id)
    }
  }
  for (const id of VIEW_IDS) {
    if (!seen.has(id)) order.push(id)
  }

  const hidden: ViewId[] = []
  const seenH = new Set<string>()
  for (const id of raw?.hidden ?? []) {
    if (isViewID(id) && !seenH.has(id)) {
      hidden.push(id)
      seenH.add(id)
    }
  }

  const calendar_mode: CalendarMode = raw?.calendar_mode === 'schedule' ? 'schedule' : 'month'

  const sizes = {} as Record<ViewId, TileSize>
  for (const id of VIEW_IDS) {
    const stored = raw?.sizes?.[id]
    sizes[id] = stored !== undefined && (TILE_SIZES as readonly string[]).includes(stored) ? (stored as TileSize) : DEFAULT_TILE_SIZE
  }
  return { order, hidden, calendar_mode, sizes }
}
