package com.shopai.app.ui.reminder

import com.shopai.app.ui.reminder.KaiUrgentMotion.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kai Urgent Action Mode's choreography: the spec's phases, the word cascade, a calm idle that never restarts. */
class KaiUrgentMotionTest {

    @Test
    fun phasesFollowTheSpec() {
        assertEquals(Phase.ARRIVAL, KaiUrgentMotion.phaseAt(0))
        assertEquals(Phase.WAKE, KaiUrgentMotion.phaseAt(KaiUrgentMotion.ARRIVAL_END))
        assertEquals(Phase.ATTENTION, KaiUrgentMotion.phaseAt(KaiUrgentMotion.WAKE_END))
        assertEquals(Phase.IDLE, KaiUrgentMotion.phaseAt(KaiUrgentMotion.ATTENTION_END))
        assertEquals(Phase.IDLE, KaiUrgentMotion.phaseAt(60_000))
        // Arrival ~500–700 ms, wake ~700–1000 ms, attention ~600–900 ms.
        assertTrue(KaiUrgentMotion.ARRIVAL_END in 500..700)
        assertTrue(KaiUrgentMotion.WAKE_END - KaiUrgentMotion.ARRIVAL_END in 700..1000)
        assertTrue(KaiUrgentMotion.ATTENTION_END - KaiUrgentMotion.WAKE_END in 600..900)
    }

    // 11: the arrival starts from black and lands without a bounce.
    @Test
    fun arrivalFromBlackNoBounce() {
        assertEquals(0f, KaiUrgentMotion.kaiAlpha(0f), 0.001f)
        assertEquals(0.92f, KaiUrgentMotion.kaiScale(0f), 0.001f)
        assertEquals(28f, KaiUrgentMotion.kaiRiseDp(0f), 0.001f)
        assertEquals(1f, KaiUrgentMotion.kaiAlpha(KaiUrgentMotion.ARRIVAL_END.toFloat()), 0.001f)
        var last = 0f
        for (ms in 0..KaiUrgentMotion.ARRIVAL_END.toInt() step 10) {
            val a = KaiUrgentMotion.kaiAlpha(ms.toFloat())
            assertTrue("alpha never overshoots or goes back", a >= last - 1e-6f && a <= 1f)
            last = a
            assertTrue("never bigger than 100 % while arriving", KaiUrgentMotion.kaiScale(ms.toFloat()) <= 1.0001f)
        }
    }

    @Test
    fun attentionLeansAndReturns() {
        val mid = (KaiUrgentMotion.WAKE_END + KaiUrgentMotion.ATTENTION_END) / 2f
        assertTrue("leans toward the owner", KaiUrgentMotion.kaiLeanDeg(mid) < -3f)
        assertTrue("subtle (≤ 4°)", KaiUrgentMotion.kaiLeanDeg(mid) >= -4f)
        assertEquals("back upright", 0f, KaiUrgentMotion.kaiLeanDeg(KaiUrgentMotion.ATTENTION_END.toFloat()), 0.01f)
        assertEquals(0f, KaiUrgentMotion.kaiLeanDeg(KaiUrgentMotion.WAKE_END.toFloat()), 0.01f)
    }

    // 12: after the intro everything is at rest — the intro values never move again (no restart).
    @Test
    fun introEndsAndStaysEnded() {
        for (ms in listOf(KaiUrgentMotion.INTRO_MS, 5_000L, 60_000L, 3_600_000L)) {
            val t = ms.toFloat()
            assertEquals(1f, KaiUrgentMotion.kaiAlpha(t), 0f)
            assertEquals(1f, KaiUrgentMotion.kaiScale(t), 0.0001f)
            assertEquals(0f, KaiUrgentMotion.kaiRiseDp(t), 0f)
            assertEquals(1f, KaiUrgentMotion.wake(t), 0f)
            assertEquals(1f, KaiUrgentMotion.textAlpha(t, KaiUrgentMotion.BUTTONS_AT), 0f)
        }
    }

    // The words: REMINDER → headline → question → buttons, all visible within the intro.
    @Test
    fun wordsCascadeInOrder() {
        val order = listOf(KaiUrgentMotion.HEADER_AT, KaiUrgentMotion.HEADLINE_AT, KaiUrgentMotion.QUESTION_AT, KaiUrgentMotion.DETAILS_AT, KaiUrgentMotion.BUTTONS_AT)
        assertEquals(order.sorted(), order)
        assertTrue(KaiUrgentMotion.BUTTONS_AT + KaiUrgentMotion.TEXT_FADE_MS <= KaiUrgentMotion.INTRO_MS)
        assertEquals(0f, KaiUrgentMotion.textAlpha(KaiUrgentMotion.HEADLINE_AT.toFloat(), KaiUrgentMotion.HEADLINE_AT), 0f)
    }

    @Test
    fun urgencyGrowsButIsCapped() {
        assertEquals(1f, KaiUrgentMotion.speed(1), 0f)
        assertTrue(KaiUrgentMotion.speed(3) > KaiUrgentMotion.speed(2))
        assertEquals(KaiUrgentMotion.speed(5), KaiUrgentMotion.speed(50), 0f)
        assertTrue("never frantic", KaiUrgentMotion.speed(5) <= 1.5f)
        assertTrue(KaiUrgentMotion.breathDepth(5) <= 0.03f)
        assertTrue("Done / Snooze never wait long", KaiUrgentMotion.EXIT_MS <= 300)
    }
}
