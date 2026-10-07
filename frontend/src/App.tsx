import { useEffect, useMemo, useRef, useState } from 'react'
import { api, ApiError } from './api'
import type { ApiBoard, ApiConfig, BoardViews, TileSize, ViewId } from './types'
import { TILE_SIZES } from './types'
import { useTheme } from './useTheme'
import { normalizeViews, TILE_SIZE_LABELS, VIEW_LABELS } from './utils'
import CalendarSection from './components/CalendarSection'
import ChatPanel, { type UiMsg } from './components/ChatPanel'
import FullscreenButton from './components/FullscreenButton'
import HAPanel from './components/HAPanel'
import PushPanel from './components/PushPanel'
import SettingsPanel from './components/SettingsPanel'

/**
 * The tile board: the four views are the tiles of a reflowing grid. Each
 * visible view is a tile (calendar, chat, home, push) that spans one of
 * the size steps (s/m/l/xl) of the board's 6-column grid; the grid
 * reflows by tile size and by how many tiles are visible. Tiles are in
 * board order (the views section's order), and a hidden tile is not
 * rendered at all (it needs no data polling).
 */

// The stacked mobile fallback: below this breakpoint the board is a single
// column of full-width tiles (each tile gets an equal share of the
// board's height). Driven from matchMedia (data-narrow on the board)
// rather than a CSS media query, so there is one source of truth and the
// fallback is testable.
const NARROW_QUERY = '(max-width: 980px)'

function useNarrow(): boolean {
  const [narrow, setNarrow] = useState(
    () =>
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia(NARROW_QUERY).matches,
  )
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return // no media queries (some test DOMs)
    const mql = window.matchMedia(NARROW_QUERY)
    const onChange = (e: MediaQueryListEvent) => setNarrow(e.matches)
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [])
  return narrow
}

export default function App() {
  const [cfg, setCfg] = useState<ApiConfig | null>(null)
  const [theme, setTheme] = useTheme()
  const [showSettings, setShowSettings] = useState(false)
  // The board's edit mode: each visible tile shows a size control; the
  // title-click maximize is suspended while it is on.
  const [editMode, setEditMode] = useState(false)
  // The tile whose title was last clicked (fills the board), if any.
  const [maximized, setMaximized] = useState<ViewId | null>(null)
  const narrow = useNarrow()
  // A size save that failed (shown above the board, cleared on the next
  // attempt).
  const [sizeErr, setSizeErr] = useState<ApiError | null>(null)
  const [savingSize, setSavingSize] = useState(false)

  // Effective tile layout: the board file's views section with defaults
  // filled in (absent configuration = exactly today's board).
  const views = useMemo<BoardViews>(() => normalizeViews(cfg?.board.views), [cfg])
  // The tiles in board order; hidden tiles are omitted entirely (not
  // rendered, so they need no data polling).
  const visible = views.order.filter((id) => !views.hidden.includes(id))

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

  // A maximized tile that gets hidden must not leave the board maximized
  // to nothing: restore the full grid.
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

  // Entering edit mode clears a maximized tile: with the other tiles
  // hidden there would be nothing to size.
  const toggleEditMode = () => {
    if (!editMode) setMaximized(null)
    setEditMode(!editMode)
  }

  // Change a tile's size step on the board: persisted to the board file
  // (the views section's per-tile sizes map) through the same guarded
  // write path as the settings saves; the server's answer adopts the new
  // layout. One in-flight save at a time (the controls disable while it
  // runs); a failure is reported above the board.
  const changeSize = (id: ViewId, size: TileSize) => {
    if (savingSize || size === views.sizes[id]) return
    setSavingSize(true)
    setSizeErr(null)
    api
      .setViews({ sizes: { [id]: size } })
      .then((board: ApiBoard) => setCfg((c) => (c ? { ...c, board } : c)))
      .catch((e: unknown) =>
        setSizeErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e))),
      )
      .finally(() => setSavingSize(false))
  }

  // Clicking a tile's title (the h2 in .card-head) fills the board with
  // that tile; clicking it again restores the grid. Only the four
  // dashboard tiles are maximizable, and nothing toggles while the board
  // is in edit mode (sizing is the point then, and maximizing would hide
  // the other tiles).
  const handleBoardClick = (e: React.MouseEvent) => {
    if (editMode) return
    const h2 = (e.target as HTMLElement).closest('h2')
    if (!h2?.closest('.card-head')) return
    const raw = h2.closest<HTMLElement>('[data-win]')?.dataset.win
    // Only the four dashboard tiles are maximizable; ignore anything else
    // (and this narrows raw to ViewId).
    if (raw !== 'calendar' && raw !== 'chat' && raw !== 'home' && raw !== 'push') return
    setMaximized((m) => (m === raw ? null : raw))
  }

  // One dashboard tile: the component is mounted only while the view is
  // visible, so hidden views neither render nor poll for data.
  const winNode = (id: ViewId) => {
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
            onClick={toggleEditMode}
            aria-pressed={editMode}
            title={editMode ? 'Leave the tile edit mode' : 'Edit the tiles\' sizes on the board'}
          >
            Edit tiles
          </button>
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

      {sizeErr && (
        <p className="note err board-err">
          {sizeErr.code} — {sizeErr.detail}
        </p>
      )}

      <main
        className="board"
        data-edit={editMode || undefined}
        data-max={maximized ?? undefined}
        data-narrow={narrow || undefined}
        onClick={handleBoardClick}
      >
        {visible.map((id) => (
          <div key={id} className="tile" data-win={id} data-size={views.sizes[id]}>
            {editMode && (
              <div className="tile-edit" role="group" aria-label={`${VIEW_LABELS[id]} tile size`}>
                <span className="tile-edit-label">Size</span>
                {TILE_SIZES.map((s) => (
                  <button
                    key={s}
                    className="tile-size-btn"
                    aria-pressed={views.sizes[id] === s}
                    disabled={savingSize}
                    onClick={() => changeSize(id, s)}
                    title={`${TILE_SIZE_LABELS[s]} tile`}
                  >
                    {s.toUpperCase()}
                  </button>
                ))}
              </div>
            )}
            {winNode(id)}
          </div>
        ))}
        {visible.length === 0 && (
          <div className="board-empty">
            <p className="note">All dashboard tiles are hidden. Open Settings → Tiles to show one again.</p>
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
