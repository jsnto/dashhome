package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/oauth2"

	"github.com/jsnto/dashhome/backend/calendar"
	"github.com/jsnto/dashhome/backend/config"
	"github.com/jsnto/dashhome/backend/ha"
	"github.com/jsnto/dashhome/backend/hermes"
	"github.com/jsnto/dashhome/backend/store"
)

func init() { gin.SetMode(gin.TestMode) }

// --- fakes ---------------------------------------------------------------

type fakeGoogle struct {
	cals      []calendar.Calendar
	events    map[string][]calendar.Event
	err       error
	hasToken  bool
	loginURL  string
	lastStart time.Time
	lastEnd   time.Time
}

func (f *fakeGoogle) ListCalendars(ctx context.Context) ([]calendar.Calendar, error) {
	if f.err != nil {
		return nil, f.err
	}
	return f.cals, nil
}

func (f *fakeGoogle) ListEvents(ctx context.Context, id string, start, end time.Time) ([]calendar.Event, error) {
	if f.err != nil {
		return nil, f.err
	}
	f.lastStart, f.lastEnd = start, end
	return f.events[id], nil
}

func (f *fakeGoogle) Mode() calendar.Mode                             { return calendar.ModeOAuth }
func (f *fakeGoogle) HasToken() bool                                  { return f.hasToken }
func (f *fakeGoogle) LoginURL() string                                { return f.loginURL }
func (f *fakeGoogle) Callback(ctx context.Context, code string) error { return nil }

// newTestGoogleOAuth builds a real OAuth-mode Google client for handler tests.
func newTestGoogleOAuth(cache string) (*calendar.GoogleClient, error) {
	return calendar.NewGoogle(context.Background(), calendar.GoogleSettings{
		ClientID:     "id",
		ClientSecret: "secret",
		RedirectURI:  "https://example.com/cb",
		TokenCache:   cache,
	})
}

type fakeHA struct{ states []ha.EntityState }

func (f *fakeHA) GetStates(ctx context.Context) ([]ha.EntityState, error) { return f.states, nil }

// fakeDash implements ha.DashboardsClient and records every call so tests
// can assert force/reload handling without a real WS server.
type fakeDash struct {
	mu        sync.Mutex
	list      []ha.Dashboard
	listErr   error
	configRaw json.RawMessage
	configErr error
	calls     []string
}

func (f *fakeDash) ListDashboards(ctx context.Context, force bool) ([]ha.Dashboard, error) {
	f.record(fmt.Sprintf("list:force=%v", force))
	if f.listErr != nil {
		return nil, f.listErr
	}
	return f.list, nil
}

func (f *fakeDash) GetDashboardConfig(ctx context.Context, urlPath string, force bool) (json.RawMessage, error) {
	f.record(fmt.Sprintf("config:%q:force=%v", urlPath, force))
	if f.configErr != nil {
		return nil, f.configErr
	}
	if f.configRaw == nil {
		return json.RawMessage(`{"views":[{"title":"Overview"}]}`), nil
	}
	return f.configRaw, nil
}

func (f *fakeDash) record(call string) {
	f.mu.Lock()
	f.calls = append(f.calls, call)
	f.mu.Unlock()
}

func (f *fakeDash) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.calls)
}

type fakeHermes struct {
	reply   string
	err     error
	lastReq hermes.ChatRequest
}

func (f *fakeHermes) Chat(ctx context.Context, req hermes.ChatRequest) (string, error) {
	f.lastReq = req
	return f.reply, f.err
}

// --- helpers ---------------------------------------------------------------

func newServer(t *testing.T, cfg *config.Config, google GoogleAPI, haC ha.StatesClient, herm hermes.Chatter, haDash ha.DashboardsClient) *gin.Engine {
	t.Helper()
	pushes, err := store.NewFileStore(filepath.Join(t.TempDir(), "pushes"))
	if err != nil {
		t.Fatal(err)
	}
	return NewServer(cfg, google, haC, haDash, herm, pushes).Engine()
}

func do(r *gin.Engine, method, path string, body any, hdr map[string]string) *httptest.ResponseRecorder {
	var reader *strings.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		reader = strings.NewReader(string(b))
	} else {
		reader = strings.NewReader("")
	}
	req := httptest.NewRequest(method, path, reader)
	if hdr == nil {
		hdr = map[string]string{"Content-Type": "application/json"}
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func cfgFor(t *testing.T) *config.Config {
	return &config.Config{
		Port:      "3000",
		PushToken: "push-tok",
		Board: config.Board{
			HALabels: map[string]string{"climate.thermo": "Living heat"},
			Calendars: []config.CalendarRef{
				{ID: "personal@example.com", Label: "Personal"},
			},
		},
	}
}

func testTime(s string) time.Time {
	ts, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return ts
}

// --- config / health ---------------------------------------------------------

func TestHealthz(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "GET", "/healthz", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d", w.Code)
	}
}

func TestGetConfig(t *testing.T) {
	cfg := cfgFor(t)
	cfg.HAURL, cfg.HAToken = "https://ha.local", "tok"
	cfg.HermesBaseURL = "https://hermes.local"
	g := &fakeGoogle{hasToken: true, cals: []calendar.Calendar{{ID: "personal@example.com", Summary: "Personal"}}}
	r := newServer(t, cfg, g, &fakeHA{}, &fakeHermes{}, nil)
	w := do(r, "GET", "/api/config", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Integrations struct {
			Homeassistant bool `json:"homeassistant"`
			Google        struct {
				Configured    bool   `json:"configured"`
				Mode          string `json:"mode"`
				LoginRequired bool   `json:"login_required"`
			} `json:"google"`
			HermesChat bool `json:"hermes_chat"`
			HermesPush bool `json:"hermes_push"`
		} `json:"integrations"`
		Board struct {
			HAEntityIDs []string `json:"ha_entities"`
		} `json:"board"`
	}
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if !resp.Integrations.Homeassistant || !resp.Integrations.HermesChat || !resp.Integrations.HermesPush {
		t.Errorf("integrations = %+v", resp.Integrations)
	}
	if !resp.Integrations.Google.Configured || resp.Integrations.Google.LoginRequired {
		t.Errorf("google = %+v", resp.Integrations.Google)
	}
}

