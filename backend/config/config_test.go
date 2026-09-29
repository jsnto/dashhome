package config

import (
	"errors"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("PORT", "")
	t.Setenv("HOMEASSISTANT_URL", "")
	t.Setenv("HERMES_BASE_URL", "")
	t.Setenv("HERMES_PUSH_TOKEN", "")
	t.Setenv("CONFIG_FILE", filepath.Join(t.TempDir(), "missing.json"))
	t.Setenv("DATA_DIR", t.TempDir())

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Port != "3000" {
		t.Errorf("default Port = %q, want 3000", cfg.Port)
	}
	if cfg.HermesChatPath != "/v1/chat" {
		t.Errorf("default HermesChatPath = %q", cfg.HermesChatPath)
	}
	if cfg.HermesAuthHeader != "Authorization" {
		t.Errorf("default HermesAuthHeader = %q", cfg.HermesAuthHeader)
	}
	if cfg.HAConfigured() || cfg.HermesConfigured() || cfg.PushConfigured() {
		t.Error("empty env should not mark integrations configured")
	}
	if cfg.RefreshSeconds() != 30 {
		t.Errorf("default RefreshSeconds = %d, want 30", cfg.RefreshSeconds())
	}
	if cfg.Google.TokenCache == "" {
		t.Error("TokenCache should default under DATA_DIR")
	}
}

func TestLoadEnv(t *testing.T) {
	t.Setenv("PORT", "8080")
	t.Setenv("HOMEASSISTANT_URL", "https://ha.local")
	t.Setenv("HOMEASSISTANT_TOKEN", "ha-tok")
	t.Setenv("HERMES_BASE_URL", "https://hermes.local")
	t.Setenv("HERMES_CHAT_PATH", "/chat")
	t.Setenv("HERMES_AUTH_HEADER", "X-Api-Key")
	t.Setenv("HERMES_AUTH_VALUE", "k123")
	t.Setenv("HERMES_PUSH_TOKEN", "push-tok")
	t.Setenv("CONFIG_FILE", filepath.Join(t.TempDir(), "missing.json"))

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Port != "8080" || !cfg.HAConfigured() || !cfg.HermesConfigured() || !cfg.PushConfigured() {
		t.Errorf("env values not loaded: %+v", cfg)
	}
	if cfg.HermesAuthHeader != "X-Api-Key" || cfg.HermesAuthValue != "k123" {
		t.Errorf("hermes auth not loaded: %q %q", cfg.HermesAuthHeader, cfg.HermesAuthValue)
	}
}

func TestLoadBadPort(t *testing.T) {
	t.Setenv("PORT", "abc")
	if _, err := Load(); err == nil {
		t.Fatal("expected error for non-numeric PORT")
	}
}

func TestBoardFile(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "board.json")
	content := `{
		"ha_entities": ["climate.thermo", "sensor.front_door"],
		"ha_labels": {"climate.thermo": "Living room heat"},
		"calendars": [{"id": "c1@example.com", "label": "Family"}],
		"refresh_seconds": 15
	}`
	if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", file)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if len(cfg.Board.HAEntityIDs) != 2 || cfg.Board.HAEntityIDs[0] != "climate.thermo" {
		t.Errorf("board entities = %v", cfg.Board.HAEntityIDs)
	}
	if got := cfg.HAEntityLabel("climate.thermo", "other"); got != "Living room heat" {
		t.Errorf("HAEntityLabel = %q", got)
	}
	if got := cfg.HAEntityLabel("sensor.front_door", "Door"); got != "Door" {
		t.Errorf("fallback HAEntityLabel = %q", got)
	}
	if got := cfg.CalendarLabel("c1@example.com", "summary"); got != "Family" {
		t.Errorf("CalendarLabel = %q", got)
	}
	if got := cfg.CalendarLabel("other", "S"); got != "S" {
		t.Errorf("fallback CalendarLabel = %q", got)
	}
	if got := cfg.CalendarFilter(); len(got) != 1 || got[0] != "c1@example.com" {
		t.Errorf("CalendarFilter = %v", got)
	}
	if got := cfg.HAFilter(); len(got) != 2 {
		t.Errorf("HAFilter = %v", got)
	}
	if cfg.RefreshSeconds() != 15 {
		t.Errorf("RefreshSeconds = %d, want 15", cfg.RefreshSeconds())
	}
}

