import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api'
import type { ChatMessage } from '../types'
import Note from './Note'

interface UiMsg extends ChatMessage {
  id: number
  error?: boolean
}

export default function ChatPanel() {
  const [msgs, setMsgs] = useState<UiMsg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [cfgErr, setCfgErr] = useState<ApiError | null>(null)
  const endRef = useRef<HTMLDivElement>(null)
  const idRef = useRef(0)

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [msgs, busy])

  const send = async (e: FormEvent) => {
    e.preventDefault()
    const text = input.trim()
    if (!text || busy) return
    const history: ChatMessage[] = msgs.map(({ role, content }) => ({ role, content }))
    setMsgs((m) => [...m, { id: idRef.current++, role: 'user', content: text }])
    setInput('')
    setBusy(true)
    setCfgErr(null)
    try {
      const reply = await api.chat(text, history)
      setMsgs((m) => [...m, { id: idRef.current++, role: 'assistant', content: reply }])
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `${err.code}${err.detail ? ` — ${err.detail}` : ''}`
          : String(err)
      setCfgErr(err instanceof ApiError ? err : null)
      setMsgs((m) => [...m, { id: idRef.current++, role: 'assistant', content: msg, error: true }])
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card chat">
      <div className="card-head">
        <h2>Hermes</h2>
        {busy && <span className="note">thinking…</span>}
      </div>
      {cfgErr && <Note err={cfgErr} />}
      <div className="chat-log">
        {msgs.length === 0 && (
          <p className="note">
            Say hi to Hermes — the conversation thread stays on this page.
          </p>
        )}
        {msgs.map((m) => (
          <div key={m.id} className={`msg ${m.role} ${m.error ? 'error' : ''}`}>
            <span className="msg-role">{m.role === 'user' ? 'You' : 'Hermes'}</span>
            <span className="msg-text">{m.content}</span>
          </div>
        ))}
        <div ref={endRef} />
      </div>
      <form className="chat-form" onSubmit={send}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Message Hermes…"
          disabled={busy}
          autoComplete="off"
        />
        <button className="btn" type="submit" disabled={busy || !input.trim()}>
          Send
        </button>
      </form>
    </section>
  )
}
