package config

import (
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
	if err := cfg.SetViews(&order, &hidden, &mode, &sizes); err != nil {
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
	if err := cfg.SetViews(nil, nil, &mode, nil); err != nil {
		t.Fatalf("SetViews(mode): %v", err)
	}
	v := cfg.ViewConfig()
	if !sameStrings(v.Order, []string{ViewChat, ViewCalendar, ViewPush, ViewHome}) ||
		!sameStrings(v.Hidden, []string{ViewPush}) || v.CalendarMode != CalendarModeMonth {
		t.Errorf("after mode-only update = %+v", v)
	}

	// Only the order changes.
	order := []string{ViewPush, ViewChat, ViewHome, ViewCalendar}
	if err := cfg.SetViews(&order, nil, nil, nil); err != nil {
		t.Fatalf("SetViews(order): %v", err)
	}
	v = cfg.ViewConfig()
	if !sameStrings(v.Order, order) || !sameStrings(v.Hidden, []string{ViewPush}) || v.CalendarMode != CalendarModeMonth {
		t.Errorf("after order-only update = %+v", v)
	}

	// An empty hidden list clears the hidden set.
	empty := []string{}
	if err := cfg.SetViews(nil, &empty, nil, nil); err != nil {
		t.Fatalf("SetViews(hidden=[]): %v", err)
	}
	v = cfg.ViewConfig()
	if !sameStrings(v.Order, order) || len(v.Hidden) != 0 {
		t.Errorf("after hidden=[] update = %+v", v)
	}

	// Only one tile's size changes; the other tiles keep their effective
	// sizes (the board file set no sizes at all, so they are the default).
	oneSize := map[string]string{ViewChat: ViewSizeSmall}
	if err := cfg.SetViews(nil, nil, nil, &oneSize); err != nil {
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
	if err := cfg.SetViews(nil, nil, nil, &none); err != nil {
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
		err := cfg.SetViews(nilIfNil(tc.order), nilIfNil(tc.hidden), tc.m, tc.sizes)
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
	if err := cfg.SetViews(&order, nil, nil, nil); !errors.Is(err, ErrNoBoardFile) {
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
	if err := cfg.SetViews(&order, nil, nil, nil); !errors.Is(err, ErrBoardFileNotWritable) {
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
	if err := cfg.SetViews(&order, &hidden, &mode, nil); err != nil {
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
