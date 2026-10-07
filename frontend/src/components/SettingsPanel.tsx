import { useEffect, useRef, useState, type FormEvent, type MouseEvent, type PointerEvent } from 'react'
import { ApiError, api, type HAEntity } from '../api'
import { emitRefreshSeconds } from '../refreshBus'
import type {
  ApiBoard,
  ApiConfig,
  BoardViews,
  CalendarMode,
  GridDims,
  HABoardTile,
  HAKind,
  ServiceField,
  ServiceSettings,
  ServiceSettingsUpdate,
  TileGeometry,
} from '../types'
import { VIEW_IDS } from '../types'
import type { Theme } from '../theme'
import {
  GRID_MAX,
  GRID_MIN,
  TILE_SIZE_LABELS,
  VIEW_LABELS,
  isHATileId,
  normalizeGrid,
  normalizeViews,
} from '../utils'
import { NEW_TILE_DEFAULT_SIZE, firstFreeCell } from '../tileGrid'

interface Props {
  cfg: ApiConfig | null
  theme: Theme
  onThemeChange: (t: Theme) => void
  /** Effective view layout (defaults already filled in). */
  views?: BoardViews
  /** The board's effective tile grid (cols x rows units), repaired.
   *  (Derived from cfg by the caller; the Tiles section edits it.) */
  grid: GridDims
  /** Adopt a board just persisted server-side (keeps the dashboard in sync). */
  onBoard?: (board: ApiBoard) => void
  /** Adopt the integration status a save just changed (the effective
   *  configuration after the service-settings save). */
  onIntegrations?: (integrations: ApiConfig['integrations']) => void
  onClose: () => void
}

type SectionId = 'appearance' | 'tiles' | 'ha' | 'services' | 'updates'

const SECTIONS: { id: SectionId; label: string }[] = [
  { id: 'appearance', label: 'Appearance' },
  { id: 'tiles', label: 'Tiles' },
  { id: 'ha', label: 'Home Assistant' },
  { id: 'services', label: 'Services' },
  { id: 'updates', label: 'Updates' },
]

/** The display name of any board tile (a built-in view or an HA tile). */
function tileName(id: string, views: BoardViews): string {
  if ((VIEW_IDS as readonly string[]).includes(id)) return VIEW_LABELS[id as keyof typeof VIEW_LABELS]
  const t = views.ha_tiles?.find((x) => x.id === id)
  return t ? (t.label && t.label.trim() !== '' ? t.label : t.entity) : id
}

/** The next free Home Assistant tile id (ha-1, ha-2, …). */
function nextHATileId(tiles: HABoardTile[]): string {
  const used = new Set(tiles.map((t) => t.id))
  for (let n = 1; ; n++) {
    const id = `ha-${n}`
    if (!used.has(id)) return id
  }
}

/**
 * Read-only summary of the integrations this deployment actually has
 * configured. Mirrors the `integrations` object of GET /api/config, which
 * exposes booleans only — no secret values ever reach the browser.
 */
function serviceRows(cfg: ApiConfig): {
  label: string
  desc: string
  state: 'ok' | 'pending' | 'off'
  stateText: string
  href?: string
}[] {
  const g = cfg.integrations.google
  const embed = cfg.board.ha_embed ?? null
  const embedPath = embed?.url_path?.trim()
  const embedDesc =
    (embedPath ? `Embeds the \u201c${embedPath}\u201d dashboard.` : 'Embeds the default dashboard.') +
    (embed?.token_url ? ' Token passed in the URL.' : '')
  return [
    {
      label: 'Google Calendar',
      desc: 'Events shown in the Calendar panel.',
      state: !g.configured ? 'off' : g.login_required ? 'pending' : 'ok',
      stateText: !g.configured ? 'Not configured' : g.login_required ? 'Sign-in required' : 'Configured',
      href: g.configured && g.login_required ? g.login_url || '/api/auth/google/login' : undefined,
    },
    {
      label: 'Home Assistant',
      desc: `Real HA dashboard in an iframe in the Home panel. ${embedDesc}`,
      state: cfg.integrations.homeassistant ? 'ok' : 'off',
      stateText: cfg.integrations.homeassistant ? 'Configured' : 'Not configured',
    },
    {
      label: 'Hermes chat',
      desc: 'Chat box in the dashboard.',
      state: cfg.integrations.hermes_chat ? 'ok' : 'off',
      stateText: cfg.integrations.hermes_chat ? 'Configured' : 'Not configured',
    },
    {
      label: 'Hermes push',
      desc: 'Markdown pushed to the dashboard by Hermes.',
      state: cfg.integrations.hermes_push ? 'ok' : 'off',
      stateText: cfg.integrations.hermes_push ? 'Configured' : 'Not configured',
    },
  ]
}

