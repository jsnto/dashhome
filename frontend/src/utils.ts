import { VIEW_IDS, type BoardViews, type CalendarMode, type ViewId } from './types'

export function dayKey(d: Date): string {
  const m = `${d.getMonth() + 1}`.padStart(2, '0')
  const day = `${d.getDate()}`.padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

/** Display names for the four dashboard views. */
export const VIEW_LABELS: Record<ViewId, string> = {
  calendar: 'Calendar',
  chat: 'Hermes chat',
  home: 'Home',
  push: 'Hermes push',
}

/** The 2x2 board slots in reading order: main-top, main-bottom, side-top, side-bottom. */
export const BOARD_SLOT_NAMES = ['main top', 'main bottom', 'side top', 'side bottom'] as const

/**
 * Fills in defaults and repairs stored view configuration (the board file is
 * a freehand-editable config surface; the backend normalizes the same way):
 * order is always a permutation of the four view IDs (known IDs in stored
 * order first, missing IDs appended in default order), hidden holds only
 * known distinct IDs, and calendar_mode is 'month' or 'schedule'. Absent
 * input means exactly today's board: all four views, original order.
 */
export function normalizeViews(
  raw: { order?: string[]; hidden?: string[]; calendar_mode?: string } | null | undefined,
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
  return { order, hidden, calendar_mode }
}
