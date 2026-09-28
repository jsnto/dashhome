// Package api wires the homdash HTTP API: calendar, Home Assistant,
// Hermes chat and Hermes markdown pushes.
package api

import (
	"context"
	"crypto/subtle"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/jsnto/homdash/internal/calendar"
	"github.com/jsnto/homdash/internal/config"
	"github.com/jsnto/homdash/internal/ha"
	"github.com/jsnto/homdash/internal/hermes"
	"github.com/jsnto/homdash/internal/store"
)

// GoogleAPI is the surface of the Google client the API needs.
type GoogleAPI interface {
	calendar.Client
	Mode() calendar.Mode
	HasToken() bool
	LoginURL() string
	Callback(ctx context.Context, code string) error
}

// Server holds the dependencies of every handler.
type Server struct {
	cfg    *config.Config
	google GoogleAPI       // nil = not configured
	ha     ha.StatesClient // nil = not configured
	hermes hermes.Chatter  // nil = not configured
	pushes store.PushStore
}

// NewServer builds a Server. All dependencies may be nil (unconfigured).
func NewServer(cfg *config.Config, google GoogleAPI, haClient ha.StatesClient, hermesClient hermes.Chatter, pushes store.PushStore) *Server {
	return &Server{cfg: cfg, google: google, ha: haClient, hermes: hermesClient, pushes: pushes}
}

// Engine builds the gin engine with all routes.
func (s *Server) Engine() *gin.Engine {
	r := gin.New()
	r.Use(gin.Recovery())

	r.GET("/healthz", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"status": "ok"}) })

	api := r.Group("/api")
	api.GET("/config", s.getConfig)
	api.GET("/calendars", s.listCalendars)
	api.GET("/events", s.listEvents)
	api.GET("/ha/entities", s.haEntities)
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

// --- /api/config -------------------------------------------------------

func (s *Server) getConfig(c *gin.Context) {
	google := gin.H{"configured": false}
	if s.google != nil {
		loginURL := s.google.LoginURL()
		google = gin.H{
			"configured":     true,
			"mode":           string(s.google.Mode()),
			"login_required": loginURL != "",
			"login_url":      loginURL,
		}
	}
	c.JSON(http.StatusOK, gin.H{
		"board": s.cfg.Board,
		"integrations": gin.H{
			"homeassistant": s.cfg.HAConfigured(),
			"google":        google,
			"hermes_chat":   s.cfg.HermesConfigured(),
			"hermes_push":   s.cfg.PushConfigured(),
		},
	})
}

// --- calendars ----------------------------------------------------------

type apiCalendar struct {
	ID      string `json:"id"`
	Summary string `json:"summary"`
	Label   string `json:"label"`
	Primary bool   `json:"primary,omitempty"`
}

func (s *Server) listCalendars(c *gin.Context) {
	if s.google == nil {
		fail(c, http.StatusServiceUnavailable, "google_not_configured",
			"set GOOGLE_APPLICATION_CREDENTIALS, GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET, or GOOGLE_ACCESS_TOKEN")
		return
	}
	if !s.google.HasToken() {
		fail(c, http.StatusServiceUnavailable, "google_login_required",
			"visit "+s.google.LoginURL()+" to authorize homdash")
		return
	}
	cals, err := s.google.ListCalendars(c.Request.Context())
	if err != nil {
		fail(c, http.StatusBadGateway, "google_unavailable", err.Error())
		return
	}

	filter := s.cfg.CalendarFilter()
	var shown []apiCalendar
	for _, cal := range cals {
		if filter != nil && !contains(filter, cal.ID) {
			continue
		}
		shown = append(shown, apiCalendar{
			ID:      cal.ID,
			Summary: cal.Summary,
			Label:   s.cfg.CalendarLabel(cal.ID, cal.Summary),
			Primary: cal.Primary,
		})
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
	if s.google == nil {
		fail(c, http.StatusServiceUnavailable, "google_not_configured",
			"set GOOGLE_APPLICATION_CREDENTIALS, GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET, or GOOGLE_ACCESS_TOKEN")
		return
	}
	if !s.google.HasToken() {
		fail(c, http.StatusServiceUnavailable, "google_login_required",
			"visit "+s.google.LoginURL()+" to authorize homdash")
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
	if s.ha == nil {
		fail(c, http.StatusServiceUnavailable, "homeassistant_not_configured",
			"set HOMEASSISTANT_URL and HOMEASSISTANT_TOKEN")
		return
	}
	states, err := s.ha.GetStates(c.Request.Context())
	if err != nil {
		fail(c, http.StatusBadGateway, "homeassistant_unavailable", err.Error())
		return
	}

	byID := make(map[string]ha.EntityState, len(states))
	for _, st := range states {
		byID[st.EntityID] = st
	}

	filter := s.cfg.HAFilter()
	var out []apiEntity
	if filter == nil {
		for _, st := range states {
			out = append(out, s.entityJSON(st))
		}
	} else {
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

// --- Hermes chat ----------------------------------------------------------

func (s *Server) hermesChat(c *gin.Context) {
	if s.hermes == nil {
		fail(c, http.StatusServiceUnavailable, "hermes_not_configured", "set HERMES_BASE_URL")
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
		fail(c, http.StatusServiceUnavailable, "push_not_configured", "set HERMES_PUSH_TOKEN to enable pushes")
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
	want := s.cfg.PushToken
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

func contains(ss []string, s string) bool {
	for _, x := range ss {
		if x == s {
			return true
		}
	}
	return false
}

func sortEvents(evs []apiEvent) {
	sort.Slice(evs, func(i, j int) bool {
		a, errA := time.Parse(time.RFC3339, evs[i].Start)
		b, errB := time.Parse(time.RFC3339, evs[j].Start)
		if errA != nil || errB != nil {
			return evs[i].Start < evs[j].Start
		}
		return a.Before(b)
	})
}
