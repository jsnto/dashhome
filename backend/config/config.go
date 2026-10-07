// Package config loads dashhome runtime configuration from environment
// variables and an optional local dashboard configuration file.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"
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
	// Views configures the dashboard's tile layout: which tiles are shown,
	// in what order, how the Calendar tile renders, and how big each tile
	// is. Optional; missing means the default board (all four tiles in the
	// original order, month-grid calendar, every tile at the default
	// size).
	Views *Views `json:"views,omitempty"`
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

// Views is the dashboard's tile layout: which of the four board tiles are
// shown, in what order, how the Calendar tile renders, and how big each
// tile is on the board.
type Views struct {
	// Order lists the view IDs in board order: the reading order of the
	// tile grid (row by row, left to right). After normalization it is
	// always a permutation of the four view IDs, even for hidden views
	// (they stay in place, so re-showing restores the position).
	Order []string `json:"order"`
	// Hidden is the set of view IDs hidden from the board (a subset of the
	// view IDs). A hidden view is not rendered and needs no data polling.
	Hidden []string `json:"hidden"`
	// CalendarMode is how the Calendar view renders: "month" is the
	// month grid, "schedule" a chronological list of upcoming events.
	CalendarMode string `json:"calendar_mode"`
	// Sizes maps each view ID to its tile size step ("s", "m", "l", "xl":
	// the column span on the board's 6-column grid). After normalization
	// it always holds every view ID with a valid step.
	Sizes map[string]string `json:"sizes"`
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

// The tile size steps, from smallest to widest. "m" (half the board's
// width) is the default: four medium tiles form the board's default 2x2.
const (
	ViewSizeSmall  = "s"  // a third of the board's width
	ViewSizeMedium = "m"  // half the board's width (the default)
	ViewSizeLarge  = "l"  // two thirds of the board's width
	ViewSizeXLarge = "xl" // the full board width
)

var viewIDs = []string{ViewCalendar, ViewChat, ViewHome, ViewPush}

var viewSizes = []string{ViewSizeSmall, ViewSizeMedium, ViewSizeLarge, ViewSizeXLarge}

// GoogleConfig carries the Google Calendar credential settings. It is a
// type alias for calendar.GoogleSettings so the loaded config and the
// calendar client share one type instead of a field-for-field copy.
type GoogleConfig = calendar.GoogleSettings

// Config is the full runtime configuration.
type Config struct {
	// Port is the HTTP listen port. Default 3000.
	Port string

	// Home Assistant.
	HAURL   string
	HAToken string

	// Hermes chat.
	HermesBaseURL    string
	HermesChatPath   string
	HermesAuthHeader string // request header name to send ("" = none)
	HermesAuthValue  string // full header value, e.g. "Bearer sk-..."

	// Hermes push auth: shared token Hermes must present when pushing.
	PushToken string

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

// HAConfigured reports whether Home Assistant is configured.
func (c *Config) HAConfigured() bool { return c.HAURL != "" && c.HAToken != "" }

// HermesConfigured reports whether the Hermes chat integration is configured.
func (c *Config) HermesConfigured() bool { return c.HermesBaseURL != "" }

// PushConfigured reports whether authenticated Hermes pushes are enabled.
func (c *Config) PushConfigured() bool { return c.PushToken != "" }

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
// the default size. Absent configuration behaves exactly like this.
func DefaultViews() Views {
	order := make([]string, len(viewIDs))
	copy(order, viewIDs)
	return Views{
		Order:        order,
		Hidden:       []string{},
		CalendarMode: CalendarModeMonth,
		Sizes:        defaultViewSizes(),
	}
}

// ViewConfig returns the effective tile layout: defaults filled in and
// stored values repaired (the board file is a freehand-editable config
// surface), so the result is always usable — order is a permutation of the
// four view IDs, hidden holds only known IDs, calendar_mode is "month"
// or "schedule", and sizes holds a valid step for every view ID. Safe to
// read while the board mutates.
func (c *Config) ViewConfig() Views {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	return normalizeViews(c.Board.Views)
}

// SetViews applies the provided view-layout fields (nil = keep the current
// effective value), validates them, persists the whole board to the board
// file, and updates the in-memory board only after the write succeeds, so
// a failed write leaves the running config (and the file) unchanged.
// The sizes argument is a per-tile map of the entries to change (view ID
// to size step); entries the map omits keep their current effective size.
// Mirrors SetRefreshSeconds: a validation error is returned as-is, and a
// persistence failure is wrapped in ErrBoardFileNotWritable (an absent
// board file path is ErrNoBoardFile).
func (c *Config) SetViews(order, hidden *[]string, mode *string, sizes *map[string]string) error {
	if order != nil {
		if err := checkViewOrder(*order); err != nil {
			return err
		}
	}
	if hidden != nil {
		if err := checkViewHidden(*hidden); err != nil {
			return err
		}
	}
	if mode != nil {
		if err := checkViewMode(*mode); err != nil {
			return err
		}
	}
	if sizes != nil {
		if err := checkViewSizes(*sizes); err != nil {
			return err
		}
	}
	if c.BoardFile == "" {
		return ErrNoBoardFile
	}
	c.boardMu.Lock()
	updated := c.Board
	views := normalizeViews(updated.Views)
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
	v := views
	updated.Views = &v
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

// checkViewOrder reports whether order is a permutation of the four view
// IDs (every view exactly once, no others).
func checkViewOrder(order []string) error {
	if len(order) != len(viewIDs) {
		return fmt.Errorf("views.order must list each of calendar, chat, home, push exactly once")
	}
	seen := make(map[string]bool, len(order))
	for _, id := range order {
		if !isViewID(id) || seen[id] {
			return fmt.Errorf("views.order must be a permutation of calendar, chat, home, push")
		}
		seen[id] = true
	}
	return nil
}

// checkViewHidden reports whether hidden is a set of distinct known view
// IDs (empty is valid: everything visible).
func checkViewHidden(hidden []string) error {
	seen := make(map[string]bool, len(hidden))
	for _, id := range hidden {
		if !isViewID(id) || seen[id] {
			return fmt.Errorf("views.hidden may only contain distinct known view IDs (calendar, chat, home, push)")
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

// checkViewSizes reports whether sizes is a per-tile map of known view IDs
// to valid size steps (an empty map is valid: it changes nothing).
func checkViewSizes(sizes map[string]string) error {
	for id, size := range sizes {
		if !isViewID(id) {
			return fmt.Errorf("views.sizes keys may only be the view IDs (calendar, chat, home, push)")
		}
		if !isViewSize(size) {
			return fmt.Errorf("views.sizes.%s must be one of s, m, l, xl", id)
		}
	}
	return nil
}

// normalizeViews fills in defaults and repairs stored values: order becomes
// a permutation of the four view IDs (known IDs in stored order first,
// missing IDs appended in default order), hidden keeps only known distinct
// IDs, calendar_mode falls back to "month", and sizes becomes a complete
// map of every view ID to a valid step (unknown keys dropped, absent or
// invalid values fall back to the default step "m").
func normalizeViews(v *Views) Views {
	out := DefaultViews()
	if v == nil {
		return out
	}
	order := make([]string, 0, len(viewIDs))
	seen := make(map[string]bool, len(viewIDs))
	for _, id := range v.Order {
		if isViewID(id) && !seen[id] {
			order = append(order, id)
			seen[id] = true
		}
	}
	for _, id := range viewIDs {
		if !seen[id] {
			order = append(order, id)
		}
	}
	out.Order = order
	out.Hidden = []string{}
	seenH := make(map[string]bool, len(v.Hidden))
	for _, id := range v.Hidden {
		if isViewID(id) && !seenH[id] {
			out.Hidden = append(out.Hidden, id)
			seenH[id] = true
		}
	}
	if v.CalendarMode == CalendarModeSchedule {
		out.CalendarMode = CalendarModeSchedule
	}
	out.Sizes = defaultViewSizes()
	for id, size := range v.Sizes {
		if isViewID(id) && isViewSize(size) {
			out.Sizes[id] = size
		}
	}
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
