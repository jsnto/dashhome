import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiBoard, ApiConfig, ServiceSettings, ServiceSettingsUpdate } from './types'
import { normalizeGrid, normalizeViews } from './utils'
import type { Theme } from './theme'
import SettingsPanel from './components/SettingsPanel'

/** Fixture: HA + push configured, Google configured but awaiting sign-in,
 *  chat off (the same integrations the settings tests use). */
const integrations = {
  homeassistant: true,
  google: {
    configured: true,
    mode: 'oauth',
    login_required: true,
    login_url: 'https://accounts.example.com/login',
  },
  hermes_chat: false,
  hermes_push: true,
}

const board: ApiBoard = { ha_entities: [], ha_labels: {}, calendars: [], refresh_seconds: 30 }
const fixtureConfig: ApiConfig = { board, integrations }

/** The masked service view the stub serves: a few fields set in the UI,
 *  a few set in .env, the rest unset; every secret reduced to set
 *  booleans (no secret value, no matter where it is set). */
const initialServices: ServiceSettings = {
  homeassistant: {
    url: { value: 'https://ha.local', fallback: 'https://ha.env.local', set: true, fallback_set: true },
    token: { value: '', fallback: '', set: false, fallback_set: true },
  },
  google: {
    service_account_file: { value: '', fallback: '/sa.json', set: false, fallback_set: true },
    domain: { value: '', fallback: '', set: false, fallback_set: false },
    client_id: { value: 'dashhome.apps.googleusercontent.com', fallback: '', set: true, fallback_set: false },
    client_secret: { value: '', fallback: '', set: true, fallback_set: false },
    redirect_uri: { value: '', fallback: '', set: false, fallback_set: false },
    token_cache: { value: '', fallback: './data/google-token.json', set: false, fallback_set: true },
    access_token: { value: '', fallback: '', set: false, fallback_set: false },
    endpoint: { value: '', fallback: '', set: false, fallback_set: false },
  },
  hermes_chat: {
    base_url: { value: '', fallback: 'https://hermes.local', set: false, fallback_set: true },
    chat_path: { value: '', fallback: '/chat/completions', set: false, fallback_set: true },
    auth_header: { value: '', fallback: 'Authorization', set: false, fallback_set: true },
    auth_value: { value: '', fallback: '', set: true, fallback_set: false },
  },
  hermes_push: {
    token: { value: '', fallback: '', set: false, fallback_set: true },
  },
}

