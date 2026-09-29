# homdash

A home dashboard: your calendars, your Home Assistant states, and Hermes —
on one page.

homdash is a single Go process (Gin) on port **3000** that integrates with

- **Google Calendar** — every visible calendar in its own tab, plus a
  **Summary** tab that combines all of them into one month view;
- **Home Assistant** — the current state of a configurable set of entities;
- **Hermes (chat)** — a chat box that forwards messages to your Hermes API
  and keeps the conversation thread visible;
- **Hermes (push)** — an authenticated endpoint for Hermes to push markdown
  to. Pushes are recorded durably, the **latest push is always shown** on the
  dashboard, and previous pushes are browsable in tabs (newest first).

The React frontend talks only to the backend API; all integrations live in
the backend.

```
+--------------------------------------------------------------+
| homdash (Go + Gin, :3000)                                     |
|                                                               |
|  /api  ──► Google Calendar   (list calendars, list events)    |
|        ──► Home Assistant    (entity states)                  |
|        ──► Hermes chat       (POST message, get reply)        |
|        ◄── Hermes push       (POST markdown, token-auth)      |
|                                                               |
|  /     ──► React (Vite) build: calendar tabs, HA grid,        |
|            chat box, markdown panel + push history tabs       |
+--------------------------------------------------------------+
```

## Quick start

Requirements: Go ≥ 1.26.3, Node ≥ 18, and the integrations you want to use
(Home Assistant, Google Cloud, a Hermes API).

```sh
# 1. configure
cp .env.example .env        # then edit the values
cp homdash.example.json homdash.json   # optional: pick the entities/calendars

# 2. build the frontend (served by the Go binary)
make web

# 3. run everything on :3000
set -a; . ./.env; set +a
make run        # or: go run ./cmd/homdash
```

Open http://localhost:3000.

For frontend development with hot reload:

```sh
make run          # backend on :3000
make run-web      # Vite dev server on :5173 (proxies /api to :3000)
```

## Docker

Or run the whole app as one container with Docker Compose. The image is
built in three stages (Node builds `web/dist`, Go builds the static
binary, a small final image carries both):

```sh
cp .env.example .env    # then edit the values
docker compose up -d --build
```

- The app's environment variables are passed into the container from
  `.env`; set `PORT` there to move both the published host port and the
  container's listen port (default `3000`). No credentials are stored in
  the image or the compose file.
- Push records and the Google token cache live in the `data` named
  volume, so they survive container rebuilds.
- To use a dashboard config file, set `CONFIG_FILE` in `.env` and add a
  volume entry mounting the file (e.g. `./homdash.json:/homdash.json`
  with `CONFIG_FILE=/homdash.json`).

`docker compose down` stops and removes the stack (add `-v` to also drop
the `data` volume).

## Configuration

Everything comes from environment variables (see `.env.example`) plus an
optional dashboard config file. Never commit real credentials.

### Home Assistant

| Variable | Meaning |
|---|---|
| `HOMEASSISTANT_URL` | Base URL, e.g. `https://ha.example.com` |
| `HOMEASSISTANT_TOKEN` | Long-lived access token (HA → Profile → Security) |

Which entities the dashboard shows is **driven by `homdash.json`**, not code
— edit `ha_entities` (and optional `ha_labels`) and restart (or reload) to
change the dashboard without a code change. With an empty or missing
`ha_entities`, all entities are shown.

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
     subset listed in `homdash.json` → `calendars`.
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
| `HERMES_CHAT_PATH` | Chat endpoint path (default `/v1/chat`) |
| `HERMES_AUTH_HEADER` | Header name to send (default `Authorization`) |
| `HERMES_AUTH_VALUE` | Full header value, e.g. `Bearer sk-…` (empty = no auth) |

**Expected contract** (kept in one module — `internal/hermes` — so it is
easy to adapt to your real Hermes service):

- Request: `POST {base}{path}` with `Content-Type: application/json`

  ```json
  {
    "message": "how's the house?",
    "conversation": [
      { "role": "user", "content": "earlier question" },
      { "role": "assistant", "content": "earlier reply" }
    ]
  }
  ```

- Response: JSON with a `reply` field (the client also accepts
  `message` / `response` / `text` or a plain-text body):

  ```json
  { "reply": "all quiet on the homdash front" }
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

### Dashboard config file (`homdash.json`)

```json
{
  "ha_entities": ["climate.thermostat", "sensor.front_door"],
  "ha_labels": { "climate.thermostat": "Thermostat" },
  "calendars": [
    { "id": "family@gmail.com", "label": "Family" },
    { "id": "work@gmail.com", "label": "Work" }
  ],
  "refresh_seconds": 30
}
```

- `ha_entities` — entity IDs to show, in order. Empty/missing = show all.
- `ha_labels` — friendly labels per entity (falls back to the entity's
  `friendly_name`).
- `calendars` — calendar IDs to show as tabs, in order. Empty/missing =
  show every visible calendar.
- `refresh_seconds` — UI polling interval hint (default 30).

Path: `CONFIG_FILE` (default `./homdash.json`).

### Other

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | Listen port |
| `DATA_DIR` | `./data` | Push records + Google token cache |
| `CONFIG_FILE` | `./homdash.json` | Dashboard config |

## API

| Route | Description |
|---|---|
| `GET /healthz` | Liveness probe |
| `GET /api/config` | Dashboard config + integration status |
| `GET /api/calendars` | Calendars visible to the credentials (labels from config) |
| `GET /api/events?start=&end=&calendar=` | Events in a range (RFC3339 or `YYYY-MM-DD`); optional `calendar` ID; otherwise all configured calendars (summary) |
| `GET /api/ha/entities` | Configured entities with current states |
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
- Secrets live only in env vars / your secrets store; `.env`, `data/`, and
  `homdash.json` are git-ignored.

## Development

```sh
make test   # go test ./...  +  frontend typecheck & build
```

- Backend tests use local fakes (httptest servers + in-memory fakes) — no
  real HA, Google, or Hermes calls.
- Layout: `cmd/homdash` (entrypoint), `internal/api` (Gin handlers),
  `internal/calendar` (Google client), `internal/ha` (HA client),
  `internal/hermes` (Hermes client), `internal/store` (push persistence),
  `internal/config` (env + board config), `web/` (React app).
