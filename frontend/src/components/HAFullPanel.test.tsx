import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import HAFullPanel from './HAFullPanel'

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

    // The embed URL is fetched once on mount.
    expect(embedUrlCalls(fetchMock)).toBe(1)
  })

  it('shows a friendly setup note when HA is not configured (no iframe)', async () => {
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
})
