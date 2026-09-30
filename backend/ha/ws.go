package ha

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	websocket "nhooyr.io/websocket"
)

// DashboardsClient exposes the read-only lovelace dashboard API over
// Home Assistant's WebSocket API. It is the WS counterpart of StatesClient
// and authenticates with the same HOMEASSISTANT_TOKEN.
type DashboardsClient interface {
	// ListDashboards returns the dashboard metadata. force bypasses the
	// cache and re-fetches from Home Assistant.
	ListDashboards(ctx context.Context, force bool) ([]Dashboard, error)
	// GetDashboardConfig returns the full dashboard config as raw JSON
	// (pass-through of the lovelace structure). urlPath is a normalized
	// url_path; "" means the default dashboard. force bypasses the cache.
	GetDashboardConfig(ctx context.Context, urlPath string, force bool) (json.RawMessage, error)
}

// Dashboard is the metadata of one Home Assistant dashboard, as returned
// by lovelace/dashboards/list.
type Dashboard struct {
	ID            string  `json:"id"`
	URLPath       *string `json:"url_path"`
	Title         string  `json:"title"`
	Icon          string  `json:"icon,omitempty"`
	RequireAdmin  bool    `json:"require_admin"`
	ShowInSidebar bool    `json:"show_in_sidebar"`
	Mode          string  `json:"mode"`
	Filename      string  `json:"filename,omitempty"`
}

// NormalizeURLPath trims surrounding whitespace and slashes from a
// dashboard url_path. The empty result means the default dashboard
// (url_path null in the HA API).
func NormalizeURLPath(p string) string {
	return strings.Trim(strings.TrimSpace(p), "/")
}

// Dashboard command names. The lovelace/* names are the current contract
// (HA >= 2024.1); older instances expose the same functionality as
// get_dashboards/get_dashboard. The client probes and remembers.
const (
	cmdListNew   = "lovelace/dashboards/list"
	cmdConfigNew = "lovelace/config"
	cmdListOld   = "get_dashboards"
	cmdConfigOld = "get_dashboard"
)

type commandStyle int

const (
	styleUnknown  commandStyle = iota // not probed yet
	styleLovelace                     // lovelace/* names work
	styleLegacy                       // get_dashboards/get_dashboard
)

// DashClient is the WebSocket implementation of DashboardsClient.
//
// Results are cached in memory with a TTL; the config changes rarely and
// the dashboard is read-only here, so a stale-for-TTL view is acceptable
// and `force` (the reload query param) bypasses it.
type DashClient struct {
	BaseURL string
	Token   string

	// ListTTL / ConfigTTL are the cache lifetimes. Defaults: 15 min for
	// the (coarser) list, 5 min for a single dashboard's config.
	ListTTL   time.Duration
	ConfigTTL time.Duration
	// CallTimeout caps one WS round trip (handshake + auth + command).
	// Default 15 s, matching the HTTP client.
	CallTimeout time.Duration

	mu      sync.Mutex
	style   commandStyle
	list    cached[[]Dashboard]
	configs map[string]cached[json.RawMessage]
}

// cached is one TTL cache entry.
type cached[T any] struct {
	at  time.Time
	val T
	ok  bool
}

func (c *cached[T]) fresh(ttl time.Duration, now time.Time) bool {
	return c.ok && now.Sub(c.at) < ttl
}

// NewDashClient builds a Home Assistant dashboard client.
func NewDashClient(baseURL, token string) *DashClient {
	return &DashClient{
		BaseURL:     baseURL,
		Token:       token,
		ListTTL:     15 * time.Minute,
		ConfigTTL:   5 * time.Minute,
		CallTimeout: 15 * time.Second,
		configs:     map[string]cached[json.RawMessage]{},
	}
}

var errUnknownCommand = errors.New("home assistant unknown command")

// dashResult is the result envelope of one WS command.
type dashResult struct {
	ID      json.Number     `json:"id"`
	Success bool            `json:"success"`
	Result  json.RawMessage `json:"result"`
	Message string          `json:"message"`
}

