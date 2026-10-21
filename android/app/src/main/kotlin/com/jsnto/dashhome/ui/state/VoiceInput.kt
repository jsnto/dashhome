package com.jsnto.dashhome.ui.state

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Speech-to-text + read-aloud for the Hermes chat - the mobile counterpart
 * of the web's useVoice (browser SpeechRecognition + speechSynthesis).
 *
 * One short recognition session at a time: interim results are surfaced
 * live ([interim]) and the final transcript fires [onFinal] - which feeds
 * the existing shared send path (POST /api/hermes/chat, the same
 * conversation history as typed messages). Degrades gracefully, like the
 * web: when the platform has no recognition service the mic is simply
 * unsupported ([supported] = false); when the mic is denied or missing,
 * [blocked] carries the reason; transient errors (no-speech, aborted,
 * network) are ignored and the session simply ends.
 *
 * The state fields are Compose snapshot state, so the chat pane
 * re-renders as the transcript arrives.
 */
class VoiceInput(private val context: Context, private val onFinal: (String) -> Unit) {

    /** False when the platform has no speech recognition (the web's "unsupported" mic). */
    val supported: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** True while a recognition session is live. */
    var listening by mutableStateOf(false)

    /** The live (not-final) transcript while listening. */
    var interim by mutableStateOf("")

    /** Why the mic is blocked (denied, no device); null when it can be used. */
    var blocked by mutableStateOf<String?>(null)

    private var rec: SpeechRecognizer? = null

    /** Whether the RECORD_AUDIO permission is currently granted. */
    fun permissionGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts a recognition session (the web's `start`): one short command,
     * interim results on. A session already running (or an unavailable
     * platform) is a no-op.
     */
    fun start() {
        if (rec != null || !supported) return
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            blocked = "Voice recognition is unavailable on this device"
            return
        }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        rec = r
        listening = true
        interim = ""
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            // e.g. IllegalStateException when a session is already running.
            rec = null
            listening = false
            release(r, cancel = false)
        }
    }

    /** Stops a live session (the web's `stop`): cancels the recognition -
     *  no transcript is sent. */
    fun stop() {
        val r = rec ?: return
        rec = null
        listening = false
        interim = ""
        release(r, cancel = true)
    }

    /** Toggles a session: stop the live one, otherwise start (the web's `toggle`). */
    fun toggle() {
        if (rec != null) stop() else start()
    }

    /** Stops and releases any live session (the web's unmount cleanup). */
    fun destroy() {
        val r = rec ?: return
        rec = null
        listening = false
        interim = ""
        release(r, cancel = true)
    }

    /**
     * Releases a recognizer instance: stops the recognition when [cancel]
     * (a user stop / unmount - no transcript is sent), and always destroys
     * the instance. Never throws. (The platform has no cancel(): a stop
     * plus destroy discards the session; any callback that fires after
     * [rec] is nulled is dropped by the listener's null guard.)
     */
    private fun release(r: SpeechRecognizer, cancel: Boolean) {
        if (cancel) {
            try {
                r.stopListening()
            } catch (e: Exception) {
                // already stopped
            }
        }
        try {
            r.destroy()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** The recognizer callbacks (the web's onresult/onerror/onend). */
    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(p0: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onBufferReceived(p0: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        /** Interim (not-final) results: the live transcript line. */
        override fun onPartialResults(p0: Bundle?) {
            if (rec == null) return
            transcriptOf(p0)?.let { if (it.isNotBlank()) interim = it }
        }

        /** The final transcript: sent through the shared send path. */
        override fun onResults(p0: Bundle?) {
            val r = rec ?: return
            rec = null
            listening = false
            interim = ""
            val text = transcriptOf(p0)?.trim().orEmpty()
            release(r, cancel = false)
            if (text.isNotEmpty()) onFinal(text)
        }

        /** Recognition errors: blocking ones explain themselves; the
         *  rest are transient (the web ignores them). The platform has
         *  already ended the session; only the instance is released. */
        override fun onError(p0: Int) {
            val reason = voiceBlockReason(p0)
            if (reason != null) blocked = reason
            val r = rec ?: return
            rec = null
            listening = false
            interim = ""
            release(r, cancel = false)
        }

        override fun onRmsChanged(p0: Float) = Unit

        override fun onEvent(p0: Int, p1: Bundle?) = Unit
    }

    /** The transcript of a results bundle (final or partial). */
    private fun transcriptOf(bundle: Bundle?): String? {
        bundle ?: return null
        return bundle
            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }
    }
}

/**
 * Why the mic is blocked, from a platform recognition error (the web's
 * blockReason taxonomy): denied access and no device are blocking (the
 * button is disabled with the reason); everything else is transient (no
 * speech, aborted, network) and simply ends the session.
 */
fun voiceBlockReason(errorCode: Int): String? = when (errorCode) {
    SpeechRecognizer.ERROR_AUDIO ->
        "No microphone is available - connect one to use voice commands"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        VOICE_DENIED_REASON
    else -> null
}

/** The reason shown when the mic is denied (permission or runtime). */
const val VOICE_DENIED_REASON =
    "Microphone access was denied - allow the mic in the app to use voice commands"

/**
 * Best-effort speech synthesis (the web's `speak`): reads [text] aloud
 * when the platform supports it. Never throws - the visible reply
 * suffices when synthesis is missing.
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var pending: String? = null

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    pending?.let { p ->
                        pending = null
                        speak(p)
                    }
                }
            }
        } catch (e: Exception) {
            tts = null
        }
    }

    /** Reads [text] aloud (stopping anything in flight, like the web). */
    fun speak(text: String) {
        val t = tts ?: run {
            pending = text
            return
        }
        try {
            t.stop()
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-reply")
        } catch (e: Exception) {
            // synthesis is best-effort only
        }
    }

    /** Releases the engine (the chat pane leaving composition). */
    fun shutdown() {
        pending = null
        val t = tts ?: return
        tts = null
        try {
            t.stop()
            t.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }
}
