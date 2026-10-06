import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { ApiBoard, ApiEvent, BoardViews, CalendarMode, ViewId } from './types'

/** In-memory stand-in for the backend: the board persists across requests. */
function stubServer(initialSeconds: number) {
  const board: ApiBoard = {
    ha_entities: [],
    ha_labels: {},
    calendars: [],
    refresh_seconds: initialSeconds,
  }
  const integrations = {
    homeassistant: false,
    google: { configured: false, login_required: false, login_url: '' },
    hermes_chat: false,
    hermes_push: false,
  }
  const json = (body: Record<string, unknown>, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    if (url === '/api/config') {
      return json({ board, integrations })
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

describe('app: section layout', () => {
  it('puts Calendar and Hermes chat in the main column, Home and Hermes push in the side column', () => {
    vi.stubGlobal('fetch', stubServer(30).fetchMock)
    render(<App />)

    const main = document.querySelector('.col-main') as HTMLElement
    const side = document.querySelector('.col-side') as HTMLElement
    expect(main).toBeTruthy()
    expect(side).toBeTruthy()

    const headingsIn = (el: HTMLElement) => [...el.querySelectorAll('h2')].map((h) => h.textContent)
    // The mobile single-column fallback stacks the columns in this same
    // DOM order, so it presents the same relative order.
    expect(headingsIn(main)).toEqual(['Calendar', 'Hermes'])
    expect(headingsIn(side)).toEqual(['Home', 'Hermes push'])
  })
})

/** jsdom has no PointerEvent: dispatch a plain bubbling event that carries
 *  the pointer coordinates the drag handlers read. React treats pointermove
 *  as a continuous event, so callers must await act() to flush the render. */
function firePointer(node: Element, type: string, x: number, y: number) {
  const ev = new window.Event(type, { bubbles: true, cancelable: true })
  Object.defineProperty(ev, 'clientX', { value: x })
  Object.defineProperty(ev, 'clientY', { value: y })
  Object.defineProperty(ev, 'pointerId', { value: 1 })
  node.dispatchEvent(ev)
}

async function pointer(node: Element, type: string, x: number, y: number) {
  await act(async () => firePointer(node, type, x, y))
}

function layoutEl() {
  return document.querySelector('.layout') as HTMLElement
}

function rectSpy(width: number, height: number) {
  return vi
    .spyOn(HTMLElement.prototype, 'getBoundingClientRect')
    .mockReturnValue({
      left: 0,
      top: 0,
      right: width,
      bottom: height,
      width,
      height,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    } as DOMRect)
}

describe('app: resizable windows', () => {
  it('renders a split handle between the columns and between the stacked windows', () => {
    vi.stubGlobal('fetch', stubServer(30).fetchMock)
    render(<App />)

    expect(screen.getAllByRole('separator')).toHaveLength(3)
    expect(screen.getByRole('separator', { name: 'Resize columns' })).toBeTruthy()
    expect(screen.getByRole('separator', { name: 'Resize Calendar and Hermes' })).toBeTruthy()
    expect(screen.getByRole('separator', { name: 'Resize Home and Hermes push' })).toBeTruthy()
  })

  it('adjusts the column split live while dragging and keeps it on release', async () => {
    vi.stubGlobal('fetch', stubServer(30).fetchMock)
    rectSpy(1000, 600)
    render(<App />)

    const handle = screen.getByRole('separator', { name: 'Resize columns' })
    const col = () => parseFloat(layoutEl().style.getPropertyValue('--col'))
    expect(col()).toBeCloseTo(7 / 12, 6)

    // Drag the handle 100px right in a 1000px-wide layout: +10% to the left
    // column, applied live on every move.
    await pointer(handle, 'pointerdown', 0, 0)
    await pointer(handle, 'pointermove', 50, 0)
    expect(col()).toBeCloseTo(7 / 12 + 0.05, 6)
    await pointer(handle, 'pointermove', 100, 0)
    expect(col()).toBeCloseTo(7 / 12 + 0.1, 6)
    await pointer(handle, 'pointerup', 100, 0)

    // The new proportion persists on release (no snap-back) and the right
    // column takes the remainder.
    expect(col()).toBeCloseTo(7 / 12 + 0.1, 6)
    expect(parseFloat(layoutEl().style.getPropertyValue('--col-r'))).toBeCloseTo(
      5 / 12 - 0.1,
      6,
    )
  })

  it('adjusts the stacked windows inside a column and clamps at the limits', async () => {
    vi.stubGlobal('fetch', stubServer(30).fetchMock)
    rectSpy(1000, 600)
    render(<App />)

    const handle = screen.getByRole('separator', { name: 'Resize Calendar and Hermes' })
    const top = () => parseFloat(layoutEl().style.getPropertyValue('--main'))
    expect(top()).toBeCloseTo(0.5, 6)

    // Drag 300px down in a 600px-tall column: +50% would exceed the clamp.
    await pointer(handle, 'pointerdown', 0, 0)
    await pointer(handle, 'pointermove', 0, 300)
    await pointer(handle, 'pointerup', 0, 300)
    expect(top()).toBeCloseTo(0.9, 6)

    // Drag far up: the bottom window keeps at least its minimum share.
    await pointer(handle, 'pointerdown', 0, 0)
    await pointer(handle, 'pointermove', 0, -3000)
    await pointer(handle, 'pointerup', 0, -3000)
    expect(top()).toBeCloseTo(0.1, 6)
    expect(parseFloat(layoutEl().style.getPropertyValue('--main-r'))).toBeCloseTo(0.9, 6)
  })

  it('leaves the splits untouched when a click (not a drag) lands on a handle', () => {
    vi.stubGlobal('fetch', stubServer(30).fetchMock)
    rectSpy(1000, 600)
    render(<App />)

    const handle = screen.getByRole('separator', { name: 'Resize columns' })
    fireEvent.click(handle)
    expect(parseFloat(layoutEl().style.getPropertyValue('--col'))).toBeCloseTo(7 / 12, 6)
  })
})

describe('app: window maximize', () => {
  it('clicking a window title maximizes it; clicking it again restores the split', () => {
    vi.stubGlobal('fetch', stubServer(30).fetchMock)
    render(<App />)

    const home = screen.getByRole('heading', { name: 'Home' })
    expect(layoutEl().getAttribute('data-max')).toBeNull()

    fireEvent.click(home)
    expect(layoutEl().getAttribute('data-max')).toBe('home')
    // The maximized window's title row (and its actions, when present) stays
    // in the DOM while maximized, so the user can click the title again.
    expect(document.querySelector('[data-win="home"] .card-head h2')).toBeTruthy()

    fireEvent.click(screen.getByRole('heading', { name: 'Home' }))
    expect(layoutEl().getAttribute('data-max')).toBeNull()
    // The prior proportions come back: nothing was reset in the meantime.
    expect(parseFloat(layoutEl().style.getPropertyValue('--col'))).toBeCloseTo(7 / 12, 6)
    expect(parseFloat(layoutEl().style.getPropertyValue('--main'))).toBeCloseTo(0.5, 6)
  })

  it('each window title toggles its own window; other clicks do not toggle', () => {
    vi.stubGlobal('fetch', stubServer(30).fetchMock)
    render(<App />)

    fireEvent.click(screen.getByRole('heading', { name: 'Calendar' }))
    expect(layoutEl().getAttribute('data-max')).toBe('calendar')

    // Clicking the window body (not the title) does not change the state.
    fireEvent.click(document.querySelector('[data-win="calendar"] .card-scroll')!)
    expect(layoutEl().getAttribute('data-max')).toBe('calendar')

    // A different title switches which window is maximized.
    fireEvent.click(screen.getByRole('heading', { name: 'Hermes push' }))
    expect(layoutEl().getAttribute('data-max')).toBe('push')

    fireEvent.click(screen.getByRole('heading', { name: 'Hermes push' }))
    expect(layoutEl().getAttribute('data-max')).toBeNull()
  })

  it('a content heading inside a window body does not toggle maximize', async () => {
    const { fetchMock } = stubServer(30)
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

    // A markdown h2 rendered in the push window body: inside the push
    // window but outside its .card-head title row.
    const content = await screen.findByRole('heading', { name: 'Heading' })
    expect(content.closest('.card-head')).toBeNull()
    expect(content.closest('[data-win="push"]')).toBeTruthy()

    fireEvent.click(content)
    expect(layoutEl().getAttribute('data-max')).toBeNull()

    // The real title of the same window still toggles.
    fireEvent.click(screen.getByRole('heading', { name: 'Hermes push' }))
    expect(layoutEl().getAttribute('data-max')).toBe('push')
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

// --- board views layout (the board file's views section) -------------------

const integrationsOff = {
  homeassistant: false,
  google: { configured: false, login_required: false, login_url: '' },
  hermes_chat: false,
  hermes_push: false,
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

/** Stand-in backend that serves a board with an editable views section. */
function stubBoardServer(
  initialViews: ApiBoard['views'] | undefined,
  events: ApiEvent[] = upcomingEvents(),
) {
  const board: ApiBoard = {
    ha_entities: [],
    ha_labels: {},
    calendars: [],
    refresh_seconds: 30,
    views: initialViews,
  }
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
      }
      const next: BoardViews = {
        order: (body.order as ViewId[]) ?? cur.order,
        hidden: body.hidden !== undefined ? (body.hidden as ViewId[]) : cur.hidden,
        calendar_mode: (body.calendar_mode as CalendarMode) ?? cur.calendar_mode,
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

function headingsIn(el: HTMLElement | null): string[] {
  if (!el) return []
  return [...el.querySelectorAll('h2')].map((h) => h.textContent ?? '')
}

describe('app: board views layout (settings.views)', () => {
  it('renders the visible views in the configured order, mapped to the slots in reading order', async () => {
    stubBoardServer({
      order: ['push', 'home', 'chat', 'calendar'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)

    const main = () => document.querySelector('.col-main') as HTMLElement
    const side = () => document.querySelector('.col-side') as HTMLElement
    // Wait for the config fetch: the layout adopts the stored order.
    await waitFor(() => expect(headingsIn(main())).toEqual(['Hermes push', 'Home']))
    expect(headingsIn(side())).toEqual(['Hermes', 'Calendar'])

    // Each window sits in its slot; the slot — not the view — owns the
    // drag-resized proportion.
    expect(main().querySelector('[data-win="push"]')!.getAttribute('data-slot')).toBe('main-top')
    expect(main().querySelector('[data-win="home"]')!.getAttribute('data-slot')).toBe('main-bottom')
    expect(side().querySelector('[data-win="chat"]')!.getAttribute('data-slot')).toBe('side-top')
    expect(side().querySelector('[data-win="calendar"]')!.getAttribute('data-slot')).toBe('side-bottom')

    // The resize handles name their current neighbours.
    await screen.findByRole('separator', { name: 'Resize Hermes push and Home' })
    expect(screen.getByRole('separator', { name: 'Resize Hermes and Calendar' })).toBeTruthy()
    expect(screen.getAllByRole('separator')).toHaveLength(3)
  })

  it('does not render or poll a hidden view; the remaining views fill the slots', async () => {
    const { calls } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['home', 'push'],
      calendar_mode: 'month',
    })
    render(<App />)

    // Wait for the config fetch: the hidden views disappear.
    await waitFor(() => {
      expect(document.querySelector('[data-win="home"]')).toBeNull()
      expect(document.querySelector('[data-win="push"]')).toBeNull()
    })
    expect(screen.getByRole('heading', { name: 'Calendar' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Hermes' })).toBeTruthy()

    // Both visible views sit in the main column (their slots 0 and 1);
    // the side column is empty and omitted, leaving only the in-column
    // handle.
    expect(document.querySelector('.col-side')).toBeNull()
    expect(screen.getAllByRole('separator')).toHaveLength(1)
    expect(screen.getByRole('separator', { name: 'Resize Calendar and Hermes' })).toBeTruthy()

    // The hidden views' data endpoints are not polled once the layout has
    // settled: after the config loads the hidden windows are unmounted, so
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

  it('a lone visible view per column fills its column', async () => {
    stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['chat', 'home'],
      calendar_mode: 'month',
    })
    render(<App />)
    // Wait for the config fetch: both visible views become solo windows.
    await waitFor(() => {
      expect(document.querySelector('.col-main .win.solo[data-win="calendar"]')).toBeTruthy()
    })
    // Calendar in the main column, push in the side: both fill their column.
    expect(document.querySelector('.col-side .win.solo[data-win="push"]')).toBeTruthy()
    expect(document.querySelector('.col-main .divider')).toBeNull()
    expect(document.querySelector('.col-side .divider')).toBeNull()
    expect(screen.getAllByRole('separator')).toHaveLength(1)
  })

  it('shows an empty-state note when every view is hidden', async () => {
    stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: ['calendar', 'chat', 'home', 'push'],
      calendar_mode: 'month',
    })
    render(<App />)
    await screen.findByText(/All dashboard views are hidden/)
    expect(document.querySelector('.col-main')).toBeNull()
    expect(document.querySelector('.col-side')).toBeNull()
  })

  it('renders the calendar as a chronological schedule summary when the board says so', async () => {
    stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'schedule',
    })
    render(<App />)

    // The month grid is not rendered; the schedule list is.
    const standup = await screen.findByText('Standup')
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

  it('keeps the drag-resized proportions when views are reordered', async () => {
    rectSpy(1000, 600)
    const { board } = stubBoardServer({
      order: ['calendar', 'chat', 'home', 'push'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)

    // Drag the main-column handle: the top window's share grows.
    const handle = screen.getByRole('separator', { name: 'Resize Calendar and Hermes' })
    await pointer(handle, 'pointerdown', 0, 0)
    await pointer(handle, 'pointermove', 0, 300)
    await pointer(handle, 'pointerup', 0, 300)
    expect(parseFloat(layoutEl().style.getPropertyValue('--main'))).toBeCloseTo(0.9, 6)

    // Reorder the views (what the settings screen persists to the board
    // file), then reopen the settings so the app picks the config up.
    board.views = { order: ['chat', 'calendar', 'home', 'push'], hidden: [], calendar_mode: 'month' }
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    await waitFor(() => {
      expect(
        (document.querySelector('[data-win="chat"]') as HTMLElement | null)?.getAttribute(
          'data-slot',
        ),
      ).toBe('main-top')
    })

    // The proportions survived the reorder (session-only state, untouched),
    // now applied to the slots the views occupy: chat owns the grown
    // main-top share and calendar the shrunken main-bottom one.
    expect(parseFloat(layoutEl().style.getPropertyValue('--main'))).toBeCloseTo(0.9, 6)
    expect(parseFloat(layoutEl().style.getPropertyValue('--main-r'))).toBeCloseTo(0.1, 6)
    expect(
      (document.querySelector('[data-win="calendar"]') as HTMLElement | null)?.getAttribute(
        'data-slot',
      ),
    ).toBe('main-bottom')
  })

  it('maximizing a view reordered into the other column targets the column it now sits in', async () => {
    // Calendar moved to side-bottom (the order that used to blank the
    // board: the maximize CSS assumed calendar always sat in the main
    // column).
    stubBoardServer({
      order: ['chat', 'home', 'push', 'calendar'],
      hidden: [],
      calendar_mode: 'month',
    })
    render(<App />)
    await waitFor(() => {
      expect(
        (document.querySelector('[data-win="calendar"]') as HTMLElement | null)?.getAttribute(
          'data-slot',
        ),
      ).toBe('side-bottom')
    })

    fireEvent.click(screen.getByRole('heading', { name: 'Calendar' }))

    // The layout marks the column that actually contains the maximized
    // window, so the maximize CSS hides the other column and the board
    // stays filled; the maximized window still renders in its column.
    expect(layoutEl().getAttribute('data-max')).toBe('calendar')
    expect(layoutEl().getAttribute('data-max-col')).toBe('side')
    expect(document.querySelector('.col-side [data-win="calendar"]')).toBeTruthy()

    // A view that sits in the main column marks the other way.
    fireEvent.click(screen.getByRole('heading', { name: 'Home' }))
    expect(layoutEl().getAttribute('data-max')).toBe('home')
    expect(layoutEl().getAttribute('data-max-col')).toBe('main')
  })

  it('keeps the chat conversation when the view is hidden and re-shown', async () => {
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

    // Hide the chat view (settings → Views), then show it again.
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Views' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes chat' }))
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeNull())
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes chat' }))
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeTruthy())

    // The thread survived the hide/show: the backend is stateless, so the
    // in-memory transcript is the only copy of the conversation.
    expect(screen.getByText('hello')).toBeTruthy()
    expect(screen.getByText('reply to: hello')).toBeTruthy()
  })

  it('lands an in-flight chat reply after the view is hidden and re-shown', async () => {
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

    // Send, then hide the view while the reply is still in flight.
    fireEvent.change(screen.getByPlaceholderText('Message Hermes…'), {
      target: { value: 'hello' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByText('hello')

    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Views' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes chat' }))
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeNull())

    // The reply lands while the view is hidden, then the view comes back.
    await act(async () => {
      release!()
    })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show Hermes chat' }))
    await waitFor(() => expect(document.querySelector('[data-win="chat"]')).toBeTruthy())
    await screen.findByText('reply to: hello')
  })
})
