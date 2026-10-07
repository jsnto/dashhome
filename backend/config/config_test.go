package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
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
	if cfg.HermesChatPath != "/chat/completions" {
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

func TestHAEmbedBoardKey(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "board.json")
	content := `{
		"ha_entities": ["climate.thermo"],
		"ha_embed": { "url_path": "  /energy/  ", "token_url": true }
	}`
	if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", file)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	embed := cfg.HAEmbed()
	if embed == nil || embed.URLPath == nil || *embed.URLPath != "  /energy/  " || !embed.TokenURL {
		t.Errorf("HAEmbed = %+v", embed)
	}

	// The accessor returns a copy: mutating it must not touch the board.
	embed.TokenURL = false
	if got := cfg.HAEmbed(); got == nil || !got.TokenURL {
		t.Errorf("HAEmbed mutated through the copy: %+v", got)
	}

	// Explicit null url_path = default dashboard (distinct from a string).
	dir2 := t.TempDir()
	file2 := filepath.Join(dir2, "board.json")
	if err := os.WriteFile(file2, []byte(`{ "ha_embed": { "url_path": null } }`), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", file2)
	cfg2, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	embed2 := cfg2.HAEmbed()
	if embed2 == nil || embed2.URLPath != nil || embed2.TokenURL {
		t.Errorf("HAEmbed (null url_path) = %+v, want {URLPath:<nil> TokenURL:false}", embed2)
	}

	// Missing key: nil embed.
	if err := os.Remove(file2); err != nil {
		t.Fatal(err)
	}
	cfg3, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg3.HAEmbed() != nil {
		t.Errorf("missing ha_embed should mean nil, got %+v", cfg3.HAEmbed())
	}
}

func TestSetRefreshSecondsPreservesHAEmbed(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.WriteFile(file, []byte(`{ "refresh_seconds": 30, "ha_embed": { "url_path": "energy", "token_url": true } }`), 0o600); err != nil {
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

	// In memory: ha_embed survives the refresh write.
	embed := cfg.HAEmbed()
	if embed == nil || embed.URLPath == nil || *embed.URLPath != "energy" || !embed.TokenURL {
		t.Errorf("in-memory HAEmbed = %+v", embed)
	}

	// On disk: ha_embed is serialized.
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var onDisk Board
	if err := json.Unmarshal(data, &onDisk); err != nil {
		t.Fatalf("board file not valid JSON: %v: %s", err, data)
	}
	if onDisk.HAEmbed == nil || onDisk.HAEmbed.URLPath == nil || *onDisk.HAEmbed.URLPath != "energy" || !onDisk.HAEmbed.TokenURL {
		t.Errorf("on-disk ha_embed = %+v", onDisk.HAEmbed)
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

// Docker bind-mount quirk: a missing host file for a bind mount is
// auto-created as a directory at the host path, so the container sees a
// directory at the board path. That must load as an empty board, never
// error (the backend must come up and serve /healthz and the calendars).
func TestBoardFileDirectoryIsTreatedAsAbsent(t *testing.T) {
	file := filepath.Join(t.TempDir(), "board.json")
	if err := os.MkdirAll(file, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", file)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load with a directory board path must not error: %v", err)
	}
	if cfg.HAFilter() != nil || cfg.CalendarFilter() != nil {
		t.Errorf("empty board should mean no filter, got %v / %v", cfg.HAFilter(), cfg.CalendarFilter())
	}
	if cfg.RefreshSeconds() != 30 {
		t.Errorf("RefreshSeconds = %d, want default 30", cfg.RefreshSeconds())
	}
}

// An unreadable board file (permissions) is tolerated like an absent one.
func TestBoardFileUnreadableIsTreatedAsAbsent(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("running as root: file permissions do not apply")
	}
	file := filepath.Join(t.TempDir(), "board.json")
	content := `{"calendars": [{"id": "c1@example.com", "label": "Family"}]}`
	if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(file, 0); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", file)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load with an unreadable board file must not error: %v", err)
	}
	if cfg.CalendarFilter() != nil {
		t.Errorf("board should be empty, filter = %v", cfg.CalendarFilter())
	}
}

