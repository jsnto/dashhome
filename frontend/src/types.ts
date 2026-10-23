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

/** The board's tile grid dimensions: cols x rows units, each unit split
 *  evenly (1/cols of the width, 1/rows of the height). */
export interface GridDims {
  cols: number
  rows: number
}

/** The tile size steps (fractions of the grid's width), smallest to widest. */
export const TILE_SIZES = ['s', 'm', 'l', 'xl'] as const

export type TileSize = (typeof TILE_SIZES)[number]

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

/** A tile's explicit placement on the board's tile grid (cols x rows
 *  units). 1-based: `x` is the starting column, `y` the starting row, `w`
 *  and `h` the spans in columns and rows. The cell must stay inside the
 *  grid (1 <= w <= cols, 1 <= x <= cols-w+1, 1 <= h <= rows,
 *  1 <= y <= rows-h+1). A tile without a placement is placed
 *  automatically (it flows in board order at its size step's width). */
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
  /** The tile grid's dimensions (default 12x12); absent when the board
   *  file sets no grid. */
  grid?: GridDims | null
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

/** One field of the masked service-settings view (GET/PUT
 *  /api/config/services). Non-secret fields expose the value set in the
 *  settings UI and the .env fallback; secret fields expose only the set
 *  booleans — a secret value never reaches the browser. */
export interface ServiceField {
  /** The value set in the UI ("" = not set in the UI). Secrets: always "". */
  value: string
  /** The .env fallback value ("" = not set there). Secrets: always "". */
  fallback: string
  /** A value is set in the UI settings. */
  set: boolean
  /** A value is set in the .env fallback. */
  fallback_set: boolean
}

/** The masked service settings, grouped per integration. */
export interface ServiceSettings {
  homeassistant: {
    url: ServiceField
    token: ServiceField
  }
  google: {
    service_account_file: ServiceField
    domain: ServiceField
    client_id: ServiceField
    client_secret: ServiceField
    redirect_uri: ServiceField
    token_cache: ServiceField
    access_token: ServiceField
    endpoint: ServiceField
  }
  hermes_chat: {
    base_url: ServiceField
    chat_path: ServiceField
    auth_header: ServiceField
    auth_value: ServiceField
  }
  hermes_push: {
    token: ServiceField
  }
}

/** A partial service-settings update (PUT /api/config/services). Omitted
 *  (null) fields keep their current UI value; an empty string clears the
 *  UI value back to the .env fallback. */
export type ServiceSettingsUpdate = {
  homeassistant?: { url?: string | null; token?: string | null }
  google?: {
    service_account_file?: string | null
    domain?: string | null
    client_id?: string | null
    client_secret?: string | null
    redirect_uri?: string | null
    token_cache?: string | null
    access_token?: string | null
    endpoint?: string | null
  }
  hermes_chat?: {
    base_url?: string | null
    chat_path?: string | null
    auth_header?: string | null
    auth_value?: string | null
  }
  hermes_push?: { token?: string | null }
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