func TestGetConfigUnconfiguredGoogle(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "GET", "/api/config", nil, nil)
	var resp struct {
		Integrations struct {
			Google struct {
				Configured bool `json:"configured"`
			} `json:"google"`
		} `json:"integrations"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp.Integrations.Google.Configured {
		t.Error("google should be unconfigured")
	}
}

// The board file is deliberately non-secret, but GET /api/config must never
// leak the environment-derived secrets either. Every value configured here
// must be absent from the response body.
func TestGetConfigSecretFree(t *testing.T) {
	cfg := cfgFor(t)
	cfg.HAURL, cfg.HAToken = "https://ha.local", "super-secret-ha-token"
	cfg.HermesBaseURL = "https://hermes.local"
	cfg.HermesAuthValue = "Bearer super-secret-hermes-key"
	cfg.PushToken = "super-secret-push-token"
	cfg.Google.ClientID = "12345.apps.googleusercontent.com"
	cfg.Google.ClientSecret = "super-secret-google-client"
	cfg.Google.AccessToken = "ya29.super-secret-google-access"
	g := &fakeGoogle{hasToken: true, cals: []calendar.Calendar{{ID: "personal@example.com", Summary: "Personal"}}}
	r := newServer(t, cfg, g, &fakeHA{}, &fakeHermes{}, nil)
	w := do(r, "GET", "/api/config", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	for _, secret := range []string{
		"super-secret-ha-token",
		"super-secret-hermes-key",
		"super-secret-push-token",
		"super-secret-google-client",
		"super-secret-google-access",
	} {
		if strings.Contains(body, secret) {
			t.Errorf("GET /api/config leaked secret %q in: %s", secret, body)
		}
	}
}

func TestPutConfigRefresh(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.WriteFile(file, []byte(`{"ha_entities":["climate.thermo"],"refresh_seconds":30}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := cfgFor(t)
	cfg.BoardFile = file
	r := newServer(t, cfg, nil, nil, nil, nil)

	// Valid update: accepted, in-memory and on-disk, and visible via GET.
	w := do(r, "PUT", "/api/config/refresh", map[string]any{"refresh_seconds": 45}, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Board struct {
			RefreshSeconds int `json:"refresh_seconds"`
		} `json:"board"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp.Board.RefreshSeconds != 45 {
		t.Errorf("response refresh_seconds = %d, want 45", resp.Board.RefreshSeconds)
	}
	if cfg.RefreshSeconds() != 45 {
		t.Errorf("in-memory RefreshSeconds = %d, want 45", cfg.RefreshSeconds())
	}
	data, _ := os.ReadFile(file)
	if !strings.Contains(string(data), `"refresh_seconds": 45`) {
		t.Errorf("file not updated: %s", data)
	}
	if !strings.Contains(string(data), `"climate.thermo"`) {
		t.Errorf("other board fields not preserved: %s", data)
	}
	wg := do(r, "GET", "/api/config", nil, nil)
	var getResp struct {
		Board struct {
			RefreshSeconds int `json:"refresh_seconds"`
		} `json:"board"`
	}
	json.NewDecoder(wg.Body).Decode(&getResp)
	if getResp.Board.RefreshSeconds != 45 {
		t.Errorf("GET refresh_seconds = %d, want 45", getResp.Board.RefreshSeconds)
	}
}

func TestPutConfigRefreshInvalid(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.WriteFile(file, []byte(`{"refresh_seconds":30}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := cfgFor(t)
	cfg.BoardFile = file
	r := newServer(t, cfg, nil, nil, nil, nil)

	cases := []struct {
		name string
		body any
	}{
		{"zero", map[string]any{"refresh_seconds": 0}},
		{"below-min", map[string]any{"refresh_seconds": 4}},
		{"above-max", map[string]any{"refresh_seconds": 3601}},
		{"negative", map[string]any{"refresh_seconds": -5}},
		{"string", map[string]any{"refresh_seconds": "45"}},
		{"missing", map[string]any{}},
		{"wrong-type-body", "not-json"},
	}
	for _, tc := range cases {
		w := do(r, "PUT", "/api/config/refresh", tc.body, map[string]string{"Content-Type": "application/json"})
		if w.Code != http.StatusBadRequest {
			t.Fatalf("%s: code = %d, want 400: %s", tc.name, w.Code, w.Body.String())
		}
	}
	if cfg.RefreshSeconds() != 30 {
		t.Errorf("in-memory RefreshSeconds = %d, want unchanged 30", cfg.RefreshSeconds())
	}
	data, _ := os.ReadFile(file)
	if !strings.Contains(string(data), `"refresh_seconds":30`) && !strings.Contains(string(data), `"refresh_seconds": 30`) {
		t.Errorf("file changed after invalid PUTs: %s", data)
	}
}

func TestPutConfigRefreshNoBoardFile(t *testing.T) {
	cfg := cfgFor(t)
	cfg.BoardFile = ""
	r := newServer(t, cfg, nil, nil, nil, nil)
	w := do(r, "PUT", "/api/config/refresh", map[string]any{"refresh_seconds": 45}, nil)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("code = %d, want 503: %s", w.Code, w.Body.String())
	}
}

