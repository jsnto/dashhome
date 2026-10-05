# dashhome Android app

A mobile client for the dashhome backend: the same data and sections the
web dashboard shows, usable from a phone or a tablet.

The layout adapts to the display: phones use a bottom navigation bar and
stacked panes; wide displays (screen width ≥ 600 dp, e.g. tablets) get a
one-page board — all four items (Calendar, Home, Hermes, Settings) as
tappable cards, each opening that section's existing full-screen view,
with back returning to the board. Inside those views the Calendar's
month grid and selected day sit side by side, and the Hermes chat and
push feed sit side by side — the same side-by-side reading as the web
dashboard.

- **Calendar** — the same tabs/summary as the web: a Summary tab combining
  every visible calendar plus one tab per calendar, over a month grid with
  day detail (`GET /api/calendars`, `GET /api/events`).
- **Home** — the real Home Assistant dashboard, same embed as the web's
  Home card. The URL comes from `GET /api/ha/embed-url` (assembled
  server-side; the token only appears when `ha_embed.token_url` is set)
  and is shown in a WebView. A WebView is a top-level page, so HA's
  `X-Frame-Options: SAMEORIGIN` header does not block it the way it can
  block the web's cross-origin iframe. "Browser" opens the same URL in
  the system browser.
- **Hermes** — the chat box (`POST /api/hermes/chat`, the whole
  conversation thread is sent as `conversation`, same as the web) and the
  markdown push feed: the latest push plus date tabs for previous pushes,
  polled on the board's `refresh_seconds` interval
  (`GET /api/hermes/pushes[/:id]`). Markdown is rendered without
  raw-HTML support, matching the web.
- **Settings** — the dashhome server URL (LAN address), the live-update
  interval (persisted server-side via `PUT /api/config/refresh`), and the
  read-only integration summary the web's settings panel shows.

The app talks only to the dashhome backend API; no backend changes are
needed. The dashboard has no login, so the server URL is the only
client-side setting.

## Building

No Android SDK or JDK is required on the build host — only a Docker
daemon. The build mirrors the lumi Android Docker pattern:

```sh
./android/scripts/build-apk-docker.sh
```

- Output: `dist/dashhome-android-debug.apk` (repo root).
- The first run downloads the Android SDK (~500 MB) and the Gradle
  distribution into a Docker image; later runs reuse the
  `dashhome-gradle-cache` volume for incremental builds.
- Overrides: `DASHHOME_DOCKER_IMAGE=<image>`, `OUT_DIR=<dir>`.

Install on a device (USB or wireless ADB):

```sh
adb install -r dist/dashhome-android-debug.apk
```

On first launch, enter the dashhome server's LAN URL (e.g.
`http://10.0.0.4:3000`) on the setup screen and press **Connect** — the
app verifies the server with `GET /api/config` before saving it.

## Unit tests

The parsing/state logic (API JSON parsing, month grid, event formatting,
push feed ordering, chat thread state) is covered by JVM unit tests, and
the markdown push feed is covered by a Robolectric render test that runs
the real Compose + Markwon stack and asserts the body TextView is painted
with the theme's visible text color (regression guard for the
"invisible body" bug: Compose packs the ARGB in the top 32 bits of
`Color.value`, so a platform `TextView` needs `toArgb()`, not
`value.toInt()`):

```sh
docker run --rm -v "$HOME/.gradle":/home/gradle/.gradle -w /src/android \
  dashhome-apk-builder:1 ./gradlew testDebugUnitTest --no-daemon
```

(or the same `./gradlew testDebugUnitTest` inside the build image).
These run without a device; the pure-JVM tests use the `org.json:json`
artifact to mirror the platform `org.json`, and the Robolectric test
uses the `android-all` framework jar Robolectric downloads on first run.

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
  scripts/build-apk-docker.sh    # the entry point (see above)
  gradle/                        # wrapper + version catalog
  app/
    src/main/kotlin/com/jsnto/dashhome/
      api/        # OkHttp client, JSON parsers, API models
      data/       # SharedPreferences (server URL)
      ui/         # Compose screens: Calendar, Home (WebView), Hermes,
                  # Settings, setup, shared components, theme
      ui/state/   # pure, unit-tested state logic (month grid, push feed,
                  # chat thread)
      markdown/   # Markwon renderer + Compose MarkdownText
    src/test/kotlin/             # JVM unit tests
```
