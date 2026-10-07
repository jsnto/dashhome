import { describe, expect, it } from 'vitest'
import {
  autoLayout,
  clampCell,
  dragCell,
  dropRefused,
  measureGrid,
  stepWidth,
  type GridMetrics,
} from './tileGrid'

const ORDER = ['calendar', 'chat', 'home', 'push']

describe('stepWidth', () => {
  it('maps the size steps to fractions of the grid width (12 columns by default)', () => {
    expect(stepWidth('s')).toBe(4)
    expect(stepWidth('m')).toBe(6)
    expect(stepWidth('l')).toBe(8)
    expect(stepWidth('xl')).toBe(12)
    expect(stepWidth(undefined)).toBe(6)
    expect(stepWidth('bogus')).toBe(6)
  })

  it('scales the fractions to the grid width', () => {
    expect(stepWidth('s', 6)).toBe(2)
    expect(stepWidth('m', 6)).toBe(3)
    expect(stepWidth('l', 6)).toBe(4)
    expect(stepWidth('xl', 6)).toBe(6)
    expect(stepWidth('s', 3)).toBe(1)
    expect(stepWidth('xl', 3)).toBe(3)
  })
})

describe('autoLayout', () => {
  it('flows the default board as a 2x2 of medium tiles', () => {
    const layout = autoLayout(ORDER, {})
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 6, h: 1 })
    expect(layout.chat).toEqual({ x: 7, y: 1, w: 6, h: 1 })
    expect(layout.home).toEqual({ x: 1, y: 2, w: 6, h: 1 })
    expect(layout.push).toEqual({ x: 7, y: 2, w: 6, h: 1 })
  })

  it('wraps to the next row when a width does not fit (no infinite wrap)', () => {
    // The regression the xl tile exposed: a full-width tile starts a new
    // row; the packer must terminate and keep flowing around it.
    const layout = autoLayout(ORDER, { chat: 'xl' })
    expect(layout.chat).toEqual({ x: 1, y: 2, w: 12, h: 1 })
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 6, h: 1 })
    expect(layout.home).toEqual({ x: 1, y: 3, w: 6, h: 1 })
    expect(layout.push).toEqual({ x: 7, y: 3, w: 6, h: 1 })
  })

  it('does not backfill gaps behind the CSS auto-flow cursor (sparse flow)', () => {
    // The regression the mixed-size board exposed: the board's CSS grid
    // uses the default sparse auto flow (the cursor never moves
    // backwards), so the columns 9-12 that calendar (l) leaves in row 1
    // are not revisited — push (s) lands at (1,3), a third row, not at
    // (9,1).
    const layout = autoLayout(ORDER, { calendar: 'l', push: 's' })
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 8, h: 1 })
    expect(layout.chat).toEqual({ x: 1, y: 2, w: 6, h: 1 })
    expect(layout.home).toEqual({ x: 7, y: 2, w: 6, h: 1 })
    expect(layout.push).toEqual({ x: 1, y: 3, w: 4, h: 1 })
  })

  it('respects board order for the auto cells', () => {
    const layout = autoLayout(['push', 'home', 'chat', 'calendar'], {})
    expect(layout.push).toEqual({ x: 1, y: 1, w: 6, h: 1 })
    expect(layout.home).toEqual({ x: 7, y: 1, w: 6, h: 1 })
  })

  it('keeps stored placements as obstacles and flows the rest around them', () => {
    const layout = autoLayout(ORDER, {}, { calendar: { x: 1, y: 1, w: 8, h: 1 } })
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 8, h: 1 })
    // m=6 cannot fit the 4 remaining columns of row 1, so chat wraps:
    expect(layout.chat).toEqual({ x: 1, y: 2, w: 6, h: 1 })
    expect(layout.home).toEqual({ x: 7, y: 2, w: 6, h: 1 })
    expect(layout.push).toEqual({ x: 1, y: 3, w: 6, h: 1 })
  })

  it('flows auto tiles around fixed entries that sit later in board order', () => {
    // CSS places every explicitly positioned tile before the auto flow
    // starts, so home's stored cell at (4,1) (columns 4-6 of row 1) must
    // not be used by chat even though home comes after chat in board
    // order: calendar (w=6) does not fit row 1 columns 1-6 and takes the
    // open columns 7-12 instead.
    const layout = autoLayout(ORDER, {}, { home: { x: 4, y: 1, w: 3, h: 1 } })
    expect(layout.home).toEqual({ x: 4, y: 1, w: 3, h: 1 })
    expect(layout.calendar).toEqual({ x: 7, y: 1, w: 6, h: 1 })
    expect(layout.chat).toEqual({ x: 1, y: 2, w: 6, h: 1 })
    expect(layout.push).toEqual({ x: 7, y: 2, w: 6, h: 1 })
  })

  it('never overlaps, regardless of order or sizes', () => {
    const layout = autoLayout(ORDER, { calendar: 'xl', chat: 'xl', home: 's', push: 's' })
    const cells = Object.values(layout)
    for (let i = 0; i < cells.length; i++) {
      for (let j = i + 1; j < cells.length; j++) {
        const a = cells[i]
        const b = cells[j]
        const overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
        expect(overlap).toBe(false)
      }
    }
  })

  it('flows the rest of the board around a full-width stored entry', () => {
    const layout = autoLayout(ORDER, {}, { calendar: { x: 1, y: 1, w: 12, h: 1 } })
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 12, h: 1 })
    expect(layout.chat).toEqual({ x: 1, y: 2, w: 6, h: 1 })
  })
})

