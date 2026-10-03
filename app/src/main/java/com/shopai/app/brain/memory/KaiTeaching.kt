package com.shopai.app.brain.memory

import java.util.Locale

/** What the owner said about Kai's private language (not a business action). */
sealed interface TeachCommand {
    /** "From now on `thooki kudu` means payment out" / "pottudu na stock in" / "red paste na Colgate 200g". */
    data class Teach(val phrase: String, val target: TeachTarget, val correction: Boolean) : TeachCommand

    /** "Today pottudu means stock out" — this conversation only; Kai asks whether to keep it. */
    data class ForNow(val phrase: String, val meaning: KaiMeaning) : TeachCommand

    /** "Forget thooki kudu" / "thooki kudu marandhudu"; [phrase] null = "idha marandhudu" (the last one). */
    data class Forget(val phrase: String?) : TeachCommand

    /** "Idha remember pannu" — keep what was just used / suggested. */
    data object RememberThat : TeachCommand

    /** "Enna enna kathukitta?" / "what have you learned?" */
    data object ShowMemory : TeachCommand
}

sealed interface TeachTarget {
    data class Meaning(val meaning: KaiMeaning) : TeachTarget
    data class Entity(val entity: KnownEntity) : TeachTarget
}

/**
 * Rules for teaching / forgetting Kai's private language — Tamil, Tanglish
 * and English. A "X na Y" sentence is a teaching only when Y is clearly an
 * action meaning or one of the business's own records; anything else is a
 * normal message for the global core.
 */
object KaiTeaching {

    private val yesWords = setOf(
        "aama", "ama", "aamaa", "aam", "yes", "yes yes", "correct", "correctu", "adhaan", "adhan", "athaan", "adhu dhaan", "right", "same",
        "exactly", "sari", "seri", "ok", "okay", "haan", "s", "yes correct", "aama correct", "ஆம்", "ஆமா", "சரி", "அதான்", "கரெக்ட்",
    )
    private val noWords = setOf(
        "illa", "illai", "ille", "no", "nope", "venam", "vendam", "wrong", "thappu", "tappu", "not that", "illa illa", "இல்ல", "இல்லை", "வேண்டாம்", "தப்பு",
    )

    fun isYes(text: String) = KaiPrivateMemory.normalize(text) in yesWords

    fun isNo(text: String) = KaiPrivateMemory.normalize(text) in noWords

    /** Words that name an action meaning ("payment out", "stock in", "பணம் கொடுத்தது"). */
    fun meaningIn(words: String): KaiMeaning? {
        val w = " " + KaiPrivateMemory.normalize(words) + " "
        fun has(vararg s: String) = s.any { w.contains(" $it ") || (it.any { c -> c.code > 0x0B80 } && w.contains(it)) }
        return when {
            has("payment out", "pay out", "money out", "paid out", "panam kudukkuradhu", "panam kuduthadhu", "kudukkuradhu", "பணம் கொடுத்தது", "கொடுத்தது") -> KaiMeaning.PAYMENT_OUT
            has("payment in", "money in", "panam vandhadhu", "vasool", "collection", "received payment", "பணம் வந்தது", "வசூல்") -> KaiMeaning.PAYMENT_IN
            has("stock in", "stock add", "stock ulla", "stock ullae", "inward", "ஸ்டாக் உள்ளே", "ஸ்டாக் சேர்") -> KaiMeaning.STOCK_IN
            has("stock out", "stock remove", "stock veliya", "stock veliye", "outward", "ஸ்டாக் வெளியே") -> KaiMeaning.STOCK_OUT
            has("business summary", "summary", "kanakku summary", "வியாபார சுருக்கம்") -> KaiMeaning.BUSINESS_SUMMARY
            has("receivable", "receivables", "receivable summary", "pending summary", "yaar tharanum", "vara vendiyadhu", "வர வேண்டியது") -> KaiMeaning.RECEIVABLE_SUMMARY
            has("payable", "payables", "payable summary", "kudukka vendiyadhu", "கொடுக்க வேண்டியது") -> KaiMeaning.PAYABLE_SUMMARY
            has("low stock", "stock kammi", "reorder list") -> KaiMeaning.LOW_STOCK
            has("cash balance", "cash evlo", "kaila cash", "கையில் பணம்") -> KaiMeaning.CASH_BALANCE
            else -> null
        }
    }

