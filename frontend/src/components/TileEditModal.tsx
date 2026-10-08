import { useEffect, useRef, useState, type MouseEvent, type PointerEvent } from 'react'
import { ApiError, api, type HAEntity } from '../api'
import type {
  ApiBoard,
  CalendarMode,
  HABoardTile,
  HAKind,
  NormalizedViews,
  TileSize,
  ViewId,
} from '../types'
import { VIEW_IDS } from '../types'
import { haTileLabel, isHATileId, TILE_SIZE_LABELS, VIEW_LABELS } from '../utils'

interface Props {
  /** The tile being edited: a built-in view id or an HA tile id. */
  id: string
  /** The board's effective layout (defaults already filled in). */
  views: NormalizedViews
  /** The HA entities (the entity picker's source; empty while HA is off). */
  entities: Record<string, HAEntity>
  /** True once a poll has reported Home Assistant is not configured. */
  haOff: boolean
  onClose: () => void
  /** Adopt the board a save just persisted server-side (the same
   *  board-views save path as the settings screen). */
  onBoard: (board: ApiBoard) => void
}

const isViewId = (id: string): id is ViewId => (VIEW_IDS as readonly string[]).includes(id)
const SIZES: TileSize[] = ['s', 'm', 'l', 'xl']

/**
 * The per-tile settings modal: the edit options for one tile, grouped in
 * labeled sections — the size step (every tile; the width the board uses
 * when it places the tile automatically), the Calendar tile's view mode
 * (its content), and a Home Assistant tile's entity, kind and label (its
 * HA mapping) — saved through the same board-views path as the settings
 * screen. Closes from the Close button, Escape, or a click on the
 * backdrop; a successful save closes it too (the change is visible on the
 * board itself).
 */
