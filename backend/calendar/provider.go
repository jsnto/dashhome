package calendar

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

// ErrNotConfigured is returned by a Provider's data calls when the
// effective settings select no credential mode.
var ErrNotConfigured = errors.New("google calendar: no credentials configured")

// SettingsFunc resolves the effective Google Calendar settings on use
// (the settings-UI value, then the environment value, then the documented
// default).
type SettingsFunc func() GoogleSettings

// Provider is a calendar.Client whose credential settings resolve on use
// and whose underlying client rebuilds itself only when the effective
// settings change, so a credential edited in the settings UI takes effect
// without a restart. Build errors (an unreadable service-account key, a
// missing OAuth redirect URI) are reported at use time as call errors —
// and via BuildErr — instead of failing the process at startup.
type Provider struct {
	resolve SettingsFunc

	mu       sync.Mutex
	built    bool
	key      GoogleSettings // settings the built client was constructed from
	client   *GoogleClient  // nil when the settings select no credential mode
	buildErr error          // last build failure (nil when the build succeeded)
}

// NewProvider builds a Provider over the settings resolver.
func NewProvider(resolve SettingsFunc) *Provider {
	return &Provider{resolve: resolve}
}

// clientOrError resolves the effective settings and returns the built
// client, rebuilding it when the effective settings changed. The client is
// nil when the settings select no credential mode; a non-nil err is a
// build failure of otherwise-configured credentials.
func (p *Provider) clientOrError(ctx context.Context) (*GoogleClient, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.resolve()
	if p.built && s == p.key {
		return p.client, p.buildErr
	}
	client, err := NewGoogle(ctx, s)
	p.built = true
	p.key = s
	p.client = client
	p.buildErr = err
	return client, err
}

// Configured reports whether the effective settings select a credential
// mode. Pure: it inspects the settings only (no key-file reads).
func (p *Provider) Configured() bool {
	return ModeFor(p.resolve()) != ModeNone
}

// Mode reports the credential mode of the effective settings.
func (p *Provider) Mode() Mode { return ModeFor(p.resolve()) }

// HasToken reports whether API calls can be made right now: a successful
// static/service-account build, or an OAuth client with a cached token.
func (p *Provider) HasToken() bool {
	cl, err := p.clientOrError(context.Background())
	if err != nil || cl == nil {
		return false
	}
	return cl.HasToken()
}

// LoginURL returns the OAuth authorization URL when a login is required
// (OAuth mode without a stored token). Empty otherwise.
func (p *Provider) LoginURL() string {
	cl, err := p.clientOrError(context.Background())
	if err != nil || cl == nil {
		return ""
	}
	return cl.LoginURL()
}

// Callback completes the OAuth code exchange and stores the token.
func (p *Provider) Callback(ctx context.Context, code string) error {
	cl, err := p.clientOrError(ctx)
	if err != nil {
		return err
	}
	if cl == nil {
		return fmt.Errorf("%w: set OAuth client credentials (or another Google credential mode)", ErrNotConfigured)
	}
	return cl.Callback(ctx, code)
}

// BuildErr reports the build error of the current effective settings
// (an unreadable service-account key, a missing OAuth redirect URI). Nil
// when the current settings build cleanly or select no credential mode.
// It resolves the settings itself, so it never reports a stale error
// from a previous settings value. The API layer surfaces it as a 503
// with the cause.
func (p *Provider) BuildErr() error {
	_, err := p.clientOrError(context.Background())
	return err
}

// ListCalendars lists the calendars visible to the effective credentials.
func (p *Provider) ListCalendars(ctx context.Context) ([]Calendar, error) {
	cl, err := p.clientOrError(ctx)
	if err != nil {
		return nil, err
	}
	if cl == nil {
		return nil, ErrNotConfigured
	}
	return cl.ListCalendars(ctx)
}

// ListEvents lists events of one calendar visible to the effective
// credentials.
func (p *Provider) ListEvents(ctx context.Context, calendarID string, start, end time.Time) ([]Event, error) {
	cl, err := p.clientOrError(ctx)
	if err != nil {
		return nil, err
	}
	if cl == nil {
		return nil, ErrNotConfigured
	}
	return cl.ListEvents(ctx, calendarID, start, end)
}
