// Package calendar defines the calendar abstractions used by the API and
// the Google Calendar integration client.
package calendar

import (
	"context"
	"time"
)

// Calendar identifies a calendar visible to the configured credentials.
type Calendar struct {
	ID      string
	Summary string
	Primary bool
}

// Event is a normalized single calendar event.
type Event struct {
	ID          string
	CalendarID  string
	Summary     string
	Description string
	Location    string
	Start       time.Time
	End         time.Time
	AllDay      bool
}

// Client fetches calendars and events. Tests use a fake implementation;
// production uses the Google implementation.
type Client interface {
	ListCalendars(ctx context.Context) ([]Calendar, error)
	ListEvents(ctx context.Context, calendarID string, start, end time.Time) ([]Event, error)
}