type wsEnv struct {
	Type    string `json:"type"`
	Version string `json:"ha_version"`
}

// ListDashboards implements DashboardsClient.
func (d *DashClient) ListDashboards(ctx context.Context, force bool) ([]Dashboard, error) {
	d.mu.Lock()
	if !force && d.list.fresh(d.ListTTL, time.Now()) {
		out := d.list.val
		d.mu.Unlock()
		return out, nil
	}
	d.mu.Unlock()

	raw, err := d.call(ctx, cmdListNew, cmdListOld, nil, false, force)
	if err != nil {
		return nil, err
	}
	var list []Dashboard
	if err := json.Unmarshal(raw, &list); err != nil {
		return nil, fmt.Errorf("decode home assistant dashboards: %w", err)
	}
	d.mu.Lock()
	d.list = cached[[]Dashboard]{at: time.Now(), val: list, ok: true}
	d.mu.Unlock()
	return list, nil
}

// GetDashboardConfig implements DashboardsClient.
func (d *DashClient) GetDashboardConfig(ctx context.Context, urlPath string, force bool) (json.RawMessage, error) {
	key := NormalizeURLPath(urlPath)
	d.mu.Lock()
	if !force {
		if c, ok := d.configs[key]; ok && c.fresh(d.ConfigTTL, time.Now()) {
			d.mu.Unlock()
			return c.val, nil
		}
	}
	d.mu.Unlock()

	args := map[string]any{"url_path": key}
	if key == "" {
		args["url_path"] = nil // default dashboard
	}
	raw, err := d.call(ctx, cmdConfigNew, cmdConfigOld, args, true, force)
	if err != nil {
		return nil, err
	}
	d.mu.Lock()
	d.configs[key] = cached[json.RawMessage]{at: time.Now(), val: raw, ok: true}
	d.mu.Unlock()
	return raw, nil
}

// call authenticates on one WS connection and runs one command. When the
// instance does not know the primary command name (older HA), it falls
// back to the legacy name and remembers the result for later calls.
func (d *DashClient) call(ctx context.Context, primary, legacy string, args map[string]any, supportsForce, force bool) (json.RawMessage, error) {
	d.mu.Lock()
	style := d.style
	d.mu.Unlock()

	if style == styleLegacy {
		return d.oneCall(ctx, legacy, dropForce(args))
	}
	raw, err := d.oneCall(ctx, primary, forceArgs(args, supportsForce, force))
	if err == nil {
		if style == styleUnknown {
			d.setStyle(styleLovelace)
		}
		return raw, nil
	}
	if style == styleUnknown && errors.Is(err, errUnknownCommand) {
		d.setStyle(styleLegacy)
		return d.oneCall(ctx, legacy, dropForce(args))
	}
	return nil, err
}

func (d *DashClient) setStyle(s commandStyle) {
	d.mu.Lock()
	d.style = s
	d.mu.Unlock()
}

// forceArgs copies args and sets "force" when the command supports it.
func forceArgs(args map[string]any, supportsForce, force bool) map[string]any {
	if !supportsForce {
		return args
	}
	out := make(map[string]any, len(args)+1)
	for k, v := range args {
		out[k] = v
	}
	out["force"] = force
	return out
}

// dropForce returns args without the "force" key (legacy commands lack it).
func dropForce(args map[string]any) map[string]any {
	if len(args) == 0 || args == nil {
		return args
	}
	out := make(map[string]any, len(args))
	for k, v := range args {
		if k == "force" {
			continue
		}
		out[k] = v
	}
	return out
}

