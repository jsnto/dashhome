// Package api wires the dashhome HTTP API: calendar, Home Assistant,
// Hermes chat and Hermes markdown pushes.
package api

import (
	"context"
	"crypto/subtle"
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/jsnto/dashhome/backend/calendar"
	"github.com/jsnto/dashhome/backend/config"
	"github.com/jsnto/dashhome/backend/ha"
	"github.com/jsnto/dashhome/backend/hermes"
	"github.com/jsnto/dashhome/backend/store"
)

// GoogleAPI is the surface of the Google client the API needs. The
// production implementation is *calendar.Provider (settings resolve on
// use); a built *calendar.GoogleClient satisfies it too.
type GoogleAPI interface {
	calendar.Client
	// Configured reports whether the effective settings select a
	// credential mode (a nil *Provider or unconfigured settings: false).
	Configured() bool
	// BuildErr reports the last build failure of the effective settings
	// (an unreadable service-account key, a missing OAuth redirect URI).
	BuildErr() error
	Mode() calendar.Mode
	HasToken() bool
	LoginURL() string
	Callback(ctx context.Context, code string) error
}

// Server holds the dependencies of every handler.
type Server struct {
	cfg    *config.Config
	google GoogleAPI           // nil = not configured
	ha     ha.StatesClient     // nil = not configured
	haSvc  ha.ServiceCaller    // nil = not configured (button-tile action path)
	haDash ha.DashboardsClient // nil = not configured
	hermes hermes.Chatter      // nil = not configured
	pushes store.PushStore
}

// NewServer builds a Server. All dependencies may be nil (unconfigured).
func NewServer(cfg *config.Config, google GoogleAPI, haClient ha.StatesClient, haSvc ha.ServiceCaller, haDash ha.DashboardsClient, hermesClient hermes.Chatter, pushes store.PushStore) *Server {
	return &Server{cfg: cfg, google: google, ha: haClient, haSvc: haSvc, haDash: haDash, hermes: hermesClient, pushes: pushes}
}

// Engine builds the gin engine with all routes.
func (s *Server) Engine() *gin.Engine {
	r := gin.New()
	r.Use(gin.Recovery())

	r.GET("/healthz", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"status": "ok"}) })

	api := r.Group("/api")
	api.GET("/config", s.getConfig)
	api.PUT("/config/refresh", s.putConfigRefresh)
	api.PUT("/config/grid", s.putConfigGrid)
	api.PUT("/config/views", s.putConfigViews)
	api.GET("/config/services", s.getConfigServices)
	api.PUT("/config/services", s.putConfigServices)
	api.GET("/calendars", s.listCalendars)
	api.GET("/events", s.listEvents)
	api.GET("/ha/entities", s.haEntities)
	api.POST("/ha/service", s.haServiceCall)
	api.GET("/ha/embed-url", s.haEmbedURL)
	api.GET("/ha/dashboards", s.haDashboards)
	// Exact-match route for the default dashboard without a trailing slash
	// (gin does not 301 the no-slash variant to the wildcard route).
	api.GET("/ha/dashboard", s.haDashboardConfig)
	api.GET("/ha/dashboard/*path", s.haDashboardConfig)
	api.POST("/hermes/chat", s.hermesChat)
	api.POST("/hermes/push", s.hermesPush)
	api.GET("/hermes/pushes", s.listPushes)
	api.GET("/hermes/pushes/latest", s.latestPush)
	api.GET("/hermes/pushes/:id", s.getPush)
	if s.google != nil {
		api.GET("/auth/google/login", s.googleLogin)
		api.GET("/auth/google/callback", s.googleCallback)
	}
	return r
}

// --- helpers -----------------------------------------------------------

func fail(c *gin.Context, status int, code, detail string) {
	body := gin.H{"error": code}
	if detail != "" {
		body["detail"] = detail
	}
	c.JSON(status, body)
}

