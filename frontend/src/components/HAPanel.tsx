import { useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import HAFullPanel from './HAFullPanel'
import Note from './Note'

/**
 * The "Home" section: the real Home Assistant dashboard embedded in an
 * iframe (HAFullPanel). The board view and its entity polling are gone —
 * this component owns the card shell and the header: the "Home" title plus
 * the embed actions ("Open in new tab", Reload) on its right. It also owns
 * the embed-URL lifecycle: the URL comes from /api/ha/embed-url (assembled
 * server-side), and Reload re-fetches it and remounts the frame via a key
 * so the iframe reloads with the fresh URL.
 */
export default function HAPanel() {
  const [url, setUrl] = useState<string | null>(null)
  const [err, setErr] = useState<ApiError | null>(null)
  const [key, setKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    setErr(null)
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

  return (
    <section className="card">
      <div className="card-head">
        <h2>Home</h2>
        {url && !err && (
          <div className="card-head-actions">
            <a className="btn small" href={url} target="_blank" rel="noreferrer">
              Open in new tab
            </a>
            <button className="btn ghost small" onClick={() => setKey((k) => k + 1)}>
              Reload
            </button>
          </div>
        )}
      </div>
      {err ? (
        <Note err={err} />
      ) : url ? (
        <HAFullPanel key={key} url={url} />
      ) : (
        <p className="note">Loading…</p>
      )}
    </section>
  )
}
