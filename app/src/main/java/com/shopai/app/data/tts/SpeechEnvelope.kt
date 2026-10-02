package com.shopai.app.data.tts

import kotlin.math.sqrt

/**
 * Loudness of a WAV file over time, for KAI's lip-sync: one value (0..1)
 * per [frameMillis] of audio. 16-bit PCM WAV (what the voice proxy returns);
 * anything else gives null and KAI falls back to a natural talking rhythm.
 */
class SpeechEnvelope(val frameMillis: Int, val levels: FloatArray) {
    /** Mouth opening at [positionMillis] into the audio. */
    fun levelAt(positionMillis: Int): Float =
        if (levels.isEmpty()) 0f else levels[(positionMillis / frameMillis).coerceIn(0, levels.lastIndex)]

    companion object {
        fun fromWav(bytes: ByteArray, frameMillis: Int = 40): SpeechEnvelope? {
            if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
            var pos = 12
            var channels = 1
            var sampleRate = 0
            var bits = 16
            var dataStart = -1
            var dataSize = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = le32(bytes, pos + 4)
                if (id == "fmt ") {
                    channels = le16(bytes, pos + 10)
                    sampleRate = le32(bytes, pos + 12)
                    bits = le16(bytes, pos + 22)
                } else if (id == "data") {
                    dataStart = pos + 8
                    dataSize = minOf(size, bytes.size - dataStart)
                    break
                }
                pos += 8 + size + (size and 1)
            }
            if (dataStart < 0 || bits != 16 || sampleRate <= 0 || channels <= 0) return null
            val samplesPerFrame = (sampleRate * frameMillis / 1000) * channels
            if (samplesPerFrame <= 0) return null
            val totalSamples = dataSize / 2
            val frames = (totalSamples + samplesPerFrame - 1) / samplesPerFrame
            val rms = FloatArray(frames) { f ->
                var sum = 0.0
                var n = 0
                var s = f * samplesPerFrame
                val end = minOf(totalSamples, s + samplesPerFrame)
                while (s < end) {
                    val v = (bytes[dataStart + s * 2].toInt() and 0xFF) or (bytes[dataStart + s * 2 + 1].toInt() shl 8)
                    sum += v.toShort().toDouble() * v.toShort().toDouble()
                    n++
                    s++
                }
                if (n == 0) 0f else sqrt(sum / n).toFloat()
            }
            // Normalise to the loud parts of this clip; ignore near-silence.
            val peak = rms.sortedArray().let { if (it.isEmpty()) 0f else it[(it.size * 0.95).toInt().coerceAtMost(it.lastIndex)] }
            if (peak <= 0f) return SpeechEnvelope(frameMillis, FloatArray(frames))
            val levels = FloatArray(frames) { ((rms[it] / peak) - 0.08f).coerceIn(0f, 1f) }
            return SpeechEnvelope(frameMillis, levels)
        }

        private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
    }
}