// Docker auto-creates a missing bind-mount source as a directory at the
// host path, so the container can see a directory at the board path: the
// write cannot succeed, and the handler must report an honest 5xx storage
// error with a not-writable detail — not 400 bad_refresh_seconds, which
// would imply the user's input was invalid.
func TestPutConfigRefreshBoardFileNotWritable(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "dashhome.json")
	if err := os.Mkdir(file, 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := cfgFor(t)
	cfg.BoardFile = file
	cfg.Board.RefreshSeconds = 15
	r := newServer(t, cfg, nil, nil, nil, nil)

	w := do(r, "PUT", "/api/config/refresh", map[string]any{"refresh_seconds": 45}, nil)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("code = %d, want 503: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Error  string `json:"error"`
		Detail string `json:"detail"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp.Error != "board_file_not_writable" {
		t.Errorf("error = %q, want board_file_not_writable", resp.Error)
	}
	if !strings.Contains(resp.Detail, "not writable") {
		t.Errorf("detail = %q, want a not-writable explanation", resp.Detail)
	}
	if cfg.RefreshSeconds() != 15 {
		t.Errorf("in-memory RefreshSeconds = %d, want unchanged 15", cfg.RefreshSeconds())
	}
}

// --- calendars / events ------------------------------------------------------

var testMonthStart = testTime("2026-03-01T00:00:00Z")
var testMonthEnd = testTime("2026-03-31T23:59:59Z")

func eventCal(id, calID, summary string, start, end string, allDay bool) calendar.Event {
	return calendar.Event{
		ID: id, CalendarID: calID, Summary: summary,
		Start: testTime(start), End: testTime(end), AllDay: allDay,
	}
}

func TestListCalendarsNotConfigured(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "GET", "/api/calendars", nil, nil)
	if w.Code != 503 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp map[string]string
	json.NewDecoder(w.Body).Decode(&resp)
	if resp["error"] != "google_not_configured" {
		t.Errorf("error = %q", resp["error"])
	}
}

func TestListCalendarsLoginRequired(t *testing.T) {
	g := &fakeGoogle{hasToken: false, loginURL: "https://accounts.google.example/login"}
	r := newServer(t, cfgFor(t), g, nil, nil, nil)
	w := do(r, "GET", "/api/calendars", nil, nil)
	if w.Code != 503 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "accounts.google.example") {
		t.Errorf("body missing login URL: %s", w.Body.String())
	}
}

func TestListCalendarsFilterAndLabels(t *testing.T) {
	g := &fakeGoogle{
		hasToken: true,
		cals: []calendar.Calendar{
			{ID: "personal@example.com", Summary: "Personal"},
			{ID: "work@example.com", Summary: "Work"},
			{ID: "hidden@example.com", Summary: "Hidden"},
		},
	}
	cfg := cfgFor(t)
	r := newServer(t, cfg, g, nil, nil, nil)
	w := do(r, "GET", "/api/calendars", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Calendars []struct {
			ID    string `json:"id"`
			Label string `json:"label"`
		} `json:"calendars"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if len(resp.Calendars) != 1 {
		t.Fatalf("calendars = %+v, want only the configured one", resp.Calendars)
	}
	if resp.Calendars[0].ID != "personal@example.com" || resp.Calendars[0].Label != "Personal" {
		t.Errorf("calendar = %+v", resp.Calendars[0])
	}
}

// listResp decodes a GET /api/calendars body.
type listResp struct {
	Calendars []struct {
		ID      string `json:"id"`
		Summary string `json:"summary"`
		Label   string `json:"label"`
		Primary bool   `json:"primary"`
	} `json:"calendars"`
}

// The service-account case: the configured IDs are absent from Google's
// calendarList (empty list). They must still appear as tabs, in configured
// order, with their configured label, no summary, and primary=false.
func TestListCalendarsConfiguredIDsNotInGoogleList(t *testing.T) {
	g := &fakeGoogle{hasToken: true, cals: nil}
	cfg := cfgFor(t) // filter: personal@example.com (label "Personal")
	cfg.Board.Calendars = append(cfg.Board.Calendars,
		config.CalendarRef{ID: "captain@example.com", Label: "Captain's"})
	r := newServer(t, cfg, g, nil, nil, nil)
	w := do(r, "GET", "/api/calendars", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp listResp
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	want := []struct{ id, label, summary string; primary bool }{
		{"personal@example.com", "Personal", "", false},
		{"captain@example.com", "Captain's", "", false},
	}
	if len(resp.Calendars) != len(want) {
		t.Fatalf("calendars = %+v, want %d entries", resp.Calendars, len(want))
	}
	for i, w := range want {
		got := resp.Calendars[i]
		if got.ID != w.id || got.Label != w.label || got.Summary != w.summary || got.Primary != w.primary {
			t.Errorf("calendars[%d] = %+v, want %+v", i, got, w)
		}
	}
}

// Configured + visible merge: the calendarList entry enriches a configured
// ID with its summary and primary, the configured label wins, a visible
// but unconfigured calendar is still excluded, and a label-less configured
// ID not in the list falls back to the ID itself as label.
func TestListCalendarsConfiguredMergeWithGoogleList(t *testing.T) {
	g := &fakeGoogle{
		hasToken: true,
		cals: []calendar.Calendar{
			{ID: "personal@example.com", Summary: "Personal (Google)", Primary: true},
			{ID: "extra@example.com", Summary: "Extra"},
		},
	}
	cfg := cfgFor(t)
	cfg.Board.Calendars = []config.CalendarRef{
		{ID: "personal@example.com", Label: "Personal"},
		{ID: "ghost@example.com"}, // no label, not in the list either
	}
	r := newServer(t, cfg, g, nil, nil, nil)
	w := do(r, "GET", "/api/calendars", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp listResp
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	want := []struct{ id, label, summary string; primary bool }{
		{"personal@example.com", "Personal", "Personal (Google)", true},
		{"ghost@example.com", "ghost@example.com", "", false},
	}
	if len(resp.Calendars) != len(want) {
		t.Fatalf("calendars = %+v, want %d entries (extra@example.com not configured)", resp.Calendars, len(want))
	}
	for i, w := range want {
		got := resp.Calendars[i]
		if got.ID != w.id || got.Label != w.label || got.Summary != w.summary || got.Primary != w.primary {
			t.Errorf("calendars[%d] = %+v, want %+v", i, got, w)
		}
	}
}

// No configured array: exactly today's behavior — every visible calendar,
// in list order, label falling back to summary, then the ID.
func TestListCalendarsNoConfiguredArrayShowsAllVisible(t *testing.T) {
	g := &fakeGoogle{
		hasToken: true,
		cals: []calendar.Calendar{
			{ID: "a@example.com", Summary: "A", Primary: true},
			{ID: "b@example.com", Summary: "B"},
			{ID: "c@example.com"}, // no summary: label falls back to the ID
		},
	}
	cfg := cfgFor(t)
	cfg.Board.Calendars = nil // no filter
	r := newServer(t, cfg, g, nil, nil, nil)
	w := do(r, "GET", "/api/calendars", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp listResp
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	want := []struct{ id, label, summary string; primary bool }{
		{"a@example.com", "A", "A", true},
		{"b@example.com", "B", "B", false},
		{"c@example.com", "c@example.com", "", false},
	}
	if len(resp.Calendars) != len(want) {
		t.Fatalf("calendars = %+v, want all %d visible", resp.Calendars, len(want))
	}
	for i, w := range want {
		got := resp.Calendars[i]
		if got.ID != w.id || got.Label != w.label || got.Summary != w.summary || got.Primary != w.primary {
			t.Errorf("calendars[%d] = %+v, want %+v", i, got, w)
		}
	}
}

// A duplicated calendar ID in the board file must yield one tab and one
// event fetch, in first-occurrence order (deduped at CalendarFilter, the
// shared boundary of both consumers).
func TestDuplicateConfiguredCalendarIDDeduped(t *testing.T) {
	g := &fakeGoogle{
		hasToken: true,
		events: map[string][]calendar.Event{
			"dup@example.com": {
				eventCal("e1", "dup@example.com", "Once", "2026-03-10T00:00:00Z", "2026-03-10T01:00:00Z", false),
			},
		},
	}
	cfg := cfgFor(t)
	cfg.Board.Calendars = []config.CalendarRef{
		{ID: "a@example.com", Label: "A"},
		{ID: "dup@example.com", Label: "Dup"},
		{ID: "a@example.com"},
		{ID: "dup@example.com"},
	}
	r := newServer(t, cfg, g, nil, nil, nil)

	w := do(r, "GET", "/api/calendars", nil, nil)
	if w.Code != 200 {
		t.Fatalf("calendars code = %d: %s", w.Code, w.Body.String())
	}
	var calResp listResp
	if err := json.NewDecoder(w.Body).Decode(&calResp); err != nil {
		t.Fatal(err)
	}
	if len(calResp.Calendars) != 2 {
		t.Fatalf("calendars = %+v, want 2 (deduped)", calResp.Calendars)
	}
	if calResp.Calendars[0].ID != "a@example.com" || calResp.Calendars[1].ID != "dup@example.com" {
		t.Errorf("order = %+v, want first-occurrence order", calResp.Calendars)
	}
	if calResp.Calendars[0].Label != "A" || calResp.Calendars[1].Label != "Dup" {
		t.Errorf("labels = %+v", calResp.Calendars)
	}

	w = do(r, "GET", "/api/events?start=2026-03-01T00:00:00Z&end=2026-03-31T23:59:59Z", nil, nil)
	if w.Code != 200 {
		t.Fatalf("events code = %d: %s", w.Code, w.Body.String())
	}
	var evResp struct {
		Events []struct {
			ID string `json:"id"`
		} `json:"events"`
	}
	if err := json.NewDecoder(w.Body).Decode(&evResp); err != nil {
		t.Fatal(err)
	}
	if len(evResp.Events) != 1 || evResp.Events[0].ID != "e1" {
		t.Fatalf("events = %+v, want e1 exactly once (fetch deduped)", evResp.Events)
	}
}

func TestListEventsSummaryCombined(t *testing.T) {
	g := &fakeGoogle{
		hasToken: true,
		cals: []calendar.Calendar{
			{ID: "personal@example.com", Summary: "Personal"},
			{ID: "work@example.com", Summary: "Work"},
		},
		events: map[string][]calendar.Event{
			"personal@example.com": {
				eventCal("p1", "personal@example.com", "Birthday", "2026-03-10T00:00:00Z", "2026-03-11T00:00:00Z", true),
				eventCal("p2", "personal@example.com", "Gym", "2026-03-05T06:00:00Z", "2026-03-05T07:00:00Z", false),
			},
			"work@example.com": {
				eventCal("w1", "work@example.com", "Standup", "2026-03-02T09:00:00Z", "2026-03-02T09:15:00Z", false),
			},
		},
	}
	cfg := cfgFor(t)
	cfg.Board.Calendars = []config.CalendarRef{} // no filter: all calendars
	r := newServer(t, cfg, g, nil, nil, nil)

	w := do(r, "GET", "/api/events?start=2026-03-01T00:00:00Z&end=2026-03-31T23:59:59Z", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Events []struct {
			ID          string `json:"id"`
			CalendarID  string `json:"calendar_id"`
			CalendarLbl string `json:"calendar_label"`
			Start       string `json:"start"`
		} `json:"events"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if len(resp.Events) != 3 {
		t.Fatalf("events = %+v, want 3", resp.Events)
	}
	// Sorted by start time.
	wantOrder := []string{"w1", "p2", "p1"}
	for i, id := range wantOrder {
		if resp.Events[i].ID != id {
			t.Errorf("order[%d] = %s, want %s", i, resp.Events[i].ID, id)
		}
	}
	if resp.Events[0].CalendarLbl != "Work" || resp.Events[1].CalendarLbl != "Personal" {
		t.Errorf("labels = %q, %q", resp.Events[0].CalendarLbl, resp.Events[1].CalendarLbl)
	}
	// Date-only inputs must be accepted and mapped onto a sane range.
	g2 := &fakeGoogle{hasToken: true, cals: g.cals, events: g.events}
	r2 := newServer(t, cfg, g2, nil, nil, nil)
	w2 := do(r2, "GET", "/api/events?start=2026-03-01&end=2026-03-31", nil, nil)
	if w2.Code != 200 {
		t.Fatalf("date-only range: code = %d: %s", w2.Code, w2.Body.String())
	}
	if g2.lastStart.Year() != 2026 || g2.lastStart.Month() != 3 || g2.lastStart.Day() != 1 {
		t.Errorf("start = %v", g2.lastStart)
	}
	if !g2.lastEnd.After(g2.lastStart) {
		t.Errorf("end = %v not after start", g2.lastEnd)
	}
}

func TestListEventsSingleCalendar(t *testing.T) {
	g := &fakeGoogle{
		hasToken: true,
		events: map[string][]calendar.Event{
			"work@example.com": {
				eventCal("w1", "work@example.com", "Standup", "2026-03-02T09:00:00Z", "2026-03-02T09:15:00Z", false),
			},
		},
	}
	r := newServer(t, cfgFor(t), g, nil, nil, nil)
	w := do(r, "GET", "/api/events?calendar=work%40example.com&start=2026-03-01T00:00:00Z&end=2026-03-31T23:59:59Z", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Events []struct {
			ID    string `json:"id"`
			CalID string `json:"calendar_id"`
		} `json:"events"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if len(resp.Events) != 1 || resp.Events[0].ID != "w1" || resp.Events[0].CalID != "work@example.com" {
		t.Errorf("events = %+v", resp.Events)
	}
}

func TestListEventsNotConfigured(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "GET", "/api/events", nil, nil)
	if w.Code != 503 {
		t.Fatalf("code = %d", w.Code)
	}
}

func TestListEventsBadRange(t *testing.T) {
	g := &fakeGoogle{hasToken: true}
	r := newServer(t, cfgFor(t), g, nil, nil, nil)
	w := do(r, "GET", "/api/events?start=garbage", nil, nil)
	if w.Code != 400 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

func TestListEventsAllFail(t *testing.T) {
	g := &fakeGoogle{hasToken: true, err: context.DeadlineExceeded}
	r := newServer(t, cfgFor(t), g, nil, nil, nil)
	w := do(r, "GET", "/api/events", nil, nil)
	if w.Code != 502 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

// --- Home Assistant ------------------------------------------------------------

func TestHAEntitiesAll(t *testing.T) {
	states := []ha.EntityState{
		{EntityID: "climate.thermo", State: "21.5", Attributes: map[string]any{"friendly_name": "Thermostat", "unit_of_measurement": "°C", "hvac_modes": []any{"heat", "cool"}}},
		{EntityID: "binary_sensor.door", State: "off", Attributes: map[string]any{"friendly_name": "Front door"}},
	}
	cfg := cfgFor(t)
	cfg.Board.HAEntityIDs = nil // no filter
	r := newServer(t, cfg, nil, &fakeHA{states: states}, nil, nil)
	w := do(r, "GET", "/api/ha/entities", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Entities []struct {
			EntityID   string         `json:"entity_id"`
			Label      string         `json:"label"`
			State      string         `json:"state"`
			Attributes map[string]any `json:"attributes"`
		} `json:"entities"`
		Total int `json:"total"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp.Total != 2 {
		t.Fatalf("total = %d", resp.Total)
	}
	if resp.Entities[0].Label != "Living heat" {
		t.Errorf("label = %q, want board label", resp.Entities[0].Label)
	}
	if resp.Entities[1].Label != "Front door" {
		t.Errorf("label = %q, want friendly name fallback", resp.Entities[1].Label)
	}
	if got := resp.Entities[0].Attributes["unit_of_measurement"]; got != "°C" {
		t.Errorf("unit_of_measurement = %v (%T)", got, got)
	}
	if got, ok := resp.Entities[0].Attributes["hvac_modes"].([]any); !ok || len(got) != 2 || got[0] != "heat" {
		t.Errorf("hvac_modes = %v (%T)", resp.Entities[0].Attributes["hvac_modes"], resp.Entities[0].Attributes["hvac_modes"])
	}
}

func TestHAEntitiesFiltered(t *testing.T) {
	states := []ha.EntityState{
		{EntityID: "climate.thermo", State: "21.5", Attributes: map[string]any{"friendly_name": "Thermostat"}},
		{EntityID: "binary_sensor.door", State: "off"},
		{EntityID: "sensor.ignored", State: "x"},
	}
	cfg := cfgFor(t)
	cfg.Board.HAEntityIDs = []string{"binary_sensor.door", "climate.thermo", "sensor.missing"}
	r := newServer(t, cfg, nil, &fakeHA{states: states}, nil, nil)
	w := do(r, "GET", "/api/ha/entities", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Entities []struct {
			EntityID string `json:"entity_id"`
			State    string `json:"state"`
		} `json:"entities"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if len(resp.Entities) != 3 {
		t.Fatalf("entities = %+v", resp.Entities)
	}
	if resp.Entities[0].EntityID != "binary_sensor.door" || resp.Entities[1].EntityID != "climate.thermo" {
		t.Errorf("order = %v, %v", resp.Entities[0].EntityID, resp.Entities[1].EntityID)
	}
	if resp.Entities[2].EntityID != "sensor.missing" || resp.Entities[2].State != "unavailable" {
		t.Errorf("missing entity = %+v", resp.Entities[2])
	}
}

func TestHANotConfigured(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "GET", "/api/ha/entities", nil, nil)
	if w.Code != 503 {
		t.Fatalf("code = %d", w.Code)
	}
	// The new HA endpoints share the same not-configured contract.
	for _, path := range []string{"/api/ha/embed-url", "/api/ha/dashboards", "/api/ha/dashboard", "/api/ha/dashboard/"} {
		w := do(r, "GET", path, nil, nil)
		if w.Code != 503 {
			t.Fatalf("%s: code = %d, want 503: %s", path, w.Code, w.Body.String())
		}
		var resp map[string]string
		json.NewDecoder(w.Body).Decode(&resp)
		if resp["error"] != "homeassistant_not_configured" {
			t.Errorf("%s: error = %q", path, resp["error"])
		}
	}
}

// --- Home Assistant embed URL --------------------------------------------------

func haCfg(t *testing.T) *config.Config {
	cfg := cfgFor(t)
	cfg.HAURL, cfg.HAToken = "https://ha.local", "super-secret-ha-token"
	return cfg
}

func TestHAEmbedURLDefault(t *testing.T) {
	r := newServer(t, haCfg(t), nil, &fakeHA{}, nil, nil)
	w := do(r, "GET", "/api/ha/embed-url", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		URL string `json:"url"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp.URL != "https://ha.local/" {
		t.Errorf("url = %q, want default dashboard", resp.URL)
	}
}

func TestHAEmbedURLPathAndToken(t *testing.T) {
	cfg := haCfg(t)
	cfg.Board.HAEmbed = &config.HAEmbed{URLPath: spStr("/energy/"), TokenURL: true}
	r := newServer(t, cfg, nil, &fakeHA{}, nil, nil)
	w := do(r, "GET", "/api/ha/embed-url", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		URL string `json:"url"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp.URL != "https://ha.local/energy?auth_long_lived=super-secret-ha-token" {
		t.Errorf("url = %q", resp.URL)
	}
}

func TestHAEmbedURLNoTokenByDefault(t *testing.T) {
	cfg := haCfg(t)
	cfg.Board.HAEmbed = &config.HAEmbed{URLPath: spStr("energy")} // token_url unset = false
	r := newServer(t, cfg, nil, &fakeHA{}, nil, nil)
	w := do(r, "GET", "/api/ha/embed-url", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if strings.Contains(body, "super-secret-ha-token") {
		t.Errorf("token leaked with token_url=false: %s", body)
	}
	if !strings.Contains(body, "https://ha.local/energy") {
		t.Errorf("url = %s", body)
	}
}

func TestHAEmbedURLNullPath(t *testing.T) {
	cfg := haCfg(t)
	cfg.Board.HAEmbed = &config.HAEmbed{URLPath: nil, TokenURL: true} // explicit null = default dashboard
	r := newServer(t, cfg, nil, &fakeHA{}, nil, nil)
	w := do(r, "GET", "/api/ha/embed-url", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		URL string `json:"url"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp.URL != "https://ha.local/?auth_long_lived=super-secret-ha-token" {
		t.Errorf("url = %q", resp.URL)
	}
}

// --- Home Assistant dashboards --------------------------------------------------

func TestHADashboards(t *testing.T) {
	d := &fakeDash{list: []ha.Dashboard{
		{ID: "default", Title: "My Home", Mode: "storage"},
		{ID: "energy", URLPath: spStr("energy"), Title: "Energy", RequireAdmin: true, Mode: "yaml"},
	}}
	r := newServer(t, haCfg(t), nil, &fakeHA{}, nil, d)
	w := do(r, "GET", "/api/ha/dashboards", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Dashboards []ha.Dashboard `json:"dashboards"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if len(resp.Dashboards) != 2 || resp.Dashboards[1].ID != "energy" || !resp.Dashboards[1].RequireAdmin {
		t.Errorf("dashboards = %+v", resp.Dashboards)
	}
	if got := d.calls[len(d.calls)-1]; got != "list:force=false" {
		t.Errorf("upstream call = %q", got)
	}

	// ?reload=1 forces a re-fetch upstream.
	w = do(r, "GET", "/api/ha/dashboards?reload=1", nil, nil)
	if w.Code != 200 {
		t.Fatalf("reload code = %d: %s", w.Code, w.Body.String())
	}
	if got := d.calls[len(d.calls)-1]; got != "list:force=true" {
		t.Errorf("upstream call = %q", got)
	}
}

func TestHADashboardsUpstreamError(t *testing.T) {
	d := &fakeDash{listErr: context.DeadlineExceeded}
	r := newServer(t, haCfg(t), nil, &fakeHA{}, nil, d)
	w := do(r, "GET", "/api/ha/dashboards", nil, nil)
	if w.Code != 502 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp map[string]string
	json.NewDecoder(w.Body).Decode(&resp)
	if resp["error"] != "homeassistant_unavailable" {
		t.Errorf("error = %q", resp["error"])
	}
}

func TestHADashboardConfigPassthrough(t *testing.T) {
	raw := json.RawMessage(`{"views":[{"title":"Overview","cards":[{"type":"entities"}]}]}`)
	d := &fakeDash{configRaw: raw}
	r := newServer(t, haCfg(t), nil, &fakeHA{}, nil, d)

	w := do(r, "GET", "/api/ha/dashboard/energy", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	if got := w.Body.String(); got != string(raw) {
		t.Errorf("body = %s, want raw pass-through", got)
	}
	if got := d.calls[len(d.calls)-1]; got != `config:"energy":force=false` {
		t.Errorf("upstream call = %q", got)
	}

	// Default dashboard: exact-match route without a trailing slash.
	w = do(r, "GET", "/api/ha/dashboard", nil, nil)
	if w.Code != 200 {
		t.Fatalf("no-slash default: code = %d: %s", w.Code, w.Body.String())
	}
	if got := d.calls[len(d.calls)-1]; got != `config:"":force=false` {
		t.Errorf("upstream call = %q", got)
	}

	// Default dashboard: empty wildcard path segment.
	w = do(r, "GET", "/api/ha/dashboard/", nil, nil)
	if w.Code != 200 {
		t.Fatalf("default code = %d: %s", w.Code, w.Body.String())
	}
	if got := d.calls[len(d.calls)-1]; got != `config:"":force=false` {
		t.Errorf("upstream call = %q", got)
	}

	// url_path is normalized on the way up.
	w = do(r, "GET", "/api/ha/dashboard//energy/", nil, nil)
	if w.Code != 200 {
		t.Fatalf("normalized code = %d: %s", w.Code, w.Body.String())
	}
	if got := d.calls[len(d.calls)-1]; got != `config:"energy":force=false` {
		t.Errorf("upstream call = %q", got)
	}

	// ?reload=1 forces a re-fetch upstream.
	w = do(r, "GET", "/api/ha/dashboard/energy?reload=1", nil, nil)
	if w.Code != 200 {
		t.Fatalf("reload code = %d: %s", w.Code, w.Body.String())
	}
	if got := d.calls[len(d.calls)-1]; got != `config:"energy":force=true` {
		t.Errorf("upstream call = %q", got)
	}
}

func TestHADashboardConfigUpstreamError(t *testing.T) {
	d := &fakeDash{configErr: errors.New("home assistant lovelace/config failed: No dashboard found")}
	r := newServer(t, haCfg(t), nil, &fakeHA{}, nil, d)
	w := do(r, "GET", "/api/ha/dashboard/nope", nil, nil)
	if w.Code != 502 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "No dashboard found") {
		t.Errorf("detail = %s", w.Body.String())
	}
}

func spStr(s string) *string { return &s }

// --- Hermes chat ------------------------------------------------------------------

func TestHermesChat(t *testing.T) {
	h := &fakeHermes{reply: "hi from hermes"}
	cfg := cfgFor(t)
	cfg.HermesBaseURL = "https://hermes.local"
	r := newServer(t, cfg, nil, nil, h, nil)
	w := do(r, "POST", "/api/hermes/chat", map[string]any{
		"message":      "hello",
		"conversation": []map[string]string{{"role": "user", "content": "earlier"}},
	}, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Reply string `json:"reply"`
	}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp.Reply != "hi from hermes" {
		t.Errorf("reply = %q", resp.Reply)
	}
	if h.lastReq.Message != "hello" || len(h.lastReq.Conversation) != 1 {
		t.Errorf("forwarded = %+v", h.lastReq)
	}
}

func TestHermesChatNotConfigured(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "POST", "/api/hermes/chat", map[string]string{"message": "hi"}, nil)
	if w.Code != 503 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

func TestHermesChatEmptyMessage(t *testing.T) {
	h := &fakeHermes{reply: "x"}
	r := newServer(t, cfgFor(t), nil, nil, h, nil)
	w := do(r, "POST", "/api/hermes/chat", map[string]string{"message": "  "}, nil)
	if w.Code != 400 {
		t.Fatalf("code = %d", w.Code)
	}
}

func TestHermesChatUpstreamError(t *testing.T) {
	h := &fakeHermes{err: context.DeadlineExceeded}
	cfg := cfgFor(t)
	cfg.HermesBaseURL = "https://hermes.local"
	r := newServer(t, cfg, nil, nil, h, nil)
	w := do(r, "POST", "/api/hermes/chat", map[string]string{"message": "hi"}, nil)
	if w.Code != 502 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

// --- Hermes pushes -------------------------------------------------------------------

func pushJSON(markdown, source string) map[string]string {
	m := map[string]string{"markdown": markdown}
	if source != "" {
		m["source"] = source
	}
	return m
}

func TestPushNotConfigured(t *testing.T) {
	cfg := cfgFor(t)
	cfg.PushToken = ""
	r := newServer(t, cfg, nil, nil, nil, nil)
	w := do(r, "POST", "/api/hermes/push", pushJSON("# hi", ""), map[string]string{"Authorization": "Bearer whatever"})
	if w.Code != 503 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

func TestPushUnauthorized(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "POST", "/api/hermes/push", pushJSON("# hi", ""), map[string]string{"Authorization": "Bearer wrong"})
	if w.Code != 401 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

func TestPushAuthStyles(t *testing.T) {
	modes := []map[string]string{
		{"Authorization": "Bearer push-tok"},
		{"X-Hermes-Token": "push-tok"},
		{"X-Token-Query": ""}, // use ?token=
	}
	for i, hdr := range modes {
		r := newServer(t, cfgFor(t), nil, nil, nil, nil)
		var w *httptest.ResponseRecorder
		if i == 2 {
			w = do(r, "POST", "/api/hermes/push?token=push-tok", pushJSON("# via query", ""), map[string]string{"Content-Type": "application/json"})
		} else {
			w = do(r, "POST", "/api/hermes/push", pushJSON("# via header", ""), hdr)
		}
		if w.Code != 201 {
			t.Fatalf("mode %d: code = %d: %s", i, w.Code, w.Body.String())
		}
	}
}

func TestPushEmptyMarkdown(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "POST", "/api/hermes/push", pushJSON("", ""), map[string]string{"Authorization": "Bearer push-tok"})
	if w.Code != 400 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

func TestPushLifecycle(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	hdr := map[string]string{"Authorization": "Bearer push-tok"}

	w1 := do(r, "POST", "/api/hermes/push", pushJSON("# one", "hermes"), hdr)
	if w1.Code != 201 {
		t.Fatalf("push1 code = %d: %s", w1.Code, w1.Body.String())
	}
	var p1 struct {
		Push struct {
			ID         string `json:"id"`
			ReceivedAt string `json:"received_at"`
			Markdown   string `json:"markdown"`
			Source     string `json:"source"`
		} `json:"push"`
	}
	json.NewDecoder(w1.Body).Decode(&p1)
	if p1.Push.ID == "" || p1.Push.Markdown != "# one" || p1.Push.Source != "hermes" {
		t.Errorf("push1 = %+v", p1.Push)
	}

	time.Sleep(2 * time.Millisecond)
	w2 := do(r, "POST", "/api/hermes/push", pushJSON("# two", ""), hdr)
	if w2.Code != 201 {
		t.Fatalf("push2 code = %d", w2.Code)
	}
	var p2 struct {
		Push struct {
			ID string `json:"id"`
		} `json:"push"`
	}
	json.NewDecoder(w2.Body).Decode(&p2)

	// Latest.
	wl := do(r, "GET", "/api/hermes/pushes/latest", nil, nil)
	if wl.Code != 200 {
		t.Fatalf("latest code = %d", wl.Code)
	}
	var latest struct {
		Push struct {
			ID string `json:"id"`
		} `json:"push"`
	}
	json.NewDecoder(wl.Body).Decode(&latest)
	if latest.Push.ID != p2.Push.ID {
		t.Errorf("latest = %s, want %s", latest.Push.ID, p2.Push.ID)
	}

	// List newest first.
	ws := do(r, "GET", "/api/hermes/pushes?limit=10", nil, nil)
	if ws.Code != 200 {
		t.Fatalf("list code = %d", ws.Code)
	}
	var list struct {
		Pushes []struct {
			ID string `json:"id"`
		} `json:"pushes"`
	}
	json.NewDecoder(ws.Body).Decode(&list)
	if len(list.Pushes) != 2 || list.Pushes[0].ID != p2.Push.ID || list.Pushes[1].ID != p1.Push.ID {
		t.Errorf("list = %+v", list.Pushes)
	}

	// Get by ID.
	wh := do(r, "GET", "/api/hermes/pushes/"+p1.Push.ID, nil, nil)
	if wh.Code != 200 {
		t.Fatalf("get code = %d", wh.Code)
	}
	var got struct {
		Push struct {
			Markdown string `json:"markdown"`
		} `json:"push"`
	}
	json.NewDecoder(wh.Body).Decode(&got)
	if got.Push.Markdown != "# one" {
		t.Errorf("markdown = %q", got.Push.Markdown)
	}

	// Unknown ID.
	wn := do(r, "GET", "/api/hermes/pushes/nope-123", nil, nil)
	if wn.Code != 404 {
		t.Fatalf("unknown code = %d", wn.Code)
	}
}

func TestPushLatestEmpty(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "GET", "/api/hermes/pushes/latest", nil, nil)
	if w.Code != 404 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

func TestPushListEmpty(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "GET", "/api/hermes/pushes", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d", w.Code)
	}
	if !strings.Contains(w.Body.String(), `"pushes":[]`) {
		t.Errorf("body = %s", w.Body.String())
	}
}

// --- Google OAuth endpoints ---------------------------------------------------------

func TestGoogleCallbackFlow(t *testing.T) {
	tokenSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"access_token":"fresh","refresh_token":"rr","token_type":"Bearer","expires_in":3599}`))
			return
		}
		http.NotFound(w, r)
	}))
	defer tokenSrv.Close()

	restore := calendar.SetOAuthEndpointForTest(oauth2.Endpoint{
		AuthURL:  tokenSrv.URL + "/auth",
		TokenURL: tokenSrv.URL + "/token",
	})
	t.Cleanup(restore)

	dir := t.TempDir()
	cache := filepath.Join(dir, "tok.json")
	g, err := newTestGoogleOAuth(cache)
	if err != nil {
		t.Fatalf("newTestGoogleOAuth: %v", err)
	}
	r := newServer(t, cfgFor(t), g, nil, nil, nil)

	// Missing code.
	w := do(r, "GET", "/api/auth/google/callback", nil, nil)
	if w.Code != 400 {
		t.Fatalf("missing code: %d: %s", w.Code, w.Body.String())
	}

	// Successful exchange redirects home and stores the token.
	w = do(r, "GET", "/api/auth/google/callback?code=abc123", nil, nil)
	if w.Code != 302 {
		t.Fatalf("callback: %d: %s", w.Code, w.Body.String())
	}
	if loc := w.Header().Get("Location"); loc != "/?google_auth=ok" {
		t.Errorf("Location = %q", loc)
	}
	if _, err := os.Stat(cache); err != nil {
		t.Errorf("token cache not written: %v", err)
	}
}

func TestGoogleLoginEndpointUnconfigured(t *testing.T) {
	r := newServer(t, cfgFor(t), nil, nil, nil, nil)
	w := do(r, "GET", "/api/auth/google/login", nil, nil)
	if w.Code != 404 {
		t.Fatalf("code = %d (route should not exist without google): %s", w.Code, w.Body.String())
	}
}

func TestGoogleLoginAlreadyAuthorized(t *testing.T) {
	g := &fakeGoogle{hasToken: true}
	r := newServer(t, cfgFor(t), g, nil, nil, nil)
	w := do(r, "GET", "/api/auth/google/login", nil, nil)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "already_authorized") {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
}

// --- static frontend serving ----------------------------------------------------------

func TestStaticServing(t *testing.T) {
	base := t.TempDir()
	dist := filepath.Join(base, "dist")
	if err := os.MkdirAll(filepath.Join(dist, "assets"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dist, "index.html"), []byte("<html>idx</html>"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dist, "assets", "app.js"), []byte("console.log(1)"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(base, "secret.json"), []byte("top-secret-token"), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg := cfgFor(t)
	r := NewServer(cfg, nil, nil, nil, nil, mustStore(t)).Engine()
	ServeStatic(r, dist)

	// API still works.
	w := do(r, "GET", "/healthz", nil, nil)
	if w.Code != 200 {
		t.Fatalf("healthz: %d", w.Code)
	}
	// Asset served.
	w = do(r, "GET", "/assets/app.js", nil, nil)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "console.log(1)") {
		t.Fatalf("asset: %d %s", w.Code, w.Body.String())
	}
	// SPA fallback.
	w = do(r, "GET", "/some/spa/route", nil, nil)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "idx") {
		t.Fatalf("spa fallback: %d %s", w.Code, w.Body.String())
	}
	// Unknown API path.
	w = do(r, "GET", "/api/nope", nil, nil)
	if w.Code != 404 {
		t.Fatalf("api 404: %d", w.Code)
	}
	// Escaping paths must not serve files outside dist.
	for _, p := range []string{"/../secret.json", "/../../secret.json", "/assets/../../secret.json", "/.."} {
		w = do(r, "GET", p, nil, nil)
		if strings.Contains(w.Body.String(), "top-secret-token") {
			t.Errorf("%s: served file outside dist: %s", p, w.Body.String())
		}
		if w.Code == http.StatusOK && !strings.Contains(w.Body.String(), "idx") {
			t.Errorf("%s: served %d for escaping path: %s", p, w.Code, w.Body.String())
		}
	}
}

func TestStaticMissing(t *testing.T) {
	r := NewServer(cfgFor(t), nil, nil, nil, nil, mustStore(t)).Engine()
	ServeStatic(r, filepath.Join(t.TempDir(), "nope"))
	w := do(r, "GET", "/", nil, nil)
	if w.Code != 404 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "make web") {
		t.Errorf("hint missing: %s", w.Body.String())
	}
}

func mustStore(t *testing.T) *store.FileStore {
	t.Helper()
	s, err := store.NewFileStore(filepath.Join(t.TempDir(), "pushes"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}
