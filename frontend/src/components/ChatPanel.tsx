import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api'
import { speak, useVoice } from '../useVoice'
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

  /** Shared send path for typed and spoken messages: same request, same thread. */
  const sendText = (text: string) => {
    if (busy) return
    const history: ChatMessage[] = msgs.map(({ role, content }) => ({ role, content }))
    setMsgs((m) => [...m, { id: idRef.current++, role: 'user', content: text }])
    setBusy(true)
    setCfgErr(null)
    ;(async () => {
      try {
        const reply = await api.chat(text, history)
        setMsgs((m) => [...m, { id: idRef.current++, role: 'assistant', content: reply }])
        speak(reply) // read the reply aloud so the voice loop is complete (best-effort)
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
    })()
  }

  const send = (e: FormEvent) => {
    e.preventDefault()
    const text = input.trim()
    if (!text) return
    setInput('')
    sendText(text)
  }

  const { supported, listening, interim, blocked, toggle } = useVoice(sendText)
  const micDisabled = !supported || blocked !== null || busy
  const micTitle = !supported
    ? 'Voice input is not supported in this browser'
    : blocked
      ? blocked
      : listening
        ? 'Stop listening'
        : 'Speak a command'

  return (
    <section className="card chat">
      <div className="card-head">
        <h2>Hermes</h2>
        {listening && <span className="note">Listening{interim ? ` — ${interim}` : '…'}</span>}
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
        <button
          type="button"
          className={`btn mic ${listening ? 'listening' : ''}`}
          onClick={toggle}
          disabled={micDisabled}
          title={micTitle}
          aria-label={listening ? 'Stop voice input' : 'Voice input'}
          aria-pressed={listening}
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <rect x="9" y="2" width="6" height="12" rx="3" />
            <path d="M5 10v1a7 7 0 0 0 14 0v-1M12 18v3" />
          </svg>
        </button>
        <button className="btn" type="submit" disabled={busy || !input.trim()}>
          Send
        </button>
      </form>
    </section>
  )
}
