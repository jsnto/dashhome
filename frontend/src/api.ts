import type {
  ApiBoard,
  ApiConfig,
  ApiEvent,
  CalendarInfo,
  CalendarMode,
  ChatMessage,
  GridDims,
  HABoardTile,
  Push,
  TileGeometry,
  TileSize,
} from './types'

/** A Home Assistant entity from GET /api/ha/entities. */
export interface HAEntity {
  entity_id: string
  label: string
  friendly: string
  state: string
  attributes: Record<string, unknown>
  last_updated?: string
  last_changed?: string
}

export interface HAEntitiesResponse {
  entities: HAEntity[]
  total: number
}

/** The allowlisted service actions the backend will fire for button tiles. */
export type HAServiceAction = 'toggle' | 'turn_on' | 'turn_off'

export interface HAServiceResult {
  ok: boolean
  entity_id: string
  action: HAServiceAction
  result: Record<string, unknown> | null
}

/** Error returned by the backend as {error, detail}. */
export class ApiError extends Error {
  status: number
  code: string
  detail?: string

  constructor(status: number, code: string, detail?: string) {
    super(detail ? `${code}: ${detail}` : code)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.detail = detail
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json' },
  })
  const text = await res.text()
  let body: unknown = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
  }
  if (!res.ok) {
    const b = body as { error?: string; detail?: string } | string | null
    if (typeof b === 'object' && b && b.error) {
      throw new ApiError(res.status, b.error, b.detail)
    }
    throw new ApiError(res.status, 'request_failed', typeof b === 'string' ? b : undefined)
  }
  return body as T
}

export const api = {
  getConfig: () => request<ApiConfig>('/config'),

  /** Persist the UI polling interval (board.refresh_seconds) server-side. */
  setRefreshSeconds: (seconds: number) =>
    request<{ board: ApiBoard }>('/config/refresh', {
      method: 'PUT',
      body: JSON.stringify({ refresh_seconds: seconds }),
    }).then((b) => b.board),

  /**
   * Persist the board's tile grid dimensions (board.grid, cols x rows
   * units) server-side; the response carries the whole board section.
   */
  setGrid: (grid: GridDims) =>
    request<{ board: ApiBoard }>('/config/grid', {
      method: 'PUT',
      body: JSON.stringify(grid),
    }).then((b) => b.board),

  /**
   * Persist part of the tile layout (board.views) server-side: the board
   * order of the tiles (the four views plus any Home Assistant tile ids),
   * which are hidden, how the Calendar tile renders, per-tile size steps
   * (a map of the tiles to resize; tiles it omits keep their current
   * size), per-tile grid placements (`geometry`: a map of the tiles to
   * place; a `null` entry clears that tile's placement, restoring
   * automatic placement), and the Home Assistant tiles (`ha_tiles`: the
   * full list, a replacement). Omitted fields keep their current
   * server-side value.
   */
  setViews: (views: {
    order?: string[]
    hidden?: string[]
    calendar_mode?: CalendarMode
    sizes?: Partial<Record<string, TileSize>>
    geometry?: Record<string, TileGeometry | null>
    ha_tiles?: HABoardTile[]
  }) => request<{ board: ApiBoard }>('/config/views', {
    method: 'PUT',
    body: JSON.stringify(views),
  }).then((b) => b.board),

  getCalendars: () =>
    request<{ calendars: CalendarInfo[] }>('/calendars').then((b) => b.calendars),

  getEvents: (start: string, end: string, calendar?: string) =>
    request<{ events: ApiEvent[] }>(
      `/events?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}` +
        (calendar ? `&calendar=${encodeURIComponent(calendar)}` : ''),
    ).then((b) => b.events),

  /**
   * Fully-formed iframe URL for the Home card's dashboard embed, assembled
   * server-side (token only when token_url is enabled, so the token never
   * lands in the board file or browser storage).
   */
  getHAAEmbedURL: () => request<{ url: string }>('/ha/embed-url').then((b) => b.url),

  chat: (message: string, conversation: ChatMessage[]) =>
    request<{ reply: string }>('/hermes/chat', {
      method: 'POST',
      body: JSON.stringify({ message, conversation }),
    }).then((b) => b.reply),

  getLatestPush: () =>
    request<{ push: Push }>('/hermes/pushes/latest').then((b) => b.push),

  getPushes: (limit = 50) =>
    request<{ pushes: Push[] }>(`/hermes/pushes?limit=${limit}`).then((b) => b.pushes),

  getPush: (id: string) =>
    request<{ push: Push }>(`/hermes/pushes/${encodeURIComponent(id)}`).then((b) => b.push),

  /** Home Assistant entities (for the settings picker and board tiles).
   *  503 = not configured, 502 = HA unreachable. */
  getHAEntities: () => request<HAEntitiesResponse>('/ha/entities'),

  /**
   * Fire an allowlisted service action on one entity (the board's button
   * tiles). The backend accepts only the fixed toggle/turn_on/turn_off
   * actions — never a free-form service or data payload — and no secret
   * crosses the boundary. 400 = bad action, 404 = unknown entity,
   * 502/503 = HA unavailable.
   */
  callHAService: (entityId: string, action: HAServiceAction) =>
    request<HAServiceResult>('/ha/service', {
      method: 'POST',
      body: JSON.stringify({ entity_id: entityId, action }),
    }),
}
