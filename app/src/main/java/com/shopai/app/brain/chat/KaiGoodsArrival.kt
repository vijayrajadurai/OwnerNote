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
            """vandhudhu|vanthuthu|vandhadhu|vandhuduchu|irakkinom|irakkunom|irakkinen|irakkanum|eranguchu|iranguchu|came|arrived|received|""" +
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
    private val unitWord = """box|boxes|bag|bags|moota|mootai|kg|kgs|kilo|packet|packets|pack|pcs|pieces|piece|bottle|bottles|litre|litres|dozen|strip|strips|carton|cartons|bundle|kattu|petti|tin|dabba|pair|tray"""
    /** "Colgate 2 box Rice 3 moota" (no separator): split after each "number unit". */
    private val oneItem = Regex("""(?i)([\p{L}\p{M}][\p{L}\p{M}\s.\-]*?\s*\d+(?:\.\d+)?\s*(?:(?:$unitWord)$E)?)""")

    /** The goods in a line: "Colgate 2 box, Rice 3 moota" → ["Colgate 2 box", "Rice 3 moota"]; nothing that isn't name + number. */
    fun items(text: String): List<String> {
        if (money.containsMatchIn(text)) return emptyList()
        val parts = text.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        val found = parts.flatMap { part -> if (Regex("""\d""").findAll(part).count() > 1) oneItem.findAll(part).map { it.value.trim() }.toList() else listOf(part) }
        return found.filter { it.any(Char::isDigit) && it.any(Char::isLetter) && it.split(Regex("""\s+""")).size <= 6 }
    }
}
