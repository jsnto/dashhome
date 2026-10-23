# dashhome

A home dashboard: your calendars, your Home Assistant, and Hermes —
on one page.

dashhome is a single Go process (Gin) on port **3000** that integrates with

- **Google Calendar** — each calendar in its own tab, plus a
  **Summary** tab that combines all of them into one month view;
- **Home Assistant** — the real HA dashboard embedded in an iframe in the
  Home card, the entity-state API, and a read-only API proxy for the
  dashboard inventory and their config;
- **Hermes (chat)** — a chat box that forwards messages to your Hermes API
  and keeps the conversation thread visible; the mic button also accepts
  spoken commands (browser speech recognition), which go through the same
  path as typed messages, with the reply read aloud;
- **Hermes (push)** — an authenticated endpoint for Hermes to push markdown
  to. Pushes are recorded durably, the **latest push is always shown** on the
  dashboard, and previous pushes are browsable in tabs (newest first).

The board is a **tile grid** on a 12 × 12 unit grid (12 units wide and 12
units tall by default — each unit an even twelfth of the board's width or
height — and the board spans its full height): the four views (calendar,
chat, home, push) are the tiles, each sized to one of the size steps (small
/ medium / large / full width, in units of the grid width), and the grid
reflows by tile size and by how many tiles are shown. The grid's dimensions
(1–24 units per axis) are editable in the settings screen and persisted to
the board file as `board.grid`. Home Assistant-backed tiles (button / visual,
each bound to one entity — see the Home Assistant section) join the grid as
first-class tiles. A tile's position is automatic by default, but it can be
fixed to a cell (Edit tiles mode or hand-edited `views.geometry`); fixed
tiles keep their spot while the rest reflow around them. Clicking a tile's
title fills the board with that tile (click it again to restore the grid).
A header button toggles the browser's full-screen mode.

The dashboard has a **settings screen** (header → *Settings*): light/dark
theme (persisted per browser, defaults to the OS preference), a Tiles
section (add or remove the four dashboard tiles — and any Home Assistant
tiles — to/from the main screen, reorder them, and switch the Calendar
tile between the month grid and a schedule summary of upcoming events;
persisted to `dashhome.json`), a Home Assistant section (add or remove the
HA board tiles — a button or visual tile bound to one entity each, picked
from `GET /api/ha/entities`; a new tile is placed by default at the first
free 2 × 2 cell of the board's unit grid; persisted to `dashhome.json` as
`views.ha_tiles`), a Services section (the integrations in use and their
status — including the full-dashboard embed settings — and each
integration's service settings, editable and persisted to `dashhome.json`
as `board.services`; see Configuration below), the board grid dimensions
(how many units wide and tall the tile grid is — `board.grid` in
`dashhome.json`, default 12 × 12), and the live-update interval (how often
the Hermes push panel polls the backend, persisted to `dashhome.json`). The header's *Edit tiles* toggle switches the
board into an edit mode: tiles can be dragged by their grip to
another cell (or resized by dragging the corner handle — every move snaps to
the grid, and overlaps and drops off the board are refused and the tile
snaps back); each tile's edit row also carries a remove
control (one press, no confirm — a Home Assistant tile leaves
`views.ha_tiles`, a built-in view is hidden; the tile's entity and data are
never touched, and the tile can be added back from the settings screen);
the row's edit button opens a modal with that tile's settings — the
built-in view tile's size step (the width the board uses when it places the
tile automatically), the Calendar tile's view mode, a Home Assistant
tile's entity, label, and kind, grouped in labeled sections (size, content,
Home Assistant) — saved through the same path; placements, removals, and
the modal's saves are persisted to
`dashhome.json` the same way (the tile set and order are managed from the
settings screen).

Live updates: the Hermes push panel polls the backend on the configured
interval; the backend fetches fresh data from each configured service on
every request — except the read-only HA dashboard-config proxy, which caches
with a short TTL.

The React frontend talks only to the backend API; all integrations live in
the backend.

