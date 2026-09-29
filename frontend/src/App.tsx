import { useEffect, useState } from 'react'
import { api } from './api'
import type { ApiConfig } from './types'
import CalendarSection from './components/CalendarSection'
import ChatPanel from './components/ChatPanel'
import HAPanel from './components/HAPanel'
import PushPanel from './components/PushPanel'

export default function App() {
  const [cfg, setCfg] = useState<ApiConfig | null>(null)

  useEffect(() => {
    api
      .getConfig()
      .then(setCfg)
      .catch(() => {})
  }, [])

  const dots: { label: string; ok: boolean; title: string }[] = cfg
    ? [
        {
          label: 'HA',
          ok: cfg.integrations.homeassistant,
          title: 'Home Assistant',
        },
        {
          label: 'Cal',
          ok: cfg.integrations.google.configured,
          title: 'Google Calendar',
        },
        {
          label: 'Chat',
          ok: cfg.integrations.hermes_chat,
          title: 'Hermes chat',
        },
        {
          label: 'Push',
          ok: cfg.integrations.hermes_push,
          title: 'Hermes push',
        },
      ]
    : []

  return (
    <div className="app">
      <header className="top">
        <h1 className="logo">
          dash<span>home</span>
        </h1>
        <div className="dots">
          {dots.map((d) => (
            <span key={d.title} className={`dot ${d.ok ? 'ok' : 'off'}`} title={d.title}>
              {d.label}
            </span>
          ))}
        </div>
      </header>

      <main className="layout">
        <div className="col-main">
          <CalendarSection />
          <HAPanel />
        </div>
        <div className="col-side">
          <ChatPanel />
          <PushPanel />
        </div>
      </main>

      <footer className="foot">dashhome — your home, on one dashboard</footer>
    </div>
  )
}
