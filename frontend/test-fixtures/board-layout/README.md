# Board-layout golden vectors (cross-language contract fixtures)

Location: `frontend/test-fixtures/board-layout/` — inside the web
package so the web image build (whose Docker context is the package
directory) can type-check the web vector test, which imports the files.

The board-layout domain is implemented three times — the Go server
(`backend/config/config.go`, the source of truth for the board-file
rules), the web client (`frontend/src/utils.ts` +
`frontend/src/tileGrid.ts`), and the Android client
(`android/.../ui/state/BoardLayout.kt`, the port of the web's
`tileGrid.ts`) — plus a JSON data model. These fixture files are the
shared golden vectors: the **same input/expected-output pairs, asserted
by all three suites**, so a rule change that lands in one language
without the others fails the shared vectors.

- Go: `backend/config/board_vectors_test.go`
- web: `frontend/src/boardVectors.test.ts`
- Android: `android/app/src/test/kotlin/com/jsnto/dashhome/ui/state/BoardVectorTest.kt`

Each suite loads these files verbatim (Go walks up to the repository
root and reads them under the web package, the web test resolves them
relative to the source tree, the Android test reads them from the
unit-test classpath — see `android/app/build.gradle.kts`). No suite
keeps its own copy of a vector.

The vectors encode **currently verified behavior**, with the Go server
as the source of truth: every expected output is what the server's
normalization produces, and every vector passes against the current
web/Android implementations (the suites are the proof). A vector that
would require changing server or client behavior is not a vector — the
only sanctioned behavior change is the tile-label convergence below.

## The label-trim convergence (pinned here)

The board-tile display name (`tileName`) and the server's entity-label
rule (`Config.HAEntityLabel`) use the label **as stored: no trim** — a
non-empty label wins, the empty label falls back. A drift had crept
into two client copies that treated a blank (whitespace-only) label as
empty (the web Settings panel and the Android `tileName`); both now
converge on the server's no-trim rule. The vectors that pin it:

- `ha-tiles.json` → `display_names` (the `ha-4` vector: a whitespace
  label is shown as stored) — asserted by the web and Android suites;
- `ha-tiles.json` → `server_label_fallback` (the `keeps-a-blank-label`
  vector: the server keeps a whitespace label) — asserted by the Go
  suite, the source of truth the convergence follows.

## Per-file contract

### `grid.json` — grid repair (`normalizeGrid` / `BoardGrid.effective`)

`input` is the stored `board.grid` (an object with integer `cols`/`rows`,
or `null`); `expected` is the repaired grid. JSON-level repair of a
non-integer or string dimension (the Go `BoardGrid.UnmarshalJSON` /
web `Number.isInteger` / Android `optIntIfInt` boundaries) is a
parser-level rule and is tested per language, not here: the vectors
exercise the shared per-axis repair of already-parsed dimensions.

### `geometry.json` — `normalizeGeometry`

`input: {cols, rows, order, ids, raw}` — the grid dimensions, the board
order (the processing order: entries are considered in board order, so
`order` is a permutation of `ids`, the known tile-ID set, as
`normalizeViews` assembles it), and the raw per-tile geometry map. `raw`
values that are not integer `{x, y, w, h}` objects (unknown units) are
dropped by each language's own parse/validate boundary before
`normalizeGeometry` runs; that drop is part of the shared contract.
`expected` is the repaired map (kept entries only).

### `views.json` — `normalizeViews` over a raw board file

`input` is a raw board-file object (the freehand-editable surface:
`grid` and/or `views`); `expected` is the effective layout the server
answers with (`boardJSON()` → `ViewConfig()`): the repaired grid
dimensions and the normalized `views` (order a permutation of the board
tile IDs, hidden a subset, calendar_mode one of the two modes, sizes a
valid step for every tile ID, geometry in-bounds and overlap-free,
ha_tiles valid entries with distinct IDs).

The input is type-well-formed (strings where the model says strings,
integer cells where it says cells) but semantically malformed (unknown
tile IDs, out-of-bounds placements, unknown size steps, bad HA tile
entries) — that is the repair surface. A board file that is malformed at
the JSON type level (a non-string `order` entry, a non-object
`ha_tiles` entry, …) is a per-language parse failure and is tested per
language, not here.

### `ha-tiles.json` — Home Assistant tile-ID rules

- `id_shape`: `isHATileID` — `"ha-"` + 1–16 lowercase alphanumerics.
- `normalize`: `normalizeHATiles` — valid entries only (id shape,
  non-empty entity, one of the two kinds), duplicate IDs deduped (first
  wins). `entity` values are non-empty strings; a whitespace-only
  entity is a per-language divergence (the server keeps it, the clients
  drop it) and is deliberately not a shared vector.
- `next_id`: `nextHATileId` — the next free `ha-<n>` ID (the
  first-free rule the clients use when adding a tile; the server side
  is `config.nextHATileID`).
- `display_names`: `tileName` (the board-tile display name) — asserted
  by the web and Android suites.
- `server_label_fallback`: the server's `HAEntityLabel` rule
  (stored label → entity's own label → entity id, no trim) — asserted
  by the Go suite.

### `step-vocabulary.json` — the size-step vocabulary and `stepWidth`

`steps` pins the vocabulary every language agrees on: the step IDs,
their documented fraction of the grid width, and the human label
(`TILE_SIZE_LABELS`). `rejects` pins the out-of-vocabulary step IDs:
the Go suite asserts the server rejects each one (`isViewSize` over the
`viewSizes` set — the accepted step set `checkViewSizes` validates
against) and the web and Android suites assert each carries no
`TILE_SIZE_LABELS` entry. `widths` pins `stepWidth` (the column span an
auto-placed tile takes at each step on a `cols`-column grid, rounded to
whole units and clamped into the grid). The web and Android suites
assert the labels and the widths; the server does not compute step
widths or auto placement — those are client rendering rules.

### `auto-layout.json` — the auto-flow placement (`autoLayout`)

`input: {cols, order, sizes, fixed}` — the grid width, the board order,
the per-tile size steps, and the stored (fixed) placements; `expected`
is the deterministic sparse auto flow every auto-placed tile lands in
(stored placements act as obstacles without advancing the cursor; the
cursor only moves forward; gaps behind it are never backfilled).
Asserted by the web and Android suites — the two implementations of
the cursor (the Go server persists placements but never computes the
rendered auto flow).
