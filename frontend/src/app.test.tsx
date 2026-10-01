import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { ApiBoard } from './types'

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
})

describe('app: settings screen shows the persisted interval', () => {
  it('reopens with the saved interval, not the mount-time value', async () => {
    const { fetchMock, board } = stubServer(30)
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
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
    const reopened = screen.getByRole('spinbutton', { name: /refresh interval/i }) as HTMLInputElement
    await waitFor(() => expect(reopened.value).toBe('120'))
    // Reopening the settings screen re-fetches the config.
    expect(configGets()).toBeGreaterThan(getsBeforeReopen)
  })
})