```
+--------------------------------------------------------------+
| nginx (:8000) — serves the React (Vite) build: calendar tabs,|
|   HA dashboard, chat box, markdown panel + push history tabs.|
|   /api/* is proxied same-origin to the backend; /assets/     |
|   bundles are immutable; the client router owns other paths. |
+--------------------------------------------------------------+
| dashhome (Go + Gin, :3000)                                   |
|                                                               |
|  /api  ──► Google Calendar   (list calendars, list events)    |
|        ──► Home Assistant    (entity states, dashboards)      |
|        ──► Hermes chat       (POST message, get reply)        |
|        ◄── Hermes push       (POST markdown, token-auth)      |
+--------------------------------------------------------------+
```

## Quick start

Requirements: Go ≥ 1.26.3, Node 22 (pinned in `frontend/.nvmrc` and the
frontend `engines` field), and the integrations you want to use
(Home Assistant, Google Cloud, a Hermes API).

```sh
# 1. configure
cp .env.example .env        # then edit the values
cp dashhome.example.json dashhome.json # optional: pick the entities/calendars

# 2. build the frontend (served by the Go binary)
make web

# 3. run everything on :3000
set -a; . ./.env; set +a
make run        # or: go run ./cmd/dashhome
```

Open http://localhost:3000.

For frontend development with hot reload:

```sh
make run          # backend on :3000
make run-web      # Vite dev server on :5173 (proxies /api to :3000)
```

## Docker

Or run the whole app with Docker Compose. There are two services:
`backend` builds the Go binary from the root `Dockerfile` (the API, on a
fixed internal port 3000) and `frontend` builds the React app and serves
it with nginx from `frontend/Dockerfile` (on 8000). The frontend proxies
`/api/` to the backend same-origin, so the app's relative `/api/...`
calls need no changes and there is no CORS:

```sh
cp .env.example .env    # then edit the values
docker compose up -d --build
```

Open http://localhost:8000 for the dashboard; the API is on
http://localhost:3000.

- The app's environment variables are passed into the backend container
  from `.env`. Set `BACKEND_PORT` / `FRONTEND_PORT` in `.env` to move
  the published host ports (defaults `3000` / `8000`); the
  container-side ports stay fixed. No credentials are stored in the
  images or the compose file.
- Push records and the Google token cache live in the `data` named
  volume on the backend, so they survive container rebuilds.
- To use a dashboard config file, create `./dashhome.json` at the stack
  root (see `dashhome.example.json`): the compose file already bind-mounts
  it read-write into the backend at the `CONFIG_FILE` default path
  (`/app/dashhome.json`), because the settings screen persists the refresh
  interval into it. For settings persistence the file must exist on the
  host and be writable by the container's `dashhome` user (uid 100; e.g.
  `chmod 666 ./dashhome.json`); the calendar IDs inside are yours to edit.
  It is optional at startup — if it is absent, Docker auto-creates a
  directory at the host path, the backend logs one line and runs
  unconfigured, and a settings save reports the board file as not
  writable.
- In the service-account mode (the `.env.example` default), the app exits
  at startup unless the key file at `GOOGLE_APPLICATION_CREDENTIALS`
  (default `/secrets/google-sa.json`) is present; add a volume entry
  mounting it (e.g. `./secrets/google-sa.json:/secrets/google-sa.json`)
  or point the variable at a mounted path.

`docker compose down` stops and removes the stack (add `-v` to also drop
the `data` volume).

## Android app