describe('clampCell', () => {
  it('clamps a cell back into the grid (12x12 by default)', () => {
    expect(clampCell({ x: 0, y: 1, w: 3, h: 1 })).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(clampCell({ x: 13, y: 1, w: 3, h: 1 })).toEqual({ x: 10, y: 1, w: 3, h: 1 })
    expect(clampCell({ x: 1, y: -3, w: 3, h: 1 })).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(clampCell({ x: 1, y: 1, w: 9, h: 1 })).toEqual({ x: 1, y: 1, w: 9, h: 1 })
    expect(clampCell({ x: 1, y: 1, w: 20, h: 1 })).toEqual({ x: 1, y: 1, w: 12, h: 1 })
    expect(clampCell({ x: 1, y: 1, w: 1, h: 0 })).toEqual({ x: 1, y: 1, w: 1, h: 1 })
  })

  it('clamps the bottom edge by the grid rows', () => {
    expect(clampCell({ x: 1, y: 12, w: 3, h: 2 })).toEqual({ x: 1, y: 11, w: 3, h: 2 })
    expect(clampCell({ x: 1, y: 15, w: 3, h: 2 })).toEqual({ x: 1, y: 11, w: 3, h: 2 })
    expect(clampCell({ x: 1, y: 1, w: 3, h: 40 })).toEqual({ x: 1, y: 1, w: 3, h: 12 })
  })

  it('follows the configured grid dimensions', () => {
    expect(clampCell({ x: 1, y: 5, w: 8, h: 2 }, 6, 4)).toEqual({ x: 1, y: 3, w: 6, h: 2 })
    expect(clampCell({ x: 1, y: 1, w: 3, h: 1 }, 3, 3)).toEqual({ x: 1, y: 1, w: 3, h: 1 })
  })
})

// The measured board box the pointer math runs against: 12 columns of
// 100px with a 16px gap (1376px wide), 12 rows of 150px (1976px tall).
const M: GridMetrics = { left: 0, top: 0, colW: 100, rowH: 150, gap: 16 }

describe('dragCell', () => {
  it('snaps a move to whole cells from the grab offset', () => {
    // One column pitch = 100 + 16 = 116; half a pitch rounds to the same cell.
    expect(dragCell('move', { x: 1, y: 1, w: 3, h: 1 }, 0, 0, M)).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(dragCell('move', { x: 1, y: 1, w: 3, h: 1 }, 116, 0, M)).toEqual({ x: 2, y: 1, w: 3, h: 1 })
    expect(dragCell('move', { x: 1, y: 1, w: 3, h: 1 }, 50, 0, M)).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(dragCell('move', { x: 1, y: 1, w: 3, h: 1 }, 0, 166, M)).toEqual({ x: 1, y: 2, w: 3, h: 1 })
  })

  it('clamps a move into the grid (a wide tile cannot cross the right edge)', () => {
    const g = { x: 1, y: 1, w: 4, h: 1 }
    expect(dragCell('move', g, 3 * 116, 0, M)).toEqual({ x: 4, y: 1, w: 4, h: 1 }) // 1+3=4
    expect(dragCell('move', g, 9 * 116, 0, M)).toEqual({ x: 9, y: 1, w: 4, h: 1 }) // clamped (12-4+1)
    expect(dragCell('move', g, 0, -3 * 166, M)).toEqual({ x: 1, y: 1, w: 4, h: 1 }) // row 1 floor
  })

  it('clamps a move at the bottom edge by the grid rows', () => {
    const g = { x: 1, y: 11, w: 4, h: 2 }
    expect(dragCell('move', g, 0, 3 * 166, M, 12, 12)).toEqual({ x: 1, y: 11, w: 4, h: 2 })
    expect(dragCell('move', { x: 1, y: 1, w: 4, h: 2 }, 0, 10 * 166, M, 12, 12)).toEqual({
      x: 1,
      y: 11,
      w: 4,
      h: 2,
    })
  })

  it('grows/shrinks width and height from the corner, clamped to the grid', () => {
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 116, 0, M)).toEqual({ x: 1, y: 1, w: 4, h: 1 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 9 * 116, 0, M)).toEqual({ x: 1, y: 1, w: 12, h: 1 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 20 * 116, 0, M)).toEqual({ x: 1, y: 1, w: 12, h: 1 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 0, 166, M)).toEqual({ x: 1, y: 1, w: 3, h: 2 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 0, 20 * 166, M)).toEqual({ x: 1, y: 1, w: 3, h: 12 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, -2 * 116, -166, M)).toEqual({ x: 1, y: 1, w: 1, h: 1 })
  })
})