/**
 * The Tiles section: which dashboard tiles are on the main screen
 * (show/hide each tile — the four views and any Home Assistant tiles),
 * in what board order, and how the Calendar tile renders. Every change is
 * persisted to the board file as it happens (partial updates; the server
 * keeps the fields the request omits). Tile size and placement are edited
 * on the board itself (the header's Edit tiles mode), not here; the
 * current size step is shown per tile.
 */
function TilesSection({
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

/**
 * The Home Assistant section: adds and removes the HA-backed board tiles
 * (views.ha_tiles) — first-class tiles bound to exactly one HA entity
 * each, from the entities HA reports (GET /api/ha/entities). A button
 * tile fires the board's allowlisted action (toggle) from the board; a
 * visual tile shows the entity's state. Entity states are polled, never
 * stored; no HA secret ever reaches the board file or the browser.
 */
function HASection({
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

/** A draft of one editable service field: the input's value and whether it
 *  changed since the masked view seeded it. An unchanged field is omitted
 *  from the save; a changed one sends its value (an empty string clears the
 *  UI value back to the .env fallback). */
interface ServiceFieldDraft {
  value: string
  dirty: boolean
}

/** Seed the drafts from the masked view: non-secret fields carry their
 *  UI-set value; secret fields never expose a value, so their draft starts
 *  empty (a typed value replaces the current one on save). */
function seedServiceDrafts(s: ServiceSettings): Record<string, ServiceFieldDraft> {
  const d: Record<string, ServiceFieldDraft> = {}
  const put = (key: string, f: ServiceField, secret: boolean) => {
    d[key] = { value: secret ? '' : f.value, dirty: false }
  }
  put('homeassistant.url', s.homeassistant.url, false)
  put('homeassistant.token', s.homeassistant.token, true)
  put('google.service_account_file', s.google.service_account_file, false)
  put('google.domain', s.google.domain, false)
  put('google.client_id', s.google.client_id, false)
  put('google.client_secret', s.google.client_secret, true)
  put('google.redirect_uri', s.google.redirect_uri, false)
  put('google.token_cache', s.google.token_cache, false)
  put('google.access_token', s.google.access_token, true)
  put('google.endpoint', s.google.endpoint, false)
  put('hermes_chat.base_url', s.hermes_chat.base_url, false)
  put('hermes_chat.chat_path', s.hermes_chat.chat_path, false)
  put('hermes_chat.auth_header', s.hermes_chat.auth_header, false)
  put('hermes_chat.auth_value', s.hermes_chat.auth_value, true)
  put('hermes_push.token', s.hermes_push.token, true)
  return d
}

/** The partial update a save sends: only the changed fields, grouped per
 *  integration (a group with no changed field is omitted wholesale, and so
 *  is an unchanged field within a group — the backend keeps omitted values). */
function serviceUpdate(drafts: Record<string, ServiceFieldDraft>): ServiceSettingsUpdate {
  const v = (key: string): string | null =>
    drafts[key]?.dirty ? drafts[key].value : null
  const ha = { url: v('homeassistant.url'), token: v('homeassistant.token') }
  const g = {
    service_account_file: v('google.service_account_file'),
    domain: v('google.domain'),
    client_id: v('google.client_id'),
    client_secret: v('google.client_secret'),
    redirect_uri: v('google.redirect_uri'),
    token_cache: v('google.token_cache'),
    access_token: v('google.access_token'),
    endpoint: v('google.endpoint'),
  }
  const hc = {
    base_url: v('hermes_chat.base_url'),
    chat_path: v('hermes_chat.chat_path'),
    auth_header: v('hermes_chat.auth_header'),
    auth_value: v('hermes_chat.auth_value'),
  }
  const hp = { token: v('hermes_push.token') }
  const any = (o: Record<string, string | null>) =>
    Object.values(o).some((x) => x !== null)
  const update: ServiceSettingsUpdate = {}
  if (any(ha)) update.homeassistant = ha
  if (any(g)) update.google = g
  if (any(hc)) update.hermes_chat = hc
  if (any(hp)) update.hermes_push = hp
  return update
}

/**
 * The Services section: the in-use integrations and their status, plus the
 * service settings — each integration's connection fields, editable here and
 * persisted to the board file (board.services), where they override the .env
 * values (settings UI > .env > default) and take effect on the next request,
 * without a restart. Secret fields never expose a value to the browser: the
 * masked view reports only whether each layer set one, and the input accepts
 * a replacement (or a clear back to the .env fallback).
 */
function ServicesSection({
  cfg,
  onBoard,
  onIntegrations,
}: {
  cfg: ApiConfig | null
  onBoard?: (board: ApiBoard) => void
  onIntegrations?: (integrations: ApiConfig['integrations']) => void
}) {
  const [services, setServices] = useState<ServiceSettings | null>(null)
  const [drafts, setDrafts] = useState<Record<string, ServiceFieldDraft> | null>(null)
  const [busy, setBusy] = useState(false)
  const [savedMsg, setSavedMsg] = useState('')
  const [loadErr, setLoadErr] = useState<ApiError | null>(null)
  const [saveErr, setSaveErr] = useState<ApiError | null>(null)

  // The masked service view (the settings-UI values plus the .env
  // fallbacks; secrets reduced to set booleans).
  useEffect(() => {
    let on = true
    api
      .getServices()
      .then((s) => {
        if (!on) return
        setServices(s)
        setDrafts(seedServiceDrafts(s))
      })
      .catch((e) => {
        if (!on) return
        setLoadErr(e instanceof ApiError ? e : new ApiError(0, 'load_failed', String(e)))
      })
    return () => {
      on = false
    }
  }, [])

  const setField = (key: string, value: string) => {
    setDrafts((d) => (d ? { ...d, [key]: { value, dirty: true } } : d))
  }

  const dirtyCount = drafts ? Object.values(drafts).filter((d) => d.dirty).length : 0

  const save = () => {
    if (busy || !drafts) return
    setBusy(true)
    setSaveErr(null)
    setSavedMsg('')
    api
      .setServices(serviceUpdate(drafts))
      .then((res) => {
        onBoard?.(res.board)
        onIntegrations?.(res.integrations)
        setServices(res.services)
        setDrafts(seedServiceDrafts(res.services))
        setSavedMsg('Service settings saved — the new values are live now.')
      })
      .catch((e) => {
        setSaveErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e)))
      })
      .finally(() => setBusy(false))
  }

  // One editable field row: the label and description on the left, the
  // input on the right. Non-secret fields seed the input with the value
  // set in the UI (the .env/default fallback shows as the placeholder);
  // secret fields never expose a value — the pill reports which layer set
  // one, the input accepts a replacement, and Clear (while the UI set one)
  // sends the empty string back to the .env fallback.
  const fieldRow = (
    key: string,
    label: string,
    desc: string,
    f: ServiceField,
    secret: boolean,
    type: 'text' | 'password' = 'text',
  ) => {
    const draft = drafts?.[key]
    if (!draft) return null
    const setInEnv = f.fallback_set
    const status = f.set
      ? 'Set in the settings UI'
      : setInEnv
        ? 'Set in .env'
        : 'Not set'
    return (
      <div className="settings-row">
        <div>
          <div className="settings-row-label">{label}</div>
          {desc !== '' && <div className="settings-row-desc">{desc}</div>}
        </div>
        <div className="service-field-inputs">
          {secret && (
            <span className={`dot ${f.set || setInEnv ? 'ok' : 'off'}`} title={status}>
              {status}
            </span>
          )}
          <input
            className="service-input"
            type={type}
            value={draft.value}
            placeholder={secret ? '' : f.fallback}
            disabled={busy}
            aria-label={label}
            onChange={(e) => setField(key, e.target.value)}
          />
          {secret && f.set && (
            <button
              type="button"
              className="btn small"
              disabled={busy}
              onClick={() => setField(key, '')}
            >
              Clear
            </button>
          )}
        </div>
      </div>
    )
  }

  return (
    <section className="settings-section">
      <h3>Services in use</h3>
      {cfg === null ? (
        <p className="note">Loading…</p>
      ) : (
        serviceRows(cfg).map((row) => (
          <div className="settings-row" key={row.label}>
            <div>
              <div className="settings-row-label">{row.label}</div>
              <div className="settings-row-desc">{row.desc}</div>
            </div>
            {row.state === 'ok' && <span className="dot ok">{row.stateText}</span>}
            {row.state === 'pending' && (
              <span className="dot warn">
                {row.href ? (
                  <a href={row.href} target="_blank" rel="noreferrer">
                    {row.stateText}
                  </a>
                ) : (
                  row.stateText
                )}
              </span>
            )}
            {row.state === 'off' && <span className="dot off">{row.stateText}</span>}
          </div>
        ))
      )}

      <h3>Service settings</h3>
      <p className="note">
        Stored in the dashboard config file, where they override the .env values
        (settings UI &gt; .env &gt; default). Changes are live on the next request —
        no restart. Secret fields show only whether a value is set; saving a new
        value replaces the current one.
      </p>
      {services === null || drafts === null ? (
        loadErr ? (
          <p className="note err">
            {loadErr.code} — {loadErr.detail}
          </p>
        ) : (
          <p className="note">Loading…</p>
        )
      ) : (
        <>
          <h3>Home Assistant settings</h3>
          {fieldRow(
            'homeassistant.url',
            'Server URL',
            'Base URL of the Home Assistant server, e.g. https://ha.example.com.',
            services.homeassistant.url,
            false,
          )}
          {fieldRow(
            'homeassistant.token',
            'Access token',
            'Long-lived access token (HA → Profile → Security).',
            services.homeassistant.token,
            true,
            'password',
          )}

          <h3>Google Calendar settings</h3>
          <p className="note">
            Credential modes, when several are set: static access token, then
            service account, then the OAuth client.
          </p>
          {fieldRow(
            'google.service_account_file',
            'Service account key',
            'Key file path of the Google service account (a file the backend can read).',
            services.google.service_account_file,
            false,
          )}
          {fieldRow(
            'google.domain',
            'Domain',
            'Workspace domain for domain-wide delegation, e.g. user@workspace.com.',
            services.google.domain,
            false,
          )}
          {fieldRow(
            'google.client_id',
            'OAuth client ID',
            'The OAuth client used for the one-time sign-in.',
            services.google.client_id,
            false,
          )}
          {fieldRow('google.client_secret', 'OAuth client secret', '', services.google.client_secret, true, 'password')}
          {fieldRow(
            'google.redirect_uri',
            'OAuth redirect URI',
            'The redirect URI the sign-in flow returns to.',
            services.google.redirect_uri,
            false,
          )}
          {fieldRow(
            'google.token_cache',
            'Token cache',
            'Path of the OAuth token cache file.',
            services.google.token_cache,
            false,
          )}
          {fieldRow(
            'google.access_token',
            'Static access token',
            'A Calendar API access token (development).',
            services.google.access_token,
            true,
            'password',
          )}
          {fieldRow(
            'google.endpoint',
            'API endpoint',
            'Calendar API base URL override (advanced).',
            services.google.endpoint,
            false,
          )}

          <h3>Hermes chat settings</h3>
          {fieldRow(
            'hermes_chat.base_url',
            'Base URL',
            'Base URL of the Hermes API (no trailing slash).',
            services.hermes_chat.base_url,
            false,
          )}
          {fieldRow(
            'hermes_chat.chat_path',
            'Chat path',
            'Chat endpoint path.',
            services.hermes_chat.chat_path,
            false,
          )}
          {fieldRow(
            'hermes_chat.auth_header',
            'Auth header',
            'Header name to send.',
            services.hermes_chat.auth_header,
            false,
          )}
          {fieldRow(
            'hermes_chat.auth_value',
            'Auth value',
            'Full header value, e.g. Bearer sk-… (empty = no auth).',
            services.hermes_chat.auth_value,
            true,
            'password',
          )}

          <h3>Hermes push settings</h3>
          {fieldRow(
            'hermes_push.token',
            'Push token',
            'Shared secret Hermes must present (empty = push disabled).',
            services.hermes_push.token,
            true,
            'password',
          )}

          <form
            onSubmit={(e) => {
              e.preventDefault()
              save()
            }}
          >
            <button className="btn" type="submit" disabled={busy || dirtyCount === 0}>
              {busy ? 'Saving…' : 'Save'}
            </button>
          </form>
          {savedMsg && <p className="note saved">{savedMsg}</p>}
          {saveErr && (
            <p className="note err">
              {saveErr.code} — {saveErr.detail}
            </p>
          )}
        </>
      )}
    </section>
  )
}

/**
 * The settings page: a left sidebar menu navigating the sections
 * (Appearance, Tiles, Home Assistant, Services, Updates). Stays an overlay
 * over the live dashboard; closes from the Close button, a click on the
 * backdrop outside the card, or Escape.
 */
export default function SettingsPanel({
  cfg,
  theme,
  onThemeChange,
  views = normalizeViews(null),
  grid,
  onBoard,
  onIntegrations,
  onClose,
}: Props) {
  const [section, setSection] = useState<SectionId>('appearance')

  // The live-update interval, edited here and persisted server-side. The
  // field adopts the config value only while it still holds the value the
  // config last seeded: the Tiles section's saves refresh the config
  // underneath an open panel (App adopts the answer via onBoard), and
  // that must not clobber an in-progress edit.
  const [seconds, setSeconds] = useState('30')
  const seededRef = useRef('30')
  const [saving, setSaving] = useState(false)
  const [savedMsg, setSavedMsg] = useState<string | null>(null)
  const [saveErr, setSaveErr] = useState<ApiError | null>(null)

  useEffect(() => {
    if (!cfg) return
    const v = String(cfg.board.refresh_seconds || 30)
    if (seconds === seededRef.current) {
      seededRef.current = v
      setSeconds(v)
    }
    // `seconds` is deliberately not a dependency: the field is adopted
    // only on config changes, and re-running on every keystroke would
    // re-seed an untouched field mid-typing.
  }, [cfg])

  // Escape closes the page, wherever the focus is.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // A press that starts on the backdrop (the overlay area outside the
  // card) closes the page; a press that starts inside the card keeps it
  // open even when the click lands on the backdrop (a text-selection drag
  // releases its click on the common ancestor, the backdrop).
  const pressOnBackdrop = useRef(false)
  const onBackdropPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    pressOnBackdrop.current = e.target === e.currentTarget
  }
  const onBackdropClick = (e: MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget && pressOnBackdrop.current) onClose()
  }

  const save = async (e: FormEvent) => {
    e.preventDefault()
    const n = Number(seconds)
    if (!Number.isInteger(n)) {
      setSaveErr(new ApiError(0, 'bad_value', 'Enter a whole number of seconds.'))
      return
    }
    setSaving(true)
    setSaveErr(null)
    setSavedMsg(null)
    try {
      const board = await api.setRefreshSeconds(n)
      const applied = board.refresh_seconds || n
      setSeconds(String(applied))
      seededRef.current = String(applied)
      setSavedMsg(`Saved — the Hermes push panel now polls every ${applied} seconds.`)
      // Let the polling panel (Hermes push) re-arm its timer without a reload.
      emitRefreshSeconds(applied)
    } catch (err) {
      setSaveErr(
        err instanceof ApiError ? err : new ApiError(0, 'save_failed', String(err)),
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="settings-wrap" onPointerDown={onBackdropPointerDown} onClick={onBackdropClick}>
      <section className="card settings" role="dialog" aria-label="Settings">
        <div className="card-head">
          <h2>Settings</h2>
          <button className="btn ghost small" onClick={onClose}>
            Close
          </button>
        </div>

        <div className="settings-page-body">
          <nav className="settings-nav" aria-label="Settings sections">
            {SECTIONS.map((s) => (
              <button
                key={s.id}
                className={section === s.id ? 'on' : ''}
                aria-current={section === s.id ? 'page' : undefined}
                onClick={() => setSection(s.id)}
              >
                {s.label}
              </button>
            ))}
          </nav>

          <div className="settings-content">
            {section === 'appearance' && (
              <section className="settings-section">
                <h3>Appearance</h3>
                <div className="settings-row">
                  <div>
                    <div className="settings-row-label">Theme</div>
                    <div className="settings-row-desc">
                      Follows your OS preference until you pick one here.
                    </div>
                  </div>
                  <div className="tabs" role="group" aria-label="Theme">
                    <button
                      className={theme === 'light' ? 'tab on' : 'tab'}
                      onClick={() => onThemeChange('light')}
                      aria-pressed={theme === 'light'}
                    >
                      Light
                    </button>
                    <button
                      className={theme === 'dark' ? 'tab on' : 'tab'}
                      onClick={() => onThemeChange('dark')}
                      aria-pressed={theme === 'dark'}
                    >
                      Dark
                    </button>
                  </div>
                </div>
              </section>
            )}

            {section === 'tiles' && <TilesSection views={views} grid={grid} onBoard={onBoard} />}

            {section === 'ha' && <HASection views={views} grid={grid} onBoard={onBoard} />}

            {section === 'services' && (
              <ServicesSection cfg={cfg} onBoard={onBoard} onIntegrations={onIntegrations} />
            )}

            {section === 'updates' && (
              <section className="settings-section">
                <h3>Updates</h3>
                <div className="settings-row">
                  <div>
                    <div className="settings-row-label">Live-update interval</div>
                    <div className="settings-row-desc">
                      How often the Hermes push panel re-fetches from the backend
                      (5–3600 seconds). Saved to the dashboard config; the panel
                      adopts the new cadence immediately.
                    </div>
                  </div>
                  <form className="settings-interval" onSubmit={save}>
                    <input
                      className="num-input"
                      type="number"
                      min={5}
                      max={3600}
                      step={1}
                      inputMode="numeric"
                      value={seconds}
                      onChange={(e) => setSeconds(e.target.value)}
                      aria-label="Refresh interval in seconds"
                    />
                    <span className="settings-interval-unit">s</span>
                    <button className="btn" type="submit" disabled={saving}>
                      {saving ? 'Saving…' : 'Save'}
                    </button>
                  </form>
                </div>
                {savedMsg && <p className="note saved">{savedMsg}</p>}
                {saveErr && (
                  <p className="note err">
                    {saveErr.code} — {saveErr.detail}
                  </p>
                )}
              </section>
            )}
          </div>
        </div>
      </section>
    </div>
  )
}
