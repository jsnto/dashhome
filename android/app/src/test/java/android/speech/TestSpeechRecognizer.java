package android.speech;

/** A concrete recognizer for Robolectric tests: the platform's
 *  SpeechRecognizer has a package-private constructor, so the test
 *  subclass lives in the same package. */
public class TestSpeechRecognizer extends SpeechRecognizer {
}
