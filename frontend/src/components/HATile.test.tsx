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
