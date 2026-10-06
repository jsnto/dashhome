import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { REFRESH_SECONDS_EVENT } from './refreshBus'
import type { ApiBoard, ApiConfig, BoardViews, CalendarMode, ViewId } from './types'
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

interface PanelProps {
  cfg?: ApiConfig | null
  theme?: Theme
  onThemeChange?: (t: Theme) => void
  views?: BoardViews
  onBoard?: (b: ApiBoard) => void
  onClose?: () => void
}

function renderPanel(props: PanelProps = {}) {
  const onThemeChange = props.onThemeChange ?? vi.fn()
  const onClose = props.onClose ?? vi.fn()
  const view = render(
    <SettingsPanel
      cfg={props.cfg ?? fixtureConfig}
      theme={props.theme ?? 'dark'}
      onThemeChange={onThemeChange}
      views={props.views}
      onBoard={props.onBoard}
      onClose={onClose}
    />,
  )
  return { ...view, onThemeChange, onClose }
}

/** Navigate the sidebar to a section. */
function go(section: 'Appearance' | 'Views' | 'Services' | 'Updates') {
  fireEvent.click(screen.getByRole('button', { name: section }))
}

/** Renders the panel with a stateful cfg: a views-section save refreshes
 *  the config underneath the open panel, exactly the way App wires
 *  onBoard. `signals.board` is set as soon as the save's answer has been
 *  handed to onBoard (before the state update commits). */
function renderPanelWithLiveCfg(initial: ApiConfig) {
  const signals: { board: ApiBoard | null } = { board: null }
  function Harness() {
    const [cfg, setCfg] = useState<ApiConfig | null>(initial)
    return (
      <SettingsPanel
        cfg={cfg}
        theme="dark"
        onThemeChange={() => {}}
        onBoard={(board) => {
          signals.board = board
          setCfg((c) => (c ? { ...c, board } : c))
        }}
        onClose={() => {}}
      />
    )
  }
  return { ...render(<Harness />), signals }
}

function disabled(el: HTMLElement): boolean {
  return (el as HTMLButtonElement).disabled === true
}

