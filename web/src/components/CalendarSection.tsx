import { useEffect, useMemo, useState } from 'react'
import { ApiError, api } from '../api'
import type { ApiEvent, CalendarInfo } from '../types'
import { dayKey } from '../utils'
import MonthGrid from './MonthGrid'
import Note from './Note'

const SUMMARY = '__summary__'

export default function CalendarSection() {
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

  // Load the visible calendars once.
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
        <>
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
          ) : (
            <MonthGrid year={year} month={month} events={events} multi={active === SUMMARY} />
          )}
        </>
      )}
    </section>
  )
}
