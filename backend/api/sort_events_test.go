package api

import (
	"encoding/json"
	"testing"

	"github.com/jsnto/dashhome/backend/calendar"
	"github.com/jsnto/dashhome/backend/config"
)

// TestListEventsChronologicalAcrossCalendars guards the decorate-sort-
// undecorate rewrite of sortEvents: events are concatenated calendar by
// calendar (A first), and each calendar's events arrive unsorted, so the
// endpoint output must be fully chronological with each event still paired
// to its own calendar. This exact interleaving produced the wrong order
// under the stale-index comparator (it emitted
// [01-01, 01-05, 01-02, 01-04] instead of [01-01, 01-02, 01-04, 01-05]).
func TestListEventsChronologicalAcrossCalendars(t *testing.T) {
	g := &fakeGoogle{
		hasToken: true,
		cals: []calendar.Calendar{
			{ID: "a@example.com", Summary: "A"},
			{ID: "b@example.com", Summary: "B"},
		},
		events: map[string][]calendar.Event{
			"a@example.com": {
				eventCal("a1", "a@example.com", "A late", "2026-01-05T09:00:00Z", "2026-01-05T10:00:00Z", false),
			},
			"b@example.com": {
				eventCal("b1", "b@example.com", "B early", "2026-01-01T09:00:00Z", "2026-01-01T10:00:00Z", false),
				eventCal("b2", "b@example.com", "B mid-late", "2026-01-04T09:00:00Z", "2026-01-04T10:00:00Z", false),
				eventCal("b3", "b@example.com", "B mid", "2026-01-02T09:00:00Z", "2026-01-02T10:00:00Z", false),
			},
		},
	}
	cfg := cfgFor(t)
	cfg.Board.Calendars = []config.CalendarRef{} // no filter: all calendars, A first
	r := newServer(t, cfg, g, nil, nil, nil)

	w := do(r, "GET", "/api/events?start=2026-01-01T00:00:00Z&end=2026-01-31T23:59:59Z", nil, nil)
	if w.Code != 200 {
		t.Fatalf("code = %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Events []struct {
			ID          string `json:"id"`
			CalendarID  string `json:"calendar_id"`
			CalendarLbl string `json:"calendar_label"`
			Start       string `json:"start"`
		} `json:"events"`
	}
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(resp.Events) != 4 {
		t.Fatalf("events = %+v, want 4", resp.Events)
	}
	want := []struct {
		id    string
		cal   string
		label string
		start string
	}{
		{"b1", "b@example.com", "B", "2026-01-01T09:00:00Z"},
		{"b3", "b@example.com", "B", "2026-01-02T09:00:00Z"},
		{"b2", "b@example.com", "B", "2026-01-04T09:00:00Z"},
		{"a1", "a@example.com", "A", "2026-01-05T09:00:00Z"},
	}
	for i, w := range want {
		got := resp.Events[i]
		if got.ID != w.id || got.Start != w.start || got.CalendarID != w.cal || got.CalendarLbl != w.label {
			t.Errorf("order[%d] = {%s %s %s %q}, want {%s %s %s %q}",
				i, got.ID, got.CalendarID, got.Start, got.CalendarLbl, w.id, w.cal, w.start, w.label)
		}
	}
}

// TestSortEventsUnparseableStartsRawFallback pins the comparator semantics
// carried over from the pre-refactor code: when either start fails to
// parse as RFC3339, the pair is ordered by raw string comparison.
func TestSortEventsUnparseableStartsRawFallback(t *testing.T) {
	cases := []struct {
		name string
		in   []string
		want []string
	}{
		{
			name: "all unparseable: raw string order",
			in:   []string{"zzz", "abc"},
			want: []string{"abc", "zzz"},
		},
		{
			name: "unparseable vs parsed: raw string order (pre-refactor behavior)",
			in:   []string{"9999-01-01T00:00:00Z", "0000-bogus"},
			want: []string{"0000-bogus", "9999-01-01T00:00:00Z"},
		},
		{
			name: "parseable strings still order by time, not lexicographically",
			in:   []string{"2026-02-01T00:00:00Z", "2026-10-01T00:00:00Z"},
			want: []string{"2026-02-01T00:00:00Z", "2026-10-01T00:00:00Z"},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			evs := make([]apiEvent, len(tc.in))
			for i, s := range tc.in {
				evs[i] = apiEvent{Start: s, End: s}
			}
			sortEvents(evs)
			for i, got := range evs {
				if got.Start != tc.want[i] {
					t.Fatalf("after sort = %v, want %v", startsOf(evs), tc.want)
				}
			}
		})
	}
}

func startsOf(evs []apiEvent) []string {
	out := make([]string, len(evs))
	for i, ev := range evs {
		out[i] = ev.Start
	}
	return out
}
