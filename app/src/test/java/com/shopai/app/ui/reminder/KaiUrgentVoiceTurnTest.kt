package com.shopai.app.ui.reminder

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiReminderFlow
import com.shopai.app.brain.tools.KaiUrgentVoiceScript
import com.shopai.app.brain.tools.ReminderAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reminder turn is ONE utterance (its sentences joined, one player), not one request per
 * sentence; Call Now / Done / Snooze cut it at once and nothing queued plays afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KaiUrgentVoiceTurnTest {

    /** A voice that records whole turns (sayTurn) separately from single lines (say). */
    private class TurnVoice(private val scope: TestScope, private val turnMs: Long = 5_000, private val lineMs: Long = 2_000) : KaiVoiceOut {
        val turns = mutableListOf<Pair<Long, List<String>>>()
        val gaps = mutableListOf<List<Long>>()
        val said = mutableListOf<Pair<Long, String>>()
        var hushes = 0
        var playing: String? = null
        private var current: CompletableDeferred<Unit>? = null

        private suspend fun play(what: String, ms: Long) {
            playing = what
            val done = CompletableDeferred<Unit>()
            current = done
            try {
                kotlinx.coroutines.withTimeoutOrNull(ms) { done.await() }
            } finally {
                if (current === done) playing = null
            }
        }

        override suspend fun say(text: String, languageCode: String) {
            said += scope.testScheduler.currentTime to text
            play(text, lineMs)
        }

        override suspend fun sayTurn(parts: List<String>, gapsMs: List<Long>, languageCode: String) {
            turns += scope.testScheduler.currentTime to parts
            gaps += gapsMs
            play(parts.joinToString(" "), turnMs)
        }

        override fun hush() {
            hushes++
            playing = null
            current?.complete(Unit)
        }
    }

    private val t0 = 1_800_000_000_000L
    private val kumar = KaiReminderFlow.trigger(
        KaiReminder(
            id = "R9", title = "Call Kumar", task = "Kumar-ku call panna", action = ReminderAction.CALL, person = "Kumar", phone = "+919000000001",
            triggerAt = t0, zone = "Asia/Kolkata", notificationMessage = "", sourceText = "Kumar-ku 2 minutes-la call", createdAt = t0 - 120_000, lang = KaiLang.TANGLISH,
        ),
        t0, newOccurrence = true,
    )
    private val lines = KaiUrgentVoiceScript.lines(kumar)
    private fun TestScope.voice(out: TurnVoice) = KaiUrgentVoice(this, out, now = { testScheduler.currentTime })

    // 24. The opening is one turn: three sentences in one utterance with the short gaps — no request per sentence.
    @Test
    fun openingIsOneUtteranceWithShortGaps() = runTest {
        val out = TurnVoice(this)
        val v = voice(out)
        v.start("R9#1", lines, "ta-IN")
        runCurrent()
        assertEquals(1, out.turns.size)
        assertEquals(lines[0].parts, out.turns.single().second)
        assertEquals(listOf(KaiUrgentVoiceScript.GAP_BEFORE_QUESTION_MS), out.gaps.single())
        assertTrue("no sentence of the opening said on its own", out.said.isEmpty())
        // The next line comes only after the turn has ended + the pause between turns.
        advanceTimeBy(5_000 + KaiUrgentVoiceScript.pauseBefore(1) - 100)
        runCurrent()
        assertTrue(out.said.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(lines[1].text, out.said.single().second)
        v.answer("R9#1", null, "ta-IN")
    }

    // 25. No duplicate TTS: a second start (rotation, resume, a second tap) never speaks the turn again.
    @Test
    fun noDuplicateTurn() = runTest {
        val out = TurnVoice(this)
        val v = voice(out)
        assertTrue(v.start("R9#1", lines, "ta-IN"))
        assertFalse(v.start("R9#1", lines, "ta-IN"))
        assertFalse(v.start("R9#1", lines, "ta-IN", openingAlreadySpoken = true))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the opening turn once", 1, out.turns.size)
        v.answer("R9#1", null, "ta-IN")
    }

    // 26 + 27. Interruption: Call Now / Done / Snooze in the middle of the turn cut it at once.
    @Test
    fun callDoneSnoozeStopTheTurnAtOnce() = runTest {
        for ((key, ack) in listOf(
            "R9#call" to KaiUrgentVoiceScript.callAck(kumar),
            "R9#done" to KaiUrgentVoiceScript.doneAck(KaiLang.TANGLISH),
            "R9#snooze" to KaiUrgentVoiceScript.snoozeAck(5, KaiLang.TANGLISH),
        )) {
            val out = TurnVoice(this)
            val v = voice(out)
            v.start(key, lines, "ta-IN")
            runCurrent()
            advanceTimeBy(1_200) // mid-sentence
            assertEquals(lines[0].text, out.playing)
            v.answer(key, ack, "ta-IN")
            runCurrent()
            assertTrue("$key: hushed", out.hushes >= 1)
            assertEquals("$key: the short answer only", ack, out.said.single().second)
            advanceTimeBy(5_000)
            runCurrent()
            assertNull("$key: silence after the answer", out.playing)
        }
    }

    // 28. No queued audio: after the answer, nothing of the cycle plays — not the rest of the turn, not a follow-up.
    @Test
    fun nothingQueuedPlaysAfterAnAnswer() = runTest {
        val out = TurnVoice(this)
        val v = voice(out)
        v.start("R9#1", lines, "ta-IN")
        runCurrent()
        advanceTimeBy(800)
        v.answer("R9#1", null, "ta-IN")
        runCurrent()
        val turns = out.turns.size
        val said = out.said.size
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(turns, out.turns.size)
        assertEquals(said, out.said.size)
        assertFalse("an answered ring never speaks again", v.start("R9#1", lines, "ta-IN"))
    }

    // 29. A voice without turn support (the default) still says the turn once, as one text.
    @Test
    fun defaultSayTurnIsOneUtterance() = runTest {
        val said = mutableListOf<String>()
        val plain = object : KaiVoiceOut {
            override suspend fun say(text: String, languageCode: String) { said += text }
            override fun hush() = Unit
        }
        plain.sayTurn(lines[0].parts, lines[0].gapsMs, "ta-IN")
        assertEquals(listOf(lines[0].text), said)
    }
}
