// Package config loads dashhome runtime configuration from environment
// variables and an optional local dashboard configuration file.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"

	"github.com/jsnto/dashhome/backend/calendar"
)

// Board is the local dashboard configuration: what the dashboard shows.
// It is deliberately kept separate from secrets (which come from the
// environment) so it can be edited freely. See dashhome.example.json.
type Board struct {
	// HAEntityIDs is the set of Home Assistant entity IDs the entity API
	// returns (GET /api/ha/entities), in order. Empty means "all entities".
	HAEntityIDs []string `json:"ha_entities"`
	// HALabels maps an entity ID to a friendlier display label.
	HALabels map[string]string `json:"ha_labels"`
	// Calendars is the set of Google calendars shown, in tab order.
	// Empty means "show every calendar visible to the credentials".
	Calendars []CalendarRef `json:"calendars"`
	// RefreshSeconds is a hint for UI polling intervals. Default 30.
	RefreshSeconds int `json:"refresh_seconds"`
	// HAEmbed configures the full-dashboard iframe section. Optional;
	// missing means "embed the default dashboard, no token in the URL".
	HAEmbed *HAEmbed `json:"ha_embed,omitempty"`
	// Grid is the tile grid's dimensions (columns x rows). Optional;
	// missing (or an invalid stored value) means the default 12x12.
	Grid *BoardGrid `json:"grid,omitempty"`
	// Views configures the dashboard's tile layout: which tiles are shown,
	// in what order, how the Calendar tile renders, and how big each tile
	// is. Optional; missing means the default board (all four tiles in the
	// original order, month-grid calendar, every tile at the default
	// size).
	Views *Views `json:"views,omitempty"`
	// Services holds the service-integration settings the settings screen
	// persists: Home Assistant, Google Calendar, Hermes chat, Hermes push.
	// Every field is an optional override of the environment value of the
	// same name (the UI value wins when set, the environment value is the
	// fallback, and an empty stored value falls back to it). Secrets live
	// here with the same local trust boundary as .env: the file is
	// git-ignored and never leaves the host.
	Services *Services `json:"services,omitempty"`
}

// Services is the settings screen's service configuration (board
// .services): one optional section per integrated service. A missing
// section, a missing field, or an empty field means "use the environment
// fallback (or the documented default)".
type Services struct {
	HomeAssistant *ServiceHomeAssistant `json:"homeassistant,omitempty"`
	Google        *ServiceGoogle        `json:"google,omitempty"`
	HermesChat    *ServiceHermesChat    `json:"hermes_chat,omitempty"`
	HermesPush    *ServiceHermesPush    `json:"hermes_push,omitempty"`
}

// ServiceHomeAssistant is the UI override of HOMEASSISTANT_URL /
// HOMEASSISTANT_TOKEN.
type ServiceHomeAssistant struct {
	URL   string `json:"url,omitempty"`
	Token string `json:"token,omitempty"` // secret
}

// ServiceGoogle is the UI override of the GOOGLE_* credential settings.
// Credential-mode precedence when several are set (mirroring NewGoogle):
// static access token, then service account, then OAuth client.
type ServiceGoogle struct {
	ServiceAccountFile string `json:"service_account_file,omitempty"`
	Domain             string `json:"domain,omitempty"`
	ClientID           string `json:"client_id,omitempty"`
	ClientSecret       string `json:"client_secret,omitempty"` // secret
	RedirectURI        string `json:"redirect_uri,omitempty"`
	TokenCache         string `json:"token_cache,omitempty"`
	AccessToken        string `json:"access_token,omitempty"` // secret
	Endpoint           string `json:"endpoint,omitempty"`
}

// ServiceHermesChat is the UI override of HERMES_BASE_URL /
// HERMES_CHAT_PATH / HERMES_AUTH_HEADER / HERMES_AUTH_VALUE.
type ServiceHermesChat struct {
	BaseURL    string `json:"base_url,omitempty"`
	ChatPath   string `json:"chat_path,omitempty"`
	AuthHeader string `json:"auth_header,omitempty"`
	AuthValue  string `json:"auth_value,omitempty"` // secret
}

// ServiceHermesPush is the UI override of HERMES_PUSH_TOKEN.
type ServiceHermesPush struct {
	Token string `json:"token,omitempty"` // secret
}

// ServicesUpdate is a partial service-settings update (PUT
// /api/config/services). Each group is nil = leave the group alone; within
// a group each field is nil = leave the field alone (a JSON null or an
// omitted key), "" clears the UI value (back to the environment fallback
// / documented default), and a non-empty string sets it.
type ServicesUpdate struct {
	HomeAssistant *ServiceHomeAssistantUpdate `json:"homeassistant"`
	Google        *ServiceGoogleUpdate        `json:"google"`
	HermesChat    *ServiceHermesChatUpdate    `json:"hermes_chat"`
	HermesPush    *ServiceHermesPushUpdate    `json:"hermes_push"`
}

type ServiceHomeAssistantUpdate struct {
	URL   *string `json:"url"`
	Token *string `json:"token"`
}

type ServiceGoogleUpdate struct {
	ServiceAccountFile *string `json:"service_account_file"`
	Domain             *string `json:"domain"`
	ClientID           *string `json:"client_id"`
	ClientSecret       *string `json:"client_secret"`
	RedirectURI        *string `json:"redirect_uri"`
	TokenCache         *string `json:"token_cache"`
	AccessToken        *string `json:"access_token"`
	Endpoint           *string `json:"endpoint"`
}

type ServiceHermesChatUpdate struct {
	BaseURL    *string `json:"base_url"`
	ChatPath   *string `json:"chat_path"`
	AuthHeader *string `json:"auth_header"`
	AuthValue  *string `json:"auth_value"`
}

type ServiceHermesPushUpdate struct {
	Token *string `json:"token"`
}

// CalendarRef names one Google calendar by ID with an optional label.
type CalendarRef struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}

// HAEmbed configures the Home section's dashboard iframe. The token itself
// never appears in the board file: when TokenURL is set, the backend
// assembles it into the embed URL server-side (GET /api/ha/embed-url).
type HAEmbed struct {
	// URLPath selects the dashboard to embed. nil means Home Assistant's
	// default dashboard. Leading/trailing slashes are ignored.
	URLPath *string `json:"url_path"`
	// TokenURL appends ?auth_long_lived=<token> to the embed URL. Default
	// false: the iframe relies on the browser's HA session (one-time
	// in-iframe login) instead of a URL token.
	TokenURL bool `json:"token_url"`
}

