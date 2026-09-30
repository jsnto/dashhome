// Shared API types mirroring the Go backend JSON.

export interface HAEmbed {
  /** Dashboard url_path to embed; null/absent = Home Assistant's default dashboard. */
  url_path: string | null
  /** Embed the long-lived token in the URL (assembled server-side). Default false. */
  token_url?: boolean
}

export interface ApiBoard {
  ha_entities: string[]
  ha_labels: Record<string, string>
  calendars: { id: string; label: string }[]
  refresh_seconds: number
  ha_embed?: HAEmbed | null
}

export interface ApiConfig {
  board: ApiBoard
  integrations: {
    homeassistant: boolean
    google: {
      configured: boolean
      mode?: string
      login_required: boolean
      login_url: string
    }
    hermes_chat: boolean
    hermes_push: boolean
  }
}

export interface CalendarInfo {
  id: string
  summary: string
  label: string
  primary?: boolean
}

export interface ApiEvent {
  calendar_id: string
  calendar_label: string
  id: string
  summary: string
  description?: string
  location?: string
  start: string
  end: string
  all_day: boolean
}

export interface HAEntity {
  entity_id: string
  label: string
  friendly_name?: string
  state: string
  attributes?: Record<string, unknown>
  last_updated?: string
  last_changed?: string
}

export interface HADashboard {
  id: string
  url_path: string | null
  title: string
  icon?: string
  require_admin: boolean
  show_in_sidebar: boolean
  mode: string
  filename?: string
}

export interface Push {
  id: string
  received_at: string
  source?: string
  markdown: string
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}
