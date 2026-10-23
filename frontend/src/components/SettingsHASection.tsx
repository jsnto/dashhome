import { useEffect, useState } from 'react'
import { ApiError, api, type HAEntity } from '../api'
import type { ApiBoard, BoardViews, GridDims, HABoardTile, HAKind, TileGeometry } from '../types'
import { nextHATileId, normalizeViews, tileName } from '../utils'
import { NEW_TILE_DEFAULT_SIZE, firstFreeCell } from '../tileGrid'

/**
 * The Home Assistant section: adds and removes the HA-backed board tiles
 * (views.ha_tiles) — first-class tiles bound to exactly one HA entity
 * each, from the entities HA reports (GET /api/ha/entities). A button
 * tile fires the board's allowlisted action (toggle) from the board; a
 * visual tile shows the entity's state. Entity states are polled, never
 * stored; no HA secret ever reaches the board file or the browser.
 */
export default function SettingsHASection({
  views,
  grid,
  onBoard,
}: {
  views: BoardViews
  /** The board's effective tile grid (cols x rows units): the new tile's
   *  default 2x2 placement is computed on these dimensions. */
  grid: GridDims
  onBoard?: (board: ApiBoard) => void
}) {
  const [tiles, setTiles] = useState<HABoardTile[]>(views.ha_tiles ?? [])
  const [entities, setEntities] = useState<HAEntity[] | null>(null)
  const [haOff, setHaOff] = useState(false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<ApiError | null>(null)
  const [entity, setEntity] = useState('')
  const [kind, setKind] = useState<HAKind>('button')
  const [label, setLabel] = useState('')

  useEffect(() => setTiles(views.ha_tiles ?? []), [views])

  // The entity picker's source (and the honest "not configured" state).
  useEffect(() => {
    let on = true
    api
      .getHAEntities()
      .then((r) => {
        if (!on) return
        setEntities(r.entities)
        setHaOff(false)
      })
      .catch((e) => {
        if (!on) return
        if (e instanceof ApiError && e.code === 'homeassistant_not_configured') {
          setHaOff(true)
        }
      })
    return () => {
      on = false
    }
  }, [])

  // Persist the full ha_tiles list (the endpoint replaces the list),
  // optionally together with the new tile's default placement; the
  // server re-normalizes the order/hidden/geometry around the new set.
  const persist = (next: HABoardTile[], geometry?: Record<string, TileGeometry | null>) => {
    if (busy) return
    const before = tiles
    setBusy(true)
    setErr(null)
    setTiles(next)
    api
      .setViews({
        ha_tiles: next,
        ...(geometry ? { geometry } : {}),
      })
      .then((board) => {
        onBoard?.(board)
        setEntity('')
        setLabel('')
      })
      .catch((e) => {
        setTiles(before)
        setErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e)))
      })
      .finally(() => setBusy(false))
  }

  // The new tile's default placement: a 2x2 cell on the board's grid, the
  // first one (in reading order) that is free of the board's effective
  // layout (the stored placements plus the auto flow, hidden tiles
  // included). A grid too small for the span, or a board with no free
  // cell, leaves the tile to auto placement (no geometry in the save).
  const add = () => {
    const entityId = entity.trim()
    if (entityId === '' || haOff) return
    const id = nextHATileId(tiles)
    const nv = normalizeViews(views, grid)
    const cell = firstFreeCell(
      nv.order,
      nv.sizes,
      nv.geometry,
      NEW_TILE_DEFAULT_SIZE.w,
      NEW_TILE_DEFAULT_SIZE.h,
      grid.cols,
      grid.rows,
    )
    persist(
      [...tiles, { id, entity: entityId, ...(label.trim() !== '' ? { label: label.trim() } : {}), kind }],
      cell ? { [id]: cell } : undefined,
    )
  }

  const remove = (id: string) => {
    persist(tiles.filter((t) => t.id !== id))
  }

  return (
    <section className="settings-section">
      <h3>Home Assistant</h3>
      <p className="note">
        Board tiles bound to Home Assistant entities: a <em>button</em> tile fires the
        allowlisted action (toggle) from the board, a <em>visual</em> tile shows the
        entity's state. Each tile is bound to exactly one entity; states are polled,
        never stored.
      </p>

      {tiles.length > 0 && (
        <div className="ha-list">
          {tiles.map((t) => (
            <div className="view-row ha-row" key={t.id}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="view-name">
                  {tileName(t.id, { ...views, ha_tiles: tiles })}
                  <span className="view-ha-badge">{t.kind}</span>
                </div>
                <div className="view-size" title={t.entity}>
                  {t.entity}
                </div>
              </div>
              <div className="view-controls">
                <button
                  className="btn small danger"
                  disabled={busy}
                  onClick={() => remove(t.id)}
                  aria-label={`Remove ${tileName(t.id, { ...views, ha_tiles: tiles })} tile`}
                >
                  Remove
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {haOff ? (
        <p className="note">
          Home Assistant is not configured. Set the Home Assistant URL and token in Settings →
          Services to add tiles.
        </p>
      ) : (
        <form
          className="ha-add"
          onSubmit={(e) => {
            e.preventDefault()
            add()
          }}
        >
          <div className="settings-row">
            <div>
              <div className="settings-row-label">Entity</div>
              <div className="settings-row-desc">
                The HA entity this tile binds to (type to search the entity list).
              </div>
            </div>
            <input
              className="ha-entity-input"
              list="ha-entity-options"
              value={entity}
              onChange={(e) => setEntity(e.target.value)}
              placeholder="light.kitchen"
              aria-label="Home Assistant entity"
              required
            />
          </div>
          <datalist id="ha-entity-options">
            {(entities ?? []).map((e) => (
              <option key={e.entity_id} value={e.entity_id}>
                {e.label || e.entity_id}
              </option>
            ))}
          </datalist>
          <div className="settings-row">
            <div>
              <div className="settings-row-label">Tile kind</div>
            </div>
            <div className="tabs" role="group" aria-label="Tile kind">
              <button
                type="button"
                className={kind === 'button' ? 'tab on' : 'tab'}
                aria-pressed={kind === 'button'}
                onClick={() => setKind('button')}
              >
                Button
              </button>
              <button
                type="button"
                className={kind === 'visual' ? 'tab on' : 'tab'}
                aria-pressed={kind === 'visual'}
                onClick={() => setKind('visual')}
              >
                Visual
              </button>
            </div>
          </div>
          <div className="settings-row">
            <div>
              <div className="settings-row-label">Label (optional)</div>
              <div className="settings-row-desc">
                Shown on the tile instead of the entity's own label.
              </div>
            </div>
            <input
              className="ha-label-input"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="Kitchen"
              aria-label="Tile label"
            />
          </div>
          <button className="btn" type="submit" disabled={busy || entity.trim() === ''}>
            {busy ? 'Saving…' : 'Add tile'}
          </button>
        </form>
      )}
      {err && (
        <p className="note err">
          {err.code} — {err.detail}
        </p>
      )}
    </section>
  )
}