// requireGoogle fails the request (and reports false) when Google Calendar
// is not configured, its credentials fail to build, or it is not
// authorized yet.
func (s *Server) requireGoogle(c *gin.Context) bool {
	if s.google == nil || !s.google.Configured() {
		fail(c, http.StatusServiceUnavailable, "google_not_configured",
			"set Google Calendar credentials (service account key, OAuth client credentials, or a static access token) in the settings UI or .env")
		return false
	}
	if !s.google.HasToken() {
		if err := s.google.BuildErr(); err != nil {
			fail(c, http.StatusServiceUnavailable, "google_unavailable", err.Error())
			return false
		}
		fail(c, http.StatusServiceUnavailable, "google_login_required",
			"visit "+s.google.LoginURL()+" to authorize dashhome")
		return false
	}
	return true
}

// --- /api/config -------------------------------------------------------

// boardJSON is the board as the config endpoints expose it: the stored
// board with its views normalized (defaults filled in), so clients can rely
// on board.views being a complete, valid layout even when the board file
// sets no views at all. The services section is deliberately excluded:
// it carries secrets and has its own masked surface (GET
// /api/config/services), so it never rides along the board surface.
func (s *Server) boardJSON() config.Board {
	board := s.cfg.BoardSnapshot()
	views := s.cfg.ViewConfig()
	board.Views = &views
	board.Services = nil
	return board
}

// integrationsJSON is the per-integration status of GET /api/config (and
// the service-settings save answer): booleans and the Google login state
// only — no secret values.
func (s *Server) integrationsJSON() gin.H {
	google := gin.H{"configured": false}
	if s.google != nil && s.google.Configured() {
		loginURL := s.google.LoginURL()
		google = gin.H{
			"configured":     true,
			"mode":           string(s.google.Mode()),
			"login_required": loginURL != "",
			"login_url":      loginURL,
		}
	}
	return gin.H{
		"homeassistant": s.cfg.HAConfigured(),
		"google":        google,
		"hermes_chat":   s.cfg.HermesConfigured(),
		"hermes_push":   s.cfg.PushConfigured(),
	}
}

func (s *Server) getConfig(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"board":        s.boardJSON(),
		"integrations": s.integrationsJSON(),
	})
}

// getConfigServices returns the service settings for the settings screen:
// per field the UI-set value, the environment fallback (for the UI's
// placeholder), and whether each layer is set. Secret fields (tokens and
// credentials) never carry a value — only the set booleans — so a secret
// set in the UI or in .env is acknowledged but never displayed.
func (s *Server) getConfigServices(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"services": s.cfg.ServicesView()})
}

// putConfigServices persists a partial service-settings update to the
// board file (board.services): each group omitted keeps its current
// values; within a group, each field omitted (or null) keeps its value,
// an empty string clears the UI value (back to the environment fallback /
// documented default), and a non-empty string sets it. The effective
// settings (UI > env > default) resolve on use, so a saved value takes
// effect without a restart. The answer carries the updated board, the
// masked service view (for the UI to re-seed), and the integration status.
// Same exposure as the other config endpoints: the dashboard itself has
// no login; the board file is the local settings store (the same trust
// boundary as the .env values it overrides).
func (s *Server) putConfigServices(c *gin.Context) {
	var u config.ServicesUpdate
	if err := c.ShouldBindJSON(&u); err != nil {
		fail(c, http.StatusBadRequest, "bad_request",
			`body must be JSON with optional homeassistant / google / hermes_chat / hermes_push objects of string fields; an empty string clears a field (back to the .env fallback)`)
		return
	}
	if err := s.cfg.SetServices(&u); err != nil {
		status := http.StatusBadRequest
		code := "bad_services"
		if errors.Is(err, config.ErrNoBoardFile) {
			status, code = http.StatusServiceUnavailable, "no_board_file"
		} else if errors.Is(err, config.ErrBoardFileNotWritable) {
			status, code = http.StatusServiceUnavailable, "board_file_not_writable"
		}
		fail(c, status, code, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"board":        s.boardJSON(),
		"services":     s.cfg.ServicesView(),
		"integrations": s.integrationsJSON(),
	})
}

