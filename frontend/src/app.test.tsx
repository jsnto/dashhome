import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { ApiBoard, ApiEvent, BoardViews, CalendarMode, TileGeometry, TileSize, ViewId } from './types'

const integrationsOff = {
  homeassistant: false,
  google: { configured: false, login_required: false, login_url: '' },
  hermes_chat: false,
  hermes_push: false,
}

const DEFAULT_SIZES: Record<ViewId, TileSize> = { calendar: 'm', chat: 'm', home: 'm', push: 'm' }

/** In-memory stand-in for the backend: the board persists across requests. */
function stubServer(initialSeconds: number) {
  const board: ApiBoard = {
    ha_entities: [],
    ha_labels: {},
    calendars: [],
    refresh_seconds: initialSeconds,
  }
  const json = (body: Record<string, unknown>, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    if (url === '/api/config') {
      return json({ board, integrations: integrationsOff })
    }
    if (url === '/api/config/refresh' && init?.method === 'PUT') {
      const req = JSON.parse(String(init.body)) as { refresh_seconds?: number }
      const n = req.refresh_seconds
      if (typeof n !== 'number' || n < 5 || n > 3600) {
        return json(
          { error: 'bad_refresh_seconds', detail: 'refresh_seconds must be between 5 and 3600' },
          400,
        )
      }
      board.refresh_seconds = n
      return json({ board })
    }
    return json({ error: 'not_configured', detail: 'integration not configured' }, 503)
  })
  return { fetchMock, board }
}

/** Events in the current month, all on or after today (so the schedule
 *  summary lists them as upcoming). */
function upcomingEvents() {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  const at = (day: number, h: number, min: number) => new Date(y, m, day, h, min).toISOString()
  const d = now.getDate()
  return [
    {
      calendar_id: 'c1@example.com',
      calendar_label: 'Family',
      id: 'e1',
      summary: 'Standup',
      start: at(d, 9, 30),
      end: at(d, 10, 0),
      all_day: false,
    },
    {
      calendar_id: 'c2@example.com',
      calendar_label: 'Work',
      id: 'e2',
      summary: 'Dinner with Sam',
      start: at(d, 19, 0),
      end: at(d, 20, 0),
      all_day: false,
    },
    {
      calendar_id: 'c1@example.com',
      calendar_label: 'Family',
      id: 'e3',
      summary: 'Picnic',
      start: at(d + 2, 0, 0),
      end: at(d + 3, 0, 0),
      all_day: true,
    },
  ]
}

/** The raw `views` section of the board file (a freehand-editable
 *  surface, so the fixtures may omit fields the backend normalizes). */
type RawViews = {
  order?: string[]
  hidden?: string[]
  calendar_mode?: CalendarMode
  sizes?: Partial<Record<ViewId, TileSize>>
  geometry?: Record<string, { x: number; y: number; w: number; h: number } | null>
  ha_tiles?: Array<{ id: string; entity: string; kind: string; label?: string }>
}

/** Stand-in backend that serves a board with an editable views section
 *  (order/hidden/calendar_mode/sizes, the same partial-update semantics
 *  the backend has: omitted fields keep their value, sizes merges per
 *  tile) and an editable tile grid (board.grid). */
function stubBoardServer(
  initialViews: RawViews | undefined,
  events: ApiEvent[] = upcomingEvents(),
  haEntities: unknown[] = [],
  initialGrid: { cols: number; rows: number } | null = null,
) {
  const board = {
    ha_entities: [],
    ha_labels: {},
    calendars: [],
    refresh_seconds: 30,
    grid: initialGrid,
    views: initialViews ?? null,
  } as unknown as ApiBoard
  const calls: string[] = []
  const haServiceCalls: Array<{ entity_id?: string; action?: string }> = []
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push(`${method} ${url}`)
    if (url === '/api/config') return json({ board, integrations: integrationsOff })
    if (url === '/api/config/grid' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as { cols?: number; rows?: number }
      if (
        typeof body.cols !== 'number' || typeof body.rows !== 'number' ||
        body.cols < 1 || body.cols > 24 || body.rows < 1 || body.rows > 24
      ) {
        return json({ error: 'bad_grid', detail: 'grid dimensions must be between 1 and 24' }, 400)
      }
      board.grid = { cols: body.cols, rows: body.rows }
      return json({ board })
    }
    if (url === '/api/config/views' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      const cur = board.views ?? {
        order: ['calendar', 'chat', 'home', 'push'],
        hidden: [],
        calendar_mode: 'month',
        sizes: { ...DEFAULT_SIZES },
      }
      const sizes = { ...DEFAULT_SIZES, ...(cur.sizes ?? {}) }
      if (body.sizes) Object.assign(sizes, body.sizes)
      // Geometry merges per tile (a null entry clears that tile's placement,
      // restoring automatic placement), mirroring the backend.
      let geometry = { ...(cur.geometry ?? {}) } as Record<string, unknown>
      if (body.geometry) {
        for (const [id, cell] of Object.entries(body.geometry as Record<string, unknown>)) {
          if (cell === null) delete geometry[id]
          else geometry[id] = cell
        }
      }
      const next: BoardViews = {
        order: (body.order as ViewId[]) ?? cur.order,
        hidden: body.hidden !== undefined ? (body.hidden as ViewId[]) : cur.hidden,
        calendar_mode: (body.calendar_mode as CalendarMode) ?? cur.calendar_mode,
        sizes,
        ...(geometry && Object.keys(geometry).length > 0 ? { geometry: geometry as Record<string, TileGeometry> } : {}),
        ...(body.ha_tiles !== undefined ? { ha_tiles: body.ha_tiles as never[] } : cur.ha_tiles !== undefined ? { ha_tiles: cur.ha_tiles } : {}),
      }
      board.views = next
      return json({ board })
    }
    if (url === '/api/ha/entities') {
      const entities = haEntities ?? []
      return json({ entities, total: entities.length })
    }
    if (url === '/api/ha/service' && init?.method === 'POST') {
      const req = JSON.parse(String(init.body)) as { entity_id?: string; action?: string }
      haServiceCalls.push(req)
      const ents = (haEntities ?? []) as Array<{ entity_id: string; state: string }>
      const ent = ents.find((e) => e.entity_id === req.entity_id)
      if (!ent) {
        return json({ error: 'entity_not_found', detail: `entity_not_found: ${req.entity_id}` }, 404)
      }
      if (req.action === 'turn_on') ent.state = 'on'
      else if (req.action === 'turn_off') ent.state = 'off'
      else if (req.action === 'toggle') ent.state = ent.state === 'on' ? 'off' : 'on'
      else return json({ error: 'bad_action', detail: 'action must be toggle, turn_on or turn_off' }, 400)
      return json({ ok: true, entity_id: req.entity_id, action: req.action, result: { state: ent.state } })
    }
    if (url === '/api/calendars') {
      return json({
        calendars: [
          { id: 'c1@example.com', summary: 'Family', label: 'Family' },
          { id: 'c2@example.com', summary: 'Work', label: 'Work' },
        ],
      })
    }
    if (url.startsWith('/api/events')) return json({ events })
    if (url === '/api/ha/embed-url') return json({ url: 'https://ha.local/dashboard' })
    if (url === '/api/hermes/pushes/latest') return json({ error: 'no_pushes' }, 404)
    if (url.startsWith('/api/hermes/pushes?')) return json({ pushes: [] })
    return json({ error: 'not_configured' }, 503)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, board, calls, haServiceCalls, haEntities }
}

