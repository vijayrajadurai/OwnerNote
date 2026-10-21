package com.shopai.app.brain.tools

import java.util.Locale

/**
 * A word Kai doesn't know, matched by its SOUND to the one word of Kai's own it is (owner, 21 Oct 2026: "voice-la nariya correct solla
 * matanga, but kai athoda intent ah purinjikanum — yeppadi sonnalum"): "taranum" / "tharnum" = tharanum, "kaden" = kadan,
 * "kudutten" / "kuduthaen" = kuduthen, "katinen" = kattinen, "vandhathu" = vandhuchu, "vithuchi" = vithuchu, "kensal" = cancel,
 * "sels" = sales, "expairy" = expiry. One rule instead of one more spelling pair each time.
 *
 * The rule: both words are written the way they sound (th / dh / d / t one sound, k / g / c one, long and short vowels one, a doubled
 * letter one), and the owner's word is Kai's word when they differ by at most one sound (one and a half for a long word; a vowel
 * counts half) —
 * and by less than from any word of another meaning. Never guessed:
 *  - a word Kai already reads, a name in the books, a product's word, a number word, a unit — never changed;
 *  - a word that could be two meanings ("kuduthan" he gave / "kuduthen" I gave both near it) — left as it is, and Kai asks;
 *  - a money or stock verb only AFTER the amount / quantity (the Tamil verb comes last: "Kumar 500 kaden"), so a new person's name
 *    before it ("Kannan 500 kadan") is never turned into a verb, and only on a line with no money verb of its own;
 *  - at most one word per meaning in a line; two words that would both become "kadan" — neither changes.
 * Nothing is written by this: the line still becomes a draft that only the owner's Confirm saves.
 */
object KaiWordSound {

    private enum class Kind(val verb: Boolean) {
        MONEY_IN(true), MONEY_OUT(true), CREDIT(true), DEBIT(true), CAME(true), SOLD(true), STOCK_IN(true),
        ASK(false), WORD(false),
    }

    /** Kai's own words (what its rules read) → the word written in their place; by meaning. */
    private val words: List<Triple<String, String, Kind>> = buildList {
        fun add(kind: Kind, vararg ws: String) = ws.forEach { w -> val (said, to) = w.split('=').let { it[0] to (it.getOrNull(1) ?: it[0]) }; add(Triple(said, to, kind)) }
        // he / they gave — money came in
        add(Kind.MONEY_IN, "kuduthan", "kuduthaan", "kuduthaanga", "kuduthutaan", "kuduthutaanga", "kuduthaar", "thandhan", "thandhaan",
            "thandhaanga", "anuppinaan", "anuppitaan", "kattinaan", "kattitaan")
        // I gave / paid — money went out
        add(Kind.MONEY_OUT, "kuduthen", "kuduthuten", "kattinen", "anuppinen", "anuppiten")
        // they owe me
        add(Kind.CREDIT, "kadan", "credit", "tharanum", "vaanganum", "tharuvan", "tharuvaanga")
        // I owe
        add(Kind.DEBIT, "kudukkanum", "debit")
        // came (money or goods), sold, unloaded
        add(Kind.CAME, "vandhuchu", "vandhadhu=vandhuchu")
        add(Kind.SOLD, "vithuchu", "vithen", "vithuten")
        add(Kind.STOCK_IN, "irakkinen")
        // questions and things asked for
        add(Kind.ASK, "evlo", "evvalavu", "yaarellam=yaar yaar", "balance", "baaki", "motham", "total", "sales", "stock", "expiry",
            "cancel", "remind", "statement", "report", "iruppu", "reminder")
    }

    private data class Known(val said: String, val to: String, val kind: Kind, val sound: String)

    /**
     * Every form Kai's own rules read ("kuduthachu", "vandhurukku", "anuppitaan" …), each standing for itself: a word Kai already reads
     * is its own nearest word, never turned into another one.
     */
    private val ruleWords: List<Triple<String, String, Kind>> by lazy {
        fun single(ws: List<String>) = ws.map { it.lowercase(Locale.ROOT) }.filter { w -> w.length >= 4 && w.all { it in 'a'..'z' } }
        single(KaiCommands.paidWords).map { Triple(it, it, Kind.MONEY_OUT) } + single(KaiCommands.receivedWords).map { Triple(it, it, Kind.MONEY_IN) } +
            single(KaiStock.stockInWords).map { Triple(it, it, Kind.STOCK_IN) } + single(KaiStock.stockOutWords).map { Triple(it, it, Kind.SOLD) }
    }

