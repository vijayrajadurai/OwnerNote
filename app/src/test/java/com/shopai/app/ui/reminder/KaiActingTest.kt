package com.shopai.app.ui.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Kai acting the reminder with his body (not the background): arrival, noticing the owner,
 * the open-hand gesture, an organic idle, and moving with his voice.
 */
class KaiActingTest {

    private val kai = KaiActing(seed = 7L)

    // Phase 1 — he steps in out of the dark and settles (no bounce, no pop).
    @Test
    fun arrivesAndSettles() {
        val first = kai.take(0)
        assertEquals(0f, first.alpha, 0f)
        assertEquals(0.93f, first.scale, 0.001f)
        assertEquals(22f, first.riseDp, 0.001f)
        assertEquals(1f, kai.take(450).alpha, 0.001f)
        var last = 0f
        for (t in 0L..1_000L step 20) {
            val s = kai.take(t).scale
            assertTrue("never bigger than 100 % while arriving", s <= 1.0001f)
            assertTrue("no bounce", s >= last - 1e-5f)
            last = s
        }
        assertEquals(1f, kai.take(1_000).scale, 0.0001f)
        assertEquals(0f, kai.take(1_000).riseDp, 0.0001f)
        // He lands on both feet: the weight he stepped in with settles.
        assertTrue(kai.take(200).pose.weightShift > 0.3f)
        assertEquals(0f, kai.take(1_000).pose.weightShift, 0.001f)
    }

    // Phase 2 — "Owner…": eyes first, then the head turns to the owner; brows and shoulders lift.
    @Test
    fun noticesTheOwner() {
        val before = kai.take(1_000).pose
        assertTrue("looking aside as he arrives", before.headTurn < -0.5f && before.lookX < -0.5f)
        val eyes = kai.take(1_260).pose
        assertEquals("eyes reach the owner first", 0f, eyes.lookX, 0.001f)
        assertTrue("the head is still turning", eyes.headTurn < -0.2f)
        assertEquals("then the head", 0f, kai.take(1_700).pose.headTurn, 0.001f)
        assertTrue("brows up", kai.take(1_400).pose.brows > 0.9f)
        assertTrue("shoulders up", kai.take(1_350).pose.shoulders > 0.6f)
        assertTrue("leaning in", kai.take(2_000).pose.lean > 0.4f)
    }

    // Phase 3 — the open hand toward the owner, a palm movement, a nod; then back to rest.
    @Test
    fun givesTheReminderWithHisHand() {
        assertEquals("hands in pockets before", 0f, kai.take(1_999).gesture, 0.001f)
        for (t in listOf(2_600L, 3_000L, 4_000L, 4_600L)) assertTrue("hand out at $t", kai.take(t).gesture > 0.99f)
        assertTrue("the palm moves", (2_600L..4_400L step 100).any { abs(kai.take(it).pose.handWave) > 3f })
        assertTrue("a nod", kai.take(3_400).pose.headNod > 0.025f)
        assertEquals("back in the pocket", 0f, kai.take(KaiActing.GESTURE_DOWN_AT + 700).gesture, 0.001f)
    }

    // The idle is alive: every moment differs from the last (breathing at least), nothing freezes.
    @Test
    fun neverFreezes() {
        for (t in 6_000L..120_000L step 1_000) {
            val a = kai.take(t).pose
            val b = kai.take(t + 500).pose
            assertNotEquals("frozen at $t", a, b)
        }
    }

    // Organic, bounded rhythms: head every 5–8 s, weight every 7–12 s (alternating legs), a gesture every
    // 10–16 s, a glance every 6–10 s, blinks every 2.4–5.8 s — not one loop repeating every 2 s.
    @Test
    fun idleRhythmsAreOrganicAndBounded() {
        fun gaps(beats: Beats, until: Long) = beats.timesUpTo(until).zipWithNext { a, b -> b - a }
        val until = 600_000L
        val head = gaps(Beats(7L * 31 + 1, KaiActing.IDLE_FROM, 5_000, 8_000), until)
        val weight = gaps(Beats(7L * 31 + 2, KaiActing.IDLE_FROM + 1_500, 7_000, 12_000), until)
        val gesture = gaps(Beats(7L * 31 + 3, KaiActing.IDLE_FROM + 6_000, 10_000, 16_000), until)
        assertTrue(head.all { it in 5_000..8_000 })
        assertTrue(weight.all { it in 7_000..12_000 })
        assertTrue(gesture.all { it in 10_000..16_000 })
        for (g in listOf(head, weight, gesture)) assertTrue("not a fixed loop", g.toSet().size > g.size / 2)

        // Weight goes from one leg to the other.
        val sides = (20_000L..200_000L step 250).map { kai.take(it).pose.weightShift }.filter { abs(it) > 0.5f }.map { it > 0 }
        assertTrue(sides.zipWithNext().any { (a, b) -> a != b })
        // Hands come out now and then in the idle, not all the time.
        val out = (10_000L..120_000L step 100).count { kai.take(it).gesture > 0.9f }
        assertTrue("some gestures", out > 30)
        assertTrue("mostly at rest", out < (110_000 / 100) / 2)
        // Blinks: short, regular-ish, never stuck closed.
        val closed = (0L..60_000L step 10).count { kai.take(it).pose.blink > 0.5f }
        assertTrue(closed in 50..400)
    }

