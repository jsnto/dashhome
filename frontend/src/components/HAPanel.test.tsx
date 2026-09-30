import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import HAPanel from './HAPanel'

const ENTITIES = [
  {
    entity_id: 'climate.thermo',
    label: 'Living heat',
    state: '21.5',
    attributes: { unit_of_measurement: '°C' },
  },
]

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
      if (!haConfigured) {
        return json({ error: 'homeassistant_not_configured', detail: 'set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN' }, 503)
      }
      return json({ entities: ENTITIES, total: ENTITIES.length })
    }
    if (url === '/api/ha/embed-url') {
      return json({ url: 'https://ha.local/' })
    }
    return json({ error: 'not_found' }, 404)
  })
  return fetchMock
}

const embedUrlCalls = (fetchMock: { mock: { calls: unknown[][] } }) =>
  fetchMock.mock.calls.filter(([u]) => String(u) === '/api/ha/embed-url').length

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('HAPanel', () => {
  it('hides the Board/Full switch and shows a setup note when HA is not configured', async () => {
    vi.stubGlobal('fetch', stubServer(false))
    render(<HAPanel />)

    expect(await screen.findByText(/Home Assistant is not configured/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Full' })).toBeNull()
  })

  it('defaults to the Board view; the iframe is mounted only when Full is chosen', async () => {
    const fetchMock = stubServer(true)
    vi.stubGlobal('fetch', fetchMock)
    render(<HAPanel />)

    // Board view: entity grid renders, no iframe yet.
    await screen.findByText('Living heat')
    expect(screen.queryByTitle('Home Assistant dashboard')).toBeNull()
    expect(embedUrlCalls(fetchMock)).toBe(0)

    // The switch exists; Board is the active tab.
    const full = screen.getByRole('button', { name: 'Full' })
    expect(screen.getByRole('button', { name: 'Board' }).getAttribute('aria-pressed')).toBe('true')
    expect(full.getAttribute('aria-pressed')).toBe('false')

    // Switching to Full lazy-mounts the iframe (and only then fetches the URL).
    fireEvent.click(full)
    const iframe = (await screen.findByTitle('Home Assistant dashboard')) as HTMLIFrameElement
    expect(iframe.src).toBe('https://ha.local/')
    expect(embedUrlCalls(fetchMock)).toBe(1)

    // Back to Board: the iframe unmounts.
    fireEvent.click(screen.getByRole('button', { name: 'Board' }))
    await screen.findByText('Living heat')
    expect(screen.queryByTitle('Home Assistant dashboard')).toBeNull()
  })
})
