package com.shopai.app.brain.tools

/**
 * How owners say an entry in other words (owner's dataset Kai_Local_Tamil_Thanglish_100000, 12 Oct 2026), said again
 * the one way Kai's entry rules already read — so a new way of saying it is one more line here, not a new code path:
 *
 *   the person owes the owner   "Kumar innum 500 rooba tharala", "Kumar thara vendiya 500 roobaya kanakkula podu",
 *                               "Kumarkitta irundhu 500 vaanganum", "Kumar baakiyoda kooduthalaa 500 varavu vai"…
 *                               → "Kumar enakku 500 tharanum"
 *   the owner owes the person   "Kumarku 500 baaki iruku", "Kumarku innum 500 kudukala", "Kumarku 500 settle pannanum",
 *                               "Kumarku kudukka vendiya baakiya 500 kootti podu"… → "naan Kumarku 500 tharanum"
 *   the person paid             "Kumar baakila 500 kattittaaru", "Kumar kudutha 500 roobaya varavula podu" → "Kumar 500 kuduthaan"
 *   the owner paid              "Kumarku kudutha 500 roobaya selavula record pannu", "Kumarku 500 settle pannitten",
 *                               "Kumarku 500 anuppitten", "Kumarku 500 kuduthutan" → "Kumarku 500 kuduthen"
 *
 * Typed Tanglish and the mic's Tamil (after [KaiSpokenWords.normalize]) read the same here. A sentence that matches none
 * is returned as it was.
 */
object KaiLedgerPhrases {

    /** A name of one to three words ("Kumar", "ABC Traders"). */
    private const val P = """((?:[\p{L}\p{M}][\p{L}\p{M}.']*\s+){0,2}?[\p{L}\p{M}][\p{L}\p{M}.']*?)"""
    /** The name with "-ku" on it, glued or apart ("Kumarku", "Kumar-ku", "ABC Traders ku", "குமாருக்கு"). */
    private const val DATIVE = """((?:[\p{L}\p{M}][\p{L}\p{M}.']*\s+){0,2}?[\p{L}\p{M}][\p{L}\p{M}.']*?(?:\s*-?\s*(?:ukku|kku|uku|ku)|க்கு))"""
    private const val FROM = """(?:\s*-?kitta|\s*-?கிட்ட)?"""
    private const val AMT = """(\d[\d,]*(?:\.\d+)?)\s*(?:rooba\p{L}*|ruba\p{L}*|roobai\p{L}*|rupees?|rs\.?)?"""
    private const val END = """\s*[.!]?$"""

    /** "konjam", "vegamaa", "kanakkula", "oru thadava", "padhivula"…: how an owner starts a line, not part of it. */
    private val opener = Regex("""^(?:konjam|vegamaa|vegama|kanakkula|(?:oru|1)\s+thadava|seekiram|padhivula|sari|seri|சரி|ippo|please)\s+""", RegexOption.IGNORE_CASE)
    /** "enaku Naan Kumarku 500 kudukanum" / "enaku Mani enaku 900 kudukanum": a leading "enakku" said before the line itself. */
    private val leadingEnakku = Regex("""^(?:enaku|enakku)\s+(?=naan\b|nan\b|\p{Lu}|[஀-௿]|\S+\s+(?:enaku|enakku)\b)""", RegexOption.IGNORE_CASE)

    private fun rx(s: String) = Regex("^$s$END", RegexOption.IGNORE_CASE)