    private val curated: List<Known> by lazy { words.map { (said, to, kind) -> Known(said, to, kind, sound(said)) } }
    // (a rule's word that is also one of the words above — "sales", "expiry" — is that word, once: never two meanings for one word)
    private val known: List<Known> by lazy {
        curated + ruleWords.filter { (said, _, _) -> curated.none { it.said == said || it.to == said } }.map { (said, to, kind) -> Known(said, to, kind, sound(said)) }
    }

    /** A word as it sounds: one letter per sound, doubled letters once. */
    fun sound(word: String): String {
        var s = word.lowercase(Locale.ROOT).filter { it in 'a'..'z' }
        s = s.replace("x", "ks").replace("q", "k").replace("ph", "p").replace(Regex("c(?=[eiy])"), "s").replace("ch", "s").replace("sh", "s")
            .replace("c", "k").replace("th", "t").replace("dh", "t").replace("zh", "l").replace("d", "t").replace("g", "k").replace("b", "p")
            .replace("w", "v").replace("f", "p")
            .replace("aa", "a").replace("ae", "e").replace("ee", "i").replace("ii", "i").replace("oo", "u").replace("uu", "u").replace("o", "u")
        return s.replace(Regex("""(.)\1+"""), "$1")
    }

    /** How far apart two sounds are, in half steps: a vowel changed, added or dropped is half a step ("sels" / "sales"), a consonant a whole one. */
    private fun distance(a: String, b: String): Int {
        fun cost(c: Char) = if (c in vowels) 1 else 2
        val d = IntArray(b.length + 1)
        for (j in 1..b.length) d[j] = d[j - 1] + cost(b[j - 1])
        for (i in 1..a.length) {
            var prev = d[0]; d[0] += cost(a[i - 1])
            for (j in 1..b.length) {
                val cur = d[j]
                val sub = if (a[i - 1] == b[j - 1]) 0 else if (a[i - 1] in vowels && b[j - 1] in vowels) 1 else 2
                d[j] = minOf(d[j] + cost(a[i - 1]), d[j - 1] + cost(b[j - 1]), prev + sub)
                prev = cur
            }
        }
        return d[b.length]
    }

    private val vowels = setOf('a', 'e', 'i', 'u')

    /** The one word of Kai's this sounds like, or null (too short, nothing near, or near words of two meanings). */
    private fun nearest(word: String): Known? {
        val s = sound(word)
        if (s.length < 4) return null
        // Half steps: one consonant (or two vowels) for a short word, a consonant and a vowel for a long one — "thakkali" is never
        // "total", "vengayam" never "vaanganum", "return" never "report", "current" never "credit".
        val most = if (s.length <= 5) 2 else 3
        val near = known.filter { k -> (k.sound[0] == s[0] || k.sound[0] in vowels && s[0] in vowels) }
            .map { it to distance(s, it.sound) }.filter { it.second <= most }
        val best = near.minOfOrNull { it.second } ?: return null
        val at = near.filter { it.second == best }.map { it.first }
        // The word itself is a word Kai reads: itself. One that sounds exactly like a rule's word ("kaden" = "katten", I paid) but is
        // also a step from a word of another meaning ("kadan", credit): never chosen between — Kai asks.
        if (best == 0) {
            // (a correction of Kai's first — "vanthathu" is "vandhuchu" — then the word itself)
            at.firstOrNull { it in curated && it.to != it.said }?.let { return it }
            at.firstOrNull { it.said == word.lowercase(Locale.ROOT) }?.let { return it }
            at.firstOrNull { it in curated }?.let { return it }
            if (near.any { (k, d) -> d <= 1 && k.kind != at.first().kind }) return null
            return at.first()
        }
        // Near words of two meanings at the same distance ("kuduthan" / "kuduthen"): never chosen between.
        if (at.map { it.kind }.distinct().size != 1) return null
        return at.first()
    }

    /** The word of Kai's that [word] sounds like ("sels" → "sales"), or null — so Kai never asks what such a word means. */
    fun readsAs(word: String): String? = nearest(word)?.to