// putConfigRefresh persists the UI polling interval (board.refresh_seconds)
// to the board file. Same exposure as GET /api/config: the dashboard itself
// has no login; the board file is deliberately a non-secret config surface.
func (s *Server) putConfigRefresh(c *gin.Context) {
	var req struct {
		RefreshSeconds *int `json:"refresh_seconds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, "bad_request", "body must be JSON {\"refresh_seconds\": n}")
		return
	}
	if req.RefreshSeconds == nil {
		fail(c, http.StatusBadRequest, "bad_request", "refresh_seconds is required")
		return
	}
	if err := s.cfg.SetRefreshSeconds(*req.RefreshSeconds); err != nil {
		status := http.StatusBadRequest
		code := "bad_refresh_seconds"
		if errors.Is(err, config.ErrNoBoardFile) {
			status, code = http.StatusServiceUnavailable, "no_board_file"
		} else if errors.Is(err, config.ErrBoardFileNotWritable) {
			status, code = http.StatusServiceUnavailable, "board_file_not_writable"
		}
		fail(c, status, code, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"board": s.boardJSON()})
}

// putConfigGrid persists the board's tile grid dimensions (board.grid:
// cols x rows units, each unit split evenly) to the board file. Same
// exposure as GET /api/config: the dashboard itself has no login; the
// board file is deliberately a non-secret config surface.
func (s *Server) putConfigGrid(c *gin.Context) {
	var req struct {
		Cols *int `json:"cols"`
		Rows *int `json:"rows"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, "bad_request", `body must be JSON {"cols": n, "rows": n}`)
		return
	}
	if req.Cols == nil || req.Rows == nil {
		fail(c, http.StatusBadRequest, "bad_request", "cols and rows are required")
		return
	}
	if err := s.cfg.SetGrid(*req.Cols, *req.Rows); err != nil {
		status := http.StatusBadRequest
		code := "bad_grid"
		if errors.Is(err, config.ErrNoBoardFile) {
			status, code = http.StatusServiceUnavailable, "no_board_file"
		} else if errors.Is(err, config.ErrBoardFileNotWritable) {
			status, code = http.StatusServiceUnavailable, "board_file_not_writable"
		}
		fail(c, status, code, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"board": s.boardJSON()})
}

