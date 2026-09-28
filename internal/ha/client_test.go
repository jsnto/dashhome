package ha

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func fakeHA(t *testing.T, statesBody string, wantAuth string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/states" {
			t.Errorf("path = %q, want /api/states", r.URL.Path)
			http.NotFound(w, r)
			return
		}
		if got := r.Header.Get("Authorization"); got != wantAuth {
			t.Errorf("Authorization = %q, want %q", got, wantAuth)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(statesBody))
	}))
}

func TestGetStates(t *testing.T) {
	srv := fakeHA(t, `[
		{"entity_id":"climate.thermo","state":"21.5","attributes":{"friendly_name":"Thermostat","unit_of_measurement":"°C"},"last_updated":"2026-01-02T03:04:05Z","last_changed":"2026-01-02T03:00:00Z"},
		{"entity_id":"binary_sensor.front_door","state":"off","attributes":{"friendly_name":"Front door"}}
	]`, "Bearer ha-tok")
	defer srv.Close()

	c := NewClient(srv.URL, "ha-tok")
	states, err := c.GetStates(context.Background())
	if err != nil {
		t.Fatalf("GetStates: %v", err)
	}
	if len(states) != 2 {
		t.Fatalf("len = %d, want 2", len(states))
	}
	if states[0].State != "21.5" || states[0].FriendlyName() != "Thermostat" {
		t.Errorf("state 0 = %+v", states[0])
	}
	if states[0].Attributes["unit_of_measurement"] != "°C" {
		t.Errorf("attrs = %v", states[0].Attributes)
	}
	if states[1].FriendlyName() != "Front door" {
		t.Errorf("state 1 = %+v", states[1])
	}
}

func TestGetStatesAuthError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "wrong")
	if _, err := c.GetStates(context.Background()); err == nil {
		t.Fatal("expected auth error")
	}
}

func TestGetStatesServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("boom"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	if _, err := c.GetStates(context.Background()); err == nil {
		t.Fatal("expected server error")
	}
}
