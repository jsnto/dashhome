import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { ApiBoard, ApiEvent, BoardViews, CalendarMode, TileSize, ViewId } from './types'

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
  order?: ViewId[]
  hidden?: ViewId[]
  calendar_mode?: CalendarMode
  sizes?: Partial<Record<ViewId, TileSize>>
}

/** Stand-in backend that serves a board with an editable views section
 *  (order/hidden/calendar_mode/sizes, the same partial-update semantics
 *  the backend has: omitted fields keep their value, sizes merges per
 *  tile). */
function stubBoardServer(
  initialViews: RawViews | undefined,
  events: ApiEvent[] = upcomingEvents(),
) {
  const board = {
    ha_entities: [],
    ha_labels: {},
    calendars: [],
    refresh_seconds: 30,
    views: initialViews ?? null,
  } as unknown as ApiBoard
  const calls: string[] = []
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
      const next: BoardViews = {
        order: (body.order as ViewId[]) ?? cur.order,
        hidden: body.hidden !== undefined ? (body.hidden as ViewId[]) : cur.hidden,
        calendar_mode: (body.calendar_mode as CalendarMode) ?? cur.calendar_mode,
        sizes,
      }
      board.views = next
      return json({ board })
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
  return { fetchMock, board, calls }
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
    // The size steps land on the board as grid spans (data-size), which
    // the CSS maps to the column spans (xl = the full board width, s a
    // third, l two thirds); the grid reflows around them.
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

  it('toggling edit mode shows a size control on every visible tile', async () => {
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

    // One size control per visible tile (the hidden tile gets none), each
    // with the four size steps, the current one pressed.
    const groups = screen.getAllByRole('group', { name: /tile size/ })
    expect(groups).toHaveLength(3)
    const cal = within(screen.getByRole('group', { name: 'Calendar tile size' }))
    for (const step of ['S', 'M', 'L', 'XL']) {
      expect(cal.getByRole('button', { name: step })).toBeTruthy()
    }
    expect(cal.getByRole('button', { name: 'M' }).getAttribute('aria-pressed')).toBe('true')
    expect(within(screen.getByRole('group', { name: 'Home tile size' }))).toBeTruthy()
  })

  it('a size change persists to the board file and round-trips to the tile', async () => {
    const { fetchMock, board, calls } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const cal = within(screen.getByRole('group', { name: 'Calendar tile size' }))
    fireEvent.click(cal.getByRole('button', { name: 'XL' }))

    // The change PUTs to the views endpoint with just this tile's size.
    await waitFor(() => {
      expect(calls).toContain('PUT /api/config/views')
    })
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT') as [
      string,
      RequestInit,
    ]
    expect(put[0]).toBe('/api/config/views')
    expect(JSON.parse(String(put[1].body))).toEqual({ sizes: { calendar: 'xl' } })

    // The board file holds the size…
    expect(board.views?.sizes?.calendar).toBe('xl')
    expect(board.views?.sizes?.chat).toBe('m')

    // …and the answer adopts it back onto the tile.
    await waitFor(() => expect(tileSize('calendar')).toBe('xl'))
    expect(tileSize('chat')).toBe('m')
  })

  it('reports a failed size save and keeps the tile at its current size', async () => {
    const { fetchMock } = stubBoardServer(undefined)
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
    await waitFor(() => expect(tileWins()).toHaveLength(4))

    fireEvent.click(screen.getByRole('button', { name: 'Edit tiles' }))
    const cal = within(screen.getByRole('group', { name: 'Calendar tile size' }))
    fireEvent.click(cal.getByRole('button', { name: 'S' }))

    await screen.findByText(/board_file_not_writable — board file not writable: denied/)
    // The failed save did not move the tile's size.
    expect(tileSize('calendar')).toBe('m')
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
