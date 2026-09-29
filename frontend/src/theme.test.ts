import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  applyTheme,
  initialTheme,
  osTheme,
  readStoredTheme,
  storeTheme,
  THEME_STORAGE_KEY,
} from './theme'
import { useTheme } from './useTheme'

/** Stub window.matchMedia with the given OS preference. */
function stubOS(os: 'light' | 'dark') {
  const addEventListener = vi.fn()
  const removeEventListener = vi.fn()
  const mql = {
    matches: os === 'light',
    addEventListener,
    removeEventListener,
  } as unknown as MediaQueryList
  window.matchMedia = vi.fn(() => mql)
  return { mql, addEventListener }
}

describe('theme helpers', () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    cleanup()
    document.documentElement.removeAttribute('data-theme')
  })

  it('defaults to the OS preference', () => {
    stubOS('light')
    expect(osTheme()).toBe('light')
    expect(initialTheme()).toBe('light')
    stubOS('dark')
    expect(osTheme()).toBe('dark')
    expect(initialTheme()).toBe('dark')
  })

  it('falls back to dark when matchMedia is unavailable', () => {
    delete (window as unknown as { matchMedia?: unknown }).matchMedia
    expect(osTheme()).toBe('dark')
  })

  it('persists the user choice, which wins over the OS default', () => {
    stubOS('light')
    storeTheme('dark')
    expect(readStoredTheme()).toBe('dark')
    expect(initialTheme()).toBe('dark')
    // Garbage in storage is treated as "no choice".
    localStorage.setItem(THEME_STORAGE_KEY, 'auto')
    expect(readStoredTheme()).toBeNull()
    expect(initialTheme()).toBe('light')
  })

  it('applyTheme sets the data-theme attribute', () => {
    applyTheme('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    applyTheme('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })
})

describe('useTheme', () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    cleanup()
    document.documentElement.removeAttribute('data-theme')
  })

  it('starts from the OS preference, applies it, and persists a user pick', () => {
    stubOS('dark')
    const { result, unmount } = renderHook(() => useTheme())
    expect(result.current[0]).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

    act(() => {
      result.current[1]('light')
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')

    // A fresh mount reads the persisted choice, not the OS preference.
    unmount()
    const again = renderHook(() => useTheme())
    expect(again.result.current[0]).toBe('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    again.unmount()
  })

  it('follows OS changes only until the user makes a pick', () => {
    const { addEventListener } = stubOS('dark')
    const { result, unmount } = renderHook(() => useTheme())
    expect(result.current[0]).toBe('dark')

    const handler = addEventListener.mock.calls[0][1] as (e: MediaQueryListEvent) => void
    // No user choice yet: the OS flipping to light is followed.
    act(() => {
      handler({ matches: true } as MediaQueryListEvent)
    })
    expect(result.current[0]).toBe('light')

    // User picks dark: later OS flips are ignored.
    act(() => {
      result.current[1]('dark')
    })
    act(() => {
      handler({ matches: true } as MediaQueryListEvent)
    })
    expect(result.current[0]).toBe('dark')
    unmount()
  })
})
