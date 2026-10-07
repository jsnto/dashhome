import { useEffect, useRef, useState, type FormEvent, type MouseEvent, type PointerEvent } from 'react'
import { ApiError, api } from '../api'
import { emitRefreshSeconds } from '../refreshBus'
import type { ApiBoard, ApiConfig, BoardViews, CalendarMode, ViewId } from '../types'
import type { Theme } from '../theme'
import { TILE_SIZE_LABELS, VIEW_LABELS, normalizeViews } from '../utils'

interface Props {
  cfg: ApiConfig | null
  theme: Theme
  onThemeChange: (t: Theme) => void
  /** Effective view layout (defaults already filled in). */
  views?: BoardViews
  /** Adopt a board just persisted server-side (keeps the dashboard in sync). */
  onBoard?: (board: ApiBoard) => void
  onClose: () => void
}

type SectionId = 'appearance' | 'tiles' | 'services' | 'updates'

const SECTIONS: { id: SectionId; label: string }[] = [
  { id: 'appearance', label: 'Appearance' },
  { id: 'tiles', label: 'Tiles' },
  { id: 'services', label: 'Services' },
  { id: 'updates', label: 'Updates' },
]

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
 * (show/hide each of the four), in what board order, and how the Calendar
 * tile renders. Every change is persisted to the board file as it happens
 * (partial updates; the server keeps the fields the request omits). Tile
 * sizes are edited on the board itself (the header's Edit tiles mode),
 * not here; the current size step is shown per tile.
 */
function TilesSection({
  views,
  onBoard,
}: {
  views: BoardViews
  onBoard?: (board: ApiBoard) => void
}) {
  // Optimistic local state: updated immediately, reverted if the save
  // fails, re-synced from the server's answer on success.
  const [local, setLocal] = useState<BoardViews>(views)
  const [saving, setSaving] = useState(false)
  const [err, setErr] = useState<ApiError | null>(null)

  useEffect(() => setLocal(views), [views])

  const apply = async (
    body: { order?: ViewId[]; hidden?: ViewId[]; calendar_mode?: CalendarMode },
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

  const toggleHidden = (id: ViewId) => {
    const nextHidden = local.hidden.includes(id)
      ? local.hidden.filter((h) => h !== id)
      : [...local.hidden, id]
    void apply({ hidden: nextHidden }, { ...local, hidden: nextHidden })
  }

  const move = (id: ViewId, delta: number) => {
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
        save immediately. Resize a tile in place from the board's Edit tiles mode.
      </p>
      {local.order.map((id, i) => {
        const hidden = local.hidden.includes(id)
        return (
          <div className="view-row" key={id}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="view-name">{VIEW_LABELS[id]}</div>
              <div className="view-size">{TILE_SIZE_LABELS[local.sizes[id]]} tile</div>
            </div>
            <div className="view-controls">
              <label className="view-toggle">
                <input
                  type="checkbox"
                  checked={!hidden}
                  disabled={saving}
                  onChange={() => toggleHidden(id)}
                  aria-label={`Show ${VIEW_LABELS[id]}`}
                />
                {hidden ? 'Hidden' : 'Visible'}
              </label>
              <div className="view-move">
                <button
                  className="btn small"
                  disabled={saving || i === 0}
                  onClick={() => move(id, -1)}
                  aria-label={`Move ${VIEW_LABELS[id]} up`}
                  title={`Move ${VIEW_LABELS[id]} up`}
                >
                  ↑
                </button>
                <button
                  className="btn small"
                  disabled={saving || i === local.order.length - 1}
                  onClick={() => move(id, 1)}
                  aria-label={`Move ${VIEW_LABELS[id]} down`}
                  title={`Move ${VIEW_LABELS[id]} down`}
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
            How the Calendar window renders: the month grid, or a schedule summary — a
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
    </section>
  )
}

/**
 * The settings page: a left sidebar menu navigating the sections
 * (Appearance, Tiles, Services, Updates). Stays an overlay over the live
 * dashboard; closes from the Close button, a click on the backdrop outside
 * the card, or Escape.
 */
export default function SettingsPanel({
  cfg,
  theme,
  onThemeChange,
  views = normalizeViews(undefined),
  onBoard,
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

            {section === 'tiles' && <TilesSection views={views} onBoard={onBoard} />}

            {section === 'services' && (
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
              </section>
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
