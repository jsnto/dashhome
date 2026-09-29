import { useCallback, useEffect, useState } from 'react'
import {
  applyTheme,
  initialTheme,
  readStoredTheme,
  storeTheme,
  type Theme,
} from './theme'

/**
 * Light/dark theme state. The value starts from the persisted choice (or the
 * OS preference), is applied to <html> on every change, and a user pick is
 * persisted. While the user has never picked, OS preference changes are
 * followed live.
 */
export function useTheme(): [Theme, (t: Theme) => void] {
  const [theme, setThemeState] = useState<Theme>(initialTheme)

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  const setTheme = useCallback((t: Theme) => {
    storeTheme(t)
    setThemeState(t)
  }, [])

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const mq = window.matchMedia('(prefers-color-scheme: light)')
    const onChange = (e: MediaQueryListEvent) => {
      // Only follow the OS when the user has not made an explicit choice.
      if (!readStoredTheme()) setThemeState(e.matches ? 'light' : 'dark')
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  return [theme, setTheme]
}