// Views is the dashboard's tile layout: which board tiles are shown, in
// what order, how the Calendar tile renders, how big each tile is, where
// each tile sits on the grid, and which Home Assistant tiles exist.
type Views struct {
	// Order lists the tile IDs in board order: the reading order of the
	// tile grid (row by row, left to right). After normalization it is
	// always a permutation of the board tile IDs (the four view IDs plus
	// the Home Assistant tile IDs), even for hidden tiles (they stay in
	// place, so re-showing restores the position).
	Order []string `json:"order"`
	// Hidden is the set of tile IDs hidden from the board (a subset of the
	// board tile IDs). A hidden tile is not rendered and needs no data
	// polling.
	Hidden []string `json:"hidden"`
	// CalendarMode is how the Calendar view renders: "month" is the
	// month grid, "schedule" a chronological list of upcoming events.
	CalendarMode string `json:"calendar_mode"`
	// Sizes maps each tile ID to its tile size step ("s", "m", "l", "xl":
	// a fraction of the grid's width — a third, half, two thirds, or the
	// full width) — the width of a tile without explicit geometry. After
	// normalization it always holds every board tile ID with a valid step.
	Sizes map[string]string `json:"sizes"`
	// Geometry maps a tile ID to its explicit grid placement (where the
	// tile starts on the tile grid and how far it spans). Tiles without an
	// entry (or with a repaired entry) keep auto-placement: they flow in
	// board order at their size step's width. After normalization the
	// entries are always in bounds and overlap-free.
	Geometry GeometryMap `json:"geometry,omitempty"`
	// HATiles is the set of Home Assistant board tiles; each one's ID is a
	// board tile ID alongside the four view IDs. After normalization the
	// list holds only valid entries with distinct IDs.
	HATiles []HABoardTile `json:"ha_tiles,omitempty"`
}

// TileGeometry is a tile's explicit grid placement on the board's tile
// grid (cols x rows units): the 1-based column (X) and row (Y) where the
// tile starts, its column span (W) and row span (H). The cell must stay
// inside the grid: 1 <= X <= cols-W+1, 1 <= W <= cols, 1 <= Y <= rows-H+1,
// 1 <= H <= rows.
type TileGeometry struct {
	X int `json:"x"`
	Y int `json:"y"`
	W int `json:"w"`
	H int `json:"h"`
}

// GeometryMap is the board file's per-tile geometry map. Values that do
// not decode as {x, y, w, h} objects (unknown units: a string, a bare
// number, a missing field of the wrong type) are dropped on load instead
// of failing the board file — the board file is a freehand-editable
// surface, and normalization repairs the rest. The tile then falls back
// to auto placement.
type GeometryMap map[string]*TileGeometry

// UnmarshalJSON decodes the geometry map, dropping entries whose value
// is not a valid {x, y, w, h} object.
func (m *GeometryMap) UnmarshalJSON(data []byte) error {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	out := GeometryMap{}
	for id, v := range raw {
		if string(v) == "null" {
			continue // null is "no placement"; not persisted
		}
		var g TileGeometry
		if err := json.Unmarshal(v, &g); err != nil {
			continue // unknown unit: repair by dropping the entry
		}
		out[id] = &g
	}
	*m = out
	return nil
}

// BoardGrid is the board's tile grid dimensions: the number of columns
// (Cols) and rows (Rows) the grid is split into. Each unit is one of the
// Cols columns and one of the Rows rows, so the default 12x12 grid splits
// the board's width and height evenly into 12 units each.
type BoardGrid struct {
	Cols int `json:"cols"`
	Rows int `json:"rows"`
}

