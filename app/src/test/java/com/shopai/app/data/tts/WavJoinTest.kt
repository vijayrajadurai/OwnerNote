package com.shopai.app.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The reminder turn is one clip: the voice service's padding is trimmed and the pauses between
 * sentences are exactly the ones asked for — the root cause of the long "restart" silence.
 */
class WavJoinTest {
    private val rate = 22_050

    /** A clip like the voice service returns: [padMs] of silence, [voiceMs] of "voice", [padMs] of silence. */
    private fun clip(voiceMs: Int, padMs: Int = 400, sampleRate: Int = rate): ByteArray {
        val pad = ShortArray(sampleRate * padMs / 1000)
        val voice = ShortArray(sampleRate * voiceMs / 1000) { i -> (8_000 * sin(2 * PI * 220 * i / sampleRate)).toInt().toShort() }
        val samples = pad + voice + pad
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> data[2 * i] = (s.toInt() and 0xFF).toByte(); data[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte() }
        return WavJoin.wav(data, sampleRate, 1)
    }

    @Test
    fun turnIsOneClipWithTheAskedPauses() {
        val joined = WavJoin.join(listOf(clip(1_500), clip(700), clip(900)), listOf(300L, 850L))
        assertNotNull(joined)
        val pcm = WavJoin.parse(joined!!)!!
        // Silences between sentences (the sine's own zero-crossings are far below 100 ms).
        val gaps = WavJoin.silences(pcm, minMs = 100).map { it.second }
        assertEquals(gaps.toString(), 2, gaps.size)
        assertTrue("after the first sentence: $gaps", gaps[0] in 250L..500L)
        assertTrue("before the question: $gaps", gaps[1] in 700L..1_200L)
        // Without the padding the service adds (400 ms on each side of every clip = 2.4 s), only voice + pauses + short edges.
        val expected = 1_500 + 700 + 900 + 300 + 850 + 3 * (WavJoin.LEAD_KEEP_MS + WavJoin.TAIL_KEEP_MS)
        assertTrue("${pcm.durationMs} vs $expected", kotlin.math.abs(pcm.durationMs - expected) <= 20)
    }

    @Test
    fun beforeTheFixEachSentenceCarriedItsPadding() {
        // What the owner heard before: clip 1's trailing pad + the script's 6.5 s + clip 2's leading pad.
        val before = 400 + 6_500 + 400
        val after = WavJoin.silences(WavJoin.parse(WavJoin.join(listOf(clip(1_500), clip(700)), listOf(300L))!!)!!, 100).single().second
        assertTrue("$after ms instead of $before ms", after < 500 && before > 7_000)
    }

    @Test
    fun mismatchedOrUnreadableClipsGiveNull() {
        assertNull(WavJoin.join(listOf(clip(500), clip(500, sampleRate = 16_000)), listOf(300L)))
        assertNull(WavJoin.join(listOf(clip(500), "not a wav file at all, just text".toByteArray()), listOf(300L)))
        assertNull(WavJoin.join(emptyList(), emptyList()))
    }

    @Test
    fun aSingleClipIsOnlyTrimmed() {
        val one = WavJoin.parse(WavJoin.join(listOf(clip(1_000)), emptyList())!!)!!
        assertTrue(one.durationMs in 1_080L..1_100L)
        assertEquals(rate, one.sampleRate)
    }
}
