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
	// Settings resolves the effective (base URL, token) on every call,
	// so a value changed in the settings UI takes effect without a
	// restart (cached results and the remembered command style are
	// invalidated when the settings change). NewDashClient wraps a
	// fixed pair.
	Settings func() (baseURL, token string)

	// ListTTL / ConfigTTL are the cache lifetimes. Defaults: 15 min for
	// the (coarser) list, 5 min for a single dashboard's config.
	ListTTL   time.Duration
	ConfigTTL time.Duration
	// CallTimeout caps one WS round trip (handshake + auth + command).
	// Default 15 s, matching the HTTP client.
	CallTimeout time.Duration

	mu       sync.Mutex
	style    commandStyle
	styleKey string // settings pair style applies to ("" = not probed yet)
	list     cached[[]Dashboard]
	configs  map[string]cached[json.RawMessage]
}

// cached is one TTL cache entry, keyed to the settings that produced it:
// an entry is fresh only while the cache TTL holds AND the settings are
// unchanged (a URL/token change invalidates the entry immediately).
type cached[T any] struct {
	at  time.Time
	key string
	val T
	ok  bool
}

func (c *cached[T]) fresh(ttl time.Duration, now time.Time, key string) bool {
	return c.ok && c.key == key && now.Sub(c.at) < ttl
}

// NewDashClient builds a Home Assistant dashboard client with fixed
// settings.
func NewDashClient(baseURL, token string) *DashClient {
	return &DashClient{
		Settings:    func() (string, string) { return baseURL, token },
		ListTTL:     15 * time.Minute,
		ConfigTTL:   5 * time.Minute,
		CallTimeout: 15 * time.Second,
		configs:     map[string]cached[json.RawMessage]{},
	}
}

// NewDashClientSettings builds a Home Assistant dashboard client whose
// settings resolve on every call (the settings UI > environment
// precedence is applied live, no restart needed).
func NewDashClientSettings(settings func() (baseURL, token string)) *DashClient {
	return &DashClient{
		Settings:    settings,
		ListTTL:     15 * time.Minute,
		ConfigTTL:   5 * time.Minute,
		CallTimeout: 15 * time.Second,
		configs:     map[string]cached[json.RawMessage]{},
	}
}

var errUnknownCommand = errors.New("home assistant unknown command")

// errCodeUnknownCommand is the HA error object code for a command type the
// instance does not know (older instances lack the lovelace/* names).
const errCodeUnknownCommand = "unknown_command"

// dashResult is the result envelope of one WS command. A failed command
// carries its details in the protocol's `error` object.
type dashResult struct {
	ID      json.Number     `json:"id"`
	Success bool            `json:"success"`
	Result  json.RawMessage `json:"result"`
	Error   *wsError        `json:"error"`
}

// wsError is the error object of a failed WS command result.
type wsError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type wsEnv struct {
	Type    string `json:"type"`
	Version string `json:"ha_version"`
}

// settingsKey is the cache key of one settings pair: an entry fetched
// under other settings must not be served (the data is per-instance).
func settingsKey(baseURL, token string) string {
	return baseURL + "\x00" + token
}

// ListDashboards implements DashboardsClient.
func (d *DashClient) ListDashboards(ctx context.Context, force bool) ([]Dashboard, error) {
	baseURL, token := d.Settings()
	key := settingsKey(baseURL, token)
	d.mu.Lock()
	if !force && d.list.fresh(d.ListTTL, time.Now(), key) {
		out := d.list.val
		d.mu.Unlock()
		return out, nil
	}
	d.mu.Unlock()

	raw, err := d.call(ctx, cmdListNew, cmdListOld, nil, false, force, baseURL, token)
	if err != nil {
		return nil, err
	}
	var list []Dashboard
	if err := json.Unmarshal(raw, &list); err != nil {
		return nil, fmt.Errorf("decode home assistant dashboards: %w", err)
	}
	d.mu.Lock()
	d.list = cached[[]Dashboard]{at: time.Now(), key: key, val: list, ok: true}
	d.mu.Unlock()
	return list, nil
}

