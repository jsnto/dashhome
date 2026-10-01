import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import HAPanel from './HAPanel'

function stubServer(haConfigured: boolean) {
  const fetchMock = vi.fn(async (input: unknown) => {
    const url = String(input)
    const json = (body: Record<string, unknown>, status = 200) =>
      new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
    if (url === '/api/config') {
      return json({
        board: { ha_entities: [], ha_labels: {}, calendars: [], refresh_seconds: 30 },
        integrations: {
          homeassistant: haConfigured,
          google: { configured: false, login_required: false, login_url: '' },
          hermes_chat: false,
          hermes_push: false,
        },
      })
    }
    if (url === '/api/ha/entities') {
      // The board view is gone: the panel must never fetch the entity grid.
      return json({ error: 'unexpected_entities_call' }, 500)
    }
    if (url === '/api/ha/embed-url') {
      if (!haConfigured) {
        return json(
          { error: 'homeassistant_not_configured', detail: 'set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN' },
          503,
        )
      }
      return json({ url: 'https://ha.local/' })
    }
    return json({ error: 'not_found' }, 404)
  })
  return fetchMock
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('HAPanel', () => {
  it('shows the full-dashboard embed behind the kept card shell and header', async () => {
    const fetchMock = stubServer(true)
    vi.stubGlobal('fetch', fetchMock)
    render(<HAPanel />)

    // Card shell and header are kept.
    expect(screen.getByRole('heading', { name: 'Home' })).toBeTruthy()

    // The embed mounts directly with the server-assembled URL.
    const iframe = (await screen.findByTitle('Home Assistant dashboard')) as HTMLIFrameElement
    expect(iframe.src).toBe('https://ha.local/')

    // The embed's own bar is kept.
    expect(screen.getByRole('link', { name: /open in new tab/i })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeTruthy()

    // The board view is gone: no Board/Full switch, no entity grid, and no
    // entity fetch/refresh machinery (the section never calls the endpoint).
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Full' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Refresh' })).toBeNull()
    const calls = fetchMock.mock.calls.map(([u]) => String(u))
    expect(calls.filter((u) => u === '/api/ha/entities')).toHaveLength(0)
    expect(calls.filter((u) => u === '/api/ha/embed-url')).toHaveLength(1)
  })

  it('shows a setup note instead of an iframe when HA is not configured', async () => {
    vi.stubGlobal('fetch', stubServer(false))
    render(<HAPanel />)

    expect(await screen.findByText(/Home Assistant is not configured/)).toBeTruthy()
    expect(screen.queryByTitle('Home Assistant dashboard')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Full' })).toBeNull()
  })
})