There is also a native Android app (`android/`) — a mobile client for the
same backend: Calendar (the same tabs, month grid or schedule summary
per the board's `calendar_mode`), the real Home Assistant dashboard in a
WebView, the Hermes chat and markdown push feed,
and Settings. Same data and sections as the web dashboard, laid out for
phones with an adaptive layout for tablets and other wide displays. The
app talks only to the dashhome API (no backend changes needed); see
[`android/README.md`](android/README.md) for building (Docker — no Android
SDK on the host) and tests.

## Configuration

Service settings (Home Assistant, Google Calendar, Hermes) can also be set
in the settings UI: a value saved there (`board.services` in
`dashhome.json`) wins over the matching environment variable, which wins
over the documented default, and takes effect without a restart. Server and
deploy variables stay in the environment. Everything else comes from
environment variables (see `.env.example`) plus an optional dashboard
config file. Never commit real credentials.

### Home Assistant

| Variable | Meaning |
|---|---|
| `HOMEASSISTANT_URL` | Base URL, e.g. `https://ha.example.com` |
| `HOMEASSISTANT_TOKEN` | Long-lived access token (HA → Profile → Security) |

Which entities the entity API shows is **driven by `dashhome.json`**, not
code — edit `ha_entities` (and optional `ha_labels`) and restart (or
reload) to change what `GET /api/ha/entities` returns without a code
change. With an empty or missing `ha_entities`, all entities are
returned.

**Board tiles.** Home Assistant-backed tiles can be added to the board from
the settings screen's Home Assistant section (or by hand in `views.ha_tiles`
below): each tile binds to exactly one entity and is either a **button**
tile (fires the board's allowlisted `toggle` action — the API also
accepts `turn_on`/`turn_off`, on any entity domain; the toggle button
wears the switch's current state — a dark button offering *Turn off*
while the entity is on, a bright button offering *Turn on* while it is
off, kept in sync with the tile's state polling) or a **visual**
tile (shows the entity's current state). Entity states are polled by the browser while a
HA tile is visible — never stored, and the token stays on the server;
the only write path to HA is `POST /api/ha/service` with the allowlisted
actions (see the API table).

**Home (iframe).** The Home card embeds the real HA dashboard in an iframe
at the `HOMEASSISTANT_URL` origin
(the viewer's browser loads HA directly — dashhome's backend being reachable
does not make HA reachable to the viewer's browser). Two deployment notes:

- Home Assistant sends `X-Frame-Options: SAMEORIGIN` by default, which makes
  browsers refuse the cross-origin iframe. The refusal renders inside the
  frame as a "refused to connect" page naming the HA host, while the same
  URL opens fine in a new tab (a top-level page is not subject to framing
  rules) — a useful tell that this is the cause. On instances that send the
  header, turn off **Send X-Frame-Options** (HA → Settings → System →
  Network). That weakens clickjacking protection for the HA UI on that
  origin — acceptable on a trusted home network.
- Auth: by default the iframe relies on the browser's own HA session — the
  first visit shows HA's login page inside the frame and the (partitioned)
  browser storage keeps it logged in afterwards. If the HA version still
  supports URL-token auth and you prefer that, set
  `"ha_embed": { "token_url": true }` in `dashhome.json`: the embed URL is
  assembled **server-side** with `?auth_long_lived=…` (same
  `HOMEASSISTANT_TOKEN`), so the token never lands in `dashhome.json` or
  browser storage — only in the iframe's own URL (visible in that context's
  history).

The backend also proxies the read-only dashboard config API (WS
`lovelace/dashboards/list` + `lovelace/config`, with the older
`get_dashboards`/`get_dashboard` names probed automatically): see
`GET /api/ha/dashboards` and `GET /api/ha/dashboard/:path` below.

### Google Calendar

Pick **one** credential mode:

1. **Service account** (recommended for a home server)
   - In Google Cloud: create a service account, enable the *Google Calendar
     API*, and download its JSON key.
   - `GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json`
   - Share each calendar you want to see with the service account's email
     address (viewer access).
   - Optional `GOOGLE_DOMAIN=user@workspace.com` enables domain-wide
     delegation (the service account acts as that user).
   - The dashboard shows every calendar visible to the credentials, or the
     calendars listed in `dashhome.json` → `calendars`. For a service
     account, each `calendars` entry is one calendar the service account
     is shared on, and the calendar ID is the email address of the account
     the calendar is shared from. A shared calendar can be missing from
     the API's calendar list (sharing propagation quirk); a configured ID
     is still shown as a tab and fetched by ID in that case.
2. **3-legged OAuth** (personal Google account)
   - Create an *OAuth client* (Web application). Authorized redirect URI:
     `<https://your-host>/api/auth/google/callback`.
   - `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`.
   - Open http://localhost:3000/api/auth/google/login once; the token is
     cached in `DATA_DIR` and refreshed automatically. The dashboard's
     Calendar area shows a "Sign in to Google" button while this is pending.
3. **Static access token** (development): `GOOGLE_ACCESS_TOKEN=ya29.…`

`GOOGLE_API_ENDPOINT` can override the Calendar API base URL (advanced:
proxy or tests).

### Hermes chat

| Variable | Meaning |
|---|---|
| `HERMES_BASE_URL` | Base URL of the Hermes API (no trailing slash) |
| `HERMES_CHAT_PATH` | Chat endpoint path (default `/chat/completions`) |
| `HERMES_AUTH_HEADER` | Header name to send (default `Authorization`) |
| `HERMES_AUTH_VALUE` | Full header value, e.g. `Bearer sk-…` (empty = no auth) |

**Expected contract** (kept in one module — `backend/hermes` — so it is
easy to adapt to your real Hermes service): Hermes speaks the
OpenAI-compatible chat-completions contract.

- Request: `POST {base}{path}` with `Content-Type: application/json` —
  a top-level `messages` array built from the conversation history
  (oldest first) followed by the current user message as the final turn:

  ```json
  {
    "messages": [
      { "role": "user", "content": "earlier question" },
      { "role": "assistant", "content": "earlier reply" },
      { "role": "user", "content": "how's the house?" }
    ]
  }
  ```

- Response: the OpenAI envelope; the reply text is at
  `choices[0].message.content`. A 2xx body without it (missing or empty
  `choices`, empty `content`) is treated as an error, not a reply:

  ```json
  {
    "id": "chatcmpl-…",
    "object": "chat.completion",
    "choices": [
      {
        "index": 0,
        "message": { "role": "assistant", "content": "all quiet on the dashhome front" },
        "finish_reason": "stop"
      }
    ]
  }
  ```

### Hermes push

| Variable | Meaning |
|---|---|
| `HERMES_PUSH_TOKEN` | Shared secret Hermes must present (empty = push endpoint disabled) |

Hermes pushes markdown with:

```sh
curl -X POST https://<host>/api/hermes/push \
  -H "Authorization: Bearer $HERMES_PUSH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"markdown": "## Morning brief\n\n- Front door: closed", "source": "hermes"}'
```

The token may also be sent as `X-Hermes-Token: <token>` or `?token=<token>`.
Each push is stored as a dated JSON record under `DATA_DIR/hermes_pushes/`
(durable across restarts). The dashboard always renders the latest push;
the History tabs open any previous one.

### Dashboard config file (`dashhome.json`)

```json
{
  "ha_entities": ["climate.thermostat", "sensor.front_door"],
  "ha_labels": { "climate.thermostat": "Thermostat" },
  "calendars": [
    { "id": "family@gmail.com", "label": "Family" },
    { "id": "work@gmail.com", "label": "Work" }
  ],
  "refresh_seconds": 30,
  "ha_embed": { "url_path": null, "token_url": false },
  "views": {
    "order": ["calendar", "chat", "home", "push", "ha-1"],
    "hidden": [],
    "calendar_mode": "month",
    "sizes": { "calendar": "m", "chat": "m", "home": "m", "push": "m" },
    "geometry": { "home": { "x": 1, "y": 2, "w": 3, "h": 2 } },
    "ha_tiles": [{ "id": "ha-1", "entity": "light.kitchen", "kind": "button" }]
  }
}
```

- `ha_entities` — entity IDs the entity API returns, in order.
  Empty/missing = all entities.
- `ha_labels` — friendly labels per entity (falls back to the entity's
  `friendly_name`).
- `calendars` — calendar IDs to show as tabs, in order. Empty/missing =
  show every visible calendar. Each entry is one calendar; multiple
  entries = multiple calendars. For a Google service account, the
  calendar ID is the email address of the account a calendar is shared
  from (e.g. the personal account of the person who shared it), and each
  entry is one calendar the service account is shared on. Configured IDs
  are shown and fetched even when the API's calendar list does not (yet)
  return them — with a service account that list can be empty until
  Google propagates the sharing.
- `refresh_seconds` — UI polling interval hint (default 30, range 5–3600).
  Editable from the dashboard's settings screen, which persists it here.
- `grid` — the tile grid's dimensions in units: `{"cols": N, "rows": M}`
  (1–24 per axis, default 12 × 12). Each unit is an even `1/cols` slice of
  the board's width and an even `1/rows` slice of its height; the board
  always spans its full height, and a tile's stored cell must fit within
  these bounds. Editable from the settings screen's Tiles section, which
  persists it here; stored placements that fall outside a shrunken grid
  are repaired back to automatic placement.
- `ha_embed` — the full-dashboard iframe (optional; missing = default
dashboard, no token in the URL):
  - `url_path` — dashboard `url_path` to embed (`null` = the default
    dashboard; find the value in the HA UI sidebar or via
    `GET /api/ha/dashboards`).
  - `token_url` — put the long-lived token in the embed URL (server-side
    only). Default `false`. See the notes above.
- `views` — the dashboard's tile layout (optional; missing = the default
  board). Also persisted by the settings screen's Tiles section (tile set,
  order, Home Assistant tiles) and the board's Edit tiles mode (positions,
  plus the per-tile settings modal's size step (built-in view tiles),
  Calendar view mode, and HA tile entity/label/kind):
  - `order` — the board tile IDs (the four view IDs `calendar`, `chat`,
    `home`, `push` plus every `views.ha_tiles` id) in the tile grid's
    reading order (row by row, left to right); the API always returns it
    as a permutation of the board tile IDs.
  - `hidden` — the board tile IDs hidden from the board (a subset of the
    board tile IDs); a hidden tile is not rendered and needs no data
    polling.
  - `calendar_mode` — how the Calendar tile renders: `"month"` (the
    month grid) or `"schedule"` (a chronological list of upcoming
    events).
  - `sizes` — a per-tile map of board tile ID to size step: `"s"` (a third of
    the grid width), `"m"` (a half, the default), `"l"` (two thirds),
    or `"xl"` (the full row). The step is a fraction of the grid's
    `cols` (rounded, clamped to 1–`cols`); on the default 12-unit grid
    that is 4 / 6 / 8 / 12 units. Missing/absent entries fall back to
    `"m"`.
  - `geometry` — a per-tile map of tile ID (view ID or HA tile ID) to a
    fixed grid cell `{"x", "y", "w", "h"}` (1-based columns and rows,
    `1 ≤ x ≤ cols-w+1`, `1 ≤ y ≤ rows-h+1`, `w ≤ cols`, `h ≤ rows`, the
    cell must stay inside the board and must not overlap another placed
    tile). Tiles with no entry are placed automatically
    in board order (row by row); a fixed tile renders at its stored
    cell, width included. On load, invalid entries (out of bounds,
    non-integer, non-object, or overlapping) are dropped and the tile
    falls back to auto placement; on save, out-of-bounds placements are
    refused with 400 `bad_views`, malformed values (non-integer or
    non-object) refuse the request with 400 `bad_request`, and
    overlapping entries are dropped with the tile falling back to auto
    placement — the server never serves a colliding layout.
  - `ha_tiles` — the Home Assistant board tiles, in board order: an `id`
    (`ha-<name>`, 1–16 lowercase alphanumerics), the bound `entity`
    (entity ID), a `kind` (`"button"` or `"visual"`), and an optional
    `label` shown on the tile instead of the entity's own label. The
    settings screen replaces this list wholesale on each save; IDs
    dropped from the list are also removed from `order`, `hidden`,
    `sizes`, and `geometry`.
  Hand-edited values are repaired, not rejected: unknown IDs are dropped
  (missing board tile IDs re-appear in the default order), an unknown
  `calendar_mode` falls back to `"month"`, `sizes` entries with an
  unknown board tile ID or an unknown step fall back to the default size,
  `grid` dimensions outside 1–24 fall back to 12 per axis, `geometry`
  entries that fall outside the grid (columns or rows) or overlap a
  neighbour are dropped (the tile falls back to automatic placement), and
  `ha_tiles` entries with a bad id, entity, or kind are dropped.

