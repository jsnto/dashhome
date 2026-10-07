import type {
  ApiBoard,
  ApiConfig,
  ApiEvent,
  CalendarInfo,
  CalendarMode,
  ChatMessage,
  Push,
  TileSize,
  ViewId,
} from './types'

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
   * Persist part of the tile layout (board.views) server-side: the board
   * order of the four tiles, which are hidden, how the Calendar tile
   * renders, and per-tile size steps (a map of the tiles to resize; tiles
   * it omits keep their current size). Omitted fields keep their current
   * server-side value.
   */
  setViews: (views: {
    order?: ViewId[]
    hidden?: ViewId[]
    calendar_mode?: CalendarMode
    sizes?: Partial<Record<ViewId, TileSize>>
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
}
