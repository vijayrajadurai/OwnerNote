package com.shopai.app.data.tts

import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Joins the natural voice's sentence clips into one clip, with exact silences between them —
 * so a reminder turn ("Owner... Kumar-ku call panna vendiya neram aachu." · "Call pannunga." ·
 * "Call pannalama?") plays as one continuous voice: one player, no restart between sentences.
 *
 * Each clip's own leading / trailing silence (the voice service pads every request) is trimmed
 * to a short edge first, so the pause the owner hears is the one asked for, not padding + gap.
 *
 * 16-bit PCM WAV only (what the voice proxy returns); clips that differ in rate or channels, or
 * anything unreadable, give null and the caller falls back to one request for the whole turn.
 * Pure bytes in, bytes out — no Android.
 */
object WavJoin {

    /** Sound kept before the first loud sample of a clip (the start of a soft consonant). */
    const val LEAD_KEEP_MS = 30
    /** Sound kept after the last loud sample (the natural decay of the last word). */
    const val TAIL_KEEP_MS = 60
    /** |sample| above this is voice (about -38 dBFS); below is the service's padding. */
    const val SILENCE_LEVEL = 400

    data class Pcm(val sampleRate: Int, val channels: Int, val data: ByteArray) {
        val frameBytes: Int get() = channels * 2
        val durationMs: Long get() = if (sampleRate <= 0) 0 else data.size.toLong() / frameBytes * 1000 / sampleRate
    }

    fun parse(bytes: ByteArray): Pcm? {
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var format = 1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            if (size < 0) return null
            if (id == "fmt " && pos + 24 <= bytes.size) {
                format = le16(bytes, pos + 8)
                channels = le16(bytes, pos + 10)
                sampleRate = le32(bytes, pos + 12)
                bits = le16(bytes, pos + 22)
            } else if (id == "data") {
                if (format != 1 || bits != 16 || channels <= 0 || sampleRate <= 0) return null
                val start = pos + 8
                val len = minOf(size, bytes.size - start).let { it - it % (channels * 2) }
                return Pcm(sampleRate, channels, bytes.copyOfRange(start, start + len))
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }

    /** The clip without its padding: [LEAD_KEEP_MS] before the first and [TAIL_KEEP_MS] after the last loud sample. */
    fun trim(pcm: Pcm): Pcm {
        val frames = pcm.data.size / pcm.frameBytes
        fun loud(frame: Int): Boolean {
            for (c in 0 until pcm.channels) {
                val i = frame * pcm.frameBytes + c * 2
                if (abs(sample(pcm.data, i)) > SILENCE_LEVEL) return true
            }
            return false
        }
        var first = 0
        while (first < frames && !loud(first)) first++
        if (first == frames) return pcm // all quiet: leave it as it is
        var last = frames - 1
        while (last > first && !loud(last)) last--
        val from = (first - pcm.sampleRate * LEAD_KEEP_MS / 1000).coerceAtLeast(0)
        val to = (last + 1 + pcm.sampleRate * TAIL_KEEP_MS / 1000).coerceAtMost(frames)
        return pcm.copy(data = pcm.data.copyOfRange(from * pcm.frameBytes, to * pcm.frameBytes))
    }

    /**
     * [clips] (WAV files) as one WAV: each trimmed, with [gapsMs][i] of silence after clip i.
     * Null when a clip can't be read or the clips don't match.
     */
    fun join(clips: List<ByteArray>, gapsMs: List<Long>): ByteArray? {
        if (clips.isEmpty()) return null
        val pcms = clips.map { parse(it) ?: return null }
        val rate = pcms.first().sampleRate
        val channels = pcms.first().channels
        if (pcms.any { it.sampleRate != rate || it.channels != channels }) return null
        val out = ByteArrayOutputStream()
        pcms.forEachIndexed { i, p ->
            out.write(trim(p).data)
            if (i < pcms.lastIndex) {
                val gap = gapsMs.getOrElse(i) { 0L }.coerceIn(0L, 5_000L)
                out.write(ByteArray((rate * gap / 1000).toInt() * channels * 2))
            }
        }
        return wav(out.toByteArray(), rate, channels)
    }

    /** A 16-bit PCM WAV file around [data]. */
    fun wav(data: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + data.size)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        ascii("RIFF"); i32(36 + data.size); ascii("WAVE")
        ascii("fmt "); i32(16); i16(1); i16(channels); i32(sampleRate); i32(sampleRate * channels * 2); i16(channels * 2); i16(16)
        ascii("data"); i32(data.size)
        out.write(data)
        return out.toByteArray()
    }

    /** The silences inside a clip longer than [minMs] (start ms, length ms) — for tests and logs. */
    fun silences(pcm: Pcm, minMs: Int = 100): List<Pair<Long, Long>> {
        val frames = pcm.data.size / pcm.frameBytes
        val found = mutableListOf<Pair<Long, Long>>()
        var start = -1
        for (f in 0..frames) {
            val quiet = f < frames && (0 until pcm.channels).all { c -> abs(sample(pcm.data, f * pcm.frameBytes + c * 2)) <= SILENCE_LEVEL }
            if (quiet && start < 0) start = f
            if (!quiet && start >= 0) {
                val ms = (f - start).toLong() * 1000 / pcm.sampleRate
                if (ms >= minMs) found += (start.toLong() * 1000 / pcm.sampleRate) to ms
                start = -1
            }
        }
        return found
    }

    private fun sample(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()
    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
}
