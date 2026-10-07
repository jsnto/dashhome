import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import type { ApiBoard } from './types'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('api.setRefreshSeconds', () => {
  it('PUTs the interval and returns the persisted board', async () => {
    const board: ApiBoard = {
      ha_entities: [],
      ha_labels: {},
      calendars: [],
      refresh_seconds: 45,
    }
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ board }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const got = await api.setRefreshSeconds(45)
    expect(got.refresh_seconds).toBe(45)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/config/refresh')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ refresh_seconds: 45 })
  })

  it('surfaces backend validation errors as ApiError', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          error: 'bad_refresh_seconds',
          detail: 'refresh_seconds must be between 5 and 3600',
        }),
        { status: 400, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const p = api.setRefreshSeconds(3)
    await expect(p).rejects.toMatchObject({
      status: 400,
      code: 'bad_refresh_seconds',
    })
  })
})

describe('api.setViews', () => {
  it('PUTs the view layout and returns the persisted board', async () => {
    const board: ApiBoard = {
      ha_entities: [],
      ha_labels: {},
      calendars: [],
      refresh_seconds: 45,
      views: {
        order: ['chat', 'calendar', 'home', 'push'],
        hidden: ['push'],
        calendar_mode: 'schedule',
        sizes: { calendar: 'm', chat: 'm', home: 'm', push: 'm' },
      },
    }
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ board }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const got = await api.setViews({ order: ['chat', 'calendar', 'home', 'push'] })
    expect(got.views?.order).toEqual(['chat', 'calendar', 'home', 'push'])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/config/views')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ order: ['chat', 'calendar', 'home', 'push'] })
  })

  it('PUTs a per-tile size change', async () => {
    const board: ApiBoard = {
      ha_entities: [],
      ha_labels: {},
      calendars: [],
      refresh_seconds: 45,
      views: {
        order: ['calendar', 'chat', 'home', 'push'],
        hidden: [],
        calendar_mode: 'month',
        sizes: { calendar: 'xl', chat: 'm', home: 'm', push: 'm' },
      },
    }
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ board }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const got = await api.setViews({ sizes: { calendar: 'xl' } })
    expect(got.views?.sizes?.calendar).toBe('xl')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/config/views')
    expect(init.method).toBe('PUT')
    // Only the changed tile's size is sent (the server keeps the rest).
    expect(JSON.parse(String(init.body))).toEqual({ sizes: { calendar: 'xl' } })
  })

  it('surfaces backend validation errors as ApiError', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          error: 'bad_views',
          detail: 'views.order must be a permutation of calendar, chat, home, push',
        }),
        { status: 400, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const p = api.setViews({ order: ['calendar', 'chat', 'home'] })
    await expect(p).rejects.toMatchObject({
      status: 400,
      code: 'bad_views',
    })
  })
})

describe('api.getHAAEmbedURL', () => {
  it('GETs the server-assembled embed URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ url: 'https://ha.local/' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const url = await api.getHAAEmbedURL()
    expect(url).toBe('https://ha.local/')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/ha/embed-url')
  })
})

describe('api.setViews geometry and ha_tiles', () => {
  const board = {
    ha_entities: [],
    ha_labels: {},
    calendars: [],
    refresh_seconds: 45,
    views: {
      order: ['calendar', 'chat', 'home', 'push', 'ha-livingroom'],
      hidden: [],
      calendar_mode: 'month',
      sizes: { calendar: 'm', chat: 'm', home: 'm', push: 'm', 'ha-livingroom': 's' },
      geometry: { calendar: { x: 1, y: 1, w: 3, h: 1 } },
      ha_tiles: [{ id: 'ha-livingroom', entity: 'light.living_room', kind: 'button' }],
    },
  }
  const stub = (status = 200, body: unknown = { board }) => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('PUTs a placement change and returns the persisted board', async () => {
    const saved = {
      ...board,
      views: { ...board.views, geometry: { calendar: { x: 4, y: 2, w: 2, h: 2 } } },
    }
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ board: saved }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const got = await api.setViews({ geometry: { calendar: { x: 4, y: 2, w: 2, h: 2 } } })
    expect(got.views?.geometry?.calendar).toEqual({ x: 4, y: 2, w: 2, h: 2 })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/config/views')
    expect(JSON.parse(String(init.body))).toEqual({ geometry: { calendar: { x: 4, y: 2, w: 2, h: 2 } } })
  })

  it('sends a null geometry entry to clear a placement', async () => {
    const fetchMock = stub()
    await api.setViews({ geometry: { calendar: null } })
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual({ geometry: { calendar: null } })
  })

  it('sends the ha_tiles list (a full replacement) with the order', async () => {
    const fetchMock = stub()
    await api.setViews({
      order: ['calendar', 'chat', 'home', 'push', 'ha-livingroom', 'ha-temp'],
      ha_tiles: [
        { id: 'ha-livingroom', entity: 'light.living_room', kind: 'button' },
        { id: 'ha-temp', entity: 'sensor.living_room_temperature', kind: 'visual', label: 'Temp' },
      ],
    })
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual({
      order: ['calendar', 'chat', 'home', 'push', 'ha-livingroom', 'ha-temp'],
      ha_tiles: [
        { id: 'ha-livingroom', entity: 'light.living_room', kind: 'button' },
        { id: 'ha-temp', entity: 'sensor.living_room_temperature', kind: 'visual', label: 'Temp' },
      ],
    })
  })

  it('surfaces a 503 (no board file) as an ApiError', async () => {
    stub(503, { error: 'no_board_file', detail: 'board file not found: dashhome.json' })
    await expect(api.setViews({ sizes: { chat: 'l' } })).rejects.toMatchObject({
      status: 503,
      code: 'no_board_file',
    })
  })
})

describe('api.getHAEntities', () => {
  it('GETs /api/ha/entities and returns the entity list', async () => {
    const entities = [
      {
        entity_id: 'light.living_room',
        label: 'Living room',
        friendly: 'Living room light',
        state: 'on',
        attributes: {},
        last_updated: '2026-01-01T00:00:00Z',
        last_changed: '2026-01-01T00:00:00Z',
      },
    ]
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ entities, total: 1 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const got = await api.getHAEntities()
    expect(got.total).toBe(1)
    expect(got.entities[0].entity_id).toBe('light.living_room')
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/ha/entities')
  })

  it('surfaces the 503 not-configured as code homeassistant_not_configured', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ error: 'homeassistant_not_configured', detail: 'set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN' }),
        { status: 503, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    await expect(api.getHAEntities()).rejects.toMatchObject({
      status: 503,
      code: 'homeassistant_not_configured',
    })
  })
})

describe('api.callHAService', () => {
  it('POSTs the allowlisted action for the entity and returns the result', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ ok: true, entity_id: 'light.living_room', action: 'toggle', result: { state: 'off' } }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const got = await api.callHAService('light.living_room', 'toggle')
    expect(got.ok).toBe(true)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/ha/service')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ entity_id: 'light.living_room', action: 'toggle' })
  })

  it('surfaces an unknown entity as a 4xx ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: 'entity_not_found', detail: 'entity_not_found' }), {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    await expect(api.callHAService('light.nope', 'toggle')).rejects.toMatchObject({
      status: 404,
      code: 'entity_not_found',
    })
  })

  it('surfaces a 502 upstream failure (HA unreachable)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: 'homeassistant_unreachable', detail: 'dial tcp: no such host' }), {
          status: 502,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    await expect(api.callHAService('light.living_room', 'turn_on')).rejects.toMatchObject({
      status: 502,
      code: 'homeassistant_unreachable',
    })
  })
})
