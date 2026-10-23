import { useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import type { ApiBoard, ApiConfig, ServiceField, ServiceSettings, ServiceSettingsUpdate } from '../types'

/**
 * Read-only summary of the integrations this deployment actually has
 * configured. Mirrors the `integrations` object of GET /api/config, which
 * exposes booleans only — no secret values ever reach the browser.
 */
function serviceRows(cfg: ApiConfig): {
  label: string
  desc: string
  state: 'ok' | 'pending' | 'off'
  stateText: string
  href?: string
}[] {
  const g = cfg.integrations.google
  const embed = cfg.board.ha_embed ?? null
  const embedPath = embed?.url_path?.trim()
  const embedDesc =
    (embedPath ? `Embeds the \u201c${embedPath}\u201d dashboard.` : 'Embeds the default dashboard.') +
    (embed?.token_url ? ' Token passed in the URL.' : '')
  return [
    {
      label: 'Google Calendar',
      desc: 'Events shown in the Calendar panel.',
      state: !g.configured ? 'off' : g.login_required ? 'pending' : 'ok',
      stateText: !g.configured ? 'Not configured' : g.login_required ? 'Sign-in required' : 'Configured',
      href: g.configured && g.login_required ? g.login_url || '/api/auth/google/login' : undefined,
    },
    {
      label: 'Home Assistant',
      desc: `Real HA dashboard in an iframe in the Home panel. ${embedDesc}`,
      state: cfg.integrations.homeassistant ? 'ok' : 'off',
      stateText: cfg.integrations.homeassistant ? 'Configured' : 'Not configured',
    },
    {
      label: 'Hermes chat',
      desc: 'Chat box in the dashboard.',
      state: cfg.integrations.hermes_chat ? 'ok' : 'off',
      stateText: cfg.integrations.hermes_chat ? 'Configured' : 'Not configured',
    },
    {
      label: 'Hermes push',
      desc: 'Markdown pushed to the dashboard by Hermes.',
      state: cfg.integrations.hermes_push ? 'ok' : 'off',
      stateText: cfg.integrations.hermes_push ? 'Configured' : 'Not configured',
    },
  ]
}

/** A draft of one editable service field: the input's value and whether it
 *  changed since the masked view seeded it. An unchanged field is omitted
 *  from the save; a changed one sends its value (an empty string clears the
 *  UI value back to the .env fallback). */
interface ServiceFieldDraft {
  value: string
  dirty: boolean
}

/** Seed the drafts from the masked view: non-secret fields carry their
 *  UI-set value; secret fields never expose a value, so their draft starts
 *  empty (a typed value replaces the current one on save). */
function seedServiceDrafts(s: ServiceSettings): Record<string, ServiceFieldDraft> {
  const d: Record<string, ServiceFieldDraft> = {}
  const put = (key: string, f: ServiceField, secret: boolean) => {
    d[key] = { value: secret ? '' : f.value, dirty: false }
  }
  put('homeassistant.url', s.homeassistant.url, false)
  put('homeassistant.token', s.homeassistant.token, true)
  put('google.service_account_file', s.google.service_account_file, false)
  put('google.domain', s.google.domain, false)
  put('google.client_id', s.google.client_id, false)
  put('google.client_secret', s.google.client_secret, true)
  put('google.redirect_uri', s.google.redirect_uri, false)
  put('google.token_cache', s.google.token_cache, false)
  put('google.access_token', s.google.access_token, true)
  put('google.endpoint', s.google.endpoint, false)
  put('hermes_chat.base_url', s.hermes_chat.base_url, false)
  put('hermes_chat.chat_path', s.hermes_chat.chat_path, false)
  put('hermes_chat.auth_header', s.hermes_chat.auth_header, false)
  put('hermes_chat.auth_value', s.hermes_chat.auth_value, true)
  put('hermes_push.token', s.hermes_push.token, true)
  return d
}

/** The partial update a save sends: only the changed fields, grouped per
 *  integration (a group with no changed field is omitted wholesale, and so
 *  is an unchanged field within a group — the backend keeps omitted values). */
function serviceUpdate(drafts: Record<string, ServiceFieldDraft>): ServiceSettingsUpdate {
  const v = (key: string): string | null =>
    drafts[key]?.dirty ? drafts[key].value : null
  const ha = { url: v('homeassistant.url'), token: v('homeassistant.token') }
  const g = {
    service_account_file: v('google.service_account_file'),
    domain: v('google.domain'),
    client_id: v('google.client_id'),
    client_secret: v('google.client_secret'),
    redirect_uri: v('google.redirect_uri'),
    token_cache: v('google.token_cache'),
    access_token: v('google.access_token'),
    endpoint: v('google.endpoint'),
  }
  const hc = {
    base_url: v('hermes_chat.base_url'),
    chat_path: v('hermes_chat.chat_path'),
    auth_header: v('hermes_chat.auth_header'),
    auth_value: v('hermes_chat.auth_value'),
  }
  const hp = { token: v('hermes_push.token') }
  const any = (o: Record<string, string | null>) =>
    Object.values(o).some((x) => x !== null)
  const update: ServiceSettingsUpdate = {}
  if (any(ha)) update.homeassistant = ha
  if (any(g)) update.google = g
  if (any(hc)) update.hermes_chat = hc
  if (any(hp)) update.hermes_push = hp
  return update
}

/**
 * The Services section: the in-use integrations and their status, plus the
 * service settings — each integration's connection fields, editable here and
 * persisted to the board file (board.services), where they override the .env
 * values (settings UI > .env > default) and take effect on the next request,
 * without a restart. Secret fields never expose a value to the browser: the
 * masked view reports only whether each layer set one, and the input accepts
 * a replacement (or a clear back to the .env fallback).
 */
export default function SettingsServicesSection({
  cfg,
  onBoard,
  onIntegrations,
}: {
  cfg: ApiConfig | null
  onBoard?: (board: ApiBoard) => void
  onIntegrations?: (integrations: ApiConfig['integrations']) => void
}) {
  const [services, setServices] = useState<ServiceSettings | null>(null)
  const [drafts, setDrafts] = useState<Record<string, ServiceFieldDraft> | null>(null)
  const [busy, setBusy] = useState(false)
  const [savedMsg, setSavedMsg] = useState('')
  const [loadErr, setLoadErr] = useState<ApiError | null>(null)
  const [saveErr, setSaveErr] = useState<ApiError | null>(null)

  // The masked service view (the settings-UI values plus the .env
  // fallbacks; secrets reduced to set booleans).
  useEffect(() => {
    let on = true
    api
      .getServices()
      .then((s) => {
        if (!on) return
        setServices(s)
        setDrafts(seedServiceDrafts(s))
      })
      .catch((e) => {
        if (!on) return
        setLoadErr(e instanceof ApiError ? e : new ApiError(0, 'load_failed', String(e)))
      })
    return () => {
      on = false
    }
  }, [])

  const setField = (key: string, value: string) => {
    setDrafts((d) => (d ? { ...d, [key]: { value, dirty: true } } : d))
  }

  const dirtyCount = drafts ? Object.values(drafts).filter((d) => d.dirty).length : 0

  const save = () => {
    if (busy || !drafts) return
    setBusy(true)
    setSaveErr(null)
    setSavedMsg('')
    api
      .setServices(serviceUpdate(drafts))
      .then((res) => {
        onBoard?.(res.board)
        onIntegrations?.(res.integrations)
        setServices(res.services)
        setDrafts(seedServiceDrafts(res.services))
        setSavedMsg('Service settings saved — the new values are live now.')
      })
      .catch((e) => {
        setSaveErr(e instanceof ApiError ? e : new ApiError(0, 'save_failed', String(e)))
      })
      .finally(() => setBusy(false))
  }

  // One editable field row: the label and description on the left, the
  // input on the right. Non-secret fields seed the input with the value
  // set in the UI (the .env/default fallback shows as the placeholder);
  // secret fields never expose a value — the pill reports which layer set
  // one, the input accepts a replacement, and Clear (while the UI set one)
  // sends the empty string back to the .env fallback.
  const fieldRow = (
    key: string,
    label: string,
    desc: string,
    f: ServiceField,
    secret: boolean,
    type: 'text' | 'password' = 'text',
  ) => {
    const draft = drafts?.[key]
    if (!draft) return null
    const setInEnv = f.fallback_set
    const status = f.set
      ? 'Set in the settings UI'
      : setInEnv
        ? 'Set in .env'
        : 'Not set'
    return (
      <div className="settings-row">
        <div>
          <div className="settings-row-label">{label}</div>
          {desc !== '' && <div className="settings-row-desc">{desc}</div>}
        </div>
        <div className="service-field-inputs">
          {secret && (
            <span className={`dot ${f.set || setInEnv ? 'ok' : 'off'}`} title={status}>
              {status}
            </span>
          )}
          <input
            className="service-input"
            type={type}
            value={draft.value}
            placeholder={secret ? '' : f.fallback}
            disabled={busy}
            aria-label={label}
            onChange={(e) => setField(key, e.target.value)}
          />
          {secret && f.set && (
            <button
              type="button"
              className="btn small"
              disabled={busy}
              onClick={() => setField(key, '')}
            >
              Clear
            </button>
          )}
        </div>
      </div>
    )
  }

  return (
    <section className="settings-section">
      <h3>Services in use</h3>
      {cfg === null ? (
        <p className="note">Loading…</p>
      ) : (
        serviceRows(cfg).map((row) => (
          <div className="settings-row" key={row.label}>
            <div>
              <div className="settings-row-label">{row.label}</div>
              <div className="settings-row-desc">{row.desc}</div>
            </div>
            {row.state === 'ok' && <span className="dot ok">{row.stateText}</span>}
            {row.state === 'pending' && (
              <span className="dot warn">
                {row.href ? (
                  <a href={row.href} target="_blank" rel="noreferrer">
                    {row.stateText}
                  </a>
                ) : (
                  row.stateText
                )}
              </span>
            )}
            {row.state === 'off' && <span className="dot off">{row.stateText}</span>}
          </div>
        ))
      )}

      <h3>Service settings</h3>
      <p className="note">
        Stored in the dashboard config file, where they override the .env values
        (settings UI &gt; .env &gt; default). Changes are live on the next request —
        no restart. Secret fields show only whether a value is set; saving a new
        value replaces the current one.
      </p>
      {services === null || drafts === null ? (
        loadErr ? (
          <p className="note err">
            {loadErr.code} — {loadErr.detail}
          </p>
        ) : (
          <p className="note">Loading…</p>
        )
      ) : (
        <>
          <h3>Home Assistant settings</h3>
          {fieldRow(
            'homeassistant.url',
            'Server URL',
            'Base URL of the Home Assistant server, e.g. https://ha.example.com.',
            services.homeassistant.url,
            false,
          )}
          {fieldRow(
            'homeassistant.token',
            'Access token',
            'Long-lived access token (HA → Profile → Security).',
            services.homeassistant.token,
            true,
            'password',
          )}

          <h3>Google Calendar settings</h3>
          <p className="note">
            Credential modes, when several are set: static access token, then
            service account, then the OAuth client.
          </p>
          {fieldRow(
            'google.service_account_file',
            'Service account key',
            'Key file path of the Google service account (a file the backend can read).',
            services.google.service_account_file,
            false,
          )}
          {fieldRow(
            'google.domain',
            'Domain',
            'Workspace domain for domain-wide delegation, e.g. user@workspace.com.',
            services.google.domain,
            false,
          )}
          {fieldRow(
            'google.client_id',
            'OAuth client ID',
            'The OAuth client used for the one-time sign-in.',
            services.google.client_id,
            false,
          )}
          {fieldRow('google.client_secret', 'OAuth client secret', '', services.google.client_secret, true, 'password')}
          {fieldRow(
            'google.redirect_uri',
            'OAuth redirect URI',
            'The redirect URI the sign-in flow returns to.',
            services.google.redirect_uri,
            false,
          )}
          {fieldRow(
            'google.token_cache',
            'Token cache',
            'Path of the OAuth token cache file.',
            services.google.token_cache,
            false,
          )}
          {fieldRow(
            'google.access_token',
            'Static access token',
            'A Calendar API access token (development).',
            services.google.access_token,
            true,
            'password',
          )}
          {fieldRow(
            'google.endpoint',
            'API endpoint',
            'Calendar API base URL override (advanced).',
            services.google.endpoint,
            false,
          )}

          <h3>Hermes chat settings</h3>
          {fieldRow(
            'hermes_chat.base_url',
            'Base URL',
            'Base URL of the Hermes API (no trailing slash).',
            services.hermes_chat.base_url,
            false,
          )}
          {fieldRow(
            'hermes_chat.chat_path',
            'Chat path',
            'Chat endpoint path.',
            services.hermes_chat.chat_path,
            false,
          )}
          {fieldRow(
            'hermes_chat.auth_header',
            'Auth header',
            'Header name to send.',
            services.hermes_chat.auth_header,
            false,
          )}
          {fieldRow(
            'hermes_chat.auth_value',
            'Auth value',
            'Full header value, e.g. Bearer sk-… (empty = no auth).',
            services.hermes_chat.auth_value,
            true,
            'password',
          )}

          <h3>Hermes push settings</h3>
          {fieldRow(
            'hermes_push.token',
            'Push token',
            'Shared secret Hermes must present (empty = push disabled).',
            services.hermes_push.token,
            true,
            'password',
          )}

          <form
            onSubmit={(e) => {
              e.preventDefault()
              save()
            }}
          >
            <button className="btn" type="submit" disabled={busy || dirtyCount === 0}>
              {busy ? 'Saving…' : 'Save'}
            </button>
          </form>
          {savedMsg && <p className="note saved">{savedMsg}</p>}
          {saveErr && (
            <p className="note err">
              {saveErr.code} — {saveErr.detail}
            </p>
          )}
        </>
      )}
    </section>
  )
}