// UnmarshalJSON decodes the grid dimensions tolerantly, per axis: a value
// that is not a whole number of units within the valid range (a fractional
// number, an out-of-range number, a string, a missing field) falls back to
// the per-axis default, and a grid that is not a JSON object falls back to
// the default 12x12 — the board file is a freehand-editable surface, so a
// mistyped dimension repairs to a valid grid instead of failing the board
// file load (and with it the whole backend startup).
func (g *BoardGrid) UnmarshalJSON(data []byte) error {
	var raw struct {
		Cols json.RawMessage `json:"cols"`
		Rows json.RawMessage `json:"rows"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		*g = BoardGrid{Cols: BoardGridDefaultCols, Rows: BoardGridDefaultRows}
		return nil
	}
	*g = BoardGrid{
		Cols: gridAxis(raw.Cols, BoardGridDefaultCols),
		Rows: gridAxis(raw.Rows, BoardGridDefaultRows),
	}
	return nil
}

// gridAxis repairs one grid axis to a valid number of units: a whole
// number within [BoardGridMin, BoardGridMax] is kept, anything else (a
// fractional or out-of-range number, a string, a missing value) falls
// back to the per-axis default.
func gridAxis(raw json.RawMessage, def int) int {
	var n float64
	if err := json.Unmarshal(raw, &n); err != nil {
		return def
	}
	if n < BoardGridMin || n > BoardGridMax || n != math.Trunc(n) {
		return def
	}
	return int(n)
}

// effective returns the grid dimensions with stored values repaired to the
// valid range: an absent grid, or an out-of-range dimension, falls back to
// the per-axis default (the board file is a freehand-editable surface).
func (g *BoardGrid) effective() (int, int) {
	cols, rows := BoardGridDefaultCols, BoardGridDefaultRows
	if g != nil {
		if g.Cols >= BoardGridMin && g.Cols <= BoardGridMax {
			cols = g.Cols
		}
		if g.Rows >= BoardGridMin && g.Rows <= BoardGridMax {
			rows = g.Rows
		}
	}
	return cols, rows
}

// HABoardTile is a Home Assistant board tile: it binds one HA entity, has
// a display label, and a kind. A "button" tile performs a standard action
// on the entity when clicked; a "visual" tile shows the entity's current
// state, refreshed with the board's polling.
type HABoardTile struct {
	// ID is the tile's board ID: "ha-" plus a lowercase alphanumeric
	// suffix (e.g. "ha-1"). The prefix keeps it clear of the four view
	// IDs.
	ID string `json:"id"`
	// Entity is the Home Assistant entity ID the tile binds to.
	Entity string `json:"entity"`
	// Label is the tile's display label; empty falls back to the entity's
	// configured label / friendly name.
	Label string `json:"label"`
	// Kind is "button" or "visual".
	Kind string `json:"kind"`
}

// The four board views, in the default board order (the tile grid's
// reading order: row by row, left to right).
const (
	ViewCalendar = "calendar"
	ViewChat     = "chat"
	ViewHome     = "home"
	ViewPush     = "push"

	CalendarModeMonth    = "month"
	CalendarModeSchedule = "schedule"
)

// The tile size steps, from smallest to widest. The step is a fraction of
// the grid's width: "m" (half the board's width) is the default, so four
// medium tiles form the board's default 2x2.
const (
	ViewSizeSmall  = "s"  // a third of the grid's width
	ViewSizeMedium = "m"  // half the grid's width (the default)
	ViewSizeLarge  = "l"  // two thirds of the grid's width
	ViewSizeXLarge = "xl" // the full grid width
)

var viewIDs = []string{ViewCalendar, ViewChat, ViewHome, ViewPush}

var viewSizes = []string{ViewSizeSmall, ViewSizeMedium, ViewSizeLarge, ViewSizeXLarge}

// The board's tile grid is cols x rows units, each unit split evenly (one
// of `cols` columns, one of `rows` rows). The dimensions are user-editable
// from the settings screen (persisted as board.grid) and default to 12x12;
// the bounds keep the units usable on screen.
const (
	BoardGridDefaultCols = 12
	BoardGridDefaultRows = 12
	BoardGridMin         = 1
	BoardGridMax         = 24
)

// The Home Assistant tile kinds.
const (
	HATileButton = "button"
	HATileVisual = "visual"
)

// HATileIDPrefix is the required prefix of Home Assistant tile IDs, so a
// tile ID never collides with the four view IDs.
const HATileIDPrefix = "ha-"

// haTileIDSuffixBound caps the suffix length of an HA tile ID ("ha-" plus
// up to 16 lowercase alphanumeric characters).
const haTileIDSuffixBound = 16

// GoogleConfig carries the Google Calendar credential settings. It is a
// type alias for calendar.GoogleSettings so the loaded config and the
// calendar client share one type instead of a field-for-field copy.
type GoogleConfig = calendar.GoogleSettings

// Config is the full runtime configuration. The service fields (HAURL,
// HAToken, Hermes*, PushToken, Google) hold the environment values — the
// fallback layer of the UI > env > default precedence; the effective
// service settings on use come from the *Effective accessors (HASettings,
// HermesChatSettings, PushTokenEffective, GoogleSettings), which also read
// the board's services section, so a value changed in the settings UI
// takes effect without a restart.
type Config struct {
	// Port is the HTTP listen port. Default 3000.
	Port string

	// Home Assistant (environment fallback values).
	HAURL   string
	HAToken string

	// Hermes chat (environment fallback values).
	HermesBaseURL    string
	HermesChatPath   string
	HermesAuthHeader string // request header name to send ("" = none)
	HermesAuthValue  string // full header value, e.g. "Bearer sk-..."

	// Hermes push auth (environment fallback value): shared token Hermes
	// must present when pushing.
	PushToken string

	// Google (environment fallback values).
	Google GoogleConfig

	// DataDir holds durable local state (push records, token cache...).
	DataDir string
	// BoardFile is the dashboard config file path. Optional.
	BoardFile string
	// Board is the parsed dashboard configuration. Guarded by boardMu;
	// readers that may race with SetRefreshSeconds must use BoardSnapshot.
	Board Board
	// boardMu guards Board against concurrent access.
	boardMu sync.RWMutex
}

// Refresh interval bounds: sane polling cadence for the Hermes push panel
// (no faster than hammering the upstream services, no slower than "live").
const (
	RefreshSecondsMin = 5
	RefreshSecondsMax = 3600
)

// ErrNoBoardFile is returned when the board file path is not configured.
var ErrNoBoardFile = errors.New("no board file configured (set CONFIG_FILE)")

// ErrBoardFileNotWritable is returned when the board could not be persisted
// to the board file (the path is a directory — Docker's auto-created source
// for an absent bind-mounted file — or the file/mount is not writable).
var ErrBoardFileNotWritable = errors.New("board file not writable")

// HAConfigured reports whether Home Assistant is configured using the
// effective settings (UI value, then environment).
func (c *Config) HAConfigured() bool {
	url, token := c.HASettings()
	return url != "" && token != ""
}

// HermesConfigured reports whether the Hermes chat integration is
// configured using the effective settings (UI value, then environment).
func (c *Config) HermesConfigured() bool {
	baseURL, _, _, _ := c.HermesChatSettings()
	return baseURL != ""
}

// PushConfigured reports whether authenticated Hermes pushes are enabled
// using the effective settings (UI value, then environment).
func (c *Config) PushConfigured() bool { return c.PushTokenEffective() != "" }

// firstNonEmpty returns the first non-empty of vals (or "").
func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// uiServices returns a copy of the board's services section (a zero value
// when absent). Safe to read while the board mutates. The returned copy
// shares the section subtrees, which are immutable once published (every
// SetServices write installs a fresh tree), so the fields may be read
// after the unlock.
func (c *Config) uiServices() Services {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	if c.Board.Services == nil {
		return Services{}
	}
	return *c.Board.Services
}

// HASettings returns the effective Home Assistant settings: the UI value
// (board.services.homeassistant), then the environment value, then the
// documented default (empty for both fields). Resolved on use, so a value
// changed in the settings UI takes effect without a restart.
func (c *Config) HASettings() (baseURL, token string) {
	s := c.uiServices()
	var ui ServiceHomeAssistant
	if s.HomeAssistant != nil {
		ui = *s.HomeAssistant
	}
	return firstNonEmpty(ui.URL, c.HAURL), firstNonEmpty(ui.Token, c.HAToken)
}

// HermesChatSettings returns the effective Hermes chat settings: the UI
// value (board.services.hermes_chat), then the environment value, then the
// documented defaults (chat path /chat/completions, auth header
// Authorization — applied by Load to the environment values). Resolved on
// use, so a value changed in the settings UI takes effect without a
// restart.
func (c *Config) HermesChatSettings() (baseURL, chatPath, authHeader, authValue string) {
	s := c.uiServices()
	var ui ServiceHermesChat
	if s.HermesChat != nil {
		ui = *s.HermesChat
	}
	return firstNonEmpty(ui.BaseURL, c.HermesBaseURL),
		firstNonEmpty(ui.ChatPath, c.HermesChatPath),
		firstNonEmpty(ui.AuthHeader, c.HermesAuthHeader),
		firstNonEmpty(ui.AuthValue, c.HermesAuthValue)
}

// PushTokenEffective returns the effective Hermes push token: the UI value
// (board.services.hermes_push.token), then the environment value, then the
// documented default (empty = push ingestion disabled). Resolved on use,
// so a value changed in the settings UI takes effect without a restart.
func (c *Config) PushTokenEffective() string {
	s := c.uiServices()
	var ui ServiceHermesPush
	if s.HermesPush != nil {
		ui = *s.HermesPush
	}
	return firstNonEmpty(ui.Token, c.PushToken)
}

// GoogleSettings returns the effective Google Calendar settings: each
// field the UI value (board.services.google), then the environment value,
// then the documented default (empty, except the token cache, which
// defaults under DATA_DIR and is applied by Load to the environment
// value). Resolved on use, so a value changed in the settings UI takes
// effect without a restart.
func (c *Config) GoogleSettings() GoogleConfig {
	s := c.uiServices()
	g := c.Google
	if s.Google != nil {
		ui := *s.Google
		g.ServiceAccountFile = firstNonEmpty(ui.ServiceAccountFile, g.ServiceAccountFile)
		g.Domain = firstNonEmpty(ui.Domain, g.Domain)
		g.ClientID = firstNonEmpty(ui.ClientID, g.ClientID)
		g.ClientSecret = firstNonEmpty(ui.ClientSecret, g.ClientSecret)
		g.RedirectURI = firstNonEmpty(ui.RedirectURI, g.RedirectURI)
		g.TokenCache = firstNonEmpty(ui.TokenCache, g.TokenCache)
		g.AccessToken = firstNonEmpty(ui.AccessToken, g.AccessToken)
		g.Endpoint = firstNonEmpty(ui.Endpoint, g.Endpoint)
	}
	return g
}

// BoardSnapshot returns a copy of the board that is safe to read while
// SetRefreshSeconds may be updating it from another goroutine.
func (c *Config) BoardSnapshot() Board {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	return c.Board
}

// RefreshSeconds returns the UI polling hint with a sane default.
func (c *Config) RefreshSeconds() int {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	if c.Board.RefreshSeconds <= 0 {
		return 30
	}
	return c.Board.RefreshSeconds
}

// GridDims returns the effective tile grid dimensions (columns, rows):
// the stored board.grid repaired to the valid range, the default 12x12
// when absent (per axis). Safe to read while the board mutates.
func (c *Config) GridDims() (int, int) {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	return c.Board.Grid.effective()
}

// Load reads configuration from the environment. A missing, empty,
// unreadable, or directory board file is tolerated as an empty board (the
// file is optional); a malformed board file is an error.
func Load() (*Config, error) {
	port := env("PORT", "3000")
	if _, err := strconv.Atoi(port); err != nil {
		return nil, fmt.Errorf("PORT %q is not a valid port", port)
	}

	cfg := &Config{
		Port:    port,
		HAURL:   env("HOMEASSISTANT_URL", ""),
		HAToken: env("HOMEASSISTANT_TOKEN", ""),

		HermesBaseURL:    env("HERMES_BASE_URL", ""),
		HermesChatPath:   env("HERMES_CHAT_PATH", "/chat/completions"),
		HermesAuthHeader: env("HERMES_AUTH_HEADER", "Authorization"),
		HermesAuthValue:  env("HERMES_AUTH_VALUE", ""),

		PushToken: env("HERMES_PUSH_TOKEN", ""),

		Google: GoogleConfig{
			Endpoint:           env("GOOGLE_API_ENDPOINT", ""),
			AccessToken:        env("GOOGLE_ACCESS_TOKEN", ""),
			ServiceAccountFile: env("GOOGLE_APPLICATION_CREDENTIALS", ""),
			Domain:             env("GOOGLE_DOMAIN", ""),
			ClientID:           env("GOOGLE_CLIENT_ID", ""),
			ClientSecret:       env("GOOGLE_CLIENT_SECRET", ""),
			RedirectURI:        env("GOOGLE_REDIRECT_URI", ""),
			TokenCache:         env("GOOGLE_TOKEN_CACHE", ""),
		},

		DataDir:   env("DATA_DIR", "./data"),
		BoardFile: env("CONFIG_FILE", "./dashhome.json"),
	}

	if cfg.Google.TokenCache == "" {
		cfg.Google.TokenCache = cfg.DataDir + "/google-token.json"
	}

	if err := cfg.loadBoard(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func (c *Config) loadBoard() error {
	// A directory where the board file should be is treated as "no board
	// file": Docker auto-creates a missing bind-mount source as a directory
	// at the host path, so a configured-but-absent ./dashhome.json reaches
	// the container as a directory at the CONFIG_FILE path.
	if st, err := os.Stat(c.BoardFile); err == nil && st.IsDir() {
		log.Printf("board file %s is a directory; treating it as absent (no board config)", c.BoardFile)
		return nil
	}
	data, err := os.ReadFile(c.BoardFile)
	if err != nil {
		if os.IsNotExist(err) {
			return nil // optional file: empty board
		}
		// Unreadable (permissions, I/O failure): tolerate like an absent
		// file so the stack stays up; the dashboard simply runs unconfigured.
		log.Printf("board file %s is unreadable (%v); treating it as absent (no board config)", c.BoardFile, err)
		return nil
	}
	if len(data) == 0 {
		return nil
	}
	var b Board
	if err := json.Unmarshal(data, &b); err != nil {
		return fmt.Errorf("parse board file %s: %w", c.BoardFile, err)
	}
	c.Board = b
	return nil
}

// CalendarLabel returns the configured label for a calendar ID, or summary
// when no label is configured.
func (c *Config) CalendarLabel(id, summary string) string {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	for _, ref := range c.Board.Calendars {
		if ref.ID == id && ref.Label != "" {
			return ref.Label
		}
	}
	if summary != "" {
		return summary
	}
	return id
}

// HAEmbed returns a copy of the configured embed settings (nil when the
// board does not set ha_embed). Safe to read while the board mutates.
func (c *Config) HAEmbed() *HAEmbed {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	if c.Board.HAEmbed == nil {
		return nil
	}
	e := *c.Board.HAEmbed
	return &e
}

// HAEntityLabel returns the configured label for an entity ID, or the
// friendly name as fallback.
func (c *Config) HAEntityLabel(id, friendlyName string) string {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	if label, ok := c.Board.HALabels[id]; ok && label != "" {
		return label
	}
	if friendlyName != "" {
		return friendlyName
	}
	return id
}

// ValidateRefreshSeconds reports whether n is a sane UI polling interval.
func ValidateRefreshSeconds(n int) error {
	if n < RefreshSecondsMin || n > RefreshSecondsMax {
		return fmt.Errorf("refresh_seconds must be between %d and %d", RefreshSecondsMin, RefreshSecondsMax)
	}
	return nil
}

// ValidateGrid reports whether dims are a sane tile grid: each dimension
// a whole number of units within the usable range.
func ValidateGrid(cols, rows int) error {
	if cols < BoardGridMin || cols > BoardGridMax || rows < BoardGridMin || rows > BoardGridMax {
		return fmt.Errorf("grid dimensions must be between %d and %d units", BoardGridMin, BoardGridMax)
	}
	return nil
}

// SetGrid validates the grid dimensions, persists the whole board to the
// board file, and updates the in-memory board only after the write
// succeeds, so a failed write leaves the running config (and the file)
// unchanged. Stored placements that no longer fit the new grid are
// repaired at the same time (their tiles fall back to auto placement),
// exactly as a views write repairs them. Mirrors SetRefreshSeconds: a
// validation error is returned as-is, and a persistence failure is wrapped
// in ErrBoardFileNotWritable (an absent board file path is
// ErrNoBoardFile).
func (c *Config) SetGrid(cols, rows int) error {
	if err := ValidateGrid(cols, rows); err != nil {
		return err
	}
	if c.BoardFile == "" {
		return ErrNoBoardFile
	}
	c.boardMu.Lock()
	updated := c.Board
	updated.Grid = &BoardGrid{Cols: cols, Rows: rows}
	if updated.Views != nil {
		views := *updated.Views
		repaired := normalizeViews(&views, cols, rows)
		updated.Views = &repaired
	}
	err := WriteBoardFile(c.BoardFile, updated)
	if err == nil {
		c.Board = updated
	}
	c.boardMu.Unlock()
	if err != nil {
		return fmt.Errorf("%w: %w", ErrBoardFileNotWritable, err)
	}
	return nil
}

// SetRefreshSeconds validates n, persists the whole board to the board file,
// and updates the in-memory board only after the write succeeds, so a failed
// write leaves the running config (and the file) unchanged. A persistence
// failure is wrapped in ErrBoardFileNotWritable (a storage error, distinct
// from a bad n).
func (c *Config) SetRefreshSeconds(n int) error {
	if err := ValidateRefreshSeconds(n); err != nil {
		return err
	}
	if c.BoardFile == "" {
		return ErrNoBoardFile
	}
	c.boardMu.Lock()
	updated := c.Board
	updated.RefreshSeconds = n
	err := WriteBoardFile(c.BoardFile, updated)
	if err == nil {
		c.Board = updated
	}
	c.boardMu.Unlock()
	if err != nil {
		return fmt.Errorf("%w: %w", ErrBoardFileNotWritable, err)
	}
	return nil
}

// SetServices persists a partial service-settings update (board.services)
// to the board file. Each group is nil = leave the group alone; within a
// group, each field is nil = leave it alone, "" clears the UI value (back
// to the environment fallback / documented default), and a non-empty
// string sets it. An update with no effective field change is a no-op
// (nothing is written). Mirrors SetViews: the in-memory board is updated
// only after the write succeeds, so a failed write leaves the running
// config (and the file) unchanged, and a persistence failure is wrapped
// in ErrBoardFileNotWritable (an absent board file path is
// ErrNoBoardFile).
func (c *Config) SetServices(u *ServicesUpdate) error {
	if c.BoardFile == "" {
		return ErrNoBoardFile
	}
	c.boardMu.Lock()
	// Deep-copy the current services tree before mutating: the board
	// struct copy shares the subtree pointers, and a failed write must not
	// have mutated the live board.
	cur := cloneServices(c.Board.Services)
	changed := false
	if u.HomeAssistant != nil {
		if cur.HomeAssistant == nil {
			cur.HomeAssistant = &ServiceHomeAssistant{}
		}
		if u.HomeAssistant.URL != nil {
			cur.HomeAssistant.URL = *u.HomeAssistant.URL
			changed = true
		}
		if u.HomeAssistant.Token != nil {
			cur.HomeAssistant.Token = *u.HomeAssistant.Token
			changed = true
		}
	}
	if u.Google != nil {
		if cur.Google == nil {
			cur.Google = &ServiceGoogle{}
		}
		if u.Google.ServiceAccountFile != nil {
			cur.Google.ServiceAccountFile = *u.Google.ServiceAccountFile
			changed = true
		}
		if u.Google.Domain != nil {
			cur.Google.Domain = *u.Google.Domain
			changed = true
		}
		if u.Google.ClientID != nil {
			cur.Google.ClientID = *u.Google.ClientID
			changed = true
		}
		if u.Google.ClientSecret != nil {
			cur.Google.ClientSecret = *u.Google.ClientSecret
			changed = true
		}
		if u.Google.RedirectURI != nil {
			cur.Google.RedirectURI = *u.Google.RedirectURI
			changed = true
		}
		if u.Google.TokenCache != nil {
			cur.Google.TokenCache = *u.Google.TokenCache
			changed = true
		}
		if u.Google.AccessToken != nil {
			cur.Google.AccessToken = *u.Google.AccessToken
			changed = true
		}
		if u.Google.Endpoint != nil {
			cur.Google.Endpoint = *u.Google.Endpoint
			changed = true
		}
	}
	if u.HermesChat != nil {
		if cur.HermesChat == nil {
			cur.HermesChat = &ServiceHermesChat{}
		}
		if u.HermesChat.BaseURL != nil {
			cur.HermesChat.BaseURL = *u.HermesChat.BaseURL
			changed = true
		}
		if u.HermesChat.ChatPath != nil {
			cur.HermesChat.ChatPath = *u.HermesChat.ChatPath
			changed = true
		}
		if u.HermesChat.AuthHeader != nil {
			cur.HermesChat.AuthHeader = *u.HermesChat.AuthHeader
			changed = true
		}
		if u.HermesChat.AuthValue != nil {
			cur.HermesChat.AuthValue = *u.HermesChat.AuthValue
			changed = true
		}
	}
	if u.HermesPush != nil {
		if cur.HermesPush == nil {
			cur.HermesPush = &ServiceHermesPush{}
		}
		if u.HermesPush.Token != nil {
			cur.HermesPush.Token = *u.HermesPush.Token
			changed = true
		}
	}
	if !changed {
		c.boardMu.Unlock()
		return nil
	}
	updated := c.Board
	updated.Services = &cur
	err := WriteBoardFile(c.BoardFile, updated)
	if err == nil {
		c.Board = updated
	}
	c.boardMu.Unlock()
	if err != nil {
		return fmt.Errorf("%w: %w", ErrBoardFileNotWritable, err)
	}
	return nil
}

// cloneServices deep-copies the services tree (a nil source yields a zero
// value) so callers can mutate the copy without touching the live board.
func cloneServices(s *Services) Services {
	var out Services
	if s == nil {
		return out
	}
	out = *s
	if s.HomeAssistant != nil {
		p := *s.HomeAssistant
		out.HomeAssistant = &p
	}
	if s.Google != nil {
		p := *s.Google
		out.Google = &p
	}
	if s.HermesChat != nil {
		p := *s.HermesChat
		out.HermesChat = &p
	}
	if s.HermesPush != nil {
		p := *s.HermesPush
		out.HermesPush = &p
	}
	return out
}

// ServiceField is one service setting as exposed to the settings UI (GET
// /api/config/services): the UI-set value (empty = not set in the UI, so
// the fallback is active), the environment fallback value (for the UI's
// placeholder), and whether each layer is set. Secret fields never carry
// a value in either layer — only the set booleans — so a secret set in
// the UI or in .env is never displayed, only acknowledged.
type ServiceField struct {
	Value       string `json:"value"`
	Fallback    string `json:"fallback"`
	Set         bool   `json:"set"`
	FallbackSet bool   `json:"fallback_set"`
}

// ServicesView is the masked view of the service settings: non-secret
// fields expose the UI value and the environment fallback; secret fields
// expose only the set booleans.
type ServicesView struct {
	HomeAssistant ServiceHomeAssistantView `json:"homeassistant"`
	Google        ServiceGoogleView        `json:"google"`
	HermesChat    ServiceHermesChatView    `json:"hermes_chat"`
	HermesPush    ServiceHermesPushView    `json:"hermes_push"`
}

type ServiceHomeAssistantView struct {
	URL   ServiceField `json:"url"`
	Token ServiceField `json:"token"`
}

type ServiceGoogleView struct {
	ServiceAccountFile ServiceField `json:"service_account_file"`
	Domain             ServiceField `json:"domain"`
	ClientID           ServiceField `json:"client_id"`
	ClientSecret       ServiceField `json:"client_secret"`
	RedirectURI        ServiceField `json:"redirect_uri"`
	TokenCache         ServiceField `json:"token_cache"`
	AccessToken        ServiceField `json:"access_token"`
	Endpoint           ServiceField `json:"endpoint"`
}

type ServiceHermesChatView struct {
	BaseURL    ServiceField `json:"base_url"`
	ChatPath   ServiceField `json:"chat_path"`
	AuthHeader ServiceField `json:"auth_header"`
	AuthValue  ServiceField `json:"auth_value"`
}

type ServiceHermesPushView struct {
	Token ServiceField `json:"token"`
}

// ServicesView returns the masked view of the service settings (the UI
// values plus the environment fallbacks, secrets reduced to set booleans).
// Safe to read while the board mutates.
func (c *Config) ServicesView() ServicesView {
	s := c.uiServices()
	var uiHA ServiceHomeAssistant
	if s.HomeAssistant != nil {
		uiHA = *s.HomeAssistant
	}
	var uiG ServiceGoogle
	if s.Google != nil {
		uiG = *s.Google
	}
	var uiC ServiceHermesChat
	if s.HermesChat != nil {
		uiC = *s.HermesChat
	}
	var uiP ServiceHermesPush
	if s.HermesPush != nil {
		uiP = *s.HermesPush
	}
	return ServicesView{
		HomeAssistant: ServiceHomeAssistantView{
			URL:   field(uiHA.URL, c.HAURL, false),
			Token: field(uiHA.Token, c.HAToken, true),
		},
		Google: ServiceGoogleView{
			ServiceAccountFile: field(uiG.ServiceAccountFile, c.Google.ServiceAccountFile, false),
			Domain:             field(uiG.Domain, c.Google.Domain, false),
			ClientID:           field(uiG.ClientID, c.Google.ClientID, false),
			ClientSecret:       field(uiG.ClientSecret, c.Google.ClientSecret, true),
			RedirectURI:        field(uiG.RedirectURI, c.Google.RedirectURI, false),
			TokenCache:         field(uiG.TokenCache, c.Google.TokenCache, false),
			AccessToken:        field(uiG.AccessToken, c.Google.AccessToken, true),
			Endpoint:           field(uiG.Endpoint, c.Google.Endpoint, false),
		},
		HermesChat: ServiceHermesChatView{
			BaseURL:    field(uiC.BaseURL, c.HermesBaseURL, false),
			ChatPath:   field(uiC.ChatPath, c.HermesChatPath, false),
			AuthHeader: field(uiC.AuthHeader, c.HermesAuthHeader, false),
			AuthValue:  field(uiC.AuthValue, c.HermesAuthValue, true),
		},
		HermesPush: ServiceHermesPushView{
			Token: field(uiP.Token, c.PushToken, true),
		},
	}
}

// field builds one masked service-setting field: for non-secret fields
// Value/Fallback carry the UI and environment values; for secret fields
// both stay empty and only the set booleans are exposed.
func field(ui, env string, secret bool) ServiceField {
	f := ServiceField{
		Set:         ui != "",
		FallbackSet: env != "",
	}
	if !secret {
		f.Value, f.Fallback = ui, env
	}
	return f
}

// defaultViewSizes returns the default per-tile sizes: every view at the
// default step ("m").
func defaultViewSizes() map[string]string {
	sizes := make(map[string]string, len(viewIDs))
	for _, id := range viewIDs {
		sizes[id] = ViewSizeMedium
	}
	return sizes
}

// DefaultViews returns the default tile layout: all four views in the
// original board order, none hidden, month-grid calendar, every tile at
// the default size, no HA tiles, no explicit geometry (auto placement).
// Absent configuration behaves exactly like this.
func DefaultViews() Views {
	order := make([]string, len(viewIDs))
	copy(order, viewIDs)
	return Views{
		Order:        order,
		Hidden:       []string{},
		CalendarMode: CalendarModeMonth,
		Sizes:        defaultViewSizes(),
		Geometry:     map[string]*TileGeometry{},
		HATiles:      []HABoardTile{},
	}
}

// isHATileID reports whether id is a well-formed Home Assistant tile ID:
// "ha-" plus 1-16 lowercase alphanumeric characters.
func isHATileID(id string) bool {
	if !strings.HasPrefix(id, HATileIDPrefix) {
		return false
	}
	suffix := id[len(HATileIDPrefix):]
	if suffix == "" || len(suffix) > haTileIDSuffixBound {
		return false
	}
	for _, r := range suffix {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			continue
		}
		return false
	}
	return true
}

func isHATileKind(kind string) bool {
	return kind == HATileButton || kind == HATileVisual
}

func isValidHATile(t HABoardTile) bool {
	return isHATileID(t.ID) && t.Entity != "" && isHATileKind(t.Kind)
}

// tileIDs returns the board's tile IDs: the four view IDs first, then the
// Home Assistant tile IDs in list order.
func tileIDs(haTiles []HABoardTile) []string {
	ids := make([]string, 0, len(viewIDs)+len(haTiles))
	ids = append(ids, viewIDs...)
	for _, t := range haTiles {
		ids = append(ids, t.ID)
	}
	return ids
}

func tileIDSet(ids []string) map[string]bool {
	set := make(map[string]bool, len(ids))
	for _, id := range ids {
		set[id] = true
	}
	return set
}

// normalizeHATiles repairs a stored HA tile list: invalid entries (bad ID
// shape, empty entity, unknown kind) are dropped, and duplicates keep the
// first occurrence. The result is always a valid (possibly empty) list.
func normalizeHATiles(raw []HABoardTile) []HABoardTile {
	out := []HABoardTile{}
	seen := make(map[string]bool, len(raw))
	for _, t := range raw {
		if !isValidHATile(t) || seen[t.ID] {
			continue
		}
		seen[t.ID] = true
		out = append(out, t)
	}
	return out
}

// validGeometry reports whether g is an in-bounds grid placement on the
// cols x rows grid (the board's effective grid dimensions).
func validGeometry(g *TileGeometry, cols, rows int) bool {
	if g == nil {
		return false
	}
	if g.W <= 0 || g.H <= 0 || g.X < 1 || g.Y < 1 {
		return false
	}
	if g.W > cols || g.H > rows {
		return false
	}
	if g.X > cols-g.W+1 {
		return false
	}
	if g.Y > rows-g.H+1 {
		return false
	}
	return true
}

func geometryOverlaps(a, b *TileGeometry) bool {
	return a.X < b.X+b.W && b.X < a.X+a.W && a.Y < b.Y+b.H && b.Y < a.Y+a.H
}

// normalizeGeometry repairs the stored placements: an entry is kept only
// for a known tile ID, in bounds on the cols x rows grid, and
// non-overlapping with the entries kept before it (processed in board
// order). A dropped entry leaves the tile auto-placed — exactly the tile's
// no-geometry behavior. The result is always a valid, non-overlapping
// layout.
func normalizeGeometry(geo map[string]*TileGeometry, order []string, known map[string]bool, cols, rows int) map[string]*TileGeometry {
	out := map[string]*TileGeometry{}
	kept := make([]*TileGeometry, 0, len(order))
	for _, id := range order {
		if !known[id] {
			continue
		}
		g, ok := geo[id]
		if !ok || !validGeometry(g, cols, rows) {
			continue
		}
		p := *g
		clash := false
		for _, k := range kept {
			if geometryOverlaps(&p, k) {
				clash = true
				break
			}
		}
		if clash {
			continue
		}
		out[id] = &p
		kept = append(kept, &p)
	}
	return out
}

// ViewConfig returns the effective tile layout: defaults filled in and
// stored values repaired (the board file is a freehand-editable config
// surface), so the result is always usable — order is a permutation of the
// board tile IDs (the four view IDs plus the HA tile IDs), hidden holds
// only known IDs, calendar_mode is "month" or "schedule", sizes holds a
// valid step for every tile ID, geometry holds only in-bounds
// non-overlapping placements on the effective grid (a repaired entry falls
// back to auto placement), and ha_tiles holds only valid entries with
// distinct IDs. Safe to read while the board mutates.
func (c *Config) ViewConfig() Views {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	cols, rows := c.Board.Grid.effective()
	return normalizeViews(c.Board.Views, cols, rows)
}

// SetViews applies the provided tile-layout fields (nil = keep the
// current effective value), validates them, persists the whole board to
// the board file, and updates the in-memory board only after the write
// succeeds, so a failed write leaves the running config (and the file)
// unchanged. The sizes argument is a per-tile map of the entries to
// change (tile ID to size step); entries it omits keep their current size.
// The geometry argument is a per-tile map of the placements to change
// (a nil entry clears the tile's geometry, back to auto placement); tiles
// it omits keep their current placement. The haTiles argument replaces
// the Home Assistant tile list. Overlapping placements are not a write
// error: normalization repairs them to a valid board (the offending tile
// falls back to auto placement). Mirrors SetRefreshSeconds: a validation
// error is returned as-is, and a persistence failure is wrapped in
// ErrBoardFileNotWritable (an absent board file path is ErrNoBoardFile).
func (c *Config) SetViews(order, hidden *[]string, mode *string, sizes *map[string]string, geometry *map[string]*TileGeometry, haTiles *[]HABoardTile) error {
	cols, rows := c.BoardSnapshot().Grid.effective()
	// Validation runs against the prospective tile ID set: the incoming
	// HA tiles when provided, the currently effective ones otherwise.
	var effHA []HABoardTile
	if haTiles != nil {
		effHA = normalizeHATiles(*haTiles)
		if err := checkHATiles(*haTiles); err != nil {
			return err
		}
	} else {
		effHA = normalizeViews(c.BoardSnapshot().Views, cols, rows).HATiles
	}
	ids := tileIDSet(tileIDs(effHA))
	if order != nil {
		if err := checkViewOrder(*order, ids); err != nil {
			return err
		}
	}
	if hidden != nil {
		if err := checkViewHidden(*hidden, ids); err != nil {
			return err
		}
	}
	if mode != nil {
		if err := checkViewMode(*mode); err != nil {
			return err
		}
	}
	if sizes != nil {
		if err := checkViewSizes(*sizes, ids); err != nil {
			return err
		}
	}
	if geometry != nil {
		if err := checkGeometry(*geometry, ids, cols, rows); err != nil {
			return err
		}
	}
	if c.BoardFile == "" {
		return ErrNoBoardFile
	}
	c.boardMu.Lock()
	views := normalizeViews(c.Board.Views, cols, rows)
	if haTiles != nil {
		views.HATiles = append([]HABoardTile{}, (*haTiles)...) // definitions replaced
	}
	if order != nil {
		views.Order = append([]string{}, *order...)
	}
	if hidden != nil {
		views.Hidden = append([]string{}, *hidden...)
	}
	if mode != nil {
		views.CalendarMode = *mode
	}
	if sizes != nil {
		for id, size := range *sizes {
			views.Sizes[id] = size
		}
	}
	if geometry != nil {
		if views.Geometry == nil {
			views.Geometry = map[string]*TileGeometry{}
		}
		for id, g := range *geometry {
			if g == nil {
				delete(views.Geometry, id) // nil clears the tile's geometry
				continue
			}
			views.Geometry[id] = g
		}
	}
	updated := c.Board
	// Final normalization over the finished layout: repairs order/hidden/
	// sizes to the final tile set and drops out-of-bounds or overlapping
	// placements (their tiles fall back to auto placement).
	final := normalizeViews(&views, cols, rows)
	updated.Views = &final
	err := WriteBoardFile(c.BoardFile, updated)
	if err == nil {
		c.Board = updated
	}
	c.boardMu.Unlock()
	if err != nil {
		return fmt.Errorf("%w: %w", ErrBoardFileNotWritable, err)
	}
	return nil
}

func isViewID(id string) bool {
	for _, v := range viewIDs {
		if id == v {
			return true
		}
	}
	return false
}

// checkViewOrder reports whether order is a permutation of the board's
// tile IDs (the four view IDs plus the HA tile IDs; every tile exactly
// once, no others).
func checkViewOrder(order []string, ids map[string]bool) error {
	if len(order) != len(ids) {
		return fmt.Errorf("views.order must list each board tile ID (the four view IDs plus every views.ha_tiles id) exactly once")
	}
	seen := make(map[string]bool, len(order))
	for _, id := range order {
		if !ids[id] || seen[id] {
			return fmt.Errorf("views.order must be a permutation of the board tile IDs (the four view IDs plus every views.ha_tiles id)")
		}
		seen[id] = true
	}
	return nil
}

// checkViewHidden reports whether hidden is a set of distinct known board
// tile IDs (empty is valid: everything visible).
func checkViewHidden(hidden []string, ids map[string]bool) error {
	seen := make(map[string]bool, len(hidden))
	for _, id := range hidden {
		if !ids[id] || seen[id] {
			return fmt.Errorf("views.hidden may only contain distinct board tile IDs (the four view IDs and the views.ha_tiles ids)")
		}
		seen[id] = true
	}
	return nil
}

// checkViewMode reports whether mode is a supported Calendar render mode.
func checkViewMode(mode string) error {
	if mode != CalendarModeMonth && mode != CalendarModeSchedule {
		return fmt.Errorf("views.calendar_mode must be %q or %q", CalendarModeMonth, CalendarModeSchedule)
	}
	return nil
}

func isViewSize(size string) bool {
	for _, s := range viewSizes {
		if size == s {
			return true
		}
	}
	return false
}

// checkViewSizes reports whether sizes is a per-tile map of known board
// tile IDs to valid size steps (an empty map is valid: it changes
// nothing).
func checkViewSizes(sizes map[string]string, ids map[string]bool) error {
	for id, size := range sizes {
		if !ids[id] {
			return fmt.Errorf("views.sizes keys may only be board tile IDs (the four view IDs or a views.ha_tiles id)")
		}
		if !isViewSize(size) {
			return fmt.Errorf("views.sizes.%s must be one of s, m, l, xl", id)
		}
	}
	return nil
}

// checkHATiles reports whether tiles is a valid Home Assistant tile list:
// each entry needs a well-formed "ha-"-prefixed ID, a non-empty entity, a
// supported kind, and IDs must be distinct.
func checkHATiles(tiles []HABoardTile) error {
	seen := make(map[string]bool, len(tiles))
	for _, t := range tiles {
		if !isHATileID(t.ID) {
			return fmt.Errorf("views.ha_tiles ids must be %q followed by 1-16 lowercase alphanumeric characters (e.g. ha-1)", HATileIDPrefix)
		}
		if t.Entity == "" {
			return fmt.Errorf("views.ha_tiles entry %s: entity is required", t.ID)
		}
		if !isHATileKind(t.Kind) {
			return fmt.Errorf("views.ha_tiles entry %s: kind must be %q or %q", t.ID, HATileButton, HATileVisual)
		}
		if seen[t.ID] {
			return fmt.Errorf("views.ha_tiles ids must be distinct (duplicate %s)", t.ID)
		}
		seen[t.ID] = true
	}
	return nil
}

// checkGeometry reports whether geometry is a per-tile map of known board
// tile IDs to in-bounds placements on the cols x rows grid (a nil value is
// valid: it clears the tile's geometry; an empty map is valid: it changes
// nothing). Overlaps are not a write error — normalization repairs them.
func checkGeometry(geometry map[string]*TileGeometry, ids map[string]bool, cols, rows int) error {
	for id, g := range geometry {
		if !ids[id] {
			return fmt.Errorf("views.geometry keys may only be board tile IDs (the four view IDs or a views.ha_tiles id)")
		}
		if g == nil {
			continue
		}
		if !validGeometry(g, cols, rows) {
			return fmt.Errorf("views.geometry.%s must be {x, y, w, h} within the board's %dx%d grid (1 <= w <= %d, 1 <= x <= %d-w+1, 1 <= h <= %d, 1 <= y <= %d-h+1)", id, cols, rows, cols, cols, rows, rows)
		}
	}
	return nil
}

// normalizeViews fills in defaults and repairs stored values on the
// cols x rows grid: ha_tiles keeps only valid entries with distinct IDs,
// order becomes a permutation of the board tile IDs (the four view IDs
// plus the HA tile IDs; known IDs in stored order first, missing IDs
// appended in default order), hidden keeps only known distinct IDs,
// calendar_mode falls back to "month", sizes becomes a complete map of
// every board tile ID to a valid step (unknown keys dropped, absent or
// invalid values fall back to the default step "m"), and geometry keeps
// only in-bounds, non-overlapping placements for known tiles (processed in
// board order; a dropped entry leaves the tile auto-placed).
func normalizeViews(v *Views, cols, rows int) Views {
	out := DefaultViews()
	if v == nil {
		return out
	}
	haTiles := normalizeHATiles(v.HATiles)
	ids := tileIDs(haTiles)
	known := tileIDSet(ids)

	order := make([]string, 0, len(ids))
	seen := make(map[string]bool, len(ids))
	for _, id := range v.Order {
		if known[id] && !seen[id] {
			order = append(order, id)
			seen[id] = true
		}
	}
	for _, id := range ids {
		if !seen[id] {
			order = append(order, id)
		}
	}
	out.Order = order
	out.Hidden = []string{}
	seenH := make(map[string]bool, len(v.Hidden))
	for _, id := range v.Hidden {
		if known[id] && !seenH[id] {
			out.Hidden = append(out.Hidden, id)
			seenH[id] = true
		}
	}
	if v.CalendarMode == CalendarModeSchedule {
		out.CalendarMode = CalendarModeSchedule
	}
	out.Sizes = defaultViewSizes()
	for _, t := range haTiles {
		out.Sizes[t.ID] = ViewSizeMedium
	}
	for id, size := range v.Sizes {
		if known[id] && isViewSize(size) {
			out.Sizes[id] = size
		}
	}
	out.Geometry = normalizeGeometry(v.Geometry, order, known, cols, rows)
	out.HATiles = haTiles
	return out
}

// WriteBoardFile writes b to path as indented JSON. The write is atomic
// (unique temp file in the same directory + rename); a missing parent
// directory is created. The existing file's permissions are preserved; a
// new file is created with 0644. A target that is a file bind mount
// (Docker mounting ./dashhome.json into the container) cannot be replaced
// by rename (EBUSY/EXDEV), so the file is then written in place, which
// propagates to the host through the read-write mount.
func WriteBoardFile(path string, b Board) error {
	data, err := json.MarshalIndent(b, "", "  ")
	if err != nil {
		return fmt.Errorf("encode board: %w", err)
	}
	data = append(data, '\n')
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("create board directory %s: %w", dir, err)
	}
	mode := os.FileMode(0o644)
	if st, err := os.Stat(path); err == nil {
		mode = st.Mode().Perm()
	} else if !os.IsNotExist(err) {
		return fmt.Errorf("stat board file %s: %w", path, err)
	}
	tmp, err := os.CreateTemp(dir, filepath.Base(path)+".tmp.*")
	if err != nil {
		return fmt.Errorf("create temp board file: %w", err)
	}
	defer func() {
		_ = tmp.Close()
		_ = os.Remove(tmp.Name())
	}()
	if err := tmp.Chmod(mode); err != nil {
		return fmt.Errorf("set board file mode %s: %w", path, err)
	}
	if _, err := tmp.Write(data); err != nil {
		return fmt.Errorf("write board file %s: %w", path, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temp board file: %w", err)
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		if errors.Is(err, syscall.EBUSY) || errors.Is(err, syscall.EXDEV) {
			return writeBoardFileInPlace(path, data)
		}
		return fmt.Errorf("replace board file %s: %w", path, err)
	}
	return nil
}

// writeBoardFileInPlace rewrites an existing board file in place
// (truncate, write, sync) preserving its mode: a file bind mount cannot be
// replaced by rename, but truncating the mounted file itself propagates to
// the host through the read-write mount.
func writeBoardFileInPlace(path string, data []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_TRUNC, 0)
	if err != nil {
		return fmt.Errorf("write board file %s: %w", path, err)
	}
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		return fmt.Errorf("write board file %s: %w", path, err)
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		return fmt.Errorf("write board file %s: %w", path, err)
	}
	if err := f.Close(); err != nil {
		return fmt.Errorf("close board file %s: %w", path, err)
	}
	return nil
}

// HAFilter returns the configured entity set. A nil slice means "all".
func (c *Config) HAFilter() []string {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	if len(c.Board.HAEntityIDs) == 0 {
		return nil
	}
	out := make([]string, len(c.Board.HAEntityIDs))
	copy(out, c.Board.HAEntityIDs)
	return out
}

// CalendarFilter returns the configured calendar IDs, deduplicated (first
// occurrence wins), in configured order. A nil slice means "all visible
// calendars".
func (c *Config) CalendarFilter() []string {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	if len(c.Board.Calendars) == 0 {
		return nil
	}
	seen := make(map[string]bool, len(c.Board.Calendars))
	out := make([]string, 0, len(c.Board.Calendars))
	for _, ref := range c.Board.Calendars {
		if ref.ID != "" && !seen[ref.ID] {
			seen[ref.ID] = true
			out = append(out, ref.ID)
		}
	}
	return out
}

func env(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return fallback
}
