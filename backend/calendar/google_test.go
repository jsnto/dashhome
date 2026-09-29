package calendar

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeGoogle serves the two Calendar API endpoints dashhome uses.
func fakeGoogle(t *testing.T, evQuery *url.Values) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/users/me/calendarList":
			json.NewEncoder(w).Encode(map[string]any{
				"items": []map[string]any{
					{"id": "primary@gmail.com", "summary": "Personal", "primary": true},
					{"id": "work@example.com", "summary": "Work"},
				},
			})
		case r.URL.Path == "/calendars/primary@gmail.com/events":
			*evQuery = r.URL.Query()
			json.NewEncoder(w).Encode(map[string]any{
				"items": []map[string]any{
					{
						"id": "e1", "summary": "Birthday",
						"start": map[string]string{"date": "2026-03-01"},
						"end":   map[string]string{"date": "2026-03-02"},
					},
					{
						"id": "e2", "summary": "Standup", "location": "room 4",
						"start": map[string]string{"dateTime": "2026-03-02T09:00:00Z"},
						"end":   map[string]string{"dateTime": "2026-03-02T09:30:00Z"},
					},
				},
			})
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
			http.NotFound(w, r)
		}
	}))
}

func TestNewGoogleUnconfigured(t *testing.T) {
	c, err := NewGoogle(context.Background(), GoogleSettings{})
	if err != nil {
		t.Fatalf("NewGoogle: %v", err)
	}
	if c != nil {
		t.Fatalf("NewGoogle with no settings = %v, want nil", c)
	}
}

func TestNewGoogleBadServiceAccountFile(t *testing.T) {
	_, err := NewGoogle(context.Background(), GoogleSettings{ServiceAccountFile: "/nonexistent/sa.json"})
	if err == nil {
		t.Fatal("expected error for missing SA file")
	}
}

func TestNewGoogleOAuthLoginState(t *testing.T) {
	cache := filepath.Join(t.TempDir(), "tok.json")
	c, err := NewGoogle(context.Background(), GoogleSettings{
		ClientID: "id", ClientSecret: "secret",
		RedirectURI: "https://example.com/cb", TokenCache: cache,
	})
	if err != nil {
		t.Fatalf("NewGoogle: %v", err)
	}
	if c.Mode() != ModeOAuth {
		t.Errorf("mode = %s", c.Mode())
	}
	if c.HasToken() {
		t.Error("HasToken should be false before login")
	}
	if u := c.LoginURL(); u == "" {
		t.Error("LoginURL should be set before login")
	}
	if u := c.LoginURL(); u == "" || !strings.Contains(u, "client_id=id") {
		t.Errorf("LoginURL = %q", u)
	}

	// After a token is stored, login is no longer required.
	if err := os.WriteFile(cache, []byte(`{"access_token":"a","refresh_token":"r"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	c2, err := NewGoogle(context.Background(), GoogleSettings{
		ClientID: "id", ClientSecret: "secret",
		RedirectURI: "https://example.com/cb", TokenCache: cache,
	})
	if err != nil {
		t.Fatalf("NewGoogle with token: %v", err)
	}
	if !c2.HasToken() {
		t.Error("HasToken should be true with stored token")
	}
	if u := c2.LoginURL(); u != "" {
		t.Errorf("LoginURL = %q, want empty after login", u)
	}
}

func TestNewGoogleOAuthMissingRedirect(t *testing.T) {
	if _, err := NewGoogle(context.Background(), GoogleSettings{ClientID: "id", ClientSecret: "s"}); err == nil {
		t.Fatal("expected error for missing redirect URI")
	}
}

func TestGoogleListCalendarsAndEvents(t *testing.T) {
	var evQuery url.Values
	srv := fakeGoogle(t, &evQuery)
	defer srv.Close()

	c, err := NewGoogle(context.Background(), GoogleSettings{AccessToken: "fake-tok", Endpoint: srv.URL})
	if err != nil {
		t.Fatalf("NewGoogle: %v", err)
	}
	if c.Mode() != ModeStatic || !c.HasToken() {
		t.Errorf("mode/token = %s/%v", c.Mode(), c.HasToken())
	}

	ctx := context.Background()
	cals, err := c.ListCalendars(ctx)
	if err != nil {
		t.Fatalf("ListCalendars: %v", err)
	}
	if len(cals) != 2 || cals[0].ID != "primary@gmail.com" || !cals[0].Primary || cals[0].Summary != "Personal" {
		t.Errorf("cals = %+v", cals)
	}

	start := time.Date(2026, 3, 1, 0, 0, 0, 0, time.Local)
	end := time.Date(2026, 3, 31, 23, 59, 59, 0, time.Local)
	evs, err := c.ListEvents(ctx, "primary@gmail.com", start, end)
	if err != nil {
		t.Fatalf("ListEvents: %v", err)
	}
	if len(evs) != 2 {
		t.Fatalf("events = %d, want 2: %+v", len(evs), evs)
	}

	// Range parameters must be forwarded to the API as RFC3339 UTC.
	wantMin := start.UTC().Format(time.RFC3339)
	wantMax := end.UTC().Format(time.RFC3339)
	if got := evQuery.Get("timeMin"); got != wantMin {
		t.Errorf("timeMin = %q, want %q", got, wantMin)
	}
	if got := evQuery.Get("timeMax"); got != wantMax {
		t.Errorf("timeMax = %q, want %q", got, wantMax)
	}
	if got := evQuery.Get("singleEvents"); got != "true" {
		t.Errorf("singleEvents = %q", got)
	}

	byID := map[string]Event{}
	for _, e := range evs {
		byID[e.ID] = e
	}
	allDay := byID["e1"]
	if !allDay.AllDay {
		t.Error("e1 should be all-day")
	}
	if got := allDay.Start; got.Year() != 2026 || got.Month() != 3 || got.Day() != 1 {
		t.Errorf("e1 start = %v", got)
	}
	timed := byID["e2"]
	if timed.AllDay {
		t.Error("e2 should not be all-day")
	}
	if got := timed.Start; got.UTC() != time.Date(2026, 3, 2, 9, 0, 0, 0, time.UTC) {
		t.Errorf("e2 start = %v", got)
	}
	if timed.Location != "room 4" || timed.CalendarID != "primary@gmail.com" {
		t.Errorf("e2 = %+v", timed)
	}
}

func TestGoogleAPIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		fmt.Fprint(w, `{"error":"invalid token"}`)
	}))
	defer srv.Close()

	c, _ := NewGoogle(context.Background(), GoogleSettings{AccessToken: "bad", Endpoint: srv.URL})
	if _, err := c.ListCalendars(context.Background()); err == nil {
		t.Error("expected API error")
	}
}