function stubServicesApi(
  initial: ServiceSettings,
  next: ServiceSettings,
  integrationsAfter: ApiConfig['integrations'],
) {
  let view = initial
  const putCalls: { body: unknown }[] = []
  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    if (url === '/api/config/services' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as ServiceSettingsUpdate
      putCalls.push({ body })
      view = next
      return new Response(
        JSON.stringify({ board, services: next, integrations: integrationsAfter }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    }
    if (url === '/api/config/services') {
      return new Response(JSON.stringify({ services: view }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response(JSON.stringify({ error: 'not_configured' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return { putCalls }
}

/** The service-settings field inputs (password inputs and the entity
 *  picker's combobox are not queryable by the textbox role; the aria-label
 *  is the stable handle). */
function input(name: string): HTMLInputElement {
  const el = document.querySelector(`.settings-content input[aria-label="${name}"]`)
  expect(el).toBeTruthy()
  return el as HTMLInputElement
}

/** The settings-row holding the field with the given label (the status
 *  pill and the Clear affordance are scoped to it). */
function fieldRow(label: string): HTMLElement {
  const row = [...document.querySelectorAll('.settings-row')].find(
    (r) => (r.querySelector('.settings-row-label')?.textContent ?? '') === label,
  )
  expect(row).toBeTruthy()
  return row as HTMLElement
}

function renderServices(onIntegrations?: (i: ApiConfig['integrations']) => void, onBoard?: (b: ApiBoard) => void) {
  const onThemeChange = vi.fn()
  const onClose = vi.fn()
  const view = render(
    <SettingsPanel
      cfg={fixtureConfig}
      theme={'dark' as Theme}
      onThemeChange={onThemeChange}
      views={normalizeViews(null)}
      grid={normalizeGrid(fixtureConfig.board.grid)}
      onBoard={onBoard}
      onIntegrations={onIntegrations}
      onClose={onClose}
    />,
  )
  // Navigate to the Services section.
  fireEvent.click(screen.getByRole('button', { name: 'Services' }))
  // The masked view seeds the forms (an async fetch).
  return { ...view, onIntegrations }
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('settings: service settings (UI > env > default)', () => {
  it('seeds the fields from the masked view: values for non-secrets, set booleans for secrets', async () => {
    stubServicesApi(initialServices, initialServices, integrations)
    renderServices()
    const area = await screen.findByRole('heading', { name: 'Service settings' })
    expect(within(area.parentElement as HTMLElement).getByText('Services in use')).toBeTruthy()

    // A non-secret set in the UI carries its value; the .env fallback shows
    // as the placeholder (what the field would fall back to).
    const url = input('Server URL')
    expect(url.value).toBe('https://ha.local')
    expect(url.placeholder).toBe('https://ha.env.local')

    // A secret set in .env reports the layer, never a value: the input is
    // empty and no Clear affordance exists (the UI set nothing).
    const token = input('Access token')
    expect(token.value).toBe('')
    expect(within(fieldRow('Access token')).getByText('Set in .env')).toBeTruthy()
    expect(within(fieldRow('Access token')).queryByRole('button', { name: 'Clear' })).toBeNull()

    // An .env/default fallback shows as the placeholder for non-secrets.
    const chatPath = input('Chat path')
    expect(chatPath.value).toBe('')
    expect(chatPath.placeholder).toBe('/chat/completions')

    // The unpushed-save starts disabled (nothing changed).
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('saves only the changed fields and re-seeds from the answer', async () => {
    const after: ServiceSettings = {
      ...initialServices,
      homeassistant: {
        url: { value: 'https://ha.new', fallback: 'https://ha.env.local', set: true, fallback_set: true },
        token: initialServices.homeassistant.token,
      },
    }
    const { putCalls } = stubServicesApi(initialServices, after, integrations)
    const onIntegrations = vi.fn()
    const onBoard = vi.fn()
    renderServices(onIntegrations, onBoard)
    await screen.findByRole('heading', { name: 'Service settings' })

    const url = input('Server URL')
    fireEvent.change(url, { target: { value: 'https://ha.new' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(putCalls).toHaveLength(1))
    const body = putCalls[0].body as ServiceSettingsUpdate
    // The changed field is sent; its sibling secret (untouched) is null
    // (kept), and the groups with no changed field are omitted wholesale.
    expect(body.homeassistant?.url).toBe('https://ha.new')
    expect(body.homeassistant?.token).toBe(null)
    expect(body.google).toBeUndefined()
    expect(body.hermes_chat).toBeUndefined()
    expect(body.hermes_push).toBeUndefined()

    // The answer re-seeds the form and reports the effective status.
    const reseeded = input('Server URL')
    expect(reseeded.value).toBe('https://ha.new')
    expect(screen.getByText(/Service settings saved/)).toBeTruthy()
    expect(onIntegrations).toHaveBeenCalledWith(integrations)
    expect(onBoard).toHaveBeenCalledWith(board)
  })

  it('a secret set in the UI shows its status and a Clear back to the .env fallback', async () => {
    const after: ServiceSettings = {
      ...initialServices,
      google: {
        ...initialServices.google,
        client_secret: { value: '', fallback: '', set: false, fallback_set: false },
      },
    }
    const { putCalls } = stubServicesApi(initialServices, after, integrations)
    renderServices()
    await screen.findByRole('heading', { name: 'Service settings' })

    // The client secret is set in the UI (its value never reaches the
    // browser): the status pill and the Clear affordance report that. Two
    // secrets are UI-set in the fixture (the client secret and the Hermes
    // auth value).
    expect(screen.getAllByText('Set in the settings UI')).toHaveLength(2)
    const clear = within(fieldRow('OAuth client secret')).getByRole('button', { name: 'Clear' })
    fireEvent.click(clear)

    // Clear marks the field as the empty value (back to the .env
    // fallback); saving sends the empty string for that field only.
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(putCalls).toHaveLength(1))
    const body = putCalls[0].body as ServiceSettingsUpdate
    expect(body.google?.client_secret).toBe('')
    expect(body.google?.client_id).toBe(null)
    expect(body.homeassistant).toBeUndefined()
  })

  it('reports a load failure without clobbering the status rows', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ error: 'no_board_file' }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderServices()
    // The in-use rows (from the config) still render…
    expect(screen.getByText('Sign-in required')).toBeTruthy()
    // …and the form reports the load failure instead.
    expect(await screen.findByText(/no_board_file/)).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Service settings' })).toBeTruthy()
  })
})
