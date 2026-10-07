import { describe, expect, it } from 'vitest'
import {
  GRID_COLS,
  haTileLabel,
  haTileState,
  isHATileId,
  isValidGeometry,
  normalizeGeometry,
  normalizeHATiles,
  normalizeViews,
  rectsOverlap,
  STEP_WIDTH,
  visibleOrder,
  VIEW_LABELS,
} from './utils'

describe('grid constants', () => {
  it('the board is a 6-column grid with the documented size spans', () => {
    expect(GRID_COLS).toBe(6)
    expect(STEP_WIDTH).toEqual({ s: 2, m: 3, l: 4, xl: 6 })
  })
})

describe('isValidGeometry', () => {
  it('accepts exactly the in-bounds 1-based integer cells', () => {
    expect(isValidGeometry({ x: 1, y: 1, w: 1, h: 1 })).toBe(true)
    expect(isValidGeometry({ x: 6, y: 1, w: 1, h: 9 })).toBe(true)
    expect(isValidGeometry({ x: 1, y: 1, w: 6, h: 1 })).toBe(true)
    // out of bounds
    expect(isValidGeometry({ x: 0, y: 1, w: 1, h: 1 })).toBe(false)
    expect(isValidGeometry({ x: 7, y: 1, w: 1, h: 1 })).toBe(false)
    expect(isValidGeometry({ x: 1, y: 1, w: 0, h: 1 })).toBe(false)
    expect(isValidGeometry({ x: 1, y: 1, w: 7, h: 1 })).toBe(false)
    expect(isValidGeometry({ x: 2, y: 1, w: 6, h: 1 })).toBe(false) // 2+6 > 6
    expect(isValidGeometry({ x: 1, y: 0, w: 1, h: 1 })).toBe(false)
    expect(isValidGeometry({ x: 1, y: 1, w: 1, h: 0 })).toBe(false)
    // not integers
    expect(isValidGeometry({ x: 1.5, y: 1, w: 1, h: 1 })).toBe(false)
    expect(isValidGeometry({ x: '1', y: 1, w: 1, h: 1 })).toBe(false)
    // not an object at all (a hand-edited board file may hold anything)
    expect(isValidGeometry(null)).toBe(false)
    expect(isValidGeometry('a')).toBe(false)
    expect(isValidGeometry(42)).toBe(false)
  })
})

describe('rectsOverlap', () => {
  it('uses the half-open interval test on both axes', () => {
    expect(rectsOverlap({ x: 1, y: 1, w: 3, h: 1 }, { x: 4, y: 1, w: 3, h: 1 })).toBe(false) // edge to edge
    expect(rectsOverlap({ x: 1, y: 1, w: 3, h: 1 }, { x: 3, y: 1, w: 3, h: 1 })).toBe(true)
    expect(rectsOverlap({ x: 1, y: 1, w: 3, h: 1 }, { x: 1, y: 2, w: 3, h: 1 })).toBe(false)
    expect(rectsOverlap({ x: 1, y: 1, w: 3, h: 2 }, { x: 1, y: 2, w: 3, h: 1 })).toBe(true)
  })
})

describe('normalizeGeometry', () => {
  const order = ['calendar', 'chat', 'home']
  const ids = new Set(order)

  it('keeps valid entries and drops invalid units (repair, never crash)', () => {
    const out = normalizeGeometry(
      {
        calendar: { x: 1, y: 1, w: 3, h: 1 },
        chat: 'a string', // a freehand edit: not an object
        home: null,
        push: { x: 1, y: 1, w: 1, h: 1 }, // unknown tile id
      },
      order,
      ids,
    )
    expect(out).toEqual({ calendar: { x: 1, y: 1, w: 3, h: 1 } })
  })

  it('drops out-of-bounds entries and duplicate overlaps (first tile in board order wins)', () => {
    const out = normalizeGeometry(
      {
        calendar: { x: 1, y: 1, w: 3, h: 1 },
        chat: { x: 2, y: 1, w: 3, h: 1 }, // overlaps calendar
        home: { x: 8, y: 1, w: 1, h: 1 }, // out of bounds
      },
      order,
      ids,
    )
    expect(out).toEqual({ calendar: { x: 1, y: 1, w: 3, h: 1 } })
  })

  it('tolerates a missing/odd raw map', () => {
    expect(normalizeGeometry(undefined, order, ids)).toEqual({})
    expect(normalizeGeometry('nope', order, ids)).toEqual({})
  })
})

describe('normalizeHATiles', () => {
  it('keeps valid tiles and drops malformed ones (id, entity, kind)', () => {
    const out = normalizeHATiles([
      { id: 'ha-livingroom', entity: 'light.living_room', kind: 'button' },
      { id: 'ha-temp', entity: 'sensor.living_room_temperature', kind: 'visual', label: 'Temp' },
      { id: 'nope', entity: 'light.x', kind: 'button' }, // bad id shape
      { id: 'ha-a', entity: '', kind: 'button' }, // empty entity
      { id: 'ha-b', entity: 'light.x', kind: 'explode' }, // bad kind
      { id: 'ha-c', entity: 'light.x' }, // missing kind
      'garbage',
      { id: 'ha-livingroom', entity: 'light.other', kind: 'visual' }, // duplicate id
    ])
    expect(out).toEqual([
      { id: 'ha-livingroom', entity: 'light.living_room', kind: 'button' },
      { id: 'ha-temp', entity: 'sensor.living_room_temperature', kind: 'visual', label: 'Temp' },
    ])
  })

  it('is a no-op on non-list input', () => {
    expect(normalizeHATiles(undefined)).toEqual([])
    expect(normalizeHATiles({})).toEqual([])
  })
})

