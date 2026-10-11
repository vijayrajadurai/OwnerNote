package com.shopai.app.data.tts

/**
 * A long answer cut into pieces the natural-voice proxy accepts (it refuses more than 500 characters — apps/tts-proxy
 * `MAX_TEXT_LENGTH`), at the places a person pauses: a new line, a sentence end, then a comma / dash, a space last.
 * The pieces, spoken one after another, are the whole text — nothing dropped, nothing reordered.
 *
 * Owner, 11 Oct 2026: a long list ("yaaru late-aa payment pannuvaanga") was refused whole, so the phone's own (female)
 * voice read it instead of Kai's. Pure text — no Android.
 */
object SpeechChunks {

    /** Under the proxy's 500, with room for the language tag and any padding it adds. */
    const val MAX_CHARS = 450

    fun split(text: String, max: Int = MAX_CHARS): List<String> {
        val t = text.trim()
        if (t.length <= max) return if (t.isEmpty()) emptyList() else listOf(t)
        val sentences = t.split(Regex("""(?<=[.!?।])\s+|\s*\n+\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { s -> if (s.length <= max) listOf(s) else cut(s, max) }
        // Short sentences together, so the voice keeps its flow and fewer requests are made.
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + 1 + s.length > max) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** One sentence longer than [max]: at a comma / dash, else at a space, else (one huge word) by length. */
    private fun cut(s: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            val window = rest.substring(0, max + 1)
            val at = listOf(window.lastIndexOf(", "), window.lastIndexOf(" — "), window.lastIndexOf(" - "), window.lastIndexOf("; "))
                .maxOrNull()?.takeIf { it > max / 3 }?.let { it + 1 }
                ?: window.lastIndexOf(' ').takeIf { it > max / 3 }
                ?: max
            out += rest.substring(0, at).trim()
            rest = rest.substring(at).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