// oneCall runs one WS session: handshake, auth, one command, one result.
func (d *DashClient) oneCall(ctx context.Context, cmdType string, args map[string]any) (json.RawMessage, error) {
	timeout := d.CallTimeout
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	conn, err := d.dial(ctx)
	if err != nil {
		return nil, err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	// 1. HA announces auth_required on connect.
	env, err := readEnv(ctx, conn)
	if err != nil {
		return nil, err
	}
	if env.Type != "auth_required" {
		return nil, fmt.Errorf("home assistant websocket: expected auth_required, got %q", env.Type)
	}

	// 2. Authenticate with the shared long-lived token.
	auth, _ := json.Marshal(map[string]any{"type": "auth", "access_token": d.Token})
	if err := conn.Write(ctx, websocket.MessageText, auth); err != nil {
		return nil, fmt.Errorf("home assistant websocket: %w", err)
	}
	env, err = readEnv(ctx, conn)
	if err != nil {
		return nil, err
	}
	switch env.Type {
	case "auth_ok":
	case "auth_invalid":
		return nil, errors.New("home assistant auth failed: check HOMEASSISTANT_TOKEN")
	default:
		return nil, fmt.Errorf("home assistant websocket: unexpected auth response %q", env.Type)
	}

	// 3. Send the command and wait for its result.
	req := map[string]any{"id": 1, "type": cmdType}
	for k, v := range args {
		req[k] = v
	}
	b, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	if err := conn.Write(ctx, websocket.MessageText, b); err != nil {
		return nil, fmt.Errorf("home assistant websocket: %w", err)
	}
	var res dashResult
	for {
		mt, b, err := conn.Read(ctx)
		if err != nil {
			return nil, fmt.Errorf("home assistant websocket: %w", err)
		}
		if mt != websocket.MessageText {
			continue
		}
		var r dashResult
		if err := json.Unmarshal(b, &r); err != nil {
			continue
		}
		if r.ID != json.Number("1") {
			continue // unrelated message (e.g. a stray event)
		}
		res = r
		break
	}
	if !res.Success {
		if strings.Contains(strings.ToLower(res.Message), "unknown command") {
			return nil, fmt.Errorf("%w: %s", errUnknownCommand, res.Message)
		}
		return nil, fmt.Errorf("home assistant %s failed: %s", cmdType, res.Message)
	}
	if len(res.Result) == 0 {
		res.Result = json.RawMessage("null")
	}
	return res.Result, nil
}

// dial opens the /api/websocket endpoint derived from the base URL.
func (d *DashClient) dial(ctx context.Context) (*websocket.Conn, error) {
	base, err := url.Parse(d.BaseURL)
	if err != nil || (base.Scheme != "http" && base.Scheme != "https") {
		return nil, fmt.Errorf("invalid HOMEASSISTANT_URL %q: %v", d.BaseURL, err)
	}
	wsScheme := "ws"
	if base.Scheme == "https" {
		wsScheme = "wss"
	}
	base.Scheme = wsScheme
	base.Path = "/api/websocket"
	base.RawQuery = ""

	conn, resp, err := websocket.Dial(ctx, base.String(), &websocket.DialOptions{
		HTTPHeader: http.Header{"Origin": []string{d.BaseURL}},
	})
	if err != nil {
		return nil, fmt.Errorf("home assistant websocket: %w", err)
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		conn.Close(websocket.StatusInternalError, "handshake failed")
		if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
			return nil, fmt.Errorf("home assistant auth failed (status %d): check HOMEASSISTANT_TOKEN", resp.StatusCode)
		}
		return nil, fmt.Errorf("home assistant websocket: handshake status %d", resp.StatusCode)
	}
	conn.SetReadLimit(8 << 20)
	return conn, nil
}

// readEnv reads one JSON text message and unmarshals its envelope fields.
func readEnv(ctx context.Context, conn *websocket.Conn) (wsEnv, error) {
	var env wsEnv
	for {
		mt, b, err := conn.Read(ctx)
		if err != nil {
			return wsEnv{}, fmt.Errorf("home assistant websocket: %w", err)
		}
		if mt != websocket.MessageText {
			continue
		}
		if err := json.Unmarshal(b, &env); err != nil {
			return wsEnv{}, fmt.Errorf("home assistant websocket: bad message: %w", err)
		}
		return env, nil
	}
}
