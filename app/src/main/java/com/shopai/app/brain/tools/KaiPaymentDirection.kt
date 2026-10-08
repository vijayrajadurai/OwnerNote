package com.shopai.app.brain.tools

import java.util.Locale

/** Who owes whom in what the owner said. */
enum class OwedDirection {
    /** The person owes the owner — the owner receives ("Kumar enakku 3000 tharanum"). */
    RECEIVABLE,
    /** The owner owes the person — the owner pays ("naan Kumar-ku 3000 tharanum"). */
    PAYABLE,
}

/**
 * The one place that decides the direction of money the owner talks about. The verb alone never
 * decides it — "tharanum" is in both "Kumar enakku 3000 tharanum" (Kumar gives me) and "naan
 * Kumar-ku 3000 tharanum" (I give Kumar). What decides it is who gives and who gets:
 *
 *  - a **taking** verb (vaanganum, collect, varanum): the owner takes from the person → RECEIVABLE
 *    (unless the person takes from the owner: "Kumar en kitta vaanganum" → PAYABLE);
 *  - a **giving** verb (tharanum, kudukkanum, pay pannanum, kattanum, owe):
 *      the owner is the receiver ("enakku", "எனக்கு", "to me")      → RECEIVABLE
 *      the owner is the subject ("naan", "நான்", "I")                 → PAYABLE
 *      the person is the receiver ("Kumar-ku", "குமாருக்கு")          → PAYABLE
 *      otherwise the person is the one who gives ("Kumar 3000 tharanum") → RECEIVABLE.
 *
 * Tamil script, Tanglish (with common misspellings) and English. Plain rules, no AI. Null when the
 * sentence has no money verb.
 */
object KaiPaymentDirection {

    private const val B = """(?<![\p{L}\p{M}])"""
    private const val E = """(?![\p{L}\p{M}])"""

    private val giving = Regex(
        """$B(tharanum|tharanu|tharanam|tharan|kudukan|kodukan|tharavendum|thara\s*vendum|thara\s*venum|thara\s*venam|tharuvaan|kudukanum|kudukkanum|kodukkanum|kodukanum|kudukanu|""" +
            """kudukka\s*venum|kudukka\s*vendum|kodukka\s*vendum|pay\s*pannanum|pay\s*panna\s*venum|payment\s*pannanum|kattanum|katta\s*venum|settle\s*pannanum|""" +
            """owe|owes|give)$E|தரணும்|தர\s*வேண்டும்|தரவேண்டும்|கொடுக்கணும்|குடுக்கணும்|கொடுக்க\s*வேண்டும்|கட்டணும்""",
        RegexOption.IGNORE_CASE,
    )
    private val taking = Regex(
        """$B(vanganum|vaanganum|vanganu|vaanganu|vangan|vaangan|vangu|vaangu|vanga\s*venum|vaanga\s*venum|collect\s*pannanum|collect|varanum|vasool|""" +
            """receive|get)$E|வாங்கணும்|வாங்க\s*வேண்டும்|வரணும்|வசூல்""",
        RegexOption.IGNORE_CASE,
    )
    /** The owner is the one who gets the money. "Kumar enna 3000 tharanum" — "enna" right before the amount is "enakku" said fast. */
    private val ownerReceives = Regex(
        """$B(enakku|enaku|ennaku|enakk|enakkum|yenakku|yenaku|yennaku|yenakk|yenakkum|to\s*me|owes\s*me|owe\s*me)$E|${B}enna(?=\s*(?:₹|rs\.?|rupees?)?\s*\d)|எனக்கு""",
        RegexOption.IGNORE_CASE,
    )
    /** The owner is the one who gives the money. */
    private val ownerGives = Regex("""$B(naan|nan|naa|i)$E|நான்""", RegexOption.IGNORE_CASE)
    /** "Kumar en kitta vaanganum": the person takes from the owner. */
    private val fromOwner = Regex("""$B(enkitta|en\s*kitta|ennidam|ennidamirundhu|from\s*me)$E|என்கிட்ட|என்னிடம்""", RegexOption.IGNORE_CASE)

    /** Words that end in -ku but are not a person's dative ("enakku", "innaikku", "yaarukku", "manikku"…). */
    private val notDative = setOf(
        "ena", "enak", "enna", "yena", "yenak", "yenna", "una", "unak", "unna", "yaar", "yaaru", "yar", "yaru", "innai", "innaik", "inni", "innik", "naalai", "naalaik", "nalai",
        "ir", "iru", "iruk", "irukk", "irukkudh", "irukkuth",
        "mani", "manik", "adhu", "idhu", "athu", "ithu", "avan", "avanu", "avar", "avaru", "avanga", "avangalu", "time", "date", "thethi", "month", "week",
    )
    private val latinDative = Regex("""(?i)([a-z]{2,})\s*-?\s*(?:ukku|kku|ku|uku)(?![a-z])""")
    private val tamilDative = Regex("""([஀-௿]+)க்கு""")
    /** Spoken Tamil after normalising: "குமாருக்கு" → "குமார்-ku" (a Tamil name, a Tanglish ending). */
    private val mixedDative = Regex("""([஀-௿]+)\s*-?\s*(?:ukku|kku|ku)(?![a-z])""", RegexOption.IGNORE_CASE)
    /** "Lokesh 9876543210-ku": the person named by their phone number. */
    private val numberDative = Regex("""\d{10}\s*-?\s*(?:ukku|kku|ku)(?![a-z])""", RegexOption.IGNORE_CASE)
    private val tamilNotDative = setOf("என", "உன", "யாரு", "இன்னை", "நாளை", "மணி", "அது", "இது", "அவனு", "அவரு")

    /** The sentence talks about owing / paying / collecting money. */
    fun mentionsMoneyOwed(text: String): Boolean = giving.containsMatchIn(text) || taking.containsMatchIn(text)

    fun of(raw: String): OwedDirection? {
        val text = raw.lowercase(Locale.ROOT)
        val gives = giving.containsMatchIn(text)
        val takes = taking.containsMatchIn(text)
        if (!gives && !takes) return null
        if (takes && !gives) return if (fromOwner.containsMatchIn(text)) OwedDirection.PAYABLE else OwedDirection.RECEIVABLE
        return when {
            ownerReceives.containsMatchIn(text) -> OwedDirection.RECEIVABLE
            ownerGives.containsMatchIn(text) -> OwedDirection.PAYABLE
            hasPersonDative(text) -> OwedDirection.PAYABLE
            else -> OwedDirection.RECEIVABLE
        }
    }

    /**
     * The side only when the words say whose it is — "enakku" / "naan" / "Kumar-ku" / "en kitta" — never the
     * receivable default: "Ramesh enna tharanum?" names no side, so it is asked about whichever side Ramesh is on.
     */
    fun explicitOf(raw: String): OwedDirection? {
        val text = raw.lowercase(Locale.ROOT)
        val direction = of(text) ?: return null
        val marked = ownerReceives.containsMatchIn(text) || ownerGives.containsMatchIn(text) || fromOwner.containsMatchIn(text) || hasPersonDative(text)
        return direction.takeIf { marked }
    }

    private fun hasPersonDative(text: String): Boolean =
        latinDative.findAll(text).any { it.groupValues[1] !in notDative } ||
            tamilDative.findAll(text).any { it.groupValues[1] !in tamilNotDative } ||
            mixedDative.findAll(text).any { m -> tamilNotDative.none { m.groupValues[1].startsWith(it) } } ||
            numberDative.containsMatchIn(text)
}