func TestBoardFileMissingIsOK(t *testing.T) {
	t.Setenv("CONFIG_FILE", filepath.Join(t.TempDir(), "nope.json"))
	cfg, err := Load()
	if err != nil {
		t.Fatalf("missing board file must not error: %v", err)
	}
	if cfg.HAFilter() != nil || cfg.CalendarFilter() != nil {
		t.Errorf("empty board should mean no filter, got %v / %v", cfg.HAFilter(), cfg.CalendarFilter())
	}
}

func TestBoardFileMalformed(t *testing.T) {
	file := filepath.Join(t.TempDir(), "bad.json")
	if err := os.WriteFile(file, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", file)
	if _, err := Load(); err == nil {
		t.Fatal("expected error for malformed board file")
	}
}

func TestValidateRefreshSeconds(t *testing.T) {
	for _, bad := range []int{0, -1, 4, RefreshSecondsMin - 1, RefreshSecondsMax + 1, 100000} {
		if err := ValidateRefreshSeconds(bad); err == nil {
			t.Errorf("ValidateRefreshSeconds(%d): unexpected success", bad)
		}
	}
	for _, ok := range []int{5, 30, 3600} {
		if err := ValidateRefreshSeconds(ok); err != nil {
			t.Errorf("ValidateRefreshSeconds(%d): %v", ok, err)
		}
	}
}

func TestSetRefreshSecondsPersistsAcrossReload(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	content := `{
		"ha_entities": ["climate.thermo"],
		"ha_labels": {"climate.thermo": "Thermostat"},
		"calendars": [{"id": "c1@example.com", "label": "Family"}],
		"refresh_seconds": 30
	}`
	if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", file)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if err := cfg.SetRefreshSeconds(45); err != nil {
		t.Fatalf("SetRefreshSeconds: %v", err)
	}
	if cfg.RefreshSeconds() != 45 {
		t.Errorf("in-memory RefreshSeconds = %d, want 45", cfg.RefreshSeconds())
	}

	// File on disk: value updated, every other board field preserved.
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var onDisk Board
	if err := json.Unmarshal(data, &onDisk); err != nil {
		t.Fatalf("board file not valid JSON: %v: %s", err, data)
	}
	if onDisk.RefreshSeconds != 45 {
		t.Errorf("on-disk refresh_seconds = %d, want 45", onDisk.RefreshSeconds)
	}
	if len(onDisk.HAEntityIDs) != 1 || onDisk.HAEntityIDs[0] != "climate.thermo" {
		t.Errorf("on-disk ha_entities = %v", onDisk.HAEntityIDs)
	}
	if onDisk.HALabels["climate.thermo"] != "Thermostat" {
		t.Errorf("on-disk ha_labels = %v", onDisk.HALabels)
	}
	if len(onDisk.Calendars) != 1 || onDisk.Calendars[0].ID != "c1@example.com" || onDisk.Calendars[0].Label != "Family" {
		t.Errorf("on-disk calendars = %v", onDisk.Calendars)
	}

	// A fresh process load sees the persisted value (survives a reload).
	cfg2, err := Load()
	if err != nil {
		t.Fatalf("reload Load: %v", err)
	}
	if cfg2.RefreshSeconds() != 45 {
		t.Errorf("reloaded RefreshSeconds = %d, want 45", cfg2.RefreshSeconds())
	}
}

func TestSetRefreshSecondsInvalidLeavesStateUntouched(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.WriteFile(file, []byte(`{"refresh_seconds": 30}`), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", file)

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	for _, bad := range []int{0, 4, 3601} {
		if err := cfg.SetRefreshSeconds(bad); err == nil {
			t.Fatalf("SetRefreshSeconds(%d): unexpected success", bad)
		}
	}
	if cfg.RefreshSeconds() != 30 {
		t.Errorf("in-memory RefreshSeconds = %d, want unchanged 30", cfg.RefreshSeconds())
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"refresh_seconds": 30`) {
		t.Errorf("file changed after invalid set: %s", data)
	}
	if _, err := os.Stat(file + ".tmp"); err == nil {
		t.Error("temp file left behind")
	}
}

func TestWriteBoardFileCreatesParentDir(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "dir", "board.json")
	if err := WriteBoardFile(path, Board{RefreshSeconds: 60}); err != nil {
		t.Fatalf("WriteBoardFile: %v", err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var b Board
	if err := json.Unmarshal(data, &b); err != nil || b.RefreshSeconds != 60 {
		t.Errorf("round trip = %+v, %v", b, err)
	}
}

func TestSetRefreshSecondsNoBoardFile(t *testing.T) {
	cfg := &Config{}
	if err := cfg.SetRefreshSeconds(30); !errors.Is(err, ErrNoBoardFile) {
		t.Errorf("err = %v, want ErrNoBoardFile", err)
	}
}