// GetDashboardConfig implements DashboardsClient.
func (d *DashClient) GetDashboardConfig(ctx context.Context, urlPath string, force bool) (json.RawMessage, error) {
	baseURL, token := d.Settings()
	key := settingsKey(baseURL, token)
	keyPath := NormalizeURLPath(urlPath)
	d.mu.Lock()
	if !force {
		if c, ok := d.configs[keyPath]; ok && c.fresh(d.ConfigTTL, time.Now(), key) {
			d.mu.Unlock()
			return c.val, nil
		}
	}
	d.mu.Unlock()

	args := map[string]any{"url_path": keyPath}
	if keyPath == "" {
		args["url_path"] = nil // default dashboard
	}
	raw, err := d.call(ctx, cmdConfigNew, cmdConfigOld, args, true, force, baseURL, token)
	if err != nil {
		return nil, err
	}
	d.mu.Lock()
	d.configs[keyPath] = cached[json.RawMessage]{at: time.Now(), key: key, val: raw, ok: true}
	d.mu.Unlock()
	return raw, nil
}

// call authenticates on one WS connection and runs one command. When the
// instance does not know the primary command name (older HA), it falls
// back to the legacy name and remembers the result for later calls.
func (d *DashClient) call(ctx context.Context, primary, legacy string, args map[string]any, supportsForce, force bool, baseURL, token string) (json.RawMessage, error) {
	key := settingsKey(baseURL, token)
	d.mu.Lock()
	style := d.style
	if key != d.styleKey {
		style = styleUnknown // settings changed since the probe: re-probe
	}
	d.mu.Unlock()

	if style == styleLegacy {
		return d.oneCall(ctx, legacy, dropForce(args), baseURL, token)
	}
	raw, err := d.oneCall(ctx, primary, forceArgs(args, supportsForce, force), baseURL, token)
	if err == nil {
		if style == styleUnknown {
			d.setStyle(styleLovelace, key)
		}
		return raw, nil
	}
	if style == styleUnknown && errors.Is(err, errUnknownCommand) {
		d.setStyle(styleLegacy, key)
		return d.oneCall(ctx, legacy, dropForce(args), baseURL, token)
	}
	return nil, err
}

func (d *DashClient) setStyle(s commandStyle, key string) {
	d.mu.Lock()
	d.style = s
	d.styleKey = key
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
func (d *DashClient) oneCall(ctx context.Context, cmdType string, args map[string]any, baseURL, token string) (json.RawMessage, error) {
	timeout := d.CallTimeout
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	conn, err := d.dial(ctx, baseURL)
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
	auth, _ := json.Marshal(map[string]any{"type": "auth", "access_token": token})
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
		code, msg := "", ""
		if res.Error != nil {
			code, msg = res.Error.Code, res.Error.Message
		}
		if code == errCodeUnknownCommand {
			return nil, fmt.Errorf("%w: %s", errUnknownCommand, msg)
		}
		return nil, fmt.Errorf("home assistant %s failed: %s", cmdType, msg)
	}
	if len(res.Result) == 0 {
		res.Result = json.RawMessage("null")
	}
	return res.Result, nil
}

// dial opens the /api/websocket endpoint derived from the base URL.
func (d *DashClient) dial(ctx context.Context, baseURL string) (*websocket.Conn, error) {
	base, err := url.Parse(baseURL)
	if err != nil || (base.Scheme != "http" && base.Scheme != "https") {
		return nil, fmt.Errorf("invalid HOMEASSISTANT_URL %q: %v", baseURL, err)
	}
	wsScheme := "ws"
	if base.Scheme == "https" {
		wsScheme = "wss"
	}
	base.Scheme = wsScheme
	base.Path = "/api/websocket"
	base.RawQuery = ""

	conn, resp, err := websocket.Dial(ctx, base.String(), &websocket.DialOptions{
		HTTPHeader: http.Header{"Origin": []string{baseURL}},
	})
	if err != nil {
		if resp != nil && (resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden) {
			return nil, fmt.Errorf("home assistant auth failed (status %d): check HOMEASSISTANT_TOKEN", resp.StatusCode)
		}
		return nil, fmt.Errorf("home assistant websocket: %w", err)
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
