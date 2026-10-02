package com.shopai.app.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads the total written out in words on Indian bills:
 * "Rupees Thirty One Thousand Nine Hundred And Thirty Only" → 31930.00,
 * "Rs. One Lakh Twenty Thousand and Fifty Paise Only" → 120000.50.
 * Tolerates one-letter OCR slips ("Thirly", "Hundrcd").
 */
object AmountInWords {
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fourty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val scales = mapOf(
        "hundred" to 100L, "thousand" to 1_000L, "lakh" to 100_000L, "lakhs" to 100_000L,
        "lac" to 100_000L, "lacs" to 100_000L, "crore" to 10_000_000L, "crores" to 10_000_000L,
    )
    private val vocabulary = units.keys + scales.keys + listOf("and", "paise", "only", "rupees", "rupee")

    // From "Rupees"/"Rs"/"INR"/"Amount in words" up to "Only" (or ~20 words).
    private val phrase = Regex(
        """\b(?:rupees|rupee|rs\.?|inr|in\s*words\s*[:\-]?)\s+([a-z\s,\-]{3,200}?)(?:\s+only\b|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun find(lines: List<String>): BigDecimal? {
        // The words often wrap onto the next line, so read the text as one run.
        val text = lines.joinToString(" ").replace(Regex("""\s+"""), " ")
        return phrase.findAll(text)
            // GST invoices also write the tax out in words ("Tax Amount (in
            // words): INR Two Thousand ..."); that is not the bill total.
            .filterNot { isTaxAmount(text, it.range.first) }
            .firstNotNullOfOrNull { parse(it.groupValues[1]) }
    }

    // "Tax Amount (in words) :" / "GST in words -" right before the phrase.
    // ("Tex"/"Tax"/"Iax": OCR slips of "Tax".)
    private val taxInWordsLabel = Regex("""\b(t[ae]x|[il1]ax|gst|cgst|sgst|igst)\b[a-z\s]{0,12}\(?\s*in\s*words\s*\)?\s*[:\-]?\s*$""")

    private fun isTaxAmount(text: String, phraseStart: Int): Boolean {
        val before = text.substring((phraseStart - 40).coerceAtLeast(0), phraseStart).lowercase()
        return taxInWordsLabel.containsMatchIn(before)
    }

    fun parse(words: String): BigDecimal? {
        val tokens = words.lowercase().split(Regex("""[\s,\-]+""")).filter { it.isNotEmpty() }
            .map { normalise(it) ?: return null }
            .filter { it != "rupees" && it != "rupee" && it != "only" }
        if (tokens.none { it in units || it in scales }) return null

        // "... and Fifty Paise": the words after the last "and" are paise.
        val paiseIndex = tokens.indexOf("paise")
        val (rupeeWords, paiseWords) = if (paiseIndex >= 0) {
            val cut = tokens.subList(0, paiseIndex).lastIndexOf("and")
            if (cut < 0) return null
            tokens.subList(0, cut) to tokens.subList(cut + 1, paiseIndex)
        } else {
            tokens to emptyList()
        }
        val rupees = number(rupeeWords) ?: return null
        val paise = if (paiseWords.isEmpty()) 0L else number(paiseWords)?.takeIf { it < 100 } ?: return null
        if (rupees <= 0 && paise <= 0) return null
        return BigDecimal.valueOf(rupees).add(BigDecimal.valueOf(paise, 2)).setScale(2, RoundingMode.HALF_UP)
    }

    // Indian place values: crore > lakh > thousand > hundred.
    private fun number(words: List<String>): Long? {
        var total = 0L
        var current = 0L
        var sawNumber = false
        for (word in words) {
            when {
                word == "and" -> Unit
                word in units -> {
                    current += units.getValue(word)
                    sawNumber = true
                }
                word == "hundred" -> current = (if (current == 0L) 1 else current) * 100
                word in scales -> {
                    total += (if (current == 0L) 1 else current) * scales.getValue(word)
                    current = 0
                    sawNumber = true
                }
                else -> return null
            }
        }
        return if (sawNumber) total + current else null
    }

    /** Exact word, or the one vocabulary word within one letter of it. */
    private fun normalise(word: String): String? {
        if (word in vocabulary) return word
        if (word.length < 4) return null
        return vocabulary.filter { it.length >= 4 && editDistance(it, word) <= 1 }.singleOrNull()
    }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }
}
