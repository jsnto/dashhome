import { useCallback, useEffect, useState } from 'react'

/**
 * Browser full-screen toggle for the dashboard. The icon reflects the
 * current state (swaps while full-screen); if the browser refuses (no user
 * gesture, unsupported, or policy), the toggle is a graceful no-op.
 */
export default function FullscreenButton() {
  const [isFullscreen, setIsFullscreen] = useState(() => Boolean(document.fullscreenElement))

  useEffect(() => {
    const onChange = () => setIsFullscreen(Boolean(document.fullscreenElement))
    document.addEventListener('fullscreenchange', onChange)
    return () => document.removeEventListener('fullscreenchange', onChange)
  }, [])

  const toggle = useCallback(async () => {
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen()
      } else if (document.documentElement.requestFullscreen) {
        await document.documentElement.requestFullscreen()
      }
      // State is reconciled by the fullscreenchange listener.
    } catch {
      /* browser refused: no-op */
    }
  }, [])

  return (
    <button
      className={`btn ghost small fs-btn ${isFullscreen ? 'on' : ''}`}
      onClick={toggle}
      title={isFullscreen ? 'Exit full screen' : 'Full screen'}
      aria-label={isFullscreen ? 'Exit full screen' : 'Enter full screen'}
      aria-pressed={isFullscreen}
    >
      {isFullscreen ? (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M9 3v3a3 3 0 0 1-3 3H3M21 9h-3a3 3 0 0 1-3-3V3M3 15h3a3 3 0 0 1 3 3v3M15 21v-3a3 3 0 0 1 3-3h3" />
        </svg>
      ) : (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3" />
        </svg>
      )}
      <span className="fs-label">{isFullscreen ? 'Exit' : 'Full screen'}</span>
    </button>
  )
}
