// Package ha is a minimal read-only Home Assistant client.
package ha

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// StatesClient fetches entity states from Home Assistant.
type StatesClient interface {
	GetStates(ctx context.Context) ([]EntityState, error)
}

// ServiceCaller calls Home Assistant services (the button-tile action
// path). The service name and data are constrained by the caller: the API
// layer offers a fixed allowlist of standard actions, never free-form
// service or data payloads.
type ServiceCaller interface {
	CallService(ctx context.Context, service string, data map[string]any) (map[string]any, error)
}

// ErrEntityNotFound is reported when Home Assistant has no such entity
// (a service call for an unknown entity_id fails on the HA side with a
// "unable to find entity" error or a 404).
var ErrEntityNotFound = errors.New("home assistant: entity not found")

// EntityState is a normalized Home Assistant entity.
type EntityState struct {
	EntityID    string         `json:"entity_id"`
	State       string         `json:"state"`
	Attributes  map[string]any `json:"attributes,omitempty"`
	LastUpdated time.Time      `json:"last_updated,omitempty"`
	LastChanged time.Time      `json:"last_changed,omitempty"`
}

// FriendlyName returns the friendly_name attribute if present.
func (e EntityState) FriendlyName() string {
	if e.Attributes == nil {
		return ""
	}
	s, _ := e.Attributes["friendly_name"].(string)
	return s
}

// Client is the HTTP implementation of StatesClient.
type Client struct {
	BaseURL string // e.g. https://ha.example.com
	Token   string
	HTTP    *http.Client
}

// NewClient builds a Home Assistant client.
func NewClient(baseURL, token string) *Client {
	return &Client{
		BaseURL: baseURL,
		Token:   token,
		HTTP:    &http.Client{Timeout: 15 * time.Second},
	}
}

type haState struct {
	EntityID    string         `json:"entity_id"`
	State       string         `json:"state"`
	Attributes  map[string]any `json:"attributes"`
	LastUpdated *time.Time     `json:"last_updated"`
	LastChanged *time.Time     `json:"last_changed"`
}

// GetStates fetches the state of every entity.
func (c *Client) GetStates(ctx context.Context) ([]EntityState, error) {
	u, err := url.Parse(c.BaseURL)
	if err != nil {
		return nil, fmt.Errorf("invalid HOMEASSISTANT_URL %q: %w", c.BaseURL, err)
	}
	u.Path = "/api/states"

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Accept", "application/json")

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("home assistant request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return nil, fmt.Errorf("home assistant auth failed (status %d): check HOMEASSISTANT_TOKEN", resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("home assistant returned status %d: %s", resp.StatusCode, string(body))
	}

	var raw []haState
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return nil, fmt.Errorf("decode home assistant states: %w", err)
	}
	out := make([]EntityState, 0, len(raw))
	for _, s := range raw {
		e := EntityState{
			EntityID:   s.EntityID,
			State:      s.State,
			Attributes: s.Attributes,
		}
		if s.LastUpdated != nil {
			e.LastUpdated = *s.LastUpdated
		}
		if s.LastChanged != nil {
			e.LastChanged = *s.LastChanged
		}
		out = append(out, e)
	}
	return out, nil
}

// CallService invokes a Home Assistant service in the homeassistant
// domain (POST /api/services/homeassistant/<service>) with the given data
// (the API layer always sends {"entity_id": ...} for an allowlisted
// action). It returns HA's result object (empty when HA returns no
// body), or an error: an auth failure, ErrEntityNotFound when HA has no
// such entity, or a generic upstream error (status + short body) for
// everything else, so the API layer can map it to a 502.
func (c *Client) CallService(ctx context.Context, service string, data map[string]any) (map[string]any, error) {
	u, err := url.Parse(c.BaseURL)
	if err != nil {
		return nil, fmt.Errorf("invalid HOMEASSISTANT_URL %q: %w", c.BaseURL, err)
	}
	u.Path = "/api/services/homeassistant/" + service

	body, err := json.Marshal(data)
	if err != nil {
		return nil, fmt.Errorf("encode service data: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u.String(), bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("home assistant request: %w", err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return nil, fmt.Errorf("read home assistant service response: %w", err)
	}

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return nil, fmt.Errorf("home assistant auth failed (status %d): check HOMEASSISTANT_TOKEN", resp.StatusCode)
	}
	// HA reports an unknown entity two ways: a 404, or a 400 whose message
	// names the missing entity ("Unable to find entity light.kitchen").
	if resp.StatusCode == http.StatusNotFound ||
		(resp.StatusCode == http.StatusBadRequest &&
			strings.Contains(strings.ToLower(string(raw)), "unable to find entity")) {
		return nil, fmt.Errorf("%w: %s", ErrEntityNotFound, strings.TrimSpace(string(raw)))
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("home assistant returned status %d: %s", resp.StatusCode, string(raw))
	}

	result := map[string]any{}
	if len(bytes.TrimSpace(raw)) > 0 {
		if err := json.Unmarshal(raw, &result); err != nil {
			result = map[string]any{"result": string(raw)}
		}
		if result == nil {
			result = map[string]any{}
		}
	}
	return result, nil
}
