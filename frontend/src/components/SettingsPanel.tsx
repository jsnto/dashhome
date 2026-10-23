import { useEffect, useRef, useState, type FormEvent, type MouseEvent, type PointerEvent } from 'react'
import { ApiError, api } from '../api'
import { emitRefreshSeconds } from '../refreshBus'
import type { ApiBoard, ApiConfig, BoardViews, GridDims } from '../types'
import type { Theme } from '../theme'
import { normalizeViews } from '../utils'
import SettingsAppearanceSection from './SettingsAppearanceSection'
import SettingsHASection from './SettingsHASection'
import SettingsServicesSection from './SettingsServicesSection'
import SettingsTilesSection from './SettingsTilesSection'
import SettingsUpdatesSection from './SettingsUpdatesSection'

interface Props {
  cfg: ApiConfig | null
  theme: Theme
  onThemeChange: (t: Theme) => void
  /** Effective view layout (defaults already filled in). */
  views?: BoardViews
  /** The board's effective tile grid (cols x rows units), repaired.
   *  (Derived from cfg by the caller; the Tiles section edits it.) */
  grid: GridDims
  /** Adopt a board just persisted server-side (keeps the dashboard in sync). */
  onBoard?: (board: ApiBoard) => void
  /** Adopt the integration status a save just changed (the effective
   *  configuration after the service-settings save). */
  onIntegrations?: (integrations: ApiConfig['integrations']) => void
  onClose: () => void
}

type SectionId = 'appearance' | 'tiles' | 'ha' | 'services' | 'updates'

const SECTIONS: { id: SectionId; label: string }[] = [
  { id: 'appearance', label: 'Appearance' },
  { id: 'tiles', label: 'Tiles' },
  { id: 'ha', label: 'Home Assistant' },
  { id: 'services', label: 'Services' },
  { id: 'updates', label: 'Updates' },
]

/**
 * The settings page: a left sidebar menu navigating the sections
 * (Appearance, Tiles, Home Assistant, Services, Updates). Stays an overlay
 * over the live dashboard; closes from the Close button, a click on the
 * backdrop outside the card, or Escape. Each section is its own component
 * (one file per section): Settings*Section.tsx.
 */
export default function SettingsPanel({
  cfg,
  theme,
  onThemeChange,
  views = normalizeViews(null),
  grid,
  onBoard,
  onIntegrations,
  onClose,
}: Props) {
  const [section, setSection] = useState<SectionId>('appearance')

  // The live-update interval, edited here and persisted server-side. The
  // field adopts the config value only while it still holds the value the
  // config last seeded: the Tiles section's saves refresh the config
  // underneath an open panel (App adopts the answer via onBoard), and
  // that must not clobber an in-progress edit.
  const [seconds, setSeconds] = useState('30')
  const seededRef = useRef('30')
  const [saving, setSaving] = useState(false)
  const [savedMsg, setSavedMsg] = useState<string | null>(null)
  const [saveErr, setSaveErr] = useState<ApiError | null>(null)

  useEffect(() => {
    if (!cfg) return
    const v = String(cfg.board.refresh_seconds || 30)
    if (seconds === seededRef.current) {
      seededRef.current = v
      setSeconds(v)
    }
    // `seconds` is deliberately not a dependency: the field is adopted
    // only on config changes, and re-running on every keystroke would
    // re-seed an untouched field mid-typing.
  }, [cfg])

  const save = async (e: FormEvent) => {
    e.preventDefault()
    const n = Number(seconds)
    if (!Number.isInteger(n)) {
      setSaveErr(new ApiError(0, 'bad_value', 'Enter a whole number of seconds.'))
      return
    }
    setSaving(true)
    setSaveErr(null)
    setSavedMsg(null)
    try {
      const board = await api.setRefreshSeconds(n)
      const applied = board.refresh_seconds || n
      setSeconds(String(applied))
      seededRef.current = String(applied)
      setSavedMsg(`Saved — the Hermes push panel now polls every ${applied} seconds.`)
      // Let the polling panel (Hermes push) re-arm its timer without a reload.
      emitRefreshSeconds(applied)
    } catch (err) {
      setSaveErr(
        err instanceof ApiError ? err : new ApiError(0, 'save_failed', String(err)),
      )
    } finally {
      setSaving(false)
    }
  }

  // Escape closes the page, wherever the focus is.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // A press that starts on the backdrop (the overlay area outside the
  // card) closes the page; a press that starts inside the card keeps it
  // open even when the click lands on the backdrop (a text-selection drag
  // releases its click on the common ancestor, the backdrop).
  const pressOnBackdrop = useRef(false)
  const onBackdropPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    pressOnBackdrop.current = e.target === e.currentTarget
  }
  const onBackdropClick = (e: MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget && pressOnBackdrop.current) onClose()
  }

  return (
    <div className="settings-wrap" onPointerDown={onBackdropPointerDown} onClick={onBackdropClick}>
      <section className="card settings" role="dialog" aria-label="Settings">
        <div className="card-head">
          <h2>Settings</h2>
          <button className="btn ghost small" onClick={onClose}>
            Close
          </button>
        </div>

        <div className="settings-page-body">
          <nav className="settings-nav" aria-label="Settings sections">
            {SECTIONS.map((s) => (
              <button
                key={s.id}
                className={section === s.id ? 'on' : ''}
                aria-current={section === s.id ? 'page' : undefined}
                onClick={() => setSection(s.id)}
              >
                {s.label}
              </button>
            ))}
          </nav>

          <div className="settings-content">
            {section === 'appearance' && (
              <SettingsAppearanceSection theme={theme} onThemeChange={onThemeChange} />
            )}

            {section === 'tiles' && <SettingsTilesSection views={views} grid={grid} onBoard={onBoard} />}

            {section === 'ha' && <SettingsHASection views={views} grid={grid} onBoard={onBoard} />}

            {section === 'services' && (
              <SettingsServicesSection cfg={cfg} onBoard={onBoard} onIntegrations={onIntegrations} />
            )}

            {section === 'updates' && (
              <SettingsUpdatesSection
                seconds={seconds}
                saving={saving}
                savedMsg={savedMsg}
                saveErr={saveErr}
                onSecondsChange={setSeconds}
                onSubmit={save}
              />
            )}
          </div>
        </div>
      </section>
    </div>
  )
}
