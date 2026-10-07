import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { HABoardTile } from '../types'
import HATile from './HATile'

const tile: HABoardTile = { id: 'ha-1', entity: 'light.kitchen', kind: 'visual' }

afterEach(() => {
  cleanup()
})

describe('HATile visual', () => {
  it('renders no timestamp for an entity without a last-changed value', () => {
    render(
      <HATile
        tile={tile}
        entity={{
          entity_id: 'light.kitchen',
          state: 'unavailable',
          label: '',
          friendly: '',
          attributes: {},
        }}
        haOff={false}
        onService={() => {}}
      />,
    )
    expect(screen.queryByText('Invalid Date')).toBeNull()
    expect(document.querySelector('.ha-updated')).toBeNull()
  })

  it('renders the updated time for a valid last-changed value', () => {
    render(
      <HATile
        tile={tile}
        entity={{
          entity_id: 'light.kitchen',
          state: 'on',
          label: '',
          friendly: '',
          attributes: {},
          last_changed: '2024-06-01T08:30:00Z',
        }}
        haOff={false}
        onService={() => {}}
      />,
    )
    expect(screen.queryByText('Invalid Date')).toBeNull()
    expect(document.querySelector('.ha-updated')?.textContent).toMatch(/\d{1,2}:\d{2}/)
  })
})

describe('HATile button (toggle wears the switch state)', () => {
  const buttonTile: HABoardTile = { id: 'ha-2', entity: 'light.kitchen', kind: 'button' }

  const renderButton = (state: string) =>
    render(
      <HATile
        tile={buttonTile}
        entity={{
          entity_id: 'light.kitchen',
          state,
          label: 'Kitchen',
          friendly: '',
          attributes: {},
        }}
        haOff={false}
        onService={() => {}}
      />,
    )

  it('wears the on (dark) style and offers the turn-off while the switch is on', () => {
    renderButton('on')
    const btn = screen.getByRole('button', { name: 'Turn off' })
    expect(btn.className).toContain('ha-action-on')
    expect(btn.className).not.toContain('ha-action-off')
  })

  it('wears the off (bright) style and offers the turn-on while the switch is off', () => {
    renderButton('off')
    const btn = screen.getByRole('button', { name: 'Turn on' })
    expect(btn.className).toContain('ha-action-off')
    expect(btn.className).not.toContain('ha-action-on')
  })

  it('keeps the neutral style for a state that is neither on nor off', () => {
    renderButton('unavailable')
    const btn = screen.getByRole('button', { name: 'Turn on' })
    expect(btn.className).toBe('ha-action')
  })
})
