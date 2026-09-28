// Package ha is a minimal read-only Home Assistant client.
package ha

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// StatesClient fetches entity states from Home Assistant.
type StatesClient interface {
	GetStates(ctx context.Context) ([]EntityState, error)
}

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
