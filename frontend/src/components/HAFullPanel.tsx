import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import Note from './Note'

/**
 * The "Home (full)" section: the real Home Assistant dashboard in an
 * iframe, pointing at the viewer's browser's own HA origin (the URL comes
 * from /api/ha/embed-url, assembled server-side). Mounted lazily by
 * HAPanel: the fetch + iframe only exist once the user switches to the
 * Full tab.
 */
export default function HAFullPanel() {
  const [url, setUrl] = useState<string | null>(null)
  const [err, setErr] = useState<ApiError | null>(null)
  const [key, setKey] = useState(0)

  const load = useCallback(() => {
    let cancelled = false
    api
      .getHAAEmbedURL()
      .then((u) => {
        if (!cancelled) setUrl(u)
      })
      .catch((e: unknown) => {
        if (!cancelled && e instanceof ApiError) setErr(e)
      })
    return () => {
      cancelled = true
    }
  }, [key])

  useEffect(() => {
    setErr(null)
    return load()
  }, [load])

  if (err) return <Note err={err} />
  if (!url) return <p className="note">Loading…</p>

  return (
    <div className="ha-full">
      <div className="ha-full-bar">
        <span className="ha-full-note">
          Loads Home Assistant directly in this frame — log in there once if
          it asks.
        </span>
        <div className="ha-full-actions">
          <a className="btn small" href={url} target="_blank" rel="noreferrer">
            Open in new tab
          </a>
          <button className="btn ghost small" onClick={() => setKey((k) => k + 1)}>
            Reload
          </button>
        </div>
      </div>
      <iframe
        key={key}
        className="ha-iframe"
        src={url}
        title="Home Assistant dashboard"
        loading="lazy"
        referrerPolicy="no-referrer"
      />
    </div>
  )
}
