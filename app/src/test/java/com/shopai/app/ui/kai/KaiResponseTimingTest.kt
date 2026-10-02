package com.shopai.app.ui.kai

import com.shopai.app.ui.kai.KaiResponsePhase.HIDDEN
import com.shopai.app.ui.kai.KaiResponsePhase.IDLE
import com.shopai.app.ui.kai.KaiResponsePhase.RESPONSE_VISIBLE
import com.shopai.app.ui.kai.KaiResponsePhase.SPEAKING
import com.shopai.app.ui.kai.KaiResponseTiming.phase
import org.junit.Assert.assertEquals
import org.junit.Test

class KaiResponseTimingTest {
    private val t0 = 1_000_000L

    @Test
    fun staysSevenSecondsByDefaultThenIdleThenGone() {
        // No voice (or voice off): 7 s, the last 0.8 s back to idle.
        assertEquals(RESPONSE_VISIBLE, phase(t0 + 100, t0, false, null, false))
        assertEquals(RESPONSE_VISIBLE, phase(t0 + 6_000, t0, false, null, false))
        assertEquals(IDLE, phase(t0 + 6_500, t0, false, null, false))
        assertEquals(HIDDEN, phase(t0 + 7_000, t0, false, null, false))
    }

    @Test
    fun speakingThenVisibleThenIdle() {
        assertEquals(SPEAKING, phase(t0 + 2_000, t0, true, null, false))
        // Short speech ended at 3 s: still 7 s in total.
        assertEquals(RESPONSE_VISIBLE, phase(t0 + 5_000, t0, false, t0 + 3_000, false))
        assertEquals(HIDDEN, phase(t0 + 7_000, t0, false, t0 + 3_000, false))
    }

    @Test
    fun longSpeechKeepsItUpUntilKaiFinishesWithinALimit() {
        // Still talking at 9 s: not hidden.
        assertEquals(SPEAKING, phase(t0 + 9_000, t0, true, null, false))
        // Finished at 10 s: readable until 11.5 s.
        assertEquals(RESPONSE_VISIBLE, phase(t0 + 10_500, t0, false, t0 + 10_000, false))
        assertEquals(HIDDEN, phase(t0 + 11_500, t0, false, t0 + 10_000, false))
        // A runaway voice never keeps it forever.
        assertEquals(HIDDEN, phase(t0 + KaiResponseTiming.MAX_MS, t0, true, null, false))
    }

    @Test
    fun tappedResponseStaysLonger() {
        assertEquals(RESPONSE_VISIBLE, phase(t0 + 30_000, t0, false, t0 + 3_000, true))
        assertEquals(HIDDEN, phase(t0 + KaiResponseTiming.PINNED_MS, t0, false, t0 + 3_000, true))
    }
}
