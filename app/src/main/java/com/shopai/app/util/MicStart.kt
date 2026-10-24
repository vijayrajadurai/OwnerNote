package com.shopai.app.util

/**
 * When the phone's speech service stops the mic at once (owner's video, 23 Oct 2026: tap the mic, "Sollunga / Listening"
 * shows for a moment and closes before a word is said), what the mic does next — kept apart from Android so it is tested on
 * the JVM. The error numbers are Android's `SpeechRecognizer.ERROR_*`.
 */
object MicStart {
    /** An error this soon after the tap, with nothing heard, is the service refusing to start — not the owner's silence. */
    const val EARLY_MS = 2_500L

    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9

    /**
     * Start once more, plainly (no shop words, one guess, a fresh recognizer — as the mic was before 21 Oct 2026), when the
     * first start failed early with nothing heard. Never a second retry, never after the owner spoke, never for a missing
     * permission (a retry can't fix that).
     */
    fun retryPlain(error: Int, attempt: Int, heardSpeech: Boolean, elapsedMs: Long): Boolean =
        attempt == 0 && !heardSpeech && elapsedMs in 0 until EARLY_MS && error != ERROR_INSUFFICIENT_PERMISSIONS

    /** The owner said nothing (or nothing the phone could match): the mic closes quietly, as before. */
    fun silence(error: Int): Boolean = error == ERROR_SPEECH_TIMEOUT || error == ERROR_NO_MATCH

    /** The mic gave up with nothing heard and it wasn't silence: the owner is told, never left wondering. */
    fun tellOwner(error: Int, heardAnything: Boolean): Boolean = !heardAnything && !silence(error)
}
