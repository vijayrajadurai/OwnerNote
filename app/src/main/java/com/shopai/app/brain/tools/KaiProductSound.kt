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
    private class Product(val name: String, val key: String, val loose: String, val words: Int, val vowelFirst: Boolean, val out: String = name, val family: Boolean = false) {
        /** As few words as the mic may write it in: "GI wire" is "ஜிஐ வயர்" (two) as well as "ஜி ஐ வயர்" (three). */
        val minWords: Int get() = name.trim().split(Regex("""[\s-]+""")).count { it.isNotEmpty() }
    }

    /** Every single word of the shop's product names, by sound ("Dal" of Toor Dal, "Tea" of Tea Powder). */
    private class Word(val text: String, val loose: String)

    /** The shop's names, ready to be heard: worked out once per list of names (a big shop has thousands), not for every line. */
    private class Prepared(val products: List<Product>, val sizeless: List<Product>, val goodsWritten: Map<String, String?>, val heardGoods: List<Product>, val parts: List<Word>)

    /** The last few lists heard (the shop's products, the owner's Menu, the salon's services): each prepared once, not per line. */
    private val cache = object : LinkedHashMap<List<String>, Prepared?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, Prepared?>?) = size > 4
    }

    private fun prepared(productNames: List<String>): Prepared? {
        synchronized(cache) { if (cache.containsKey(productNames)) return cache[productNames] }
        val prep = prepare(productNames)
        synchronized(cache) { cache[productNames.toList()] = prep }
        return prep
    }

    private fun prepare(productNames: List<String>): Prepared? {
        val products = productNames.map { it.trim() }.filter { n -> n.isNotEmpty() && !KaiSpokenWords.hasTamil(n) && n.any(Char::isLetter) }.distinct()
            .flatMap { n ->
                // "ENO", "BPL": capitals are said letter by letter ("பிபிஎல்") — or as a word when they read as one ("ஈனோ").
                val asWord = n.split(Regex("""\s+""")).joinToString(" ") { w -> if (w.length in 3..6 && w.all { it in 'A'..'Z' } && w.any { it in "AEIOU" } && w.toSet().size > 1) w.lowercase() else w }
                // "Spinach / Palak 250 g": either name said alone ("ஸ்பினாச் 250 கிராம்", "பாலக் 250 கிராம்") is that product.
                // "Batteries AAA" / "Batteries AA": said "triple A" / "double A" too ("ட்ரிபிள் ஏ").
                val repeated = n.split(Regex("""\s+""")).joinToString(" ") { w ->
                    Regex("""^([A-Z])\1{1,2}$""").find(w)?.let { (if (w.length == 3) "triple " else "double ") + w[0] } ?: w
                }
                (listOfNotNull(n, asWord.takeIf { it != n }, repeated.takeIf { it != n }) + othersOf(n).filter { o -> productNames.none { it.trim().equals(o, ignoreCase = true) } }).mapNotNull { said ->
                    val key = nameKey(spoken(said)).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Product(said, key, loose(key), saidWords(said), spoken(said).first() in "aeiou", n)
                }
            }
        if (products.isEmpty()) return null
        // "பிளைவுட் 12mm" for Plywood 12mm / Plywood 18mm: the name without its size is heard too ("Plywood"); the size said after it
        // picks the product (KaiStock), or Kai asks which.
        // "Braided hose 1/2 inch" → "Braided hose" (the size and its unit both go); "1-way switch 6A" → "1-way switch".
        // "Presto Garbage bag Medium" → "Presto Garbage bag": a size word at the end goes too.
        // ("Fresh milk Small pack" → "Fresh milk": the pack named by its size goes too.)
        fun cutOf(name: String): String? {
            val ws = name.split(Regex("""\s+"""))
            val cut = ws.withIndex().indexOfFirst { (i, w) -> i > 0 && w.any(Char::isDigit) }
            return (if (cut > 0) ws.take(cut) else ws.filterNot { w -> w.any(Char::isDigit) }.let { k ->
                if (k.size > 2 && k.last().equals("pack", true) && k[k.size - 2].lowercase() in packSizes + "family") k.dropLast(2)
                else if (k.size > 1 && k.last().lowercase() in packSizes) k.dropLast(1) else k
            }).takeIf { it.isNotEmpty() && it.size < ws.size }?.joinToString(" ")
        }
        // (a reading of a name — "Batteries triple A Small pack" — is heard without its pack as the name it reads: "Batteries AAA")
        val sizeless = products.mapNotNull { p -> cutOf(p.name)?.let { b -> b to (cutOf(p.out) ?: b) } }
        // ("டோலோ 650" is Dolo 650 — a short name too, then heard only right before its quantity, as short names are.)
            .distinctBy { it.first }.filter { (b, _) -> products.none { it.name.equals(b, ignoreCase = true) } && b.count(Char::isLetter) >= 3 }.mapNotNull { (b, out) ->
                val key = nameKey(spoken(b)).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Product(b, key, loose(key), saidWords(b), spoken(b).first() in "aeiou", out, family = true)
            }
        // Goods by the name the owner says (KaiFeed): "கதவு கீல்" written as it is in the list, or heard like "kathavu keel".
        val names = products.map { it.out }.distinct()
        val goods = KaiFeed.goodsNames.flatMap { (ks, e) -> ks.map { k -> k to ks + e } }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten().distinct() }
            // (a thing kept only in packs is heard as the packs' own name: "நண்டு" = Crab, for Crab Small pack / Crab Medium pack)
            .mapNotNull { (k, es) -> (KaiStock.shopNameFor(es, names) ?: KaiStock.familyOf(es, names))?.let { k to it } }
        val goodsWritten = goods.filter { KaiSpokenWords.hasTamil(it.first) }.groupBy({ fold(it.first) }, { it.second })
            .mapValues { (_, v) -> v.distinct().singleOrNull() }
        val heardGoods = goods.filter { !KaiSpokenWords.hasTamil(it.first) && it.first.count(Char::isLetter) >= 4 }.mapNotNull { (k, out) ->
            val key = keyOf(spoken(k)).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Product(k, key, loose(key), k.split(Regex("""\s+""")).size, spoken(k).first() in "aeiou", out)
        }
        val parts = products.flatMap { p -> p.name.split(Regex("""\s+""")).map { w -> Word(w, NameSound.key(spoken(w)).let { if (it.isEmpty()) it else loose(it) }) } }
        // "24 Mantra Red rice", "A1 Chips Banana chips": the number / code is said as it is ("24", "ஏ1"), the rest in Tamil letters —
        // the rest is heard as the name too, and the code said before it is the name's own (not a quantity).
        val led = (products + sizeless).filter { p -> p.name.split(Regex("""\s+""")).let { ws -> ws.size >= 2 && ws.first().any(Char::isDigit) } }
            .mapNotNull { p ->
                val rest = p.name.substringAfter(' ')
                val key = keyOf(spoken(rest)).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Product(rest, key, loose(key), saidWords(rest), spoken(rest).first() in "aeiou", p.out)
            }
        return Prepared(products + led, sizeless, goodsWritten, heardGoods, parts)
    }

    /**
     * A name's sound key word by word, then joined: "Hydraulic hose" keeps its k — read as one run of letters, the c of
     * "hydraulic" and the h of "hose" would be a "ch".
     */
    /** A name's key — and a one-word name of only an h and its vowel ("Hoe") heard by its h. */
    private fun nameKey(spoken: String): String = keyOf(spoken).ifEmpty { if (spoken.startsWith("h") && ' ' !in spoken.trim()) "h" else "" }

    /** Kai's words for where goods are put ("rack-la", "shelf-la") — a product of that sound before a count is that product. */
    private val placeWords = setOf("rack", "shelf", "selfu")

    private fun keyOf(spoken: String): String =
        spoken.split(' ').filter { it.isNotEmpty() }.joinToString("") { NameSound.key(it) }.replace(Regex("""(.)\1+"""), "$1")

    /** The names a product goes by when the shop writes two ("Spinach / Palak 250 g" → "Spinach 250 g", "Palak 250 g"). */
    private fun othersOf(name: String): List<String> {
        val m = Regex("""\s\d[\d.]*\s*\p{L}*$""").find(name)
        val base = m?.let { name.substring(0, it.range.first) } ?: name
        // "Ginger (tender)": said the other way round too — "டெண்டர் ஜிஞ்சர்".
        Regex("""^([\p{L} ]+?)\s*\(([\p{L} ]+)\)$""").find(base)?.let { r -> return listOf("${r.groupValues[2]} ${r.groupValues[1]}${m?.value.orEmpty()}") }
        if (!name.contains(" / ")) return emptyList()
        return base.split(" / ").map { it.trim() }.filter { it.any(Char::isLetter) }.map { it + m?.value.orEmpty() }
    }

    fun withProducts(text: String, productNames: List<String>, people: List<String> = emptyList()): String {
        if (!KaiSpokenWords.hasTamil(text)) return text
        val prep = prepared(productNames) ?: return text
        val products = prep.products
        val sizeless = prep.sizeless
        val names = products.map { it.name }
        val goodsWritten = prep.goodsWritten
        val heardGoods = prep.heardGoods
        val parts = prep.parts
        val words = tamilWord.findAll(text).toList()
        if (words.isEmpty()) return text
        val codes = productNames.flatMap { n -> Regex("""(?<![\p{L}\d])([A-Za-z]{1,4})-(\d+)(?![\d])""").findAll(n).map { it.groupValues[1].uppercase() + it.groupValues[2] } }.toSet()
        // "கேட் 6 கேபிள்" for Cat6 Cable: a name's letters-and-number word, said as a word and the number.
        productNames.flatMap { it.split(Regex("""\s+""")) }.mapNotNull { w -> Regex("""^([A-Za-z]{2,})(\d+)$""").find(w)?.let { w to it } }.distinct()
            .fold(text) { t, (w, m) ->
                Regex("""([஀-௿]+)\s*${m.groupValues[2]}(?!\d)""").replace(t) { r -> if (sameWord(r.groupValues[1], m.groupValues[1]) || NameSound.same(r.groupValues[1], m.groupValues[1])) w else r.value }
            }.takeIf { it != text }?.let { return withProducts(it, productNames, people) }
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
                // A shop name's last word said to it ("ஷாப்புக்கு", "ஸ்டோருக்கு") is the shop's, never a product's ("Shop" is no "Soap").
                if (span == 1 && Regex("""(க்கு|கிட்ட)$""").containsMatchIn(run[0].value) &&
                    KaiCommands.isShopWord(run[0].value.removeSuffix("க்கு").removeSuffix("கிட்ட"))) return@firstNotNullOfOrNull null
                // A unit said after its number ("10 பாக்கெட்" = 10 packets) is the unit, not a product that sounds like it (Bucket); said
                // as the item itself ("பக்கெட் 7"), it may be the product.
                if (span == 1 && KaiStock.unitOf(KaiSpokenWords.normalize(run[0].value)) != null &&
                    Regex("""(\d|${KaiSpokenWords.numberWordsPattern})\s*$""").containsMatchIn(KaiSpokenWords.normalize(text.substring(0, run[0].range.first))))
                    return@firstNotNullOfOrNull null
                // "ஓஎன் 0001": a code the shop's names write with a hyphen (ON-0001), its letters said before its number — the code, not a
                // product that sounds like the letters ("Onion").
                if (span == 1 && codes.isNotEmpty()) Regex("""^\s*(\d+)""").find(text.substring(run[0].range.last + 1))?.let { n ->
                    if ((KaiSpokenWords.code("${run[0].value} ${n.groupValues[1]}").replace(" ", "")).uppercase() in codes) return@firstNotNullOfOrNull null
                }
                // A stock question's own words ("எவ்வளவு", "ஸ்டாக்", "இருக்கு") are never part of a name.
                // (the shop's own "Stock pot": its "ஸ்டாக்" is the name's)
                if (run.any { it.value in stockAsk && !(it.value == "ஸ்டாக்" && names.any { n -> Regex("""(?i)\bstock\b""").containsMatchIn(n) }) }) return@firstNotNullOfOrNull null
                // Nor a number or a size said with it ("அரை இன்ச்", "முக்கால்", "ஒன்றரை"): "பிவிசி ரெடியூசர் அரை இன்ச்" keeps its half inch.
                if (run.any { w -> KaiSpokenWords.normalize(w.value).let { n -> n in sizeWords } }) return@firstNotNullOfOrNull null
                // "6 எம்.எம். ஜல்லி": a goods name that starts with its size takes the number said before it.
                val said = run.joinToString(" ") { it.value }
                // (a place word Kai knows — "ரேக்" = rack, said with "-ல" for where goods were put — is the shop's product when it is
                // said right before its count: "ரேக் 7 ஆட் பண்ணு" is the Rake — release check, 20 Oct 2026)
                val placeBeforeCount = span == 1 && KaiSpokenWords.normalize(run[0].value).lowercase() in placeWords && beforeQuantity(text, run.last().range.last + 1)
                val known = !placeBeforeCount && run.all { KaiSpokenWords.normalize(it.value) != it.value }
                goodsWritten[fold(said)]?.let { alias ->
                    // The shop's own product of that very name first ("சாமை" = Samai when Samai is kept, not the Little millet it also means).
                    // (only a name that sounds the same, sound for sound — "சமையல் எண்ணெய்" is Cooking oil, never Semolina)
                    val own = match(said, span, products, parts, known, beforeQuantity(text, run.last().range.last + 1))
                        ?.takeIf { o -> NameSound.same(said.replace(" ", ""), spoken(o).replace(" ", "")) }
                    return@firstNotNullOfOrNull Triple(span, own ?: alias, run.first().range.first)
                }
                Regex("""(\d+)\s*$""").find(text.substring(copied, run.first().range.first))?.let { n ->
                    goodsWritten[fold(n.groupValues[1] + said)]?.let { return@firstNotNullOfOrNull Triple(span, it, copied + n.range.first) }
                }
                // ("சுடிதார் ஸ்மால் பேக்": a goods name the run starts with is that name — a longer name heard by sound is taken only where it
                // is that goods name's own (Zip fastener Small pack for "ஜிப் …"), never another (Sweater for "சுடிதார்" = Churidar))
                // (but a goods word that is the longer name's own first word by sound — "பால்" of Ball valve, "அப்பளம்" of Appalam flour — is that word)
                val startsAs = (1 until span).mapNotNull { k ->
                    goodsWritten[fold(run.subList(0, k).joinToString(" ") { it.value })]?.let { alias -> k to alias }
                }
                // (a goods name may hold one — "சின்ன வெங்காயம்" — but a name heard by sound never does)
                if (run.any { KaiSpokenWords.normalize(it.value) in packSizeSaid }) return@firstNotNullOfOrNull null
                // ("ஃபேமிலி", "ஸ்மால்" alone are a pack's size — never a product of their own: "ஃபேமிலி" is not "poo maalai")
                if (span == 1 && KaiSpokenWords.normalize(run[0].value).lowercase() in setOf("small", "medium", "large", "family")) return@firstNotNullOfOrNull null
                match(said, span, products + heardGoods + sizeless, parts, known, beforeQuantity(text, run.last().range.last + 1), packAfter(text, run.last().range.last + 1))
                    ?.takeIf { m ->
                        startsAs.all { (k, a) ->
                            m.startsWith(a, ignoreCase = true) || NameSound.key(run.subList(0, k).joinToString("") { it.value }).let { t ->
                                t.isNotEmpty() && t == keyOf(spoken(m.split(Regex("""\s+""")).take(k).joinToString(" ")))
                            }
                        }
                    }
                    ?.let { Triple(span, it, run.first().range.first) }
            }
            if (found == null) { i++; continue }
            val (span, name, from0) = found
            // "24 மந்த்ரா …" → "24 Mantra …": the code said before the name is the name's own — not said twice.
            val lead = name.substringBefore(' ').takeIf { it.any(Char::isDigit) && name.contains(' ') }
            // ("ஏ1 சிப்ஸ் …" = A1 Chips: the code's letter said in Tamil.)
            val from = lead?.let { l ->
                Regex("""(?<![\p{L}\p{M}\d])([\p{L}\p{M}]*\s?\d+)\s*$""").find(text.substring(copied, from0))
                    ?.takeIf { m -> m.groupValues[1].replace(" ", "").let { c -> c.equals(l, true) || KaiSpokenWords.code(c).replace(" ", "").equals(l, true) ||
                        // ("4700 பிசி பாப்கார்ன்" = 4700BC Popcorn: its number said, its letters heard with the name.)
                        (c.all(Char::isDigit) && l.startsWith(c) && l.drop(c.length).all(Char::isLetter)) } }
                    ?.range?.first?.plus(copied)
            } ?: from0
            out.append(text, copied, from).append(name)
            copied = words[i + span - 1].range.last + 1
            // "மாக்ஸ் 500" → "Mox 500": the strength said after the name is the name's own, not a second quantity ("அப்ரைஸ் டி3" → Uprise D3).
            Regex("""(\d+(?:\.\d+)?)$""").find(name.substringAfterLast(' '))?.value?.let { size ->
                Regex("""^\s*${Regex.escape(size)}(?!\d)""").find(text.substring(copied))?.let { copied += it.range.last + 1 }
            }
            i += span
        }
        return if (copied == 0) text else out.append(text, copied, text.length).toString()
    }

    /**
     * The mic's Tamil words for words of the shop's product names, put in English one by one — after [withProducts], for a part
     * of a name said ("கிரவுண்ட்நட் ஆயில்" = "groundnut oil" of Groundnut Oil idhayam, "ஃபார்ச்சூன் ஆயில்" = "fortune oil" of
     * Sunflower Oil Fortune — owner, 16 Oct 2026), so it is read as the typed words are. Only a word that sounds exactly like one
     * name word (the closest when several do), never one of Kai's own words, a number, a unit or a person in the books. Whatever
     * names the shop adds — from a bill, by hand, by chat — no word list.
     */
    /** Every Tamil word of the goods names Kai knows (KaiFeed) — read by those names, not by a product word's sound. */
    private val feedGoodsWords: Set<String> by lazy {
        KaiFeed.goodsNames.flatMap { (names, _) -> names.filter(KaiSpokenWords::hasTamil).flatMap { it.split(Regex("""\s+""")) } }.toSet()
    }

    fun withProductWords(text: String, productNames: List<String>, people: List<String> = emptyList()): String {
        if (!KaiSpokenWords.hasTamil(text)) return text
        val vocab = productNames.flatMap { it.split(Regex("""[\s/()\-_,.|]+""")) }
            .filter { w -> w.length >= 3 && w.all(Char::isLetter) && !KaiSpokenWords.hasTamil(w) }
            .map { it.lowercase() }.distinct()
        if (vocab.isEmpty()) return text
        return tamilWord.replace(text) { m ->
            val w = m.value
            // (a goods word Kai knows in Tamil — "பினாயில்" = phenyl — is read by its own name, never turned into another word)
            if (KaiSpokenWords.normalize(w) != w || w in stockAsk || w in feedGoodsWords) return@replace w
            // (a shop name's last word, with its "-க்கு" — "ஷாப்புக்கு", "ஸ்டோருக்கு" — is never a product's: "Shop" is no "Soap")
            if (KaiCommands.isShopWord(w) || KaiCommands.isShopWord(w.removeSuffix("க்கு").removeSuffix("கிட்ட"))) return@replace w
            if (people.any { p -> p.split(Regex("""\s+""")).any { NameSound.same(w, it) } }) return@replace w
            // A whole product name by its sound ("பினாயில்" = Phenyl) is read as that name further on — never a word of another.
            if (productNames.any { n -> !KaiSpokenWords.hasTamil(n) && NameSound.same(w, n.replace(" ", "")) }) return@replace w
            // The name word with the same sound skeleton first ("phenyl", not "fennel" for "பினாயில்"), else the closest in shape.
            vocab.filter { e -> NameSound.same(w, e) }.singleOrNull()?.let { return@replace it }
            val gaps = vocab.mapNotNull { e -> wordGap(w, e)?.let { e to it } }
            val best = gaps.minOfOrNull { it.second } ?: return@replace w
            gaps.filter { it.second == best }.map { it.first }.singleOrNull() ?: w
        }
    }

    /** A name the shop keeps in Tamil letters said in English letters ("Clutch plate" for "கிளட்ச் பிளேட்"): the same name, by sound. */
    fun sameName(tamil: String, english: String): Boolean {
        if (!KaiSpokenWords.hasTamil(tamil) || KaiSpokenWords.hasTamil(english) || english.none(Char::isLetter)) return false
        val key = keyOf(spoken(english)).takeIf { it.isNotEmpty() } ?: return false
        val p = Product(english, key, loose(key), saidWords(english), spoken(english).first() in "aeiou")
        val parts = english.trim().split(Regex("""\s+""")).map { w -> Word(w, NameSound.key(spoken(w)).let { if (it.isEmpty()) it else loose(it) }) }
        val phrase = tamil.trim().replace(Regex("""[.\-]+"""), " ").replace(Regex("""\s+"""), " ").trim()
        return match(phrase, phrase.split(' ').size, listOf(p), parts, known = false, beforeQty = true) != null
    }

    /**
     * One Tamil word the mic wrote is exactly this English word by sound ("சிரப்" = syrup, "இன்ஜெக்ஷன்" = injection) — no near
     * misses: used to read what kind of product a name said aloud is, as its typed name would be read.
     */
    fun sameWord(tamil: String, english: String): Boolean = wordGap(tamil, english) != null

    /** [sameWord], and how far apart the two are in shape (syllables) — the closer one wins when two words both sound alike. */
    fun wordGap(tamil: String, english: String): Int? {
        if (!KaiSpokenWords.hasTamil(tamil) || KaiSpokenWords.hasTamil(english) || english.none(Char::isLetter)) return null
        val phrase = tamil.trim().replace(Regex("""(?<![஀-௿])ஒய"""), "வய").replace("ட்ச", "ச").replace(Regex("""(?<![஀-௿])([அஆஒஓ])யி"""), "$1இ")
        val key = NameSound.key(phrase).takeIf { it.length >= 3 } ?: return null
        val sp = spoken(english)
        val en = NameSound.key(sp).takeIf { it.length >= 3 } ?: return null
        if (loose(key) != loose(en) || (phrase.first() in 'அ'..'ஔ') != (sp.first() in "aeiou")) return null
        // "சிரப்" is syrup, not surf: the first vowel is heard too.
        if (!firstVowelHeard(phrase, sp)) return null
        // "கிளவுஸ்" is gloves (its v heard), not glass.
        return shapeGap(phrase, english) + if (key.replace("y", "") == en.replace("y", "")) 0 else 1
    }

    /** The one product a Tamil phrase sounds like, or null (none, too little to tell, or two alike). */
    private fun match(said: String, span: Int, products: List<Product>, parts: List<Word>, known: Boolean, beforeQty: Boolean, packAfter: Boolean = false): String? {
        // "ட்ச" is the "tch" of Switch (the t not heard) — or a real "tz" / "dc" (Cetzine, Gudcef); "ங்க" the ng of Zinco — or the
        // n-k of Anchor / Angle ("ஆங்கர்", "ஆங்கிள்"): read every way. "ட்ஜ" is the dge of Wedge; "ஹவுஸ்" the ou of House.
        // (The ou only before another consonant: "ஹவுஸ்", never the end of "எவ்வளவு".)
        val heard = said.replace("ட்ஜ", "ஜ").replace(Regex("""([க-ஹ])வு(?=[க-ஹ])"""), "$1ு")
        // (Each reading changes one thing: "ஸ்விட்ச்" read for its ங்க is still Switch, never "ஸ்விக்ச்" = Jigsaw.)
        val usual = heard.replace("ட்ச", "ச")
        val readings = listOf(usual, heard.replace("ட்ச", "ட்ஸ"), usual.replace("ங்க", "ன்க"), said.replace("ட்ச", "ச")).distinct()
        val found = readings
            .mapNotNull { matchSaid(it, span, products, parts, known, beforeQty, packAfter, relaxed = false) }
            .ifEmpty { readings.mapNotNull { matchSaid(it, span, products, parts, known, beforeQty, packAfter, relaxed = true) } }
        if (found.map { it.out }.distinct().size <= 1) return found.firstOrNull()?.out
        // Two readings, two products ("எம்எஸ் ஆங்கிள்": MS channel by one, MS angle by the other): the closer, then the one whose words
        // start as said (a vowel word for a vowel word), then the usual reading.
        val best = found.minOf { it.d }
        val close = found.filter { it.d == best }
        return (close.filter { it.agrees }.ifEmpty { close }).first().out
    }

    private class Found(val out: String, val d: Int, val agrees: Boolean)

    /**
     * A Tamil word's first vowel is the English word's ([sp], as said) — after the vowel Tamil puts inside "dr", "gl", "pl", "fl"
     * ("டிராப்ஸ்" = drops, "ஃபிளாஜில்" = Flagyl, "பிளேட்" = plate).
     */
    private fun firstVowelHeard(phrase: String, sp: String): Boolean {
        val word = phrase.trim().substringBefore(' ').removePrefix("ஃ")
        // Only where the Tamil word itself is written with that vowel inside (பி-ளே, டி-ரா) or the pulli (ப்-ள) — "போல்ட்" is bolt, never plate.
        val split = word.length > 2 && (word[1] == '்' || word[1] == 'ி' || word[1] == 'ு') && word[2] in '\u0B95'..'\u0BB9'
        val cluster = split && Regex("""^[^aeiouy]{2}""").containsMatchIn(sp) && !sp.startsWith("ch") && !sp.startsWith("sh") && !sp.startsWith("th")
        // ("ஸ்குவாஷ்" = squash: the u of "குவா" is the w glide — the vowel is the ா after it)
        val rest = word.drop(2).replace(Regex("""^([\u0B95-\u0BB9])ுவ"""), "$1்வ")
        val heard = when {
            !cluster || rest.isEmpty() -> tamilVowel(word)
            // "கிளவுஸ்": the ள after the cluster carries its own "a".
            rest.length > 1 && rest[0] in '\u0B95'..'\u0BB9' && rest[1] !in '\u0BBE'..'\u0BCD' -> setOf('a')
            else -> tamilVowel(rest)
        }
        return heard.intersect(latinVowel(sp)).isNotEmpty()
    }

    /** Syllables of a Tamil word: its vowels and the consonants that carry one (not those with the pulli). */
    private fun syllables(word: String): Int = word.indices.count { i ->
        val c = word[i]
        c in '\u0B85'..'\u0B94' || c in '\u0B95'..'\u0BB9' && word.getOrNull(i + 1) != '்'
    }

    /** The phrase's first vowel is a long ஆ / ா. */
    private fun longA(phrase: String): Boolean = phrase.firstOrNull { it in '\u0B85'..'\u0B94' || it in '\u0BBE'..'\u0BCC' }.let { it == 'ா' || it == 'ஆ' }

    /**
     * How closely each word's first vowel is heard, word by word: 2 where the Tamil letter is the one the mic writes for that
     * English vowel (short அ for the u of "rug" / "bun", ே for the a of "rack" / "bag", ை for the i of "side", ா for "ar" / the o
     * of "doctor", ீ for ee / ea / i, ூ for oo / u, ோ for o), 1 where they only share a sound, 0 otherwise.
     */
    private fun vowelsStrength(phrase: String, name: String): Int {
        val ta = phrase.trim().split(Regex("""\s+"""))
        val en = spoken(name).trim().split(Regex("""\s+"""))
        if (ta.size != en.size) return 0
        return ta.zip(en).sumOf { (t, e) ->
            // (after the vowel Tamil puts inside "bl", "pl", "dr" — "பிளாங்கெட்" is blanket's a, not an i)
            val w0 = t.removePrefix("ஃ")
            val cluster = w0.length > 2 && w0[1] in "்ிு" && w0[2] in '\u0B95'..'\u0BB9' && Regex("""^[^aeiouy]{2}""").containsMatchIn(e) &&
                !e.startsWith("ch") && !e.startsWith("sh") && !e.startsWith("th")
            val tv = tamilVowelKind(if (cluster) w0.drop(2) else w0)
            // ("ow", "aw", "ew": the w belongs to the vowel — showpiece's "ow")
            val m = Regex("""[aeiouy]+w?""").find(e, if (e.firstOrNull() == 'y') 1 else 0) ?: return@sumOf 0
            val v = m.value
            val after = e.substring(m.range.last + 1)
            val closed = after.isNotEmpty() && after[0] !in "aeiouy" && (after.length == 1 || after[1] !in "aeiouy")
            val magicE = Regex("""^[^aeiouy]+e""").containsMatchIn(after) && v.length == 1
            val strong = when (tv) {
                "a" -> v == "u" && closed || v == "a" && !closed && !magicE
                // (short ெ for the e of "pepper" / "bed" and the ai of "chains"; long ே for the a of "rack" / "paper" / "cake")
                "e" -> v == "e" || v.startsWith("ai") || v.startsWith("ay")
                "E" -> v == "a" && (closed || magicE) || v.startsWith("ai") || v.startsWith("ay") || v.startsWith("ea") || v == "e" && magicE
                // (and the "ia" of "diary", "diamond" after one consonant: "டைரி", "டைமண்ட்")
                "ai" -> (v == "i" || v == "y") && (magicE || after.startsWith("gh")) || v.startsWith("ie") && after.isEmpty() || v.startsWith("ia") && m.range.first == 1
                "A" -> v == "a" && (after.isEmpty() || after.startsWith("r") || after.startsWith("nk") || after.startsWith("ng")) || v == "o" && closed || v.startsWith("au") || v.startsWith("aw")
                "i" -> v == "i" && closed || v.startsWith("ee") || v.startsWith("ea") || v.startsWith("ie")
                "u" -> v.startsWith("oo") || v == "u" && !closed || v.startsWith("ew")
                "o" -> v == "o" && !closed || v.startsWith("oa") || v.startsWith("ow")
                else -> false
            }
            if (strong) 2 else if (tamilVowel(t).intersect(latinVowel(e)).isNotEmpty()) 1 else 0
        }
    }

    /** The first vowel of a Tamil word as written: "a" (short அ, or a letter with no sign), "A" (ா), "e", "ai", "i", "u", "o". */
    private fun tamilVowelKind(word: String): String {
        for ((i, c) in word.withIndex()) {
            when (c) {
                in "அ" -> return "a"; in "ஆா" -> return "A"; in "இஈிீ" -> return "i"; in "உஊுூ" -> return "u"; in "எெ" -> return "e"; in "ஏே" -> return "E"
                in "ஐை" -> return "ai"; in "ஒஓொோ" -> return "o"; in "ஔௌ" -> return "o"
            }
            if (c in '\u0B95'..'\u0BB9' && word.getOrNull(i + 1).let { it == null || it in '\u0B95'..'\u0BB9' }) return "a"
        }
        return ""
    }

    /** How many words' sound keys are the English words', word by word (0 when the words don't line up). */
    private fun wordSoundsAgree(phrase: String, name: String): Int {
        val ta = phrase.trim().split(Regex("""\s+"""))
        val en = spoken(name).trim().split(Regex("""\s+"""))
        if (ta.size != en.size) return 0
        return ta.zip(en).sumOf { (t, e) ->
            if (NameSound.key(t).isNotEmpty() && NameSound.key(t) == NameSound.key(e)) 1 else 0
        }
    }

    /** [shapeGap] word by word where the words line up ("பேர் மீடியம் பேக்" is Pear Medium pack — "மீடியம்" counts three, "medium" two). */
    private fun wordsShapeGap(phrase: String, name: String): Int {
        val ta = phrase.trim().split(Regex("""\s+"""))
        val en = name.trim().split(Regex("""\s+"""))
        return if (ta.size == en.size) ta.zip(en).sumOf { (t, e) -> shapeGap(t, e) } else shapeGap(phrase, name)
    }

    /** How far apart the f's and sh's are: the Tamil ஃப / ஷ against the English name's f / ph and sh. */
    private fun fShGap(phrase: String, name: String): Int {
        val en = spoken(name).replace(" ", "")
        val f = Regex("""ஃப""").findAll(phrase).count() - Regex("""f+|ph""").findAll(en).count()
        val sh = Regex("""ஷ""").findAll(phrase).count() - Regex("""sh""").findAll(en).count()
        return kotlin.math.abs(f) + kotlin.math.abs(sh)
    }

    /** How many words end on the vowel heard, word by word ("காபி பிரீமிக்ஸ்" = Coffee premix, not Cake premix); the last word alone when they don't line up. */
    private fun lastVowelsAgree(phrase: String, name: String): Int {
        val ta = phrase.trim().split(Regex("""\s+"""))
        val en = name.trim().split(Regex("""\s+"""))
        if (ta.size != en.size) return if (lastVowelHeard(phrase, name)) 1 else 0
        return ta.zip(en).count { (t, e) -> lastVowelHeard(t, e) }
    }

    /** The phrase's last vowel is the name's last vowel ("டோஃபு" ends in u like Tofu; "டாஃபி" in i like Toffee). */
    private fun lastVowelHeard(phrase: String, name: String): Boolean {
        val ta = phrase.trim().substringAfterLast(' ')
        val tv: Set<Char> = ta.trimEnd { it in '\u0B95'..'\u0BB9' || it == '்' }.lastOrNull().let { c ->
            when {
                ta.lastOrNull()?.let { it in '\u0B95'..'\u0BB9' } == true -> setOf('a')
                c == null -> emptySet()
                c in "ிீ" || c in "இஈ" -> setOf('i'); c in "ுூ" || c in "உஊ" -> setOf('u'); c in "ெே" || c in "எஏ" -> setOf('e', 'i')
                c == 'ை' || c == 'ஐ' -> setOf('a', 'e', 'i'); c in "ொோ" || c in "ஒஓ" -> setOf('o'); c == 'ா' || c in "அஆ" -> setOf('a')
                else -> setOf('a')
            }
        }
        val en = spoken(name).trim().substringAfterLast(' ').let { w -> if (w.length > 3 && Regex("""[^aeiou]e$""").containsMatchIn(w)) w.dropLast(1) else w }
        val v = Regex("""[aeiouy]+""").findAll(en).lastOrNull()?.value ?: return true
        val ev = when {
            v.startsWith("ee") || v.startsWith("ea") || v.startsWith("ie") || v == "y" || v == "i" -> setOf('i', 'e')
            v.startsWith("oo") || v.startsWith("u") || v == "ew" -> setOf('u')
            v.startsWith("o") -> setOf('o', 'a'); v.startsWith("a") -> setOf('a', 'e'); else -> setOf('e', 'i')
        }
        return tv.isEmpty() || tv.intersect(ev).isNotEmpty()
    }

    /** How many words' first vowels are heard the same, word by word (0 when the words don't line up). */
    private fun vowelsAgree(phrase: String, name: String): Int {
        val ta = phrase.trim().split(Regex("""\s+"""))
        val en = spoken(name).trim().split(Regex("""\s+"""))
        if (ta.size != en.size) return 0
        return ta.zip(en).count { (t, e) -> firstVowelHeard(t, e) }
    }

    /** Each word of the Tamil phrase starts with a vowel where the English word does ("ஆங்கிள்" angle, not "channel"). */
    private fun wordsAgree(phrase: String, name: String): Boolean {
        val ta = phrase.trim().split(Regex("""\s+"""))
        val en = spoken(name).trim().split(Regex("""\s+"""))
        if (ta.size != en.size) return true
        return ta.zip(en).all { (t, e) -> (t.first() in 'அ'..'ஔ') == (e.first() in "aeiou") }
    }

    private fun matchSaid(said: String, span: Int, products: List<Product>, parts: List<Word>, known: Boolean, beforeQty: Boolean, packAfter: Boolean, relaxed: Boolean): Found? {
        // The mic writes an English "w" before a vowel as "ஒய" ("ஒயர்" = Wire, "ஒயிட்" = White): heard as "வ".
        val phrase = said.replace(Regex("""(?<![஀-௿])ஒய"""), "வய")
            // Two words said together are two words: the ட் ending one and the ச starting the next ("வுட் சிசல்" = Wood chisel) are
            // not the "ட்ச" of Lakshmi.
            .replace(Regex("""ட்\s+ச"""), "ட் ஸ").replace(Regex("""ங்\s+க"""), "ன் க")
            // "இயர்" is ear: the ய after a starting இ is a glide.
            .replace(Regex("""(?<![஀-௿])([இஈ])ய"""), "$1")
            // An English "oi" / "ai" at the start of a word is written with a gliding ய ("ஆயின்மென்ட்" = Ointment): no consonant of its own.
            .replace(Regex("""(?<![஀-௿])([அஆஒஓ])யி"""), "$1இ")
            // "அயர்னிங்" = ironing: the "அய" before ர் is the English "i" (ai) — no y of its own.
            .replace(Regex("""(?<![஀-௿])அய(?=ர்)"""), "ஐ")
            // "ஃபியூஸ்" = Fuse, "டியூப்" = Tube: the "இயூ" the mic writes for an English "u" (yu) at a word's start is one vowel, that u.
            .replace(Regex("""(?<![஀-௿])(ஃ?[க-ஹ])ியூ"""), "$1ூ")
        // ("ஹோ" = Hoe: a word of only an h and its vowel is heard by its h)
        val key = NameSound.key(phrase.replace(" ", "")).ifEmpty { if (phrase.startsWith("ஹ") && ' ' !in phrase.trim()) "h" else "" }
        if (key.isEmpty()) return null
        val l = loose(key)
        val vowelFirst = phrase.first() in 'அ'..'ஔ'
        val scored = products.mapNotNull { p ->
            if (p.vowelFirst != vowelFirst || p.key.first() != key.first()) return@mapNotNull null
            val d = if (relaxed) distance(once(l), once(p.loose)) else distance(l, p.loose).let { d0 ->
                // "பொன்னி ரா ரைஸ்" (the w of raw not written): a whole name of two words or more said as many words is that name.
                if (d0 > 0 && span >= 2 && span in minOf(p.minWords, p.words)..p.words && once(l) == once(p.loose)) 0 else d0
            }
            val words = span in minOf(p.minWords, p.words)..p.words
            // Capitals said letter by letter ("MCCB" = em-si-si-bi): one syllable a letter, so "எம்சிபி" (three) is never MCCB (four).
            // (only where the letters are written as one word: "எச்.டி.எச்.எம்.ஆர்." said one by one is counted by its words)
            p.name.trim().split(Regex("""\s+""")).first().takeIf { w -> w.length in 2..6 && w.all { it in 'A'..'Z' } && 'W' !in w && span <= p.minWords }?.let { caps ->
                if (syllables(phrase.trim().split(' ').first()) != caps.length) return@mapNotNull null
            }
            // (any word of the name, where the words line up: "பேட்டரீஸ் ஏஏ" is Batteries AA — two letters said — never AAA)
            p.name.trim().split(Regex("""\s+""")).let { nw ->
                val tw = phrase.trim().split(Regex("""\s+"""))
                // (a Roman numeral is said as its number: "Act II" = "ஆக்ட் டூ")
                if (nw.size == tw.size) nw.zip(tw).drop(1).forEach { (n, t) ->
                    if (n.length in 2..6 && n.all { it in 'A'..'Z' } && 'W' !in n && !n.all { it in "IVX" } && syllables(t) != n.length) return@mapNotNull null
                }
            }
            val ok = when {
                // (a short one too with its pack said right after it: "சேவ் 100 கிராம்" is Sev 100 g, never "save")
                // (or a name the shop keeps in packs, right before a quantity: "சேவ் 7" = which Sev? — Kai asks, never drafts "save")
                known -> d == 0 && (l.length >= 3 || packAfter || p.family && beforeQty) && words && NameSound.same(phrase.replace(" ", ""), spoken(p.name).replace(" ", ""))
                // (relaxed — sounds said twice heard once — only for a whole name of two words or more: "பிவிசி யூனியன்", never one word)
                relaxed && (span < 2 || span !in minOf(p.minWords, p.words)..p.words) -> false
                d == 0 && l.length >= 3 -> span <= p.words + 1 && (span > 1 || firstVowelHeard(phrase, spoken(p.name)))
                // A short name (Tide, Pepsi, Maida): right before its quantity, its first vowel heard the same, and no word of another
                // product as close ("தூள்" is not Dal; "டெட்டால்" is Dettol, not the Dal of Toor Dal; "டைட்" is Tide, not Tea).
                // (the first vowel after the one Tamil puts inside "br" / "pl": "பிரா" is Bra's a)
                d == 0 -> beforeQty && words && firstVowelHeard(phrase, spoken(p.name)) &&
                    // (a name of two words or more may be a syllable longer said: "ஜிஐ வயர்" = GI wire)
                    shapeGap(phrase, p.name).let { gap -> gap <= (if (relaxed) maxOf(1, span) else 1) && parts.none { w -> w.loose == l && (spoken(w.text).first() in "aeiou") == vowelFirst && p.name.split(Regex("""\s+""")).none { it.equals(w.text, ignoreCase = true) } &&
                        // (a long "ா" is not an English short u: "மாப்" is Mop, not the muff of Ear muff — that is "மஃப்")
                        !(longA(phrase) && Regex("""[aeiou]""").find(spoken(w.text))?.value == "u") &&
                        tamilVowel(phrase).intersect(latinVowel(spoken(w.text))).isNotEmpty() && shapeGap(phrase, w.text) <= gap &&
                        // ("சீஸ்" is Cheese — its ee heard — not the e of Sev; "பென்" is Pen, not the a of Pan.)
                        !(latinVowel(spoken(p.name)).firstOrNull() in tamilVowel(phrase) && latinVowel(spoken(w.text)).firstOrNull() !in tamilVowel(phrase)) &&
                        // ("ஹனி" is Honey, sound for sound — Nivea has a v the mic didn't write.)
                        !(p.key == key && NameSound.key(spoken(w.text)) != key) &&
                        // ("டோஃபு" is Tofu — its last u heard — not Toffee)
                        !(lastVowelHeard(phrase, p.name) && !lastVowelHeard(phrase, w.text)) &&
                        // ("ரக்" is Rug — the short அ the mic writes for its u — not Rack, written ரேக்)
                        vowelsStrength(phrase, p.name) <= vowelsStrength(phrase, w.text) } }
                d == 1 && minOf(l.length, p.loose.length) >= 5 -> span == p.words || span == 1
                else -> false
            }
            if (ok) p to d else null
        }
        val best = scored.minOfOrNull { it.second } ?: return null
        // "கோச் ஸ்க்ரூ" / "ஹெக்ஸ் ஸ்க்ரூ" sound alike by consonants (the h isn't heard): the one whose words start as said, whose first
        // vowel and shape are heard.
        val vowel = tamilVowel(phrase)
        val tied = scored.filter { it.second == best }.map { it.first }.let { tied ->
            if (tied.map { it.out }.distinct().size < 2) tied
            // "பொன்னி ரா ரைஸ்" said in three words is Ponni Raw rice (three), not Ponni rice (two).
            else tied.filter { span in minOf(it.minWords, it.words)..it.words }.ifEmpty { tied }
                // "ஜாம் மீடியம் பேக்" / "கிவி": each word's own sound — Jam (not Sauce, whose s and the m of medium run together), Kiwi
                // (not Ghee, its v kept).
                .let { v -> val best = v.maxOf { wordSoundsAgree(phrase, it.name) }; v.filter { wordSoundsAgree(phrase, it.name) == best } }
                .let { v -> v.filter { wordsAgree(phrase, it.name) }.ifEmpty { v } }
                // "சைடு டேபிள்" / "ரக்": the vowel as Tamil writes that English vowel — Side table (not Study), Rug (not Rack).
                .let { v -> val best = v.maxOf { vowelsStrength(phrase, it.name) }; v.filter { vowelsStrength(phrase, it.name) == best } }
                // "ஆர்சிபிஓ" is RCBO, not RCCB: the vowels in order, when they are the same as said.
                .let { v -> v.filter { NameSound.vowels(it.name.let(::spoken).replace(" ", "")) == NameSound.vowels(phrase.replace(" ", "")) }.ifEmpty { v } }
                // (the first vowel after the one Tamil puts inside "bl" / "pl": "பிளாங்கெட்" is Blanket, not Blinds)
                .let { v -> v.filter { firstVowelHeard(phrase, spoken(it.name)) }.ifEmpty { v } }
                // "ஃப்ளஷ் போல்ட்" / "ஃப்ளஷ் பிளேட்": every word's own first vowel (bolt, not plate).
                .let { v -> val best = v.maxOf { vowelsAgree(phrase, it.name) }; v.filter { vowelsAgree(phrase, it.name) == best } }
                // "டாஃபி" / "டோஃபு", "காபி பிரீமிக்ஸ்": each word's last vowel heard — Toffee, Tofu, Coffee premix (not Cake premix).
                .let { v -> val best = v.maxOf { lastVowelsAgree(phrase, it.name) }; v.filter { lastVowelsAgree(phrase, it.name) == best } }
                // "ஃப்ரோசன் ஃபிஷ்" / "ஃப்ரோசன் பீஸ்": the mic writes an f (ஃப) and an sh (ஷ) as their own letters — Frozen fish, Frozen peas.
                .let { v -> val g = v.minOf { fShGap(phrase, it.name) }; v.filter { fShGap(phrase, it.name) == g } }
                .let { v -> val g = v.minOf { wordsShapeGap(phrase, it.name) }; v.filter { wordsShapeGap(phrase, it.name) == g } }
                // Still alike: "பைண்டிங் வயர்" keeps its w (Binding wire), "Paint thinner" has none — the whole sound, glides and all.
                .let { v -> v.filter { it.key.replace("y", "") == key.replace("y", "") }.ifEmpty { v } }
        }
        // "ஹெட்லைட்" / "டெயில் லைட்" sound alike (Headlight, Tail light): the one said in as many words.
        val out = (tied.takeIf { a -> a.map { it.out }.distinct().size == 1 } ?: tied.filter { it.words == span }).map { it.out }.distinct().singleOrNull() ?: return null
        return Found(out, best, tied.filter { it.out == out }.any { wordsAgree(phrase, it.name) })
    }

    /** How many words the mic writes for a name: "HDHMR Board" is six ("எச்.டி.எச்.எம்.ஆர். போர்டு") — letters said one by one. */
    private fun saidWords(name: String): Int = name.trim().split(Regex("""[\s-]+""")).filter { it.isNotEmpty() }.sumOf { w -> if (w.length in 2..6 && w.all { it in 'A'..'Z' }) w.length else 1 }

    private val joiner = Regex("""\s+|-|\.\s*""")

    /** A Tamil name however it is spaced or joined: "பாசி பருப்பு" / "பாசிப்பருப்பு", "மிளகாய்த் தூள்" / "மிளகாய் தூள்". */
    private fun fold(phrase: String): String = phrase.replace(Regex("""[\s.\-]+"""), "").replace(Regex("""([஀-௿])்(?=\1)"""), "")

    /**
     * Right before a quantity — "2", "ஒரு", "இரண்டு" — or a stock question about it — "எவ்வளவு", "ஸ்டாக்", "இருக்கா", "விலை"
     * ("ஒயர் எவ்வளவு இருக்கு"): a short name is only taken in such a place for it.
     */
    private fun beforeQuantity(text: String, from: Int): Boolean {
        val next = text.substring(from).trimStart().split(Regex("""\s+""")).firstOrNull().orEmpty()
        return next.firstOrNull()?.isDigit() == true || KaiSpokenWords.normalize(next).firstOrNull()?.isDigit() == true ||
            next.trimEnd('?', '.', '!') in stockAsk ||
            // (or its pack named by size: "ஐஸ்டு டீ சின்ன பேக்", "ஐஸ்டு டீ ஸ்மால் பேக்")
            KaiSpokenWords.normalize(next).lowercase() in setOf("small", "medium", "large", "family", "chinna", "periya")
    }

    /** A pack said right after ("100 கிராம்", "1 கிலோ", "200 எம்எல்", "1 லிட்டர்"). */
    private fun packAfter(text: String, from: Int): Boolean =
        Regex("""^\s*\d+(?:\.\d+)?\s*(g|gm|gram|grams|kg|kilo|ml|l|litre|liter)\b""").containsMatchIn(KaiSpokenWords.normalize(text.substring(from)).lowercase())

    /** Sizes a pack is named by instead of a number ("Garbage bag Medium", "Diapers XL"). */
    private val packSizes = setOf("small", "medium", "large", "xs", "xl", "xxl", "xxxl", "mini", "jumbo", "regular")

    private val sizeWords = setOf("half", "mukkal", "onnara", "onnekaal", "into", "inch", "mm", "ml", "amp", "amps")

    /** "சின்ன பேக்", "பெரிய பேக்": a pack's size in Tamil — never part of a name heard by sound ("பீஃப் பெரிய பேக்" is Beef, large pack). */
    private val packSizeSaid = setOf("chinna", "periya")

    private val stockAsk = setOf("எவ்வளவு", "எவ்ளோ", "எவ்வளவுக்கு", "எத்தனை", "ஸ்டாக்", "ஸ்டாக்ல", "இருக்கா", "இருக்கு", "விலை", "ரேட்")

    private val letterNames = mapOf('a' to "e", 'b' to "bi", 'c' to "si", 'd' to "di", 'e' to "i", 'f' to "ef", 'g' to "ji", 'h' to "ech",
        'i' to "ai", 'j' to "je", 'k' to "ke", 'l' to "el", 'm' to "em", 'n' to "en", 'o' to "o", 'p' to "pi", 'q' to "kyu", 'r' to "ar",
        's' to "es", 't' to "ti", 'u' to "yu", 'v' to "vi", 'w' to "dabilyu", 'x' to "eks", 'y' to "vai", 'z' to "zed")

    /** An English product name as it is said (and as the mic hears it): silent letters out, soft c, x = ks, a lone letter by its name. */
    private fun spoken(name: String): String = name.trim().split(Regex("""\s+""")).joinToString(" ") { w0 ->
        // "WD-type", "U-bolt", "1-way", "Multi-plug": each part as it is said ("dabilyudi type", "yu bolt", "one way").
        if (w0.contains('-') && w0.length > 2) return@joinToString w0.split('-').filter { it.isNotEmpty() }.joinToString(" ") { part ->
            when (part) { "1" -> "one"; "2" -> "tu"; "3" -> "three"; else -> spoken(part) }
        }
        val w = w0
        when {
            w.length == 1 && w[0].isLetter() -> letterNames[w[0].lowercaseChar()] ?: w
            w.length in 2..6 && w.all { it in 'A'..'Z' } -> w.lowercase().map { letterNames[it] ?: "$it" }.joinToString("")
            else -> w.lowercase()
                // "Cough" = kof, "Laugh" = laf; "Switch" / "Watch": the t of "tch" isn't heard (the mic writes "ஸ்விட்ச்" / "ஸ்விச்").
                .replace(Regex("""(?<=[ctrnl])ough$"""), "of").replace(Regex("""augh$"""), "af").replace("tch", "ch")
                .replace(Regex("""^kn"""), "n").replace(Regex("""^wr"""), "r").replace("ght", "t").replace(Regex("""(?<!^)gh"""), "")
                // "Zucchini" = zukini; "Ginger", "Gingelly" = jinjer, jinjelly ("ஜிஞ்சர்").
                .replace("cch", "k").replace(Regex("""^ging"""), "jinj").replace(Regex("""^veg$"""), "vej")
                // "Pneumatic" = numatic (the p isn't heard); "Squash", "Squeegee" = skwash, skweejee.
                .replace(Regex("""^pn"""), "n").replace("qu", "kw").replace(Regex("""(?<=e)gee$"""), "jee")
                // "Sausages" = sausajes ("சாசேஜஸ்"), "Spatula" = spachula ("ஸ்பேச்சுலா").
                .replace(Regex("""(?<=a)ges$"""), "jes").replace(Regex("""(?<=[aeiou])tula"""), "chula")
                // "Statue" = stachu ("ஸ்டேச்சு"); "Hacksaw", "Straw", "Prawns": the w of "aw" is the vowel's ("ஹேக்சா", "ஸ்ட்ரா").
                .replace(Regex("""(?<=[aeiou])tue$"""), "chu").replace(Regex("""aw(?![aeiouy])"""), "a").replace("ture", "chur").replace("x", "ks").replace(Regex("""c(?=[eiy])"""), "s")
                // "Bandage" = bendej, "Syringe" = sirinj, "Bridge" = brij; "Ointment" = oinment (the t isn't heard); "Eye", "Eyewear" = ai….
                .replace(Regex("""d?ge$"""), "j").replace("ntm", "nm").replace(Regex("""^eye"""), "ai")
                // "Dr Trust" = doctor trust ("டாக்டர்"), "Head & Shoulders" = and; "Flagyl" = flajil; "Zincovit" = zingovit ("சிங்கோவிட்").
                // "U-bolt" = yu bolt, "D-shackle" = di shackle; "Union", "Universal", "Utility", "Urea" = yu…; "Chemical" = kemical, "Chrome" = krome.
                .replace(Regex("""^u(?=ni|ti|ri|re|sa|se|su|ro)"""), "yu").replace(Regex("""^chem"""), "kem").replace(Regex("""^chr"""), "kr")
                .replace(Regex("""^sch"""), "sk").replace(Regex("""^(te|me)ch"""), "$1k")
                // "Palmolein" / "Balm" = pamolin / bam (the l isn't heard); "Iodized" = ayodized ("அயோடைஸ்டு").
                .replace(Regex("""(?<=[^aeiou])alm"""), "am").replace(Regex("""^io"""), "ayo")
                // "Fortune" = forchun ("பார்ச்சூன்"), "Whole" = hole, "Geometry" = jiyometry, "Packaged" = packejd, "Sachet" = sashay.
                .replace("rtune", "rchun").replace(Regex("""^who"""), "ho").replace(Regex("""^geo"""), "jiyo")
                // "Lotion" = loshun ("லோஷன்"), "Nestlé" = nesle (the t isn't heard).
                .replace(Regex("""(?<=[a-z])tion"""), "shun").replace("é", "e").replace(Regex("""stle$"""), "sle")
                // "Borges" = borjes ("போர்ஜஸ்"): a g before -es is soft.
                .replace(Regex("""(?<=[rn])ges$"""), "jes")
                .replace(Regex("""(?<=.)aged$"""), "ejd").replace(Regex("""(?<=a)chet(s?)$"""), "shay$1")
                // "Hydraulic" = haidraulic, "Nylon" = nailon: a y between consonants is a vowel; "Gel", "Emergency", "Agent": a soft g.
                .replace(Regex("""^gy"""), "jy").replace(Regex("""(?<=^[^aeiouy]{1,2})y(?=[^aeiouy])"""), "i").replace(Regex("""^ge(?=[lmnr])"""), "je")
                .replace(Regex("""(?<=[aeiour])g(?=en)"""), "j")
                .replace(Regex("""^dr\.?$"""), "doktar").replace(Regex("""^&$"""), "and").replace(Regex("""g(?=y)"""), "j").replace(Regex("""n(?:c(?!h)|k)"""), "ng")
                // "Sunglasses" = sun-glasses ("சன்கிளாசஸ்"): the g of glass is heard, not an ng.
                .replace(Regex("""(?<=[aeiou])nglass"""), "nklass")
                // "Urea", "Area" = yuriya, eriya ("யூரியா"): the e and a of a last -ea are two vowels, a y between.
                .replace(Regex("""(?<=r)ea$"""), "iya")
                // "Chalk" = chak ("சாக்"): the l of -alk isn't heard.
                .replace(Regex("""(?<=[^aeiou])alk"""), "ak")
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
        // ("ஹனி" = honey: the ஹ with no sign carries its a — the இ is the next syllable's.)
        if (first in '\u0B95'..'\u0BB9' && phrase.getOrNull(1).let { it == null || it in '\u0B95'..'\u0BB9' || it == ' ' }) return setOf('a')
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
            // "Screw", "Glue": ew / ue are an oo ("ஸ்க்ரூ", "க்ளூ").
            v == "e" && w.getOrNull(m.range.last + 1) == 'w' || v.startsWith("ue") -> setOf('u', 'e')
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
    private fun loose(key: String) = key.drop(1).filter { it != 'y' && it != 'v' }.let { rest ->
        // ("டியோடரண்ட்" = Deodorant: the ய gone, the two t's either side of it are one.)
        key.first() + if (rest.firstOrNull() == key.first()) rest.drop(1) else rest
    }

    /** [loose] with sounds said twice heard once ("யூனியன்" = union) — only when nothing matched without it. */
    private fun once(key: String) = key.replace(Regex("""(.)\1+"""), "$1")

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