// putConfigViews persists part of the tile layout (board.views) to the
// board file: the board order of the tiles, which are hidden, how the
// Calendar tile renders, per-tile size steps, per-tile grid geometry
// (explicit positions; a null entry clears a tile's geometry), and the
// Home Assistant tile list. Fields omitted from the request keep their
// current effective value; each provided field is validated. The sizes
// and geometry arguments are per-tile maps of the entries to change;
// entries they omit keep the tile's current size step / placement.
// Same exposure as GET /api/config: the dashboard itself has no login; the
// board file is deliberately a non-secret config surface.
func (s *Server) putConfigViews(c *gin.Context) {
	var req struct {
		Order        *[]string                        `json:"order"`
		Hidden       *[]string                        `json:"hidden"`
		CalendarMode *string                          `json:"calendar_mode"`
		Sizes        *map[string]string               `json:"sizes"`
		Geometry     *map[string]*config.TileGeometry `json:"geometry"`
		HATiles      *[]config.HABoardTile            `json:"ha_tiles"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, "bad_request",
			`body must be JSON {"order": [...], "hidden": [...], "calendar_mode": "month"|"schedule", "sizes": {tile: "s"|"m"|"l"|"xl"}, "geometry": {tile: {"x":1,"y":1,"w":2,"h":1}|null}, "ha_tiles": [{"id":"ha-1","entity":"light.kitchen","label":"Kitchen","kind":"button"|"visual"}]}`)
		return
	}
	if err := s.cfg.SetViews(req.Order, req.Hidden, req.CalendarMode, req.Sizes, req.Geometry, req.HATiles); err != nil {
		status := http.StatusBadRequest
		code := "bad_views"
		if errors.Is(err, config.ErrNoBoardFile) {
			status, code = http.StatusServiceUnavailable, "no_board_file"
		} else if errors.Is(err, config.ErrBoardFileNotWritable) {
			status, code = http.StatusServiceUnavailable, "board_file_not_writable"
		}
		fail(c, status, code, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"board": s.boardJSON()})
}

// --- calendars ----------------------------------------------------------

type apiCalendar struct {
	ID      string `json:"id"`
	Summary string `json:"summary"`
	Label   string `json:"label"`
	Primary bool   `json:"primary,omitempty"`
}

func (s *Server) listCalendars(c *gin.Context) {
	if !s.requireGoogle(c) {
		return
	}
	cals, err := s.google.ListCalendars(c.Request.Context())
	if err != nil {
		fail(c, http.StatusBadGateway, "google_unavailable", err.Error())
		return
	}

	filter := s.cfg.CalendarFilter()
	var shown []apiCalendar
	if filter == nil {
		// No configured array: every visible calendar, unchanged.
		for _, cal := range cals {
			shown = append(shown, apiCalendar{
				ID:      cal.ID,
				Summary: cal.Summary,
				Label:   s.cfg.CalendarLabel(cal.ID, cal.Summary),
				Primary: cal.Primary,
			})
		}
	} else {
		// The configured array is the source of truth: every configured ID
		// appears as a calendar even when Google's calendarList does not
		// return it (a calendar shared to a service account may never show
		// up there); the list only enriches the entries it does know with
		// summary and primary.
		byID := make(map[string]calendar.Calendar, len(cals))
		for _, cal := range cals {
			byID[cal.ID] = cal
		}
		for _, id := range filter {
			summary, primary := "", false
			if cal, ok := byID[id]; ok {
				summary, primary = cal.Summary, cal.Primary
			}
			shown = append(shown, apiCalendar{
				ID:      id,
				Summary: summary,
				Label:   s.cfg.CalendarLabel(id, summary),
				Primary: primary,
			})
		}
	}
	if shown == nil {
		shown = []apiCalendar{}
	}
	c.JSON(http.StatusOK, gin.H{"calendars": shown})
}

// --- events -------------------------------------------------------------

type apiEvent struct {
	CalendarID  string `json:"calendar_id"`
	CalendarLbl string `json:"calendar_label"`
	ID          string `json:"id"`
	Summary     string `json:"summary"`
	Description string `json:"description,omitempty"`
	Location    string `json:"location,omitempty"`
	Start       string `json:"start"`
	End         string `json:"end"`
	AllDay      bool   `json:"all_day"`
}

func (s *Server) listEvents(c *gin.Context) {
	if !s.requireGoogle(c) {
		return
	}

	start, end, ok := parseRange(c)
	if !ok {
		fail(c, http.StatusBadRequest, "bad_range", "start/end must be RFC3339 or YYYY-MM-DD")
		return
	}

	single := c.Query("calendar")

	// Which calendars to fetch.
	var ids []string
	labels := map[string]string{}
	if single != "" {
		ids = []string{single}
	} else if filter := s.cfg.CalendarFilter(); filter != nil {
		ids = filter
	} else {
		cals, err := s.google.ListCalendars(c.Request.Context())
		if err != nil {
			fail(c, http.StatusBadGateway, "google_unavailable", err.Error())
			return
		}
		for _, cal := range cals {
			ids = append(ids, cal.ID)
			labels[cal.ID] = s.cfg.CalendarLabel(cal.ID, cal.Summary)
		}
	}

	var events []apiEvent
	var firstErr error
	success := 0
	for _, id := range ids {
		evs, err := s.google.ListEvents(c.Request.Context(), id, start, end)
		if err != nil {
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		success++
		for _, ev := range evs {
			lbl := labels[id]
			if lbl == "" {
				lbl = s.cfg.CalendarLabel(id, "")
			}
			events = append(events, apiEvent{
				CalendarID:  id,
				CalendarLbl: lbl,
				ID:          ev.ID,
				Summary:     ev.Summary,
				Description: ev.Description,
				Location:    ev.Location,
				Start:       ev.Start.Format(time.RFC3339),
				End:         ev.End.Format(time.RFC3339),
				AllDay:      ev.AllDay,
			})
		}
	}
	if success == 0 && firstErr != nil {
		fail(c, http.StatusBadGateway, "google_unavailable", firstErr.Error())
		return
	}
	sortEvents(events)
	if events == nil {
		events = []apiEvent{}
	}
	c.JSON(http.StatusOK, gin.H{"events": events, "start": start.Format(time.RFC3339), "end": end.Format(time.RFC3339)})
}

// --- Home Assistant ------------------------------------------------------

type apiEntity struct {
	EntityID    string         `json:"entity_id"`
	Label       string         `json:"label"`
	Friendly    string         `json:"friendly_name,omitempty"`
	State       string         `json:"state"`
	Attributes  map[string]any `json:"attributes,omitempty"`
	LastUpdated *time.Time     `json:"last_updated,omitempty"`
	LastChanged *time.Time     `json:"last_changed,omitempty"`
}

func (s *Server) haEntities(c *gin.Context) {
	if s.ha == nil || !s.cfg.HAConfigured() {
		fail(c, http.StatusServiceUnavailable, "homeassistant_not_configured",
			"set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN in the settings UI or .env")
		return
	}
	states, err := s.ha.GetStates(c.Request.Context())
	if err != nil {
		fail(c, http.StatusBadGateway, "homeassistant_unavailable", err.Error())
		return
	}

	filter := s.cfg.HAFilter()
	var out []apiEntity
	if filter == nil {
		for _, st := range states {
			out = append(out, s.entityJSON(st))
		}
	} else {
		byID := make(map[string]ha.EntityState, len(states))
		for _, st := range states {
			byID[st.EntityID] = st
		}
		for _, id := range filter {
			st, ok := byID[id]
			if !ok {
				out = append(out, apiEntity{EntityID: id, Label: s.cfg.HAEntityLabel(id, ""), State: "unavailable"})
				continue
			}
			out = append(out, s.entityJSON(st))
		}
	}
	if out == nil {
		out = []apiEntity{}
	}
	c.JSON(http.StatusOK, gin.H{"entities": out, "total": len(out)})
}

func (s *Server) entityJSON(st ha.EntityState) apiEntity {
	e := apiEntity{
		EntityID:   st.EntityID,
		Label:      s.cfg.HAEntityLabel(st.EntityID, st.FriendlyName()),
		Friendly:   st.FriendlyName(),
		State:      st.State,
		Attributes: st.Attributes,
	}
	if !st.LastUpdated.IsZero() {
		t := st.LastUpdated
		e.LastUpdated = &t
	}
	if !st.LastChanged.IsZero() {
		t := st.LastChanged
		e.LastChanged = &t
	}
	return e
}

// haServiceAllowlist is the fixed set of standard actions a button tile
// may perform on its entity (all in the homeassistant domain). The API
// accepts the action names only — never a free-form service or data
// payload — so a caller can at most run these three actions on the
// entity it names (the entity_id is caller-supplied, not restricted to
// the entities the board shows).
var haServiceAllowlist = map[string]string{
	"toggle":   "toggle",
	"turn_on":  "turn_on",
	"turn_off": "turn_off",
}

// haServiceCall performs one standard action on one entity: the button
// tiles' action path. Guarded exactly like the other HA endpoints: 503
// when HA is not configured, 502 when HA is unreachable (or fails the
// call), a clean 404 when HA has no such entity. The only data sent to
// HA is {"entity_id": ...} for an allowlisted action; no HA secret
// appears in the request or the response.
func (s *Server) haServiceCall(c *gin.Context) {
	if s.haSvc == nil || !s.cfg.HAConfigured() {
		fail(c, http.StatusServiceUnavailable, "homeassistant_not_configured",
			"set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN in the settings UI or .env")
		return
	}
	var req struct {
		EntityID string `json:"entity_id"`
		Action   string `json:"action"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, "bad_request",
			`body must be JSON {"entity_id": "light.kitchen", "action": "toggle"|"turn_on"|"turn_off"}`)
		return
	}
	if strings.TrimSpace(req.EntityID) == "" {
		fail(c, http.StatusBadRequest, "bad_request", "entity_id is required")
		return
	}
	service, ok := haServiceAllowlist[req.Action]
	if !ok {
		fail(c, http.StatusBadRequest, "bad_action", "action must be one of toggle, turn_on, turn_off")
		return
	}
	result, err := s.haSvc.CallService(c.Request.Context(), service, map[string]any{"entity_id": req.EntityID})
	if err != nil {
		if errors.Is(err, ha.ErrEntityNotFound) {
			fail(c, http.StatusNotFound, "ha_entity_not_found",
				"no such entity in Home Assistant: "+req.EntityID)
			return
		}
		fail(c, http.StatusBadGateway, "homeassistant_unavailable", err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "entity_id": req.EntityID, "action": req.Action, "result": result})
}

