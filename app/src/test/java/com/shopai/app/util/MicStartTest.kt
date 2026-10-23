package com.shopai.app.util

import com.shopai.app.util.MicStart.ERROR_CLIENT
import com.shopai.app.util.MicStart.ERROR_INSUFFICIENT_PERMISSIONS
import com.shopai.app.util.MicStart.ERROR_NETWORK
import com.shopai.app.util.MicStart.ERROR_NO_MATCH
import com.shopai.app.util.MicStart.ERROR_RECOGNIZER_BUSY
import com.shopai.app.util.MicStart.ERROR_SPEECH_TIMEOUT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's video (23 Oct 2026): tap the mic in Kai Chat, "Sollunga / Listening…" shows for a moment and closes before a
 * word is said. The phone's speech service stopped the start at once; the mic now starts once more plainly, and tells the
 * owner when it still can't — the same for every word typed or spoken after it (the mic is how Kai hears the owner).
 */
class MicStartTest {

    @Test fun anEarlyErrorWithNothingHeardStartsOnceMorePlainly() {
        listOf(ERROR_CLIENT, ERROR_RECOGNIZER_BUSY, ERROR_NETWORK, 12, 13).forEach { e ->
            assertTrue("error $e", MicStart.retryPlain(e, attempt = 0, heardSpeech = false, elapsedMs = 150))
        }
    }

    @Test fun neverASecondRetry() {
        assertFalse(MicStart.retryPlain(ERROR_CLIENT, attempt = 1, heardSpeech = false, elapsedMs = 150))
    }

    @Test fun neverAfterTheOwnerSpokeOrWasSilentAWhile() {
        assertFalse(MicStart.retryPlain(ERROR_NO_MATCH, attempt = 0, heardSpeech = true, elapsedMs = 400))
        // The owner said nothing for five seconds: that is silence, not a refused start.
        assertFalse(MicStart.retryPlain(ERROR_SPEECH_TIMEOUT, attempt = 0, heardSpeech = false, elapsedMs = 5_200))
        assertFalse(MicStart.retryPlain(ERROR_CLIENT, attempt = 0, heardSpeech = false, elapsedMs = MicStart.EARLY_MS))
    }

    @Test fun aMissingPermissionIsNotRetried() {
        assertFalse(MicStart.retryPlain(ERROR_INSUFFICIENT_PERMISSIONS, attempt = 0, heardSpeech = false, elapsedMs = 100))
    }

    @Test fun silenceClosesQuietlyAnythingElseIsSaid() {
        assertFalse(MicStart.tellOwner(ERROR_SPEECH_TIMEOUT, heardAnything = false))
        assertFalse(MicStart.tellOwner(ERROR_NO_MATCH, heardAnything = false))
        assertTrue(MicStart.tellOwner(ERROR_CLIENT, heardAnything = false))
        assertTrue(MicStart.tellOwner(ERROR_RECOGNIZER_BUSY, heardAnything = false))
        // Words were heard: they go to Kai, no message.
        assertFalse(MicStart.tellOwner(ERROR_CLIENT, heardAnything = true))
    }

    /** Android's own numbers (SpeechRecognizer.ERROR_*), so the rule reads the phone right. */
    @Test fun errorNumbersAreAndroids() {
        assertTrue(ERROR_CLIENT == 5 && ERROR_SPEECH_TIMEOUT == 6 && ERROR_NO_MATCH == 7 && ERROR_RECOGNIZER_BUSY == 8 && ERROR_INSUFFICIENT_PERMISSIONS == 9)
    }
}
