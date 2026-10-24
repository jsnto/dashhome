# dashhome Android app

A mobile client for the dashhome backend: the same data and sections the
web dashboard shows, usable from a phone or a tablet.

The layout adapts to the display: phones use a bottom navigation bar and
stacked panes; wide displays (screen width ≥ 600 dp, e.g. tablets) get
the web dashboard's one-page tile board: the four dashboard views (and
any Home Assistant tiles) are tiles on a cols × rows unit grid
(12 × 12 by default), laid out from the board file — `board.grid` and
`board.views` from `GET /api/config` — each tile either at its stored
placement or auto-flowed (the pure math in `ui/state/BoardLayout`, the
port of the web's tileGrid.ts). The board's Edit tiles mode, the
per-tile settings modal, and the Home Assistant tiles work the same as
on the web (drag/resize with grid snapping and a refusal snap-back, the
tile's size step / Calendar view mode / HA entity·kind·label, the remove
control, and the editable grid dimensions in Settings) — see the board
description in the [main README](../README.md). Every tile shows its
real section content inline and carries a title bar; a built-in view's
title opens that section's existing full-screen view (drill down, back
returns to the board) — inside those views the Calendar's month grid and
selected day sit side by side, and the Hermes chat and push feed sit
side by side — while a Home Assistant tile's title fills the board with
that tile. Long content scrolls inside its own tile; the board itself
fits the display. Settings is reachable from the board's top bar, like
the web header's Settings button.

- **Calendar** — the same tabs/summary as the web: a Summary tab combining
  the calendars plus one tab per calendar, over the render the board's
  `calendar_mode` picks — the month grid with day detail ("month", the
  default) or the schedule summary of upcoming events ("schedule")
  (`GET /api/calendars`, `GET /api/events`).
- **Home** — the real Home Assistant dashboard, same embed as the web's
  Home card. The URL comes from `GET /api/ha/embed-url` (assembled
  server-side; the token only appears when `ha_embed.token_url` is set)
  and is shown in a WebView. A WebView is a top-level page, so HA's
  `X-Frame-Options: SAMEORIGIN` header does not block it the way it can
  block the web's cross-origin iframe. "Browser" opens the same URL in
  the system browser.
- **Hermes** — the chat box (`POST /api/hermes/chat`, the whole
  conversation thread is sent as `conversation`, same as the web), with
  the mic button taking spoken input: the platform's speech recognition
  feeds the same send path (interim results live, the final transcript
  sent) and the reply to a voice-originated message is read aloud
  (TextToSpeech) — the mobile counterpart of the web's browser mic, with
  a graceful note when recognition or the microphone permission is
  unavailable — and the markdown push feed: the latest push plus date
  tabs for previous pushes, polled on the board's `refresh_seconds`
  interval (`GET /api/hermes/pushes[/:id]`). Markdown is rendered
  without raw-HTML support, matching the web.
- **Settings** — the web's settings panel sections: Appearance (the
  light/dark theme pick, persisted client-side; the OS preference
  applies and is followed live until the user picks one), Tiles (the
  board's tile set and order, size steps, the Calendar tile's view mode,
  and the board grid dimensions — `PUT /api/config/views` /
  `PUT /api/config/grid`), Home Assistant (the HA board tiles — entity,
  kind, label, picked from `GET /api/ha/entities` — with a new tile
  default-placed at the first free 2 × 2 cell), plus the app's own
  Server (the dashhome server URL, LAN address), the read-only
  integration summary, the live-update interval (persisted server-side
  via `PUT /api/config/refresh`), and the danger zone (forget this
  server).

The app talks only to the dashhome backend API; no backend changes are
needed. The dashboard has no login, so the server URL and the theme pick
are the only client-side settings (SharedPreferences; the web persists
its theme pick in localStorage).

## Building

No Android SDK or JDK is required on the build host — only a Docker
daemon. The build mirrors the lumi Android Docker pattern:

```sh
./scripts/build-apk-docker.sh
```

- Output: `dist/dashhome-android-debug.apk` (repo root).
- The first run downloads the Android SDK (~500 MB) and the Gradle
  distribution into a Docker image; later runs reuse the
  `android_gradle-cache` volume for incremental builds and the
  `dashhome-android-keystore` volume for the debug signing key, so every
  APK is signed with the same key and on-device updates don't fail with
  a signature conflict.
- Overrides: `DASHHOME_DOCKER_IMAGE=<image>`, `OUT_DIR=<dir>`,
  `DASHHOME_KEYSTORE_VOLUME=<volume>`.

Install on a device (USB or wireless ADB):

```sh
adb install -r dist/dashhome-android-debug.apk
```

On first launch, enter the dashhome server's LAN URL (e.g.
`http://10.0.0.4:3000`) on the setup screen and press **Connect** — the
app verifies the server with `GET /api/config` before saving it.

## Unit tests

The parsing/state logic (API JSON parsing, month grid, event formatting,
push feed ordering, chat thread state, the board's grid layout math —
normalization, auto flow, bounds, and the drag/resize pointer-to-cell
math) is covered by JVM unit tests; the markdown push feed is covered by
a Robolectric render test that runs the real Compose + Markwon stack and
asserts the body TextView is painted with the theme's visible text color
(regression guard for the "invisible body" bug: Compose packs the ARGB
in the top 32 bits of `Color.value`, so a platform `TextView` needs
`toArgb()`, not `value.toInt()`); and the tablet board's UI — drill-down
navigation, the wide-display routing boundary, Edit-tiles drag/resize,
the settings flow, and the theme pick — is covered by Robolectric
Compose tests that run the board against an in-test fake backend (an
in-memory board that records the app's writes):

```sh
docker run --rm -v "$HOME/.gradle":/home/gradle/.gradle -w /src/android \
  dashhome-apk-builder:1 ./gradlew testDebugUnitTest --no-daemon
```

(or the same `./gradlew testDebugUnitTest` inside the build image).
These run without a device; the pure-JVM tests use the `org.json:json`
artifact to mirror the platform `org.json`, and the Robolectric tests
use the `android-all` framework jar Robolectric downloads on first run.

## Stack

- Kotlin + Jetpack Compose (Material 3), single `:app` module.
- OkHttp for the API client; org.json for parsing (no serialization codegen).
- Markwon for markdown rendering (tables + strikethrough extensions; no
  HTML extension).
- AGP 8.7.3, Kotlin 2.0.21, compile/target SDK 35, min SDK 26
  (Android 8.0 — the test device runs Android 17).
- `android:usesCleartextTraffic="true"` so plain-HTTP LAN dashhome
  instances (and HTTP Home Assistant instances) work.

## Layout

```
android/
  Dockerfile                     # JDK 17 + Android SDK 35 build image
  gradle/                        # wrapper + version catalog
  app/
    src/main/kotlin/com/jsnto/dashhome/
      api/        # OkHttp client, JSON parsers, API models
      data/       # SharedPreferences (server URL, theme pick)
      ui/         # Compose screens: Calendar, Home (WebView), Hermes,
                  # Settings, the tablet tile board + per-tile settings
                  # modal + HA tile view, setup, shared components, theme
      ui/state/   # state logic: pure, unit-tested (month grid, schedule,
                  # push feed, chat thread, board-grid layout), plus the
                  # shared board/section state and the platform voice
                  # input + read-aloud
      markdown/   # Markwon renderer + Compose MarkdownText
    src/test/kotlin/             # JVM unit tests
```
