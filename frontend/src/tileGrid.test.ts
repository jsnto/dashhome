import { describe, expect, it } from 'vitest'
import {
  autoLayout,
  boardRows,
  clampCell,
  dragCell,
  dropRefused,
  inBounds,
  measureGrid,
  pointToCell,
  stepWidth,
  type GridMetrics,
} from './tileGrid'
import type { TileGeometry, TileSize } from './types'

const ORDER = ['calendar', 'chat', 'home', 'push']

describe('stepWidth', () => {
  it('maps the size steps to their column spans and defaults to m', () => {
    expect(stepWidth('s')).toBe(2)
    expect(stepWidth('m')).toBe(3)
    expect(stepWidth('l')).toBe(4)
    expect(stepWidth('xl')).toBe(6)
    expect(stepWidth(undefined)).toBe(3)
    expect(stepWidth('bogus')).toBe(3)
  })
})

describe('autoLayout', () => {
  it('flows the default board as a 2x2 of medium tiles', () => {
    const layout = autoLayout(ORDER, {})
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(layout.chat).toEqual({ x: 4, y: 1, w: 3, h: 1 })
    expect(layout.home).toEqual({ x: 1, y: 2, w: 3, h: 1 })
    expect(layout.push).toEqual({ x: 4, y: 2, w: 3, h: 1 })
  })

  it('wraps to the next row when a width does not fit (no infinite wrap)', () => {
    // The regression the xl tile exposed: a full-width tile starts a new
    // row; the packer must terminate and keep flowing around it.
    const layout = autoLayout(ORDER, { chat: 'xl' })
    expect(layout.chat).toEqual({ x: 1, y: 2, w: 6, h: 1 })
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(layout.home).toEqual({ x: 4, y: 1, w: 3, h: 1 })
    expect(layout.push).toEqual({ x: 1, y: 3, w: 3, h: 1 })
  })

  it('respects board order for the auto cells', () => {
    const layout = autoLayout(['push', 'home', 'chat', 'calendar'], {})
    expect(layout.push).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(layout.home).toEqual({ x: 4, y: 1, w: 3, h: 1 })
  })

  it('keeps stored placements as obstacles and flows the rest around them', () => {
    const layout = autoLayout(ORDER, {}, { calendar: { x: 1, y: 1, w: 4, h: 1 } })
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 4, h: 1 })
    // m=3 cannot fit the 2 remaining columns of row 1, so chat wraps:
    expect(layout.chat).toEqual({ x: 1, y: 2, w: 3, h: 1 })
    expect(layout.home).toEqual({ x: 4, y: 2, w: 3, h: 1 })
    expect(layout.push).toEqual({ x: 1, y: 3, w: 3, h: 1 })
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

  it('clamps a wider-than-board stored width to the board', () => {
    const layout = autoLayout(ORDER, {}, { calendar: { x: 1, y: 1, w: 6, h: 1 } })
    expect(layout.calendar).toEqual({ x: 1, y: 1, w: 6, h: 1 })
    expect(layout.chat).toEqual({ x: 1, y: 2, w: 3, h: 1 })
  })
})

describe('boardRows', () => {
  it('counts the rows the layout needs', () => {
    expect(boardRows(ORDER, {})).toBe(2)
    expect(boardRows(ORDER, { chat: 'xl' })).toBe(3)
    expect(boardRows(ORDER, {}, { calendar: { x: 1, y: 4, w: 3, h: 2 } })).toBe(5)
  })
})

describe('inBounds / clampCell', () => {
  it('accepts in-bounds cells and rejects out-of-board ones', () => {
    expect(inBounds({ x: 1, y: 1, w: 1, h: 1 })).toBe(true)
    expect(inBounds({ x: 6, y: 1, w: 1, h: 1 })).toBe(true)
    expect(inBounds({ x: 1, y: 1, w: 6, h: 1 })).toBe(true)
    expect(inBounds({ x: 7, y: 1, w: 1, h: 1 })).toBe(false) // past the right edge
    expect(inBounds({ x: 0, y: 1, w: 1, h: 1 })).toBe(false)
    expect(inBounds({ x: 1, y: 0, w: 1, h: 1 })).toBe(false)
    expect(inBounds({ x: 1, y: 1, w: 7, h: 1 })).toBe(false) // wider than the board
    expect(inBounds({ x: 1, y: 1, w: 1, h: 0 })).toBe(false)
    expect(inBounds({ x: 1.5, y: 1, w: 1, h: 1 })).toBe(false)
  })

  it('clamps a cell back into the grid (rows unbounded downward)', () => {
    expect(clampCell({ x: 0, y: 1, w: 3, h: 1 })).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(clampCell({ x: 9, y: 1, w: 3, h: 1 })).toEqual({ x: 4, y: 1, w: 3, h: 1 })
    expect(clampCell({ x: 1, y: -3, w: 3, h: 1 })).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(clampCell({ x: 1, y: 1, w: 9, h: 1 })).toEqual({ x: 1, y: 1, w: 6, h: 1 })
    expect(clampCell({ x: 5, y: 1, w: 9, h: 1 })).toEqual({ x: 1, y: 1, w: 6, h: 1 })
    expect(clampCell({ x: 1, y: 1, w: 1, h: 0 })).toEqual({ x: 1, y: 1, w: 1, h: 1 })
  })
})

