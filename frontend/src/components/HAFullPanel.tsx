import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import Note from './Note'

/**
 * How long to wait for the iframe's load event before surfacing a failure
 * note. A frame that never finishes loading (blackholed port, hung
 * handshake) is reported instead of being left as a silent blank card.
 */
export const HA_LOAD_WATCHDOG_MS = 10000

/**
 * The "Home" section: the real Home Assistant dashboard in an iframe,
 * pointing at the viewer's browser's own HA origin (the URL comes from
 * /api/ha/embed-url, assembled server-side). The section's card shell and
 * header live in HAPanel; this component owns the embed bar (note,
 * "Open in new tab", Reload) and the load-failure notes.
 *
 * A cross-origin frame can fail in ways the page cannot see — the browser
 * renders its own error page (e.g. "10.10.20.243 refused the connection")
 * inside the iframe and fires no error event. So the panel detects the
 * detectable failure modes and surfaces them as an actionable note with
 * the URL and the open-in-new-tab path:
 *
 *  - a load watchdog: the iframe's `load` event must fire;
 *  - a no-cors probe that replays the frame's (unauthenticated) request
 *    with fetch: it resolves if the browser can complete the request and
 *    rejects on a network error — e.g. a refused hop in an auth-redirect
 *    chain — exactly the failure a frame would show as a bare error page.
 *    (X-Frame-Options blocks framing only, not fetch, so they do not trip
 *    the probe; for those, the open-in-new-tab path is the workaround.)
 */
export default function HAFullPanel() {
  const [url, setUrl] = useState<string | null>(null)
  const [err, setErr] = useState<ApiError | null>(null)
  const [key, setKey] = useState(0)
  const [timedOut, setTimedOut] = useState(false)
  const [probeFailed, setProbeFailed] = useState(false)

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

  useEffect(() => {
    if (!url) return
    let cancelled = false
    setTimedOut(false)
    setProbeFailed(false)

    const id = window.setTimeout(() => {
      if (!cancelled) setTimedOut(true)
    }, HA_LOAD_WATCHDOG_MS)

    fetch(url, { mode: 'no-cors', credentials: 'include' })
      .then(() => {
        if (!cancelled) setProbeFailed(false)
      })
      .catch(() => {
        if (!cancelled) setProbeFailed(true)
      })

    return () => {
      cancelled = true
      window.clearTimeout(id)
    }
  }, [url, key])

  // The load event clears the watchdog. (The probe result is sticky per
  // load attempt: when it rejects, the frame shows its own error page, so
  // the note stays until the next Reload.)
  const onIframeLoad = () => {
    setTimedOut(false)
  }

  if (err) return <Note err={err} />
  if (!url) return <p className="note">Loading…</p>

  const showFailNote = probeFailed || timedOut

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
      {showFailNote && (
        <p className="note err ha-load-note" role="alert">
          Home Assistant did not load in this frame. If the frame shows a
          'refused to connect' page, the browser could not complete the
          request from this frame — open{' '}
          <a href={url} target="_blank" rel="noreferrer">
            {url}
          </a>{' '}
          in a new tab (a top-level page is not subject to cross-origin
          framing rules), or Reload.
        </p>
      )}
      <iframe
        key={key}
        className="ha-iframe"
        src={url}
        title="Home Assistant dashboard"
        loading="lazy"
        referrerPolicy="no-referrer"
        onLoad={onIframeLoad}
      />
    </div>
  )
}
