package com.shopai.app.ui.reminder

import com.shopai.app.data.tts.NaturalTtsSpeaker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Kai's reminder voice through the app's existing natural TTS (Sarvam proxy, device TTS fallback). */
class NaturalVoiceOut(private val speaker: NaturalTtsSpeaker) : KaiVoiceOut {

    override suspend fun say(text: String, languageCode: String) = suspendCancellableCoroutine { cont ->
        speaker.speakNatural(text, languageCode = languageCode, fallbackText = text, fallbackLanguage = languageCode, onDone = {
            if (cont.isActive) cont.resume(Unit)
        })
    }

    override fun hush() = speaker.stop()
}
