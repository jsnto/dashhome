package com.jsnto.dashhome.ui;

import android.content.Context;
import android.speech.SpeechRecognizer;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;

/** Reports the platform as having speech recognition (the web's
 *  "supported" state), so the pane's disabled interplay can be tested
 *  without a device. */
@Implements(SpeechRecognizer.class)
public class RecognitionAvailableShadow {
    @Implementation
    public static boolean isRecognitionAvailable(Context context) {
        return true;
    }
}
