package com.shopai.app.data.inventory

/**
 * Client-side, keyword-based parser for the "speak stock in/out" feature
 * (English / Tamil / Tanglish). Pure function, no I/O, no product lookup —
 * it only ever produces a structured intent; [StockVoiceResolver] resolves
 * the spoken product name against the real product list and the database
 * write itself always goes through the existing InventoryRepository.stockIn/
 * stockOut after an explicit owner confirmation (never here).
 *
 * Deliberately narrow: it only fires when BOTH a recognizable STOCK_IN/
 * STOCK_OUT verb phrase AND a positive quantity are present. Anything else —
 * including STOCK_QUERY-shaped questions like "cement evlo irukku" — returns
 * null so the caller can fall through untouched to Ask My Business.
 *
 * Known limitations (kept as future extensions, not built here):
 * - No cross-script transliteration: a spoken Tamil product name only
 *   resolves against a product actually named in Tamil script (or vice
 *   versa) — matching the existing app-wide product-name normalization
 *   strategy (trim + lowercase), which has no phonetic/alias table either.
 * - No date/backdating support: the existing InventoryRepository.stockIn/
 *   stockOut calls carry no occurredAt parameter, so a spoken date is
 *   ignored rather than guessed.
 * - No multi-action parsing (e.g. a stock line plus a supplier payment in
 *   one sentence) — only the first recognizable stock action is parsed.
 */

enum class StockVoiceIntent { STOCK_IN, STOCK_OUT }

data class ParsedStockVoiceInput(
    val intent: StockVoiceIntent,
    val productName: String,
    val quantity: Double,
    val unit: String?,
)

object StockVoiceParser {
    private val STOCK_IN_KEYWORDS = listOf(
        "vanginen", "vaanginen",
        "vandhirukku", "vanthirukku",
        "vandhuchu", "vanthuchu",
        "purchase panninen", "purchase pannen",
        "add pannu", "add panniten",
        "received",
        "வாங்கினேன்",
        "வந்திருக்கு",
        "வந்துச்சு",
    )

    private val STOCK_OUT_KEYWORDS = listOf(
        "sale panniten", "sale pannen",
        "pochu",
        "remove pannu",
        "sold",
        "poiduchu", "poiduchchu",
        "விற்றுவிட்டேன்",
        "போயிடுச்சு",
    )

