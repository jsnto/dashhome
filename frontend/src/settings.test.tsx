import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { REFRESH_SECONDS_EVENT } from './refreshBus'
import type { ApiBoard, ApiConfig, BoardViews, CalendarMode, GridDims, ViewId } from './types'
import type { Theme } from './theme'
import { normalizeGrid, normalizeViews } from './utils'
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
  grid?: GridDims
  onBoard?: (b: ApiBoard) => void
  onClose?: () => void
}

function renderPanel(props: PanelProps = {}) {
  const onThemeChange = props.onThemeChange ?? vi.fn()
  const onClose = props.onClose ?? vi.fn()
  const cfg = props.cfg ?? fixtureConfig
  const view = render(
    <SettingsPanel
      cfg={cfg}
      theme={props.theme ?? 'dark'}
      onThemeChange={onThemeChange}
      views={props.views}
      grid={props.grid ?? normalizeGrid(cfg.board.grid)}
      onBoard={props.onBoard}
      onClose={onClose}
    />,
  )
  return { ...view, onThemeChange, onClose }
}

/** Navigate the sidebar to a section. */
function go(section: 'Appearance' | 'Tiles' | 'Home Assistant' | 'Services' | 'Updates') {
  fireEvent.click(screen.getByRole('button', { name: section }))
}

