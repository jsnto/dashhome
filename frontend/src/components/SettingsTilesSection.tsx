import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api'
import type { ApiBoard, BoardViews, CalendarMode, GridDims } from '../types'
import { GRID_MAX, GRID_MIN, TILE_SIZE_LABELS, isHATileId, normalizeGrid, tileName } from '../utils'

/**
 * The Tiles section: which dashboard tiles are on the main screen
 * (show/hide each tile — the four views and any Home Assistant tiles),
 * in what board order, and how the Calendar tile renders. Every change is
 * persisted to the board file as it happens (partial updates; the server
 * keeps the fields the request omits). Tile size and placement are edited
 * on the board itself (the header's Edit tiles mode), not here; the
 * current size step is shown per tile.
 */
export default function SettingsTilesSection({
  views,
  grid,
  onBoard,
}: {
  views: BoardViews
  grid: GridDims
  onBoard?: (board: ApiBoard) => void
}) {
  // Optimistic local state: updated immediately, reverted if the save
  // fails, re-synced from the server's answer on success.
  const [local, setLocal] = useState<BoardViews>(views)
  const [saving, setSaving] = useState(false)
  const [err, setErr] = useState<ApiError | null>(null)

  // The board's tile grid dimensions, edited here and persisted server-side
  // (board.grid). Same adopt-only-while-untouched pattern as the refresh
  // interval: the section's saves refresh the config underneath an open
  // panel (App adopts the answer via onBoard), and that must not clobber
  // an in-progress edit.
  const [gridCols, setGridCols] = useState(String(grid.cols))
  const [gridRows, setGridRows] = useState(String(grid.rows))
  const gridSeedRef = useRef(`${grid.cols}x${grid.rows}`)
  const [gridSaving, setGridSaving] = useState(false)
  const [gridSavedMsg, setGridSavedMsg] = useState<string | null>(null)
  const [gridErr, setGridErr] = useState<ApiError | null>(null)

  useEffect(() => {
    const v = `${grid.cols}x${grid.rows}`
    if (gridSeedRef.current === `${gridCols}x${gridRows}`) {
      gridSeedRef.current = v
      setGridCols(String(grid.cols))
      setGridRows(String(grid.rows))
    }
    // gridCols/gridRows are deliberately not dependencies: the fields are
    // adopted only on grid changes, not on every keystroke.
  }, [grid]) // eslint-disable-line react-hooks/exhaustive-deps

  const saveGrid = async (e: FormEvent) => {
    e.preventDefault()
    const cols = Number(gridCols)
    const rows = Number(gridRows)
    if (
      !Number.isInteger(cols) || !Number.isInteger(rows) ||
      cols < GRID_MIN || cols > GRID_MAX || rows < GRID_MIN || rows > GRID_MAX
    ) {
      setGridErr(
        new ApiError(
          0,
          'bad_grid',
          `Grid dimensions must be whole numbers between ${GRID_MIN} and ${GRID_MAX}.`,
        ),
      )
      return
    }
    setGridSaving(true)
    setGridErr(null)
    setGridSavedMsg(null)
    try {
      const board = await api.setGrid({ cols, rows })
      const applied = normalizeGrid(board.grid)
      setGridCols(String(applied.cols))
      setGridRows(String(applied.rows))
      gridSeedRef.current = `${applied.cols}x${applied.rows}`
      onBoard?.(board)
      setGridSavedMsg(`Saved — the board grid is now ${applied.cols} by ${applied.rows} units.`)
    } catch (e) {
      setGridErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e)))
    } finally {
      setGridSaving(false)
    }
  }

  useEffect(() => setLocal(views), [views])

  const apply = async (
    body: { order?: string[]; hidden?: string[]; calendar_mode?: CalendarMode },
    next: BoardViews,
  ) => {
    if (saving) return
    const before = local
    setSaving(true)
    setErr(null)
    setLocal(next)
    try {
      const board = await api.setViews(body)
      onBoard?.(board)
    } catch (e) {
      setLocal(before)
      setErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e)))
    } finally {
      setSaving(false)
    }
  }

  const toggleHidden = (id: string) => {
    const nextHidden = local.hidden.includes(id)
      ? local.hidden.filter((h) => h !== id)
      : [...local.hidden, id]
    void apply({ hidden: nextHidden }, { ...local, hidden: nextHidden })
  }

  const move = (id: string, delta: number) => {
    const i = local.order.indexOf(id)
    const j = i + delta
    if (i < 0 || j < 0 || j >= local.order.length) return
    const order = [...local.order]
    ;[order[i], order[j]] = [order[j], order[i]]
    void apply({ order }, { ...local, order })
  }

  const setMode = (m: CalendarMode) => {
    if (m === local.calendar_mode) return
    void apply({ calendar_mode: m }, { ...local, calendar_mode: m })
  }

  return (
    <section className="settings-section">
      <h3>Tiles</h3>
      <p className="note">
        Which dashboard tiles are on the main screen and in what board order. Changes
        save immediately. Resize a tile or place it in the grid from the board's
        Edit tiles mode.
      </p>
      {local.order.map((id, i) => {
        const name = tileName(id, local)
        const hidden = local.hidden.includes(id)
        return (
          <div className="view-row" key={id}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="view-name">
                {name}
                {isHATileId(id) && <span className="view-ha-badge">HA</span>}
              </div>
              <div className="view-size">{TILE_SIZE_LABELS[local.sizes[id] ?? 'm']}</div>
            </div>
            <div className="view-controls">
              <label className="view-toggle">
                <input
                  type="checkbox"
                  checked={!hidden}
                  disabled={saving}
                  onChange={() => toggleHidden(id)}
                  aria-label={`Show ${name}`}
                />
                {hidden ? 'Hidden' : 'Visible'}
              </label>
              <div className="view-move">
                <button
                  className="btn small"
                  disabled={saving || i === 0}
                  onClick={() => move(id, -1)}
                  aria-label={`Move ${name} up`}
                  title={`Move ${name} up`}
                >
                  ↑
                </button>
                <button
                  className="btn small"
                  disabled={saving || i === local.order.length - 1}
                  onClick={() => move(id, 1)}
                  aria-label={`Move ${name} down`}
                  title={`Move ${name} down`}
                >
                  ↓
                </button>
              </div>
            </div>
          </div>
        )
      })}

      <div className="settings-row">
        <div>
          <div className="settings-row-label">Calendar view</div>
          <div className="settings-row-desc">
            How the Calendar tile renders: the month grid, or a schedule summary — a
            chronological list of upcoming events.
          </div>
        </div>
        <div className="tabs" role="group" aria-label="Calendar view">
          <button
            className={local.calendar_mode === 'month' ? 'tab on' : 'tab'}
            aria-pressed={local.calendar_mode === 'month'}
            disabled={saving}
            onClick={() => setMode('month')}
          >
            Calendar
          </button>
          <button
            className={local.calendar_mode === 'schedule' ? 'tab on' : 'tab'}
            aria-pressed={local.calendar_mode === 'schedule'}
            disabled={saving}
            onClick={() => setMode('schedule')}
          >
            Schedule summary
          </button>
        </div>
      </div>
      {err && (
        <p className="note err">
          {err.code} — {err.detail}
        </p>
      )}

      <div className="settings-row">
        <div>
          <div className="settings-row-label">Board grid</div>
          <div className="settings-row-desc">
            The board is split evenly into this many units across (columns) and down
            (rows); a tile spans whole units. 1–{GRID_MAX} each. Placements that no
            longer fit a smaller grid fall back to automatic placement.
          </div>
        </div>
        <form className="settings-grid" onSubmit={saveGrid}>
          <input
            className="num-input"
            type="number"
            min={GRID_MIN}
            max={GRID_MAX}
            step={1}
            inputMode="numeric"
            value={gridCols}
            onChange={(e) => setGridCols(e.target.value)}
            aria-label="Grid columns"
          />
          <span className="settings-interval-unit">×</span>
          <input
            className="num-input"
            type="number"
            min={GRID_MIN}
            max={GRID_MAX}
            step={1}
            inputMode="numeric"
            value={gridRows}
            onChange={(e) => setGridRows(e.target.value)}
            aria-label="Grid rows"
          />
          <button className="btn" type="submit" disabled={gridSaving}>
            {gridSaving ? 'Saving…' : 'Save'}
          </button>
        </form>
      </div>
      {gridSavedMsg && <p className="note saved">{gridSavedMsg}</p>}
      {gridErr && (
        <p className="note err">
          {gridErr.code} — {gridErr.detail}
        </p>
      )}
    </section>
  )
}
