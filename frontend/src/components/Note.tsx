import type { ReactNode } from 'react'
import type { ApiError } from '../api'

const MESSAGES: Record<string, string> = {
  google_not_configured:
    'Google Calendar is not configured. Set the Google Calendar settings in Settings → Services.',
  google_login_required:
    'Google Calendar needs a one-time sign-in. Click the button to open the Google login, then come back.',
  homeassistant_not_configured:
    'Home Assistant is not configured. Set the Home Assistant URL and token in Settings → Services.',
  hermes_not_configured:
    'Hermes is not configured. Set the Hermes chat settings in Settings → Services.',
  push_not_configured:
    'Hermes pushes are not configured. Set the Hermes push token in Settings → Services.',
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
