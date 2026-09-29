import { useEffect, useState } from 'react'
import { api } from './api'
import type { ApiConfig } from './types'
import { useTheme } from './useTheme'
import CalendarSection from './components/CalendarSection'
import ChatPanel from './components/ChatPanel'
import FullscreenButton from './components/FullscreenButton'
import HAPanel from './components/HAPanel'
import PushPanel from './components/PushPanel'
import SettingsPanel from './components/SettingsPanel'

export default function App() {
  const [cfg, setCfg] = useState<ApiConfig | null>(null)
  const [theme, setTheme] = useTheme()
  const [showSettings, setShowSettings] = useState(false)

  useEffect(() => {
    api
      .getConfig()
      .then(setCfg)
      .catch(() => {})
  }, [])

  useEffect(() => {
    if (!showSettings) return
    api
      .getConfig()
      .then(setCfg)
      .catch(() => {})
  }, [showSettings])

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
        <div className="head-actions">
          <div className="dots">
            {dots.map((d) => (
              <span key={d.title} className={`dot ${d.ok ? 'ok' : 'off'}`} title={d.title}>
                {d.label}
              </span>
            ))}
          </div>
          <button
            className="btn ghost small"
            onClick={() => setShowSettings((s) => !s)}
            aria-pressed={showSettings}
          >
            Settings
          </button>
          <FullscreenButton />
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

      {showSettings && (
        <SettingsPanel
          cfg={cfg}
          theme={theme}
          onThemeChange={setTheme}
          onClose={() => setShowSettings(false)}
        />
      )}

      <footer className="foot">dashhome — your home, on one dashboard</footer>
    </div>
  )
}