- `services` — the service settings saved from the settings screen's
  Services section (optional; missing = environment variables only):
  `homeassistant` (`url`, `token`), `google` (`service_account_file`,
  `domain`, `client_id`, `client_secret`, `redirect_uri`, `token_cache`,
  `access_token`, `endpoint`), `hermes_chat` (`base_url`, `chat_path`,
  `auth_header`, `auth_value`), `hermes_push` (`token`). A value saved here
  wins over the matching environment variable, which wins over the
  documented default, and takes effect without a restart. Secret fields are
  only ever written, never read back: `GET /api/config/services` reports
  only whether they are set.

Path: `CONFIG_FILE` (default `./dashhome.json`).

### Other

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | Listen port (the Docker backend is pinned to this) |
| `BACKEND_PORT` | `3000` | Docker: published host port for the backend (API) service |
| `FRONTEND_PORT` | `8000` | Docker: published host port for the frontend (dashboard) service |
| `DATA_DIR` | `./data` | Push records + Google token cache |
| `CONFIG_FILE` | `./dashhome.json` | Dashboard config |

## API

| Route | Description |
|---|---|
| `GET /healthz` | Liveness probe |
| `GET /api/config` | Dashboard config + integration status (no secrets) |
| `PUT /api/config/refresh` | `{"refresh_seconds": n}` (5–3600) → persist to `dashhome.json` (the board file must be writable by the backend), `200 {board}` |
| `PUT /api/config/grid` | `{"cols": n, "rows": m}` (1–24 per axis; both required) → set the tile grid's dimensions (each unit an even slice of the board's width/height; the board spans its full height) and persist to `dashhome.json` (the board file must be writable by the backend); stored placements that fall outside the new grid are repaired back to automatic placement, `400 bad_grid` for out-of-range values, `400 bad_request` for missing fields, `503` when the board file is missing or not writable, `200 {board}` |
| `PUT /api/config/views` | `{"order": [...], "hidden": [...], "calendar_mode": "month"\|"schedule", "sizes": {tile: "s"\|"m"\|"l"\|"xl"}, "geometry": {tile: {"x": n, "y": n, "w": n, "h": n} \| null}, "ha_tiles": [...]}` (any subset of the fields; omitted fields keep their value, `sizes`/`geometry` change only the tiles they list, a `null` geometry entry clears that tile's placement, and `ha_tiles` replaces the list) → persist the dashboard's tile layout to `dashhome.json` (the board file must be writable by the backend); placements that fall outside the board are refused with `400 bad_views` (as are other invalid field values), and overlapping stored entries are dropped on load and on save — the tile falls back to automatic placement — `503` when the board file is missing or not writable, `200 {board}` |
| `GET /api/config/services` | Masked service settings for the settings screen: per field the UI-set value, the environment fallback, and whether each layer is set; secret fields (tokens and credentials) carry only the set booleans, never a value |
| `PUT /api/config/services` | `{"homeassistant": {...}, "google": {...}, "hermes_chat": {...}, "hermes_push": {...}}` (any subset; a field omitted or null keeps its value, an empty string clears the UI value back to the environment fallback, a non-empty string sets it; an update that changes nothing is a no-op) → persist the service settings to `dashhome.json` (`board.services`; the board file must be writable by the backend), `400 bad_request` for a malformed body, `503 no_board_file` when the board file is missing, `503 board_file_not_writable` when it is not writable, `200 {board, services, integrations}` |
| `GET /api/calendars` | Calendar tabs: the configured `calendars` (shown even if the API's calendar list omits them), else every calendar visible to the credentials (labels from config) |
| `GET /api/events?start=&end=&calendar=` | Events in a range (RFC3339 or `YYYY-MM-DD`); optional `calendar` ID; otherwise all configured calendars (summary) |
| `GET /api/ha/entities` | Configured entities with current states |
| `POST /api/ha/service` | `{"entity_id": "light.kitchen", "action": "toggle"\|"turn_on"\|"turn_off"}` → calls the HA service matching the action on that entity (only these three actions are accepted, on any entity domain, via `homeassistant.toggle`/`turn_on`/`turn_off`), `404 ha_entity_not_found`, `502 homeassistant_unavailable` when HA cannot be reached, `503 homeassistant_not_configured`, `200 {ok: true}` |
| `GET /api/ha/embed-url` | Fully-formed iframe URL for the Home card's dashboard embed (token only when `ha_embed.token_url`) |
| `GET /api/ha/dashboards?reload=1` | Dashboard inventory (metadata only; `reload` bypasses the TTL cache) |
| `GET /api/ha/dashboard/:path?reload=1` | One dashboard's full config JSON (pass-through); `:path` empty (i.e. `/api/ha/dashboard[/]`) = default dashboard |
| `POST /api/hermes/chat` | `{message, conversation}` → `{reply}` |
| `POST /api/hermes/push` | Authenticated markdown push → `201 {push}` |
| `GET /api/hermes/pushes?limit=` | Previous pushes, newest first |
| `GET /api/hermes/pushes/latest` | Latest push |
| `GET /api/hermes/pushes/:id` | One push by ID |
| `GET /api/auth/google/login` | OAuth login URL (OAuth mode) |
| `GET /api/auth/google/callback` | OAuth code exchange → redirect to `/` |

Errors are JSON: `{"error": "code", "detail": "..."}`. Unconfigured
integrations return `503` with a `*_not_configured` code; the UI turns these
into friendly setup notes.

## Security notes

- The Hermes push endpoint is gated by the shared token (constant-time
  compare); leaving `HERMES_PUSH_TOKEN` empty disables ingestion.
- Push record IDs are validated against a strict pattern (no path
  traversal).
- Markdown from Hermes is rendered with `react-markdown` **without**
  raw-HTML support, so pushed content cannot inject HTML.
- `GET /api/config` exposes only non-secret board settings and per-integration
  booleans; tokens and credentials (in `.env` or `dashhome.json`) are never
  included in API responses — `GET /api/config/services` reports only
  whether a secret field is set in the UI or in the environment, never its
  value. One deliberate exception: `GET /api/ha/embed-url` returns the
  embed URL with `?auth_long_lived=<token>` **only when `ha_embed.token_url`
  is set** — the token is assembled server-side for the iframe and never
  written to `dashhome.json` or browser storage.
- The dashboard-config proxy (`/api/ha/dashboards`,
  `/api/ha/dashboard/:path`) is read-only and caches the results in
  memory with a TTL (`?reload=1` forces a re-fetch). The one write path to
  Home Assistant is `POST /api/ha/service`, which accepts a fixed
  allowlist of actions (`toggle`, `turn_on`, `turn_off` — the three
  standard actions, on any entity) against a caller-supplied entity ID
  (not restricted to the entities the board shows) — no free-form
  service or data payload — so the board can flip a light but
  cannot invoke arbitrary HA services.
- Secrets live in env vars, your secrets store, or `dashhome.json`
  (`board.services`, written by the settings UI); `.env`, `data/`, and
  `dashhome.json` are git-ignored.

## Development

```sh
make test   # go test ./...  +  frontend typecheck & build  +  vitest
```

- The frontend legs require Node 22 (pinned in `frontend/.nvmrc` and the
  frontend `engines` field): run `nvm use` in `frontend/` first.
- Backend tests use local fakes (httptest servers + in-memory fakes) — no
  real HA, Google, or Hermes calls.
- Layout: `cmd/dashhome` (entrypoint), `backend/api` (Gin handlers),
  `backend/calendar` (Google client), `backend/ha` (HA client),
  `backend/hermes` (Hermes client), `backend/store` (push persistence),
  `backend/config` (env + board config), `frontend/` (React app),
  `android/` (native Android app).
