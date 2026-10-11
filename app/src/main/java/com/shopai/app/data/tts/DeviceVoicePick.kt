package com.shopai.app.data.tts

/**
 * Which of the phone's own TTS voices speaks when Kai's natural voice can't be had: a male one (owner, 11 Oct 2026 — the
 * fallback came out in a woman's voice, Kai is a man). Android gives voices no gender field, so a voice counts as male only
 * when its engine says so in its name or features ("male", "gender=male", "#male" — never "female"). None marked → null,
 * and the caller deepens the default voice's pitch instead. Pure — no Android.
 */
object DeviceVoicePick {

    data class Option(val name: String, val language: String, val country: String, val features: Set<String>, val needsNetwork: Boolean)

    private val maleMark = Regex("""(?i)(?<![a-z])male(?![a-z])|gender\s*[=:]\s*male""")

    private fun male(o: Option): Boolean =
        (o.features + o.name).any { f -> maleMark.containsMatchIn(f) && !f.contains("female", ignoreCase = true) }

    /** The male voice to use for [language] / [country]: same country first, then one that works offline. */
    fun male(options: List<Option>, language: String, country: String): String? =
        options.filter { it.language.equals(language, ignoreCase = true) && male(it) }
            .sortedWith(compareBy<Option>({ !it.country.equals(country, ignoreCase = true) }, { it.needsNetwork }, { it.name }))
            .firstOrNull()?.name

    /** The default voice, deepened, when the phone has no male voice for the language (1.0 = as it is). */
    const val DEEPER_PITCH = 0.78f
}
