import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, ApiError } from './api'
import type {
  ApiBoard,
  ApiConfig,
  NormalizedViews,
  TileGeometry,
  TileSize,
  ViewId,
} from './types'
import { TILE_SIZES, VIEW_IDS } from './types'
import { useTheme } from './useTheme'
import { useHAEntities } from './useHAEntities'
import { GRID_COLS, normalizeViews, TILE_SIZE_LABELS, VIEW_LABELS, visibleOrder } from './utils'
import CalendarSection from './components/CalendarSection'
import ChatPanel, { type UiMsg } from './components/ChatPanel'
import FullscreenButton from './components/FullscreenButton'
import HAPanel from './components/HAPanel'
import HATile from './components/HATile'
import PushPanel from './components/PushPanel'
import SettingsPanel from './components/SettingsPanel'
import {
  autoLayout,
  boardRows,
  dragCell,
  dropRefused,
  GridMetrics,
  measureGrid,
  stepWidth,
} from './tileGrid'

/**
 * The tile board: the four views (plus any Home Assistant tiles) are the
 * tiles of a reflowing 6-column grid. A tile either flows automatically
 * (its size step's span) or sits at a stored placement (the board file's
 * views.geometry, which the server repairs). Edit mode exposes the
 * placement affordances: drag with the grip or title, nudge with the move
 * buttons, change the height, resize from the corner, and the size
 * stepper (which also drives a placed tile's width).
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

const isViewId = (id: string): id is ViewId =>
  (VIEW_IDS as readonly string[]).includes(id)

// A tile's column span for its size step (the size stepper doubles as the
// width control on a placed tile).
const widthForSize = (size: TileSize | string | undefined) => stepWidth(size)

export default function App() {
  const [cfg, setCfg] = useState<ApiConfig | null>(null)
  const [theme, setTheme] = useTheme()
  const [showSettings, setShowSettings] = useState(false)
  // The board's edit mode: each visible tile shows the size, move and
  // height controls plus the drag/resize handles; the title-click maximize
  // is suspended while it is on.
  const [editMode, setEditMode] = useState(false)
  // The tile whose title was last clicked (fills the board), if any.
  const [maximized, setMaximized] = useState<string | null>(null)
  const narrow = useNarrow()
  // A layout save that failed (shown above the board, cleared on the next
  // attempt).
  const [layoutErr, setLayoutErr] = useState<ApiError | null>(null)
  const [savingSize, setSavingSize] = useState(false)

  // Effective tile layout: the board file's views section with defaults
  // filled in (absent configuration = exactly today's board).
  const views = useMemo<NormalizedViews>(
    () => normalizeViews(cfg?.board.views ?? null),
    [cfg],
  )
  // The tiles in board order; hidden tiles are omitted entirely (not
  // rendered, so they need no data polling).
  const visible = useMemo(() => visibleOrder(views), [views])

  // The visible tiles' effective layout: the stored placements plus the
  // auto flow of the rest — the board's rendered positions. The
  // drag/resize pixel math and the placement controls all act on this.
  const layout = useMemo(
    () => autoLayout(visible, views.sizes, views.geometry),
    [visible, views],
  )
  // The board's total rows (that layout), used for the drag/resize
  // pixel math.
  const rows = useMemo(() => boardRows(layout), [layout])

  // Home Assistant tiles shown on the board drive the entity-state polling;
  // hidden HA tiles are skipped.
  const haVisible = useMemo(() => visible.filter((id) => !isViewId(id)), [visible])
  const { entities, haOff, refresh: refreshHA } = useHAEntities(haVisible.length > 0)

  // The Hermes chat transcript lives here rather than in ChatPanel: the
  // backend is stateless (the client sends the whole history on every
  // call), so this in-memory copy is the only one, and hiding or moving
  // the chat view must not drop the thread. App stays mounted while the
  // view is hidden, so an in-flight reply still lands in the transcript.
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

  // Settings was closed (possibly after saving the board): refresh.
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

  // Persist a board-views patch (sizes and/or geometry) through the
  // guarded write path; the server's answer (the saved, re-normalized
  // layout) is adopted. One in-flight layout save at a time.
  const commitViews = (patch: {
    sizes?: Partial<Record<string, TileSize>>
    geometry?: Record<string, TileGeometry | null>
  }) => {
    if (!cfg) return
    api
      .setViews(patch)
      .then((board: ApiBoard) => {
        setCfg((c) => (c ? { ...c, board } : c))
        setLayoutErr(null)
      })
      .catch((e: unknown) =>
        setLayoutErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e))),
      )
      .finally(() => setSavingSize(false))
  }

  // Entering edit mode clears a maximized tile: with the other tiles
  // hidden there would be nothing to move or size.
  const toggleEditMode = () => {
    if (!editMode) setMaximized(null)
    setEditMode(!editMode)
  }

  // Change a tile's size step on the board: the tile's CSS span (and, for
  // a placed tile, its geometry width) is persisted through the same
  // guarded write path as the settings saves.
  const changeSize = (id: string, size: TileSize) => {
    if (savingSize || size === views.sizes[id]) return
    // A placed tile's width follows the size step (the stepper is its
    // width control); a new cell that overlaps a neighbour is refused
    // (the tile stays where it is) instead of committed.
    let cell: TileGeometry | null = null
    const g = views.geometry[id]
    if (g) {
      const w = Math.min(widthForSize(size), GRID_COLS)
      cell = { ...g, w, x: Math.max(1, Math.min(GRID_COLS - w + 1, g.x)) }
      if (dropRefused(id, cell, visible, views.sizes, views.geometry)) return
    }
    setSavingSize(true)
    setLayoutErr(null)
    const patch: {
      sizes: Partial<Record<string, TileSize>>
      geometry?: Record<string, TileGeometry | null>
    } = { sizes: { [id]: size } }
    if (cell) patch.geometry = { [id]: cell }
    commitViews(patch)
  }

  // A one-cell nudge from the on-tile move controls (placed tiles only).
  const nudge = (id: string, dx: number, dy: number) => {
    const g = views.geometry[id]
    if (!g || savingSize) return
    const cell: TileGeometry = {
      ...g,
      x: Math.max(1, Math.min(GRID_COLS - g.w + 1, g.x + dx)),
      y: Math.max(1, g.y + dy),
    }
    if (cell.x === g.x && cell.y === g.y) return
    if (dropRefused(id, cell, visible, views.sizes, views.geometry)) return
    setSavingSize(true)
    setLayoutErr(null)
    commitViews({ geometry: { [id]: cell } })
  }

  // Grow/shrink a tile's height (rows are unbounded), pinning an
  // unplaced tile at the cell it currently renders in.
  const stepHeight = (id: string, delta: number) => {
    if (savingSize) return
    const h = Math.max(1, layout[id].h + delta)
    if (h === layout[id].h) return
    const cell: TileGeometry = { ...layout[id], h }
    if (dropRefused(id, cell, visible, views.sizes, views.geometry)) return
    setSavingSize(true)
    setLayoutErr(null)
    commitViews({ geometry: { [id]: cell } })
  }

  // --- Drag & drop (edit mode only). The live drag data lives in a ref;
  // the preview cell in state so the board re-renders with the ghost.
  type Drag = {
    mode: 'move' | 'resize'
    id: string
    origin: TileGeometry
    startClientX: number
    startClientY: number
    moved: boolean
    metrics: GridMetrics
  }
  const dragRef = useRef<Drag | null>(null)
  const boardElRef = useRef<HTMLElement | null>(null)
  const [preview, setPreview] = useState<{
    id: string
    cell: TileGeometry
    refused: boolean
  } | null>(null)
  const previewRef = useRef(preview)
  useEffect(() => {
    previewRef.current = preview
  }, [preview])

  const beginDrag = (id: string, mode: 'move' | 'resize', e: React.PointerEvent) => {
    if (!editMode || narrow) return
    if (!boardElRef.current) return
    const metrics = measureGrid(boardElRef.current, rows)
    if (!metrics) return
    const origin = layout[id]
    dragRef.current = {
      mode,
      id,
      origin,
      startClientX: e.clientX,
      startClientY: e.clientY,
      moved: mode === 'resize', // a resize press is intentional
      metrics,
    }
  }

  const onDragMove = useCallback(
    (e: PointerEvent) => {
      const d = dragRef.current
      if (!d) return
      const dxPx = e.clientX - d.startClientX
      const dyPx = e.clientY - d.startClientY
      if (!d.moved && d.mode === 'move' && Math.hypot(dxPx, dyPx) < 4) return
      d.moved = true
      const cell = dragCell(d.mode, d.origin, dxPx, dyPx, d.metrics)
      const refused = dropRefused(d.id, cell, visible, views.sizes, views.geometry)
      setPreview({ id: d.id, cell, refused })
    },
    [visible, views],
  )

  const endDrag = useCallback(
    (commit: boolean) => {
      const d = dragRef.current
      dragRef.current = null
      if (!d || !commit || !d.moved) {
        setPreview(null)
        return
      }
      const pv = previewRef.current
      if (!pv || pv.refused || pv.id !== d.id) {
        setPreview(null) // snap back to the stored placement
        return
      }
      const cur = layout[d.id]
      if (
        pv.cell.x === cur.x &&
        pv.cell.y === cur.y &&
        pv.cell.w === cur.w &&
        pv.cell.h === cur.h
      ) {
        setPreview(null)
        return
      }
      setPreview(null)
      setSavingSize(true)
      api
        .setViews({ geometry: { [d.id]: pv.cell } })
        .then((board: ApiBoard) => {
          setCfg((c) => (c ? { ...c, board } : c))
          setLayoutErr(null)
        })
        .catch((e: unknown) =>
          setLayoutErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e))),
        )
        .finally(() => setSavingSize(false))
    },
    [layout],
  )

  // Attach the window pointer listeners while edit mode is on.
  useEffect(() => {
    if (!editMode) return
    const move = (e: PointerEvent) => onDragMove(e)
    const up = () => endDrag(true)
    const cancel = () => endDrag(false)
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
    window.addEventListener('pointercancel', cancel)
    return () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      window.removeEventListener('pointercancel', cancel)
    }
  }, [editMode, onDragMove, endDrag])

  useEffect(() => {
    if (editMode && !narrow) return
    dragRef.current = null
    setPreview(null)
  }, [editMode, narrow])

  // Clicking a tile's title (the h2 in .card-head) fills the board with
  // that tile; clicking it again restores the grid. Any board tile can be
  // maximized (a view or a Home Assistant tile); nothing toggles while the
  // board is in edit mode (moving is the point then).
  const handleBoardClick = (e: React.MouseEvent) => {
    if (editMode) return
    const h2 = (e.target as HTMLElement).closest('h2')
    if (!h2?.closest('.card-head')) return
    const raw = h2.closest<HTMLElement>('[data-win]')?.dataset.win
    if (!raw) return
    setMaximized((m) => (m === raw ? null : raw))
  }

  const tileName = (id: string) => {
    if (isViewId(id)) return VIEW_LABELS[id]
    const ht = views.ha_tiles.find((t) => t.id === id)
    return ht ? ht.label || ht.entity : id
  }

  // One dashboard tile: the component is mounted only while the view is
  // visible, so hidden views neither render nor poll for data.
  const winNode = (id: string) => {
    if (isViewId(id)) {
      switch (id) {
        case 'calendar':
          return <CalendarSection mode={views.calendar_mode} />
        case 'chat':
          return <ChatPanel msgs={chatMsgs} onAppend={appendChatMsg} />
        case 'home':
          return <HAPanel />
        default:
          return <PushPanel />
      }
    }
    const ht = views.ha_tiles.find((t) => t.id === id)
    if (!ht) return null
    return (
      <HATile
        tile={ht}
        entity={entities[ht.entity] ?? null}
        haOff={haOff}
        onService={refreshHA}
      />
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
            onClick={toggleEditMode}
            aria-pressed={editMode}
            title={editMode ? 'Leave the tile edit mode' : 'Edit the tile layout on the board'}
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

      {layoutErr && (
        <p className="note err board-err">
          {layoutErr.code} — {layoutErr.detail}
        </p>
      )}

      <main
        ref={boardElRef as React.RefObject<HTMLElement>}
        className="board"
        data-edit={editMode || undefined}
        data-max={maximized ?? undefined}
        data-narrow={narrow || undefined}
        onClick={handleBoardClick}
      >
        {visible.map((id) => {
          const maxed = maximized === id
          const geo = views.geometry[id]
          const pv = preview?.id === id ? preview : null
          const dragging = pv !== null
          // Inline placement only while a tile is placed, dragged, or (via
          // the .maxed class) maximized: auto-placed tiles keep the CSS
          // size spans, and data-narrow overrides everything (CSS
          // !important) for the stacked fallback.
          const placedStyle = dragging
            ? {
                gridColumn: `${pv.cell.x} / span ${pv.cell.w}`,
                gridRow: `${pv.cell.y} / span ${pv.cell.h}`,
              }
            : geo
              ? { gridColumn: `${geo.x} / span ${geo.w}`, gridRow: `${geo.y} / span ${geo.h}` }
              : undefined
          return (
            <div
              key={id}
              className={[
                'tile',
                maxed ? 'maxed' : '',
                dragging ? 'dragging' : '',
                dragging && pv?.refused ? 'preview-invalid' : '',
              ]
                .filter(Boolean)
                .join(' ')}
              data-win={id}
              data-size={views.sizes[id]}
              style={maxed || narrow ? undefined : placedStyle}
            >
              {editMode && (
                <div className="tile-edit" role="group" aria-label={`${VIEW_LABELS[id as ViewId] ?? tileName(id)} tile size`}>
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
              {editMode && (
                <div
                  className="tile-layout"
                  role="group"
                  aria-label={`${tileName(id)} tile placement`}
                >
                  <span className="tile-grip" title="Drag to move this tile" onPointerDown={(e) => beginDrag(id, 'move', e)}>
                    ⠿
                  </span>
                  <span className="tile-move">
                    <button type="button" className="btn tiny" title="Move left" aria-label={`move ${tileName(id)} left`} onClick={() => nudge(id, -1, 0)}>
                      ←
                    </button>
                    <button type="button" className="btn tiny" title="Move right" aria-label={`move ${tileName(id)} right`} onClick={() => nudge(id, 1, 0)}>
                      →
                    </button>
                    <button type="button" className="btn tiny" title="Move up" aria-label={`move ${tileName(id)} up`} onClick={() => nudge(id, 0, -1)}>
                      ↑
                    </button>
                    <button type="button" className="btn tiny" title="Move down" aria-label={`move ${tileName(id)} down`} onClick={() => nudge(id, 0, 1)}>
                      ↓
                    </button>
                  </span>
                  <span className="tile-h" title="Tile height (rows)">
                    <button type="button" className="btn tiny" title="Make shorter" aria-label={`decrease height of ${tileName(id)}`} onClick={() => stepHeight(id, -1)}>
                      −
                    </button>
                    <span className="tile-h-val">{views.geometry[id]?.h ?? 1}</span>
                    <button type="button" className="btn tiny" title="Make taller" aria-label={`increase height of ${tileName(id)}`} onClick={() => stepHeight(id, 1)}>
                      +
                    </button>
                  </span>
                </div>
              )}
              {winNode(id)}
              {editMode && (
                <button
                  type="button"
                  className="tile-resize"
                  title="Drag to resize (width and height)"
                  aria-label={`resize ${tileName(id)}`}
                  onPointerDown={(e) => beginDrag(id, 'resize', e)}
                />
              )}
            </div>
          )
        })}
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
