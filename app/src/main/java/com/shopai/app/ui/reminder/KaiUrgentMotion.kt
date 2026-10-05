package com.shopai.app.ui.reminder

/**
 * Kai Urgent Action Mode's timeline — plain numbers (no Android), so the timing is tested.
 *
 *   ARRIVE    0 – 1000 ms   Kai steps into the scene: out of the dark, settling onto both feet
 *   NOTICE 1000 – 2000 ms   his eyes, then his head turn to the owner; brows and shoulders lift ("Owner…")
 *   GESTURE 2000 – 4000 ms  the open hand comes out toward the owner, he leans in, a nod
 *   IDLE    from ~5300 ms   breathing, weight shifts, head moves, glances, a gesture now and then
 *
 * Kai's body itself is [KaiActing]; this file is the shared clock and the words.
 * The words follow Kai: REMINDER, the headline, the question, then the buttons — all within NOTICE.
 * One intro per ring; a recreated screen (rotation, resume) continues the same clock, never the intro again.
 */
object KaiUrgentMotion {
    const val ARRIVE_END = 1_000L
    const val NOTICE_END = 2_000L
    const val GESTURE_END = 4_000L

    const val HEADER_AT = 700L
    const val HEADLINE_AT = 900L
    const val QUESTION_AT = 1_150L
    const val DETAILS_AT = 1_350L
    const val BUTTONS_AT = 1_500L
    const val TEXT_FADE_MS = 350L

    /** Done / Snooze: the action is saved first, then Kai settles out this fast (never delays the action). */
    const val EXIT_MS = 260L

    enum class Phase { ARRIVE, NOTICE, GESTURE, IDLE }

    fun phaseAt(ms: Long): Phase = when {
        ms < ARRIVE_END -> Phase.ARRIVE
        ms < NOTICE_END -> Phase.NOTICE
        ms < GESTURE_END -> Phase.GESTURE
        else -> Phase.IDLE
    }

    /** 0 → 1 between [from] and [to] (clamped). */
    fun segment(ms: Float, from: Long, to: Long): Float = ((ms - from) / (to - from).toFloat()).coerceIn(0f, 1f)

    /** Ease-out cubic: fast start, soft landing — no bounce. */
    fun easeOut(x: Float): Float { val y = 1f - x; return 1f - y * y * y }

    /** Ease-in-out cubic. */
    fun easeInOut(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f

    // ---------------------------------------------------------------- words

    fun textAlpha(ms: Float, at: Long) = easeOut(segment(ms, at, at + TEXT_FADE_MS))
    fun textRiseDp(ms: Float, at: Long) = 12f * (1f - easeOut(segment(ms, at, at + TEXT_FADE_MS)))

    // ---------------------------------------------------------------- urgency per attempt

    /** A little more insistent each ring (gestures come sooner), capped — premium, never frantic. */
    fun speed(attempt: Int) = 1f + 0.12f * (attempt - 1).coerceIn(0, 4)
}
