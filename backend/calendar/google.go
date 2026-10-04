package calendar

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"golang.org/x/oauth2"
	googleoauth2 "golang.org/x/oauth2/google"
	gcal "google.golang.org/api/calendar/v3"
	"google.golang.org/api/option"
)

// oauthEndpoint is the Google OAuth authorization server. It is a variable
// so tests can point the login/callback flow at a fake token server.
var oauthEndpoint = googleoauth2.Endpoint

// SetOAuthEndpointForTest swaps the OAuth endpoint for tests and returns a
// restore function.
func SetOAuthEndpointForTest(ep oauth2.Endpoint) (restore func()) {
	old := oauthEndpoint
	oauthEndpoint = ep
	return func() { oauthEndpoint = old }
}

// scope is the read-only Calendar scope; dashhome never writes calendars.
const scope = "https://www.googleapis.com/auth/calendar.readonly"

// Mode is the credential mode of a GoogleClient.
type Mode string

const (
	// ModeStatic uses a static access token (development only).
	ModeStatic Mode = "static"
	// ModeServiceAcc uses a service account key file, optionally with
	// domain-wide delegation (Subject).
	ModeServiceAcc Mode = "service_account"
	// ModeOAuth uses a 3-legged OAuth client with a cached token.
	ModeOAuth Mode = "oauth"
)

// GoogleSettings carries Google Calendar credential settings.
type GoogleSettings struct {
	// Endpoint optionally overrides the Calendar API base URL
	// (advanced: API proxy or tests).
	Endpoint string
	// AccessToken is a static OAuth access token (development only).
	AccessToken string
	// ServiceAccountFile is a service account JSON key file.
	ServiceAccountFile string
	// Domain enables domain-wide delegation as this Workspace user.
	Domain string
	// OAuth 3-legged settings.
	ClientID     string
	ClientSecret string
	RedirectURI  string
	// TokenCache is where the OAuth token JSON is stored.
	TokenCache string
}

// GoogleClient implements Client against the Google Calendar API.
type GoogleClient struct {
	srv *gcal.Service

	mode      Mode
	oauthCfg  *oauth2.Config
	tokenPath string

	mu       sync.Mutex
	hasTok   bool
	loginURL string
}

// NewGoogle builds a Google Calendar client from the given settings.
// It returns (nil, nil) when no credential mode is configured, so callers
// treat a nil client as "Google not configured".
func NewGoogle(ctx context.Context, g GoogleSettings) (*GoogleClient, error) {
	switch {
	case g.AccessToken != "":
		ts := oauth2.StaticTokenSource(&oauth2.Token{AccessToken: g.AccessToken})
		return newGoogleService(g, ts, &GoogleClient{mode: ModeStatic, hasTok: true})

	case g.ServiceAccountFile != "":
		data, err := os.ReadFile(g.ServiceAccountFile)
		if err != nil {
			return nil, fmt.Errorf("read service account key %s: %w", g.ServiceAccountFile, err)
		}
		cred, err := googleoauth2.CredentialsFromJSONWithParams(ctx, data, googleoauth2.CredentialsParams{
			Scopes:  []string{scope},
			Subject: g.Domain, // empty = no domain-wide delegation
		})
		if err != nil {
			return nil, fmt.Errorf("parse service account key: %w", err)
		}
		return newGoogleService(g, cred.TokenSource, &GoogleClient{mode: ModeServiceAcc, hasTok: true})

	case g.ClientID != "" && g.ClientSecret != "":
		if g.RedirectURI == "" {
			return nil, fmt.Errorf("RedirectURI is required when using Google OAuth client credentials")
		}
		oc := &oauth2.Config{
			ClientID:     g.ClientID,
			ClientSecret: g.ClientSecret,
			RedirectURL:  g.RedirectURI,
			Scopes:       []string{scope},
			Endpoint:     oauthEndpoint,
		}
		c := &GoogleClient{mode: ModeOAuth, oauthCfg: oc, tokenPath: g.TokenCache}
		// One token-cache read: the has-token check and the service's token
		// source share the loaded token.
		tok := loadTokenOrNil(g.TokenCache)
		if tok != nil {
			c.hasTok = true
		} else {
			c.loginURL = c.newLoginURL()
		}
		return newGoogleService(g, oc.TokenSource(ctx, tok), c)

	default:
		return nil, nil // no credentials configured
	}
}

func newGoogleService(g GoogleSettings, ts oauth2.TokenSource, c *GoogleClient) (*GoogleClient, error) {
	opts := []option.ClientOption{option.WithTokenSource(ts)}
	if g.Endpoint != "" {
		opts = append(opts, option.WithEndpoint(g.Endpoint))
	}
	srv, err := gcal.NewService(context.Background(), opts...)
	if err != nil {
		return nil, fmt.Errorf("build google calendar service: %w", err)
	}
	c.srv = srv
	return c, nil
}