// haEmbedURL returns the fully-formed URL for the Home card's iframe, so
// the frontend never has to assemble it (and never sees the token except in
// this deliberately narrow response, when token_url is enabled).
func (s *Server) haEmbedURL(c *gin.Context) {
	if s.ha == nil || !s.cfg.HAConfigured() {
		fail(c, http.StatusServiceUnavailable, "homeassistant_not_configured",
			"set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN in the settings UI or .env")
		return
	}
	baseURL, token := s.cfg.HASettings()
	u, err := url.Parse(baseURL)
	if err != nil || u.Scheme == "" || u.Host == "" {
		fail(c, http.StatusServiceUnavailable, "homeassistant_not_configured",
			"HOMEASSISTANT_URL is not a valid URL")
		return
	}
	embed := s.cfg.HAEmbed()
	p := "/"
	if embed != nil && embed.URLPath != nil {
		if n := ha.NormalizeURLPath(*embed.URLPath); n != "" {
			p = "/" + n
		}
	}
	u.Path = p
	u.RawQuery = ""
	if embed != nil && embed.TokenURL {
		q := u.Query()
		q.Set("auth_long_lived", token)
		u.RawQuery = q.Encode()
	}
	c.JSON(http.StatusOK, gin.H{"url": u.String()})
}

// --- Home Assistant dashboards (read-only config proxy) -------------------

