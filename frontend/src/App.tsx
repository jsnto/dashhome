import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'
import type { ApiConfig, ApiBoard, BoardViews } from './types'
import { useTheme } from './useTheme'
import { normalizeViews } from './utils'
import CalendarSection from './components/CalendarSection'
import ChatPanel, { type UiMsg } from './components/ChatPanel'
import FullscreenButton from './components/FullscreenButton'
import HAPanel from './components/HAPanel'
import PushPanel from './components/PushPanel'
import SettingsPanel from './components/SettingsPanel'

/**
 * Window split proportions, kept in React state (session-only — they reset
 * on reload) and exposed to the CSS as flex-grow ratios via the --col /
 * --main / --side custom properties set on .layout. On desktop --col splits
 * the layout width; on the stacked mobile fallback the same properties
 * split the rows, so every handle keeps working in both layouts.
 */
const COL_FRAC = 7 / 12 // matches the original 7fr / 5fr column split
const ROW_FRAC = 0.5
const MIN_FRAC = 0.1
const MAX_FRAC = 0.9
const NARROW_QUERY = '(max-width: 980px)'

type WinId = 'calendar' | 'chat' | 'home' | 'push'
type DragId = 'col' | 'main' | 'side'
type Axis = 'x' | 'y'

// The titles the windows actually show (their card-head h2); the resize
// handles name their two neighbours, so these match the visible titles.
const WIN_TITLES: Record<WinId, string> = {
  calendar: 'Calendar',
  chat: 'Hermes',
  home: 'Home',
  push: 'Hermes push',
}

interface DragSession {
  id: DragId
  axis: Axis
  start: number
  startFrac: number
  size: number
}

