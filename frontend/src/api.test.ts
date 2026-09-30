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

describe('api.getHADashboards', () => {
  it('GETs the dashboard list and passes reload through', async () => {
    const fetchMock = vi.fn(async (_input: unknown) =>
      new Response(JSON.stringify({ dashboards: [{ id: 'default', title: 'My Home' }] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const list = await api.getHADashboards()
    expect(list[0].id).toBe('default')
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/ha/dashboards')

    await api.getHADashboards(true)
    expect(String(fetchMock.mock.calls[1][0])).toBe('/api/ha/dashboards?reload=1')
  })
})

describe('api.getHADashboardConfig', () => {
  it('GETs the config of one dashboard, default via empty path', async () => {
    const fetchMock = vi.fn(async (_input: unknown) =>
      new Response(JSON.stringify({ views: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const cfg = (await api.getHADashboardConfig('energy')) as { views: unknown[] }
    expect(cfg.views).toEqual([])
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/ha/dashboard/energy')

    await api.getHADashboardConfig('')
    expect(String(fetchMock.mock.calls[1][0])).toBe('/api/ha/dashboard/')
  })
})