    private val theyOwe = listOf(
        rx("""$P\s+innum\s+$AMT\s+tharala"""),
        rx("""$P\s+(?:thara|தர)\s+vendiya\s+$AMT\S*\s+kanakkula\s+podu"""),
        rx("""$P$FROM\s+irundhu\s+$AMT\s+vaanganum"""),
        rx("""$P\s+kitta\s+$AMT\s+varanum"""),
        rx("""$P\s+(?:enaku|enakku)\s+(?:thara|தர)\s+vendiya\s+kanakkula\s+innum\s+$AMT\s+(?:ser\p{L}*|சேர்)"""),
        rx("""$P\s+baakiyoda\s+kooduthalaa?\s+$AMT\s+(?:varavu|வரவு)\s+(?:vai|வை)"""),
        rx("""$P$FROM\s+(?:vara|வர)\s+vendiya\s+thogaiya\s+$AMT\s+adhigapaduthu"""),
    )
    private val iOwe = listOf(
        rx("""$DATIVE\s+innum\s+$AMT\s+kudukala"""),
        rx("""$DATIVE\s+(?:kudukka|கொடுக்க|குடுக்க)\s+vendiya\s+$AMT\S*\s+kanakkula\s+podu"""),
        rx("""$DATIVE\s+$AMT\s+settle\s+pannanum"""),
        rx("""$DATIVE\s+$AMT\s+baaki\s+irukk?u"""),
        rx("""$DATIVE\s+naan\s+(?:thara|தர)\s+vendiya\s+kanakkula\s+innum\s+$AMT\s+(?:ser\p{L}*|சேர்)"""),
        rx("""$DATIVE\s+(?:kudukka|கொடுக்க|குடுக்க)\s+vendiya\s+baakiya\s+$AMT\s+kootti\s+podu"""),
        rx("""$DATIVE\s+(?:thara|தர)\s+vendiya\s+thogaiyoda\s+$AMT\s+(?:kooda|கூட)\s+(?:ser\p{L}*|சேர்)"""),
    )
    private val theyPaid = listOf(
        rx("""$P\s+baakila\s+$AMT\s+katti\p{L}*"""),
        rx("""$P\s+kudutha\s+$AMT\S*\s+varavula\s+podu"""),
        rx("""$P\s+$AMT\s+kuduthutt?aa?ru"""),
        rx("""$P$FROM\s+irundhu\s+$AMT\s+vandhuchu"""),
    )
    private val iPaid = listOf(
        rx("""$DATIVE\s+kudutha\s+$AMT\S*\s+selavula\s+(?:record|padhivu|பதிவு)\s+pannu"""),
        rx("""$DATIVE\s+$AMT\s+settle\s+pannitt?en"""),
        // "ABC Traders ku 2000 settle": settled to them — paid out (without "-ku", "Ramesh 2000 settle" is money in).
        rx("""$DATIVE\s+$AMT\s+(?:settle|settled|clear|cleared)(?:\s+(?:pannen|panniten|pannitten|aachu|ஆச்சு))?"""),
        rx("""$DATIVE\s+$AMT\s+anupp?itt?en"""),
        // "Sri Balaji ku 786 kuduthutan" (owner, 15 Oct 2026): given TO the person — "-ku" says the owner paid, whatever the
        // ending ("kuduthuttan" is the owner's own "I gave" in many towns; the mic's குடுத்துட்டான் reads "kuduthutaan").
        rx("""(?:(?:naan|nan)\s+)?$DATIVE\s+$AMT\s+k[ou]duthutt?(?:en|aa?n)"""),
    )

    /** "Ramesh 2000 settle", "Ramesh 2000 clear aachu": with no "-ku" (read after [iPaid]), the person settled with the owner — money in. */
    private val settledBy = rx("""$P\s+$AMT\s+(?:settle|settled|clear|cleared)(?:\s+(?:aachu|ஆச்சு|pannitaan|pannitan|pannitaar))?""")

    /** "Kumaruku" is Kumar + uku, "Prabhuku" is Prabhu + ku, "Karthikku" is Karthik + ku. */
    private fun bareOf(word: String): String {
        val uku = Regex("""^(.{3,}?)u(?:kku|ku)$""", RegexOption.IGNORE_CASE).find(word)
        if (uku != null && Regex("""[^aeiou]$""", RegexOption.IGNORE_CASE).containsMatchIn(uku.groupValues[1]) &&
            !Regex("""[bdgkp]h$""", RegexOption.IGNORE_CASE).containsMatchIn(uku.groupValues[1])) return uku.groupValues[1]
        return word.replace(Regex("""-?(?:kku|ku)$""", RegexOption.IGNORE_CASE), "")
    }

