package com.shopai.app.ui.reminder

import kotlin.math.PI
import kotlin.math.sin

/**
 * Kai Urgent Action Mode's choreography — plain numbers (no Android), so the timing is tested.
 *
 *   ARRIVAL   0 – 650 ms    Kai emerges from black: fade, 92 % → 100 %, a little rise
 *   WAKE    650 – 1550 ms   energy field, orbit and particles come alive
 *   ATTENTION 1550 – 2300 ms Kai leans toward the owner (+ a soft pulse wave), a small gesture
 *   IDLE    2300 ms →        slow breathing, sway, an occasional nod, slow orbit — never a restart
 *
 * The words follow Kai: REMINDER, then the headline slides up, then the question, then the buttons.
 * One intro per ring; a recreated screen (rotation, resume) shows the end state, never the intro again.
 */
object KaiUrgentMotion {
    const val ARRIVAL_END = 650L
    const val WAKE_END = 1_550L
    const val ATTENTION_END = 2_300L
    /** The whole intro; after this only the calm idle runs. */
    const val INTRO_MS = ATTENTION_END

    const val HEADER_AT = 500L
    const val HEADLINE_AT = 700L
    const val QUESTION_AT = 950L
    const val DETAILS_AT = 1_150L
    const val BUTTONS_AT = 1_300L
    const val TEXT_FADE_MS = 350L

    /** Done / Snooze: the action is saved first, then Kai settles out this fast (never delays the action). */
    const val EXIT_MS = 260L

    const val BREATH_MS = 4_200
    const val SWAY_MS = 6_400
    /** Every this often a small nod (the micro gesture), otherwise still. */
    const val NOD_EVERY_MS = 7_000

    enum class Phase { ARRIVAL, WAKE, ATTENTION, IDLE }

    fun phaseAt(ms: Long): Phase = when {
        ms < ARRIVAL_END -> Phase.ARRIVAL
        ms < WAKE_END -> Phase.WAKE
        ms < ATTENTION_END -> Phase.ATTENTION
        else -> Phase.IDLE
    }

    /** 0 → 1 between [from] and [to] (clamped). */
    fun segment(ms: Float, from: Long, to: Long): Float = ((ms - from) / (to - from).toFloat()).coerceIn(0f, 1f)

    /** Ease-out cubic: fast start, soft landing — no bounce. */
    fun easeOut(x: Float): Float { val y = 1f - x; return 1f - y * y * y }

    /** Ease-in-out for the lean. */
    fun easeInOut(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f

    // ---------------------------------------------------------------- Kai's body during the intro

    fun kaiAlpha(ms: Float) = easeOut(segment(ms, 0, ARRIVAL_END))
    fun kaiScale(ms: Float) = 0.92f + 0.08f * easeOut(segment(ms, 0, ARRIVAL_END)) + 0.03f * attentionBump(ms)
    /** Rise in dp: 28 dp below → in place. */
    fun kaiRiseDp(ms: Float) = 28f * (1f - easeOut(segment(ms, 0, ARRIVAL_END)))
    /** The lean toward the owner, degrees (there and back). */
    fun kaiLeanDeg(ms: Float) = -3.5f * attentionBump(ms)
    private fun attentionBump(ms: Float) = sin(PI.toFloat() * easeInOut(segment(ms, WAKE_END, ATTENTION_END)))

    /** Energy field / orbit / particles: 0 → 1 during WAKE. */
    fun wake(ms: Float) = easeOut(segment(ms, ARRIVAL_END, WAKE_END))
    /** The one soft pulse wave during ATTENTION: radius 0 → 1 and fading out. */
    fun pulse(ms: Float) = segment(ms, WAKE_END, ATTENTION_END)
    /** Idle motion fades in under the end of the intro — no jump when it takes over. */
    fun idleWeight(ms: Float) = easeOut(segment(ms, WAKE_END + 300, ATTENTION_END + 300))

    // ---------------------------------------------------------------- words

    fun textAlpha(ms: Float, at: Long) = easeOut(segment(ms, at, at + TEXT_FADE_MS))
    fun textRiseDp(ms: Float, at: Long) = 14f * (1f - easeOut(segment(ms, at, at + TEXT_FADE_MS)))

    // ---------------------------------------------------------------- urgency per attempt

    /** Orbit / particle speed: a little quicker each ring, capped (premium, never frantic). */
    fun speed(attempt: Int) = 1f + 0.12f * (attempt - 1).coerceIn(0, 4)
    /** Breathing depth: slightly deeper each ring, capped. */
    fun breathDepth(attempt: Int) = 0.014f + 0.003f * (attempt - 1).coerceIn(0, 4)
}
