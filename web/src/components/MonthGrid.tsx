import { useMemo, useState } from 'react'
import type { ApiEvent } from '../types'

const PALETTE = [
  '#4cc2ff',
  '#ffb84c',
  '#8de04c',
  '#ff6b81',
  '#c792ea',
  '#ffd166',
  '#7bdff2',
  '#f78c6c',
  '#95e6c9',
  '#d7a7ff',
]

const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function shortLabel(label: string): string {
  const word = (label.split(/\s+/)[0] || label).slice(0, 9)
  return label ? `${word} · ` : ''
}

function dayKey(d: Date): string {
  const m = `${d.getMonth() + 1}`.padStart(2, '0')
  const day = `${d.getDate()}`.padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function fmtWhen(e: ApiEvent): string {
  const d = new Date(e.start)
  if (e.all_day) {
    return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  }
  return d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

function fmtWhenEnd(e: ApiEvent): string {
  const d = new Date(e.end)
  if (e.all_day) {
    // all-day events are exclusive on the end date
    return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  }
  return d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

interface Cell {
  date: Date
  inMonth: boolean
}

interface Props {
  year: number
  month: number // 0-based
  events: ApiEvent[]
  multi: boolean // show per-calendar labels (summary view)
}

export default function MonthGrid({ year, month, events, multi }: Props) {
  const [selected, setSelected] = useState<string | null>(null)

  const calOrder = useMemo(() => {
    const seen: string[] = []
    for (const e of events) {
      if (!seen.includes(e.calendar_id)) seen.push(e.calendar_id)
    }
    return seen
  }, [events])

  const color = (e: ApiEvent): string => {
    const i = calOrder.indexOf(e.calendar_id)
    return PALETTE[(i < 0 ? 0 : i) % PALETTE.length]
  }

  const byDay = useMemo(() => {
    const m = new Map<string, ApiEvent[]>()
    for (const e of events) {
      const k = dayKey(new Date(e.start))
      const arr = m.get(k) || []
      arr.push(e)
      m.set(k, arr)
    }
    return m
  }, [events])

  const cells = useMemo<Cell[]>(() => {
    const first = new Date(year, month, 1)
    const lead = first.getDay()
    const out: Cell[] = []
    for (let i = 0; i < 42; i++) {
      const d = new Date(year, month, 1 - lead + i)
      out.push({ date: d, inMonth: d.getMonth() === month })
    }
    return out
  }, [year, month])

  const todayKey = dayKey(new Date())

  return (
    <div className="month">
      <div className="month-dow">
        {DOW.map((d) => (
          <span key={d}>{d}</span>
        ))}
      </div>
      <div className="month-grid">
        {cells.map((c, i) => {
          const k = dayKey(c.date)
          const evs = byDay.get(k) || []
          return (
            <div
              key={i}
              className={[
                'cell',
                c.inMonth ? '' : 'out',
                k === todayKey ? 'today' : '',
                k === selected ? 'sel' : '',
              ]
                .filter(Boolean)
                .join(' ')}
              onClick={() => setSelected(k === selected ? null : k)}
            >
              <span className="cell-day">{c.date.getDate()}</span>
              {evs.slice(0, 3).map((e) => (
                <span key={`${e.calendar_id}-${e.id}`} className="chip" style={{ background: color(e) }}>
                  {multi ? shortLabel(e.calendar_label) : ''}
                  {fmtWhen(e)} {e.summary || '(no title)'}
                </span>
              ))}
              {evs.length > 3 && <span className="more">+{evs.length - 3} more</span>}
            </div>
          )
        })}
      </div>

      {selected && (
        <div className="day-detail">
          <h4>{new Date(selected).toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}</h4>
          {(byDay.get(selected) || []).length === 0 && <p className="note">No events.</p>}
          <ul>
            {(byDay.get(selected) || []).map((e) => (
              <li key={`${e.calendar_id}-${e.id}`}>
                {multi && (
                  <span className="chip inline" style={{ background: color(e) }}>
                    {e.calendar_label}
                  </span>
                )}
                <b>{e.summary || '(no title)'}</b>
                <span className="when">
                  {fmtWhen(e)}
                  {(!e.all_day || new Date(e.end).getDate() !== new Date(e.start).getDate()) &&
                    ` – ${fmtWhenEnd(e)}`}
                </span>
                {e.location && <em> @ {e.location}</em>}
                {e.description && <p className="desc">{e.description}</p>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
