#!/usr/bin/env python3
"""
A fake Google Calendar API for live-validating the dashhome app's
Calendar section without real Google credentials.

dashhome's Google client honors the GOOGLE_API_ENDPOINT override (see
.env.example): point a scratch dashhome instance at this server plus a
dummy GOOGLE_ACCESS_TOKEN:

    GOOGLE_ACCESS_TOKEN=fake
    GOOGLE_API_ENDPOINT=http://127.0.0.1:3200/v3

Serves the two endpoints the v3 client uses:

    GET /v3/users/me/calendarList
    GET /v3/calendars/{id}/events

Sample events are generated around "today" (the current month), so the
app's current-month grid shows chips immediately. Events respect the
timeMin/timeMax range parameters the way the real API does.
"""

import calendar
import datetime
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, unquote

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3210

CALENDARS = [
    {"id": "family@example.com", "summary": "Family", "primary": True},
    {"id": "work@example.com", "summary": "Work", "primary": False},
    {"id": "personal@example.com", "summary": "Personal", "primary": False},
]


def now():
    return datetime.datetime.now(datetime.timezone.utc)


def day(dt):
    return dt.date()


def all_day_start(d):
    return datetime.datetime(d.year, d.month, d.day, tzinfo=datetime.timezone.utc)


def all_day_end(d):
    # Exclusive end: the day AFTER the last day, as Google returns it.
    return datetime.datetime(d.year, d.month, d.day, tzinfo=datetime.timezone.utc) + datetime.timedelta(days=1)


def build_events():
    """Deterministic sample events spread over the current month."""
    today = day(now())
    first = datetime.datetime(today.year, today.month, 1)
    days_in_month = calendar.monthrange(today.year, today.month)[1]

    def d(n):
        return datetime.date(today.year, today.month, min(n, days_in_month))

    events = [
        # Family: an all-day trip spanning three days.
        {"id": "family-trip", "calendar": "family@example.com", "summary": "Cabin weekend",
         "start": all_day_start(d(max(1, today.day - 2))),
         "end": all_day_end(d(today.day + 1)), "all_day": True,
         "description": "Unplugged weekend at the lake.", "location": "Lake cabin"},
        # Family: a timed event today.
        {"id": "family-dinner", "calendar": "family@example.com", "summary": "Dinner with grandparents",
         "start": now().replace(hour=18, minute=30, second=0, microsecond=0),
         "end": now().replace(hour=20, minute=0, second=0, microsecond=0), "all_day": False,
         "location": "Grandma's"},
        # Work: a standup on a weekday near today.
        {"id": "work-standup", "calendar": "work@example.com", "summary": "Daily standup",
         "start": now().replace(hour=9, minute=30, second=0, microsecond=0),
         "end": now().replace(hour=9, minute=45, second=0, microsecond=0), "all_day": False,
         "description": "Daily sync.", "location": "Zoom"},
        # Work: a multi-day all-day conference later in the month.
        {"id": "work-conf", "calendar": "work@example.com", "summary": "Platform conference",
         "start": all_day_start(d(max(2, today.day + 3))),
         "end": all_day_end(d(max(4, today.day + 5))), "all_day": True,
         "description": "Keynotes + tracks.", "location": "Convention center"},
        # Personal: a timed review and a same-day all-day event.
        {"id": "personal-review", "calendar": "personal@example.com", "summary": "Phone bill review",
         "start": now().replace(hour=21, minute=0, second=0, microsecond=0),
         "end": now().replace(hour=21, minute=30, second=0, microsecond=0), "all_day": False,
         "description": "Cancel anything unused."},
        {"id": "personal-yoga", "calendar": "personal@example.com", "summary": "Yoga day",
         "start": all_day_start(d(max(1, today.day - 4))),
         "end": all_day_end(d(max(1, today.day - 3))), "all_day": True,
         "location": "Studio"},
    ]
    # Drop the unused variable to keep linters quiet.
    _ = first
    return events


def overlaps(ev, time_min, time_max):
    s, e = ev["start"], ev["end"]
    if time_min is not None and e <= time_min:
        return False
    if time_max is not None and s >= time_max:
        return False
    return True


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # quiet
        pass

    def _send(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)

        # The Go API client drops the /v3 segment when a custom endpoint is
        # set, so both /v3/<path> and bare <path> are accepted.
        m = re.fullmatch(r"(?:/v3)?/users/me/calendarList", parsed.path)
        if m:
            return self._send({"kind": "calendar#calendarList",
                               "items": CALENDARS})

        m = re.fullmatch(r"(?:/v3)?/calendars/([^/]+)/events", parsed.path)
        if m:
            cal_id = unquote(m.group(1))
            if cal_id not in {c["id"] for c in CALENDARS}:
                return self._send({"error": {"code": 404, "message": "not found"}}, 404)
            time_min = datetime.datetime.fromisoformat(qs["timeMin"][0].replace("Z", "+00:00")) if "timeMin" in qs else None
            time_max = datetime.datetime.fromisoformat(qs["timeMax"][0].replace("Z", "+00:00")) if "timeMax" in qs else None
            items = []
            for ev in build_events():
                if ev["calendar"] != cal_id or not overlaps(ev, time_min, time_max):
                    continue
                start = ({"date": ev["start"].strftime("%Y-%m-%d")} if ev["all_day"]
                         else {"dateTime": ev["start"].isoformat()})
                # Real Google returns all-day end dates as EXCLUSIVE (the day
                # after the last day); the web/app logic relies on that.
                end = ({"date": ev["end"].strftime("%Y-%m-%d")}
                       if ev["all_day"]
                       else {"dateTime": ev["end"].isoformat()})
                item = {"id": ev["id"], "summary": ev["summary"], "start": start, "end": end}
                if ev.get("description"):
                    item["description"] = ev["description"]
                if ev.get("location"):
                    item["location"] = ev["location"]
                items.append(item)
            return self._send({"kind": "calendar#events", "items": items})

        self._send({"error": {"code": 404, "message": "unknown path " + parsed.path}}, 404)


if __name__ == "__main__":
    print(f"fake Google Calendar API on http://127.0.0.1:{PORT}/v3 "
          f"(calendars: {', '.join(c['id'] for c in CALENDARS)})", flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