describe('isHATileId / haTileLabel / haTileState', () => {
  it('recognizes the ha- id shape', () => {
    expect(isHATileId('ha-livingroom')).toBe(true)
    expect(isHATileId('ha-1a')).toBe(true)
    expect(isHATileId('calendar')).toBe(false)
    expect(isHATileId('HA-x')).toBe(false)
    expect(isHATileId('ha-' + 'a'.repeat(17))).toBe(false)
  })

  it('labels a tile from the tile label, else the entity label, else the entity id', () => {
    expect(haTileLabel({ id: 'ha-a', entity: 'light.x', kind: 'button' }, null)).toBe('light.x')
    expect(
      haTileLabel({ id: 'ha-a', entity: 'light.x', kind: 'button', label: 'Living room' }, null),
    ).toBe('Living room')
    expect(
      haTileLabel({ id: 'ha-a', entity: 'light.x', kind: 'button' }, { label: 'Front door' }),
    ).toBe('Front door')
    expect(
      haTileLabel(
        { id: 'ha-a', entity: 'light.x', kind: 'button', label: '  ' },
        { label: 'Front door' },
      ),
    ).toBe('Front door') // a blank label falls through
  })

  it('uppercases on/off states and passes everything else through', () => {
    expect(haTileState('on')).toBe('ON')
    expect(haTileState('off')).toBe('OFF')
    expect(haTileState('21.5')).toBe('21.5')
  })
})

describe('normalizeViews', () => {
  it('fills defaults for a missing section (the four views, all medium)', () => {
    const v = normalizeViews(null)
    expect(v.order).toEqual(['calendar', 'chat', 'home', 'push'])
    expect(v.hidden).toEqual([])
    expect(v.calendar_mode).toBe('month')
    expect(v.sizes).toEqual({ calendar: 'm', chat: 'm', home: 'm', push: 'm' })
    expect(v.geometry).toEqual({})
    expect(v.ha_tiles).toEqual([])
  })

  it('repairs a malformed section without crashing', () => {
    const v = normalizeViews({
      order: ['chat', 'bogus', 'chat', 42],
      hidden: ['home', 'home', 'nope'],
      calendar_mode: 'sideways',
      sizes: { chat: 'gigantic', home: 's' },
      geometry: { chat: { x: 1, y: 1, w: 3, h: 1 }, home: { x: 1, y: 1, w: 3, h: 1 } },
      ha_tiles: [{ id: 'ha-livingroom', entity: 'light.x', kind: 'button' }],
    } as never)
    // order: deduped valid ids, missing views appended in canonical order
    expect(v.order).toEqual(['chat', 'calendar', 'home', 'push', 'ha-livingroom'])
    // hidden: only valid, deduped ids
    expect(v.hidden).toEqual(['home'])
    expect(v.calendar_mode).toBe('month')
    // sizes: valid steps kept, the rest repaired to m
    expect(v.sizes.chat).toBe('m')
    expect(v.sizes.home).toBe('s')
    expect(v.sizes['ha-livingroom']).toBe('m')
    // geometry: the overlapping entry is dropped (board order: chat first)
    expect(v.geometry.chat).toEqual({ x: 1, y: 1, w: 3, h: 1 })
    expect(v.geometry.home).toBeUndefined()
    expect(v.ha_tiles).toEqual([{ id: 'ha-livingroom', entity: 'light.x', kind: 'button' }])
  })

  it('drops ha_tiles ids that are not valid tiles from order/hidden', () => {
    const v = normalizeViews({
      order: ['calendar', 'ha-gone', 'chat', 'home', 'push'],
      hidden: ['ha-gone'],
    } as never)
    expect(v.order).toEqual(['calendar', 'chat', 'home', 'push'])
    expect(v.hidden).toEqual([])
  })

  it('keeps a valid geometry entry for an HA tile', () => {
    const v = normalizeViews({
      order: ['calendar', 'chat', 'home', 'push', 'ha-a'],
      ha_tiles: [{ id: 'ha-a', entity: 'light.x', kind: 'button' }],
      geometry: { 'ha-a': { x: 1, y: 1, w: 2, h: 1 } },
    } as never)
    expect(v.geometry['ha-a']).toEqual({ x: 1, y: 1, w: 2, h: 1 })
  })
})

describe('visibleOrder', () => {
  it('is the board order minus the hidden tiles (normalizing first)', () => {
    expect(visibleOrder(null)).toEqual(['calendar', 'chat', 'home', 'push'])
    expect(visibleOrder({ order: ['push', 'chat', 'home', 'calendar'], hidden: ['home'], calendar_mode: 'month', sizes: {} })).toEqual([
      'push',
      'chat',
      'calendar',
    ])
  })
})

describe('VIEW_LABELS', () => {
  it('labels the four views', () => {
    expect(VIEW_LABELS.calendar).toBe('Calendar')
    expect(VIEW_LABELS.chat).toBe('Hermes chat')
    expect(VIEW_LABELS.home).toBe('Home')
    expect(VIEW_LABELS.push).toBe('Hermes push')
  })
})
