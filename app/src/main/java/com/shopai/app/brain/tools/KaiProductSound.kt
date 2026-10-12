package com.shopai.app.brain.tools

import com.shopai.app.util.NameSound

/**
 * The shop's product names in what the mic wrote. Tamil speech-to-text writes an English product name in Tamil letters, one
 * word or several ("பிரிட்டானியா பிஸ்கட்" = Britannia Biscuit, "டூர் தால்" = Toor Dal, "சர்ஃப் எக்செல்" = Surf Excel). Each run
 * of Tamil words is compared — as a whole phrase, longest first — with every product the shop keeps, by sound (the consonant
 * key of [NameSound]); the one product it sounds like is put in its place, in the shop's own spelling.
 *
 * Works for any product name without a list per name (owner, 11 Oct 2026: lakhs of shops, any product name — the mic must
 * read them as the text box does). Plain rules, no network:
 *  - the whole phrase is matched, so "டெட்டால் சோப்" is "Dettol Soap" and "தால்" alone is never guessed between Dal / Dettol;
 *  - the English name is read as it is said ("Knight" = nait, "Fortune" = forchun, "Excel" = eksel, "Parle G" = parle ji), and
 *    the mic's gliding ய / வ ("பிரிட்டானியா", "ஆல் அவுட்") don't count;
 *  - a long name (5+ sounds) may still differ by one sound; a short one (Tide, Pepsi, Maida) only right before its quantity;
 *  - words Kai already reads as something else ("பே பண்ணிட்டேன்", "ஒரு", "கிலோ") are a product only when they sound exactly
 *    like it; two products alike → nothing is replaced (Kai asks, never guesses); a person in the books is never a product.
 * It reads the mic's own words, before [KaiSpokenWords.normalize] turns some of them into Tanglish ("பவுடர்" → powder).
 */
object KaiProductSound {

    private val tamilWord = Regex("""[஀-௿]+""")

    /** [out]: the shop's own name put in its place — a goods name (KaiFeed: "kathavu keel") stands for the product it names. */
    private class Product(val name: String, val key: String, val loose: String, val words: Int, val vowelFirst: Boolean, val out: String = name)

    /** Every single word of the shop's product names, by sound ("Dal" of Toor Dal, "Tea" of Tea Powder). */
    private class Word(val text: String, val loose: String)

