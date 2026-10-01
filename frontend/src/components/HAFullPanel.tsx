import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import Note from './Note'

/**
 * The "Home" section: the real Home Assistant dashboard in an iframe,
 * pointing at the viewer's browser's own HA origin (the URL comes from
 * /api/ha/embed-url, assembled server-side). The section's card shell and
 * header live in HAPanel; this component owns the embed bar (note,
 * "Open in new tab", Reload) and the iframe.
 *
 * A cross-origin frame can fail in ways the page cannot see — the browser
 * renders its own error page (e.g. "10.10.20.243 refused the connection")
 * inside the iframe and fires no error event — so the bar carries a static
 * note pointing at the Open-in-new-tab path as the way out.
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
          it asks. If the frame shows a connection error, use Open in new tab
          (same URL).
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
