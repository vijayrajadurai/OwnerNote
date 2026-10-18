package com.shopai.app.brain.note

/** What a piece of Kai's reply is, for how the chat shows it. */
enum class KaiMarkKind {
    /** Money coming to the shop (owed to the owner, received): bold green. */
    AMOUNT_IN,
    /** Money going out (the owner owes / paid / spent): bold red. */
    AMOUNT_OUT,
    /** Any other amount: bold. */
    AMOUNT,
    /** A person / shop in the owner's books: bold. */
    NAME,
}

data class KaiMark(val start: Int, val end: Int, val kind: KaiMarkKind)

/**
 * Kai's reply, marked for the chat (owner, 18 Oct 2026: "chat-la iruka text romba simple-aa iruku"): amounts in bold — green when
 * the money comes to the shop, red when it goes out, by the words of their own sentence — and the names of the owner's people in
 * bold. Typed and spoken replies alike, in Tamil, Tanglish and English. The words themselves are never changed.
 */
object KaiReplyMarks {
    private val amount = Regex("""₹\s?-?\d+(?:,\d+)*(?:\.\d+)?""")
    private val sentence = Regex("""[^.!?\n।]+[.!?।]?""")

    private val outWords = Regex(
        """(?i)(?<![\p{L}])(kudukkanum|kudukanum|kodukkanum|kudukka\s+vendiyadhu|kudukka\s+vendiyathu|naan\s+[^.!?\n]*tharanum|debit|selavu|""" +
            """expense|expenses|to\s+pay|i\s+owe|you\s+owe\s+\S+|paid\s+out|kuduthadhu|kudutha\s+panam|payable)(?![\p{L}])|""" +
            """கொடுக்கணும்|குடுக்கணும்|கொடுக்க\s+வேண்டியது|நான்\s+[^.!?\n]*தரணும்|டெபிட்|செலவு|கொடுத்தது|குடுத்தது""",
    )
    private val inWords = Regex(
        """(?i)(?<![\p{L}])(tharanum|tharuvaan|tharuvanga|vaanganum|vaanga\s+vendiyadhu|credit|owes\s+you|received|vandhuchu|vandhadhu|""" +
            """vandha\s+panam|collect|receivable|vasool)(?![\p{L}])|தரணும்|வாங்கணும்|வாங்க\s+வேண்டியது|கிரெடிட்|வந்துச்சு|வந்தது|வசூல்""",
    )

    fun of(text: String, names: List<String>): List<KaiMark> {
        val marks = mutableListOf<KaiMark>()
        for (s in sentence.findAll(text)) {
            val part = s.value
            // "I owe" words win: "Naan ABC Traders-ku ₹2,000 tharanum" is money going out though "tharanum" is there.
            val kind = when {
                outWords.containsMatchIn(part) -> KaiMarkKind.AMOUNT_OUT
                inWords.containsMatchIn(part) -> KaiMarkKind.AMOUNT_IN
                else -> KaiMarkKind.AMOUNT
            }
            amount.findAll(part).forEach { m -> marks += KaiMark(s.range.first + m.range.first, s.range.first + m.range.last + 1, kind) }
        }
        names.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }.forEach { n ->
            Regex("""(?i)(?<![\p{L}\p{M}])${Regex.escape(n)}(?![\p{L}\p{M}])""").findAll(text).forEach { m ->
                val r = m.range.first until m.range.last + 1
                if (marks.none { it.start < r.last + 1 && r.first < it.end }) marks += KaiMark(r.first, r.last + 1, KaiMarkKind.NAME)
            }
        }
        return marks.sortedBy { it.start }
    }
}