    /** Words that end in -ku but are not a person ("innaikku", "naalaikku", "enakku", "yaarukku"…). */
    private val notPerson = Regex("""^(?:innaikku|innaiku|innikku|naalaikku|naalaiku|enakku|enaku|ennaku|unakku|yaarukku|yaaruku|ungalukku|avanukku|avarukku|manikku|maniku|rubaikku|roobaikku)$""", RegexOption.IGNORE_CASE)
    /** "naan Kumarku evlo tharanum?" — what the owner owes Kumar, asked as Kai's balance question reads it. */
    private val owedQuestion = Regex("""^(?:naan|nan)\s+([\p{L}][\p{L}\p{M}]*?)-?(?:ukku|kku|uku|ku|க்கு)\s+(?:innum\s+)?evlo\s+(?:tharanum|kudukanum|kudukkanum)\s*\??$""", RegexOption.IGNORE_CASE)
    /** "(naan) மணிக்கு 250 …" read as the time word "manikku": with an amount after it and no hour before it, it is Mani-ku. */
    private val maniFirst = Regex("""^((?:naan|nan)\s+)?mani(?:k)?ku(?=\s+\d)""", RegexOption.IGNORE_CASE)
    /** "Kumarku innum evlo baaki?": the name with its "-ku" said apart, so the name is looked up without it. */
    private val gluedQuestion = Regex("""^([A-Za-z][a-z]{2,}(?:ukku|kku|uku|ku))(\s+(?:innum\s+)?evlo\b.*)$""", RegexOption.IGNORE_CASE)

    /** "மணிக்கு" heard first in a line is Mani-ku (to Mani), not "at … o'clock" (no hour before it). */
    private fun person(dative: String): String = if (dative.equals("manikku", true) || dative.equals("maniku", true)) "Maniku" else dative

    /** "avarukku", "அவருக்கு": a pronoun — the person already talked about, left for the conversation to resolve. */
    private fun pronoun(word: String) = Regex("""^(?:ava|iva|அவ|இவ)""", RegexOption.IGNORE_CASE).containsMatchIn(word)

    fun canonical(text: String): String {
        var t = text.trim()
        repeat(2) { t = opener.replace(t, "") }
        t = leadingEnakku.replace(t, "")
        t = maniFirst.replace(t) { "${it.groupValues[1]}Maniku" }
        val bare = t.trim()
        fun first(list: List<Regex>) = list.firstNotNullOfOrNull { it.find(bare) }
        first(theyOwe)?.let { m -> return "${m.groupValues[1]} enakku ${m.groupValues[2]} tharanum" }
        first(iOwe)?.let { m -> return "naan ${person(m.groupValues[1])} ${m.groupValues[2]} tharanum" }
        first(theyPaid)?.let { m -> return "${m.groupValues[1]} ${m.groupValues[2]} kuduthaan" }
        first(iPaid)?.let { m -> return "${person(m.groupValues[1])} ${m.groupValues[2]} kuduthen" }
        settledBy.find(bare)?.let { m -> if (!m.groupValues[1].contains(Regex("""(?i)(?<![\p{L}])(?:naan|nan|enakku|enaku)(?![\p{L}])"""))) return "${m.groupValues[1]} ${m.groupValues[2]} kuduthaan" }
        owedQuestion.find(bare)?.let { m -> if (!notPerson.matches(m.groupValues[1] + "ku") && !pronoun(m.groupValues[1])) return "${m.groupValues[1].removeSuffix("ு")}-ku evlo kudukkanum?" }
        gluedQuestion.find(bare)?.let { m -> if (!notPerson.matches(m.groupValues[1]) && !pronoun(m.groupValues[1])) return "${bareOf(m.groupValues[1])}-ku${m.groupValues[2]}" }
        // "Kumar 500 adhu", "Kumar 500 pa": a word said after the amount that says nothing — the line is "Kumar 500" (Kai asks which way).
        val filler = Regex("""^(.*\d[\d,]*)\s+(?:adhu|athu|idhu|ithu|nu|pa|ma|da|sir|owner|anna|ok|okay|ji|அது|இது)\s*[.!]?$""", RegexOption.IGNORE_CASE)
        filler.find(bare)?.let { return it.groupValues[1] }
        return if (t != text.trim() && Regex("""\d""").containsMatchIn(t)) t else text
    }
}
