package com.shopai.app.ui.kai

import com.shopai.app.data.tts.SpeechEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class KaiTest {

    private fun KaiScene.run(vararg events: KaiEvent): List<KaiState> {
        var scene = this
        return events.map { scene = scene.on(it); scene.state }
    }

    @Test
    fun creditFlowListensThinksSpeaksReactsCelebratesAndRests() {
        val states = KaiScene().run(
            KaiEvent.Listen,
            KaiEvent.Think,
            KaiEvent.Understood(KaiReaction.CREDIT),
            KaiEvent.SpeechEnded,
            KaiEvent.Saved,
            KaiEvent.HoldElapsed,
        )
        assertEquals(
            listOf(KaiState.LISTENING, KaiState.PROCESSING, KaiState.SPEAKING, KaiState.CREDIT, KaiState.SUCCESS, KaiState.IDLE),
            states,
        )
    }

    @Test
    fun debitIsARecordedEntryNotAnError() {
        val states = KaiScene().run(KaiEvent.Listen, KaiEvent.Think, KaiEvent.Understood(KaiReaction.DEBIT), KaiEvent.SpeechEnded)
        assertEquals(KaiState.DEBIT, states.last())
        assertTrue(KaiState.CLARIFY !in states)
    }

    @Test
    fun answerReturnsToIdleAfterSpeaking() {
        val states = KaiScene().run(KaiEvent.Think, KaiEvent.Understood(KaiReaction.ANSWER), KaiEvent.SpeechEnded)
        assertEquals(listOf(KaiState.PROCESSING, KaiState.SPEAKING, KaiState.IDLE), states)
    }

    @Test
    fun clarificationAsksThenRests() {
        val scene = KaiScene().on(KaiEvent.Understood(KaiReaction.CLARIFY)).on(KaiEvent.SpeechEnded)
        assertEquals(KaiState.CLARIFY, scene.state)
        assertEquals(KaiState.IDLE, scene.on(KaiEvent.HoldElapsed).state)
    }

    @Test
    fun onlyTimedStatesReturnToIdleByThemselves() {
        // Listening, thinking, speaking and a Credit card waiting to be saved never time out.
        for (s in listOf(KaiState.LISTENING, KaiState.PROCESSING, KaiState.SPEAKING, KaiState.CREDIT, KaiState.DEBIT)) {
            assertNull(s.name, KaiScene(s).holdMillis)
            assertEquals(s, KaiScene(s).on(KaiEvent.HoldElapsed).state)
        }
        for (s in listOf(KaiState.GREETING, KaiState.SUCCESS, KaiState.CLARIFY, KaiState.REMINDER, KaiState.INSIGHT, KaiState.FUNDING)) {
            assertTrue(s.name, (KaiScene(s).holdMillis ?: 0) > 0)
            assertEquals(KaiState.IDLE, KaiScene(s).on(KaiEvent.HoldElapsed).state)
        }
    }

    @Test
    fun cancelAlwaysReturnsToIdle() {
        for (s in KaiState.entries) assertEquals(KaiState.IDLE, KaiScene(s).on(KaiEvent.Cancel).state)
    }

    @Test
    fun rigIndexesAreStableAndUnique() {
        assertEquals((0..18).toList(), KaiState.entries.map { it.rigIndex })
    }

    @Test
    fun amountsAsKaiShowsAndSaysThem() {
        assertEquals("₹2,000", kaiRupees(2000.0))
        assertEquals("₹8,000", kaiRupees(8000.0))
        assertEquals("₹1,24,500.50", kaiRupees(124500.5))
        assertEquals("₹500", kaiRupees(500.0))
        assertEquals("2000", kaiSpokenAmount(2000.0))
        assertEquals("2000.50", kaiSpokenAmount(2000.5))
    }

    // A 16-bit mono WAV: 200 ms silence, then 200 ms of a loud tone.
    private fun wav(): ByteArray {
        val rate = 8000
        val samples = ShortArray(rate * 4 / 10) { i -> if (i < rate / 5) 0 else (sin(i * 2 * PI * 440 / rate) * 20000).toInt().toShort() }
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        val out = ByteArrayOutputStream()
        fun le(v: Int, n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array().copyOf(n)
        out.write("RIFF".toByteArray()); out.write(le(36 + data.size, 4)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le(16, 4)); out.write(le(1, 2)); out.write(le(1, 2))
        out.write(le(rate, 4)); out.write(le(rate * 2, 4)); out.write(le(2, 2)); out.write(le(16, 2))
        out.write("data".toByteArray()); out.write(le(data.size, 4)); out.write(data)
        return out.toByteArray()
    }

    @Test
    fun lipSyncFollowsTheVoiceLoudness() {
        val envelope = SpeechEnvelope.fromWav(wav())!!
        assertEquals(0f, envelope.levelAt(50))
        assertTrue(envelope.levelAt(300) > 0.8f)
        // Past the end: the last frame, never a crash.
        envelope.levelAt(10_000)
    }

    @Test
    fun notAWavMeansNaturalRhythmFallback() {
        assertNull(SpeechEnvelope.fromWav("ID3 mp3 data".toByteArray()))
        assertNull(SpeechEnvelope.fromWav(ByteArray(10)))
    }
}
