package com.jsnto.dashhome.ui;

import android.content.Context;
import android.content.Intent;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;

/** A stand-in for the platform's speech recognition service: reports
 *  recognition as available and accepts the session lifecycle calls, so a
 *  test drives the pane's real VoiceInput flow (interim results through
 *  the listener it hands the recognizer). */
@Implements(SpeechRecognizer.class)
public class FakeSpeechRecognizer {

    /** The listener the pane's session registered; null before it starts. */
    public static RecognitionListener listener;

    @Implementation
    public static boolean isRecognitionAvailable(Context context) {
        return true;
    }

    @Implementation
    public static SpeechRecognizer createSpeechRecognizer(Context context) {
        return new android.speech.TestSpeechRecognizer();
    }

    @Implementation
    public void setRecognitionListener(RecognitionListener listener) {
        FakeSpeechRecognizer.listener = listener;
    }

    @Implementation
    public void startListening(Intent intent) {
    }

    @Implementation
    public void stopListening() {
    }

    @Implementation
    public void destroy() {
    }
}
