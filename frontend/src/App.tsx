import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import type { ApiConfig } from './types'
import { useTheme } from './useTheme'
import CalendarSection from './components/CalendarSection'
import ChatPanel from './components/ChatPanel'
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

  // Split proportions: left column's share, top window's share per column.
  const [colFrac, setColFrac] = useState(COL_FRAC)
  const [mainFrac, setMainFrac] = useState(ROW_FRAC)
  const [sideFrac, setSideFrac] = useState(ROW_FRAC)
  // The window whose title was last clicked (fills the layout), if any.
  const [maximized, setMaximized] = useState<WinId | null>(null)

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
    if (!h2) return
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
        <div ref={colMainRef} className="col-main">
          <div className="win" data-win="calendar">
            <CalendarSection />
          </div>
          <div
            className="divider divider-h"
            role="separator"
            aria-orientation="horizontal"
            aria-label="Resize Calendar and Hermes"
            {...dividerProps('main')}
          />
          <div className="win" data-win="chat">
            <ChatPanel />
          </div>
        </div>
        <div
          className="divider divider-v"
          role="separator"
          aria-label="Resize columns"
          {...dividerProps('col')}
        />
        <div ref={colSideRef} className="col-side">
          <div className="win" data-win="home">
            <HAPanel />
          </div>
          <div
            className="divider divider-h"
            role="separator"
            aria-orientation="horizontal"
            aria-label="Resize Home and Hermes push"
            {...dividerProps('side')}
          />
          <div className="win" data-win="push">
            <PushPanel />
          </div>
        </div>
      </main>

      {showSettings && (
        <SettingsPanel
          cfg={cfg}
          theme={theme}
          onThemeChange={setTheme}
          onClose={() => setShowSettings(false)}
        />
      )}

      <footer className="foot">dashhome — your home, on one dashboard</footer>
    </div>
  )
}
