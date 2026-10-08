package com.shopai.app.ui.reminder

import android.util.Log
import com.shopai.app.data.tts.NaturalTtsSpeaker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Kai's reminder voice through the app's existing natural TTS (Sarvam proxy, device TTS fallback). */
class NaturalVoiceOut(private val speaker: NaturalTtsSpeaker) : KaiVoiceOut {

    override suspend fun say(text: String, languageCode: String) = suspendCancellableCoroutine { cont ->
        speaker.speakNatural(text, languageCode = languageCode, fallbackText = text, fallbackLanguage = languageCode, onDone = {
            // Which voice spoke — "sarvam" is the natural one; "device" means the robotic fallback (and why).
            Log.i("KaiReminder", "voice engine=${speaker.lastEngine}" + (speaker.lastProxyProblem?.let { " (natural voice not used: $it)" } ?: ""))
            if (cont.isActive) cont.resume(Unit)
        })
    }

    override suspend fun sayTurn(parts: List<String>, gapsMs: List<Long>, languageCode: String) = suspendCancellableCoroutine { cont ->
        speaker.speakTurn(parts, gapsMs, languageCode = languageCode, onDone = {
            Log.i("KaiReminder", "voice turn engine=${speaker.lastEngine}" + (speaker.lastProxyProblem?.let { " (natural voice not used: $it)" } ?: ""))
            if (cont.isActive) cont.resume(Unit)
        })
    }

    override fun hush() = speaker.stop()
}
