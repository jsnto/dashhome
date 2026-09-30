import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatPanel from './ChatPanel'
import type {
  SpeechRecognitionErrorEventLike,
  SpeechRecognitionEventLike,
  SpeechRecognitionLike,
} from '../useVoice'

/** Minimal stand-in for the Web Speech recognition API (Chrome's constructor). */
class MockSpeechRecognition {
  static instances: MockSpeechRecognition[] = []
  continuous = false
  interimResults = false
  onresult: ((e: SpeechRecognitionEventLike) => void) | null = null
  onerror: ((e: SpeechRecognitionErrorEventLike) => void) | null = null
  onend: (() => void) | null = null
  started = false

  constructor() {
    MockSpeechRecognition.instances.push(this)
  }

  start() {
    this.started = true
  }

  stop() {
    this.started = false
    this.onend?.()
  }

  abort() {
    this.started = false
  }

  /** The most recent recognizer the panel created. */
  static get last(): MockSpeechRecognition {
    return MockSpeechRecognition.instances[MockSpeechRecognition.instances.length - 1]
  }
}

function resultEvent(parts: { interim?: string; final?: string }): SpeechRecognitionEventLike {
  const results = [
    ...(parts.interim ? [{ isFinal: false, 0: { transcript: parts.interim } }] : []),
    ...(parts.final ? [{ isFinal: true, 0: { transcript: parts.final } }] : []),
  ]
  return { resultIndex: 0, results }
}

interface SentChat {
  message: string
  conversation: { role: string; content: string }[]
}

