import { useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import { onRefreshSeconds } from '../refreshBus'
import type { HAEntity } from '../types'
import HAFullPanel from './HAFullPanel'
import Note from './Note'

function stateClass(state: string): string {
  const s = state.toLowerCase()
  if (['on', 'open', 'unlocked', 'off', 'closed', 'locked'].includes(s)) {
    return ['on', 'open', 'unlocked'].includes(s) ? 'good' : 'mid'
  }
  if (s === 'unavailable' || s === 'unknown') return 'dim'
  return 'num'
}

export default function HAPanel() {
  const [entities, setEntities] = useState<HAEntity[] | null>(null)
  const [err, setErr] = useState<ApiError | null>(null)
  const [refreshSec, setRefreshSec] = useState(30)
  const [refreshKey, setRefreshKey] = useState(0)
  const [haConfigured, setHaConfigured] = useState(false)
  // 'board' is the default view (the styled at-a-glance grid); 'full' lazy-
  // mounts the real HA dashboard in an iframe.
  const [tab, setTab] = useState<'board' | 'full'>('board')

  useEffect(() => {
    api
      .getConfig()
      .then((c) => {
        setRefreshSec(c.board.refresh_seconds || 30)
        setHaConfigured(c.integrations.homeassistant)
      })
      .catch(() => {})
  }, [])

  // Adopt a new interval saved in Settings without a reload.
  useEffect(() => onRefreshSeconds(setRefreshSec), [])

  useEffect(() => {
    let cancelled = false
    const load = () => {
      api
        .getHAEntities()
        .then((es) => {
          if (cancelled) return
          setEntities(es)
          setErr(null)
        })
        .catch((e: unknown) => {
          if (!cancelled && e instanceof ApiError) setErr(e)
        })
    }
    load()
    const id = window.setInterval(load, refreshSec * 1000)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [refreshSec, refreshKey])

  return (
    <section className="card">
      <div className="card-head">
        <h2>Home</h2>
        {haConfigured && (
          <div className="tabs tabs-small" role="group" aria-label="Home view">
            <button
              className={tab === 'board' ? 'tab on' : 'tab'}
              onClick={() => setTab('board')}
              aria-pressed={tab === 'board'}
            >
              Board
            </button>
            <button
              className={tab === 'full' ? 'tab on' : 'tab'}
              onClick={() => setTab('full')}
              aria-pressed={tab === 'full'}
            >
              Full
            </button>
          </div>
        )}
        <button className="btn ghost small" onClick={() => setRefreshKey((k) => k + 1)}>
          Refresh
        </button>
      </div>
      {tab === 'full' && haConfigured ? (
        <HAFullPanel />
      ) : err ? (
        <Note err={err} />
      ) : entities === null ? (
        <p className="note">Loading…</p>
      ) : entities.length === 0 ? (
        <p className="note">No Home Assistant entities configured.</p>
      ) : (
        <div className="ha-grid">
          {entities.map((e) => {
            const unit = e.attributes?.unit_of_measurement
            return (
              <div className="ha-card" key={e.entity_id} title={e.entity_id}>
                <div className="ha-state-wrap">
                  <span className={`ha-state ${stateClass(e.state)}`}>{e.state}</span>
                  {unit != null && <span className="ha-unit">{String(unit)}</span>}
                </div>
                <div className="ha-label">{e.label}</div>
                {e.last_changed && (
                  <div className="ha-meta">
                    {new Date(e.last_changed).toLocaleString(undefined, {
                      month: 'short',
                      day: 'numeric',
                      hour: 'numeric',
                      minute: '2-digit',
                    })}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}
