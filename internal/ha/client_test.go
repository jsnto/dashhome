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

func TestGetStatesNativeAttributeTypes(t *testing.T) {
	srv := fakeHA(t, `[
		{"entity_id":"zone.home","state":"zoning","attributes":{"friendly_name":"Home","latitude":47.6,"longitude":-122.3},"last_updated":"2026-01-02T03:04:05Z"},
		{"entity_id":"climate.thermostat","state":"heat","attributes":{"friendly_name":"Thermostat","hvac_modes":["heat","cool","off"],"current_temperature":21.5,"locked":true},"last_updated":"2026-01-02T03:04:05Z"}
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
	if got := states[0].Attributes["latitude"]; got != 47.6 {
		t.Errorf("latitude = %v (%T), want 47.6", got, got)
	}
	if got, ok := states[1].Attributes["hvac_modes"].([]any); !ok || len(got) != 3 || got[0] != "heat" || got[2] != "off" {
		t.Errorf("hvac_modes = %v (%T)", states[1].Attributes["hvac_modes"], states[1].Attributes["hvac_modes"])
	}
	if got := states[1].Attributes["current_temperature"]; got != 21.5 {
		t.Errorf("current_temperature = %v (%T)", got, got)
	}
	if got := states[1].Attributes["locked"]; got != true {
		t.Errorf("locked = %v (%T)", got, got)
	}
	if states[0].FriendlyName() != "Home" || states[1].FriendlyName() != "Thermostat" {
		t.Errorf("friendly names = %q, %q", states[0].FriendlyName(), states[1].FriendlyName())
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
