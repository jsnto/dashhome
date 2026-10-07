package ha

import (
	"context"
	"errors"
	"io"
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

// --- CallService ----------------------------------------------------------

func fakeHAServices(t *testing.T, status int, body string, wantPath, wantAuth, wantData string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != wantPath {
			t.Errorf("path = %q, want %q", r.URL.Path, wantPath)
		}
		if r.Method != http.MethodPost {
			t.Errorf("method = %q, want POST", r.Method)
		}
		if got := r.Header.Get("Authorization"); got != wantAuth {
			t.Errorf("Authorization = %q, want %q", got, wantAuth)
		}
		if wantData != "" {
			b, _ := io.ReadAll(r.Body)
			if string(b) != wantData {
				t.Errorf("data = %s, want %s", b, wantData)
			}
		}
		if status != http.StatusOK {
			w.WriteHeader(status)
		}
		w.Write([]byte(body))
	}))
}

func TestCallService(t *testing.T) {
	srv := fakeHAServices(t, http.StatusOK, `{"success":true}`, "/api/services/homeassistant/toggle", "Bearer ha-tok", `{"entity_id":"light.kitchen"}`)
	defer srv.Close()

	c := NewClient(srv.URL, "ha-tok")
	result, err := c.CallService(context.Background(), "toggle", map[string]any{"entity_id": "light.kitchen"})
	if err != nil {
		t.Fatalf("CallService: %v", err)
	}
	if result["success"] != true {
		t.Errorf("result = %v", result)
	}
}

func TestCallServiceEmptyBody(t *testing.T) {
	srv := fakeHAServices(t, http.StatusOK, "", "/api/services/homeassistant/turn_on", "Bearer ha-tok", `{"entity_id":"light.kitchen"}`)
	defer srv.Close()

	c := NewClient(srv.URL, "ha-tok")
	result, err := c.CallService(context.Background(), "turn_on", map[string]any{"entity_id": "light.kitchen"})
	if err != nil {
		t.Fatalf("CallService: %v", err)
	}
	if len(result) != 0 {
		t.Errorf("result = %v, want empty", result)
	}
}

func TestCallServiceUnknownEntity(t *testing.T) {
	// HA reports an unknown entity two ways: a 404, or a 400 whose
	// message names the missing entity.
	for name, tc := range map[string]struct {
		status int
		body   string
	}{
		"not-found":   {status: http.StatusNotFound, body: `{"message":"not found"}`},
		"bad-request": {status: http.StatusBadRequest, body: `{"message":"Unable to find entity light.nope."}`},
	} {
		srv := fakeHAServices(t, tc.status, tc.body, "/api/services/homeassistant/turn_off", "Bearer ha-tok", `{"entity_id":"light.nope"}`)
		c := NewClient(srv.URL, "ha-tok")
		_, err := c.CallService(context.Background(), "turn_off", map[string]any{"entity_id": "light.nope"})
		if !errors.Is(err, ErrEntityNotFound) {
			t.Errorf("%s: err = %v, want ErrEntityNotFound", name, err)
		}
		srv.Close()
	}
}

func TestCallServiceOtherClientErrorIsNotEntityNotFound(t *testing.T) {
	srv := fakeHAServices(t, http.StatusBadRequest, `{"message":"invalid service data"}`, "/api/services/homeassistant/toggle", "Bearer ha-tok", `{"entity_id":"light.kitchen"}`)
	defer srv.Close()

	c := NewClient(srv.URL, "ha-tok")
	_, err := c.CallService(context.Background(), "toggle", map[string]any{"entity_id": "light.kitchen"})
	if err == nil {
		t.Fatal("expected error")
	}
	if errors.Is(err, ErrEntityNotFound) {
		t.Errorf("err = %v, must not be ErrEntityNotFound", err)
	}
}

func TestCallServiceAuthError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "wrong")
	if _, err := c.CallService(context.Background(), "toggle", map[string]any{"entity_id": "light.kitchen"}); err == nil {
		t.Fatal("expected auth error")
	}
}

func TestCallServiceServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("boom"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "tok")
	if _, err := c.CallService(context.Background(), "toggle", map[string]any{"entity_id": "light.kitchen"}); err == nil {
		t.Fatal("expected server error")
	}
}