describe('dropRefused', () => {
  it('refuses a drop onto an auto-placed neighbour', () => {
    // Default 12-column layout: calendar (1,1,w6), chat (7,1,w6),
    // home (1,2,w6), push (7,2,w6).
    expect(dropRefused('calendar', { x: 7, y: 1, w: 6, h: 1 }, ['calendar', 'chat', 'home'], {}, {})).toBe(true)
    expect(dropRefused('calendar', { x: 1, y: 2, w: 6, h: 1 }, ['calendar', 'chat', 'home'], {}, {})).toBe(true)
    // The empty cells of the default board accept the drop:
    expect(dropRefused('calendar', { x: 7, y: 3, w: 6, h: 1 }, ['calendar', 'chat', 'home'], {}, {})).toBe(false)
  })

  it('refuses a drop onto a stored placement of another tile', () => {
    const geometry = { chat: { x: 1, y: 1, w: 6, h: 1 } }
    expect(dropRefused('calendar', { x: 2, y: 1, w: 4, h: 1 }, ['calendar', 'chat'], {}, geometry)).toBe(true)
    expect(dropRefused('calendar', { x: 7, y: 2, w: 6, h: 1 }, ['calendar', 'chat'], {}, geometry)).toBe(false)
  })

  it('refuses a drop outside the grid', () => {
    // Beyond the right edge:
    expect(dropRefused('calendar', { x: 13, y: 1, w: 1, h: 1 }, ['calendar'], {}, {})).toBe(true)
    // Beyond the bottom edge:
    expect(dropRefused('calendar', { x: 1, y: 13, w: 1, h: 1 }, ['calendar'], {}, {})).toBe(true)
    // A cell taller than the grid:
    expect(dropRefused('calendar', { x: 1, y: 1, w: 12, h: 13 }, ['calendar'], {}, {})).toBe(true)
    // The same bounds against a configured grid:
    expect(dropRefused('calendar', { x: 7, y: 1, w: 1, h: 1 }, ['calendar'], {}, {}, 6, 12)).toBe(true)
    expect(dropRefused('calendar', { x: 1, y: 7, w: 1, h: 1 }, ['calendar'], {}, {}, 12, 6)).toBe(true)
  })

  it('never refuses a drop of a tile onto its own current cell', () => {
    const geometry = { calendar: { x: 1, y: 1, w: 4, h: 2 } }
    expect(dropRefused('calendar', { x: 1, y: 1, w: 4, h: 2 }, ORDER, {}, geometry)).toBe(false)
  })
})

describe('measureGrid', () => {
  it('derives the column/row pitch from the board box and the computed gap', () => {
    const board = document.createElement('div')
    // jsdom: no layout; stub the box and the computed gap.
    board.getBoundingClientRect = () =>
      ({
        left: 10,
        top: 20,
        width: 1376, // 12 * 100 + 11 * 16
        height: 1976, // 12 * 150 + 11 * 16
        x: 10,
        y: 20,
        right: 1386,
        bottom: 1996,
        toJSON: () => null,
      }) as DOMRect
    const orig = window.getComputedStyle
    window.getComputedStyle = (el: Element) =>
      new Proxy(orig(el), {
        get: (t, k) => (k === 'columnGap' ? '16px' : (t as unknown as Record<PropertyKey, unknown>)[k]),
      }) as unknown as CSSStyleDeclaration
    try {
      expect(measureGrid(board, 12, 12)).toEqual({ left: 10, top: 20, colW: 100, rowH: 150, gap: 16 })
      // Degenerate box: a zero width cannot be measured.
      board.getBoundingClientRect = () =>
        ({ left: 0, top: 0, width: 0, height: 0, x: 0, y: 0, right: 0, bottom: 0, toJSON: () => null }) as DOMRect
      expect(measureGrid(board, 12, 12)).toBeNull()
    } finally {
      window.getComputedStyle = orig
    }
  })
})
