import type { FormEvent } from 'react'
import type { ApiError } from '../api'

/**
 * The Updates section: the live-update interval — how often the Hermes push
 * panel re-fetches from the backend. Saved to the dashboard config
 * (board.refresh_seconds) server-side; the panel adopts the new cadence
 * immediately (the refresh bus, no reload).
 *
 * The interval field's state (the current value, the save round-trip) is
 * owned by the settings page (SettingsPanel): it must survive switching
 * sections, and the page re-seeds it only while it still holds the value
 * the config last seeded (the page's saves of other sections refresh the
 * config underneath the open panel, and that must not clobber an
 * in-progress edit). This component is presentational.
 */
export default function SettingsUpdatesSection({
  seconds,
  saving,
  savedMsg,
  saveErr,
  onSecondsChange,
  onSubmit,
}: {
  seconds: string
  saving: boolean
  savedMsg: string | null
  saveErr: ApiError | null
  onSecondsChange: (v: string) => void
  onSubmit: (e: FormEvent) => void
}) {
  return (
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
        <form className="settings-interval" onSubmit={onSubmit}>
          <input
            className="num-input"
            type="number"
            min={5}
            max={3600}
            step={1}
            inputMode="numeric"
            value={seconds}
            onChange={(e) => onSecondsChange(e.target.value)}
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
  )
}