    /** One of the business's records named in [words] (exact name, any case). */
    fun entityIn(words: String, entities: List<KnownEntity>): KnownEntity? {
        val n = KaiPrivateMemory.normalize(words)
        if (n.isEmpty()) return null
        entities.filter { KaiPrivateMemory.normalize(it.name) == n }.singleOrNull()?.let { return it }
        // "Colgate 200g-aa", "Kumar Traders customer" — the record's full name inside the words.
        return entities.filter { e -> KaiPrivateMemory.contains(n, KaiPrivateMemory.normalize(e.name)) }
            .sortedByDescending { it.name.length }
            .let { list -> list.firstOrNull()?.takeIf { first -> list.count { it.name.length == first.name.length } == 1 } }
    }

    private val forNowWords = listOf("today", "innaiku mattum", "innaikku mattum", "inniku mattum", "for now", "ippothaikku", "indha time mattum", "இன்னைக்கு மட்டும்")

    /**
     * A teaching / forgetting sentence, or null for a normal message. [entities]
     * are the business's own products / customers / suppliers.
     */
    fun parse(text: String, entities: List<KnownEntity>): TeachCommand? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val n = KaiPrivateMemory.normalize(raw)

        if (n in setOf("idha remember pannu", "idha nyabagam vechuko", "remember this", "remember that", "idha remember panniko", "இதை நினைவில் வை", "idha gnabagam vechuko")) {
            return TeachCommand.RememberThat
        }
        if (n in setOf("idha marandhudu", "adha marandhudu", "forget this", "forget this meaning", "forget that", "remove this slang", "idha maranthudu", "இதை மறந்துடு")) {
            return TeachCommand.Forget(null)
        }
        if (Regex("""(enna enna|edhellam|what have you|what did you) .*(kathukitta|kathukittinga|learn|learned|learnt|remember)""").containsMatchIn(n) ||
            n in setOf("my kai language", "kai language", "en kai language", "show my slang")
        ) return TeachCommand.ShowMemory

        // Forget a phrase: "forget thooki kudu", "remove thooki kudu slang", "thooki kudu marandhudu".
        Regex("""^(?:forget|remove|delete)\s+(?:the\s+)?(?:slang\s+|meaning\s+(?:of\s+)?)?(.+?)(?:\s+(?:slang|meaning))?$""").find(n)?.let { m ->
            return TeachCommand.Forget(m.groupValues[1].trim().takeIf { it.isNotEmpty() })
        }
        Regex("""^(.+?)\s*(?:a|ah|ai|va|vai|nu sonnadha|meaning)?\s+(?:marandhudu|maranthudu|marandhidu|marandhu vidu|forget pannu|remove pannu|delete pannu|மறந்துடு)$""").find(n)?.let { m ->
            val p = m.groupValues[1].trim().removeSuffix(" slang").trim()
            return TeachCommand.Forget(p.takeIf { it.isNotEmpty() && it !in setOf("idha", "adha", "this", "that") })
        }

        // Correction prefix: "Illai, pottudu-na stock IN".
        var body = raw
        var correction = false
        Regex("""^(?i)(illai|illa|ille|no|இல்லை|இல்ல)[\s,.!-]+""").find(body)?.let {
            correction = true
            body = body.substring(it.range.last + 1)
        }
        // "From now on", "inimel", "ini" — a permanent instruction.
        body = body.replace(Regex("""^(?i)(from now on|inimel|inime|ini mel|ini|இனிமேல்)[\s,]+"""), "")
        val forNow = forNowWords.any { (" " + KaiPrivateMemory.normalize(body) + " ").contains(" $it ") }
        if (forNow) {
            forNowWords.forEach { w -> body = body.replace(Regex("""(?i)(?<![\p{L}])${Regex.escape(w)}(?![\p{L}])"""), " ") }
        }
        // Drop a trailing "remember pannu / nu vechuko / nu nyabagam vechuko".
        body = body.replace(Regex("""(?i)[\s,]*(-?\s*nu\s+)?(remember\s+(pannu|panniko|pannikko|vechuko)|nyabagam\s+vechuko|gnabagam\s+vechuko|vechuko|ninaivil vai)\s*\.?$"""), "")

