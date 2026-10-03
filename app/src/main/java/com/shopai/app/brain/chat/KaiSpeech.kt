package com.shopai.app.brain.chat

/**
 * How Kai's answer is delivered: spoken (TTS) only when the owner spoke —
 * typed messages get text only. The answer itself (intent, result, words)
 * is the same either way.
 */
object KaiSpeech {
    data class Line(val speech: String, val languageTag: String)

    private val emoji = Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2705}]""")

    /** What to say aloud for [text]; null when the owner typed (no voice for typed messages). */
    fun forReply(text: String, voice: Boolean): Line? {
        if (!voice) return null
        val speech = text.replace("`", "").replace(emoji, "").replace(Regex("""\s+"""), " ").trim()
        if (speech.isEmpty()) return null
        return Line(speech, if (text.any { it in '஀'..'௿' }) "ta-IN" else "en-IN")
    }
}
