package com.shopai.app.ui.reminder

import com.shopai.app.brain.tools.VoiceLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kai's reminder voice: one loop, natural pauses, continues until the owner acts, stops at once on
 * Call / Done / Snooze (nothing queued plays after), never doubles on rotation / resume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KaiUrgentVoiceTest {

    /** A voice that takes [lineMs] per line and records what was said / hushed. */
    private class FakeVoice(private val scope: TestScope, private val lineMs: Long = 3_000) : KaiVoiceOut {
        val said = mutableListOf<Pair<Long, String>>()
        var hushes = 0
        var playing: String? = null
        private var current: CompletableDeferred<Unit>? = null

        override suspend fun say(text: String, languageCode: String) {
            said += scope.testScheduler.currentTime to text
            playing = text
            val done = CompletableDeferred<Unit>()
            current = done
            try {
                kotlinx.coroutines.withTimeoutOrNull(lineMs) { done.await() }
            } finally {
                if (current === done) playing = null
            }
        }

        override fun hush() {
            hushes++
            playing = null
            current?.complete(Unit)
        }
    }

    private val lines = listOf(
        VoiceLine("Owner, Praba-ku call panna vendiya neram aachu. Ippo call pannalama?", true),
        VoiceLine("Praba-ku call pannunga Owner.", true),
        VoiceLine("Seekiram pannunga Owner.", false),
        VoiceLine("Owner, Praba-ku call pannalama?", true),
        VoiceLine("Praba-ku call panna marakkadheenga Owner.", false),
        VoiceLine("Owner, idha ippo mudichidalaama?", true),
    )

    private fun TestScope.voice(out: FakeVoice) = KaiUrgentVoice(this, out, now = { testScheduler.currentTime })

    @Test
    fun keepsRemindingNaturallyUntilAnswered() = runTest {
        val out = FakeVoice(this)
        val v = voice(out)
        assertTrue(v.start("R1#1", lines, "ta-IN"))
        advanceTimeBy(120_000)
        runCurrent()
        val texts = out.said.map { it.second }
        assertEquals(lines[0].text, texts[0])
        assertTrue("continues after the first line", texts.size >= 6)
        // Opening once, then follow-ups; never twice in a row.
        assertEquals(1, texts.count { it == lines[0].text })
        assertTrue(texts.zipWithNext().none { (a, b) -> a == b })
        // Real pauses between lines (line 3 s + pause ≥ 6.5 s), not every 2 s.
        val gaps = out.said.map { it.first }.zipWithNext { a, b -> b - a }
        assertTrue(gaps.toString(), gaps.all { it >= 9_000 })
        v.answer("R1#1", null, "ta-IN")
    }

    @Test
    fun oneLoopOnlyForTheSameRing() = runTest {
        val out = FakeVoice(this)
        val v = voice(out)
        assertTrue(v.start("R1#1", lines, "ta-IN"))
        // Rotation / recomposition / resume / a second tap: the same ring is already speaking.
        assertFalse(v.start("R1#1", lines, "ta-IN"))
        assertFalse(v.start("R1#1", lines, "ta-IN", openingAlreadySpoken = true))
        advanceTimeBy(30_000)
        runCurrent()
        val starts = out.said.map { it.first }
        assertEquals("never two voices at once", starts.size, starts.toSet().size)
        assertEquals(1, out.said.count { it.second == lines[0].text })
        v.answer("R1#1", null, "ta-IN")
    }

    @Test
    fun actionStopsImmediatelyThenOneShortAnswer() = runTest {
        val out = FakeVoice(this)
        val v = voice(out)
        v.start("R1#1", lines, "ta-IN")
        runCurrent()
        assertEquals(lines[0].text, out.playing)
        advanceTimeBy(1_000)
        v.answer("R1#1", "Seri Owner.", "ta-IN")
        runCurrent()
        assertTrue("the line being spoken is cut", out.hushes >= 1)
        assertEquals("Seri Owner.", out.said.last().second)
        val count = out.said.size
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals("nothing after Done", count, out.said.size)
        // The answered ring never speaks again (back from the dialer, a resume).
        assertFalse(v.start("R1#1", lines, "ta-IN"))
        advanceTimeBy(60_000)
        assertEquals(count, out.said.size)
    }

    @Test
    fun pauseAndComeBackContinuesNotFromTheTop() = runTest {
        val out = FakeVoice(this)
        val v = voice(out)
        v.start("R1#1", lines, "ta-IN")
        advanceTimeBy(12_000)
        runCurrent()
        assertEquals(2, out.said.size)
        v.pause("R1#1")
        val n = out.said.size
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("quiet while the screen is away", n, out.said.size)
        assertTrue(v.start("R1#1", lines, "ta-IN"))
        runCurrent()
        assertEquals("no line at once on return", n, out.said.size)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("the next line, not the opening", lines[2].text, out.said[n].second)
        v.answer("R1#1", null, "ta-IN")
    }

    @Test
    fun nextAttemptStartsItsOwnCycle() = runTest {
        val out = FakeVoice(this)
        val v = voice(out)
        v.start("R1#1", lines, "ta-IN")
        advanceTimeBy(5_000)
        v.start("R1#2", lines, "ta-IN")
        runCurrent()
        assertEquals(lines[0].text, out.said.last().second)
        assertEquals(2, out.said.count { it.second == lines[0].text })
        v.answer("R1#2", null, "ta-IN")
    }

    @Test
    fun openingAlreadySpokenStartsWithAFollowUp() = runTest {
        val out = FakeVoice(this)
        val v = voice(out)
        v.start("R1#1", lines, "ta-IN", openingAlreadySpoken = true)
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(lines[1].text, out.said.single().second)
        v.answer("R1#1", null, "ta-IN")
    }

    @Test
    fun bodyCueFollowsEachLine() = runTest {
        val out = FakeVoice(this)
        val v = voice(out)
        v.start("R1#1", lines, "ta-IN")
        runCurrent()
        val cue = v.cue.value
        assertNotNull(cue)
        assertNull(cue!!.endedAt)
        assertTrue(cue.gesture)
        advanceTimeBy(3_500)
        runCurrent()
        assertNotNull("ended with the line", v.cue.value!!.endedAt)
        v.answer("R1#1", null, "ta-IN")
    }

    @Test
    fun aVoiceThatNeverReportsBackCannotStallIt() = runTest {
        val out = FakeVoice(this, lineMs = Long.MAX_VALUE / 4)
        val v = voice(out)
        v.start("R1#1", lines, "ta-IN")
        advanceTimeBy(KaiUrgentVoice.LINE_TIMEOUT_MS + 20_000)
        runCurrent()
        assertTrue(out.said.size >= 2)
        v.answer("R1#1", null, "ta-IN")
    }
}
