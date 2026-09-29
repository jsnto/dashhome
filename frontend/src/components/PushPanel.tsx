import { useEffect, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { api } from '../api'
import { onRefreshSeconds } from '../refreshBus'
import type { Push } from '../types'

/**
 * Hermes markdown panel: the latest push is always shown, and previous
 * pushes are browsable in tabs (newest first). Markdown is rendered by
 * react-markdown without raw-HTML support, so pushed content cannot
 * inject HTML.
 */
export default function PushPanel() {
  const [latest, setLatest] = useState<Push | null>(null)
  const [history, setHistory] = useState<Push[]>([])
  const [openId, setOpenId] = useState<string | null>(null)
  const [open, setOpen] = useState<Push | null>(null)
  const [refreshSec, setRefreshSec] = useState(30)

  useEffect(() => {
    api
      .getConfig()
      .then((c) => setRefreshSec(c.board.refresh_seconds || 30))
      .catch(() => {})
  }, [])

  // Adopt a new interval saved in Settings without a reload.
  useEffect(() => onRefreshSeconds(setRefreshSec), [])

  useEffect(() => {
    let cancelled = false
    const load = () => {
      api
        .getLatestPush()
        .then((p) => {
          if (!cancelled) setLatest(p)
        })
        .catch(() => {
          /* no pushes yet (404) */
        })
      api
        .getPushes(50)
        .then((ps) => {
          if (!cancelled) setHistory(ps)
        })
        .catch(() => {})
    }
    load()
    const id = window.setInterval(load, refreshSec * 1000)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [refreshSec])

  const shown = openId !== null ? open : latest

  const openPush = async (p: Push) => {
    if (openId === p.id) {
      setOpenId(null)
      setOpen(null)
      return
    }
    setOpenId(p.id)
    // We may already have the full record from the list; fetch to be sure.
    api
      .getPush(p.id)
      .then(setOpen)
      .catch(() => setOpen(p))
  }

  const fmtShort = (iso: string) =>
    new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })

  return (
    <section className="card">
      <div className="card-head">
        <h2>Hermes push</h2>
        {history.length > 0 && (
          <div className="tabs tabs-small">
            <button
              className={openId === null ? 'tab on' : 'tab'}
              onClick={() => {
                setOpenId(null)
                setOpen(null)
              }}
            >
              Latest
            </button>
            {history.slice(0, 12).map((p) => (
              <button
                key={p.id}
                className={openId === p.id ? 'tab on' : 'tab'}
                onClick={() => openPush(p)}
                title={`${new Date(p.received_at).toLocaleString()}`}
              >
                {fmtShort(p.received_at)}
              </button>
            ))}
          </div>
        )}
      </div>
      {shown ? (
        <div className="push-body">
          <p className="push-meta">
            {new Date(shown.received_at).toLocaleString(undefined, {
              dateStyle: 'medium',
              timeStyle: 'short',
            })}
            {shown.source ? ` · ${shown.source}` : ''}
            {openId !== null ? ' (previous push)' : ' (latest)'}
          </p>
          <ReactMarkdown className="markdown">{shown.markdown}</ReactMarkdown>
        </div>
      ) : (
        <p className="note">Waiting for the first Hermes markdown push…</p>
      )}
    </section>
  )
}