export default function TileEditModal({ id, views, entities, haOff, onClose, onBoard }: Props) {
  const view = isViewId(id)
  const ht = views.ha_tiles.find((t) => t.id === id)

  // Drafts seeded from the effective layout (the modal mounts fresh for
  // every tile it opens on).
  const [size, setSize] = useState<TileSize>(views.sizes[id] ?? 'm')
  const [mode, setMode] = useState<CalendarMode>(views.calendar_mode)
  const [entity, setEntity] = useState(ht?.entity ?? '')
  const [label, setLabel] = useState(ht?.label ?? '')
  const [kind, setKind] = useState<HAKind>(ht?.kind ?? 'button')
  const [saving, setSaving] = useState(false)
  const [saveErr, setSaveErr] = useState<ApiError | null>(null)

  // Escape closes the modal, wherever the focus is.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // A press that starts on the backdrop (the overlay area outside the
  // card) closes the modal; a press that starts inside the card keeps it
  // open even when the click lands on the backdrop.
  const pressOnBackdrop = useRef(false)
  const onBackdropPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    pressOnBackdrop.current = e.target === e.currentTarget
  }
  const onBackdropClick = (e: MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget && pressOnBackdrop.current) onClose()
  }

  const sizeSeed = views.sizes[id] ?? 'm'
  const sizeChanged = size !== sizeSeed
  const haChanged =
    entity.trim() !== (ht?.entity ?? '') ||
    label.trim() !== (ht?.label ?? '') ||
    kind !== (ht?.kind ?? 'button')
  const changed = view
    ? sizeChanged || (id === 'calendar' && mode !== views.calendar_mode)
    : sizeChanged || haChanged

  // The board-views patch a save sends: the changed size step (per-tile
  // merge), the calendar view mode when the Calendar tile changed it, and
  // — for an HA tile — the ha_tiles list with this tile's fields replaced.
  const save = async () => {
    if (saving || !changed) return
    const trimmedEntity = entity.trim()
    if (!view && trimmedEntity === '') {
      setSaveErr(new ApiError(0, 'bad_value', 'The tile needs a Home Assistant entity.'))
      return
    }
    setSaving(true)
    setSaveErr(null)
    try {
      const board = view
        ? await api.setViews({
            ...(sizeChanged ? { sizes: { [id]: size } } : {}),
            ...(id === 'calendar' && mode !== views.calendar_mode ? { calendar_mode: mode } : {}),
          })
        : await api.setViews({
            ...(sizeChanged ? { sizes: { [id]: size } } : {}),
            ...(haChanged
              ? {
                  ha_tiles: views.ha_tiles.map((t): HABoardTile =>
                    t.id === id
                      ? {
                          id,
                          entity: trimmedEntity,
                          kind,
                          ...(label.trim() !== '' ? { label: label.trim() } : {}),
                        }
                      : t,
                  ),
                }
              : {}),
          })
      onBoard(board)
      onClose()
    } catch (e) {
      setSaveErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e)))
    } finally {
      setSaving(false)
    }
  }

  const name = view ? VIEW_LABELS[id] : ht ? haTileLabel(ht, entities[ht.entity] ?? null) : id

  return (
    <div className="tile-modal-wrap" onPointerDown={onBackdropPointerDown} onClick={onBackdropClick}>
      <section className="card tile-modal" role="dialog" aria-label={`Edit ${name} tile settings`}>
        <div className="card-head">
          <h2>
            {name}
            {isHATileId(id) && <span className="view-ha-badge">HA</span>}
          </h2>
          <div className="card-head-actions">
            <button
              type="button"
              className="btn small"
              onClick={onClose}
              aria-label="Close tile settings"
              title="Close"
            >
              ✕
            </button>
          </div>
        </div>
        <form
          id="tile-edit-form"
          className="tile-modal-body"
          onSubmit={(e) => {
            e.preventDefault()
            void save()
          }}
        >
          <section className="settings-section">
            <h3>Size</h3>
            <div className="settings-row">
              <div>
                <div className="settings-row-label">Tile size</div>
                <div className="settings-row-desc">
                  The tile's width when the board places it automatically; an explicit
                  placement (drag/resize on the board) keeps its own span.
                </div>
              </div>
              <div className="tabs" role="group" aria-label="Tile size">
                {SIZES.map((s) => (
                  <button
                    key={s}
                    type="button"
                    className={size === s ? 'tab on' : 'tab'}
                    aria-pressed={size === s}
                    disabled={saving}
                    onClick={() => setSize(s)}
                  >
                    {TILE_SIZE_LABELS[s].replace(' tile', '')}
                  </button>
                ))}
              </div>
            </div>
          </section>
          {view && id === 'calendar' && (
            <section className="settings-section">
              <h3>Content</h3>
              <div className="settings-row">
                <div>
                  <div className="settings-row-label">Calendar view</div>
                  <div className="settings-row-desc">
                    How the Calendar tile renders: the month grid, or a schedule summary —
                    a chronological list of upcoming events.
                  </div>
                </div>
                <div className="tabs" role="group" aria-label="Calendar view">
                  <button
                    type="button"
                    className={mode === 'month' ? 'tab on' : 'tab'}
                    aria-pressed={mode === 'month'}
                    disabled={saving}
                    onClick={() => setMode('month')}
                  >
                    Calendar
                  </button>
                  <button
                    type="button"
                    className={mode === 'schedule' ? 'tab on' : 'tab'}
                    aria-pressed={mode === 'schedule'}
                    disabled={saving}
                    onClick={() => setMode('schedule')}
                  >
                    Schedule summary
                  </button>
                </div>
              </div>
            </section>
          )}
          {!view && (
            <section className="settings-section">
              <h3>Home Assistant</h3>
              <div className="settings-row">
                <div>
                  <div className="settings-row-label">Entity</div>
                  <div className="settings-row-desc">
                    The HA entity this tile binds to (type to search the entity list).
                  </div>
                </div>
                <input
                  className="ha-entity-input"
                  list="tile-edit-entity-options"
                  value={entity}
                  onChange={(e) => setEntity(e.target.value)}
                  placeholder="light.kitchen"
                  aria-label="Home Assistant entity"
                  required
                />
              </div>
              <datalist id="tile-edit-entity-options">
                {Object.values(entities).map((e) => (
                  <option key={e.entity_id} value={e.entity_id}>
                    {e.label || e.entity_id}
                  </option>
                ))}
              </datalist>
              <div className="settings-row">
                <div>
                  <div className="settings-row-label">Tile kind</div>
                  <div className="settings-row-desc">
                    A button tile fires the allowlisted action (toggle) from the board; a
                    visual tile shows the entity's state.
                  </div>
                </div>
                <div className="tabs" role="group" aria-label="Tile kind">
                  <button
                    type="button"
                    className={kind === 'button' ? 'tab on' : 'tab'}
                    aria-pressed={kind === 'button'}
                    disabled={saving}
                    onClick={() => setKind('button')}
                  >
                    Button
                  </button>
                  <button
                    type="button"
                    className={kind === 'visual' ? 'tab on' : 'tab'}
                    aria-pressed={kind === 'visual'}
                    disabled={saving}
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
              {haOff && (
                <p className="note">
                  Home Assistant is not configured — the entity list is unavailable until the
                  connection is set.
                </p>
              )}
            </section>
          )}
          {saveErr && (
            <p className="note err">
              {saveErr.code} — {saveErr.detail}
            </p>
          )}
        </form>
        <div className="tile-modal-foot">
          <button type="button" className="btn ghost" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="tile-edit-form" className="btn" disabled={saving || !changed}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </section>
    </div>
  )
}
