/**
 * Theme (light/dark) helpers. The theme is a CSS-variable switch on a
 * `data-theme` attribute on <html>; the user's choice persists in
 * localStorage and the OS preference is the default.
 */
export type Theme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'dashhome-theme'

/** Read the persisted user choice, or null when the user never picked. */
export function readStoredTheme(): Theme | null {
  try {
    const t = localStorage.getItem(THEME_STORAGE_KEY)
    return t === 'light' || t === 'dark' ? t : null
  } catch {
    return null
  }
}

/** Persist the user's choice. */
export function storeTheme(theme: Theme): void {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme)
  } catch {
    /* storage unavailable (private mode); the choice just won't persist */
  }
}

/** The OS preferred theme. Falls back to dark when matchMedia is absent. */
export function osTheme(): Theme {
  if (typeof window.matchMedia !== 'function') return 'dark'
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

/** Persisted choice wins; otherwise follow the OS. */
export function initialTheme(): Theme {
  return readStoredTheme() ?? osTheme()
}

/** Apply the theme to the document root immediately. */
export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute('data-theme', theme)
}
