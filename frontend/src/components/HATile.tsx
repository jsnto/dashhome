import { useState } from 'react'
import { ApiError, api, type HAEntity, type HAServiceAction } from '../api'
import type { HABoardTile } from '../types'
import { haTileLabel, haTileState } from '../utils'

interface Props {
  tile: HABoardTile
  /** The entity's current state from the polled /api/ha/entities, or null when unknown. */
  entity: HAEntity | null
  /** True once a poll has reported Home Assistant is not configured. */
  haOff: boolean
  /** Fired after a service action is accepted (the board refreshes the states). */
  onService: () => void
}

/**
 * A Home Assistant board tile: first-class like the view tiles (geometry,
 * order, hide all apply) but bound to one HA entity. `button` tiles fire
 * the board's allowlisted action (toggle) through POST /api/ha/service;
 * `visual` tiles show the entity's state.
 */
export default function HATile({ tile, entity, haOff, onService }: Props) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<ApiError | null>(null)
  const label = haTileLabel(tile, entity)

  const stamp = (ts?: string) => {
    const d = ts ? new Date(ts) : null
    return d && !Number.isNaN(d.getTime())
      ? d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
      : null
  }
  const updated = entity ? stamp(entity.last_changed) ?? stamp(entity.last_updated) : null

  const run = async (action: HAServiceAction) => {
    if (busy || !entity) return
    setBusy(true)
    setErr(null)
    try {
      await api.callHAService(tile.entity, action)
      onService()
    } catch (e) {
      setErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e)))
    } finally {
      setBusy(false)
    }
  }

  const actionDisabled = busy || !entity || haOff
  const actionLabel =
    tile.kind !== 'button'
      ? null
      : entity
        ? entity.state === 'on'
          ? 'Turn off'
          : 'Turn on'
        : 'Toggle'
  // The switch's current state, when the entity reports an on/off one: the
  // button's styling wears it (kept in sync through the tile's existing
  // state refresh) — ON reads dark and offers the turn-off, OFF reads
  // bright and offers the turn-on. Any other state keeps the neutral fill.
  const stateOn = entity?.state === 'on'
  const stateOff = entity?.state === 'off'

  return (
    <section className={`card ha-card ha-${tile.kind}`}>
      <header className="card-head">
        <h2>{label}</h2>
      </header>
      <div className="card-scroll">
        {err ? (
          <p className="note err">
            {err.code}
            {err.detail ? ` — ${err.detail}` : ''}
          </p>
        ) : null}
        {tile.kind === 'button' ? (
          <button
            className={[
              'ha-action',
              stateOn ? 'ha-action-on' : '',
              stateOff ? 'ha-action-off' : '',
            ]
              .filter(Boolean)
              .join(' ')}
            disabled={actionDisabled}
            onClick={() => run('toggle')}
          >
            {actionLabel}
          </button>
        ) : (
          <div className="ha-visual">
            <span className="ha-state">{entity ? haTileState(entity.state) : haOff ? 'Not connected' : '—'}</span>
            {updated ? <span className="ha-updated">{updated}</span> : null}
          </div>
        )}
        {haOff ? (
          <p className="note">
            Home Assistant is not configured. Set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN and
            restart the server.
          </p>
        ) : null}
        <p className="note ha-entity" title={tile.entity}>
          {tile.entity}
        </p>
      </div>
    </section>
  )
}