function boardEl() {
  return document.querySelector('.board') as HTMLElement
}

/** The tiles in DOM (board) order. */
function tiles() {
  return [...document.querySelectorAll('.tile')]
}

function tileWins() {
  return tiles().map((t) => t.getAttribute('data-win'))
}

function tileSize(win: ViewId) {
  return document.querySelector(`[data-win="${win}"]`)?.getAttribute('data-size')
}

/** This jsdom build has no matchMedia at all; stub it so the board's
 *  stacked-fallback flag (driven from it) can be exercised. */
function stubNarrow(matches: boolean) {
  const mql = {
    matches,
    media: '(max-width: 980px)',
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent() {
      return false
    },
  } as unknown as MediaQueryList
  vi.stubGlobal('matchMedia', () => mql)
}

beforeEach(() => {
  // jsdom does not implement scrollIntoView; ChatPanel calls it on mount.
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView
})

describe('app: tile board', () => {
  it('renders the four tiles in board order, all at the default size', () => {
    stubBoardServer(undefined) // no views section: the default board
    render(<App />)

    // The stacked mobile fallback presents the same board order, so the
    // DOM order pins both.
    expect(tileWins()).toEqual(['calendar', 'chat', 'home', 'push'])
    for (const win of ['calendar', 'chat', 'home', 'push'] as const) {
      expect(tileSize(win)).toBe('m')
    }
    // No old-layout machinery on the board: no columns, no dividers.
    expect(document.querySelector('.col-main')).toBeNull()
    expect(document.querySelector('.col-side')).toBeNull()
    expect(screen.queryAllByRole('separator')).toHaveLength(0)
  })

  it('adopts the stored order and sizes from the board config', async () => {
    stubBoardServer({
      order: ['push', 'home', 'chat', 'calendar'],
      hidden: [],
      calendar_mode: 'month',
      sizes: { calendar: 'l', chat: 'xl', home: 'm', push: 's' },
    })
    render(<App />)

    // Wait for the config fetch: the board adopts the stored layout.
    await waitFor(() => expect(tileWins()).toEqual(['push', 'home', 'chat', 'calendar']))
    expect(tileSize('push')).toBe('s')
    expect(tileSize('home')).toBe('m')
    expect(tileSize('chat')).toBe('xl')
    expect(tileSize('calendar')).toBe('l')
  })

  it('reflows by tile size: each tile spans its size step', async () => {
    stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
      sizes: { calendar: 'xl', chat: 'xl', home: 's', push: 'l' },
    })
    render(<App />)

    await waitFor(() => expect(tileWins()).toHaveLength(4))
    // The size steps land on the board as the data-size attribute; the
    // board's effective layout gives each tile its step's column span
    // (xl = the full grid width, s a third, l two thirds), and the grid
    // reflows around them.
    expect(boardEl().querySelectorAll('.tile[data-size="xl"]')).toHaveLength(2)
    expect(boardEl().querySelectorAll('.tile[data-size="s"]')).toHaveLength(1)
    expect(boardEl().querySelectorAll('.tile[data-size="l"]')).toHaveLength(1)
    // Board order is kept by the grid (a later tile never jumps ahead of
    // an earlier one).
    expect(tileWins()).toEqual(['calendar', 'chat', 'home', 'push'])
  })

  it('does not render or poll a hidden tile; the remaining tiles fill the board', async () => {
    const { calls } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['home', 'push'],
      calendar_mode: 'month',
    })
    render(<App />)

    // Wait for the config fetch: the hidden tiles disappear, and the two
    // visible tiles (both at the default half width) fill the board as a
    // pair of rows.
    await waitFor(() => {
      expect(document.querySelector('[data-win="home"]')).toBeNull()
      expect(document.querySelector('[data-win="push"]')).toBeNull()
    })
    expect(tileWins()).toEqual(['calendar', 'chat'])
    expect(tileSize('calendar')).toBe('m')
    expect(tileSize('chat')).toBe('m')

    // The hidden tiles' data endpoints are not polled once the board has
    // settled: after the config loads the hidden tiles are unmounted, so
    // no further requests go out for them (the default board may issue one
    // mount-time fetch each before the config arrives).
    await waitFor(() => expect(calls.some((c) => c === 'GET /api/config')).toBe(true))
    await waitFor(() => {
      expect(document.querySelector('[data-win="home"]')).toBeNull()
      expect(document.querySelector('[data-win="push"]')).toBeNull()
    })
    const settled = calls.length
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20))
    })
    const fresh = calls.slice(settled)
    expect(fresh).not.toContain('GET /api/ha/embed-url')
    expect(fresh).not.toContain('GET /api/hermes/pushes/latest')
    expect(fresh).not.toContain('GET /api/hermes/pushes?limit=50')
  })

  it('shows an empty-state note when every tile is hidden', async () => {
    stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['calendar', 'chat', 'home', 'push'],
      calendar_mode: 'month',
    })
    render(<App />)
    await screen.findByText(/All dashboard tiles are hidden/)
    expect(tiles()).toHaveLength(0)
    expect(boardEl().querySelector('.board-empty')).toBeTruthy()
  })

  it('keeps the chat conversation when the tile is hidden and re-shown', async () => {
    const { fetchMock } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    vi.stubGlobal(
      'fetch',
      async (input: unknown, init?: RequestInit) => {
        const url = String(input)
        if (url === '/api/hermes/chat' && init?.method === 'POST') {
          const body = JSON.parse(String(init.body)) as { message: string }
          return new Response(JSON.stringify({ reply: `reply to: ${body.message}` }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return fetchMock(input, init)
      },
    )
    render(<App />)
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeTruthy())

    fireEvent.change(screen.getByPlaceholderText('Message Hermes…'), {
      target: { value: 'hello' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByText('reply to: hello')

    // Hide the chat tile (settings → Tiles), then show it again.
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tiles' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes chat' }))
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeNull())
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes chat' }))
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeTruthy())

    // The thread survived the hide/show: the backend is stateless, so the
    // in-memory transcript is the only copy of the conversation.
    expect(screen.getByText('hello')).toBeTruthy()
    expect(screen.getByText('reply to: hello')).toBeTruthy()
  })

  it('lands an in-flight chat reply after the tile is hidden and re-shown', async () => {
    const { fetchMock } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    let release: (() => void) | null = null
    vi.stubGlobal(
      'fetch',
      async (input: unknown, init?: RequestInit) => {
        const url = String(input)
        if (url === '/api/hermes/chat' && init?.method === 'POST') {
          const body = JSON.parse(String(init.body)) as { message: string }
          await new Promise<void>((r) => {
            release = r
          })
          return new Response(JSON.stringify({ reply: `reply to: ${body.message}` }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return fetchMock(input, init)
      },
    )
    render(<App />)
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeTruthy())

    // Send, then hide the tile while the reply is still in flight.
    fireEvent.change(screen.getByPlaceholderText('Message Hermes…'), {
      target: { value: 'hello' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByText('hello')

    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tiles' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes chat' }))
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeNull())

    // The reply lands while the tile is hidden, then the tile comes back.
    await act(async () => {
      release!()
    })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes chat' }))
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeTruthy())
    await screen.findByText('reply to: hello')
  })
})

