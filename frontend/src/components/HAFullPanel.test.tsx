import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import HAFullPanel from './HAFullPanel'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('HAFullPanel', () => {
  it('mounts the iframe with the given URL and nothing else in the way', () => {
    const url = 'https://ha.local/energy?auth_long_lived=tok'
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<HAFullPanel url={url} />)

    const iframe = screen.getByTitle('Home Assistant dashboard') as HTMLIFrameElement
    expect(iframe.src).toBe(url)
    expect(iframe.classList.contains('ha-iframe')).toBeTruthy()

    // The static embed bar (note + buttons) is gone: the panel is just the
    // frame that fills the card body; the buttons live in HAPanel's title
    // row. No embed-URL fetch of its own either.
    expect(screen.queryByText(/Loads Home Assistant directly in this frame/i)).toBeNull()
    expect(document.querySelector('.ha-full-bar')).toBeNull()
    expect(screen.queryByRole('link', { name: /open in new tab/i })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reload' })).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
