package com.shopai.app.ui.reminder

import com.shopai.app.ui.reminder.KaiUrgentMotion.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kai Urgent Action Mode's timeline: the spec's phases, the word cascade, the quick exit. */
class KaiUrgentMotionTest {

    @Test
    fun phasesFollowTheSpec() {
        // Arrive 0–1 s, notice 1–2 s, reminder gesture 2–4 s, then natural idle.
        assertEquals(Phase.ARRIVE, KaiUrgentMotion.phaseAt(0))
        assertEquals(Phase.ARRIVE, KaiUrgentMotion.phaseAt(999))
        assertEquals(Phase.NOTICE, KaiUrgentMotion.phaseAt(1_000))
        assertEquals(Phase.GESTURE, KaiUrgentMotion.phaseAt(2_000))
        assertEquals(Phase.IDLE, KaiUrgentMotion.phaseAt(4_000))
        assertEquals(Phase.IDLE, KaiUrgentMotion.phaseAt(3_600_000))
    }

    // The words: REMINDER → headline → question → details → buttons, all in while Kai notices the owner.
    @Test
    fun wordsCascadeInOrder() {
        val order = listOf(KaiUrgentMotion.HEADER_AT, KaiUrgentMotion.HEADLINE_AT, KaiUrgentMotion.QUESTION_AT, KaiUrgentMotion.DETAILS_AT, KaiUrgentMotion.BUTTONS_AT)
        assertEquals(order.sorted(), order)
        assertTrue(KaiUrgentMotion.BUTTONS_AT + KaiUrgentMotion.TEXT_FADE_MS <= KaiUrgentMotion.NOTICE_END)
        assertEquals(0f, KaiUrgentMotion.textAlpha(KaiUrgentMotion.HEADLINE_AT.toFloat(), KaiUrgentMotion.HEADLINE_AT), 0f)
        for (ms in listOf(KaiUrgentMotion.NOTICE_END, 60_000L, 3_600_000L)) {
            assertEquals(1f, KaiUrgentMotion.textAlpha(ms.toFloat(), KaiUrgentMotion.BUTTONS_AT), 0f)
            assertEquals(0f, KaiUrgentMotion.textRiseDp(ms.toFloat(), KaiUrgentMotion.BUTTONS_AT), 0f)
        }
    }

    @Test
    fun easingNeverOvershoots() {
        var last = 0f
        for (i in 0..100) {
            val x = i / 100f
            val a = KaiUrgentMotion.easeOut(x)
            val b = KaiUrgentMotion.easeInOut(x)
            assertTrue(a in 0f..1f && b in 0f..1f)
            assertTrue(a >= last - 1e-6f)
            last = a
        }
    }

    @Test
    fun urgencyGrowsButIsCapped() {
        assertEquals(1f, KaiUrgentMotion.speed(1), 0f)
        assertTrue(KaiUrgentMotion.speed(3) > KaiUrgentMotion.speed(2))
        assertEquals(KaiUrgentMotion.speed(5), KaiUrgentMotion.speed(50), 0f)
        assertTrue("never frantic", KaiUrgentMotion.speed(5) <= 1.5f)
        assertTrue("Done / Snooze never wait long", KaiUrgentMotion.EXIT_MS <= 300)
    }
}
