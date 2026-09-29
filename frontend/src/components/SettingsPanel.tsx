import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api'
import { emitRefreshSeconds } from '../refreshBus'
import type { ApiConfig } from '../types'
import type { Theme } from '../theme'

interface Props {
  cfg: ApiConfig | null
  theme: Theme
  onThemeChange: (t: Theme) => void
  onClose: () => void
}

type ServiceState = 'ok' | 'pending' | 'off'

interface ServiceRow {
  label: string
  desc: string
  state: ServiceState
  stateText: string
  href?: string
}

/**
 * Read-only summary of the integrations this deployment actually has
 * configured. Mirrors the `integrations` object of GET /api/config, which
 * exposes booleans only — no secret values ever reach the browser.
 */
function serviceRows(cfg: ApiConfig): ServiceRow[] {
  const g = cfg.integrations.google
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
      desc: 'Entity states shown in the Home panel.',
      state: cfg.integrations.homeassistant ? 'ok' : 'off',
      stateText: cfg.integrations.homeassistant ? 'Configured' : 'Not configured',
    },
    {
      label: 'Hermes chat',
      desc: 'Chat box in the side panel.',
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

export default function SettingsPanel({ cfg, theme, onThemeChange, onClose }: Props) {
  // Live-update interval, edited here and persisted server-side.
  const [seconds, setSeconds] = useState('30')
  const [saving, setSaving] = useState(false)
  const [savedMsg, setSavedMsg] = useState<string | null>(null)
  const [saveErr, setSaveErr] = useState<ApiError | null>(null)

  useEffect(() => {
    if (cfg) setSeconds(String(cfg.board.refresh_seconds || 30))
  }, [cfg])

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
      setSavedMsg(`Saved — panels now poll every ${applied} seconds.`)
      // Let the polling panels re-arm their timers without a reload.
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
    <main className="settings-wrap">
      <section className="card settings">
        <div className="card-head">
          <h2>Settings</h2>
          <button className="btn ghost small" onClick={onClose}>
            Close
          </button>
        </div>

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

        <section className="settings-section">
          <h3>Updates</h3>
          <div className="settings-row">
            <div>
              <div className="settings-row-label">Live-update interval</div>
              <div className="settings-row-desc">
                How often the panels re-fetch from their services (5–3600
                seconds). Saved to the dashboard config; panels adopt the new
                cadence immediately.
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
      </section>
    </main>
  )
}