/** In-memory stand-in for the backend's POST /api/hermes/chat. */
function stubHermes() {
  const state = { delay: 0 }
  const sent: SentChat[] = []
  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    if (String(input) === '/api/hermes/chat' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as {
        message: string
        conversation?: SentChat['conversation']
      }
      sent.push({ message: body.message, conversation: body.conversation ?? [] })
      if (state.delay) await new Promise((r) => setTimeout(r, state.delay))
      return new Response(JSON.stringify({ reply: `reply to: ${body.message}` }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response(JSON.stringify({ error: 'not_found' }), {
      status: 404,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  return { fetchMock, sent, state }
}

class MockUtterance {
  constructor(public readonly text: string) {}
}

/** Stub window.speechSynthesis + SpeechSynthesisUtterance with call recorders. */
function stubSynthesis() {
  const synth = { speak: vi.fn(), cancel: vi.fn() }
  vi.stubGlobal('SpeechSynthesisUtterance', MockUtterance)
  Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true })
  return synth
}

type WindowWithSpeech = Window & {
  SpeechRecognition?: SpeechRecognitionLike
}

function enableRecognition() {
  ;(window as WindowWithSpeech).SpeechRecognition = MockSpeechRecognition as unknown as SpeechRecognitionLike
}

beforeEach(() => {
  // jsdom does not implement scrollIntoView; ChatPanel calls it on mount.
  Element.prototype.scrollIntoView = () => {}
  MockSpeechRecognition.instances = []
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  delete (window as WindowWithSpeech).SpeechRecognition
  delete (window as unknown as Record<string, unknown>).speechSynthesis
  delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView
})

describe('ChatPanel voice input', () => {
  it('sends a spoken command through the same chat path as typed input', async () => {
    const { fetchMock, sent } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec = MockSpeechRecognition.last
    expect(rec.started).toBe(true)

    rec.onresult?.(resultEvent({ interim: 'turn' }))
    await screen.findByText(/Listening — turn/)

    rec.onresult?.(resultEvent({ final: 'turn on the lights' }))
    await screen.findByText('reply to: turn on the lights')
    expect(sent).toEqual([{ message: 'turn on the lights', conversation: [] }])

    rec.onend?.()
    await waitFor(() => expect(screen.queryByText(/Listening/)).toBeNull())
  })

  it('stops listening when the mic button is clicked again', async () => {
    vi.stubGlobal('fetch', stubHermes().fetchMock)
    enableRecognition()
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    await screen.findByText(/Listening/)

    fireEvent.click(screen.getByRole('button', { name: 'Stop voice input' }))
    await waitFor(() => expect(screen.queryByText(/Listening/)).toBeNull())
  })

  it('ignores a stale onend so a replacement session keeps its live state', async () => {
    const { fetchMock } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec1 = MockSpeechRecognition.last
    await screen.findByText(/Listening/)

    fireEvent.click(screen.getByRole('button', { name: 'Stop voice input' }))
    await waitFor(() => expect(screen.queryByText(/Listening/)).toBeNull())

    // A second session starts before rec1's (asynchronous) onend arrives.
    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec2 = MockSpeechRecognition.last
    expect(rec2).not.toBe(rec1)
    await screen.findByText(/Listening/)

    // The stale onend arrives late; it must not clobber rec2's state.
    rec1.onend?.()

    expect((screen.getByRole('button', { name: 'Stop voice input' }) as HTMLButtonElement).disabled).toBe(false)
    expect(rec2.started).toBe(true)

    // The live session can still be stopped normally.
    fireEvent.click(screen.getByRole('button', { name: 'Stop voice input' }))
    await waitFor(() => expect(screen.queryByText(/Listening/)).toBeNull())
    expect(rec2.started).toBe(false)
  })

  it('reads Hermes replies aloud with speechSynthesis', async () => {
    const { fetchMock } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    const synth = stubSynthesis()
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec = MockSpeechRecognition.last
    rec.onresult?.(resultEvent({ final: 'hello there' }))
    await screen.findByText('reply to: hello there')

    expect(synth.speak).toHaveBeenCalledTimes(1)
    const utterance = synth.speak.mock.calls[0][0]
    expect(utterance).toBeInstanceOf(MockUtterance)
    expect((utterance as MockUtterance).text).toBe('reply to: hello there')
    expect(synth.cancel).toHaveBeenCalledTimes(1)
  })

  it('shares the conversation history between typed and spoken messages', async () => {
    const { fetchMock, sent } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    render(<ChatPanel />)

    fireEvent.change(screen.getByPlaceholderText('Message Hermes…'), { target: { value: 'first question' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByText('reply to: first question')

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec = MockSpeechRecognition.last
    rec.onresult?.(resultEvent({ final: 'second question' }))
    rec.onend?.()
    await screen.findByText('reply to: second question')

    expect(sent[0]).toEqual({ message: 'first question', conversation: [] })
    expect(sent[1]).toEqual({
      message: 'second question',
      conversation: [
        { role: 'user', content: 'first question' },
        { role: 'assistant', content: 'reply to: first question' },
      ],
    })
  })

  it('disables the mic when SpeechRecognition is missing, and typed input still works', async () => {
    const { fetchMock } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    // No window.SpeechRecognition: jsdom never defines it.
    render(<ChatPanel />)

    const mic = screen.getByRole('button', { name: 'Voice input' })
    expect((mic as HTMLButtonElement).disabled).toBe(true)
    expect(mic.title).toBe('Voice input is not supported in this browser')

    fireEvent.change(screen.getByPlaceholderText('Message Hermes…'), { target: { value: 'hi' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByText('reply to: hi')
  })

  it('disables the mic after the microphone is denied, and typed input still works', async () => {
    const { fetchMock } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec = MockSpeechRecognition.last
    rec.onerror?.({ error: 'not-allowed' })
    rec.onend?.()
    await waitFor(() => expect(screen.queryByText(/Listening/)).toBeNull())

    const mic = screen.getByRole('button', { name: 'Voice input' })
    expect((mic as HTMLButtonElement).disabled).toBe(true)
    expect(mic.title).toContain('denied')

    fireEvent.change(screen.getByPlaceholderText('Message Hermes…'), { target: { value: 'hi' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByText('reply to: hi')
  })

  it('still shows the reply when speechSynthesis is unavailable', async () => {
    const { fetchMock } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    // No speechSynthesis stub: jsdom does not implement it.
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec = MockSpeechRecognition.last
    rec.onresult?.(resultEvent({ final: 'quiet hello' }))
    await screen.findByText('reply to: quiet hello')
    expect(screen.getByText('quiet hello')).toBeTruthy()
  })

  it('disables the mic while a reply is in flight, then re-enables it', async () => {
    const { fetchMock, state } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    state.delay = 50
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec = MockSpeechRecognition.last
    rec.onresult?.(resultEvent({ final: 'wait for me' }))

    await screen.findByText('wait for me')
    expect(rec.started).toBe(false)
    expect(screen.queryByText(/Listening/)).toBeNull()
    expect((screen.getByRole('button', { name: 'Voice input' }) as HTMLButtonElement).disabled).toBe(true)

    await screen.findByText('reply to: wait for me')
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Voice input' }) as HTMLButtonElement).disabled).toBe(false),
    )
  })

  it('stops the live recognition session as soon as a send goes out', async () => {
    const { fetchMock, state } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    state.delay = 50
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec = MockSpeechRecognition.last
    await screen.findByText(/Listening/)

    rec.onresult?.(resultEvent({ final: 'turn on the lights' }))

    expect(rec.started).toBe(false)
    await waitFor(() => expect(screen.queryByText(/Listening/)).toBeNull())
    await screen.findByText('reply to: turn on the lights')
    expect(rec.started).toBe(false)
  })

  it('does not read replies aloud for typed messages', async () => {
    const { fetchMock } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    const synth = stubSynthesis()
    render(<ChatPanel />)

    fireEvent.change(screen.getByPlaceholderText('Message Hermes…'), { target: { value: 'typed question' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByText('reply to: typed question')

    expect(synth.speak).not.toHaveBeenCalled()
    expect(synth.cancel).not.toHaveBeenCalled()
  })

  it('neither double-sends nor loses the second spoken final that lands while a reply is in flight', async () => {
    const { fetchMock, state, sent } = stubHermes()
    vi.stubGlobal('fetch', fetchMock)
    enableRecognition()
    state.delay = 50
    render(<ChatPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Voice input' }))
    const rec = MockSpeechRecognition.last
    rec.onresult?.(resultEvent({ final: 'turn on the lights' }))

    // The session was stopped when the first send went out; a late/stale
    // second final arriving before the reply lands must not produce a
    // second request or a second log entry.
    rec.onresult?.(resultEvent({ final: 'and open the blinds' }))

    // The visible busy state is the user-visible outcome, mirroring typed input.
    await screen.findByText('thinking…')
    expect(sent).toEqual([{ message: 'turn on the lights', conversation: [] }])
    expect(screen.queryByText('and open the blinds')).toBeNull()

    await screen.findByText('reply to: turn on the lights')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('and open the blinds')).toBeNull()
  })
})
