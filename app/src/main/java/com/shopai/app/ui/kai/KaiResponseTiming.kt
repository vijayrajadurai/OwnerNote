package com.shopai.app.ui.kai

/** Where a Kai response is in its life on screen. */
enum class KaiResponsePhase {
    /** Kai is talking (lip-synced). */
    SPEAKING,
    /** Finished talking; the response stays readable with Kai's reaction. */
    RESPONSE_VISIBLE,
    /** Kai settles back to idle just before the response fades out. */
    IDLE,
    /** Gone. */
    HIDDEN,
}

/**
 * How long a Kai response stays on screen — a non-blocking timed state,
 * not a delay: the overlay just asks "which phase is it now?".
 *
 *  - Visible at least [DEFAULT_MS] (7 s) from when it appeared.
 *  - If Kai is still talking, it stays until he finishes, plus [AFTER_SPEECH_MS]
 *    so the owner can finish reading — up to [MAX_MS].
 *  - Tapped (pinned) by the owner: stays up to [PINNED_MS], or until closed.
 *  - The last [IDLE_TAIL_MS] Kai is back to idle, then it fades out.
 */
object KaiResponseTiming {
    const val DEFAULT_MS = 7_000L
    const val AFTER_SPEECH_MS = 1_500L
    const val MAX_MS = 20_000L
    const val PINNED_MS = 60_000L
    const val IDLE_TAIL_MS = 800L

    /** When the response should disappear. */
    fun hideAt(startedAt: Long, speakingNow: Boolean, speechEndedAt: Long?, pinned: Boolean): Long {
        val cap = startedAt + if (pinned) PINNED_MS else MAX_MS
        if (pinned) return cap
        val wanted = when {
            speakingNow -> Long.MAX_VALUE
            speechEndedAt != null -> maxOf(startedAt + DEFAULT_MS, speechEndedAt + AFTER_SPEECH_MS)
            else -> startedAt + DEFAULT_MS
        }
        return minOf(cap, wanted)
    }

    fun phase(now: Long, startedAt: Long, speakingNow: Boolean, speechEndedAt: Long?, pinned: Boolean): KaiResponsePhase {
        val hide = hideAt(startedAt, speakingNow, speechEndedAt, pinned)
        return when {
            now >= hide -> KaiResponsePhase.HIDDEN
            speakingNow -> KaiResponsePhase.SPEAKING
            now >= hide - IDLE_TAIL_MS -> KaiResponsePhase.IDLE
            else -> KaiResponsePhase.RESPONSE_VISIBLE
        }
    }
}
