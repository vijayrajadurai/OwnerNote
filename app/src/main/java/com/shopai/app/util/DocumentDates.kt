package com.shopai.app.util

import java.time.LocalDate
import java.util.Locale

/** A date found in text, the exact text it came from, and whether the year was filled in. */
data class DateMatch(val date: LocalDate, val text: String, val yearAssumed: Boolean)

/** What to do when a date is written without a year ("21/9"). */
enum class YearlessPolicy {
    /** The most recent such date that isn't in the future (bills, receipts). */
    MOST_RECENT_PAST,
    /** This calendar year (notes about money to give/receive, which may be upcoming). */
    CURRENT_YEAR,
}

/**
 * Finds the date written on a bill or note. Day comes first (Indian
 * style): 21/09/2026, 21-9-26, 21.09.2026, 2026-09-21, 21 Sep 2026,
 * Sep 21 2026, 10 அக்டோபர், and — for handwritten notes — 21/9 or
 * "Oct 10" with no year.
 */
object DocumentDates {
    private val numeric = Regex("""(?<![\d/.\-])(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2}|\d{4})(?![\d/.\-])""")
    private val isoDate = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val dayMonthName = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/.,]*(\p{L}[\p{L}\p{M}]{2,11})[\s\-/.,']*(\d{4}|\d{2}(?!\d))?""", RegexOption.IGNORE_CASE)
    private val monthNameDay = Regex("""(?<![\p{L}\p{M}])(\p{L}[\p{L}\p{M}]{2,11})[\s.]+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?(?![\d/.\-])""", RegexOption.IGNORE_CASE)
    // "21/9", "21-9". Not "21.9": that is a decimal amount far more often.
    private val dayMonthOnly = Regex("""(?<![\d/.\-])(\d{1,2})[/\-](\d{1,2})(?![\d/.\-])""")
    private val dateLabels = listOf("date", "dt", "dated", "தேதி")
    private val notBillDateWords = listOf("due", "valid", "expiry", "exp.", "till")

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val tamilMonths = listOf(
        "ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்",
        "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்",
    )

    /**
     * Prefers a date on a "Date:" line. [allowYearless] accepts "21/9"
     * (used for handwritten notes, where the year is often left out).
     */
    fun find(
        text: String,
        today: LocalDate = LocalDate.now(),
        allowYearless: Boolean = false,
        yearless: YearlessPolicy = YearlessPolicy.MOST_RECENT_PAST,
    ): LocalDate? = findMatch(text, today, allowYearless, yearless)?.date

    fun findMatch(
        text: String,
        today: LocalDate = LocalDate.now(),
        allowYearless: Boolean = false,
        yearless: YearlessPolicy = YearlessPolicy.MOST_RECENT_PAST,
    ): DateMatch? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val labelled = lines.filter { line -> dateLabels.any { line.lowercase(Locale.ROOT).contains(it) } }
        // "Due date" / "Valid till" / "Expiry" are not the date the bill was made.
        val (otherDates, billDates) = labelled.partition { line ->
            notBillDateWords.any { line.lowercase(Locale.ROOT).contains(it) }
        }
        return (billDates + lines.filterNot { it in otherDates } + otherDates)
            .firstNotNullOfOrNull { findInLine(it, today, allowYearless, yearless) }
    }

    private fun findInLine(line: String, today: LocalDate, allowYearless: Boolean, yearless: YearlessPolicy): DateMatch? {
        isoDate.find(line)?.let { m ->
            build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), today)
                ?.let { return DateMatch(it, m.value, false) }
        }
        numeric.find(line)?.let { m ->
            build(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt(), today)
                ?.let { return DateMatch(it, m.value, false) }
        }
        dayMonthName.findAll(line).forEach { m ->
            val month = monthNumber(m.groupValues[2]) ?: return@forEach
            val day = m.groupValues[1].toInt()
            val yearText = m.groupValues[3]
            if (yearText.isEmpty() && !allowYearless && !isEnglishMonth(m.groupValues[2])) return@forEach
            val year = if (yearText.isEmpty()) guessYear(month, day, today, yearless) else year(yearText)
            build(year, month, day, today)?.let { return DateMatch(it, m.value.trim(), yearText.isEmpty()) }
        }
        monthNameDay.findAll(line).forEach { m ->
            val month = monthNumber(m.groupValues[1]) ?: return@forEach
            val day = m.groupValues[2].toInt()
            val yearText = m.groupValues[3]
            if (yearText.isEmpty() && !allowYearless) return@forEach
            val year = if (yearText.isEmpty()) guessYear(month, day, today, yearless) else yearText.toInt()
            build(year, month, day, today)?.let { return DateMatch(it, m.value.trim(), yearText.isEmpty()) }
        }
        if (allowYearless) {
            dayMonthOnly.find(line)?.let { m ->
                val day = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                build(guessYear(month, day, today, yearless), month, day, today)
                    ?.let { return DateMatch(it, m.value, true) }
            }
        }
        return null
    }

    private fun year(text: String): Int = if (text.length == 2) 2000 + text.toInt() else text.toInt()

    private fun guessYear(month: Int, day: Int, today: LocalDate, policy: YearlessPolicy): Int {
        if (policy == YearlessPolicy.CURRENT_YEAR) return today.year
        // "21/9" on a bill means the most recent 21 Sept that is not in the future.
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
        return if (thisYear != null && thisYear.isAfter(today)) today.year - 1 else today.year
    }

    private val fullMonths = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    private fun isEnglishMonth(name: String) = englishMonth(name) != null

    // Only real month names or their usual short forms ("Sep", "Sept"), so
    // "Mayur" or "Decks" are never read as months.
    private fun englishMonth(name: String): Int? {
        val word = name.lowercase(Locale.ROOT).trimEnd('.')
        fullMonths.indexOf(word).takeIf { it >= 0 }?.let { return it + 1 }
        months.indexOf(word).takeIf { it >= 0 }?.let { return it + 1 }
        return if (word == "sept") 9 else null
    }

    private fun monthNumber(name: String): Int? =
        englishMonth(name) ?: tamilMonths.indexOfFirst { name.startsWith(it) }.takeIf { it >= 0 }?.plus(1)

    // Rejects impossible dates and anything far from today.
    private fun build(year: Int, month: Int, day: Int, today: LocalDate): LocalDate? {
        if (year < 2000 || year > today.year + 1) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
}
