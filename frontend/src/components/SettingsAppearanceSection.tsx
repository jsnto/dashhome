import type { Theme } from '../theme'

/**
 * The Appearance section: the theme choice (light/dark). Follows the OS
 * preference until one is picked here; the choice is persisted by the
 * caller (App keeps it in localStorage).
 */
export default function SettingsAppearanceSection({
  theme,
  onThemeChange,
}: {
  theme: Theme
  onThemeChange: (t: Theme) => void
}) {
  return (
    <section className="settings-section">
      <h3>Appearance</h3>
      <div className="settings-row">
        <div>
          <div className="settings-row-label">Theme</div>
          <div className="settings-row-desc">
            Follows your OS preference until you pick one here.
          </div>
        </div>
        <div className="tabs" role="group" aria-label="Theme">
          <button
            className={theme === 'light' ? 'tab on' : 'tab'}
            onClick={() => onThemeChange('light')}
            aria-pressed={theme === 'light'}
          >
            Light
          </button>
          <button
            className={theme === 'dark' ? 'tab on' : 'tab'}
            onClick={() => onThemeChange('dark')}
            aria-pressed={theme === 'dark'}
          >
            Dark
          </button>
        </div>
      </div>
    </section>
  )
}
