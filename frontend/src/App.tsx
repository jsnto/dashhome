import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, ApiError } from './api'
import type {
  ApiBoard,
  ApiConfig,
  GridDims,
  HABoardTile,
  NormalizedViews,
  TileGeometry,
  ViewId,
} from './types'
import { VIEW_IDS } from './types'
import { useTheme } from './useTheme'
import { useHAEntities } from './useHAEntities'
import { normalizeGrid, normalizeViews, tileName, visibleOrder } from './utils'
import { type UiMsg } from './components/ChatPanel'
import BoardTile, { BoardTileContent } from './components/BoardTile'
import FullscreenButton from './components/FullscreenButton'
import SettingsPanel from './components/SettingsPanel'
import TileEditModal from './components/TileEditModal'
import {
  autoLayout,
  dragCell,
  dropRefused,
  GridMetrics,
  measureGrid,
} from './tileGrid'

/**
 * The tile board: the four views (plus any Home Assistant tiles) are the
 * tiles of a grid of cols x rows units (12 x 12 by default, editable in
 * settings and persisted to the board file as board.grid). A tile either
 * flows automatically (its size step's fraction of the grid's width) or
 * sits at a stored placement (the board file's views.geometry, which the
 * server repairs). Edit mode exposes the placement affordances: drag with
 * the grip or the corner resize handle (both snapping to the grid), the
 * settings button (the per-tile modal: the size step, the type-specific
 * options), and the remove button.
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

/** The rows the board actually renders: the auto flow is open on rows, so
 *  a layout that overflows the grid's rows stretches the board with 1fr
 *  auto rows, and the drag/resize row pitch follows that rendered count. */
function renderedRows(layout: Record<string, TileGeometry>, gridRows: number): number {
  let last = 1
  for (const id in layout) {
    const c = layout[id]
    if (c) last = Math.max(last, c.y + c.h - 1)
  }
  return Math.max(gridRows, last)
}