    private val ENGLISH_NUMBER_WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100,
    )

    private val TAMIL_NUMBER_WORDS = mapOf(
        "பூஜ்யம்" to 0, "ஒன்று" to 1, "இரண்டு" to 2, "மூன்று" to 3, "நான்கு" to 4, "ஐந்து" to 5,
        "ஆறு" to 6, "ஏழு" to 7, "எட்டு" to 8, "ஒன்பது" to 9, "பத்து" to 10,
        "இருபது" to 20, "முப்பது" to 30, "நாற்பது" to 40, "ஐம்பது" to 50, "அறுபது" to 60,
        "எழுபது" to 70, "எண்பது" to 80, "தொண்ணூறு" to 90, "நூறு" to 100,
    )

    private val UNIT_ALIASES = mapOf(
        "bag" to "bags", "bags" to "bags", "பை" to "bags", "பைகள்" to "bags",
        "kg" to "kg", "kgs" to "kg", "kilo" to "kg", "kilos" to "kg",
        "g" to "g", "gram" to "g", "grams" to "g",
        "piece" to "pieces", "pieces" to "pieces", "pcs" to "pieces", "pc" to "pieces",
        "box" to "boxes", "boxes" to "boxes",
        "packet" to "packets", "packets" to "packets",
        "litre" to "litres", "litres" to "litres", "liter" to "litres", "liters" to "litres",
        "metre" to "metres", "metres" to "metres", "meter" to "metres", "meters" to "metres",
        "dozen" to "dozen",
    )

    private val NUMERIC_TOKEN_REGEX = Regex("""-?\d+(?:\.\d+)?""")

    fun parse(text: String): ParsedStockVoiceInput? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed.lowercase()

        val intent = detectIntent(normalized) ?: return null
        val matchedKeyword = keywordsFor(intent).firstOrNull { normalized.contains(it) } ?: return null

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val consumed = BooleanArray(words.size)

        var quantity: Double
        var quantityFound = false

        val digitIndex = words.indexOfFirst { numericValueOf(it) != null }
        if (digitIndex >= 0) {
            quantity = numericValueOf(words[digitIndex])!!
            consumed[digitIndex] = true
            quantityFound = true
        } else {
            var current = 0
            for (i in words.indices) {
                val stripped = stripPunctuation(words[i]).lowercase()
                val value = ENGLISH_NUMBER_WORDS[stripped] ?: TAMIL_NUMBER_WORDS[stripped]
                if (value != null) {
                    consumed[i] = true
                    quantityFound = true
                    current = if (value == 100) {
                        if (current == 0) 100 else current * 100
                    } else {
                        current + value
                    }
                }
            }
            quantity = current.toDouble()
        }
        if (!quantityFound || quantity <= 0.0) return null

        var unit: String? = null
        for (i in words.indices) {
            if (consumed[i]) continue
            val u = UNIT_ALIASES[stripPunctuation(words[i]).lowercase()]
            if (u != null) {
                unit = u
                consumed[i] = true
                break
            }
        }

        val keywordWords = matchedKeyword.split(" ")
        var i = 0
        while (i <= words.size - keywordWords.size) {
            if (matchesKeywordAt(words, i, keywordWords)) {
                for (k in keywordWords.indices) consumed[i + k] = true
                break
            }
            i++
        }

        val productName = words.indices
            .filter { !consumed[it] }
            .joinToString(" ") { words[it] }
            .trim()
            .trim(',', '.', '?', '!')
            .trim()
        if (productName.isBlank()) return null

        return ParsedStockVoiceInput(intent, productName, quantity, unit)
    }

    private fun keywordsFor(intent: StockVoiceIntent): List<String> = when (intent) {
        StockVoiceIntent.STOCK_IN -> STOCK_IN_KEYWORDS
        StockVoiceIntent.STOCK_OUT -> STOCK_OUT_KEYWORDS
    }

    private fun detectIntent(normalized: String): StockVoiceIntent? {
        val hasIn = STOCK_IN_KEYWORDS.any { normalized.contains(it) }
        val hasOut = STOCK_OUT_KEYWORDS.any { normalized.contains(it) }
        return when {
            hasIn && !hasOut -> StockVoiceIntent.STOCK_IN
            hasOut && !hasIn -> StockVoiceIntent.STOCK_OUT
            else -> null
        }
    }

    private fun numericValueOf(word: String): Double? = NUMERIC_TOKEN_REGEX.find(word)?.value?.toDoubleOrNull()

    /**
     * Trims leading/trailing punctuation only. Tamil dependent vowel signs
     * and the pulli (்) are Unicode combining marks, not letters, so a naive
     * `!ch.isLetterOrDigit()` trim strips them off real Tamil words (e.g.
     * "வாங்கினேன்" -> "வாங்கினேன", "பை" -> "ப") and breaks every exact-string
     * match against the keyword/number-word/unit maps below. Combining
     * marks are explicitly kept.
     */
    private fun stripPunctuation(word: String): String = word.trim { ch ->
        !ch.isLetterOrDigit() && !isCombiningMark(ch)
    }

    private fun isCombiningMark(ch: Char): Boolean {
        val type = Character.getType(ch)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun matchesKeywordAt(words: List<String>, startIndex: Int, keywordWords: List<String>): Boolean {
        if (startIndex + keywordWords.size > words.size) return false
        for (k in keywordWords.indices) {
            if (stripPunctuation(words[startIndex + k]).lowercase() != keywordWords[k]) return false
        }
        return true
    }
}