/** Stub PUT /api/config/views, echoing the merged layout back as the board. */
function stubViewsApi(board: ApiBoard) {
  const calls: { body: unknown }[] = []
  const fetchMock = vi.fn().mockImplementation(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    if (url === '/api/config/views' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      calls.push({ body })
      const views: BoardViews = board.views ?? {
        order: ['calendar', 'chat', 'home', 'push'],
        hidden: [],
        calendar_mode: 'month',
      }
      const merged: BoardViews = {
        ...views,
        order: (body.order as ViewId[]) ?? views.order,
        hidden: body.hidden !== undefined ? (body.hidden as ViewId[]) : views.hidden,
        calendar_mode: (body.calendar_mode as CalendarMode) ?? views.calendar_mode,
      }
      board.views = merged
      return new Response(JSON.stringify({ board: { ...board, views: merged } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url === '/api/config') {
      return new Response(JSON.stringify({ board, integrations: fixtureConfig.integrations }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response(JSON.stringify({ error: 'not_configured' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, calls }
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('settings: page and sidebar', () => {
  it('opens on Appearance and navigates the sections from the sidebar', () => {
    renderPanel()
    // The sidebar lists every section.
    for (const label of ['Appearance', 'Views', 'Services', 'Updates']) {
      expect(screen.getByRole('button', { name: label })).toBeTruthy()
    }
    // Appearance is the default section.
    expect(screen.getByRole('heading', { name: 'Appearance' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: /services in use/i })).toBeNull()

    go('Services')
    expect(screen.getByRole('heading', { name: /services in use/i })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Appearance' })).toBeNull()

    go('Updates')
    expect(screen.getByRole('heading', { name: 'Updates' })).toBeTruthy()

    go('Views')
    expect(screen.getByRole('heading', { name: 'Views' })).toBeTruthy()
  })

  it('closes when the user clicks the backdrop outside the card', () => {
    const onClose = vi.fn()
    renderPanel({ onClose })
    const wrap = document.querySelector('.settings-wrap') as HTMLElement
    const card = document.querySelector('.card.settings') as HTMLElement
    expect(wrap).toBeTruthy()
    expect(card).toBeTruthy()

    // A click on the backdrop area itself (not the card) closes.
    fireEvent.pointerDown(wrap)
    fireEvent.click(wrap)
    expect(onClose).toHaveBeenCalledTimes(1)

    // A click inside the card does not close.
    fireEvent.click(card)
    expect(onClose).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('heading', { name: 'Settings' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('keeps the panel open when a press starts inside the card and releases on the backdrop', () => {
    const onClose = vi.fn()
    renderPanel({ onClose })
    const wrap = document.querySelector('.settings-wrap') as HTMLElement
    const card = document.querySelector('.card.settings') as HTMLElement
    expect(wrap).toBeTruthy()
    expect(card).toBeTruthy()

    // A text-selection drag: the press starts inside the card, the click
    // lands on the backdrop (the common ancestor) — the panel must stay
    // open.
    fireEvent.pointerDown(card)
    fireEvent.click(wrap)
    expect(onClose).not.toHaveBeenCalled()

    // A plain press starting on the backdrop still closes.
    fireEvent.pointerDown(wrap)
    fireEvent.click(wrap)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('still closes from the Close button', () => {
    const onClose = vi.fn()
    renderPanel({ onClose })
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes on Escape', () => {
    const onClose = vi.fn()
    renderPanel({ onClose })
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

describe('settings: services in use', () => {
  it('renders one row per configured source with its status', () => {
    renderPanel()
    go('Services')
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
    go('Services')
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
    go('Services')
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
    go('Services')
    expect(screen.getAllByText('Not configured')).toHaveLength(4)
  })
})

describe('settings: updates interval round-trip', () => {
  it('keeps an edited interval when a views save refreshes the config', async () => {
    stubViewsApi({ ...fixtureConfig.board })
    const { signals } = renderPanelWithLiveCfg(fixtureConfig) // refresh_seconds 30
    go('Updates')
    const input = screen.getByRole('spinbutton', { name: /refresh interval/i }) as HTMLInputElement
    fireEvent.change(input, { target: { value: '120' } })

    // A views-section save persists and refreshes the config underneath
    // the open panel (onBoard adopts the answer as a fresh cfg).
    go('Views')
    fireEvent.click(screen.getByRole('button', { name: 'Move Calendar down' }))
    await vi.waitFor(() => expect(signals.board).toBeTruthy())
    // Commit the config refresh (and any field re-seed it triggers)
    // before re-checking the interval field.
    await act(async () => {})

    // The typed value survived the config refresh.
    go('Updates')
    const reopened = screen.getByRole('spinbutton', { name: /refresh interval/i }) as HTMLInputElement
    expect(reopened.value).toBe('120')
  })

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
    go('Updates')
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
    go('Updates')
    const input = screen.getByRole('spinbutton', { name: /refresh interval/i }) as HTMLInputElement
    // Out-of-range values are also blocked by the browser's own constraint
    // validation; submit the form directly to exercise the server error.
    fireEvent.change(input, { target: { value: '3' } })
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }).closest('form') as HTMLFormElement)

    await screen.findByText(/bad_refresh_seconds — refresh_seconds must be between 5 and 3600/)
    expect(events).toEqual([])
  })
})

describe('settings: views section', () => {
  it('lists the four views in order with their board slots, all visible', () => {
    const { calls } = stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Views')

    const rows = document.querySelectorAll('.view-row')
    expect(rows).toHaveLength(4)
    const names = [...rows].map((r) => (r.querySelector('.view-name') as HTMLElement).textContent)
    expect(names).toEqual(['Calendar', 'Hermes chat', 'Home', 'Hermes push'])
    const slots = [...rows].map((r) => (r.querySelector('.view-slot') as HTMLElement).textContent)
    expect(slots).toEqual(['main top', 'main bottom', 'side top', 'side bottom'])
    // All visible by default.
    expect(screen.getAllByText('Visible')).toHaveLength(4)
    // No saves until the user changes something.
    expect(calls).toHaveLength(0)
  })

  it('honors a stored views layout from the board config', () => {
    stubViewsApi({ ...fixtureConfig.board })
    renderPanel({
      views: {
        order: ['home', 'push', 'calendar', 'chat'],
        hidden: ['chat'],
        calendar_mode: 'schedule',
      },
    })
    go('Views')
    const rows = document.querySelectorAll('.view-row')
    const names = [...rows].map((r) => (r.querySelector('.view-name') as HTMLElement).textContent)
    expect(names).toEqual(['Home', 'Hermes push', 'Calendar', 'Hermes chat'])
    expect(screen.getByText('Hidden')).toBeTruthy()
    expect(screen.getAllByText('Visible')).toHaveLength(3)
    // The stored calendar mode is selected.
    const scheduleTab = screen.getByRole('button', { name: 'Schedule summary' })
    expect(scheduleTab.getAttribute('aria-pressed')).toBe('true')
  })

  it('sends the new order when a view is moved up', async () => {
    const { calls, fetchMock } = stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Views')

    const up = screen.getByRole('button', { name: 'Move Hermes chat up' })
    expect(disabled(screen.getByRole('button', { name: 'Move Calendar up' }))).toBe(true)
    expect(disabled(up)).toBe(false)

    fireEvent.click(up)
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].body).toEqual({ order: ['chat', 'calendar', 'home', 'push'] })

    // The row order updates in place (and the slot hints move with it).
    const names = [...document.querySelectorAll('.view-name')].map(
      (n) => (n as HTMLElement).textContent,
    )
    expect(names).toEqual(['Hermes chat', 'Calendar', 'Home', 'Hermes push'])
    const slots = [...document.querySelectorAll('.view-slot')].map(
      (n) => (n as HTMLElement).textContent,
    )
    expect(slots).toEqual(['main top', 'main bottom', 'side top', 'side bottom'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('sends the new order when a view is moved down and disables the ends', async () => {
    const { calls } = stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Views')

    const down = screen.getByRole('button', { name: 'Move Calendar down' })
    fireEvent.click(down)
    // The optimistic move renders at once; the control re-enables when the
    // save settles.
    await vi.waitFor(() => {
      expect(calls).toHaveLength(1)
      expect(disabled(screen.getByRole('button', { name: 'Move Calendar down' }))).toBe(false)
    })
    expect(calls[0].body).toEqual({ order: ['chat', 'calendar', 'home', 'push'] })

    // Hermes chat is now first; Calendar is second with its up button re-enabled.
    expect(disabled(screen.getByRole('button', { name: 'Move Hermes chat up' }))).toBe(true)
    expect(disabled(screen.getByRole('button', { name: 'Move Hermes push down' }))).toBe(true)
  })

  it('sends the hidden set when a view is hidden or shown again', async () => {
    const { calls } = stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Views')

    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes push' }))
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].body).toEqual({ hidden: ['push'] })
    // The row flips to Hidden and the checkbox is unchecked.
    expect(screen.getByText('Hidden')).toBeTruthy()
    const toggle = screen.getByRole('checkbox', { name: 'Show Hermes push' }) as HTMLInputElement
    expect(toggle.checked).toBe(false)

    // Let the save settle, then show it again: the hidden set clears.
    await vi.waitFor(() => expect(disabled(screen.getByRole('button', { name: 'Move Home up' }))).toBe(false))
    fireEvent.click(toggle)
    await vi.waitFor(() => expect(calls).toHaveLength(2))
    expect(calls[1].body).toEqual({ hidden: [] })
    expect(screen.queryByText('Hidden')).toBeNull()
  })

  it('sends the calendar render mode when the pick changes', async () => {
    const { calls } = stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Views')

    expect(screen.getByRole('button', { name: 'Calendar' }).getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'Schedule summary' }))
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].body).toEqual({ calendar_mode: 'schedule' })
    expect(screen.getByRole('button', { name: 'Schedule summary' }).getAttribute('aria-pressed')).toBe(
      'true',
    )

    // Let the save settle, then back to the month grid.
    await vi.waitFor(() => expect(disabled(screen.getByRole('button', { name: 'Calendar' }))).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: 'Calendar' }))
    await vi.waitFor(() => expect(calls).toHaveLength(2))
    expect(calls[1].body).toEqual({ calendar_mode: 'month' })
  })

  it('reports the save error and reverts the optimistic change', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ error: 'board_file_not_writable', detail: 'board file not writable: denied' }),
        { status: 503, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderPanel()
    go('Views')

    fireEvent.click(screen.getByRole('button', { name: 'Move Home up' }))

    await screen.findByText(/board_file_not_writable — board file not writable: denied/)
    // The optimistic move reverted: Home is back in third place.
    const names = [...document.querySelectorAll('.view-name')].map(
      (n) => (n as HTMLElement).textContent,
    )
    expect(names).toEqual(['Calendar', 'Hermes chat', 'Home', 'Hermes push'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
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
