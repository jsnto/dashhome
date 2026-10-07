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

/** The board's tile layout (the optional `views` object of the board file).
 *  The tile IDs are the four view IDs plus any `views.ha_tiles` ids. */
export interface BoardViews {
  /** Tile IDs in board order (the four view IDs plus every ha_tiles id). */
  order: string[]
  /** Tile IDs hidden from the board. */
  hidden: string[]
  /** How the Calendar view renders: 'month' (grid) or 'schedule' (list). */
  calendar_mode: CalendarMode
  /** Per-tile size step (every tile ID, a valid step). */
  sizes: Record<string, TileSize>
  /** Explicit per-tile grid placements; missing = automatic placement. */
  geometry?: Record<string, TileGeometry>
  /** Home Assistant board tiles (button or visual), bound to entities. */
  ha_tiles?: HABoardTile[]
}

/**
 * A tile's explicit placement on the board's 6-column grid. 1-based:
 * `x` is the starting column (1..6), `y` the starting row, `w` and `h`
 * the spans in columns and rows. A tile without a placement is placed
 * automatically (it flows in board order at its size step's width).
 */
export interface TileGeometry {
  x: number
  y: number
  w: number
  h: number
}

/**
 * A fully repaired views section: what normalizeViews returns (and what
 * the API answers always are). The raw board file / API input may omit
 * the geometry and ha_tiles sections (older files); the normalized form
 * always has both, so consumers can index them directly.
 */
export type NormalizedViews = Omit<BoardViews, 'geometry' | 'ha_tiles'> & {
  geometry: Record<string, TileGeometry>
  ha_tiles: HABoardTile[]
}

/** The kinds of Home Assistant board tiles. */
export type HAKind = 'button' | 'visual'

/** A Home Assistant board tile: bound to exactly one HA entity. */
export interface HABoardTile {
  /** Tile id: "ha-" + 1–16 lowercase alphanumerics (e.g. "ha-1"). */
  id: string
  /** The HA entity_id the tile is bound to (e.g. "light.kitchen"). */
  entity: string
  /** Display label; falls back to the entity's own label. */
  label?: string
  kind: HAKind
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