export default function App() {
  const [cfg, setCfg] = useState<ApiConfig | null>(null)
  const [theme, setTheme] = useTheme()
  const [showSettings, setShowSettings] = useState(false)

  // Effective view layout: the board file's views section with defaults
  // filled in (absent configuration = exactly today's board). The four
  // views map one-to-one onto the board's slots in reading order
  // (main-top, main-bottom, side-top, side-bottom): a view keeps its slot
  // when the order changes, and a hidden view is not rendered at all (its
  // slot stays empty and the view needs no data polling).
  const views = useMemo<BoardViews>(() => normalizeViews(cfg?.board.views), [cfg])
  const slotView = (slot: number): WinId | null => {
    const id = views.order[slot]
    return id !== undefined && !views.hidden.includes(id) ? id : null
  }
  const mainWins = [slotView(0), slotView(1)].filter((w): w is WinId => w !== null)
  const sideWins = [slotView(2), slotView(3)].filter((w): w is WinId => w !== null)
  const visible = [...mainWins, ...sideWins]

  // Split proportions: left column's share, top window's share per column.
  const [colFrac, setColFrac] = useState(COL_FRAC)
  const [mainFrac, setMainFrac] = useState(ROW_FRAC)
  const [sideFrac, setSideFrac] = useState(ROW_FRAC)
  // The window whose title was last clicked (fills the layout), if any.
  const [maximized, setMaximized] = useState<WinId | null>(null)

  // The Hermes chat transcript (and its id counter) lives here rather than
  // in ChatPanel: the backend is stateless (the client sends the whole
  // history on every call), so this in-memory copy is the only one, and
  // hiding or moving the chat view must not drop the thread. App stays
  // mounted while the view is hidden, so an in-flight reply still lands
  // in the transcript.
  const [chatMsgs, setChatMsgs] = useState<UiMsg[]>([])
  const chatIdRef = useRef(0)
  const appendChatMsg = (m: Omit<UiMsg, 'id'>) => {
    const id = chatIdRef.current++
    setChatMsgs((cur) => [...cur, { ...m, id }])
  }

  const layoutRef = useRef<HTMLElement>(null)
  const colMainRef = useRef<HTMLDivElement>(null)
  const colSideRef = useRef<HTMLDivElement>(null)
  const dragRef = useRef<DragSession | null>(null)
  const [dragAxis, setDragAxis] = useState<Axis | null>(null)

  useEffect(() => {
    api
      .getConfig()
      .then(setCfg)
      .catch(() => {})
  }, [])

  useEffect(() => {
    if (!showSettings) return
    api
      .getConfig()
      .then(setCfg)
      .catch(() => {})
  }, [showSettings])

  // A maximized window that gets hidden in the settings must not leave the
  // layout maximized to nothing: restore the full split.
  const visibleKey = visible.join(',')
  useEffect(() => {
    if (maximized && !visible.includes(maximized)) setMaximized(null)
    // visible is re-derived each render; visibleKey tracks its content.
  }, [visibleKey, maximized]) // eslint-disable-line react-hooks/exhaustive-deps

  const dots: { label: string; ok: boolean; title: string }[] = cfg
    ? [
        {
          label: 'HA',
          ok: cfg.integrations.homeassistant,
          title: 'Home Assistant dashboard',
        },
        {
          label: 'Cal',
          ok: cfg.integrations.google.configured,
          title: 'Google Calendar',
        },
        {
          label: 'Chat',
          ok: cfg.integrations.hermes_chat,
          title: 'Hermes chat',
        },
        {
          label: 'Push',
          ok: cfg.integrations.hermes_push,
          title: 'Hermes push',
        },
      ]
    : []

  const isNarrow = () =>
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia(NARROW_QUERY).matches

  const handleDragStart = (id: DragId) => (e: React.PointerEvent<HTMLDivElement>) => {
    // The column handle splits the columns left/right on desktop but the
    // stacked rows on the narrow layout, so its drag axis follows the media
    // query; the in-column handles always drag vertically.
    const axis: Axis = id === 'col' ? (isNarrow() ? 'y' : 'x') : 'y'
    const container =
      id === 'col' ? layoutRef.current : id === 'main' ? colMainRef.current : colSideRef.current
    const rect = container?.getBoundingClientRect()
    const size = rect ? (axis === 'x' ? rect.width : rect.height) : 0
    const startFrac = id === 'col' ? colFrac : id === 'main' ? mainFrac : sideFrac
    dragRef.current = { id, axis, start: axis === 'x' ? e.clientX : e.clientY, startFrac, size }
    setDragAxis(axis)
    e.preventDefault()
    e.currentTarget.setPointerCapture?.(e.pointerId)
  }

  const handleDragMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = dragRef.current
    if (!d) return
    const pos = d.axis === 'x' ? e.clientX : e.clientY
    const delta = d.size > 0 ? (pos - d.start) / d.size : 0
    const frac = Math.min(MAX_FRAC, Math.max(MIN_FRAC, d.startFrac + delta))
    if (d.id === 'col') setColFrac(frac)
    else if (d.id === 'main') setMainFrac(frac)
    else setSideFrac(frac)
  }

  const handleDragEnd = () => {
    dragRef.current = null
    setDragAxis(null)
  }

  // While dragging, the resize cursor and no-select must follow the pointer
  // even when it leaves the handle.
  useEffect(() => {
    const body = document.body
    body.classList.toggle('resizing-x', dragAxis === 'x')
    body.classList.toggle('resizing-y', dragAxis === 'y')
    return () => {
      body.classList.remove('resizing-x', 'resizing-y')
    }
  }, [dragAxis])

  // Clicking a window's title (the h2 in .card-head) maximizes that window
  // to the whole layout; clicking it again restores the previous split,
  // which is kept in state so the proportions come back exactly.
  const handleLayoutClick = (e: React.MouseEvent) => {
    const h2 = (e.target as HTMLElement).closest('h2')
    if (!h2?.closest('.card-head')) return
    const raw = h2.closest<HTMLElement>('[data-win]')?.dataset.win
    // Only the four dashboard windows are maximizable; ignore anything else
    // (and this narrows raw to WinId).
    if (raw !== 'calendar' && raw !== 'chat' && raw !== 'home' && raw !== 'push') return
    setMaximized((m) => (m === raw ? null : raw))
  }

  const dividerProps = (id: DragId) => ({
    onPointerDown: handleDragStart(id),
    onPointerMove: handleDragMove,
    onPointerUp: handleDragEnd,
    onPointerCancel: handleDragEnd,
  })

  // One dashboard window: the component is mounted only while the view is
  // visible, so hidden views neither render nor poll for data.
  const winNode = (id: WinId) => {
    switch (id) {
      case 'calendar':
        return <CalendarSection mode={views.calendar_mode} />
      case 'chat':
        return <ChatPanel msgs={chatMsgs} onAppend={appendChatMsg} />
      case 'home':
        return <HAPanel />
      case 'push':
        return <PushPanel />
      default:
        return null
    }
  }

  // A board column (main or side): its visible windows in slot order, with
  // a resize handle between stacked windows. A lone window fills the
  // column (no handle, no fixed share); an empty column is omitted entirely.
  // The slot numbers (0-3) are the board's reading-order slots, so the
  // data-slot attribute — and the drag-resized proportion attached to it —
  // follows the slot, not the view.
  const renderColumn = (col: 'main' | 'side') => {
    const slots: [number, number] = col === 'main' ? [0, 1] : [2, 3]
    const items: { win: WinId; slot: number }[] = []
    for (const slot of slots) {
      const win = slotView(slot)
      if (win !== null) items.push({ win, slot })
    }
    if (items.length === 0) return null
    return (
      <div ref={col === 'main' ? colMainRef : colSideRef} className={`col-${col}`}>
        {items.map(({ win, slot }, i) => (
          <Fragment key={win}>
            {i > 0 && (
              <div
                className="divider divider-h"
                role="separator"
                aria-orientation="horizontal"
                aria-label={`Resize ${WIN_TITLES[items[i - 1].win]} and ${WIN_TITLES[win]}`}
                {...dividerProps(col)}
              />
            )}
            <div
              className={items.length === 1 ? 'win solo' : 'win'}
              data-win={win}
              data-slot={slot % 2 === 0 ? `${col}-top` : `${col}-bottom`}
            >
              {winNode(win)}
            </div>
          </Fragment>
        ))}
      </div>
    )
  }

  return (
    <div className="app">
      <header className="top">
        <h1 className="logo">
          dash<span>home</span>
        </h1>
        <div className="head-actions">
          <div className="dots">
            {dots.map((d) => (
              <span key={d.title} className={`dot ${d.ok ? 'ok' : 'off'}`} title={d.title}>
                {d.label}
              </span>
            ))}
          </div>
          <button
            className="btn ghost small"
            onClick={() => setShowSettings((s) => !s)}
            aria-pressed={showSettings}
          >
            Settings
          </button>
          <FullscreenButton />
        </div>
      </header>

      <main
        ref={layoutRef}
        className="layout"
        data-max={maximized ?? undefined}
        data-max-col={maximized ? (mainWins.includes(maximized) ? 'main' : 'side') : undefined}
        style={
          {
            '--col': colFrac,
            '--col-r': 1 - colFrac,
            '--main': mainFrac,
            '--main-r': 1 - mainFrac,
            '--side': sideFrac,
            '--side-r': 1 - sideFrac,
          } as React.CSSProperties
        }
        onClick={handleLayoutClick}
      >
        {renderColumn('main')}
        {mainWins.length > 0 && sideWins.length > 0 && (
          <div
            className="divider divider-v"
            role="separator"
            aria-label="Resize columns"
            {...dividerProps('col')}
          />
        )}
        {renderColumn('side')}
        {visible.length === 0 && (
          <div className="layout-empty">
            <p className="note">
              All dashboard views are hidden. Open Settings → Views to show one again.
            </p>
          </div>
        )}
      </main>

      {showSettings && (
        <SettingsPanel
          cfg={cfg}
          theme={theme}
          onThemeChange={setTheme}
          views={views}
          onBoard={(board: ApiBoard) => setCfg((c) => (c ? { ...c, board } : c))}
          onClose={() => setShowSettings(false)}
        />
      )}
    </div>
  )
}
