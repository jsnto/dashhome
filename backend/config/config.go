// Package config loads dashhome runtime configuration from environment
// variables and an optional local dashboard configuration file.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"sync"
)

// Board is the local dashboard configuration: what the dashboard shows.
// It is deliberately kept separate from secrets (which come from the
// environment) so it can be edited freely. See dashhome.example.json.
type Board struct {
	// HAEntityIDs is the set of Home Assistant entity IDs shown on the
	// dashboard, in display order. Empty means "show every entity".
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
}

// CalendarRef names one Google calendar by ID with an optional label.
type CalendarRef struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}

// HAEmbed configures the "Home (full)" section, which embeds the real Home
// Assistant dashboard in an iframe. The token itself never appears in the
// board file: when TokenURL is set, the backend assembles it into the
// embed URL server-side (GET /api/ha/embed-url).
type HAEmbed struct {
	// URLPath selects the dashboard to embed. nil means Home Assistant's
	// default dashboard. Leading/trailing slashes are ignored.
	URLPath *string `json:"url_path"`
	// TokenURL appends ?auth_long_lived=<token> to the embed URL. Default
	// false: the iframe relies on the browser's HA session (one-time
	// in-iframe login) instead of a URL token.
	TokenURL bool `json:"token_url"`
}

// GoogleConfig carries the Google Calendar credential settings.
type GoogleConfig struct {
	// Endpoint optionally overrides the Google Calendar API base URL
	// (advanced: API proxy or tests).
	Endpoint string
	// AccessToken is a static OAuth access token (development only).
	AccessToken string
	// ServiceAccountFile is the path to a service account JSON key file
	// (usually $GOOGLE_APPLICATION_CREDENTIALS).
	ServiceAccountFile string
	// Domain enables domain-wide delegation: events are fetched as the
	// given user in the Google Workspace domain.
	Domain string
	// OAuth 3-legged settings.
	ClientID     string
	ClientSecret string
	RedirectURI  string
	// TokenCache is where the OAuth token JSON is stored.
	TokenCache string
}

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

// Refresh interval bounds: sane polling cadence for the dashboard panels
// (no faster than hammering the upstream services, no slower than "live").
const (
	RefreshSecondsMin = 5
	RefreshSecondsMax = 3600
)

// ErrNoBoardFile is returned when the board file path is not configured.
var ErrNoBoardFile = errors.New("no board file configured (set CONFIG_FILE)")

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

// Load reads configuration from the environment. A missing board file is
// fine; an unreadable or malformed board file is an error.
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
	data, err := os.ReadFile(c.BoardFile)
	if err != nil {
		if os.IsNotExist(err) {
			return nil // optional file: empty board
		}
		return fmt.Errorf("read board file %s: %w", c.BoardFile, err)
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
// write leaves the running config (and the file) unchanged.
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
	return err
}

// WriteBoardFile writes b to path as indented JSON. The write is atomic
// (unique temp file in the same directory + rename); a missing parent
// directory is created. The existing file's permissions are preserved; a
// new file is created with 0644.
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
	renamed := false
	defer func() {
		if !renamed {
			_ = tmp.Close()
			_ = os.Remove(tmp.Name())
		}
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
		return fmt.Errorf("replace board file %s: %w", path, err)
	}
	renamed = true
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

// CalendarFilter returns the configured calendar IDs. A nil slice means
// "all visible calendars".
func (c *Config) CalendarFilter() []string {
	c.boardMu.RLock()
	defer c.boardMu.RUnlock()
	if len(c.Board.Calendars) == 0 {
		return nil
	}
	out := make([]string, 0, len(c.Board.Calendars))
	for _, ref := range c.Board.Calendars {
		if ref.ID != "" {
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
