package com.shopai.app.ui.reminder

import com.shopai.app.brain.tools.KaiUrgentVoiceScript
import com.shopai.app.brain.tools.VoiceLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where Kai's voice goes (the app's natural TTS; a fake in tests). */
interface KaiVoiceOut {
    /** Speaks [text] and returns when it has finished (or was hushed). */
    suspend fun say(text: String, languageCode: String)

    /** Stops what is playing now and anything still being prepared — nothing queued survives. */
    fun hush()
}

/**
 * Kai's reminder voice while a reminder rings — exactly one loop in the app:
 *
 *  - [start] for a ring that is already being spoken does nothing (rotation, recomposition, resume,
 *    a second notification tap): one voice, never two, never the opening again.
 *  - [pause] (the screen went away unanswered — Home, power button, the lock screen) stops the voice;
 *    coming back continues with the next line, after a pause, not from the top.
 *  - [answer] (Call Now / Done / Snooze) stops at once — the line being spoken is cut, nothing queued
 *    plays — says one short answer, and that ring never speaks again.
 *  - A new ring (the next attempt) starts its own cycle.
 *
 * [cue] tells Kai's body when a line starts and ends (in [now]'s clock) so he moves with his voice.
 */
class KaiUrgentVoice(
    private val scope: CoroutineScope,
    private val out: KaiVoiceOut,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val _cue = MutableStateFlow<SpeechCue?>(null)
    val cue: StateFlow<SpeechCue?> = _cue.asStateFlow()

    private var ring: String? = null
    private var job: Job? = null
    /** Per ring: the next utterance number (so a paused cycle continues). */
    private val next = HashMap<String, Int>()
    /** Rings the owner answered: they never speak again. */
    private val answered = HashSet<String>()

    val speakingRing: String? get() = ring?.takeIf { job?.isActive == true }

    /**
     * Starts (or keeps) the reminder voice for [ringKey]. [openingAlreadySpoken]: this ring's opening
     * line was already said (e.g. before a process restart) — the cycle begins with a follow-up.
     * Returns true when a loop was started now.
     */
    fun start(ringKey: String, lines: List<VoiceLine>, languageCode: String, openingAlreadySpoken: Boolean = false): Boolean {
        if (lines.isEmpty() || ringKey in answered) return false
        if (ring == ringKey && job?.isActive == true) return false
        if (job?.isActive == true) stopLoop("another ring ($ringKey)")
        ring = ringKey
        val from = next[ringKey] ?: if (openingAlreadySpoken) 1 else 0
        log("voice loop start $ringKey at line $from")
        job = scope.launch {
            var n = from
            while (isActive) {
                // The opening right away; every other line after its pause (also when coming back).
                val pause = if (n == 0) 0L else KaiUrgentVoiceScript.pauseBefore(n)
                if (pause > 0) delay(pause)
                val line = lines[KaiUrgentVoiceScript.lineAt(n, lines.size)]
                next[ringKey] = n + 1
                val mine = SpeechCue(startedAt = now(), gesture = line.gesture)
                _cue.value = mine
                log("voice line $n $ringKey: ${line.text}")
                try {
                    // A voice that never reports back (stopped elsewhere) can't stall the reminder.
                    withTimeoutOrNull(LINE_TIMEOUT_MS) { out.say(line.text, languageCode) }
                } finally {
                    endCue(mine)
                }
                n++
            }
        }
        return true
    }

    /** The screen went away without an answer: quiet now, the cycle continues when the owner comes back. */
    fun pause(ringKey: String) {
        if (ring != ringKey || job?.isActive != true) return
        stopLoop("screen left $ringKey")
    }

    /** Call Now / Done / Snooze: stop immediately, then one short answer ([ack]); this ring stays silent. */
    fun answer(ringKey: String, ack: String?, languageCode: String) {
        answered += ringKey
        if (ring == ringKey) stopLoop("answered $ringKey")
        else out.hush()
        if (ack.isNullOrBlank()) return
        log("voice answer $ringKey: $ack")
        scope.launch {
            val mine = SpeechCue(startedAt = now(), gesture = false)
            _cue.value = mine
            try {
                withTimeoutOrNull(LINE_TIMEOUT_MS) { out.say(ack, languageCode) }
            } finally {
                endCue(mine)
            }
        }
    }

    companion object {
        const val LINE_TIMEOUT_MS = 30_000L
    }

    /** Marks [cue] finished — only if it is still the current one (a newer line or answer keeps its own). */
    private fun endCue(cue: SpeechCue) {
        if (_cue.value === cue) _cue.value = cue.copy(endedAt = now())
    }

    private fun stopLoop(why: String) {
        job?.cancel()
        job = null
        out.hush()
        _cue.value?.let { if (it.endedAt == null) endCue(it) }
        log("voice stop ($why)")
    }
}