describe('app: tile maximize (fill board)', () => {
  it('clicking a tile title fills the board with it; clicking it again restores the grid', () => {
    stubBoardServer(undefined)
    render(<App />)

    const home = screen.getByRole('heading', { name: 'Home' })
    expect(boardEl().getAttribute('data-max')).toBeNull()

    fireEvent.click(home)
    expect(boardEl().getAttribute('data-max')).toBe('home')
    // The maximized tile's title row (and its actions, when present) stays
    // in the DOM while maximized, so the user can click the title again.
    expect(document.querySelector('[data-win="home"] .card-head h2')).toBeTruthy()
    // The other tiles stay mounted (the CSS hides them); the sizes are
    // untouched, so the grid comes back exactly.
    expect(tileWins()).toEqual(['calendar', 'chat', 'home', 'push'])
    expect(tileSize('calendar')).toBe('m')

    fireEvent.click(screen.getByRole('heading', { name: 'Home' }))
    expect(boardEl().getAttribute('data-max')).toBeNull()
    expect(tileSize('calendar')).toBe('m')
  })

  it('each tile title toggles its own tile; other clicks do not toggle', () => {
    stubBoardServer(undefined)
    render(<App />)

    fireEvent.click(screen.getByRole('heading', { name: 'Calendar' }))
    expect(boardEl().getAttribute('data-max')).toBe('calendar')

    // Clicking the tile body (not the title) does not change the state.
    fireEvent.click(document.querySelector('[data-win="calendar"] .card-scroll')!)
    expect(boardEl().getAttribute('data-max')).toBe('calendar')

    // A different title switches which tile is maximized.
    fireEvent.click(screen.getByRole('heading', { name: 'Hermes push' }))
    expect(boardEl().getAttribute('data-max')).toBe('push')

    fireEvent.click(screen.getByRole('heading', { name: 'Hermes push' }))
    expect(boardEl().getAttribute('data-max')).toBeNull()
  })

  it('a content heading inside a tile body does not toggle maximize', async () => {
    const { fetchMock } = stubBoardServer(undefined)
    const push = {
      id: 'p1',
      received_at: new Date().toISOString(),
      markdown: '## Heading\n\nSome body.',
    }
    const json = (body: Record<string, unknown>) =>
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    vi.stubGlobal(
      'fetch',
      async (input: unknown, init?: RequestInit) => {
        const url = String(input)
        if (url === '/api/hermes/pushes/latest') return json({ push })
        if (url.startsWith('/api/hermes/pushes?')) return json({ pushes: [push] })
        return fetchMock(input, init)
      },
    )
    render(<App />)

    // A markdown h2 rendered in the push tile body: inside the tile but
    // outside its .card-head title row.
    const content = await screen.findByRole('heading', { name: 'Heading' })
    expect(content.closest('.card-head')).toBeNull()
    expect(content.closest('[data-win="push"]')).toBeTruthy()

    fireEvent.click(content)
    expect(boardEl().getAttribute('data-max')).toBeNull()

    // The real title of the same tile still toggles.
    fireEvent.click(screen.getByRole('heading', { name: 'Hermes push' }))
    expect(boardEl().getAttribute('data-max')).toBe('push')
  })

  it('maximizing works for a tile wherever it sits in the board order', async () => {
    // Calendar last in the board order (the old layout's bug was keyed to
    // the column a view sat in; the tile model has no columns).
    stubBoardServer({
      order: ['chat', 'home', 'push', 'calendar'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toEqual(['chat', 'home', 'push', 'calendar']))

    fireEvent.click(screen.getByRole('heading', { name: 'Calendar' }))
    expect(boardEl().getAttribute('data-max')).toBe('calendar')
    // The maximized tile still renders (in its board position).
    expect(document.querySelector('[data-win="calendar"] .card')).toBeTruthy()

    // Another tile maximizes the other way.
    fireEvent.click(screen.getByRole('heading', { name: 'Home' }))
    expect(boardEl().getAttribute('data-max')).toBe('home')
  })
})

describe('app: tile edit mode', () => {
  it('keeps the normal board free of editing affordances', () => {
    stubBoardServer(undefined)
    render(<App />)

    expect(boardEl().hasAttribute('data-edit')).toBe(false)
    expect(document.querySelector('.tile-edit')).toBeNull()
    const toggle = screen.getByRole('button', { name: 'Edit tiles' })
    expect(toggle.getAttribute('aria-pressed')).toBe('false')
  })

  it('toggling edit mode shows the placement controls on every visible tile', async () => {
    stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['push'],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toEqual(['calendar', 'chat', 'home']))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    expect(boardEl().hasAttribute('data-edit')).toBe(true)
    expect(screen.getByRole('button', { name: 'Edit tiles' }).getAttribute('aria-pressed')).toBe(
      'true',
    )

    // One control row per visible tile (the hidden tile gets none): the
    // move grip and the numeric width/height fields, each showing the
    // tile's effective span. No size-stepper buttons.
    const groups = screen.getAllByRole('group', { name: /tile controls/ })
    expect(groups).toHaveLength(3)
    const calEl = screen.getByRole('group', { name: 'Calendar tile controls' }) as HTMLElement
    const cal = within(calEl)
    const inputs = calEl.querySelectorAll('.tile-size-input')
    expect(inputs).toHaveLength(2)
    expect((inputs[0] as HTMLInputElement).value).toBe('6') // m = half of 12
    expect((inputs[1] as HTMLInputElement).value).toBe('1')
    expect(cal.queryByRole('button', { name: 'S' })).toBeNull()
    expect(cal.queryByRole('button', { name: 'M' })).toBeNull()
    expect(cal.queryByRole('button', { name: 'L' })).toBeNull()
    expect(cal.queryByRole('button', { name: 'XL' })).toBeNull()
    expect(calEl.querySelector('.tile-grip')).toBeTruthy()
    // The corner resize handle is on every visible tile.
    expect(document.querySelectorAll('.tile-resize')).toHaveLength(3)
  })

  it('a numeric width change persists to the board file and round-trips to the tile', async () => {
    const { fetchMock, board, calls } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['chat', 'home', 'push'], // the lone visible tile has room to grow
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toEqual(['calendar']))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const cal = within(screen.getByRole('group', { name: 'Calendar tile controls' }))
    const w = cal.getByRole('spinbutton', { name: 'width of Calendar in grid units' }) as HTMLInputElement
    fireEvent.change(w, { target: { value: '12' } })
    fireEvent.keyDown(w, { key: 'Enter' })

    // The change PUTs to the views endpoint with just this tile's geometry.
    await waitFor(() => {
      expect(calls).toContain('PUT /api/config/views')
    })
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT') as [
      string,
      RequestInit,
    ]
    expect(put[0]).toBe('/api/config/views')
    expect(JSON.parse(String(put[1].body))).toEqual({
      geometry: { calendar: { x: 1, y: 1, w: 12, h: 1 } },
    })

    // The board file holds the placement…
    expect(geometryOf(board).calendar).toEqual({ x: 1, y: 1, w: 12, h: 1 })

    // …and the answer adopts it back onto the tile (the field shows the
    // saved span, the board renders the wider cell).
    await waitFor(() => expect(w.value).toBe('12'))
    const calTile = document.querySelector('[data-win="calendar"]') as HTMLElement
    await waitFor(() => expect(calTile.style.gridColumn).toBe('1 / span 12'))
  })

  it('a numeric height change pins the effective cell and persists', async () => {
    const { board } = stubBoardServer(placedViews)
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    // Home is placed at (1,2,3,1): growing its height keeps its cell.
    const home = within(screen.getByRole('group', { name: 'Home tile controls' }))
    const h = home.getByRole('spinbutton', { name: 'height of Home in grid units' }) as HTMLInputElement
    fireEvent.change(h, { target: { value: '2' } })
    fireEvent.keyDown(h, { key: 'Enter' })
    await waitFor(() => expect(geometryOf(board).home).toEqual({ x: 1, y: 2, w: 3, h: 2 }))
    const homeTile = document.querySelector('[data-win="home"]') as HTMLElement
    await waitFor(() => expect(homeTile.style.gridRow).toBe('2 / span 2'))
  })

  it('clamps a numeric size into the grid before committing', async () => {
    const { board } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['chat', 'home', 'push'],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toEqual(['calendar']))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    // Calendar's effective cell is (1,1,6,1); a width of 20 units cannot
    // fit the 12-column grid: the commit clamps to full width.
    const cal = within(screen.getByRole('group', { name: 'Calendar tile controls' }))
    const w = cal.getByRole('spinbutton', { name: 'width of Calendar in grid units' }) as HTMLInputElement
    fireEvent.change(w, { target: { value: '20' } })
    fireEvent.keyDown(w, { key: 'Enter' })
    await waitFor(() => expect(geometryOf(board).calendar).toEqual({ x: 1, y: 1, w: 12, h: 1 }))
  })

  it('refuses a numeric size that would overlap a neighbour and reverts the fields', async () => {
    const { board, calls } = stubBoardServer(undefined)
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    // The default board: calendar (1,1,6,1), chat (7,1,6,1), home
    // (1,2,6,1), push (7,2,6,1). Growing calendar to 8 units would cover
    // chat's column 7 — the commit is refused and nothing is saved.
    const cal = within(screen.getByRole('group', { name: 'Calendar tile controls' }))
    const w = cal.getByRole('spinbutton', { name: 'width of Calendar in grid units' }) as HTMLInputElement
    fireEvent.change(w, { target: { value: '8' } })
    fireEvent.keyDown(w, { key: 'Enter' })
    await waitFor(() => expect(w.value).toBe('6')) // reverted to the effective width
    expect(calls.filter((c) => c === 'PUT /api/config/views')).toHaveLength(0)
    expect(geometryOf(board).calendar).toBeUndefined()
    const calTile = document.querySelector('[data-win="calendar"]') as HTMLElement
    expect(calTile.style.gridColumn).toBe('1 / span 6')
  })

  it('leaves the board file untouched when a numeric size is a no-op', async () => {
    const { board, calls } = stubBoardServer(undefined)
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    // Entering the tile's current effective width and committing changes
    // nothing: no placement is pinned onto the auto-flowing tile.
    const cal = within(screen.getByRole('group', { name: 'Calendar tile controls' }))
    const w = cal.getByRole('spinbutton', { name: 'width of Calendar in grid units' }) as HTMLInputElement
    fireEvent.change(w, { target: { value: '6' } })
    fireEvent.keyDown(w, { key: 'Enter' })
    expect(calls.filter((c) => c === 'PUT /api/config/views')).toHaveLength(0)
    expect(geometryOf(board).calendar).toBeUndefined()
  })

  it('reports a failed size save and reverts the fields to the effective spans', async () => {
    const { fetchMock } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['chat', 'home', 'push'],
      calendar_mode: 'month',
    })
    vi.stubGlobal(
      'fetch',
      async (input: unknown, init?: RequestInit) => {
        if (String(input) === '/api/config/views' && init?.method === 'PUT') {
          return new Response(
            JSON.stringify({
              error: 'board_file_not_writable',
              detail: 'board file not writable: denied',
            }),
            { status: 503, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return fetchMock(input, init)
      },
    )
    render(<App />)
    await waitFor(() => expect(tileWins()).toEqual(['calendar']))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const cal = within(screen.getByRole('group', { name: 'Calendar tile controls' }))
    const w = cal.getByRole('spinbutton', { name: 'width of Calendar in grid units' }) as HTMLInputElement
    fireEvent.change(w, { target: { value: '12' } })
    fireEvent.keyDown(w, { key: 'Enter' })

    await screen.findByText(/board_file_not_writable — board file not writable: denied/)
    // The failed save reverted the fields to the effective span.
    expect(w.value).toBe('6')
    const calTile = document.querySelector('[data-win="calendar"]') as HTMLElement
    expect(calTile.style.gridColumn).toBe('1 / span 6')
  })

  it('a tile title does not maximize while the board is in edit mode', () => {
    stubBoardServer(undefined)
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    fireEvent.click(screen.getByRole('heading', { name: 'Home' }))
    expect(boardEl().getAttribute('data-max')).toBeNull()

    // Leaving edit mode clears the affordances again.
    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    expect(boardEl().hasAttribute('data-edit')).toBe(false)
    expect(document.querySelector('.tile-edit')).toBeNull()
    // Maximizing works again once the board is back to normal mode.
    fireEvent.click(screen.getByRole('heading', { name: 'Home' }))
    expect(boardEl().getAttribute('data-max')).toBe('home')
  })
})

