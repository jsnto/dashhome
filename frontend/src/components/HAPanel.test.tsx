import { cleanup, fireEvent, render, screen } from '@testing-library/react'
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

const embedUrlCalls = (fetchMock: { mock: { calls: unknown[][] } }) =>
  fetchMock.mock.calls.filter(([u]) => String(u) === '/api/ha/embed-url').length

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

    // The embed's actions live in the card's title row, on the right of the
    // title: "Open in new tab" (same URL) and Reload.
    const head = screen.getByRole('heading', { name: 'Home' }).parentElement as HTMLElement
    expect(head.classList.contains('card-head')).toBeTruthy()
    const link = screen.getByRole('link', { name: /open in new tab/i })
    expect(link.closest('.card-head-actions')).toBeTruthy()
    expect(link.getAttribute('href')).toBe('https://ha.local/')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(screen.getByRole('button', { name: 'Reload' }).closest('.card-head-actions')).toBeTruthy()

    // The static embed-bar note is gone, so no bar sits under the title:
    // the iframe fills the card body directly.
    expect(screen.queryByText(/Loads Home Assistant directly in this frame/i)).toBeNull()
    expect(document.querySelector('.ha-full-bar')).toBeNull()
    expect(iframe.closest('.card')).toBe(document.querySelector('.card'))

    // The board view is gone: no Board/Full switch and no entity grid.
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Full' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Refresh' })).toBeNull()
    expect(embedUrlCalls(fetchMock)).toBe(1)
  })

  it('shows a setup note instead of an iframe when HA is not configured', async () => {
    vi.stubGlobal('fetch', stubServer(false))
    render(<HAPanel />)

    expect(await screen.findByText(/Home Assistant is not configured/)).toBeTruthy()
    expect(screen.queryByTitle('Home Assistant dashboard')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Full' })).toBeNull()
    // Without a URL the embed actions stay out of the title row.
    expect(screen.queryByRole('link', { name: /open in new tab/i })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reload' })).toBeNull()
  })

  it('refetches the embed URL and remounts the iframe when Reload is clicked', async () => {
    const fetchMock = stubServer(true)
    vi.stubGlobal('fetch', fetchMock)
    render(<HAPanel />)
    await screen.findByTitle('Home Assistant dashboard')
    expect(embedUrlCalls(fetchMock)).toBe(1)

    fireEvent.click(screen.getByRole('button', { name: 'Reload' }))
    await vi.waitFor(() => expect(embedUrlCalls(fetchMock)).toBe(2))
    // The refetched URL is applied to the (re-keyed) iframe.
    await vi.waitFor(() =>
      expect((screen.getByTitle('Home Assistant dashboard') as HTMLIFrameElement).src).toBe(
        'https://ha.local/',
      ),
    )
  })
})
