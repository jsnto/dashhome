/**
 * Tiny in-page bus: when the settings screen saves a new polling interval,
 * the panels re-arm their setInterval to the new cadence without a reload.
 */
export const REFRESH_SECONDS_EVENT = 'dashhome:refresh-seconds'

export function emitRefreshSeconds(seconds: number): void {
  window.dispatchEvent(new CustomEvent<number>(REFRESH_SECONDS_EVENT, { detail: seconds }))
}

export function onRefreshSeconds(handler: (seconds: number) => void): () => void {
  const listener = (e: Event) => {
    const d = (e as CustomEvent<number>).detail
    if (typeof d === 'number' && Number.isFinite(d) && d > 0) {
      handler(d)
    }
  }
  window.addEventListener(REFRESH_SECONDS_EVENT, listener)
  return () => window.removeEventListener(REFRESH_SECONDS_EVENT, listener)
}