describe('app: mobile stacked fallback', () => {
  it('stacks the tiles in a single column on a narrow viewport', () => {
    stubNarrow(true)
    stubBoardServer(undefined)
    render(<App />)

    // The board flags the narrow fallback (CSS makes it a single column of
    // full-width tiles); the board order is kept in the stack.
    expect(boardEl().hasAttribute('data-narrow')).toBe(true)
    expect(tileWins()).toEqual(['calendar', 'chat', 'home', 'push'])
  })

  it('keeps the grid (not the stack) on a wide viewport', () => {
    stubNarrow(false)
    stubBoardServer(undefined)
    render(<App />)
    expect(boardEl().hasAttribute('data-narrow')).toBe(false)
  })
})

describe('app: settings screen shows the persisted interval', () => {
  it('reopens with the saved interval, not the mount-time value', async () => {
    const { fetchMock, board } = stubServer(30)
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Updates' }))
    const input = (await screen.findByRole('spinbutton', { name: /refresh interval/i })) as HTMLInputElement
    expect(input.value).toBe('30')

    fireEvent.change(input, { target: { value: '120' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText(/Saved — the Hermes push panel now polls every 120 seconds\./)
    expect(board.refresh_seconds).toBe(120)

    const configGets = () =>
      fetchMock.mock.calls.filter(([u]) => String(u) === '/api/config').length

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('spinbutton', { name: /refresh interval/i })).toBeNull()
    const getsBeforeReopen = configGets()

    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Updates' }))
    const reopened = screen.getByRole('spinbutton', { name: /refresh interval/i }) as HTMLInputElement
    await waitFor(() => expect(reopened.value).toBe('120'))
    // Reopening the settings screen re-fetches the config.
    expect(configGets()).toBeGreaterThan(getsBeforeReopen)
  })
})

describe('app: calendar modes (settings.calendar_mode)', () => {
  it('renders the calendar as a chronological schedule summary when the board says so', async () => {
    const { calls } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'schedule',
    })
    render(<App />)

    // The events effect refetches once the calendar list lands (the
    // calendars state is a fetch dependency), briefly re-showing the
    // loading note; wait for the settled list — both events fetches
    // recorded and the rows rendered — before asserting.
    await vi.waitFor(() => {
      expect(calls.filter((c) => c.startsWith('GET /api/events'))).toHaveLength(2)
      expect(screen.getByText('Dinner with Sam')).toBeTruthy()
    })

    // The month grid is not rendered; the schedule list is.
    const standup = screen.getByText('Standup')
    expect(standup.closest('.schedule-item')).toBeTruthy()
    expect(document.querySelector('.month-grid')).toBeNull()
    expect(screen.getByText('Dinner with Sam').closest('.schedule-item')).toBeTruthy()
    // Time, title, and calendar label are all present per row.
    const row = standup.closest('.schedule-item') as HTMLElement
    expect(row.querySelector('.schedule-time')?.textContent).toBeTruthy()
    expect(row.querySelector('.schedule-cal')?.textContent).toBe('Family')
  })

  it('drops an all-day event that ended exactly at the start of today from the schedule summary', async () => {
    const t = new Date()
    const d = t.getDate()
    // Zero-padded local calendar date, offset by days from today.
    const dateStr = (offset: number) => {
      const x = new Date(t.getFullYear(), t.getMonth(), d + offset)
      return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`
    }
    const at = (day: number, h = 0, min = 0) =>
      new Date(t.getFullYear(), t.getMonth(), day, h, min).toISOString()
    // An all-day event whose last real day was yesterday: the backend
    // emits Google's exclusive end (last day + 1 = local today) as a UTC
    // midnight, so the comparison must be in date space — under a
    // browser timezone east of UTC a wall-clock compare would keep it.
    stubBoardServer(
      { order: ['calendar', 'chat', 'home', 'push'], hidden: [], calendar_mode: 'schedule' },
      [
        {
          calendar_id: 'c1@example.com',
          calendar_label: 'Family',
          id: 'dayoff',
          summary: 'Day off',
          start: `${dateStr(-1)}T00:00:00Z`,
          end: `${dateStr(0)}T00:00:00Z`,
          all_day: true,
        },
        {
          calendar_id: 'c1@example.com',
          calendar_label: 'Family',
          id: 'standup',
          summary: 'Standup',
          start: at(d, 9, 0),
          end: at(d, 10, 0),
          all_day: false,
        },
      ],
    )
    render(<App />)

    // The future timed event stays in the upcoming list; the all-day
    // event that ended at the start of today drops out.
    const standup = await screen.findByText('Standup')
    expect(standup.closest('.schedule-item')).toBeTruthy()
    expect(screen.queryByText('Day off')).toBeNull()
  })

  it('renders the month grid by default when the board sets no calendar mode', async () => {
    stubBoardServer(undefined) // no views section at all
    render(<App />)
    await waitFor(() => expect(document.querySelector('.month-grid')).toBeTruthy())
    expect(document.querySelector('.schedule')).toBeNull()
  })
})

describe('app: calendar today highlight', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('highlights only today’s date cell in the month grid', async () => {
    // Mocked date keeps the assertion deterministic (Sun 2025-06-15, so
    // the grid shows June 2025 and today is unambiguous).
    vi.useFakeTimers().setSystemTime(new Date(2025, 5, 15))
    stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)

    await vi.waitFor(() => expect(document.querySelector('.month-grid')).toBeTruthy())

    const cells = [...document.querySelectorAll('.month-grid .cell')]
    expect(cells).toHaveLength(42)
    const today = cells.filter((c) => c.classList.contains('today'))
    // Exactly one cell carries the highlight, and it is the 15th.
    expect(today).toHaveLength(1)
    expect(today[0].querySelector('.cell-day')?.textContent).toBe('15')
    // The highlight is additive: event marks still render on the cell.
    expect(today[0].textContent).toContain('Standup')
    // Day selection still works on the highlighted cell.
    fireEvent.click(today[0])
    expect(today[0].classList.contains('sel')).toBe(true)
  })
})

// --- Shared helpers for the placement/drag/HA tests ----------------------

/** Stub the board's box + computed gap so the drag math has real numbers:
 *  a 12x12 grid of 100px columns and 150px rows with a 16px gap. */
function stubBoardBox(board?: HTMLElement) {
  const el = board ?? boardEl()
  const origRect = el.getBoundingClientRect.bind(el)
  el.getBoundingClientRect = () =>
    ({
      left: 0,
      top: 0,
      width: 1376, // 12 * 100 + 11 * 16
      height: 1976, // 12 * 150 + 11 * 16
      x: 0,
      y: 0,
      right: 1376,
      bottom: 1976,
      toJSON: () => null,
    }) as DOMRect
  const origGCS = window.getComputedStyle
  window.getComputedStyle = (node: Element) =>
    new Proxy(origGCS(node), {
      get: (t, k) =>
        k === 'columnGap' ? '16px' : (t as unknown as Record<PropertyKey, unknown>)[k],
    }) as unknown as CSSStyleDeclaration
  return () => {
    el.getBoundingClientRect = origRect
    window.getComputedStyle = origGCS
  }
}

/** One column/row pitch in the stubbed box. */
const PITCH_X = 116 // 100 + 16
const PITCH_Y = 166 // 150 + 16

/** jsdom's fireEvent.pointer* builds bare Events without clientX/Y;
 *  dispatch a real MouseEvent (the handlers only read clientX/Y/bubbles). */
function firePointer(el: Element | Window, type: string, x: number, y: number) {
  el.dispatchEvent(
    new window.MouseEvent(type, {
      clientX: x,
      clientY: y,
      button: 0,
      buttons: 1,
      bubbles: true,
      cancelable: true,
    }),
  )
}

const geometryOf = (board: { views?: BoardViews | null }) =>
  ((board.views?.geometry ?? {}) as Record<string, unknown>)

// The placement test board: home is pinned at (1,2,3,1); on the default
// 12-column grid the rest flows around it (calendar (1,1,6,1), chat
// (7,1,6,1), push (7,2,6,1)).
const placedViews = {
  order: ['calendar', 'chat', 'home', 'push'],
  hidden: [],
  calendar_mode: 'month' as const,
  sizes: { calendar: 'm', chat: 'm', home: 'm', push: 'm' } as Partial<Record<ViewId, TileSize>>,
  geometry: { home: { x: 1, y: 2, w: 3, h: 1 } },
}

describe('app: tile placement (edit mode)', () => {

  it('drags a placed tile to a free cell and persists the placement', async () => {
    const { board, calls } = stubBoardServer(placedViews)
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const restoreBox = stubBoardBox()

    const home = document.querySelector('[data-win="home"]') as HTMLElement
    const grip = home.querySelector('.tile-grip') as HTMLElement
    // Grab in row 2 and drag one row down: the cell (1,3) is free.
    await act(async () => {
      firePointer(grip, 'pointerdown', 10, 200)
    })
    await act(async () => {
      firePointer(window, 'pointermove', 10, 200 + PITCH_Y)
    })

    // The live preview snaps to the target cell.
    expect(home.className).toContain('dragging')
    expect(home.className).not.toContain('preview-invalid')
    expect(home.style.gridColumn).toBe('1 / span 3')
    expect(home.style.gridRow).toBe('3 / span 1')

    await act(async () => {
      firePointer(window, 'pointerup', 10, 200 + PITCH_Y)
    })
    restoreBox()

    await waitFor(() => expect(geometryOf(board).home).toEqual({ x: 1, y: 3, w: 3, h: 1 }))
    const put = calls.filter((c) => c === 'PUT /api/config/views')
    expect(put.length).toBe(1)
    // The board re-renders from the saved placement.
    await waitFor(() => expect(home.style.gridRow).toBe('3 / span 1'))
  })

  it('refuses a drop that overlaps a neighbour and snaps back', async () => {
    const { board, calls } = stubBoardServer(placedViews)
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const restoreBox = stubBoardBox()

    const cal = document.querySelector('[data-win="calendar"]') as HTMLElement
    const grip = cal.querySelector('.tile-grip') as HTMLElement
    // Drag calendar one column right: (2,1,6,1) overlaps chat at (7,1).
    await act(async () => {
      firePointer(grip, 'pointerdown', 10, 10)
    })
    await act(async () => {
      firePointer(window, 'pointermove', 10 + PITCH_X, 10)
    })

    expect(cal.className).toContain('dragging')
    expect(cal.className).toContain('preview-invalid')
    expect(cal.style.gridColumn).toBe('2 / span 6')

    await act(async () => {
      firePointer(window, 'pointerup', 10 + PITCH_X, 10)
    })
    restoreBox()

    // No save went out; the tile snaps back to its effective placement
    // (the layout math is the single source of truth, so the snap-back
    // renders the auto-flowed cell, not an unspanned tile).
    expect(calls.filter((c) => c === 'PUT /api/config/views')).toHaveLength(0)
    expect(cal.className).not.toContain('preview-invalid')
    expect(cal.style.gridColumn).toBe('1 / span 6')
    expect(cal.style.gridRow).toBe('1 / span 1')
    expect(geometryOf(board).calendar).toBeUndefined()
  })

  it('drags an auto-placed tile from its effective cell (not (1,1))', async () => {
    const { board } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const restoreBox = stubBoardBox()

    const push = document.querySelector('[data-win="push"]') as HTMLElement
    const grip = push.querySelector('.tile-grip') as HTMLElement
    // push auto-places at (7,2) in the default 2x2 board: grab in row 2
    // and drag one row down — (7,3) is free.
    await act(async () => {
      firePointer(grip, 'pointerdown', 10, 200)
    })
    await act(async () => {
      firePointer(window, 'pointermove', 10, 200 + PITCH_Y)
    })

    // The preview snaps from the effective cell, not (1,1).
    expect(push.style.gridColumn).toBe('7 / span 6')
    expect(push.style.gridRow).toBe('3 / span 1')
    expect(push.className).not.toContain('preview-invalid')

    await act(async () => {
      firePointer(window, 'pointerup', 10, 200 + PITCH_Y)
    })
    restoreBox()

    await waitFor(() => expect(geometryOf(board).push).toEqual({ x: 7, y: 3, w: 6, h: 1 }))
  })

  it('leaves the board file untouched when a tile is dropped on its own cell', async () => {
    const { board, calls } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const restoreBox = stubBoardBox()

    const push = document.querySelector('[data-win="push"]') as HTMLElement
    const grip = push.querySelector('.tile-grip') as HTMLElement
    // push auto-places at (7,2): a sub-pitch jitter past the 4px grab
    // threshold rounds back to its own cell, which changes nothing.
    await act(async () => {
      firePointer(grip, 'pointerdown', 10, 200)
    })
    await act(async () => {
      firePointer(window, 'pointermove', 10, 200 + 8)
    })
    await act(async () => {
      firePointer(window, 'pointerup', 10, 200 + 8)
    })
    restoreBox()

    expect(calls.filter((c) => c === 'PUT /api/config/views')).toHaveLength(0)
    expect(geometryOf(board).push).toBeUndefined()
  })

  it('discards an in-flight drag when edit mode is left mid-drag', async () => {
    const { board, calls } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const restoreBox = stubBoardBox()

    const push = document.querySelector('[data-win="push"]') as HTMLElement
    const grip = push.querySelector('.tile-grip') as HTMLElement
    // Grab push and drag one row down: the ghost previews (4,3).
    await act(async () => {
      firePointer(grip, 'pointerdown', 10, 200)
    })
    await act(async () => {
      firePointer(window, 'pointermove', 10, 200 + PITCH_Y)
    })
    expect(push.style.gridRow).toBe('3 / span 1')
    // A second touch leaves edit mode while the drag is in flight.
    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    // The ghost is cleared together with the pointer listeners: push
    // re-renders its effective cell (7,2,6,1), not the drag preview.
    expect(push.style.gridRow).toBe('2 / span 1')
    expect(push.className).not.toContain('dragging')
    // The first finger releases after the listeners are gone: nothing commits.
    await act(async () => {
      firePointer(window, 'pointerup', 10, 200 + PITCH_Y)
    })
    // Re-entering edit mode and releasing a pointer commits no stale ghost.
    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    await act(async () => {
      firePointer(window, 'pointerup', 10, 200 + PITCH_Y)
    })
    restoreBox()

    expect(calls.filter((c) => c === 'PUT /api/config/views')).toHaveLength(0)
    expect(geometryOf(board).push).toBeUndefined()
  })

  it('grows an auto-placed tile from its effective cell with the height field', async () => {
    const { board } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    // push auto-places at (7,2): growing it pins (7,2,6,2) — not
    // (1,2,6,2), which would overlap the home tile on row 2.
    const push = within(screen.getByRole('group', { name: 'Hermes push tile controls' }))
    const h = push.getByRole('spinbutton', { name: 'height of Hermes push in grid units' }) as HTMLInputElement
    fireEvent.change(h, { target: { value: '2' } })
    fireEvent.keyDown(h, { key: 'Enter' })
    await waitFor(() => expect(geometryOf(board).push).toEqual({ x: 7, y: 2, w: 6, h: 2 }))
  })

  it('commits a numeric width for a placed tile, refusing the overlaps', async () => {
    const { board, calls } = stubBoardServer(placedViews)
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    // Home is fixed at (1,2,3,1) and push auto-places at (7,2,6,1):
    // growing home's width to 12 would cover push — the commit is
    // refused and the placement is kept.
    const home = within(screen.getByRole('group', { name: 'Home tile controls' }))
    const w = home.getByRole('spinbutton', { name: 'width of Home in grid units' }) as HTMLInputElement
    fireEvent.change(w, { target: { value: '12' } })
    fireEvent.keyDown(w, { key: 'Enter' })
    expect(geometryOf(board).home).toEqual({ x: 1, y: 2, w: 3, h: 1 })

    // A width that fits the row commits (the origin column is kept).
    fireEvent.change(w, { target: { value: '2' } })
    fireEvent.keyDown(w, { key: 'Enter' })
    await waitFor(() => expect(geometryOf(board).home).toEqual({ x: 1, y: 2, w: 2, h: 1 }))
    expect(calls.filter((c) => c === 'PUT /api/config/views').length).toBe(1)
  })

  it('renders no placement affordances outside edit mode', () => {
    stubBoardServer(placedViews)
    render(<App />)
    expect(document.querySelector('.tile-grip')).toBeNull()
    expect(document.querySelector('.tile-size-input')).toBeNull()
    expect(document.querySelector('.tile-resize')).toBeNull()
  })
})

// A board on a custom tile grid: 8 columns x 5 rows.
const gridViews = {
  order: ['calendar', 'chat', 'home', 'push'],
  hidden: [],
  calendar_mode: 'month' as const,
  sizes: { calendar: 'm', chat: 'm', home: 'm', push: 'm' } as Partial<Record<ViewId, TileSize>>,
}

describe('app: board grid dimensions', () => {
  it('splits the default board into 12 x 12 units', () => {
    stubBoardServer(undefined)
    render(<App />)
    const board = boardEl()
    expect(board.style.getPropertyValue('--grid-cols')).toBe('12')
    expect(board.style.getPropertyValue('--grid-rows')).toBe('12')
  })

  it('adopts a stored grid from the board config and lays out on it', async () => {
    stubBoardServer(gridViews, undefined, [], { cols: 8, rows: 5 })
    render(<App />)

    // The board carries the effective grid as custom properties (the
    // CSS splits the width/height into that many equal units).
    await waitFor(() => {
      expect(boardEl().style.getPropertyValue('--grid-cols')).toBe('8')
    })
    expect(boardEl().style.getPropertyValue('--grid-rows')).toBe('5')

    // On an 8-column grid m = half = 4 units: the default board is a 2x2
    // of 4-unit tiles at (1,1), (5,1), (1,2), (5,2).
    const cal = document.querySelector('[data-win="calendar"]') as HTMLElement
    await waitFor(() => expect(cal.style.gridColumn).toBe('1 / span 4'))
    expect(cal.style.gridRow).toBe('1 / span 1')
    const chat = document.querySelector('[data-win="chat"]') as HTMLElement
    expect(chat.style.gridColumn).toBe('5 / span 4')
    const push = document.querySelector('[data-win="push"]') as HTMLElement
    expect(push.style.gridColumn).toBe('5 / span 4')
    expect(push.style.gridRow).toBe('2 / span 1')
  })

  it('repairs a stored grid out of the usable range to the per-axis default', async () => {
    stubBoardServer(gridViews, undefined, [], { cols: 99, rows: 5 })
    render(<App />)
    // cols 99 is outside 1-24: the columns fall back to the 12 default,
    // rows keep their stored 5.
    await waitFor(() => {
      expect(boardEl().style.getPropertyValue('--grid-cols')).toBe('12')
    })
    expect(boardEl().style.getPropertyValue('--grid-rows')).toBe('5')
  })

  it('clamps drag placement to the configured grid edges', async () => {
    // One visible tile on a 12x12 grid: dragging it far past the bottom
    // edge previews the last row (clamped), and the drop persists there.
    const { board } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['chat', 'home', 'push'],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toEqual(['calendar']))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const restoreBox = stubBoardBox()
    const cal = document.querySelector('[data-win="calendar"]') as HTMLElement
    const grip = cal.querySelector('.tile-grip') as HTMLElement
    await act(async () => {
      firePointer(grip, 'pointerdown', 10, 10)
    })
    // 40 rows down: far past the 12-row grid; the preview clamps to row 12.
    await act(async () => {
      firePointer(window, 'pointermove', 10, 10 + 40 * PITCH_Y)
    })
    expect(cal.style.gridRow).toBe('12 / span 1')
    expect(cal.className).not.toContain('preview-invalid')

    await act(async () => {
      firePointer(window, 'pointerup', 10, 10 + 40 * PITCH_Y)
    })
    restoreBox()
    await waitFor(() => expect(geometryOf(board).calendar).toEqual({ x: 1, y: 12, w: 6, h: 1 }))
  })

  it('snaps a drag by the stretched board\'s row pitch when the layout overflows the grid rows', async () => {
    // The grid is 2 rows tall, but three full-width tiles flow into three
    // rendered rows (the grid stretches with auto rows): the drag pitch
    // must follow the rendered rows, not the configured grid's rows.
    const { calls } = stubBoardServer(
      {
        order: ['calendar', 'chat', 'home', 'push'],
        hidden: ['push'],
        calendar_mode: 'month',
        sizes: { calendar: 'xl', chat: 'xl', home: 'xl', push: 'xl' },
      },
      undefined,
      [],
      { cols: 12, rows: 2 },
    )
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(3))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const restoreBox = stubBoardBox()

    const cal = document.querySelector('[data-win="calendar"]') as HTMLElement
    const grip = cal.querySelector('.tile-grip') as HTMLElement
    // Stubbed box: the three rendered rows are (1976 - 2 * 16) / 3 = 648px
    // each, so one row's pitch is 664px. A 450px drag (0.7 of a rendered
    // row) must snap one row down; measured against the configured two
    // rows (996px pitch) it rounds to zero and the tile does not move.
    await act(async () => {
      firePointer(grip, 'pointerdown', 10, 200)
    })
    await act(async () => {
      firePointer(window, 'pointermove', 10, 200 + 450)
    })

    // The preview snaps to row 2 — occupied by chat, so it is flagged as
    // refused (placements stay bounded by the configured grid's rows).
    expect(cal.className).toContain('dragging')
    expect(cal.style.gridRow).toBe('2 / span 1')
    expect(cal.className).toContain('preview-invalid')

    await act(async () => {
      firePointer(window, 'pointerup', 10, 200 + 450)
    })
    restoreBox()

    // The refused drop snaps back without a layout write.
    await waitFor(() => expect(cal.style.gridRow).toBe('1 / span 1'))
    expect(calls).not.toContain('PUT /api/config/views')
  })
})

describe('app: Home Assistant board tiles', () => {
  const haViews = {
    order: ['calendar', 'chat', 'home', 'push', 'ha-lamp', 'ha-temp'],
    hidden: [],
    calendar_mode: 'month' as const,
    sizes: { calendar: 'm', chat: 'm', home: 'm', push: 'm', 'ha-lamp': 's', 'ha-temp': 's' } as Partial<Record<string, TileSize>>,
    geometry: { 'ha-lamp': { x: 1, y: 3, w: 2, h: 1 } },
    ha_tiles: [
      { id: 'ha-lamp', entity: 'light.living_room', kind: 'button', label: 'Lamp' },
      { id: 'ha-temp', entity: 'sensor.living_room_temperature', kind: 'visual', label: 'Temp' },
    ],
  }
  const haEntities = [
    {
      entity_id: 'light.living_room',
      label: 'Living room',
      friendly: 'Living room light',
      state: 'on',
      attributes: {},
      last_updated: '2026-01-01T00:00:00Z',
      last_changed: '2026-01-01T00:00:00Z',
    },
    {
      entity_id: 'sensor.living_room_temperature',
      label: 'Temperature',
      friendly: 'Living room temperature',
      state: '21.5',
      attributes: {},
      last_updated: '2026-01-01T00:00:00Z',
      last_changed: '2026-01-01T00:00:00Z',
    },
  ]

  it('renders HA tiles with their polled state and fires the button action', async () => {
    const { haServiceCalls } = stubBoardServer(haViews, upcomingEvents(), haEntities)
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(6))

    const lamp = document.querySelector('[data-win="ha-lamp"]') as HTMLElement
    expect(lamp.querySelector('.ha-card')).toBeTruthy()
    // The button tile's action label follows the entity state (on → "Turn off").
    const turnOff = await screen.findByRole('button', { name: 'Turn off' })
    expect(within(lamp).getByText('Lamp')).toBeTruthy()
    fireEvent.click(turnOff)

    await waitFor(() => expect(haServiceCalls).toEqual([
      { entity_id: 'light.living_room', action: 'toggle' },
    ]))
    // The tile re-polls after the action and shows the new state.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Turn on' })).toBeTruthy()
    })

    // The visual tile shows the entity's state and id.
    const temp = document.querySelector('[data-win="ha-temp"]') as HTMLElement
    expect(temp.querySelector('.ha-state')?.textContent).toBe('21.5')
    expect(within(temp).getByText('sensor.living_room_temperature')).toBeTruthy()
  })

  it('reports the not-configured state instead of failing when HA is off', async () => {
    const { fetchMock } = stubBoardServer(haViews, upcomingEvents(), [])
    vi.stubGlobal(
      'fetch',
      async (input: unknown, init?: RequestInit) => {
        if (String(input) === '/api/ha/entities') {
          return new Response(
            JSON.stringify({
              error: 'homeassistant_not_configured',
              detail: 'set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN',
            }),
            { status: 503, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return fetchMock(input, init)
      },
    )
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(6))

    const lamp = document.querySelector('[data-win="ha-lamp"]') as HTMLElement
    await waitFor(() => {
      expect(lamp.textContent).toContain('Home Assistant is not configured')
    })
    const action = lamp.querySelector('.ha-action') as HTMLButtonElement | null
    expect(action?.disabled).toBe(true)
  })

  it('honors a stored HA tile placement and order on the board', async () => {
    stubBoardServer(haViews, upcomingEvents(), haEntities)
    render(<App />)
    await waitFor(() => expect(tileWins()).toEqual([
      'calendar',
      'chat',
      'home',
      'push',
      'ha-lamp',
      'ha-temp',
    ]))
    const lamp = document.querySelector('[data-win="ha-lamp"]') as HTMLElement
    expect(lamp.style.gridColumn).toBe('1 / span 2')
    expect(lamp.style.gridRow).toBe('3 / span 1')
  })
})

describe('app: narrow fallback with stored placements', () => {
  it('stacks the tiles (stored placements suspended) on a narrow viewport', async () => {
    stubNarrow(true)
    stubBoardServer({
      order: ['push', 'home', 'chat', 'calendar'],
      hidden: [],
      calendar_mode: 'month',
      sizes: { calendar: 'l', chat: 'xl', home: 'm', push: 's' } as Partial<Record<ViewId, TileSize>>,
      geometry: { home: { x: 1, y: 2, w: 3, h: 1 } },
    })
    render(<App />)
    expect(boardEl().hasAttribute('data-narrow')).toBe(true)
    // Board order is kept in the stack; the inline placement spans are
    // dropped so the CSS !important overrides win.
    const home = document.querySelector('[data-win="home"]') as HTMLElement
    expect(home.style.gridColumn).toBe('')
    expect(home.style.gridRow).toBe('')
  })
})
