import {
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { REFRESH_SECONDS_EVENT } from './refreshBus'
import type { ApiConfig } from './types'
import type { Theme } from './theme'
import SettingsPanel from './components/SettingsPanel'

/** Fixture: HA + push configured, Google configured but awaiting sign-in, chat off. */
const fixtureConfig: ApiConfig = {
  board: { ha_entities: [], ha_labels: {}, calendars: [], refresh_seconds: 30 },
  integrations: {
    homeassistant: true,
    google: {
      configured: true,
      mode: 'oauth',
      login_required: true,
      login_url: 'https://accounts.example.com/login',
    },
    hermes_chat: false,
    hermes_push: true,
  },
}

function renderPanel(props: Partial<{ cfg: ApiConfig | null; theme: Theme; onThemeChange: (t: Theme) => void }> = {}) {
  const onThemeChange = props.onThemeChange ?? vi.fn()
  const view = render(
    <SettingsPanel
      cfg={props.cfg ?? fixtureConfig}
      theme={props.theme ?? 'dark'}
      onThemeChange={onThemeChange}
      onClose={vi.fn()}
    />,
  )
  return { ...view, onThemeChange }
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('settings: services in use', () => {
  it('renders one row per configured source with its status', () => {
    renderPanel()
    for (const label of ['Google Calendar', 'Home Assistant', 'Hermes chat', 'Hermes push']) {
      expect(screen.getByText(label)).toBeTruthy()
    }
    // Google is configured but awaiting the one-time sign-in.
    expect(screen.getByText('Sign-in required')).toBeTruthy()
    const link = screen.getByRole('link', { name: 'Sign-in required' })
    expect(link.getAttribute('href')).toBe('https://accounts.example.com/login')
    // HA and push are configured.
    expect(screen.getAllByText('Configured')).toHaveLength(2)
    // Only the chat integration is off.
    expect(screen.getAllByText('Not configured')).toHaveLength(1)
  })

  it('summarizes the full-dashboard embed config (default dashboard)', () => {
    renderPanel()
    expect(screen.getByText(/Embeds the default dashboard/)).toBeTruthy()
    // The Home Assistant row (full-dashboard embed) reports as configured.
    expect(screen.getAllByText('Configured')).toHaveLength(2)
  })

  it('summarizes a configured embed path and token in URL', () => {
    renderPanel({
      cfg: {
        board: { ...fixtureConfig.board, ha_embed: { url_path: 'energy', token_url: true } },
        integrations: fixtureConfig.integrations,
      },
    })
    expect(screen.getByText(/Embeds the \u201cenergy\u201d dashboard/)).toBeTruthy()
    expect(screen.getByText(/Token passed in the URL/)).toBeTruthy()
  })

  it('marks everything not configured when the deployment has no integrations', () => {
    renderPanel({
      cfg: {
        board: fixtureConfig.board,
        integrations: {
          homeassistant: false,
          google: { configured: false, login_required: false, login_url: '' },
          hermes_chat: false,
          hermes_push: false,
        },
      },
    })
    expect(screen.getAllByText('Not configured')).toHaveLength(4)
  })
})

describe('settings: updates interval round-trip', () => {
  it('PUTs the new interval and adopts the server answer without a reload', async () => {
    const board = { ...fixtureConfig.board, refresh_seconds: 12 }
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ board }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const events: number[] = []
    window.addEventListener(REFRESH_SECONDS_EVENT, (e) => {
      events.push((e as CustomEvent<number>).detail)
    })

    renderPanel()
    const input = screen.getByRole('spinbutton', { name: /refresh interval/i }) as HTMLInputElement
    expect(input.value).toBe('30')

    fireEvent.change(input, { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await screen.findByText(/Saved — the Hermes push panel now polls every 12 seconds\./)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/config/refresh')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(String(init.body))).toEqual({ refresh_seconds: 12 })
    // Panels re-arm their timers via the in-page event.
    expect(events).toEqual([12])
    // The input shows the value the server persisted.
    expect(input.value).toBe('12')
  })

  it('shows the backend error for an invalid interval and does not emit', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: 'bad_refresh_seconds', detail: 'refresh_seconds must be between 5 and 3600' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const events: number[] = []
    window.addEventListener(REFRESH_SECONDS_EVENT, (e) => {
      events.push((e as CustomEvent<number>).detail)
    })

    renderPanel()
    const input = screen.getByRole('spinbutton', { name: /refresh interval/i }) as HTMLInputElement
    // Out-of-range values are also blocked by the browser's own constraint
    // validation; submit the form directly to exercise the server error.
    fireEvent.change(input, { target: { value: '3' } })
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }).closest('form') as HTMLFormElement)

    await screen.findByText(/bad_refresh_seconds — refresh_seconds must be between 5 and 3600/)
    expect(events).toEqual([])
  })
})

describe('settings: theme toggle', () => {
  it('offers light/dark and reports the pick to the app', () => {
    const { onThemeChange } = renderPanel({ theme: 'dark' })
    const light = screen.getByRole('button', { name: 'Light' })
    const dark = screen.getByRole('button', { name: 'Dark' })
    expect(light.getAttribute('aria-pressed')).toBe('false')
    expect(dark.getAttribute('aria-pressed')).toBe('true')

    fireEvent.click(light)
    expect(onThemeChange).toHaveBeenCalledTimes(1)
    expect(onThemeChange).toHaveBeenCalledWith('light')
  })
})
