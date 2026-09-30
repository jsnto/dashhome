package ha

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	websocket "nhooyr.io/websocket"
)

// --- fake Home Assistant WebSocket server ---------------------------------

type fakeConfigCall struct {
	URLPath *string
	Force   bool
}

type fakeCalls struct {
	mu        sync.Mutex
	Auth      int
	Commands  []string
	ConfigSee []fakeConfigCall
}

func (f *fakeCalls) commandCount(name string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, c := range f.Commands {
		if c == name {
			n++
		}
	}
	return n
}

func (f *fakeCalls) lastConfig() (fakeConfigCall, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.ConfigSee) == 0 {
		return fakeConfigCall{}, false
	}
	return f.ConfigSee[len(f.ConfigSee)-1], true
}

// fakeHAWS speaks just enough of the Home Assistant WebSocket API for the
// DashClient: auth_required -> auth -> auth_ok, then result replies for the
// dashboard commands of the given style. A presentation of rejectToken is
// answered with auth_invalid without failing the test (for auth-error
// coverage); any other token mismatch is a test bug. `errorCommand` (if set)
// answers success=false with `errorMessage`.
func fakeHAWS(t *testing.T, style commandStyle, token, rejectToken string, errorCommand, errorMessage string) (string, *fakeCalls) {
	t.Helper()
	calls := &fakeCalls{}
	list := json.RawMessage(`[
		{"id":"default","url_path":null,"title":"My Home","icon":null,"require_admin":false,"show_in_sidebar":true,"mode":"storage"},
		{"id":"energy","url_path":"energy","title":"Energy","icon":"mdi:flash","require_admin":true,"show_in_sidebar":true,"mode":"yaml","filename":"energy.yaml"}
	]`)
	cfg := json.RawMessage(`{"views":[{"title":"Overview","path":"overview","cards":[{"type":"entities","entities":["climate.thermo"]}]}]}`)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/websocket" {
			http.NotFound(w, r)
			return
		}
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		bctx := context.Background()
		send := func(v any) {
			b, err := json.Marshal(v)
			if err == nil {
				if err := conn.Write(bctx, websocket.MessageText, b); err != nil {
					t.Errorf("write: %v", err)
				}
			}
		}
		read := func(v any) error {
			for {
				mt, b, err := conn.Read(bctx)
				if err != nil {
					return err
				}
				if mt != websocket.MessageText {
					continue
				}
				return json.Unmarshal(b, v)
			}
		}

		send(map[string]string{"type": "auth_required"})
		var auth struct {
			Type        string `json:"type"`
			AccessToken string `json:"access_token"`
		}
		if err := read(&auth); err != nil {
			t.Errorf("read auth: %v", err)
			return
		}
		calls.mu.Lock()
		calls.Auth++
		calls.mu.Unlock()
		if auth.Type != "auth" {
			t.Errorf("first client message = %+v", auth)
		}
		if token != "" && auth.AccessToken != token && auth.AccessToken != rejectToken {
			t.Errorf("access_token = %q, want %q", auth.AccessToken, token)
		}
		if auth.AccessToken != token {
			send(map[string]string{"type": "auth_invalid"})
			return
		}
		send(map[string]any{"type": "auth_ok", "ha_version": "2026.1.0"})

		for {
			var cmd struct {
				ID      json.Number `json:"id"`
				Type    string      `json:"type"`
				URLPath *string     `json:"url_path"`
				Force   bool        `json:"force"`
			}
			if err := read(&cmd); err != nil {
				return // client hung up
			}
			calls.mu.Lock()
			calls.Commands = append(calls.Commands, cmd.Type)
			if cmd.Type == cmdConfigNew || cmd.Type == cmdConfigOld {
				calls.ConfigSee = append(calls.ConfigSee, fakeConfigCall{URLPath: cmd.URLPath, Force: cmd.Force})
			}
			calls.mu.Unlock()

			switch cmd.Type {
			case cmdListNew, cmdListOld:
				if style == styleLegacy && cmd.Type == cmdListNew {
					send(unknownCommand(cmd.ID))
					break
				}
				send(result(cmd.ID, true, list, ""))
			case cmdConfigNew, cmdConfigOld:
				if style == styleLegacy && cmd.Type == cmdConfigNew {
					send(unknownCommand(cmd.ID))
					break
				}
				if cmd.Type == errorCommand {
					send(result(cmd.ID, false, nil, errorMessage))
					break
				}
				send(result(cmd.ID, true, cfg, ""))
			default:
				send(unknownCommand(cmd.ID))
			}
		}
	}))
	t.Cleanup(srv.Close)
	return srv.URL, calls
}

func result(id json.Number, success bool, res json.RawMessage, message string) map[string]any {
	out := map[string]any{"id": id, "type": "result", "success": success}
	if res != nil {
		out["result"] = json.RawMessage(res)
	}
	if message != "" {
		out["message"] = message
	}
	return out
}

func unknownCommand(id json.Number) map[string]any {
	return result(id, false, nil, "Unknown command.")
}

