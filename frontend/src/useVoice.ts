import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Web Speech recognition types, declared structurally: Chrome ships the API
 * as `SpeechRecognition`/`webkitSpeechRecognition`, which TypeScript's
 * lib.dom does not declare.
 */
export interface SpeechRecognitionResult {
  readonly isFinal: boolean
  readonly 0: { readonly transcript: string }
}

export interface SpeechRecognitionResultList {
  readonly length: number
  readonly [index: number]: SpeechRecognitionResult
}

export interface SpeechRecognitionEventLike {
  readonly resultIndex: number
  readonly results: SpeechRecognitionResultList
}

export interface SpeechRecognitionErrorEventLike {
  readonly error: string
}

export interface SpeechRecognitionLike {
  continuous: boolean
  interimResults: boolean
  onresult: ((event: SpeechRecognitionEventLike) => void) | null
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}

type SpeechRecognitionCtor = new () => SpeechRecognitionLike

/** The (vendor-prefixed) recognition constructor, if this browser has one. */
function recognitionCtor(): SpeechRecognitionCtor | undefined {
  const w = window as Window & {
    SpeechRecognition?: SpeechRecognitionCtor
    webkitSpeechRecognition?: SpeechRecognitionCtor
  }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition
}

/**
 * Mic errors that mean voice input cannot work at all (and why), used as the
 * disabled-button title. Transient errors (no-speech, aborted, network) are
 * ignored: the session simply ends and the mic stays available.
 */
function blockReason(error: string): string | null {
  switch (error) {
    case 'not-allowed':
    case 'service-not-allowed':
      return 'Microphone access was denied — allow the mic in the browser to use voice commands'
    case 'audio-capture':
      return 'No microphone is available — connect one to use voice commands'
    default:
      return null
  }
}

export interface Voice {
  /** false when the browser has no SpeechRecognition API. */
  supported: boolean
  listening: boolean
  /** Live (not-final) transcript while listening. */
  interim: string
  /** Why the mic is blocked (denied, no device); null when it can be used. */
  blocked: string | null
  start(): void
  stop(): void
  toggle(): void
}

/**
 * Browser speech-to-text for a single short command: one recognition session,
 * interim results surfaced live, and `onFinal` fired with the final
 * transcript. Degrades gracefully: when recognition is missing the mic is
 * simply unsupported, and when the mic is denied the hook reports why.
 */
export function useVoice(onFinal: (text: string) => void): Voice {
  const [listening, setListening] = useState(false)
  const [interim, setInterim] = useState('')
  const [blocked, setBlocked] = useState<string | null>(null)
  const recRef = useRef<SpeechRecognitionLike | null>(null)
  const onFinalRef = useRef(onFinal)
  useEffect(() => {
    onFinalRef.current = onFinal
  }, [onFinal])

  const supported = recognitionCtor() !== undefined

  const stop = useCallback(() => {
    const rec = recRef.current
    recRef.current = null
    setListening(false)
    setInterim('')
    if (rec) {
      try {
        rec.stop()
      } catch {
        /* already stopped */
      }
    }
  }, [])

  const start = useCallback(() => {
    if (recRef.current) return
    const Ctor = recognitionCtor()
    if (!Ctor) return
    let rec: SpeechRecognitionLike
    try {
      rec = new Ctor()
    } catch {
      setBlocked('Voice recognition is unavailable in this browser')
      return
    }
    rec.continuous = false
    rec.interimResults = true
    rec.onresult = (event) => {
      let inter = ''
      let fin = ''
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const r = event.results[i]
        const transcript = r[0]?.transcript ?? ''
        if (r.isFinal) fin += transcript
        else inter += transcript
      }
      if (inter) setInterim(inter)
      const text = fin.trim()
      if (text) onFinalRef.current(text)
    }
    rec.onerror = (event) => {
      setBlocked(blockReason(event.error))
    }
    rec.onend = () => {
      recRef.current = null
      setListening(false)
      setInterim('')
    }
    recRef.current = rec
    setListening(true)
    setInterim('')
    try {
      rec.start()
    } catch {
      // e.g. InvalidStateError when a session is already running.
      recRef.current = null
      setListening(false)
    }
  }, [])

  const toggle = useCallback(() => {
    if (recRef.current) stop()
    else start()
  }, [start, stop])

  // Stop any live session when the panel goes away.
  useEffect(
    () => () => {
      const rec = recRef.current
      recRef.current = null
      if (rec) {
        try {
          rec.abort()
        } catch {
          /* ignore */
        }
      }
    },
    [],
  )

  return { supported, listening, interim, blocked, start, stop, toggle }
}

/**
 * Best-effort speech synthesis: reads `text` aloud when the browser supports
 * it. Never throws — the visible reply suffices when synthesis is missing.
 */
export function speak(text: string): void {
  try {
    const synth = window.speechSynthesis
    if (!synth || typeof SpeechSynthesisUtterance === 'undefined') return
    synth.cancel()
    synth.speak(new SpeechSynthesisUtterance(text))
  } catch {
    /* synthesis is best-effort only */
  }
}
