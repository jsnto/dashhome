import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import HAFullPanel, { HA_LOAD_WATCHDOG_MS } from './HAFullPanel'

function json(body: Record<string, unknown>, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const embedUrlCalls = (fetchMock: { mock: { calls: unknown[][] } }) =>
  fetchMock.mock.calls.filter(([u]) => String(u) === '/api/ha/embed-url').length

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('HAFullPanel', () => {
  it('mounts the iframe with the server-assembled URL and an open-in-new-tab link', async () => {
    const url = 'https://ha.local/energy?auth_long_lived=tok'
    const fetchMock = vi.fn(async (_input: unknown, _init?: RequestInit) => json({ url }))
    vi.stubGlobal('fetch', fetchMock)
    render(<HAFullPanel />)

    const iframe = (await screen.findByTitle('Home Assistant dashboard')) as HTMLIFrameElement
    expect(iframe.src).toBe(url)
    const link = screen.getByRole('link', { name: /open in new tab/i })
    expect(link.getAttribute('href')).toBe(url)
    expect(link.getAttribute('target')).toBe('_blank')

    // The embed URL is fetched once; the load probe then replays the frame's
    // request against the full URL as a no-cors fetch.
    expect(embedUrlCalls(fetchMock)).toBe(1)
    const probe = fetchMock.mock.calls.find(([u]) => String(u) === url)
    expect(probe).toBeTruthy()
    expect(probe![1]?.mode).toBe('no-cors')
  })

  it('shows a friendly setup note when HA is not configured (no iframe, no probe)', async () => {
    const fetchMock = vi.fn(async (_input: unknown) =>
      json(
        { error: 'homeassistant_not_configured', detail: 'set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN' },
        503,
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(<HAFullPanel />)

    expect(await screen.findByText(/Home Assistant is not configured/)).toBeTruthy()
    expect(screen.queryByTitle('Home Assistant dashboard')).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/ha/embed-url')
  })

  it('refetches the embed URL when Reload is clicked', async () => {
    const fetchMock = vi.fn(async (_input: unknown) => json({ url: 'https://ha.local/' }))
    vi.stubGlobal('fetch', fetchMock)
    render(<HAFullPanel />)
    await screen.findByTitle('Home Assistant dashboard')

    fireEvent.click(screen.getByRole('button', { name: 'Reload' }))
    await vi.waitFor(() => expect(embedUrlCalls(fetchMock)).toBe(2))
    // The refetched URL is applied to the (re-keyed) iframe.
    await vi.waitFor(() =>
      expect((screen.getByTitle('Home Assistant dashboard') as HTMLIFrameElement).src).toBe(
        'https://ha.local/',
      ),
    )
  })

  it('surfaces a failed load as an actionable note with the URL and open-in-new-tab path', async () => {
    const url = 'https://ha.local/'
    const fetchMock = vi.fn(async (input: unknown) => {
      const u = String(input)
      if (u === '/api/ha/embed-url') return json({ url })
      // Network error on the frame's (unauthenticated) request: a refused
      // hop or unreachable host, as the frame would show it.
      throw new TypeError('fetch failed')
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<HAFullPanel />)

    const note = (await screen.findByRole('alert')) as HTMLElement
    expect(note.textContent).toContain(url)
    const link = within(note).getByRole('link', { name: url })
    expect(link.getAttribute('href')).toBe(url)
    expect(link.getAttribute('target')).toBe('_blank')
    // The bar's own open-in-new-tab path is still there.
    expect(screen.getByRole('link', { name: /open in new tab/i })).toBeTruthy()
  })

  it('surfaces a frame whose load event never arrives, and clears it once load fires', async () => {
    vi.useFakeTimers()
    const url = 'https://ha.local/'
    const fetchMock = vi.fn(async (input: unknown) =>
      String(input) === '/api/ha/embed-url' ? json({ url }) : new Response(''),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { unmount } = render(<HAFullPanel />)

    // Let the embed URL and the probe settle (the probe resolves: the
    // browser can reach HA). No failure note yet.
    await vi.advanceTimersByTimeAsync(1)
    expect(screen.queryByRole('alert')).toBeNull()

    // The load event never fires: after the watchdog the note appears.
    await vi.advanceTimersByTimeAsync(HA_LOAD_WATCHDOG_MS)
    const note = screen.getByRole('alert')
    expect(note.textContent).toContain(url)

    // The load event fires late: the note is cleared.
    const iframe = document.querySelector(
      'iframe[title="Home Assistant dashboard"]',
    ) as HTMLIFrameElement
    fireEvent.load(iframe)
    expect(screen.queryByRole('alert')).toBeNull()

    unmount()
  })
})