        // "X means Y", "X na Y", "X-na Y", "X nna Y", "X endral Y", "X-nu sonna Y", "X-னா Y".
        val m = Regex("""^\s*[`'"“‘]?(.+?)[`'"”’]?(?:\s*-\s*|\s+)(?:means|mean|na|nna|naa|ndraa|ndra|endral|nu sonna|nu sonnaa|னா|என்றால்|=)\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)
            .find(body) ?: return null
        val phrase = m.groupValues[1].trim().trim('`', '\'', '"', '“', '”', '‘', '’', '-').trim()
        val rest = m.groupValues[2].trim().trim('`', '\'', '"', '“', '”', '‘', '’').trim()
            .replace(Regex("""(?i)[\s-]*(aa|ah|nu|-nu)$"""), "").trim()
        if (phrase.isEmpty() || phrase.split(' ').size > 5 || rest.isEmpty()) return null
        // A question ("Kumar na yaaru?") is not a teaching.
        if (raw.contains('?')) return null

        meaningIn(rest)?.let { meaning ->
            return if (forNow) TeachCommand.ForNow(phrase, meaning) else TeachCommand.Teach(phrase, TeachTarget.Meaning(meaning), correction)
        }
        entityIn(rest, entities)?.let { e ->
            // A nickname must differ from the real name.
            if (KaiPrivateMemory.normalize(phrase) == KaiPrivateMemory.normalize(e.name)) return null
            return TeachCommand.Teach(phrase, TeachTarget.Entity(e), correction)
        }
        return null
    }

    // ------------------------------------------------------------ unknown phrases

    /** Words the global core already understands (or that carry no action) — never "unknown". */
    private val known = setOf(
        // people / filler / politeness
        "owner", "kai", "sir", "please", "pls", "bro", "anna", "akka", "thambi", "naan", "nan", "enakku", "ennaku", "avan", "aval", "avar", "avanga",
        "ku", "kku", "ukku", "kitta", "kita", "kitte", "idam", "oda", "la", "le", "um", "and", "the", "to", "from", "for", "of", "a", "an", "is", "it",
        "rs", "rupees", "rupee", "rubai", "ruba", "amount", "panam", "kaasu", "cash", "upi", "gpay", "bank", "today", "inniku", "innaikku", "innaiku",
        "nethu", "naalaikku", "yesterday", "tomorrow", "ippo", "now", "indha", "intha", "andha", "antha", "idhu", "adhu",
        // global action / question words
        "pannu", "pannunga", "panniten", "pannen", "pannidu", "kuduthen", "koduthen", "kuduthuten", "vanginen", "vaanginen", "vangiten", "paid", "pay",
        "payment", "received", "gave", "given", "sent", "anuppinen", "vandhuchu", "vanthuchu", "thandhan", "credit", "debit", "sale", "purchase",
        "stock", "in", "out", "add", "remove", "sold", "pochu", "evlo", "evvalavu", "enna", "eppo", "yaar", "yaaru", "how", "what", "when", "who", "which",
        "pending", "baaki", "bakki", "tharanum", "kudukkanum", "kodukkanum", "vanganum", "balance", "due", "irukku", "iruku", "remind", "reminder", "call",
        "phone", "bill", "summary", "total", "sollu", "sollunga", "kaatu", "paaru", "check", "ah", "aa", "va", "nu",
        "pcs", "kg", "bag", "bags", "box", "packet", "litre", "dozen", "k",
    )

    /**
     * The words Kai does not know in a message that looks like an action
     * (a person / product and a number). Null when nothing is unknown.
     */
    fun unknownPhrase(text: String, people: List<String>, products: List<String>): String? {
        var t = " " + KaiPrivateMemory.normalize(text) + " "
        // Remove the business's own names (longest first) and numbers.
        (people + products).filter { it.isNotBlank() }.map(KaiPrivateMemory::normalize).sortedByDescending { it.length }.forEach { name ->
            t = t.replace(Regex("""(?<![\p{L}\p{M}])${Regex.escape(name)}(?![\p{L}\p{M}])"""), " ")
        }
        val tokens = t.replace(Regex("""₹?\d[\d,.]*\s*k?"""), " ").split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val unknown = tokens.filter { it !in known && it.length >= 2 }
        if (unknown.isEmpty() || unknown.size > 3) return null
        // Must be one run of words in the original order.
        val start = tokens.indexOf(unknown.first())
        val run = tokens.drop(start).take(unknown.size)
        if (run != unknown) return null
        return unknown.joinToString(" ")
    }

    fun hasAmount(text: String): Boolean = Regex("""(?<![\p{L}])₹?\d[\d,]*(\.\d+)?\s*(k\b)?""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun lower(s: String) = s.lowercase(Locale.ROOT)
}
