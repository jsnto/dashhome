// Shared API types mirroring the Go backend JSON.

export interface HAEmbed {
  /** Dashboard url_path to embed; null/absent = Home Assistant's default dashboard. */
  url_path: string | null
  /** Embed the long-lived token in the URL (assembled server-side). Default false. */
  token_url?: boolean
}

/** The four dashboard views (the board's tiles), in the default board order (the tile grid's reading order: row by row, left to right). */
export const VIEW_IDS = ['calendar', 'chat', 'home', 'push'] as const

export type ViewId = (typeof VIEW_IDS)[number]

/** How the Calendar view renders. */
export type CalendarMode = 'month' | 'schedule'

/** The tile size steps (column spans on the board's 6-column grid), smallest to widest. */
export const TILE_SIZES = ['s', 'm', 'l', 'xl'] as const

export type TileSize = (typeof TILE_SIZES)[number]

/** The default tile size: half the board's width (four of them form the default 2x2 board). */
export const DEFAULT_TILE_SIZE: TileSize = 'm'

/** The board's tile layout (the optional `views` object of the board file). */
export interface BoardViews {
  /** View IDs in board order; always a permutation of VIEW_IDS. */
  order: ViewId[]
  /** View IDs hidden from the board (a subset of VIEW_IDS). */
  hidden: ViewId[]
  /** How the Calendar view renders: 'month' (grid) or 'schedule' (list). */
  calendar_mode: CalendarMode
  /** Per-tile size step; always complete (every view ID, a valid step). */
  sizes: Record<ViewId, TileSize>
}

export interface ApiBoard {
  ha_entities: string[]
  ha_labels: Record<string, string>
  calendars: { id: string; label: string }[]
  refresh_seconds: number
  ha_embed?: HAEmbed | null
  /** View layout; the backend always sends a complete effective layout. */
  views?: BoardViews | null
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