// Mode reports the credential mode.
func (c *GoogleClient) Mode() Mode { return c.mode }

// HasToken reports whether API calls can be made right now.
func (c *GoogleClient) HasToken() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.hasTok
}

// LoginURL returns the OAuth authorization URL when a login is required
// (OAuth mode without a stored token). Empty otherwise.
func (c *GoogleClient) LoginURL() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.mode != ModeOAuth || c.hasTok {
		return ""
	}
	return c.loginURL
}

// Callback completes the OAuth code exchange and stores the token.
func (c *GoogleClient) Callback(ctx context.Context, code string) error {
	c.mu.Lock()
	oc, path, mode := c.oauthCfg, c.tokenPath, c.mode
	c.mu.Unlock()
	if mode != ModeOAuth {
		return fmt.Errorf("google OAuth is not configured")
	}
	if code == "" {
		return fmt.Errorf("missing ?code= parameter")
	}
	tok, err := oc.Exchange(ctx, code)
	if err != nil {
		return fmt.Errorf("exchange auth code: %w", err)
	}
	if err := saveToken(path, tok); err != nil {
		return err
	}
	c.mu.Lock()
	c.hasTok = true
	c.loginURL = ""
	c.mu.Unlock()
	return nil
}

// newLoginURL builds an authorization URL with a random, unchecked state
// (v1 convenience; the callback does not verify it).
func (c *GoogleClient) newLoginURL() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return ""
	}
	return c.oauthCfg.AuthCodeURL(hex.EncodeToString(b))
}

// ListCalendars lists the calendars visible to the credentials.
func (c *GoogleClient) ListCalendars(ctx context.Context) ([]Calendar, error) {
	res, err := c.srv.CalendarList.List().Context(ctx).Do()
	if err != nil {
		return nil, err
	}
	out := make([]Calendar, 0, len(res.Items))
	for _, it := range res.Items {
		out = append(out, Calendar{ID: it.Id, Summary: it.Summary, Primary: it.Primary})
	}
	return out, nil
}

// ListEvents lists events of one calendar overlapping [start, end].
func (c *GoogleClient) ListEvents(ctx context.Context, calendarID string, start, end time.Time) ([]Event, error) {
	res, err := c.srv.Events.List(calendarID).
		TimeMin(start.UTC().Format(time.RFC3339)).
		TimeMax(end.UTC().Format(time.RFC3339)).
		ShowDeleted(false).
		SingleEvents(true).
		Context(ctx).
		Do()
	if err != nil {
		return nil, err
	}
	out := make([]Event, 0, len(res.Items))
	for i := range res.Items {
		out = append(out, mapEvent(calendarID, res.Items[i]))
	}
	return out, nil
}

func mapEvent(calID string, ev *gcal.Event) Event {
	return Event{
		ID:          ev.Id,
		CalendarID:  calID,
		Summary:     ev.Summary,
		Description: ev.Description,
		Location:    ev.Location,
		Start:       eventTime(ev.Start),
		End:         eventTime(ev.End),
		AllDay:      ev.Start != nil && ev.Start.Date != "",
	}
}

func eventTime(t *gcal.EventDateTime) time.Time {
	if t == nil {
		return time.Time{}
	}
	if t.Date != "" {
		if ts, err := time.ParseInLocation("2006-01-02", t.Date, time.Local); err == nil {
			return ts
		}
		return time.Time{}
	}
	if ts, err := time.Parse(time.RFC3339, t.DateTime); err == nil {
		return ts
	}
	return time.Time{}
}

// --- token persistence -------------------------------------------------

type tokenFile struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	Expiry       time.Time `json:"expiry,omitempty"`
}

func loadTokenOrNil(path string) *oauth2.Token {
	tok, err := loadToken(path)
	if err != nil {
		return nil
	}
	return tok
}

func loadToken(path string) (*oauth2.Token, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var tf tokenFile
	if err := json.Unmarshal(data, &tf); err != nil {
		return nil, err
	}
	return &oauth2.Token{
		AccessToken:  tf.AccessToken,
		RefreshToken: tf.RefreshToken,
		Expiry:       tf.Expiry,
	}, nil
}

func saveToken(path string, t *oauth2.Token) error {
	tf := tokenFile{AccessToken: t.AccessToken, RefreshToken: t.RefreshToken, Expiry: t.Expiry}
	data, err := json.MarshalIndent(tf, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}