    @Test
    fun movesStayNatural() {
        for (t in 0L..300_000L step 37) {
            val p = kai.take(t, mouth = (t % 7) / 7f).pose
            assertTrue(abs(p.headTilt) <= 5f)
            assertTrue(abs(p.headTurn) <= 1f && abs(p.lookX) <= 1f)
            assertTrue(abs(p.weightShift) <= 1f && p.lean in 0f..1f)
            assertTrue(abs(p.handWave) <= 8f)
            assertTrue(p.brows in 0f..1f && p.shoulders in 0f..1f)
        }
    }

    // The same ring always acts the same (a recreated screen continues the same performance).
    @Test
    fun sameRingSamePerformance() {
        val again = KaiActing(seed = 7L)
        for (t in listOf(0L, 1_500L, 9_000L, 61_000L, 600_000L)) assertEquals(kai.take(t), again.take(t))
        // Asking out of order (e.g. after a pause) changes nothing.
        val other = KaiActing(seed = 7L)
        assertEquals(kai.take(90_000), other.take(90_000))
        assertEquals(kai.take(12_000), other.take(12_000))
        assertNotEquals(KaiActing(seed = 8L).take(30_000).pose, kai.take(30_000).pose)
    }

    // He moves with his voice: a nod and brows as each line starts, eye contact, the hand on lines that ask.
    @Test
    fun followsHisVoice() {
        val quiet = kai.take(30_300).pose
        val cue = SpeechCue(startedAt = 30_000L, gesture = true)
        val talking = kai.take(30_260, mouth = 0.8f, speech = cue)
        assertTrue("brows with the first word", talking.pose.brows > 0.5f)
        assertTrue("a nod", kai.take(30_320, speech = cue).pose.headNod > quiet.headNod + 0.02f)
        assertEquals("eye contact while speaking", 0f, kai.take(31_000, mouth = 0.5f, speech = cue).pose.lookX, 0.001f)
        assertTrue("the hand comes out on an ask", kai.take(30_800, speech = cue).gesture > 0.99f)
        assertTrue("…and stays while he talks", kai.take(34_000, speech = cue).gesture > 0.99f)
        val ended = cue.copy(endedAt = 34_000L)
        assertTrue(kai.take(35_100, speech = ended).gesture > 0.99f)
        val after = kai.take(36_000, speech = ended)
        assertTrue("…then goes back", after.gesture < 0.05f || after.gesture == kai.take(36_000).gesture)
        // A line that doesn't ask: no hand from the voice.
        val plain = SpeechCue(startedAt = 30_000L, gesture = false)
        assertEquals(kai.take(30_800).gesture, kai.take(30_800, speech = plain).gesture, 0.0001f)
        // The mouth is the voice's.
        assertEquals(0.7f, kai.take(31_000, mouth = 0.7f, speech = cue).pose.mouth, 0f)
    }

    @Test
    fun moreInsistentEachRingButCalm() {
        val first = Beats(7L * 31 + 3, KaiActing.IDLE_FROM + (6_000 / KaiUrgentMotion.speed(1)).toLong(), 10_000, 16_000).timesUpTo(60_000).size
        val fifth = Beats(7L * 31 + 3, KaiActing.IDLE_FROM + (6_000 / KaiUrgentMotion.speed(5)).toLong(), (10_000 / KaiUrgentMotion.speed(5)).toLong(), (16_000 / KaiUrgentMotion.speed(5)).toLong()).timesUpTo(60_000).size
        assertTrue("gestures come sooner on the 5th ring", fifth > first)
        assertTrue(KaiActing(7L, attempt = 5).take(60_000).pose.lean <= 1f)
    }

    // Reduced motion: Kai still (his open hand toward the owner), only the lips follow the voice.
    @Test
    fun reducedMotionIsStill() {
        val a = kai.take(0, mouth = 0.4f, still = true)
        val b = kai.take(45_000, mouth = 0.4f, still = true)
        assertEquals(a, b)
        assertEquals(1f, a.gesture, 0f)
        assertEquals(1f, a.alpha, 0f)
        assertEquals(0.4f, a.pose.mouth, 0f)
        assertFalse(a.pose.blink > 0f)
    }
}
