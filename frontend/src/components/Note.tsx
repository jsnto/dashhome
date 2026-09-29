import type { ReactNode } from 'react'
import type { ApiError } from '../api'

const MESSAGES: Record<string, string> = {
  google_not_configured:
    'Google Calendar is not configured. Set the Google credential env vars (see README), then restart the backend.',
  google_login_required:
    'Google Calendar needs a one-time sign-in. Click the button to open the Google login, then come back.',
  homeassistant_not_configured:
    'Home Assistant is not configured. Set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN, then restart the backend.',
  hermes_not_configured:
    'Hermes is not configured. Set HERMES_BASE_URL (plus chat path/auth), then restart the backend.',
  push_not_configured:
    'Hermes pushes are not configured. Set HERMES_PUSH_TOKEN, then restart the backend.',
}

export function loginHref(err: ApiError): string | null {
  if (err.code !== 'google_login_required') return null
  const m = /visit (\S+)/.exec(err.detail || '')
  if (m && m[1].startsWith('http')) return m[1]
  return '/api/auth/google/login'
}

/** Friendly explanation for a backend error, with an optional action. */
export default function Note({
  err,
  children,
}: {
  err?: ApiError | null
  children?: ReactNode
}) {
  if (err) {
    const known = MESSAGES[err.code]
    const text = known ? `${known}` : `${err.code}${err.detail ? ` — ${err.detail}` : ''}`
    const href = loginHref(err)
    return (
      <p className="note warn">
        {text}
        {href && (
          <a className="btn small" href={href} target="_blank" rel="noreferrer">
            Sign in to Google
          </a>
        )}
      </p>
    )
  }
  return <p className="note">{children}</p>
}