    private val wordRe = Regex("""[A-Za-z]+""")
    private val amountRe = Regex("""\d""")
    private val caseAfter = Regex("""^\s*-?\s*(?:ku|kku|kitta|kita|oda|ooda|idam|kittendhu)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val caseOn = Regex("""(?i)(?:ku|kitta|kita|oda)$""")

    /**
     * [text] with each word Kai can't read written as the word of Kai's it sounds like; [keep] are the shop's own words (its people's
     * and products' names, lower case) — never changed.
     */
    fun fix(text: String, keep: Set<String>): String = fix(text, keep, null)

    /**
     * [text] with only [words] (the words Kai was about to ask about) changed — null when none of them sounds like a word of Kai's.
     * This is how Kai uses the rule: a line it already reads is never touched.
     */
    fun fixWords(text: String, words: Collection<String>, keep: Set<String>): String? {
        val only = words.map { it.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
        if (only.isEmpty()) return null
        return fix(text, keep, only).takeIf { it != text }
    }

    private fun fix(text: String, keep: Set<String>, only: Set<String>?): String {
        val ms = wordRe.findAll(text).toList()
        if (ms.isEmpty()) return text
        val firstAmount = amountRe.find(text)?.range?.first ?: com.shopai.app.brain.KaiUnderstanding.let { u ->
            ms.firstOrNull { u.isNumberWord(it.value) }?.range?.first
        }
        // What the line already says: a money / stock verb of its own means no other verb is guessed into it.
        val saidKinds = ms.mapNotNull { m -> m.value.lowercase(Locale.ROOT).let { w -> words.firstOrNull { it.first == w || it.second == w }?.third } }.toSet()
        val saidWords = ms.map { it.value.lowercase(Locale.ROOT) }.toSet()
        fun isVerb(w: String) = w.lowercase(Locale.ROOT).let { l -> words.any { (it.first == l || it.second == l) && it.third.verb } || moneyVerbs.any { v -> v.matches(l) } }
        val changes = ms.mapIndexedNotNull { i, m ->
            val w = m.value
            val lower = w.lowercase(Locale.ROOT)
            if (only != null && lower !in only) return@mapIndexedNotNull null
            if (readsAs(lower) == lower || lower in keep || only == null && isKnown(lower)) return@mapIndexedNotNull null
            // "… Ramesh" typed with a capital in the middle of a line: a name.
            if (i > 0 && w[0].isUpperCase()) return@mapIndexedNotNull null
            // "Kannanku", "Kannan kitta": a person.
            if (caseOn.containsMatchIn(lower) || caseAfter.containsMatchIn(text.substring(m.range.last + 1))) return@mapIndexedNotNull null
            // (only to one of Kai's own words above: a rule's form — "pottaanga" — keeps itself but is never what another word becomes)
            val k = nearest(w)?.takeIf { it in curated } ?: return@mapIndexedNotNull null
            if (k.kind.verb && (firstAmount == null || m.range.first < firstAmount || ms.any { o -> o !== m && isVerb(o.value) })) return@mapIndexedNotNull null
            if (k.kind.verb && k.kind in saidKinds || k.to in saidWords) return@mapIndexedNotNull null
            m to k
        }
        // Two words that would both become the same verb (or the same word): neither (one of them may be a name).
        val kept = changes.groupBy { if (it.second.kind.verb) it.second.kind.name else it.second.to }.filterValues { it.size == 1 }.values.flatten()
        if (kept.isEmpty()) return text
        val sb = StringBuilder(text)
        for ((m, k) in kept.sortedByDescending { it.first.range.first }) sb.replace(m.range.first, m.range.last + 1, k.to)
        return sb.toString()
    }

    /** Words Kai's rules read already: its everyday words, number words, units. */
    private fun isKnown(w: String): Boolean = com.shopai.app.brain.chat.KaiLexicon.knows(w) || com.shopai.app.brain.KaiUnderstanding.isNumberWord(w) ||
        KaiUnits.canon(w) != w.uppercase(Locale.ROOT) || KaiFeed.spellings.containsKey(w) || KaiFeed.verbSpelling(w) != null

    /** Money verbs the rules read in all their forms ("kuduthutaan", "kattiten") — a line with one already has its verb. */
    private val moneyVerbs = listOf(
        Regex("""k[ou]duth\p{L}*"""), Regex("""thandh\p{L}*|thanth\p{L}*"""), Regex("""katti\p{L}*"""), Regex("""anupp?i\p{L}*"""),
        Regex("""thar(?:anum|uv\p{L}*|en)"""), Regex("""vaang\p{L}*|vang\p{L}*"""), Regex("""kudukk?anum|kodukk?anum"""),
        Regex("""vandh\p{L}*|vanth\p{L}*"""), Regex("""vith\p{L}*|vitt\p{L}*"""), Regex("""pochu|poachu"""), Regex("""kadan\p{L}*|credit|debit"""),
    )
}
