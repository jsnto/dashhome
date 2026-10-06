import { useEffect, useMemo, useState } from 'react'
import { ApiError, api } from '../api'
import type { ApiEvent, CalendarInfo, CalendarMode } from '../types'
import { dayKey } from '../utils'
import MonthGrid from './MonthGrid'
import Note from './Note'

const SUMMARY = '__summary__'

/** Start–end times for a timed event, or "All day" for all-day events. */
function fmtWhen(e: ApiEvent): string {
  if (e.all_day) {
    return 'All day'
  }
  const t = (d: Date) => d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  return `${t(new Date(e.start))} – ${t(new Date(e.end))}`
}

/**
 * The schedule summary: a chronological list of upcoming events (time,
 * title, calendar label), grouped by day. The backend returns the events
 * already sorted by start time, so the days fall out of the load order.
 */
function ScheduleList({ events }: { events: ApiEvent[] }) {
  const days = useMemo(() => {
    const out: { key: string; label: string; items: ApiEvent[] }[] = []
    for (const e of events) {
      const d = new Date(e.start)
      const k = dayKey(d)
      const last = out[out.length - 1]
      if (!last || last.key !== k) {
        out.push({
          key: k,
          label: d.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' }),
          items: [e],
        })
      } else {
        last.items.push(e)
      }
    }
    return out
  }, [events])

  if (days.length === 0) {
    return <p className="note">No upcoming events in this range.</p>
  }

  return (
    <div className="schedule-wrap">
      {days.map((day) => (
        <div key={day.key} className="schedule-day">
          <div className="schedule-day-label">{day.label}</div>
          <ul className="schedule">
            {day.items.map((e) => (
              <li key={`${e.calendar_id}-${e.id}`} className="schedule-item">
                <span className="schedule-time">{fmtWhen(e)}</span>
                <span className="schedule-title">{e.summary || '(no title)'}</span>
                {e.calendar_label && <span className="schedule-cal">{e.calendar_label}</span>}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

/**
 * The Calendar window: a month grid, or (when the board's views section
 * sets calendar_mode "schedule") a chronological schedule summary of the
 * same loaded events. Month navigation and the Summary/per-calendar data
 * tabs work in both modes.
 */
export default function CalendarSection({ mode = 'month' }: { mode?: CalendarMode }) {
  const [calendars, setCalendars] = useState<CalendarInfo[] | null>(null)
  const [calErr, setCalErr] = useState<ApiError | null>(null)
  const [active, setActive] = useState<string>(SUMMARY)
  const [now, setNow] = useState(() => new Date())
  const [events, setEvents] = useState<ApiEvent[]>([])
  const [eventsErr, setEventsErr] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(false)

  const year = now.getFullYear()
  const month = now.getMonth()
  const range = useMemo(() => {
    const first = new Date(year, month, 1)
    const last = new Date(year, month + 1, 0)
    return { start: dayKey(first), end: dayKey(last) }
  }, [year, month])

  // Load the calendars once.
  useEffect(() => {
    api
      .getCalendars()
      .then((cs) => setCalendars(cs))
      .catch((e: unknown) => {
        setCalErr(e instanceof ApiError ? e : new ApiError(0, 'load_failed', String(e)))
      })
  }, [])

  // Load events whenever the tab or month changes.
  useEffect(() => {
    if (calErr) return
    let cancelled = false
    setLoading(true)
    api
      .getEvents(range.start, range.end, active === SUMMARY ? undefined : active)
      .then((ev) => {
        if (cancelled) return
        setEvents(ev)
        setEventsErr(null)
      })
      .catch((e: unknown) => {
        if (!cancelled && e instanceof ApiError) setEventsErr(e)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [range.start, range.end, active, calErr, calendars])

  const shiftMonth = (delta: number) => setNow(new Date(year, month + delta, 1))

  // The schedule summary is a list of upcoming events: when the loaded
  // range includes today, events that already ended before today drop out
  // (navigated-to past months list their full month instead). All-day ends
  // are exclusive dates (end date = last real day + 1) that the backend
  // emits as midnight, so the all-day branch compares in date space
  // (zero-padded date prefix vs. local today), not wall-clock time: a
  // wall-clock compare shifts with the browser's timezone. Timed events
  // carry explicit offsets, so they compare as absolute times.
  const upcoming = useMemo(() => {
    if (mode !== 'schedule') return events
    const t = new Date()
    const todayStart = new Date(t.getFullYear(), t.getMonth(), t.getDate())
    const todayDate = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
    const rangeStart = new Date(range.start + 'T00:00:00')
    const rangeEnd = new Date(range.end + 'T23:59:59')
    const includesToday = todayStart >= rangeStart && todayStart <= rangeEnd
    if (!includesToday) return events
    return events.filter((e) =>
      e.all_day ? e.end.slice(0, 10) > todayDate : new Date(e.end) >= todayStart,
    )
  }, [events, mode, range.start, range.end])

  return (
    <section className="card">
      <div className="card-head">
        <h2>Calendar</h2>
        {calendars && calendars.length > 0 && (
          <div className="tabs">
            <button
              className={active === SUMMARY ? 'tab on' : 'tab'}
              onClick={() => setActive(SUMMARY)}
            >
              Summary
            </button>
            {calendars.map((c) => (
              <button
                key={c.id}
                className={active === c.id ? 'tab on' : 'tab'}
                onClick={() => setActive(c.id)}
                title={c.id}
              >
                {c.label}
              </button>
            ))}
          </div>
        )}
      </div>

      {calErr ? (
        <Note err={calErr} />
      ) : (
        <div className="card-scroll">
          <div className="month-nav">
            <button className="btn" onClick={() => shiftMonth(-1)}>
              ‹
            </button>
            <span className="month-title">
              {now.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}
            </span>
            <button className="btn" onClick={() => shiftMonth(1)}>
              ›
            </button>
            <button className="btn ghost" onClick={() => setNow(new Date())}>
              Today
            </button>
            {calendars && calendars.length === 0 && (
              <span className="note">No calendars configured.</span>
            )}
          </div>

          {eventsErr && <p className="note err">{eventsErr.code} — {eventsErr.detail}</p>}
          {loading ? (
            <p className="note">Loading events…</p>
          ) : mode === 'schedule' ? (
            <ScheduleList events={upcoming} />
          ) : (
            <MonthGrid year={year} month={month} events={events} multi={active === SUMMARY} />
          )}
        </div>
      )}
    </section>
  )
}