// haDashboards lists the instance's dashboards (metadata only). ?reload=1
// bypasses the server-side TTL cache.
func (s *Server) haDashboards(c *gin.Context) {
	if s.haDash == nil || !s.cfg.HAConfigured() {
		fail(c, http.StatusServiceUnavailable, "homeassistant_not_configured",
			"set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN in the settings UI or .env")
		return
	}
	list, err := s.haDash.ListDashboards(c.Request.Context(), reloadRequested(c))
	if err != nil {
		fail(c, http.StatusBadGateway, "homeassistant_unavailable", err.Error())
		return
	}
	if list == nil {
		list = []ha.Dashboard{}
	}
	c.JSON(http.StatusOK, gin.H{"dashboards": list})
}

// haDashboardConfig passes through one dashboard's full config JSON.
// The wildcard path segment is the dashboard url_path; empty (i.e.
// /api/ha/dashboard or /api/ha/dashboard/) is the default dashboard.
// ?reload=1 bypasses the server-side TTL cache. Read-only: nothing here
// writes to Home Assistant.
func (s *Server) haDashboardConfig(c *gin.Context) {
	if s.haDash == nil || !s.cfg.HAConfigured() {
		fail(c, http.StatusServiceUnavailable, "homeassistant_not_configured",
			"set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN in the settings UI or .env")
		return
	}
	path := ha.NormalizeURLPath(c.Param("path"))
	raw, err := s.haDash.GetDashboardConfig(c.Request.Context(), path, reloadRequested(c))
	if err != nil {
		fail(c, http.StatusBadGateway, "homeassistant_unavailable", err.Error())
		return
	}
	c.Data(http.StatusOK, "application/json", raw)
}