/** The settings content area (the sidebar labels live outside it). */
function content() {
  return document.querySelector('.settings-content') as HTMLElement
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
        views={normalizeViews(null)}
        grid={normalizeGrid(cfg?.board.grid)}
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

/** Stub PUT /api/config/views, echoing the merged layout back as the board.
 *  Optionally serves the HA entity list (GET /api/ha/entities). */
const defaultSizes = { calendar: 'm', chat: 'm', home: 'm', push: 'm' } as const

function stubViewsApi(board: ApiBoard, haEntities: unknown[] = []) {
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
        sizes: { ...defaultSizes },
      }
      // Geometry merges per tile; a null entry clears that tile's placement.
      let geometry = { ...(views.geometry ?? {}) }
      if (body.geometry) {
        for (const [id, cell] of Object.entries(body.geometry as Record<string, unknown>)) {
          if (cell === null) delete geometry[id]
          else geometry[id] = cell as never
        }
      }
      const merged: BoardViews = {
        ...views,
        order: (body.order as ViewId[]) ?? views.order,
        hidden: body.hidden !== undefined ? (body.hidden as ViewId[]) : views.hidden,
        calendar_mode: (body.calendar_mode as CalendarMode) ?? views.calendar_mode,
        sizes: { ...defaultSizes, ...(views.sizes ?? {}), ...((body.sizes as object) ?? {}) },
        ...(Object.keys(geometry).length > 0 ? { geometry } : {}),
        ...(body.ha_tiles !== undefined ? { ha_tiles: body.ha_tiles as never[] } : {}),
      }
      board.views = merged
      return new Response(JSON.stringify({ board: { ...board, views: merged } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url === '/api/config/grid' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as { cols?: number; rows?: number }
      if (
        typeof body.cols !== 'number' || typeof body.rows !== 'number' ||
        body.cols < 1 || body.cols > 24 || body.rows < 1 || body.rows > 24
      ) {
        return new Response(JSON.stringify({ error: 'bad_grid', detail: 'grid dimensions must be between 1 and 24' }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      board.grid = { cols: body.cols, rows: body.rows }
      return new Response(JSON.stringify({ board }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url === '/api/ha/entities') {
      return new Response(JSON.stringify({ entities: haEntities, total: haEntities.length }), {
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
    for (const label of ['Appearance', 'Tiles', 'Home Assistant', 'Services', 'Updates']) {
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

    go('Tiles')
    expect(screen.getByRole('heading', { name: 'Tiles' })).toBeTruthy()
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
    // The service rows live in the content area (the sidebar now has a
    // Home Assistant section too, so query within the content).
    const c = content()
    for (const label of ['Google Calendar', 'Home Assistant', 'Hermes chat', 'Hermes push']) {
      expect(within(c).getByText(label)).toBeTruthy()
    }
    // Google is configured but awaiting the one-time sign-in.
    expect(within(c).getByText('Sign-in required')).toBeTruthy()
    const link = within(c).getByRole('link', { name: 'Sign-in required' })
    expect(link.getAttribute('href')).toBe('https://accounts.example.com/login')
    // HA and push are configured.
    expect(within(c).getAllByText('Configured')).toHaveLength(2)
    // Only the chat integration is off.
    expect(within(c).getAllByText('Not configured')).toHaveLength(1)
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
    expect(within(content()).getAllByText('Not configured')).toHaveLength(4)
  })
})

describe('settings: updates interval round-trip', () => {
  it('keeps an edited interval when a views save refreshes the config', async () => {
    stubViewsApi({ ...fixtureConfig.board })
    const { signals } = renderPanelWithLiveCfg(fixtureConfig) // refresh_seconds 30
    go('Updates')
    const input = screen.getByRole('spinbutton', { name: /refresh interval/i }) as HTMLInputElement
    fireEvent.change(input, { target: { value: '120' } })

    // A tiles-section save persists and refreshes the config underneath
    // the open panel (onBoard adopts the answer as a fresh cfg).
    go('Tiles')
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

describe('settings: tiles section', () => {
  it('lists the four tiles in order with their size step, all on the main screen', () => {
    const { calls } = stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Tiles')

    const rows = document.querySelectorAll('.view-row')
    expect(rows).toHaveLength(4)
    const names = [...rows].map((r) => (r.querySelector('.view-name') as HTMLElement).textContent)
    expect(names).toEqual(['Calendar', 'Hermes chat', 'Home', 'Hermes push'])
    const sizes = [...rows].map((r) => (r.querySelector('.view-size') as HTMLElement).textContent)
    expect(sizes).toEqual(['Medium tile', 'Medium tile', 'Medium tile', 'Medium tile'])
    // All on the main screen by default.
    expect(screen.getAllByText('Visible')).toHaveLength(4)
    // No saves until the user changes something.
    expect(calls).toHaveLength(0)
  })

  it('honors a stored tile layout from the board config', () => {
    stubViewsApi({ ...fixtureConfig.board })
    renderPanel({
      views: {
        order: ['home', 'push', 'calendar', 'chat'],
        hidden: ['chat'],
        calendar_mode: 'schedule',
        sizes: { calendar: 'm', chat: 's', home: 'l', push: 'xl' },
      },
    })
    go('Tiles')
    const rows = document.querySelectorAll('.view-row')
    const names = [...rows].map((r) => (r.querySelector('.view-name') as HTMLElement).textContent)
    expect(names).toEqual(['Home', 'Hermes push', 'Calendar', 'Hermes chat'])
    // The stored size steps are shown per tile.
    const sizes = [...rows].map((r) => (r.querySelector('.view-size') as HTMLElement).textContent)
    expect(sizes).toEqual(['Large tile', 'Full width tile', 'Medium tile', 'Small tile'])
    expect(screen.getByText('Hidden')).toBeTruthy()
    expect(screen.getAllByText('Visible')).toHaveLength(3)
    // The stored calendar mode is selected.
    const scheduleTab = screen.getByRole('button', { name: 'Schedule summary' })
    expect(scheduleTab.getAttribute('aria-pressed')).toBe('true')
  })

  it('sends the new order when a view is moved up', async () => {
    const { calls, fetchMock } = stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Tiles')

    const up = screen.getByRole('button', { name: 'Move Hermes chat up' })
    expect(disabled(screen.getByRole('button', { name: 'Move Calendar up' }))).toBe(true)
    expect(disabled(up)).toBe(false)

    fireEvent.click(up)
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].body).toEqual({ order: ['chat', 'calendar', 'home', 'push'] })

    // The row order updates in place (the size hints are keyed to the
    // tile, so they move with the row).
    const names = [...document.querySelectorAll('.view-name')].map(
      (n) => (n as HTMLElement).textContent,
    )
    expect(names).toEqual(['Hermes chat', 'Calendar', 'Home', 'Hermes push'])
    const sizes = [...document.querySelectorAll('.view-size')].map(
      (n) => (n as HTMLElement).textContent,
    )
    expect(sizes).toEqual(['Medium tile', 'Medium tile', 'Medium tile', 'Medium tile'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('sends the new order when a view is moved down and disables the ends', async () => {
    const { calls } = stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Tiles')

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
    go('Tiles')

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
    go('Tiles')

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
    go('Tiles')

    fireEvent.click(screen.getByRole('button', { name: 'Move Home up' }))

    await screen.findByText(/board_file_not_writable — board file not writable: denied/)
    // The optimistic move reverted: Home is back in third place.
    const names = [...document.querySelectorAll('.view-name')].map(
      (n) => (n as HTMLElement).textContent,
    )
    expect(names).toEqual(['Calendar', 'Hermes chat', 'Home', 'Hermes push'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows the board grid row with the default 12 x 12 units', () => {
    stubViewsApi({ ...fixtureConfig.board })
    renderPanel()
    go('Tiles')

    expect(within(content()).getByText('Board grid')).toBeTruthy()
    const cols = screen.getByRole('spinbutton', { name: 'Grid columns' }) as HTMLInputElement
    const rows = screen.getByRole('spinbutton', { name: 'Grid rows' }) as HTMLInputElement
    expect(cols.value).toBe('12')
    expect(rows.value).toBe('12')
  })

  it('shows the stored grid from the board config', () => {
    stubViewsApi({ ...fixtureConfig.board, grid: { cols: 8, rows: 5 } })
    renderPanel({ grid: { cols: 8, rows: 5 } })
    go('Tiles')
    const cols = screen.getByRole('spinbutton', { name: 'Grid columns' }) as HTMLInputElement
    const rows = screen.getByRole('spinbutton', { name: 'Grid rows' }) as HTMLInputElement
    expect(cols.value).toBe('8')
    expect(rows.value).toBe('5')
  })

  it('PUTs the new grid dimensions and adopts the server answer', async () => {
    const board: ApiBoard = { ...fixtureConfig.board }
    const fetchMock = stubViewsApi(board).fetchMock
    renderPanel()
    go('Tiles')

    const cols = screen.getByRole('spinbutton', { name: 'Grid columns' }) as HTMLInputElement
    const rows = screen.getByRole('spinbutton', { name: 'Grid rows' }) as HTMLInputElement
    fireEvent.change(cols, { target: { value: '8' } })
    fireEvent.change(rows, { target: { value: '5' } })
    fireEvent.click(within(content()).getByRole('button', { name: 'Save' }))

    await screen.findByText(/Saved — the board grid is now 8 by 5 units./)
    const put = (fetchMock.mock.calls as Array<[string, RequestInit]>).find(
      ([url]) => url === '/api/config/grid',
    )
    expect(put).toBeTruthy()
    expect(JSON.parse(String(put![1].body))).toEqual({ cols: 8, rows: 5 })
    // The answer is adopted: the fields and the board file hold 8x5.
    expect((cols as HTMLInputElement).value).toBe('8')
    expect((rows as HTMLInputElement).value).toBe('5')
    expect(board.grid).toEqual({ cols: 8, rows: 5 })
  })

  it('keeps a typed grid value when a views save refreshes the config', async () => {
    stubViewsApi({ ...fixtureConfig.board })
    const { signals } = renderPanelWithLiveCfg(fixtureConfig)
    go('Tiles')
    const cols = screen.getByRole('spinbutton', { name: 'Grid columns' }) as HTMLInputElement
    fireEvent.change(cols, { target: { value: '9' } })

    // A tiles-section save persists and refreshes the config underneath
    // the open panel (onBoard adopts the answer as a fresh cfg).
    fireEvent.click(screen.getByRole('button', { name: 'Move Calendar down' }))
    await vi.waitFor(() => expect(signals.board).toBeTruthy())
    await act(async () => {})

    // The typed value survived the config refresh (the stored grid is
    // still the default, so the untouched field re-seeds to 12; the
    // edited one keeps its draft).
    const reopened = screen.getByRole('spinbutton', { name: 'Grid columns' }) as HTMLInputElement
    expect(reopened.value).toBe('9')
    const rows = screen.getByRole('spinbutton', { name: 'Grid rows' }) as HTMLInputElement
    expect(rows.value).toBe('12')
  })

  it('reports an out-of-range grid without saving', async () => {
    const fetchMock = stubViewsApi({ ...fixtureConfig.board }).fetchMock
    renderPanel()
    go('Tiles')

    const cols = screen.getByRole('spinbutton', { name: 'Grid columns' }) as HTMLInputElement
    fireEvent.change(cols, { target: { value: '99' } })
    // The browser's own constraint validation (max=24) blocks the
    // submit on a real click; submit the form directly to exercise the
    // validation the handler runs on every submission.
    fireEvent.submit(
      within(content()).getByRole('button', { name: 'Save' }).closest('form') as HTMLFormElement,
    )

    await screen.findByText(/bad_grid — Grid dimensions must be whole numbers between 1 and 24./)
    expect(fetchMock).not.toHaveBeenCalledWith(
      expect.stringContaining('/api/config/grid'),
      expect.anything(),
    )
    expect(cols.value).toBe('99') // the draft is kept for correction
  })

  it('reports a failed grid save and keeps the previous dimensions', async () => {
    const fetchMock = vi.fn().mockImplementation(async (input: unknown) => {
      if (String(input) === '/api/config/grid') {
        return new Response(
          JSON.stringify({ error: 'board_file_not_writable', detail: 'denied' }),
          { status: 503, headers: { 'Content-Type': 'application/json' } },
        )
      }
      return new Response(JSON.stringify({ error: 'not_configured' }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()
    go('Tiles')

    const cols = screen.getByRole('spinbutton', { name: 'Grid columns' }) as HTMLInputElement
    fireEvent.change(cols, { target: { value: '8' } })
    fireEvent.click(within(content()).getByRole('button', { name: 'Save' }))

    await screen.findByText(/board_file_not_writable — denied/)
    // The failed save did not change the stored dimensions (the draft is
    // kept so the user can correct and retry).
    expect(cols.value).toBe('8')
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

describe('settings: Home Assistant section', () => {
  const entity = {
    entity_id: 'light.kitchen',
    label: 'Kitchen',
    friendly: 'Kitchen light',
    state: 'on',
    attributes: {},
    last_updated: '2026-01-01T00:00:00Z',
    last_changed: '2026-01-01T00:00:00Z',
  }

  it('adds a button tile for an entity and persists the full list', async () => {
    const board: ApiBoard = {
      ha_entities: [],
      ha_labels: {},
      calendars: [],
      refresh_seconds: 30,
      views: {
        order: ['calendar', 'chat', 'home', 'push'],
        hidden: [],
        calendar_mode: 'month',
        sizes: { ...defaultSizes },
      },
    }
    const { calls } = stubViewsApi(board, [entity])
    renderPanel({ views: board.views ?? undefined })
    go('Home Assistant')

    // The entity picker is fed by the entity list (the option's label).
    await screen.findByText('Kitchen')
    const input = within(content()).getByRole('combobox', { name: 'Home Assistant entity' }) as HTMLInputElement
    fireEvent.change(input, { target: { value: 'light.kitchen' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add tile' }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].body).toEqual({
      ha_tiles: [{ id: 'ha-1', entity: 'light.kitchen', kind: 'button' }],
    })
    // The save answer lands: the row shows the new tile.
    await waitFor(() => expect(document.querySelectorAll('.ha-row')).toHaveLength(1))
    expect(board.views?.ha_tiles).toHaveLength(1)
  })

  it('adds a labeled visual tile with a sequential id', async () => {
    const board: ApiBoard = {
      ha_entities: [],
      ha_labels: {},
      calendars: [],
      refresh_seconds: 30,
      views: {
        order: ['calendar', 'chat', 'home', 'push'],
        hidden: [],
        calendar_mode: 'month',
        sizes: { ...defaultSizes },
        ha_tiles: [{ id: 'ha-1', entity: 'light.kitchen', kind: 'button' }],
      },
    }
    const { calls } = stubViewsApi(board, [
      entity,
      {
        entity_id: 'sensor.temperature',
        label: 'Temperature',
        friendly: 'Temperature',
        state: '21.5',
        attributes: {},
        last_updated: '2026-01-01T00:00:00Z',
        last_changed: '2026-01-01T00:00:00Z',
      },
    ])
    renderPanel({ views: board.views ?? undefined })
    go('Home Assistant')
    await waitFor(() => expect(document.querySelectorAll('.ha-row')).toHaveLength(1))

    fireEvent.click(within(content()).getByRole('button', { name: 'Visual' }))
    const input = within(content()).getByRole('combobox', { name: 'Home Assistant entity' }) as HTMLInputElement
    fireEvent.change(input, { target: { value: 'sensor.temperature' } })
    const label = within(content()).getByRole('textbox', { name: 'Tile label' }) as HTMLInputElement
    fireEvent.change(label, { target: { value: 'Temp' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add tile' }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].body).toEqual({
      ha_tiles: [
        { id: 'ha-1', entity: 'light.kitchen', kind: 'button' },
        { id: 'ha-2', entity: 'sensor.temperature', kind: 'visual', label: 'Temp' },
      ],
    })
    await waitFor(() => expect(document.querySelectorAll('.ha-row')).toHaveLength(2))
  })

  it('removes a tile (the list is a full replacement)', async () => {
    const board: ApiBoard = {
      ha_entities: [],
      ha_labels: {},
      calendars: [],
      refresh_seconds: 30,
      views: {
        order: ['calendar', 'chat', 'home', 'push', 'ha-1'],
        hidden: [],
        calendar_mode: 'month',
        sizes: { ...defaultSizes },
        ha_tiles: [{ id: 'ha-1', entity: 'light.kitchen', kind: 'button' }],
      },
    }
    const { calls } = stubViewsApi(board, [entity])
    renderPanel({ views: board.views ?? undefined })
    go('Home Assistant')
    await waitFor(() => expect(document.querySelectorAll('.ha-row')).toHaveLength(1))

    fireEvent.click(screen.getByRole('button', { name: /Remove .* tile/ }))
    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].body).toEqual({ ha_tiles: [] })
    expect(board.views?.ha_tiles).toEqual([])
  })

  it('shows the not-configured note (and no form) when HA is off', async () => {
    const board: ApiBoard = {
      ha_entities: [],
      ha_labels: {},
      calendars: [],
      refresh_seconds: 30,
      views: {
        order: ['calendar', 'chat', 'home', 'push'],
        hidden: [],
        calendar_mode: 'month',
        sizes: { ...defaultSizes },
      },
    }
    const fetchMock = vi.fn().mockImplementation(async (input: unknown) => {
      const url = String(input)
      if (url === '/api/ha/entities') {
        return new Response(
          JSON.stringify({ error: 'homeassistant_not_configured', detail: 'set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN' }),
          { status: 503, headers: { 'Content-Type': 'application/json' } },
        )
      }
      return new Response(JSON.stringify({ error: 'not_configured' }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel({ views: board.views ?? undefined })
    go('Home Assistant')

    await screen.findByText(/Home Assistant is not configured/)
    expect(within(content()).queryByRole('button', { name: 'Add tile' })).toBeNull()
  })

  it('reports a failed save and rolls the tile list back', async () => {
    const board: ApiBoard = {
      ha_entities: [],
      ha_labels: {},
      calendars: [],
      refresh_seconds: 30,
      views: {
        order: ['calendar', 'chat', 'home', 'push'],
        hidden: [],
        calendar_mode: 'month',
        sizes: { ...defaultSizes },
      },
    }
    const fetchMock = vi.fn().mockImplementation(async (input: unknown) => {
      if (String(input) === '/api/config/views') {
        return new Response(
          JSON.stringify({ error: 'board_file_not_writable', detail: 'denied' }),
          { status: 503, headers: { 'Content-Type': 'application/json' } },
        )
      }
      return new Response(JSON.stringify({ entities: [entity], total: 1 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel({ views: board.views ?? undefined })
    go('Home Assistant')
    await screen.findByText('Kitchen')

    const input = within(content()).getByRole('combobox', { name: 'Home Assistant entity' }) as HTMLInputElement
    fireEvent.change(input, { target: { value: 'light.kitchen' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add tile' }))

    await screen.findByText(/board_file_not_writable — denied/)
    // The failed add did not stick: the tile list is empty again.
    expect(document.querySelectorAll('.ha-row')).toHaveLength(0)
  })
})