    fun withProducts(text: String, productNames: List<String>, people: List<String> = emptyList()): String {
        if (!KaiSpokenWords.hasTamil(text)) return text
        val products = productNames.map { it.trim() }.filter { n -> n.isNotEmpty() && !KaiSpokenWords.hasTamil(n) && n.any(Char::isLetter) }.distinct()
            .mapNotNull { n ->
                val key = NameSound.key(spoken(n)).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Product(n, key, loose(key), saidWords(n), spoken(n).first() in "aeiou")
            }
        if (products.isEmpty()) return text
        // Goods by the name the owner says (KaiFeed): "கதவு கீல்" written as it is in the list, or heard like "kathavu keel".
        val names = products.map { it.name }
        val goods = KaiFeed.goodsNames.flatMap { (ks, e) -> ks.map { k -> k to ks + e } }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten().distinct() }
            .mapNotNull { (k, es) -> KaiStock.shopNameFor(es, names)?.let { k to it } }
        val goodsWritten = goods.filter { KaiSpokenWords.hasTamil(it.first) }.groupBy({ fold(it.first) }, { it.second })
            .mapValues { (_, v) -> v.distinct().singleOrNull() }
        val heardGoods = goods.filter { !KaiSpokenWords.hasTamil(it.first) && it.first.count(Char::isLetter) >= 4 }.mapNotNull { (k, out) ->
            val key = NameSound.key(spoken(k)).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Product(k, key, loose(key), k.split(Regex("""\s+""")).size, spoken(k).first() in "aeiou", out)
        }
        val parts = products.flatMap { p -> p.name.split(Regex("""\s+""")).map { w -> Word(w, NameSound.key(spoken(w)).let { if (it.isEmpty()) it else loose(it) }) } }
        val words = tamilWord.findAll(text).toList()
        if (words.isEmpty()) return text
        val out = StringBuilder()
        var copied = 0
        var i = 0
        while (i < words.size) {
            val found = (minOf(6, words.size - i) downTo 1).firstNotNullOfOrNull { span ->
                val run = words.subList(i, i + span)
                // Only words said together (spaces between them), never a person in the books.
                // "டி-ஷர்ட்", "எம்.டி.எஃப். பலகை": a hyphen or the dots of letters said one by one join words too.
                if ((1 until span).any { j -> !joiner.matches(text.substring(run[j - 1].range.last + 1, run[j].range.first)) }) return@firstNotNullOfOrNull null
                if (run.any { w -> people.any { p -> p.split(Regex("""\s+""")).any { NameSound.same(w.value, it) } } }) return@firstNotNullOfOrNull null
                // "6 எம்.எம். ஜல்லி": a goods name that starts with its size takes the number said before it.
                val said = run.joinToString(" ") { it.value }
                goodsWritten[fold(said)]?.let { return@firstNotNullOfOrNull Triple(span, it, run.first().range.first) }
                Regex("""(\d+)\s*$""").find(text.substring(copied, run.first().range.first))?.let { n ->
                    goodsWritten[fold(n.groupValues[1] + said)]?.let { return@firstNotNullOfOrNull Triple(span, it, copied + n.range.first) }
                }
                val known = run.all { KaiSpokenWords.normalize(it.value) != it.value }
                match(said, span, products + heardGoods, parts, known, beforeQuantity(text, run.last().range.last + 1))
                    ?.let { Triple(span, it, run.first().range.first) }
            }
            if (found == null) { i++; continue }
            val (span, name, from) = found
            out.append(text, copied, from).append(name)
            copied = words[i + span - 1].range.last + 1
            i += span
        }
        return if (copied == 0) text else out.append(text, copied, text.length).toString()
    }

    /** A name the shop keeps in Tamil letters said in English letters ("Clutch plate" for "கிளட்ச் பிளேட்"): the same name, by sound. */
    fun sameName(tamil: String, english: String): Boolean {
        if (!KaiSpokenWords.hasTamil(tamil) || KaiSpokenWords.hasTamil(english) || english.none(Char::isLetter)) return false
        val key = NameSound.key(spoken(english)).takeIf { it.isNotEmpty() } ?: return false
        val p = Product(english, key, loose(key), saidWords(english), spoken(english).first() in "aeiou")
        val parts = english.trim().split(Regex("""\s+""")).map { w -> Word(w, NameSound.key(spoken(w)).let { if (it.isEmpty()) it else loose(it) }) }
        val phrase = tamil.trim().replace(Regex("""[.\-]+"""), " ").replace(Regex("""\s+"""), " ").trim()
        return match(phrase, phrase.split(' ').size, listOf(p), parts, known = false, beforeQty = true) != null
    }

    /** The one product a Tamil phrase sounds like, or null (none, too little to tell, or two alike). */
    private fun match(phrase: String, span: Int, products: List<Product>, parts: List<Word>, known: Boolean, beforeQty: Boolean): String? {
        val key = NameSound.key(phrase.replace(" ", ""))
        if (key.isEmpty()) return null
        val l = loose(key)
        val vowelFirst = phrase.first() in 'அ'..'ஔ'
        val scored = products.mapNotNull { p ->
            if (p.vowelFirst != vowelFirst || p.key.first() != key.first()) return@mapNotNull null
            val d = distance(l, p.loose)
            val ok = when {
                known -> d == 0 && l.length >= 3 && span == p.words && NameSound.same(phrase.replace(" ", ""), spoken(p.name).replace(" ", ""))
                d == 0 && l.length >= 3 -> span <= p.words + 1
                // A short name (Tide, Pepsi, Maida): right before its quantity, its first vowel heard the same, and no word of another
                // product as close ("தூள்" is not Dal; "டெட்டால்" is Dettol, not the Dal of Toor Dal; "டைட்" is Tide, not Tea).
                d == 0 -> beforeQty && span == p.words && tamilVowel(phrase).intersect(latinVowel(spoken(p.name))).isNotEmpty() &&
                    shapeGap(phrase, p.name).let { gap -> gap <= 1 && parts.none { w -> w.loose == l && (spoken(w.text).first() in "aeiou") == vowelFirst && !p.name.split(Regex("""\s+""")).contains(w.text) &&
                        tamilVowel(phrase).intersect(latinVowel(spoken(w.text))).isNotEmpty() && shapeGap(phrase, w.text) <= gap } }
                d == 1 && minOf(l.length, p.loose.length) >= 5 -> span == p.words || span == 1
                else -> false
            }
            if (ok) p to d else null
        }
        val best = scored.minOfOrNull { it.second } ?: return null
        // "ஹெட்லைட்" / "டெயில் லைட்" sound alike (Headlight, Tail light): the one said in as many words.
        return scored.filter { it.second == best }.map { it.first }.let { alike -> alike.takeIf { a -> a.map { it.out }.distinct().size == 1 } ?: alike.filter { it.words == span } }
            .map { it.out }.distinct().singleOrNull()
    }

    /** How many words the mic writes for a name: "HDHMR Board" is six ("எச்.டி.எச்.எம்.ஆர். போர்டு") — letters said one by one. */
    private fun saidWords(name: String): Int = name.trim().split(Regex("""\s+""")).sumOf { w -> if (w.length in 2..6 && w.all { it in 'A'..'Z' }) w.length else 1 }

    private val joiner = Regex("""\s+|-|\.\s*""")

    /** A Tamil name however it is spaced or joined: "பாசி பருப்பு" / "பாசிப்பருப்பு", "மிளகாய்த் தூள்" / "மிளகாய் தூள்". */
    private fun fold(phrase: String): String = phrase.replace(Regex("""[\s.\-]+"""), "").replace(Regex("""([஀-௿])்(?=\1)"""), "")

    /** Right before a quantity: "2", "ஒரு", "இரண்டு" (a short name is only taken in a stock line's place for it). */
    private fun beforeQuantity(text: String, from: Int): Boolean {
        val next = text.substring(from).trimStart().split(Regex("""\s+""")).firstOrNull().orEmpty()
        return next.firstOrNull()?.isDigit() == true || KaiSpokenWords.normalize(next).firstOrNull()?.isDigit() == true
    }

    private val letterNames = mapOf('a' to "e", 'b' to "bi", 'c' to "si", 'd' to "di", 'e' to "i", 'f' to "ef", 'g' to "ji", 'h' to "ech",
        'i' to "ai", 'j' to "je", 'k' to "ke", 'l' to "el", 'm' to "em", 'n' to "en", 'o' to "o", 'p' to "pi", 'q' to "kyu", 'r' to "ar",
        's' to "es", 't' to "ti", 'u' to "yu", 'v' to "vi", 'w' to "dabilyu", 'x' to "eks", 'y' to "vai", 'z' to "zed")

    /** An English product name as it is said (and as the mic hears it): silent letters out, soft c, x = ks, a lone letter by its name. */
    private fun spoken(name: String): String = name.trim().split(Regex("""\s+""")).joinToString(" ") { w ->
        when {
            w.length == 1 && w[0].isLetter() -> letterNames[w[0].lowercaseChar()] ?: w
            w.length in 2..6 && w.all { it in 'A'..'Z' } -> w.lowercase().map { letterNames[it] ?: "$it" }.joinToString("")
            else -> w.lowercase()
                .replace(Regex("""^kn"""), "n").replace(Regex("""^wr"""), "r").replace("ght", "t").replace("gh", "")
                .replace("ture", "chur").replace("x", "ks").replace(Regex("""c(?=[eiy])"""), "s")
        }
    }

    /** How far a short Tamil word is from an English name in shape: syllables apart, +1 if one ends in a consonant and the other doesn't. */
    private fun shapeGap(phrase: String, name: String): Int {
        val ta = phrase.replace(" ", "")
        val taSyllables = ta.indices.count { i ->
            val c = ta[i]
            c in '\u0B85'..'\u0B94' || c in '\u0B95'..'\u0BB9' && ta.getOrNull(i + 1) != '்'
        }
        val taConsonantEnd = ta.endsWith("்") && !ta.endsWith("ய்")
        val en = spoken(name).split(" ").joinToString(" ") { w -> if (w.length > 3 && Regex("""[^aeiou]e$""").containsMatchIn(w)) w.dropLast(1) else w }
        val enSyllables = en.split(" ").sumOf { w -> Regex("""[aeiou]+""").findAll(w).count() + if (Regex("""[^aeiou]y$""").containsMatchIn(w)) 1 else 0 }
        val enConsonantEnd = en.lastOrNull()?.let { it !in "aeiouy" } ?: false
        return kotlin.math.abs(taSyllables - enSyllables) + if (taConsonantEnd == enConsonantEnd) 0 else 1
    }

    /** The first vowel sound of a Tamil word ("டைட்" → ai, "தூள்" → u). */
    private fun tamilVowel(phrase: String): Set<Char> {
        val c = phrase.firstOrNull { it in '\u0B85'..'\u0B94' || it in '\u0BBE'..'\u0BCC' || it == '்' }
        val first = phrase.first()
        // A consonant with no vowel sign carries "a"; with the pulli it has none of its own — the next letter's counts.
        if (first !in '\u0B85'..'\u0B94' && (c == null || c == '்' && phrase.indexOf(c) > 1)) return setOf('a')
        return when (c) {
            'அ', 'ஆ', 'ா' -> setOf('a'); 'இ', 'ஈ', 'ி', 'ீ' -> setOf('i'); 'உ', 'ஊ', 'ு', 'ூ' -> setOf('u'); 'எ', 'ஏ', 'ெ', 'ே' -> setOf('e')
            'ஐ', 'ை' -> setOf('a', 'e', 'i'); 'ஒ', 'ஓ', 'ொ', 'ோ' -> setOf('o'); 'ஔ', 'ௌ' -> setOf('a', 'o', 'u')
            else -> tamilVowel(phrase.substring(phrase.indexOf('்') + 1).ifEmpty { return setOf('a') })
        }
    }

    /** The first vowel sound of an English name as Tamil ears hear it ("Tide" → ai, "Dal" → a / e, "Surf" → a / u). */
    private fun latinVowel(spoken: String): Set<Char> {
        val w = spoken.lowercase().filter { it in 'a'..'z' }
        val m = Regex("""[aeiouy]+""").find(w, if (w.firstOrNull() == 'y') 1 else 0) ?: return emptySet()
        val v = m.value
        val magicE = Regex("""^[^aeiouy]+e""").containsMatchIn(w.substring(m.range.last + 1)) && v.length == 1
        return when {
            v.startsWith("ai") || v.startsWith("ay") || v.startsWith("ei") || v.startsWith("ey") -> setOf('a', 'e', 'i')
            v.startsWith("ee") || v.startsWith("ea") || v.startsWith("ie") -> setOf('i', 'e')
            v.startsWith("oo") -> setOf('u', 'o')
            v.startsWith("ou") || v.startsWith("ow") || v.startsWith("au") -> setOf('a', 'o', 'u')
            v.startsWith("oi") || v.startsWith("oy") -> setOf('o', 'a')
            v[0] == 'a' -> if (magicE) setOf('e', 'a') else setOf('a', 'e')
            v[0] == 'e' -> setOf('e', 'i')
            v[0] == 'i' || v[0] == 'y' -> if (magicE) setOf('a', 'e', 'i') else setOf('i')
            v[0] == 'o' -> setOf('o', 'a')
            else -> setOf('u', 'a')
        }
    }

    /** The key without the gliding sounds the mic adds or drops (ய, a ல் + அவு's வ) — the first sound always counts. */
    private fun loose(key: String) = key.first() + key.drop(1).filter { it != 'y' && it != 'v' }

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        var prev = IntArray(b.length + 1) { it }
        for (x in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = x
            for (y in 1..b.length) cur[y] = minOf(prev[y] + 1, cur[y - 1] + 1, prev[y - 1] + if (a[x - 1] == b[y - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