// The measured board box the pointer math runs against: 6 columns of 100px
// with a 16px gap (716px wide), 4 rows of 150px.
const M: GridMetrics = { left: 0, top: 0, colW: 100, rowH: 150, gap: 16 }

describe('pointToCell', () => {
  it('maps a client point to its 1-based cell', () => {
    expect(pointToCell(5, 5, M)).toEqual({ x: 1, y: 1 })
    expect(pointToCell(150, 5, M)).toEqual({ x: 2, y: 1 })
    expect(pointToCell(5, 165, M)).toEqual({ x: 1, y: 1 }) // within the first row's pitch
    expect(pointToCell(5, 200, M)).toEqual({ x: 1, y: 2 })
    expect(pointToCell(500, 500, M)).toEqual({ x: 5, y: 4 })
  })

  it('is null above the board and null far to the left', () => {
    expect(pointToCell(5, -10, M)).toBeNull()
    expect(pointToCell(-20, 5, M)).toBeNull()
  })

  it('is null far outside the board box', () => {
    expect(pointToCell(9999, 9999, M)).toBeNull()
  })
})

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
    expect(dragCell('move', g, 3 * 116, 0, M)).toEqual({ x: 3, y: 1, w: 4, h: 1 }) // 1+2=3
    expect(dragCell('move', g, 5 * 116, 0, M)).toEqual({ x: 3, y: 1, w: 4, h: 1 }) // clamped
    expect(dragCell('move', g, 0, -3 * 166, M)).toEqual({ x: 1, y: 1, w: 4, h: 1 }) // row 1 floor
  })

  it('grows/shrinks width and height from the corner, clamped to >= 1', () => {
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 116, 0, M)).toEqual({ x: 1, y: 1, w: 4, h: 1 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 3 * 116, 0, M)).toEqual({ x: 1, y: 1, w: 6, h: 1 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 6 * 116, 0, M)).toEqual({ x: 1, y: 1, w: 6, h: 1 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, 0, 166, M)).toEqual({ x: 1, y: 1, w: 3, h: 2 })
    expect(dragCell('resize', { x: 1, y: 1, w: 3, h: 1 }, -2 * 116, -166, M)).toEqual({ x: 1, y: 1, w: 1, h: 1 })
  })
})

describe('dropRefused', () => {
  it('refuses a drop onto an auto-placed neighbour', () => {
    // Default layout: calendar (1,1,w3), chat (4,1,w3), home (1,2,w3), push (4,2,w3).
    expect(dropRefused('calendar', { x: 4, y: 1, w: 3, h: 1 }, ['calendar', 'chat', 'home'], {}, {})).toBe(true)
    expect(dropRefused('calendar', { x: 1, y: 2, w: 3, h: 1 }, ['calendar', 'chat', 'home'], {}, {})).toBe(true)
    // The empty lower-right of a 3-tile board accepts the drop:
    expect(dropRefused('calendar', { x: 4, y: 2, w: 3, h: 1 }, ['calendar', 'chat', 'home'], {}, {})).toBe(false)
  })

  it('refuses a drop onto a stored placement of another tile', () => {
    const geometry = { chat: { x: 1, y: 1, w: 3, h: 1 } }
    expect(dropRefused('calendar', { x: 2, y: 1, w: 2, h: 1 }, ['calendar', 'chat'], {}, geometry)).toBe(true)
    expect(dropRefused('calendar', { x: 4, y: 2, w: 3, h: 1 }, ['calendar', 'chat'], {}, geometry)).toBe(false)
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
        width: 680, // 6 * 100 + 5 * 16
        height: 648, // 4 * 150 + 3 * 16
        x: 10,
        y: 20,
        right: 690,
        bottom: 668,
        toJSON: () => null,
      }) as DOMRect
    const orig = window.getComputedStyle
    window.getComputedStyle = (el: Element) =>
      new Proxy(orig(el), {
        get: (t, k) => (k === 'columnGap' ? '16px' : (t as unknown as Record<PropertyKey, unknown>)[k]),
      }) as unknown as CSSStyleDeclaration
    try {
      expect(measureGrid(board, 4)).toEqual({ left: 10, top: 20, colW: 100, rowH: 150, gap: 16 })
      // Degenerate box: a zero width cannot be measured.
      board.getBoundingClientRect = () =>
        ({ left: 0, top: 0, width: 0, height: 0, x: 0, y: 0, right: 0, bottom: 0, toJSON: () => null }) as DOMRect
      expect(measureGrid(board, 4)).toBeNull()
    } finally {
      window.getComputedStyle = orig
    }
  })
})

describe('types plumbing', () => {
  it('keeps the geometry/size types aligned with the grid bounds', () => {
    const g: TileGeometry = { x: 1, y: 1, w: 6, h: 1 }
    expect(inBounds(g)).toBe(true)
    const s: TileSize = 'xl'
    expect(stepWidth(s)).toBe(6)
  })
})
