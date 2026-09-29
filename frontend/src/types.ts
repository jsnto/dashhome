// Shared API types mirroring the Go backend JSON.

export interface ApiConfig {
  board: {
    ha_entities: string[]
    ha_labels: Record<string, string>
    calendars: { id: string; label: string }[]
    refresh_seconds: number
  }
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