func sp(s string) *string { return &s }

// --- tests -----------------------------------------------------------------

func TestDashListDashboardsModern(t *testing.T) {
	base, calls := fakeHAWS(t, styleLovelace, "ha-tok", "", "", "")
	c := NewDashClient(base, "ha-tok")

	list, err := c.ListDashboards(context.Background(), false)
	if err != nil {
		t.Fatalf("ListDashboards: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("len = %d, want 2", len(list))
	}
	d0 := list[0]
	if d0.ID != "default" || d0.URLPath != nil || d0.Title != "My Home" || d0.Mode != "storage" || !d0.ShowInSidebar || d0.RequireAdmin {
		t.Errorf("default = %+v", d0)
	}
	d1 := list[1]
	if d1.ID != "energy" || d1.URLPath == nil || *d1.URLPath != "energy" || d1.Icon != "mdi:flash" || !d1.RequireAdmin || d1.Mode != "yaml" || d1.Filename != "energy.yaml" {
		t.Errorf("energy = %+v", d1)
	}
	if n := calls.commandCount(cmdListNew); n != 1 {
		t.Errorf("list commands = %d, want 1 (got %v)", n, calls.Commands)
	}
	if got := calls.commandCount(cmdListOld); got != 0 {
		t.Errorf("legacy list commands = %d, want 0", got)
	}

	// Cached: a second call hits the cache, not the network.
	if _, err := c.ListDashboards(context.Background(), false); err != nil {
		t.Fatalf("cached ListDashboards: %v", err)
	}
	if n := calls.commandCount(cmdListNew); n != 1 {
		t.Errorf("list commands after cache hit = %d, want 1", n)
	}

	// force bypasses the cache.
	if _, err := c.ListDashboards(context.Background(), true); err != nil {
		t.Fatalf("forced ListDashboards: %v", err)
	}
	if n := calls.commandCount(cmdListNew); n != 2 {
		t.Errorf("list commands after force = %d, want 2", n)
	}
}

func TestDashListDashboardsLegacyProbe(t *testing.T) {
	base, calls := fakeHAWS(t, styleLegacy, "ha-tok", "", "", "")
	c := NewDashClient(base, "ha-tok")

	list, err := c.ListDashboards(context.Background(), false)
	if err != nil {
		t.Fatalf("ListDashboards: %v", err)
	}
	if len(list) != 2 || list[1].ID != "energy" {
		t.Fatalf("list = %+v", list)
	}
	// First use probes the modern name, then falls back and remembers.
	if n := calls.commandCount(cmdListNew); n != 1 {
		t.Errorf("modern probes = %d, want 1 (%v)", n, calls.Commands)
	}
	if n := calls.commandCount(cmdListOld); n != 1 {
		t.Errorf("legacy commands = %d, want 1", n)
	}

	// Second call goes straight to the legacy name.
	if _, err := c.ListDashboards(context.Background(), true); err != nil {
		t.Fatalf("forced ListDashboards: %v", err)
	}
	if n := calls.commandCount(cmdListNew); n != 1 {
		t.Errorf("modern commands after remember = %d, want 1", n)
	}
	if n := calls.commandCount(cmdListOld); n != 2 {
		t.Errorf("legacy commands after remember = %d, want 2", n)
	}
}

func TestDashGetDashboardConfigModern(t *testing.T) {
	base, calls := fakeHAWS(t, styleLovelace, "ha-tok", "", "", "")
	c := NewDashClient(base, "ha-tok")

	raw, err := c.GetDashboardConfig(context.Background(), "energy", false)
	if err != nil {
		t.Fatalf("GetDashboardConfig: %v", err)
	}
	var views struct {
		Views []struct {
			Title string `json:"title"`
		} `json:"views"`
	}
	if err := json.Unmarshal(raw, &views); err != nil || len(views.Views) != 1 || views.Views[0].Title != "Overview" {
		t.Errorf("config = %s (%v)", raw, err)
	}
	if got, ok := calls.lastConfig(); !ok || got.URLPath == nil || *got.URLPath != "energy" || got.Force {
		t.Errorf("config call = %+v, want url_path=energy force=false", got)
	}

	// Normalized: equivalent spellings share one cache entry.
	if _, err := c.GetDashboardConfig(context.Background(), "/energy/", false); err != nil {
		t.Fatalf("normalized GetDashboardConfig: %v", err)
	}
	if n := calls.commandCount(cmdConfigNew); n != 1 {
		t.Errorf("config commands = %d, want 1 (normalized cache miss)", n)
	}

	// Default dashboard (empty path) is a separate entry, sent as url_path null.
	if _, err := c.GetDashboardConfig(context.Background(), "", false); err != nil {
		t.Fatalf("default GetDashboardConfig: %v", err)
	}
	if got, ok := calls.lastConfig(); !ok || got.URLPath != nil || got.Force {
		t.Errorf("default config call = %+v, want url_path=nil force=false", got)
	}

	// force re-fetches and passes force=true upstream.
	if _, err := c.GetDashboardConfig(context.Background(), "energy", true); err != nil {
		t.Fatalf("forced GetDashboardConfig: %v", err)
	}
	if n := calls.commandCount(cmdConfigNew); n != 3 {
		t.Errorf("config commands after force = %d, want 3", n)
	}
	if got, ok := calls.lastConfig(); !ok || !got.Force {
		t.Errorf("forced config call = %+v, want force=true", got)
	}
}

func TestDashGetDashboardConfigLegacy(t *testing.T) {
	base, calls := fakeHAWS(t, styleLegacy, "ha-tok", "", "", "")
	c := NewDashClient(base, "ha-tok")

	raw, err := c.GetDashboardConfig(context.Background(), "energy", false)
	if err != nil {
		t.Fatalf("GetDashboardConfig: %v", err)
	}
	if !strings.Contains(string(raw), `"Overview"`) {
		t.Errorf("config = %s", raw)
	}
	if n := calls.commandCount(cmdConfigNew); n != 1 {
		t.Errorf("modern probes = %d, want 1", n)
	}
	if n := calls.commandCount(cmdConfigOld); n != 1 {
		t.Errorf("legacy commands = %d, want 1", n)
	}
	// Remembered: the second call skips the probe.
	if _, err := c.GetDashboardConfig(context.Background(), "", true); err != nil {
		t.Fatalf("forced default GetDashboardConfig: %v", err)
	}
	if n := calls.commandCount(cmdConfigNew); n != 1 {
		t.Errorf("modern commands after remember = %d, want 1", n)
	}
}

func TestDashAuthInvalid(t *testing.T) {
	base, calls := fakeHAWS(t, styleLovelace, "ha-tok", "wrong-token", "", "")
	c := NewDashClient(base, "wrong-token")

	_, err := c.ListDashboards(context.Background(), false)
	if err == nil {
		t.Fatal("expected auth error")
	}
	if !strings.Contains(err.Error(), "HOMEASSISTANT_TOKEN") {
		t.Errorf("err = %v, want token hint", err)
	}
	if calls.Auth != 1 || len(calls.Commands) != 0 {
		t.Errorf("auth=%d commands=%v, want one auth and no commands", calls.Auth, calls.Commands)
	}
}

func TestDashCommandError(t *testing.T) {
	base, _ := fakeHAWS(t, styleLovelace, "ha-tok", "", cmdConfigNew, "No dashboard found with path 'nope'")
	c := NewDashClient(base, "ha-tok")

	_, err := c.GetDashboardConfig(context.Background(), "nope", false)
	if err == nil {
		t.Fatal("expected command error")
	}
	if !strings.Contains(err.Error(), "No dashboard found") {
		t.Errorf("err = %v, want the HA message", err)
	}
}

func TestDashDialErrors(t *testing.T) {
	if _, err := NewDashClient("not a url", "tok").ListDashboards(context.Background(), false); err == nil ||
		!strings.Contains(err.Error(), "invalid HOMEASSISTANT_URL") {
		t.Errorf("bad base URL: err = %v", err)
	}

	// Unreachable host: bounded by CallTimeout, not the 15 s default.
	c := NewDashClient("http://127.0.0.1:1", "tok")
	c.CallTimeout = 500 * time.Millisecond
	if _, err := c.ListDashboards(context.Background(), false); err == nil || !strings.Contains(err.Error(), "home assistant websocket") {
		t.Errorf("unreachable: err = %v", err)
	}
}

func TestDashCacheTTLElapses(t *testing.T) {
	base, calls := fakeHAWS(t, styleLovelace, "ha-tok", "", "", "")
	c := NewDashClient(base, "ha-tok")
	c.ListTTL = 30 * time.Millisecond
	c.ConfigTTL = 30 * time.Millisecond

	must := func() {
		if _, err := c.ListDashboards(context.Background(), false); err != nil {
			t.Fatalf("list: %v", err)
		}
		if _, err := c.GetDashboardConfig(context.Background(), "energy", false); err != nil {
			t.Fatalf("config: %v", err)
		}
	}
	must()
	time.Sleep(60 * time.Millisecond)
	must()
	if n := calls.commandCount(cmdListNew); n != 2 {
		t.Errorf("list commands = %d, want 2 (TTL elapsed)", n)
	}
	if n := calls.commandCount(cmdConfigNew); n != 2 {
		t.Errorf("config commands = %d, want 2 (TTL elapsed)", n)
	}
}

func TestDashNormalizeURLPath(t *testing.T) {
	for in, want := range map[string]string{
		"":        "",
		"/":       "",
		"energy":  "energy",
		"/energy": "energy",
		"energy/": "energy",
		" /x/ ":   "x",
		"//a//b/": "a//b",
	} {
		if got := NormalizeURLPath(in); got != want {
			t.Errorf("NormalizeURLPath(%q) = %q, want %q", in, got, want)
		}
	}
}