export default function App() {
  const [cfg, setCfg] = useState<ApiConfig | null>(null)
  const [theme, setTheme] = useTheme()
  const [showSettings, setShowSettings] = useState(false)
  // The board's edit mode: each visible tile shows the move grip, the
  // settings and remove buttons plus the corner resize handle; the
  // title-click maximize is suspended while it is on.
  const [editMode, setEditMode] = useState(false)
  // The tile whose title was last clicked (fills the board), if any.
  const [maximized, setMaximized] = useState<string | null>(null)
  const narrow = useNarrow()
  // A layout save that failed (shown above the board, cleared on the next
  // attempt).
  const [layoutErr, setLayoutErr] = useState<ApiError | null>(null)
  const [savingSize, setSavingSize] = useState(false)
  // The tile whose settings modal is open (the per-tile edit button), if any.
  const [editingTile, setEditingTile] = useState<string | null>(null)

  // The board's effective tile grid (cols x rows units): the board file's
  // board.grid repaired to valid dimensions, defaulting to 12x12. Every
  // layout, bounds and pixel computation below runs on these dimensions.
  const grid = useMemo<GridDims>(() => normalizeGrid(cfg?.board.grid), [cfg])

  // Effective tile layout: the board file's views section with defaults
  // filled in (absent configuration = exactly today's board).
  const views = useMemo<NormalizedViews>(
    () => normalizeViews(cfg?.board.views ?? null, grid),
    [cfg, grid],
  )
  // The tiles in board order; hidden tiles are omitted entirely (not
  // rendered, so they need no data polling).
  const visible = useMemo(() => visibleOrder(views, grid), [views, grid])

  // The visible tiles' effective layout: the stored placements plus the
  // auto flow of the rest — the board's rendered positions. The drag
  // pixel math and the size controls all act on this.
  const layout = useMemo(
    () => autoLayout(visible, views.sizes, views.geometry, grid.cols),
    [visible, views, grid],
  )

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

  // Persist a board-views patch through the guarded write path (the same
  // PUT /api/config/views save the geometry changes go through); the
  // server's answer (the saved, re-normalized layout) is adopted. One
  // in-flight layout save at a time.
  const commitViews = (patch: {
    hidden?: string[]
    geometry?: Record<string, TileGeometry | null>
    ha_tiles?: HABoardTile[]
  }) => {
    if (!cfg) return
    setSavingSize(true)
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

  // Edit mode's per-tile remove control: a single press drops the tile
  // from the board (no confirm — a removed tile can be added back from
  // Settings), persisted through the same board-views save path as the
  // geometry changes. A Home Assistant tile is dropped from views.ha_tiles
  // (only the board's reference goes — its entity and data are untouched);
  // a built-in view is hidden (it keeps its stored placement, so re-showing
  // it from Settings restores the tile). The server re-normalizes the
  // order/hidden/geometry around the new set.
  const removeTile = (id: string) => {
    if (savingSize) return
    if (!isViewId(id)) {
      commitViews({ ha_tiles: views.ha_tiles.filter((t) => t.id !== id) })
      return
    }
    if (views.hidden.includes(id)) return
    commitViews({ hidden: [...views.hidden, id] })
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
    const metrics = measureGrid(boardElRef.current, renderedRows(layout, grid.rows), grid.cols)
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
      const cell = dragCell(d.mode, d.origin, dxPx, dyPx, d.metrics, grid.cols, grid.rows)
      const refused = dropRefused(d.id, cell, visible, views.sizes, views.geometry, grid.cols, grid.rows)
      setPreview({ id: d.id, cell, refused })
    },
    [visible, views, grid],
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

  // One dashboard tile: the component is mounted only while the view is
  // visible, so hidden views neither render nor poll for data.
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
        style={{ '--grid-cols': grid.cols, '--grid-rows': grid.rows } as React.CSSProperties}
        onClick={handleBoardClick}
      >
        {visible.map((id) => {
          const maxed = maximized === id
          const pv = preview?.id === id ? preview : null
          const dragging = pv !== null
          // The board's effective cell for this tile (the drag preview
          // while dragging, its effective layout otherwise): the layout
          // math is the single source of truth for rendering — every
          // tile gets an explicit placement. Maximizing and the
          // data-narrow override win (CSS !important / data-max).
          const cell = dragging ? pv.cell : layout[id]
          const placedStyle = {
            gridColumn: `${cell.x} / span ${cell.w}`,
            gridRow: `${cell.y} / span ${cell.h}`,
          }
          return (
            <BoardTile
              key={id}
              id={id}
              name={tileName(id, views)}
              maxed={maxed}
              dragging={dragging}
              refused={pv?.refused === true}
              size={views.sizes[id]}
              style={maxed || narrow ? undefined : placedStyle}
              editMode={editMode}
              savingSize={savingSize}
              onGripDown={(e) => beginDrag(id, 'move', e)}
              onResizeDown={(e) => beginDrag(id, 'resize', e)}
              onEdit={() => setEditingTile(id)}
              onRemove={() => removeTile(id)}
            >
              <BoardTileContent
                id={id}
                views={views}
                chatMsgs={chatMsgs}
                onAppendChat={appendChatMsg}
                entities={entities}
                haOff={haOff}
                onService={refreshHA}
              />
            </BoardTile>
          )
        })}
        {visible.length === 0 && (
          <div className="board-empty">
            <p className="note">All dashboard tiles are hidden. Open Settings → Tiles to show one again.</p>
          </div>
        )}
      </main>

      {editingTile && visible.includes(editingTile) && (
        <TileEditModal
          id={editingTile}
          views={views}
          entities={entities}
          haOff={haOff}
          onClose={() => setEditingTile(null)}
          onBoard={(board: ApiBoard) => setCfg((c) => (c ? { ...c, board } : c))}
        />
      )}

      {showSettings && (
        <SettingsPanel
          cfg={cfg}
          theme={theme}
          onThemeChange={setTheme}
          views={views}
          grid={grid}
          onBoard={(board: ApiBoard) => setCfg((c) => (c ? { ...c, board } : c))}
          onIntegrations={(integrations) => setCfg((c) => (c ? { ...c, integrations } : c))}
          onClose={() => setShowSettings(false)}
        />
      )}
    </div>
  )
}