// A duplicated calendar ID in the board file is collapsed to its first
// occurrence in order: listCalendars and listEvents both read
// CalendarFilter, so this one boundary keeps the tab list and the event
// fetches duplicate-free.
func TestCalendarFilterDedupesPreservingOrder(t *testing.T) {
	cfg := &Config{Board: Board{Calendars: []CalendarRef{
		{ID: "a@example.com"},
		{ID: "b@example.com"},
		{ID: "a@example.com", Label: "duplicate"},
		{ID: ""},
		{ID: "c@example.com"},
		{ID: "b@example.com"},
	}}}
	got := cfg.CalendarFilter()
	want := []string{"a@example.com", "b@example.com", "c@example.com"}
	if len(got) != len(want) {
		t.Fatalf("CalendarFilter = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("CalendarFilter = %v, want %v", got, want)
		}
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

func TestWriteBoardFilePreservesMode(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "dashhome.json")
	if err := os.WriteFile(path, []byte(`{"refresh_seconds": 30}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := WriteBoardFile(path, Board{RefreshSeconds: 60}); err != nil {
		t.Fatalf("WriteBoardFile: %v", err)
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Errorf("mode = %v, want 0600 preserved", st.Mode().Perm())
	}

	fresh := filepath.Join(dir, "fresh.json")
	if err := WriteBoardFile(fresh, Board{RefreshSeconds: 60}); err != nil {
		t.Fatalf("WriteBoardFile fresh: %v", err)
	}
	st, err = os.Stat(fresh)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o644 {
		t.Errorf("fresh file mode = %v, want 0644", st.Mode().Perm())
	}
}

func TestWriteBoardFileRemovesTempWhenRenameFails(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "dashhome.json")
	if err := os.Mkdir(target, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := WriteBoardFile(target, Board{RefreshSeconds: 30}); err == nil {
		t.Fatal("expected error when the target path is a directory")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if e.Name() != "dashhome.json" {
			t.Errorf("leftover entry %q after failed write", e.Name())
		}
	}
}

// A board file path that is a directory (Docker's auto-created source for
// an absent bind-mounted file) cannot be written: the error is
// ErrBoardFileNotWritable so the API can report a storage failure, and the
// in-memory board is left unchanged.
// The in-place fallback write (used when the rename cannot replace a
// bind-mounted board file, which fails EBUSY): the content is replaced
// and the file's mode is preserved.
func TestWriteBoardFileInPlacePreservesContentAndMode(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "dashhome.json")
	if err := os.WriteFile(path, []byte(`{"refresh_seconds": 30}`), 0o640); err != nil {
		t.Fatal(err)
	}
	data, err := json.MarshalIndent(Board{RefreshSeconds: 60}, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := writeBoardFileInPlace(path, append(data, '\n')); err != nil {
		t.Fatalf("writeBoardFileInPlace: %v", err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var b Board
	if err := json.Unmarshal(raw, &b); err != nil || b.RefreshSeconds != 60 {
		t.Errorf("content = %s, want refresh_seconds 60: %v", raw, err)
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o640 {
		t.Errorf("mode = %v, want 0640 preserved", st.Mode().Perm())
	}
}

func TestSetRefreshSecondsDirectoryPathIsNotWritable(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.Mkdir(file, 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{BoardFile: file, Board: Board{RefreshSeconds: 30}}
	if err := cfg.SetRefreshSeconds(45); !errors.Is(err, ErrBoardFileNotWritable) {
		t.Fatalf("err = %v, want ErrBoardFileNotWritable", err)
	}
	if cfg.RefreshSeconds() != 30 {
		t.Errorf("in-memory RefreshSeconds = %d, want unchanged 30", cfg.RefreshSeconds())
	}
}

func TestSetRefreshSecondsConcurrentWithReaders(t *testing.T) {
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

	stop := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				cfg.BoardSnapshot()
				cfg.RefreshSeconds()
				cfg.CalendarLabel("c1@example.com", "s")
				cfg.HAEntityLabel("climate.thermo", "x")
				cfg.CalendarFilter()
				cfg.HAFilter()
			}
		}()
	}
	done := make(chan error, 1)
	go func() {
		defer close(stop)
		var err error
		for i := 0; i < 100; i++ {
			if err = cfg.SetRefreshSeconds(5 + i%30); err != nil {
				break
			}
		}
		done <- err
	}()
	wg.Wait()
	if err := <-done; err != nil {
		t.Fatalf("SetRefreshSeconds: %v", err)
	}
	if got := cfg.RefreshSeconds(); got < 5 || got > 34 {
		t.Errorf("final RefreshSeconds = %d, want a persisted value in [5,34]", got)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var onDisk Board
	if err := json.Unmarshal(data, &onDisk); err != nil {
		t.Fatalf("board file not valid JSON after concurrent writes: %v: %s", err, data)
	}
	if onDisk.RefreshSeconds != cfg.RefreshSeconds() {
		t.Errorf("on-disk refresh_seconds = %d, in-memory = %d", onDisk.RefreshSeconds, cfg.RefreshSeconds())
	}
}

// --- views ----------------------------------------------------------------

func sameStrings(got, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

func mapsEqual(got, want map[string]string) bool {
	if len(got) != len(want) {
		return false
	}
	for k, v := range want {
		if got[k] != v {
			return false
		}
	}
	return true
}

func defaultSizes() map[string]string {
	return map[string]string{
		ViewCalendar: ViewSizeMedium,
		ViewChat:     ViewSizeMedium,
		ViewHome:     ViewSizeMedium,
		ViewPush:     ViewSizeMedium,
	}
}

func viewsCfg(t *testing.T, content string) (*Config, string) {
	t.Helper()
	dir := t.TempDir()
	file := filepath.Join(dir, "board.json")
	if content != "" {
		if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("CONFIG_FILE", file)
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	return cfg, file
}

func TestViewConfigDefaults(t *testing.T) {
	cfg, _ := viewsCfg(t, `{}`)
	v := cfg.ViewConfig()
	if !sameStrings(v.Order, []string{ViewCalendar, ViewChat, ViewHome, ViewPush}) {
		t.Errorf("default order = %v", v.Order)
	}
	if len(v.Hidden) != 0 {
		t.Errorf("default hidden = %v, want empty", v.Hidden)
	}
	if v.CalendarMode != CalendarModeMonth {
		t.Errorf("default calendar_mode = %q, want %q", v.CalendarMode, CalendarModeMonth)
	}
	if !mapsEqual(v.Sizes, defaultSizes()) {
		t.Errorf("default sizes = %v, want every view at the default step", v.Sizes)
	}

	// A board file that sets no views key at all behaves identically.
	empty, _ := viewsCfg(t, `{"refresh_seconds": 15}`)
	if v2 := empty.ViewConfig(); !sameStrings(v2.Order, v.Order) || len(v2.Hidden) != 0 || v2.CalendarMode != CalendarModeMonth {
		t.Errorf("absent views = %+v, want the default layout", v2)
	}

	// A views section without a sizes key behaves like the default sizes.
	noSizes, _ := viewsCfg(t, `{"views": {"order": ["calendar", "chat", "home", "push"], "hidden": []}}`)
	if v3 := noSizes.ViewConfig(); !mapsEqual(v3.Sizes, defaultSizes()) {
		t.Errorf("absent sizes = %v, want the default sizes", v3.Sizes)
	}
}

func TestViewConfigRepairsStoredValues(t *testing.T) {
	cfg, _ := viewsCfg(t, `{
		"views": {
			"order": ["push", "bogus", "chat", "push"],
			"hidden": ["home", "unknown", "home"],
			"calendar_mode": "weird",
			"sizes": {"calendar": "l", "home": "giant", "nope": "m", "chat": "xl"}
		}
	}`)
	v := cfg.ViewConfig()
	// Known IDs kept in stored order (deduped), missing IDs appended in
	// default order.
	if !sameStrings(v.Order, []string{ViewPush, ViewChat, ViewCalendar, ViewHome}) {
		t.Errorf("repaired order = %v", v.Order)
	}
	if !sameStrings(v.Hidden, []string{ViewHome}) {
		t.Errorf("repaired hidden = %v", v.Hidden)
	}
	if v.CalendarMode != CalendarModeMonth {
		t.Errorf("repaired calendar_mode = %q, want %q", v.CalendarMode, CalendarModeMonth)
	}
	// Valid sizes kept; an invalid value (and the unknown key) fall back
	// to the default step, and views with no stored entry get it too.
	if !mapsEqual(v.Sizes, map[string]string{
		ViewCalendar: ViewSizeLarge,
		ViewChat:     ViewSizeXLarge,
		ViewHome:     ViewSizeMedium,
		ViewPush:     ViewSizeMedium,
	}) {
		t.Errorf("repaired sizes = %v", v.Sizes)
	}
}

func TestSetViewsRoundTrip(t *testing.T) {
	cfg, file := viewsCfg(t, `{"refresh_seconds": 45, "ha_entities": ["climate.thermo"]}`)
	order := []string{ViewChat, ViewHome, ViewPush, ViewCalendar}
	hidden := []string{ViewHome}
	mode := CalendarModeSchedule
	sizes := map[string]string{ViewCalendar: ViewSizeLarge, ViewPush: ViewSizeXLarge}
	if err := cfg.SetViews(&order, &hidden, &mode, &sizes, nil, nil); err != nil {
		t.Fatalf("SetViews: %v", err)
	}

	v := cfg.ViewConfig()
	if !sameStrings(v.Order, order) || !sameStrings(v.Hidden, hidden) || v.CalendarMode != CalendarModeSchedule {
		t.Errorf("in-memory views = %+v", v)
	}
	// The provided size entries change, the rest keep the default step.
	if !mapsEqual(v.Sizes, map[string]string{
		ViewCalendar: ViewSizeLarge,
		ViewChat:     ViewSizeMedium,
		ViewHome:     ViewSizeMedium,
		ViewPush:     ViewSizeXLarge,
	}) {
		t.Errorf("in-memory sizes = %v", v.Sizes)
	}

	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var onDisk Board
	if err := json.Unmarshal(data, &onDisk); err != nil {
		t.Fatalf("board file not valid JSON: %v: %s", err, data)
	}
	if onDisk.Views == nil {
		t.Fatalf("views not written to the board file: %s", data)
	}
	if !sameStrings(onDisk.Views.Order, order) || !sameStrings(onDisk.Views.Hidden, hidden) || onDisk.Views.CalendarMode != CalendarModeSchedule {
		t.Errorf("on-disk views = %+v", onDisk.Views)
	}
	if !mapsEqual(onDisk.Views.Sizes, v.Sizes) {
		t.Errorf("on-disk sizes = %v, in-memory = %v", onDisk.Views.Sizes, v.Sizes)
	}
	// Other board fields are preserved.
	if onDisk.RefreshSeconds != 45 || !sameStrings(onDisk.HAEntityIDs, []string{"climate.thermo"}) {
		t.Errorf("other board fields not preserved: %s", data)
	}

	// A fresh Load sees the persisted views.
	_, _ = viewsCfg(t, ``) // reset nothing; fresh process env via Load below
	t.Setenv("CONFIG_FILE", file)
	reloaded, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	v2 := reloaded.ViewConfig()
	if !sameStrings(v2.Order, order) || !sameStrings(v2.Hidden, hidden) || v2.CalendarMode != CalendarModeSchedule {
		t.Errorf("reloaded views = %+v", v2)
	}
	if !mapsEqual(v2.Sizes, v.Sizes) {
		t.Errorf("reloaded sizes = %v, want %v", v2.Sizes, v.Sizes)
	}
}

func TestSetViewsPartialUpdate(t *testing.T) {
	cfg, _ := viewsCfg(t, `{
		"views": { "order": ["chat", "calendar", "push", "home"], "hidden": ["push"], "calendar_mode": "schedule" }
	}`)

	// Only the mode changes; order and hidden keep their stored values.
	mode := CalendarModeMonth
	if err := cfg.SetViews(nil, nil, &mode, nil, nil, nil); err != nil {
		t.Fatalf("SetViews(mode): %v", err)
	}
	v := cfg.ViewConfig()
	if !sameStrings(v.Order, []string{ViewChat, ViewCalendar, ViewPush, ViewHome}) ||
		!sameStrings(v.Hidden, []string{ViewPush}) || v.CalendarMode != CalendarModeMonth {
		t.Errorf("after mode-only update = %+v", v)
	}

	// Only the order changes.
	order := []string{ViewPush, ViewChat, ViewHome, ViewCalendar}
	if err := cfg.SetViews(&order, nil, nil, nil, nil, nil); err != nil {
		t.Fatalf("SetViews(order): %v", err)
	}
	v = cfg.ViewConfig()
	if !sameStrings(v.Order, order) || !sameStrings(v.Hidden, []string{ViewPush}) || v.CalendarMode != CalendarModeMonth {
		t.Errorf("after order-only update = %+v", v)
	}

	// An empty hidden list clears the hidden set.
	empty := []string{}
	if err := cfg.SetViews(nil, &empty, nil, nil, nil, nil); err != nil {
		t.Fatalf("SetViews(hidden=[]): %v", err)
	}
	v = cfg.ViewConfig()
	if !sameStrings(v.Order, order) || len(v.Hidden) != 0 {
		t.Errorf("after hidden=[] update = %+v", v)
	}

	// Only one tile's size changes; the other tiles keep their effective
	// sizes (the board file set no sizes at all, so they are the default).
	oneSize := map[string]string{ViewChat: ViewSizeSmall}
	if err := cfg.SetViews(nil, nil, nil, &oneSize, nil, nil); err != nil {
		t.Fatalf("SetViews(sizes): %v", err)
	}
	v = cfg.ViewConfig()
	if !mapsEqual(v.Sizes, map[string]string{
		ViewCalendar: ViewSizeMedium,
		ViewChat:     ViewSizeSmall,
		ViewHome:     ViewSizeMedium,
		ViewPush:     ViewSizeMedium,
	}) {
		t.Errorf("after sizes-only update = %+v", v)
	}

	// An empty sizes map changes nothing.
	none := map[string]string{}
	if err := cfg.SetViews(nil, nil, nil, &none, nil, nil); err != nil {
		t.Fatalf("SetViews(sizes={}): %v", err)
	}
	if !mapsEqual(cfg.ViewConfig().Sizes, v.Sizes) {
		t.Errorf("empty sizes map changed the sizes: %v", cfg.ViewConfig().Sizes)
	}
}

func TestSetViewsInvalidLeavesStateUntouched(t *testing.T) {
	cfg, file := viewsCfg(t, `{"refresh_seconds": 45}`)
	cases := []struct {
		name   string
		order  []string
		hidden []string
		m      *string
		sizes  *map[string]string
	}{
		{"order-missing-view", []string{ViewCalendar, ViewChat, ViewHome}, nil, nil, nil},
		{"order-duplicate", []string{ViewCalendar, ViewCalendar, ViewHome, ViewPush}, nil, nil, nil},
		{"order-unknown", []string{ViewCalendar, ViewChat, ViewHome, "nope"}, nil, nil, nil},
		{"order-extra", []string{ViewCalendar, ViewChat, ViewHome, ViewPush, ViewPush}, nil, nil, nil},
		{"hidden-unknown", nil, []string{"nope"}, nil, nil},
		{"hidden-duplicate", nil, []string{ViewPush, ViewPush}, nil, nil},
		{"mode-invalid", nil, nil, ptrString("grid"), nil},
		{"sizes-unknown-key", nil, nil, nil, ptrSizes(map[string]string{"nope": ViewSizeMedium})},
		{"sizes-invalid-value", nil, nil, nil, ptrSizes(map[string]string{ViewCalendar: "giant"})},
		{"sizes-mixed-invalid", nil, nil, nil, ptrSizes(map[string]string{ViewHome: ViewSizeLarge, ViewPush: "huge"})},
	}
	before := cfg.ViewConfig()
	for _, tc := range cases {
		err := cfg.SetViews(nilIfNil(tc.order), nilIfNil(tc.hidden), tc.m, tc.sizes, nil, nil)
		if err == nil {
			t.Fatalf("%s: unexpected success", tc.name)
		}
		if errors.Is(err, ErrBoardFileNotWritable) || errors.Is(err, ErrNoBoardFile) {
			t.Errorf("%s: err = %v looks like a storage error, want a validation error", tc.name, err)
		}
	}
	after := cfg.ViewConfig()
	if !sameStrings(after.Order, before.Order) || !sameStrings(after.Hidden, before.Hidden) || after.CalendarMode != before.CalendarMode {
		t.Errorf("state changed by invalid updates: %+v -> %+v", before, after)
	}
	if !mapsEqual(after.Sizes, before.Sizes) {
		t.Errorf("sizes changed by invalid updates: %v -> %v", before.Sizes, after.Sizes)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), `"views"`) {
		t.Errorf("invalid updates wrote to the board file: %s", data)
	}
}

func nilIfNil(s []string) *[]string {
	if s == nil {
		return nil
	}
	return &s
}

func ptrString(s string) *string { return &s }

func ptrSizes(m map[string]string) *map[string]string { return &m }

func TestSetViewsNoBoardFile(t *testing.T) {
	cfg := &Config{}
	order := []string{ViewCalendar, ViewChat, ViewHome, ViewPush}
	if err := cfg.SetViews(&order, nil, nil, nil, nil, nil); !errors.Is(err, ErrNoBoardFile) {
		t.Errorf("err = %v, want ErrNoBoardFile", err)
	}
}

func TestSetViewsDirectoryPathIsNotWritable(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.Mkdir(file, 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{BoardFile: file}
	order := []string{ViewChat, ViewCalendar, ViewHome, ViewPush}
	if err := cfg.SetViews(&order, nil, nil, nil, nil, nil); !errors.Is(err, ErrBoardFileNotWritable) {
		t.Fatalf("err = %v, want ErrBoardFileNotWritable", err)
	}
	v := cfg.ViewConfig()
	if !sameStrings(v.Order, []string{ViewCalendar, ViewChat, ViewHome, ViewPush}) {
		t.Errorf("in-memory order = %v, want unchanged defaults", v.Order)
	}
}

func TestSetRefreshSecondsPreservesViews(t *testing.T) {
	cfg, file := viewsCfg(t, ``)
	order := []string{ViewChat, ViewHome, ViewPush, ViewCalendar}
	hidden := []string{ViewPush}
	mode := CalendarModeSchedule
	if err := cfg.SetViews(&order, &hidden, &mode, nil, nil, nil); err != nil {
		t.Fatalf("SetViews: %v", err)
	}
	if err := cfg.SetRefreshSeconds(60); err != nil {
		t.Fatalf("SetRefreshSeconds: %v", err)
	}
	v := cfg.ViewConfig()
	if !sameStrings(v.Order, order) || !sameStrings(v.Hidden, hidden) || v.CalendarMode != CalendarModeSchedule {
		t.Errorf("views lost after SetRefreshSeconds: %+v", v)
	}
	data, _ := os.ReadFile(file)
	if !strings.Contains(string(data), `"calendar_mode": "schedule"`) {
		t.Errorf("views not preserved on disk: %s", data)
	}
}

// --- geometry + HA tiles ---------------------------------------------------

func geoMap(m map[string]*TileGeometry) *map[string]*TileGeometry { return &m }

func geom(x, y, w, h int) *TileGeometry { return &TileGeometry{X: x, Y: y, W: w, H: h} }

func TestViewConfigDefaultsNoGeometryNoHATiles(t *testing.T) {
	cfg, _ := viewsCfg(t, `{}`)
	v := cfg.ViewConfig()
	if len(v.Geometry) != 0 {
		t.Errorf("default geometry = %v, want none (auto placement)", v.Geometry)
	}
	if len(v.HATiles) != 0 {
		t.Errorf("default ha_tiles = %v, want none", v.HATiles)
	}
	if !mapsEqual(v.Sizes, defaultSizes()) {
		t.Errorf("default sizes = %v", v.Sizes)
	}
	if !sameStrings(v.Order, []string{ViewCalendar, ViewChat, ViewHome, ViewPush}) {
		t.Errorf("default order = %v", v.Order)
	}
}

func TestViewConfigRepairsGeometryAndHATiles(t *testing.T) {
	cfg, _ := viewsCfg(t, `{
		"views": {
			"order": ["chat", "ha-1", "calendar", "ha-2", "push", "home", "stale-ha"],
			"hidden": ["ha-1", "nope"],
			"calendar_mode": "month",
			"sizes": {"calendar": "l", "ha-1": "s", "bogus": "xl"},
			"geometry": {
				"calendar": {"x": 1, "y": 1, "w": 3, "h": 2},
				"chat": {"x": 4, "y": 1, "w": 3, "h": 2},
				"home": {"x": 3, "y": 1, "w": 4, "h": 1},
				"push": {"x": 13, "y": 1, "w": 2, "h": 1},
				"ha-1": {"x": 1, "y": 2, "w": 1, "h": 0},
				"ha-2": "wobbly",
				"stale-ha": {"x": 1, "y": 1, "w": 1, "h": 1}
			},
			"ha_tiles": [
				{"id": "ha-1", "entity": "light.kitchen", "label": "Kitchen", "kind": "button"},
				{"id": "ha-1", "entity": "light.dup", "label": "Dup", "kind": "visual"},
				{"id": "ha-2", "entity": "sensor.temp", "label": "", "kind": "visual"},
				{"id": "nope", "entity": "sensor.bad", "label": "", "kind": "button"},
				{"id": "ha-3", "entity": "", "label": "", "kind": "button"},
				{"id": "ha-4", "entity": "switch.x", "label": "", "kind": "grid"}
			]
		}
	}`)
	v := cfg.ViewConfig()

	// ha_tiles: deduped and invalid entries dropped.
	if len(v.HATiles) != 2 {
		t.Fatalf("repaired ha_tiles = %+v, want 2 entries", v.HATiles)
	}
	if v.HATiles[0].ID != "ha-1" || v.HATiles[0].Entity != "light.kitchen" || v.HATiles[0].Kind != HATileButton {
		t.Errorf("ha-1 = %+v", v.HATiles[0])
	}
	if v.HATiles[1].ID != "ha-2" || v.HATiles[1].Entity != "sensor.temp" || v.HATiles[1].Kind != HATileVisual {
		t.Errorf("ha-2 = %+v", v.HATiles[1])
	}

	// order: the stale HA ID is dropped, the known set is a permutation.
	if !sameStrings(v.Order, []string{ViewChat, "ha-1", ViewCalendar, "ha-2", ViewPush, ViewHome}) {
		t.Errorf("repaired order = %v", v.Order)
	}
	// hidden: only the known HA ID survives.
	if !sameStrings(v.Hidden, []string{"ha-1"}) {
		t.Errorf("repaired hidden = %v", v.Hidden)
	}
	// sizes: every board tile ID holds a valid step; the unknown key is
	// dropped, absent tiles fall back to the default.
	if !mapsEqual(v.Sizes, map[string]string{
		ViewCalendar: ViewSizeLarge,
		ViewChat:     ViewSizeMedium,
		ViewHome:     ViewSizeMedium,
		ViewPush:     ViewSizeMedium,
		"ha-1":       ViewSizeSmall,
		"ha-2":       ViewSizeMedium,
	}) {
		t.Errorf("repaired sizes = %v", v.Sizes)
	}

	// geometry: the valid non-overlapping entries are kept (calendar and
	// chat are the two halves of row 1-2); home overlaps calendar (it is
	// later in the board order), push is out of bounds, ha-1 has a zero
	// height, ha-2 is not an object, and stale-ha is no longer a known
	// tile. Every repaired tile is auto-placed.
	if len(v.Geometry) != 2 {
		t.Fatalf("repaired geometry = %+v, want 2 entries", v.Geometry)
	}
	if g := v.Geometry[ViewCalendar]; g == nil || g.X != 1 || g.Y != 1 || g.W != 3 || g.H != 2 {
		t.Errorf("calendar geometry = %+v", g)
	}
	if g := v.Geometry[ViewChat]; g == nil || g.X != 4 || g.Y != 1 || g.W != 3 || g.H != 2 {
		t.Errorf("chat geometry = %+v", g)
	}
}

// Extreme-but-representable coordinates are out-of-bounds placements: load
// repair drops them back to auto placement exactly like any other
// out-of-grid entry (the in-bounds one is kept).
func TestViewConfigRepairsOverflowGeometry(t *testing.T) {
	cfg, _ := viewsCfg(t, `{
		"views": {
			"geometry": {
				"calendar": {"x": 9223372036854775807, "y": 1, "w": 2, "h": 1},
				"chat": {"x": 1, "y": 9223372036854775807, "w": 1, "h": 2},
				"home": {"x": 1, "y": 1, "w": 3, "h": 1}
			}
		}
	}`)
	v := cfg.ViewConfig()
	if len(v.Geometry) != 1 {
		t.Fatalf("repaired geometry = %+v, want only the in-bounds placement", v.Geometry)
	}
	if g := v.Geometry[ViewHome]; g == nil || g.X != 1 || g.Y != 1 || g.W != 3 || g.H != 1 {
		t.Errorf("home geometry = %+v", g)
	}
}

func TestSetViewsGeometryAndHATilesRoundTrip(t *testing.T) {
	cfg, file := viewsCfg(t, `{"refresh_seconds": 45}`)
	order := []string{ViewChat, ViewHome, "ha-1", ViewPush, ViewCalendar}
	geometry := map[string]*TileGeometry{
		ViewChat:     geom(1, 1, 3, 1),
		ViewHome:     geom(4, 1, 3, 2),
		"ha-1":       geom(1, 2, 2, 1),
		ViewPush:     geom(3, 2, 1, 1),
		ViewCalendar: geom(1, 3, 2, 1),
	}
	haTiles := []HABoardTile{
		{ID: "ha-1", Entity: "light.kitchen", Label: "Kitchen", Kind: HATileButton},
		{ID: "ha-2", Entity: "sensor.temp", Label: "Temp", Kind: HATileVisual},
	}
	order = append(order, "ha-2")
	if err := cfg.SetViews(&order, nil, nil, nil, geoMap(geometry), &haTiles); err != nil {
		t.Fatalf("SetViews: %v", err)
	}

	v := cfg.ViewConfig()
	if !sameStrings(v.Order, order) {
		t.Errorf("in-memory order = %v", v.Order)
	}
	if len(v.HATiles) != 2 {
		t.Errorf("in-memory ha_tiles = %+v", v.HATiles)
	}
	if len(v.Geometry) != 5 {
		t.Errorf("in-memory geometry = %+v", v.Geometry)
	}
	// ha-2 has no geometry: it stays auto-placed (no entry).
	if _, ok := v.Geometry["ha-2"]; ok {
		t.Errorf("ha-2 must not gain geometry: %+v", v.Geometry)
	}

	// Persisted to the board file, other fields preserved.
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var onDisk Board
	if err := json.Unmarshal(data, &onDisk); err != nil {
		t.Fatalf("board file not valid JSON: %v: %s", err, data)
	}
	if onDisk.Views == nil || len(onDisk.Views.HATiles) != 2 || len(onDisk.Views.Geometry) != 5 {
		t.Errorf("geometry/ha_tiles not written to the board file: %s", data)
	}
	if !strings.Contains(string(data), `"refresh_seconds": 45`) {
		t.Errorf("other board fields not preserved: %s", data)
	}

	// A fresh Load sees the persisted layout.
	t.Setenv("CONFIG_FILE", file)
	reloaded, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	v2 := reloaded.ViewConfig()
	if !sameStrings(v2.Order, order) || len(v2.HATiles) != 2 {
		t.Errorf("reloaded views = %+v", v2)
	}
	for id, g := range v.Geometry {
		if g2 := v2.Geometry[id]; g2 == nil || *g2 != *g {
			t.Errorf("reloaded geometry[%s] = %+v, want %+v", id, g2, g)
		}
	}
}

func TestSetViewsGeometryMergeAndClear(t *testing.T) {
	cfg, _ := viewsCfg(t, `{"views": {"geometry": {"calendar": {"x": 1, "y": 1, "w": 3, "h": 1}, "chat": {"x": 4, "y": 1, "w": 3, "h": 2}}}}`)

	// Only one tile's placement changes; the other keeps its placement.
	if err := cfg.SetViews(nil, nil, nil, nil, geoMap(map[string]*TileGeometry{ViewHome: geom(1, 2, 3, 1)}), nil); err != nil {
		t.Fatalf("SetViews(geometry): %v", err)
	}
	v := cfg.ViewConfig()
	if len(v.Geometry) != 3 {
		t.Fatalf("geometry = %+v, want the three placements", v.Geometry)
	}
	if g := v.Geometry[ViewCalendar]; g == nil || g.X != 1 || g.W != 3 {
		t.Errorf("calendar kept = %+v", g)
	}

	// A nil entry clears a tile's geometry (back to auto placement); the
	// other entries survive.
	clear := map[string]*TileGeometry{ViewChat: nil}
	if err := cfg.SetViews(nil, nil, nil, nil, geoMap(clear), nil); err != nil {
		t.Fatalf("SetViews(clear): %v", err)
	}
	v = cfg.ViewConfig()
	if _, ok := v.Geometry[ViewChat]; ok {
		t.Errorf("chat geometry not cleared: %+v", v.Geometry)
	}
	if len(v.Geometry) != 2 {
		t.Errorf("geometry = %+v, want the two remaining placements", v.Geometry)
	}

	// An empty geometry map changes nothing.
	if err := cfg.SetViews(nil, nil, nil, nil, geoMap(map[string]*TileGeometry{}), nil); err != nil {
		t.Fatalf("SetViews(geometry={}): %v", err)
	}
	if len(cfg.ViewConfig().Geometry) != 2 {
		t.Errorf("empty geometry map changed the geometry: %+v", cfg.ViewConfig().Geometry)
	}
}

func TestSetViewsOverlappingGeometryIsRepaired(t *testing.T) {
	// Overlaps are not a write error: the board is repaired instead
	// (the later tile in board order falls back to auto placement), so
	// the endpoint keeps its 400/503 contract for validation errors only.
	cfg, _ := viewsCfg(t, `{}`)
	geometry := map[string]*TileGeometry{
		ViewCalendar: geom(1, 1, 4, 2),
		ViewChat:     geom(3, 1, 4, 2), // overlaps calendar
		ViewHome:     geom(1, 3, 6, 1),
	}
	if err := cfg.SetViews(nil, nil, nil, nil, geoMap(geometry), nil); err != nil {
		t.Fatalf("SetViews(overlapping): %v", err)
	}
	v := cfg.ViewConfig()
	if len(v.Geometry) != 2 {
		t.Fatalf("repaired geometry = %+v, want calendar and home kept", v.Geometry)
	}
	if _, ok := v.Geometry[ViewChat]; ok {
		t.Errorf("overlapping chat geometry not repaired away: %+v", v.Geometry)
	}
}

func TestSetViewsInvalidGeometryAndHATiles(t *testing.T) {
	cfg, file := viewsCfg(t, `{"refresh_seconds": 45}`)
	geoCases := []struct {
		name     string
		geometry map[string]*TileGeometry
	}{
		{"out-of-bounds-x", map[string]*TileGeometry{ViewCalendar: geom(13, 1, 2, 1)}},
		{"too-wide", map[string]*TileGeometry{ViewCalendar: geom(1, 1, 13, 1)}},
		{"crosses-edge", map[string]*TileGeometry{ViewCalendar: geom(12, 1, 2, 1)}},
		{"out-of-bounds-y", map[string]*TileGeometry{ViewCalendar: geom(1, 13, 2, 1)}},
		{"crosses-bottom", map[string]*TileGeometry{ViewCalendar: geom(1, 12, 2, 2)}},
		{"overflow-x", map[string]*TileGeometry{ViewCalendar: geom(9223372036854775807, 1, 2, 1)}},
		{"overflow-y", map[string]*TileGeometry{ViewCalendar: geom(1, 9223372036854775807, 2, 1)}},
		{"negative-height", map[string]*TileGeometry{ViewCalendar: geom(1, 1, 3, 0)}},
		{"zero-width", map[string]*TileGeometry{ViewCalendar: geom(1, 1, 0, 1)}},
		{"unknown-tile", map[string]*TileGeometry{"nope": geom(1, 1, 1, 1)}},
	}
	before := cfg.ViewConfig()
	for _, tc := range geoCases {
		err := cfg.SetViews(nil, nil, nil, nil, geoMap(tc.geometry), nil)
		if err == nil {
			t.Fatalf("%s: unexpected success", tc.name)
		}
		if errors.Is(err, ErrBoardFileNotWritable) || errors.Is(err, ErrNoBoardFile) {
			t.Errorf("%s: err = %v looks like a storage error, want a validation error", tc.name, err)
		}
	}

	haCases := [][]HABoardTile{
		{{ID: "nope", Entity: "light.x", Kind: HATileButton}},
		{{ID: "ha-1", Entity: "", Kind: HATileButton}},
		{{ID: "ha-1", Entity: "light.x", Kind: "grid"}},
		{{ID: "ha-1", Entity: "light.x", Kind: HATileButton}, {ID: "ha-1", Entity: "light.y", Kind: HATileVisual}},
	}
	for i, tiles := range haCases {
		err := cfg.SetViews(nil, nil, nil, nil, nil, &tiles)
		if err == nil {
			t.Fatalf("ha-tiles case %d: unexpected success", i)
		}
	}

	// An order that names an unknown HA tile is rejected too.
	order := []string{ViewCalendar, ViewChat, ViewHome, ViewPush, "ha-nope"}
	if err := cfg.SetViews(&order, nil, nil, nil, nil, nil); err == nil {
		t.Fatal("order with unknown HA tile ID: unexpected success")
	}

	// Validation errors leave the effective layout and the file untouched.
	after := cfg.ViewConfig()
	if !sameStrings(after.Order, before.Order) || !sameStrings(after.Hidden, before.Hidden) || after.CalendarMode != before.CalendarMode {
		t.Errorf("state changed by invalid updates: %+v -> %+v", before, after)
	}
	if !mapsEqual(after.Sizes, before.Sizes) {
		t.Errorf("sizes changed by invalid updates: %v -> %v", before.Sizes, after.Sizes)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), `"views"`) || strings.Contains(string(data), `"geometry"`) || strings.Contains(string(data), `"ha_tiles"`) {
		t.Errorf("invalid updates wrote to the board file: %s", data)
	}
}

func TestSetViewsHATilesReplacesList(t *testing.T) {
	cfg, _ := viewsCfg(t, `{
		"views": {
			"order": ["calendar", "chat", "home", "push", "ha-1"],
			"hidden": ["ha-1"],
			"sizes": {"ha-1": "s"},
			"geometry": {"ha-1": {"x": 1, "y": 1, "w": 2, "h": 1}},
			"ha_tiles": [{"id": "ha-1", "entity": "light.old", "label": "Old", "kind": "button"}]
		}
	}`)
	// Replacing the HA tile list with a new tile repairs order/hidden/
	// sizes/geometry to the new tile set: the stale ID drops out
	// everywhere and the new ID is appended to the board order.
	haTiles := []HABoardTile{{ID: "ha-2", Entity: "light.new", Label: "New", Kind: HATileVisual}}
	if err := cfg.SetViews(nil, nil, nil, nil, nil, &haTiles); err != nil {
		t.Fatalf("SetViews(ha_tiles): %v", err)
	}
	v := cfg.ViewConfig()
	if !sameStrings(v.Order, []string{ViewCalendar, ViewChat, ViewHome, ViewPush, "ha-2"}) {
		t.Errorf("order = %v", v.Order)
	}
	if len(v.Hidden) != 0 {
		t.Errorf("hidden = %v, want empty (stale ID dropped)", v.Hidden)
	}
	if len(v.HATiles) != 1 || v.HATiles[0].ID != "ha-2" {
		t.Errorf("ha_tiles = %+v", v.HATiles)
	}
	if _, ok := v.Geometry["ha-1"]; ok {
		t.Errorf("stale geometry entry not dropped: %+v", v.Geometry)
	}
	if v.Sizes["ha-2"] != ViewSizeMedium {
		t.Errorf("new tile size = %q, want the default step", v.Sizes["ha-2"])
	}
	if _, ok := v.Sizes["ha-1"]; ok {
		t.Errorf("stale size entry not dropped: %+v", v.Sizes)
	}
}

// --- grid dimensions -----------------------------------------------------

func TestGridDimsDefaultsAndRepair(t *testing.T) {
	// No grid key: the default 12x12.
	cfg, _ := viewsCfg(t, `{"refresh_seconds": 30}`)
	if cols, rows := cfg.GridDims(); cols != 12 || rows != 12 {
		t.Errorf("default grid = %dx%d, want 12x12", cols, rows)
	}

	// A stored grid is honored.
	cfg, _ = viewsCfg(t, `{"grid": {"cols": 6, "rows": 4}}`)
	if cols, rows := cfg.GridDims(); cols != 6 || rows != 4 {
		t.Errorf("stored grid = %dx%d, want 6x4", cols, rows)
	}

	// Invalid stored dimensions fall back to the per-axis default (the
	// board file is a freehand-editable surface).
	cfg, _ = viewsCfg(t, `{"grid": {"cols": 99, "rows": 4}}`)
	if cols, rows := cfg.GridDims(); cols != 12 || rows != 4 {
		t.Errorf("repaired cols = %dx%d, want 12x4", cols, rows)
	}
	cfg, _ = viewsCfg(t, `{"grid": {"cols": 6, "rows": 0}}`)
	if cols, rows := cfg.GridDims(); cols != 6 || rows != 12 {
		t.Errorf("repaired rows = %dx%d, want 6x12", cols, rows)
	}
}

// A mistyped board.grid value must repair per axis on load instead of
// failing the board file load (which would take the whole backend down
// with it): a fractional or non-numeric dimension falls back to the
// per-axis default, a whole number is kept, and a grid that is not a JSON
// object falls back to the default 12x12.
func TestBoardGridTolerantDecode(t *testing.T) {
	cases := []struct {
		name string
		grid string
		cols int
		rows int
	}{
		{"float-whole-cols-string-rows", `{"grid": {"cols": 12.0, "rows": "12"}}`, 12, 12},
		{"fractional-cols", `{"grid": {"cols": 6.5, "rows": 4}}`, 12, 4},
		{"string-cols", `{"grid": {"cols": "6", "rows": 4}}`, 12, 4},
		{"out-of-range", `{"grid": {"cols": 99, "rows": -1}}`, 12, 12},
		{"missing-rows", `{"grid": {"cols": 6}}`, 6, 12},
		{"empty-object", `{"grid": {}}`, 12, 12},
		{"null", `{"grid": null}`, 12, 12},
		{"non-object-number", `{"grid": 5}`, 12, 12},
		{"non-object-string", `{"grid": "wobbly"}`, 12, 12},
		{"non-object-array", `{"grid": [6, 4]}`, 12, 12},
		{"valid", `{"grid": {"cols": 6, "rows": 4}}`, 6, 4},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg, _ := viewsCfg(t, tc.grid)
			if cols, rows := cfg.GridDims(); cols != tc.cols || rows != tc.rows {
				t.Errorf("grid = %dx%d, want %dx%d", cols, rows, tc.cols, tc.rows)
			}
		})
	}

	// A repaired non-object grid is stored as the valid default, not as an
	// invalid value: a later board write persists a grid the reload agrees
	// with (the in-memory board must never carry a mistyped dimension).
	cfg, file := viewsCfg(t, `{"refresh_seconds": 30, "grid": 5}`)
	if err := cfg.SetRefreshSeconds(45); err != nil {
		t.Fatalf("SetRefreshSeconds: %v", err)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var onDisk Board
	if err := json.Unmarshal(data, &onDisk); err != nil {
		t.Fatalf("board file not valid JSON: %v: %s", err, data)
	}
	if onDisk.Grid == nil || onDisk.Grid.Cols != 12 || onDisk.Grid.Rows != 12 {
		t.Errorf("persisted grid = %+v, want the repaired 12x12", onDisk.Grid)
	}
	t.Setenv("CONFIG_FILE", file)
	reloaded, err := Load()
	if err != nil {
		t.Fatalf("reloaded Load: %v", err)
	}
	if cols, rows := reloaded.GridDims(); cols != 12 || rows != 12 {
		t.Errorf("reloaded grid = %dx%d, want 12x12", cols, rows)
	}
}

func TestSetGridPersistsAndRepairs(t *testing.T) {
	// chat's stored placement fits the default 12-column grid…
	cfg, file := viewsCfg(t, `{"refresh_seconds": 45, "views": {"geometry": {"chat": {"x": 7, "y": 1, "w": 6, "h": 1}}}}`)
	if err := cfg.SetGrid(6, 6); err != nil {
		t.Fatalf("SetGrid: %v", err)
	}
	if cols, rows := cfg.GridDims(); cols != 6 || rows != 6 {
		t.Errorf("in-memory grid = %dx%d, want 6x6", cols, rows)
	}
	// …but no longer the 6-column grid: it is repaired back to auto
	// placement (no geometry entry), the same repair as a views write.
	v := cfg.ViewConfig()
	if _, ok := v.Geometry[ViewChat]; ok {
		t.Errorf("out-of-bounds geometry not repaired: %+v", v.Geometry)
	}

	// Persisted to the board file, other fields preserved.
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var onDisk Board
	if err := json.Unmarshal(data, &onDisk); err != nil {
		t.Fatalf("board file not valid JSON: %v: %s", err, data)
	}
	if onDisk.Grid == nil || onDisk.Grid.Cols != 6 || onDisk.Grid.Rows != 6 {
		t.Errorf("grid not written to the board file: %s", data)
	}
	if !strings.Contains(string(data), `"refresh_seconds": 45`) {
		t.Errorf("other board fields not preserved: %s", data)
	}

	// A fresh Load sees the persisted grid.
	t.Setenv("CONFIG_FILE", file)
	reloaded, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cols, rows := reloaded.GridDims(); cols != 6 || rows != 6 {
		t.Errorf("reloaded grid = %dx%d, want 6x6", cols, rows)
	}
}

func TestSetGridInvalidLeavesStateUntouched(t *testing.T) {
	cfg, file := viewsCfg(t, `{"refresh_seconds": 45}`)
	beforeCols, beforeRows := cfg.GridDims()
	cases := []struct {
		name string
		cols int
		rows int
	}{
		{"zero-cols", 0, 12},
		{"zero-rows", 12, 0},
		{"above-max-cols", 25, 12},
		{"above-max-rows", 12, 25},
		{"negative", -5, -5},
	}
	for _, tc := range cases {
		err := cfg.SetGrid(tc.cols, tc.rows)
		if err == nil {
			t.Fatalf("%s: unexpected success", tc.name)
		}
		if errors.Is(err, ErrBoardFileNotWritable) || errors.Is(err, ErrNoBoardFile) {
			t.Errorf("%s: err = %v looks like a storage error, want a validation error", tc.name, err)
		}
	}
	afterCols, afterRows := cfg.GridDims()
	if afterCols != beforeCols || afterRows != beforeRows {
		t.Errorf("grid changed by invalid updates: %dx%d -> %dx%d", beforeCols, beforeRows, afterCols, afterRows)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), `"grid"`) {
		t.Errorf("invalid updates wrote to the board file: %s", data)
	}
}

func TestSetGridNoBoardFile(t *testing.T) {
	cfg := &Config{}
	if err := cfg.SetGrid(8, 8); !errors.Is(err, ErrNoBoardFile) {
		t.Errorf("err = %v, want ErrNoBoardFile", err)
	}
}

func TestSetGridDirectoryPathIsNotWritable(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.Mkdir(file, 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{BoardFile: file}
	if err := cfg.SetGrid(8, 8); !errors.Is(err, ErrBoardFileNotWritable) {
		t.Fatalf("err = %v, want ErrBoardFileNotWritable", err)
	}
	if cols, rows := cfg.GridDims(); cols != 12 || rows != 12 {
		t.Errorf("in-memory grid = %dx%d, want unchanged defaults", cols, rows)
	}
}

// --- service settings (board.services): UI > env > default ----------------
//
// The settings screen persists the service configuration to the board file
// (board.services). Every field resolves on use: the UI value wins, the
// environment value is the fallback, and the documented default applies
// last. The tests below pin that chain per service group.

func servicesCfg(t *testing.T, content string) (*Config, string) {
	t.Helper()
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if content != "" {
		if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("CONFIG_FILE", file)
	t.Setenv("DATA_DIR", t.TempDir())
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	return cfg, file
}

func svcStrPtr(s string) *string { return &s }

func TestServicesPrecedenceHomeAssistant(t *testing.T) {
	cases := []struct {
		name, envURL, envTok, board string
		wantURL, wantTok            string
	}{
		{"neither set", "", "", "", "", ""},
		{"env only", "https://env.local", "env-tok", "", "https://env.local", "env-tok"},
		{"ui only", "", "", `{"services":{"homeassistant":{"url":"https://ui.local","token":"ui-tok"}}}`, "https://ui.local", "ui-tok"},
		{"ui wins over env", "https://env.local", "env-tok", `{"services":{"homeassistant":{"url":"https://ui.local","token":"ui-tok"}}}`, "https://ui.local", "ui-tok"},
		{"per field: ui url, env token", "https://env.local", "env-tok", `{"services":{"homeassistant":{"url":"https://ui.local"}}}`, "https://ui.local", "env-tok"},
		{"empty ui fields fall back to env", "https://env.local", "env-tok", `{"services":{"homeassistant":{"url":"","token":""}}}`, "https://env.local", "env-tok"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("HOMEASSISTANT_URL", tc.envURL)
			t.Setenv("HOMEASSISTANT_TOKEN", tc.envTok)
			cfg, _ := servicesCfg(t, tc.board)
			gotURL, gotTok := cfg.HASettings()
			if gotURL != tc.wantURL || gotTok != tc.wantTok {
				t.Errorf("HASettings = (%q, %q), want (%q, %q)", gotURL, gotTok, tc.wantURL, tc.wantTok)
			}
			if want := tc.wantURL != "" && tc.wantTok != ""; cfg.HAConfigured() != want {
				t.Errorf("HAConfigured = %v, want %v", cfg.HAConfigured(), want)
			}
		})
	}
}

func TestServicesPrecedenceHermesChat(t *testing.T) {
	const board = `{"services":{"hermes_chat":{"base_url":"https://ui.local","chat_path":"/v1/chat","auth_header":"X-Ui-Key","auth_value":"ui-key"}}}`
	cases := []struct {
		name                                      string
		envBase, envPath, envHeader, envValue     string
		board                                     string
		wantBase, wantPath, wantHeader, wantValue string
	}{
		{
			name:       "documented defaults when nothing set",
			wantPath:   "/chat/completions",
			wantHeader: "Authorization",
		},
		{
			name:       "env overrides defaults",
			envBase:    "https://env.local",
			envPath:    "/chat",
			envHeader:  "X-Api-Key",
			envValue:   "env-key",
			wantBase:   "https://env.local",
			wantPath:   "/chat",
			wantHeader: "X-Api-Key",
			wantValue:  "env-key",
		},
		{
			name:       "ui wins over env",
			envBase:    "https://env.local",
			envPath:    "/chat",
			envHeader:  "X-Api-Key",
			envValue:   "env-key",
			board:      board,
			wantBase:   "https://ui.local",
			wantPath:   "/v1/chat",
			wantHeader: "X-Ui-Key",
			wantValue:  "ui-key",
		},
		{
			name:       "per field: ui auth value, env url, default path/header",
			envBase:    "https://env.local",
			board:      `{"services":{"hermes_chat":{"auth_value":"ui-key"}}}`,
			wantBase:   "https://env.local",
			wantPath:   "/chat/completions",
			wantHeader: "Authorization",
			wantValue:  "ui-key",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("HERMES_BASE_URL", tc.envBase)
			t.Setenv("HERMES_CHAT_PATH", tc.envPath)
			t.Setenv("HERMES_AUTH_HEADER", tc.envHeader)
			t.Setenv("HERMES_AUTH_VALUE", tc.envValue)
			cfg, _ := servicesCfg(t, tc.board)
			gotBase, gotPath, gotHeader, gotValue := cfg.HermesChatSettings()
			if gotBase != tc.wantBase || gotPath != tc.wantPath || gotHeader != tc.wantHeader || gotValue != tc.wantValue {
				t.Errorf("HermesChatSettings = (%q, %q, %q, %q), want (%q, %q, %q, %q)",
					gotBase, gotPath, gotHeader, gotValue, tc.wantBase, tc.wantPath, tc.wantHeader, tc.wantValue)
			}
			if want := tc.wantBase != ""; cfg.HermesConfigured() != want {
				t.Errorf("HermesConfigured = %v, want %v", cfg.HermesConfigured(), want)
			}
		})
	}
}

func TestServicesPrecedenceGoogle(t *testing.T) {
	const board = `{"services":{"google":{
		"service_account_file":"./ui-sa.json","domain":"ui@workspace.com",
		"client_id":"ui-client","client_secret":"ui-secret","redirect_uri":"https://ui/cb",
		"token_cache":"./ui-token.json","access_token":"ui-ya29","endpoint":"https://ui.api"
	}}}`
	setEnv := func(t *testing.T, prefix string) {
		t.Setenv("GOOGLE_APPLICATION_CREDENTIALS", prefix+"sa.json")
		t.Setenv("GOOGLE_DOMAIN", prefix+"@workspace.com")
		t.Setenv("GOOGLE_CLIENT_ID", prefix+"client")
		t.Setenv("GOOGLE_CLIENT_SECRET", prefix+"secret")
		t.Setenv("GOOGLE_REDIRECT_URI", "https://"+prefix+"/cb")
		t.Setenv("GOOGLE_TOKEN_CACHE", prefix+"token.json")
		t.Setenv("GOOGLE_ACCESS_TOKEN", prefix+"ya29")
		t.Setenv("GOOGLE_API_ENDPOINT", "https://"+prefix+".api")
	}
	envSettings := func() GoogleConfig {
		return GoogleConfig{
			ServiceAccountFile: "envsa.json",
			Domain:             "env@workspace.com",
			ClientID:           "envclient",
			ClientSecret:       "envsecret",
			RedirectURI:        "https://env/cb",
			TokenCache:         "envtoken.json",
			AccessToken:        "envya29",
			Endpoint:           "https://env.api",
		}
	}
	uiSettings := func() GoogleConfig {
		return GoogleConfig{
			ServiceAccountFile: "./ui-sa.json",
			Domain:             "ui@workspace.com",
			ClientID:           "ui-client",
			ClientSecret:       "ui-secret",
			RedirectURI:        "https://ui/cb",
			TokenCache:         "./ui-token.json",
			AccessToken:        "ui-ya29",
			Endpoint:           "https://ui.api",
		}
	}

	t.Run("defaults when nothing set", func(t *testing.T) {
		for _, k := range []string{"GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_DOMAIN", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "GOOGLE_REDIRECT_URI", "GOOGLE_TOKEN_CACHE", "GOOGLE_ACCESS_TOKEN", "GOOGLE_API_ENDPOINT"} {
			t.Setenv(k, "")
		}
		cfg, _ := servicesCfg(t, "")
		g := cfg.GoogleSettings()
		if g != (GoogleConfig{TokenCache: filepath.Join(cfg.DataDir, "google-token.json")}) {
			t.Errorf("GoogleSettings = %+v, want only the default token cache", g)
		}
	})

	t.Run("env only", func(t *testing.T) {
		setEnv(t, "env")
		cfg, _ := servicesCfg(t, "")
		want := envSettings()
		if got := cfg.GoogleSettings(); got != want {
			t.Errorf("GoogleSettings = %+v, want %+v", got, want)
		}
	})

	t.Run("ui wins over env per field", func(t *testing.T) {
		setEnv(t, "env")
		cfg, _ := servicesCfg(t, board)
		want := uiSettings()
		if got := cfg.GoogleSettings(); got != want {
			t.Errorf("GoogleSettings = %+v, want %+v", got, want)
		}
	})
}

func TestServicesPrecedencePush(t *testing.T) {
	cases := []struct {
		name, envTok, board, want string
	}{
		{"neither set", "", "", ""},
		{"env only", "env-push", "", "env-push"},
		{"ui only", "", `{"services":{"hermes_push":{"token":"ui-push"}}}`, "ui-push"},
		{"ui wins over env", "env-push", `{"services":{"hermes_push":{"token":"ui-push"}}}`, "ui-push"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("HERMES_PUSH_TOKEN", tc.envTok)
			cfg, _ := servicesCfg(t, tc.board)
			if got := cfg.PushTokenEffective(); got != tc.want {
				t.Errorf("PushTokenEffective = %q, want %q", got, tc.want)
			}
			if want := tc.want != ""; cfg.PushConfigured() != want {
				t.Errorf("PushConfigured = %v, want %v", cfg.PushConfigured(), want)
			}
		})
	}
}

func TestSetServicesRoundTrip(t *testing.T) {
	t.Setenv("HOMEASSISTANT_URL", "https://env.local")
	t.Setenv("HOMEASSISTANT_TOKEN", "env-tok")
	cfg, file := servicesCfg(t, `{"refresh_seconds": 30}`)

	// Set two groups partially; the other groups stay absent.
	if err := cfg.SetServices(&ServicesUpdate{
		HomeAssistant: &ServiceHomeAssistantUpdate{URL: svcStrPtr("https://ui.local"), Token: svcStrPtr("ui-tok")},
		HermesPush:    &ServiceHermesPushUpdate{Token: svcStrPtr("push-ui")},
	}); err != nil {
		t.Fatalf("SetServices: %v", err)
	}
	if url, tok := cfg.HASettings(); url != "https://ui.local" || tok != "ui-tok" {
		t.Errorf("HASettings = (%q, %q), want the UI values", url, tok)
	}
	if tok := cfg.PushTokenEffective(); tok != "push-ui" {
		t.Errorf("PushTokenEffective = %q, want push-ui", tok)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var b Board
	if err := json.Unmarshal(data, &b); err != nil {
		t.Fatalf("parse board file: %v (%s)", err, data)
	}
	if b.RefreshSeconds != 30 {
		t.Errorf("other board fields not preserved: %+v", b)
	}
	if b.Services == nil || b.Services.HomeAssistant == nil ||
		b.Services.HomeAssistant.URL != "https://ui.local" || b.Services.HomeAssistant.Token != "ui-tok" ||
		b.Services.HermesPush == nil || b.Services.HermesPush.Token != "push-ui" ||
		b.Services.Google != nil || b.Services.HermesChat != nil {
		t.Errorf("board file services = %+v (%s)", b.Services, data)
	}

	// A partial update keeps the untouched fields and groups.
	if err := cfg.SetServices(&ServicesUpdate{HomeAssistant: &ServiceHomeAssistantUpdate{Token: svcStrPtr("ui-tok-2")}}); err != nil {
		t.Fatalf("SetServices: %v", err)
	}
	if url, tok := cfg.HASettings(); url != "https://ui.local" || tok != "ui-tok-2" {
		t.Errorf("HASettings = (%q, %q), want url kept and token updated", url, tok)
	}
	if tok := cfg.PushTokenEffective(); tok != "push-ui" {
		t.Errorf("push token = %q, want unchanged", tok)
	}

	// An empty string clears the UI value: the environment fallback comes
	// back.
	if err := cfg.SetServices(&ServicesUpdate{HomeAssistant: &ServiceHomeAssistantUpdate{URL: svcStrPtr("")}}); err != nil {
		t.Fatalf("SetServices: %v", err)
	}
	if url, tok := cfg.HASettings(); url != "https://env.local" || tok != "ui-tok-2" {
		t.Errorf("HASettings = (%q, %q), want env url and UI token", url, tok)
	}

	// A reload agrees with the in-memory state.
	cfg2, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if url, tok := cfg2.HASettings(); url != "https://env.local" || tok != "ui-tok-2" {
		t.Errorf("reloaded HASettings = (%q, %q)", url, tok)
	}
	if tok := cfg2.PushTokenEffective(); tok != "push-ui" {
		t.Errorf("reloaded push token = %q", tok)
	}
}

func TestSetServicesUnchangedValueIsANoop(t *testing.T) {
	cfg, file := servicesCfg(t, "")
	if err := cfg.SetServices(&ServicesUpdate{HermesPush: &ServiceHermesPushUpdate{Token: svcStrPtr("push-ui")}}); err != nil {
		t.Fatalf("SetServices: %v", err)
	}
	before, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	// Re-setting the same value must not change the stored content.
	if err := cfg.SetServices(&ServicesUpdate{HermesPush: &ServiceHermesPushUpdate{Token: svcStrPtr("push-ui")}}); err != nil {
		t.Fatalf("SetServices: %v", err)
	}
	after, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(before, after) {
		t.Errorf("unchanged update altered the board file: %s -> %s", before, after)
	}
	// Pin that the unchanged update wrote nothing at all: make the board
	// file impossible to replace, and the same-value update must still
	// succeed (a write would fail with ErrBoardFileNotWritable).
	if err := os.Rename(file, file+".orig"); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(file, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := cfg.SetServices(&ServicesUpdate{HermesPush: &ServiceHermesPushUpdate{Token: svcStrPtr("push-ui")}}); err != nil {
		t.Errorf("unchanged update with an unwritable board file = %v, want a no-op success", err)
	}
}

func TestSetServicesNoBoardFile(t *testing.T) {
	cfg := &Config{}
	if err := cfg.SetServices(&ServicesUpdate{}); !errors.Is(err, ErrNoBoardFile) {
		t.Errorf("err = %v, want ErrNoBoardFile", err)
	}
}

func TestSetServicesDirectoryPathIsNotWritable(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.Mkdir(file, 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{BoardFile: file}
	if err := cfg.SetServices(&ServicesUpdate{HermesPush: &ServiceHermesPushUpdate{Token: svcStrPtr("x")}}); !errors.Is(err, ErrBoardFileNotWritable) {
		t.Fatalf("err = %v, want ErrBoardFileNotWritable", err)
	}
	if tok := cfg.PushTokenEffective(); tok != "" {
		t.Errorf("in-memory push token = %q, want unchanged", tok)
	}
}

// A failed write (the board file becomes unreadable-to-replace mid-update)
// must leave the in-memory board and the effective settings untouched.
func TestSetServicesFailedWriteLeavesStateUntouched(t *testing.T) {
	cfg, file := servicesCfg(t, "")
	if err := cfg.SetServices(&ServicesUpdate{HomeAssistant: &ServiceHomeAssistantUpdate{URL: svcStrPtr("https://ui.local")}}); err != nil {
		t.Fatalf("SetServices: %v", err)
	}
	// Make the next write fail: the board file's directory is read-only.
	// (Rename would succeed, so replace the file with a directory instead.)
	if err := os.Rename(file, file+".orig"); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(file, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := cfg.SetServices(&ServicesUpdate{HomeAssistant: &ServiceHomeAssistantUpdate{URL: svcStrPtr("https://other.local")}}); !errors.Is(err, ErrBoardFileNotWritable) {
		t.Fatalf("err = %v, want ErrBoardFileNotWritable", err)
	}
	if url, _ := cfg.HASettings(); url != "https://ui.local" {
		t.Errorf("in-memory HA url = %q, want the previous value", url)
	}
}

// TestServicesViewMasksSecrets pins the exposure contract: non-secret
// fields carry the UI value and the environment fallback; secret fields
// carry only the set booleans — never a value, from either layer.
func TestServicesViewMasksSecrets(t *testing.T) {
	t.Setenv("HOMEASSISTANT_URL", "https://env.local")
	t.Setenv("HOMEASSISTANT_TOKEN", "env-ha-secret")
	t.Setenv("HERMES_BASE_URL", "https://env.hermes")
	t.Setenv("HERMES_AUTH_VALUE", "env-hermes-secret")
	t.Setenv("HERMES_PUSH_TOKEN", "env-push-secret")
	t.Setenv("GOOGLE_CLIENT_ID", "env-client")
	t.Setenv("GOOGLE_CLIENT_SECRET", "env-google-secret")
	cfg, _ := servicesCfg(t, `{"services":{
		"homeassistant":{"url":"https://ui.local","token":"ui-ha-secret"},
		"hermes_chat":{"auth_value":"ui-hermes-secret"},
		"hermes_push":{"token":"ui-push-secret"},
		"google":{"client_secret":"ui-google-secret"}
	}}`)

	v := cfg.ServicesView()

	// Non-secret: UI value + env fallback exposed.
	if v.HomeAssistant.URL.Value != "https://ui.local" || v.HomeAssistant.URL.Fallback != "https://env.local" {
		t.Errorf("HA url = %+v", v.HomeAssistant.URL)
	}
	if !v.HomeAssistant.URL.Set || !v.HomeAssistant.URL.FallbackSet {
		t.Errorf("HA url set flags = %+v", v.HomeAssistant.URL)
	}
	if v.HermesChat.BaseURL.Value != "" || v.HermesChat.BaseURL.Fallback != "https://env.hermes" {
		t.Errorf("hermes base url = %+v", v.HermesChat.BaseURL)
	}
	if v.Google.ClientID.Value != "" || v.Google.ClientID.Fallback != "env-client" {
		t.Errorf("google client id = %+v", v.Google.ClientID)
	}

	// Secrets: no value in either layer, only the set booleans.
	secrets := []struct {
		name             string
		field            ServiceField
		set, fallbackSet bool
	}{
		{"ha token", v.HomeAssistant.Token, true, true},
		{"hermes auth value", v.HermesChat.AuthValue, true, true},
		{"push token", v.HermesPush.Token, true, true},
		{"google client secret", v.Google.ClientSecret, true, true},
		{"google access token (env only)", v.Google.AccessToken, false, false},
	}
	for _, tc := range secrets {
		if tc.field.Value != "" || tc.field.Fallback != "" {
			t.Errorf("%s: secret value exposed: %+v", tc.name, tc.field)
		}
		if tc.field.Set != tc.set || tc.field.FallbackSet != tc.fallbackSet {
			t.Errorf("%s: set booleans = %+v, want set=%v fallbackSet=%v", tc.name, tc.field, tc.set, tc.fallbackSet)
		}
	}

	// The whole view must not contain any of the secret strings.
	out, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range []string{"env-ha-secret", "ui-ha-secret", "env-hermes-secret", "ui-hermes-secret", "env-push-secret", "ui-push-secret", "env-google-secret", "ui-google-secret"} {
		if strings.Contains(string(out), s) {
			t.Errorf("view leaked secret %q: %s", s, out)
		}
	}
}
