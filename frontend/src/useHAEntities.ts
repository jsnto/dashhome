import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api, type HAEntity } from './api'

/** How often the board polls Home Assistant entity states. */
const POLL_MS = 10_000

export interface HAEntitiesState {
  /** Entity states by entity_id (only the entities the board shows need, but the poll is the same). */
  entities: Record<string, HAEntity>
  /** True once a poll has reported Home Assistant is not configured. */
  haOff: boolean
  /** Fire an immediate poll (e.g. right after a button tile's action landed). */
  refresh: () => void
}

/**
 * Poll /api/ha/entities while `active` (any Home Assistant tile is
 * visible): on mount, on a timer, and when the page regains visibility.
 * The 503 "not configured" is reported via `haOff` rather than an error —
 * the tile explains the state instead of failing.
 */
export function useHAEntities(active: boolean): HAEntitiesState {
  const [entities, setEntities] = useState<Record<string, HAEntity>>({})
  const [haOff, setHaOff] = useState(false)
  const seq = useRef(0)

  const load = useCallback(async () => {
    const n = ++seq.current
    try {
      const r = await api.getHAEntities()
      if (seq.current !== n) return
      const map: Record<string, HAEntity> = {}
      for (const e of r.entities) map[e.entity_id] = e
      setEntities(map)
      setHaOff(false)
    } catch (err) {
      if (seq.current !== n) return
      if (err instanceof ApiError && err.code === 'homeassistant_not_configured') {
        setHaOff(true)
      }
      // Transient 502s (HA unreachable) keep the last good states.
    }
  }, [])

  const refresh = useCallback(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (!active) return
    load()
    const iv = setInterval(load, POLL_MS)
    const onVis = () => {
      if (document.visibilityState === 'visible') load()
    }
    document.addEventListener('visibilitychange', onVis)
    return () => {
      clearInterval(iv)
      document.removeEventListener('visibilitychange', onVis)
    }
  }, [active, load])

  return { entities, haOff, refresh }
}