// reloadRequested reports whether the explicit ?reload query param is set.
func reloadRequested(c *gin.Context) bool {
	return c.Query("reload") != ""
}

// --- Hermes chat ----------------------------------------------------------

func (s *Server) hermesChat(c *gin.Context) {
	if s.hermes == nil || !s.cfg.HermesConfigured() {
		fail(c, http.StatusServiceUnavailable, "hermes_not_configured", "set HERMES_BASE_URL in the settings UI or .env")
		return
	}
	var req hermes.ChatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if strings.TrimSpace(req.Message) == "" {
		fail(c, http.StatusBadRequest, "bad_request", "message is required")
		return
	}
	reply, err := s.hermes.Chat(c.Request.Context(), req)
	if err != nil {
		fail(c, http.StatusBadGateway, "hermes_unavailable", err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"reply": reply})
}

// --- Hermes pushes ---------------------------------------------------------

type pushRequest struct {
	Markdown string `json:"markdown"`
	Source   string `json:"source,omitempty"`
}

func (s *Server) hermesPush(c *gin.Context) {
	if !s.cfg.PushConfigured() {
		fail(c, http.StatusServiceUnavailable, "push_not_configured", "set HERMES_PUSH_TOKEN in the settings UI or .env to enable pushes")
		return
	}
	if !s.pushAuthorized(c) {
		fail(c, http.StatusUnauthorized, "unauthorized",
			"present the push token as `Authorization: Bearer <token>`, `X-Hermes-Token: <token>`, or ?token=<token>")
		return
	}
	var pr pushRequest
	if err := c.ShouldBindJSON(&pr); err != nil {
		fail(c, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if strings.TrimSpace(pr.Markdown) == "" {
		fail(c, http.StatusBadRequest, "bad_request", "markdown is required")
		return
	}
	p, err := s.pushes.Save(&store.Push{Markdown: pr.Markdown, Source: pr.Source})
	if err != nil {
		fail(c, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	c.JSON(http.StatusCreated, gin.H{"push": p})
}

func (s *Server) pushAuthorized(c *gin.Context) bool {
	want := s.cfg.PushTokenEffective()
	var got string
	if h := c.GetHeader("Authorization"); strings.HasPrefix(h, "Bearer ") {
		got = strings.TrimPrefix(h, "Bearer ")
	} else if h := c.GetHeader("X-Hermes-Token"); h != "" {
		got = h
	} else {
		got = c.Query("token")
	}
	return got != "" && subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func (s *Server) listPushes(c *gin.Context) {
	limit := 100
	if v := c.Query("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n <= 0 {
			fail(c, http.StatusBadRequest, "bad_request", "limit must be a positive integer")
			return
		}
		limit = n
	}
	pushes, err := s.pushes.List(limit)
	if err != nil {
		fail(c, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	if pushes == nil {
		pushes = []store.Push{}
	}
	c.JSON(http.StatusOK, gin.H{"pushes": pushes})
}

func (s *Server) latestPush(c *gin.Context) {
	p, err := s.pushes.Latest()
	if err != nil {
		if err == store.ErrNotFound {
			fail(c, http.StatusNotFound, "no_pushes", "no markdown has been pushed yet")
			return
		}
		fail(c, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"push": p})
}

func (s *Server) getPush(c *gin.Context) {
	p, err := s.pushes.Get(c.Param("id"))
	if err != nil {
		if err == store.ErrNotFound {
			fail(c, http.StatusNotFound, "push_not_found", "no push with id "+c.Param("id"))
			return
		}
		fail(c, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{"push": p})
}

// --- Google OAuth endpoints -------------------------------------------------

func (s *Server) googleLogin(c *gin.Context) {
	if s.google == nil || s.google.Mode() != calendar.ModeOAuth {
		fail(c, http.StatusBadRequest, "oauth_not_configured", "Google OAuth client credentials are not set")
		return
	}
	if s.google.HasToken() {
		c.JSON(http.StatusOK, gin.H{"already_authorized": true})
		return
	}
	u := s.google.LoginURL()
	if u == "" {
		fail(c, http.StatusInternalServerError, "no_login_url", "could not build the OAuth login URL")
		return
	}
	c.JSON(http.StatusOK, gin.H{"login_url": u})
}

func (s *Server) googleCallback(c *gin.Context) {
	if s.google == nil || s.google.Mode() != calendar.ModeOAuth {
		fail(c, http.StatusBadRequest, "oauth_not_configured", "Google OAuth client credentials are not set")
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 30*time.Second)
	defer cancel()
	err := s.google.Callback(ctx, c.Query("code"))
	if err != nil {
		fail(c, http.StatusBadRequest, "auth_failed", err.Error())
		return
	}
	c.Redirect(http.StatusFound, "/?google_auth=ok")
}

// --- small utils ------------------------------------------------------------

func parseRange(c *gin.Context) (time.Time, time.Time, bool) {
	now := time.Now()
	var start, end time.Time
	if v := c.Query("start"); v != "" {
		t, ok := parseTimeParam(v)
		if !ok {
			return time.Time{}, time.Time{}, false
		}
		start = t
	} else {
		start = time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	}
	if v := c.Query("end"); v != "" {
		t, ok := parseTimeParam(v)
		if !ok {
			return time.Time{}, time.Time{}, false
		}
		end = t
	} else {
		end = start.AddDate(0, 0, 60)
	}
	if end.Before(start) {
		end = start.AddDate(0, 0, 1)
	}
	return start, end, true
}

func parseTimeParam(v string) (time.Time, bool) {
	layouts := []struct {
		layout string
		eod    bool // date-only input means end of day
	}{
		{time.RFC3339, false},
		{"2006-01-02T15:04:05", false},
		{"2006-01-02", true},
	}
	for _, l := range layouts {
		t, err := time.ParseInLocation(l.layout, v, time.Local)
		if err != nil {
			continue
		}
		if l.eod {
			t = t.Add(23*time.Hour + 59*time.Minute + 59*time.Second)
		}
		return t, true
	}
	return time.Time{}, false
}

// sortEvents orders events by start time. The RFC3339 start strings are
// parsed once up front: a comparator runs O(n log n) times, so parsing
// inside it would re-parse the same strings again and again.
func sortEvents(evs []apiEvent) {
	type decorated struct {
		ev    apiEvent
		start time.Time
		ok    bool
	}
	ds := make([]decorated, len(evs))
	for i := range evs {
		t, err := time.Parse(time.RFC3339, evs[i].Start)
		ds[i] = decorated{ev: evs[i], start: t, ok: err == nil}
	}
	sort.Slice(ds, func(i, j int) bool {
		if ds[i].ok && ds[j].ok {
			return ds[i].start.Before(ds[j].start)
		}
		return ds[i].ev.Start < ds[j].ev.Start
	})
	for i := range ds {
		evs[i] = ds[i].ev
	}
}
