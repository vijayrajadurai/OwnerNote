package com.shopai.app.brain.chat

/**
 * "Kadaiku saman vandhuruku", "saraku vandhachu", "stock add pannalama": goods came in and none is named yet. Kai asks which,
 * and the owner's next lines ("Colgate 2 box, Rice 3 moota") are each a stock-in draft (Confirm first) until "avlo dhaan".
 */
object KaiGoodsArrival {

    private const val B = """(?<![\p{L}\p{M}])"""
    private const val E = """(?![\p{L}\p{M}])"""

    private val goodsWord = Regex(
        """(?i)$B(saman|saaman|saamaan|samaan|saamaanam|saraku|sarakku|saaraku|maal|stock|items?|porul|porutkal|goods|load|lodu|parcel)$E|சாமான்|சரக்கு|மால்|ஸ்டாக்|பொருள்""",
    )
    private val arrived = Regex(
        """(?i)$B(vandhuruku|vandhurukku|vandhiruku|vandhirukku|vanthuruku|vanthurukku|vanthiruku|vanthirukku|vandhuchu|vanthuchu|vandhachu|vanthachu|""" +
            """vandhudhu|vanthuthu|vandhadhu|vandhuduchu|vandhuchi|vanthuchi|vandhuruchu|vandhuruchi|vanthuruchu|vanthuruchi|vandhiruchu|irakkinom|irakkunom|irakkinen|irakkanum|eranguchu|iranguchu|came|arrived|received|""" +
            """add\s*pannalama|add\s*pannanum|add\s*pannu|add\s*pannunga|podanum|podalama|podunga|serkkanum|serkkalama|serthudu|update\s*pannanum)$E|""" +
            """வந்திருக்கு|வந்துருக்கு|வந்துச்சு|வந்தாச்சு|இறக்கினோம்|சேர்க்கலாமா|சேர்க்கணும்""",
    )
    /** A question about stock, not goods coming in ("stock evlo irukku"). */
    private val asking = Regex("""(?i)$B(evlo|evvalavu|enna\s+irukku|irukka|how\s+much|list|check)$E|எவ்வளவு""")
    private val money = Regex("""(?i)₹|$B(rs|rupees?|rupa|ruba|amount|panam|kaasu|payment|rate|price|vilai|bill)$E""")

    /** Words around "saman vandhuruku" that name nothing: "Kadaiku", "ippo", "pudhu", "Owner" … */
    private val filler = Regex(
        """(?i)$B(kadaiku|kadaikku|kadaikki|kadaila|kadaiyila|kadaikkulla|namma|namba|nammakku|engalukku|ippo|ippa|ipo|innaiku|inniku|innaikku|""" +
            """konjam|pudhu|puthu|pudhusa|new|fresh|ellam|ellaam|lam|laam|ku|la|owner|seri|sari|sir|anna|kai|hey|ok|okay|ah|aa|nu|naama|""" +
            """stock-?la|inventory-?la|inventory|kadai|shop|today|now|some|our|the|has|have|to|for|in|is|are|of)$E""",
    )

    /** Goods came / are to be added, with nothing named: no number, no product, and no other word ("Pepsodent stock vandhiruku" names one). */
    fun announces(text: String, productNames: List<String>): Boolean {
        if (!goodsWord.containsMatchIn(text) || !arrived.containsMatchIn(text) || text.any(Char::isDigit) ||
            asking.containsMatchIn(text) || money.containsMatchIn(text) ||
            productNames.any { p -> Regex("""(?i)$B${Regex.escape(p)}$E""").containsMatchIn(text) }) return false
        val rest = filler.replace(arrived.replace(goodsWord.replace(text, " "), " "), " ").replace(Regex("""[^\p{L}\p{M}]+"""), " ").trim()
        return rest.isEmpty()
    }

    /** The line without its goods / shop words, for looking for a person: "Saraku" is not "Sara" + "ku", "Kadaiku" not "Kadai". */
    fun withoutGoodsWords(text: String): String =
        goodsWord.replace(text, " ").replace(Regex("""(?i)$B(kadaiku|kadaikku|kadaikki)$E|கடைக்கு"""), " ")

    private val done = Regex("""(?i)^\s*((?:avlo|avlodhan|avvalavu|avalavu)\s*(?:dhaan|dhan|than|thaan|daan|dan|தான்)?|mudinjuchu|mudinjiduchu|mudinjidichu|podhum|pothum|vera\s*illa|vera\s*edhuvum\s*illa|illa|venam|vendam|""" +
            """apram\s*(?:solren|sollren|solluren)|later|cancel|that'?s\s*all|done|finish|nothing|அவ்வளவு\s*தான்|அவ்ளோ\s*தான்|முடிஞ்சுது|போதும்|வேற\s*இல்ல)\s*[.!]*\s*$""")
    fun ends(text: String) = done.containsMatchIn(text)

    private val separator = Regex("""\s*(?:,|;|\n|&|\+|$B(?:and|matrum|innum|apram|appuram|um)$E|மற்றும்)\s*""", RegexOption.IGNORE_CASE)
    private val unitWord = """box|boxes|bag|bags|moota|mootai|kg|kgs|kilo|packet|packets|packetu|pack|pcs|pieces|piece|bottle|bottles|litre|litres|dozen|strip|strips|carton|cartons|bundle|kattu|petti|tin|dabba|pair|tray|gram|grams|g|gm|ml"""
    /** A piece's product words, without its number and units ("gram 50 packet" → ""). */
    private fun nameOf(piece: String) = piece.replace(Regex("""(?i)\d+(?:[./]\d+)?|(?<![\p{L}])(?:$unitWord)(?![\p{L}])"""), " ").replace(Regex("""[^\p{L}\p{M}]+"""), " ").trim()

    private const val N = """(\d+(?:\.\d+)?)"""
    private val packWord = """packet|packets|packetu|pack|pocket|pouch|cover|bag|bags|box|boxes|bottle|bottles|tin|dabba|jar"""
    private val weightWord = """kg|kgs|kilo|g|gm|gram|grams|ml|litre|litres|liter|l"""
    /** "arakilo", "arai kilo", "half kg", "1/2 kg" → 0.5 kg; "kaal kilo", "1/4 kg" → 0.25 kg; "mukkaal kilo" → 0.75 kg. */
    private val halves = listOf(
        Regex("""(?i)(?<![\p{L}])(?:mukkaalkilo|(?:mukkaal|mukkal|mukaal)\s*(?:kilo|kg))(?![\p{L}])|(?<![\d/])3/4\s*(?:kilo|kg)(?![\p{L}])""") to "0.75 kg",
        Regex("""(?i)(?<![\p{L}])(?:arakilo|araikilo|arakkilo|araikkilo|ara\s+kilo|arai\s+kilo|half\s*(?:kilo|kg))(?![\p{L}])|(?<![\d/])1/2\s*(?:kilo|kg)(?![\p{L}])""") to "0.5 kg",
        Regex("""(?i)(?<![\p{L}])(?:kaalkilo|kaal\s+kilo|kal\s+kilo|quarter\s*(?:kilo|kg))(?![\p{L}])|(?<![\d/])1/4\s*(?:kilo|kg)(?![\p{L}])""") to "0.25 kg",
    )

    /**
     * "Ashirvad rava arakilo 50 packet", "Ashirvad rava 500 gram 50 packet", "Ashirvad rava 50 packetu arakilo": one product with its
     * pack size → "Ashirvad rava 50 packet, oru packet 0.5 kg" (the stock line Kai already reads: a new product's size, or the
     * packets of a product kept in kg). Null when the line isn't a name, a weight and a count of packs.
     */
    fun sized(text: String): String? {
        if (money.containsMatchIn(text)) return null
        val t = halves.fold(text.trim().replace(Regex("""\s+"""), " ")) { s, (r, v) -> r.replace(s, v) }.replace(Regex("""[.,!]+$"""), "")
        val first = Regex("""(?i)^([\p{L}\p{M}][\p{L}\p{M}\s'-]*?)\s+$N\s*($weightWord)\s+$N\s*($packWord)$""").find(t)
        val last = Regex("""(?i)^([\p{L}\p{M}][\p{L}\p{M}\s'-]*?)\s+$N\s*($packWord)\s+$N\s*($weightWord)$""").find(t)
        val (name, w, wu, n, pack) = first?.destructured?.let { (a, b, c, d, e) -> listOf(a, b, c, d, e) }
            ?: last?.destructured?.let { (a, b, c, d, e) -> listOf(a, d, e, b, c) } ?: return null
        val kg = when (wu.lowercase()) {
            "g", "gm", "gram", "grams" -> w.toBigDecimal().divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString() to "kg"
            "ml" -> w.toBigDecimal().divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString() to "litre"
            "l", "litre", "litres", "liter" -> w to "litre"
            else -> w to "kg"
        }
        val p = pack.lowercase().let { if (it == "packetu") "packet" else it }
        return "${name.trim().replaceFirstChar(Char::uppercaseChar)} $n $p, oru $p ${kg.first} ${kg.second}"
    }
    /** "Colgate 2 box Rice 3 moota" (no separator): split after each "number unit". */
    private val oneItem = Regex("""(?i)([\p{L}\p{M}][\p{L}\p{M}\s.\-]*?\s*\d+(?:\.\d+)?\s*(?:(?:$unitWord)$E)?)""")

    /** The goods in a line: "Colgate 2 box, Rice 3 moota" → ["Colgate 2 box", "Rice 3 moota"]; nothing that isn't name + number. */
    fun items(text: String): List<String> {
        if (money.containsMatchIn(text)) return emptyList()
        val parts = text.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        // "Ashirvad rava 500 gram 50 packet": a piece named only by a unit ("gram 50 packet") is not another product — the part stays whole.
        val found = parts.flatMap { part ->
            if (Regex("""\d""").findAll(part).count() <= 1) return@flatMap listOf(part)
            val pieces = oneItem.findAll(part).map { it.value.trim() }.toList()
            val numbers = Regex("""\d+(?:[./]\d+)?""")
            if (pieces.size < 2 || numbers.findAll(pieces.joinToString(" ")).count() != numbers.findAll(part).count() ||
                pieces.drop(1).any { p -> nameOf(p).isEmpty() }) listOf(part) else pieces
        }
        return found.filter { it.any(Char::isDigit) && it.any(Char::isLetter) && it.split(Regex("""\s+""")).size <= 6 }
    }
}
