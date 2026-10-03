package com.shopai.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

/** Credit = the person owes me. Debit = I owe the person. */
enum class TxnDirection { CREDIT, DEBIT, UNKNOWN }

/** Position on the page as fractions (0..1) of the image width/height. */
data class OcrBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class OcrWord(val text: String, val confidence: Int, val box: OcrBox? = null)

/** One line of OCR output with its confidence (0..100) and position. */
data class OcrLine(
    val text: String,
    val confidence: Int = 100,
    val box: OcrBox? = null,
    val words: List<OcrWord> = emptyList(),
)

/**
 * Why a row needs the owner's attention. [blocksSave] issues must be fixed
 * (edit, or "Mark as checked") before the row can be saved.
 */
enum class ReviewIssue(val blocksSave: Boolean) {
    NAME_MISSING(true),
    AMOUNT_MISSING(true),
    AMOUNT_AMBIGUOUS(true),
    TYPE_UNKNOWN(true),
    NAME_UNCLEAR(true),
    AMOUNT_UNCLEAR(true),
    DATE_UNCLEAR(true),
    YEAR_ASSUMED(false),
    DATE_MISSING(false),
}

/** One person / one transaction read from a handwritten line. */
data class ExtractedTransaction(
    val personName: String?,
    val amount: BigDecimal?,
    /** The amount exactly as written, e.g. "₹1,500" or "2K". */
    val amountText: String?,
    val date: LocalDate?,
    val dateText: String?,
    val direction: TxnDirection,
    /** Short explanation of how the direction was decided (or why it couldn't be). */
    val directionReason: String?,
    val description: String?,
    val originalLine: String,
    val pageIndex: Int,
    val lineIndex: Int,
    val box: OcrBox?,
    val nameConfidence: Int?,
    val amountConfidence: Int?,
    val dateConfidence: Int?,
    val directionConfidence: Int?,
    val issues: Set<ReviewIssue>,
    /** Handwritten bill only: the shop / company in the header (the party when I owe them). */
    val billCompany: String? = null,
    /** Handwritten bill only: the customer ("Name / M/s / To"), the party when they owe me. */
    val billCustomer: String? = null,
    /** Handwritten bill only: already paid ("Paid / Advance 500", or Total − "Balance"); the rest stays pending. */
    val billPaid: BigDecimal? = null,
    /** Handwritten bill only: the written due date ("Due date / Pay by 15/10"). */
    val billDueDate: LocalDate? = null,
) {
    val needsReview: Boolean get() = issues.any { it.blocksSave }
}

/**
 * What could actually be read from one line that did not make a complete
 * transaction, for pre-filling the quick-correction form. Fields are null
 * when not read; nothing is guessed.
 */
data class LinePrefill(
    /** A name read clearly enough to pre-fill. */
    val personName: String?,
    /** Letters OCR read but not confidently — offered as a hint only, never filled in. */
    val nameHint: String?,
    val amount: BigDecimal?,
    /** The amount came from an OCR slip like "2OOO" and should be checked. */
    val amountUnclear: Boolean,
    val direction: TxnDirection,
    val date: LocalDate?,
)

/** What a scanned handwritten page is, decided from its content before review. */
enum class NotePageType {
    /** Items + total: one entry (party + total). */
    HANDWRITTEN_BILL,
    /** Every entry is money I gave → each person owes me (Credit). */
    MONEY_GIVEN,
    /** Every entry is money I received → I owe each person (Debit). */
    MONEY_RECEIVED,
    /** Both directions on the page; each row keeps its own. */
    MIXED,
    /** No Credit/Debit could be read — the owner chooses on review. */
    UNKNOWN,
}

/** What a handwritten page is: one bill, a list of people + amounts, or not sure (the owner chooses). */
enum class PageKind { BILL, NOTES, UNKNOWN }

data class NoteExtraction(
    val transactions: List<ExtractedTransaction>,
    /** Lines with text that isn't a transaction. Shown, never silently dropped. */
    val unparsedLines: List<Pair<Int, OcrLine>>,
    /** Per page, in order: what the page was read (or chosen) as. */
    val pageKinds: List<PageKind> = emptyList(),
) {
    /** Pages the owner must mark as Bill or Notes before anything is extracted from them. */
    val unknownPages: List<Int> get() = pageKinds.withIndex().filter { it.value == PageKind.UNKNOWN }.map { it.index }

    /** The page type, from what was read (never guessed for unknown rows). */
    val pageType: NotePageType get() = pageTypeOf(transactions)

    companion object {
        fun pageTypeOf(rows: List<ExtractedTransaction>): NotePageType {
            if (rows.isEmpty()) return NotePageType.UNKNOWN
            if (rows.all { it.description?.startsWith(HANDWRITTEN_BILL_LABEL) == true }) return NotePageType.HANDWRITTEN_BILL
            val known = rows.map { it.direction }.filter { it != TxnDirection.UNKNOWN }.toSet()
            return when {
                known.isEmpty() -> NotePageType.UNKNOWN
                known.size > 1 -> NotePageType.MIXED
                known.single() == TxnDirection.CREDIT -> NotePageType.MONEY_GIVEN
                else -> NotePageType.MONEY_RECEIVED
            }
        }
    }
}

/** Description prefix of the single entry made from a handwritten bill page. */
const val HANDWRITTEN_BILL_LABEL = "Handwritten bill"

/**
 * Turns the lines of one or more handwritten pages into transactions:
 * one row per person per line ("Ravi 500 Kumar 300" gives two rows).
 * Never invents a name, amount, date or direction: anything missing or
 * unclear is left empty and marked for review.
 */
object HandwrittenTransactionParser {
    /** Below this OCR confidence a field is marked "Needs Review". */
    const val CONFIDENCE_THRESHOLD = 70

    /**
     * [kinds]: the owner's choice for pages read as [PageKind.UNKNOWN]. A page
     * still UNKNOWN gives no rows — the owner is asked "Bill or Notes?" first.
     */
    fun parse(
        pages: List<List<OcrLine>>,
        today: LocalDate = LocalDate.now(),
        kinds: Map<Int, PageKind> = emptyMap(),
    ): NoteExtraction {
        val transactions = mutableListOf<ExtractedTransaction>()
        val unparsed = mutableListOf<Pair<Int, OcrLine>>()
        val pageKinds = mutableListOf<PageKind>()
        pages.forEachIndexed { pageIndex, lines ->
            val (detected, bill) = billOf(pageIndex, lines, today)
            val kind = kinds[pageIndex] ?: detected
            pageKinds += kind
            when (kind) {
                // A handwritten bill is ONE entry, not one per item line.
                PageKind.BILL -> {
                    bill?.let { transactions += it }
                    return@forEachIndexed
                }
                PageKind.UNKNOWN -> return@forEachIndexed
                PageKind.NOTES -> Unit
            }
            // A date or "To give:" heading applies to the lines under it.
            var carryDate: DateMatch? = null
            var carryDateConfidence: Int? = null
            var carryDirection: Pair<TxnDirection, String>? = null
            lines.forEachIndexed { lineIndex, line ->
                val (text, repairedAmounts) = prepare(line.text)
                if (text.isBlank()) {
                    // A handwritten line OCR could not read at all: keep it (its
                    // picture is shown for the owner to type), never drop it.
                    if (line.box != null) unparsed += pageIndex to line
                    return@forEachIndexed
                }
                var producedAny = false
                var headerOnly = true
                for (segment in splitPeople(text)) {
                    val parsed = parseSegment(segment, line, pageIndex, lineIndex, today, carryDate, carryDateConfidence, carryDirection, repairedAmounts)
                    when (parsed) {
                        is Segment.DateHeading -> {
                            carryDate = parsed.date
                            carryDateConfidence = parsed.confidence
                        }
                        is Segment.DirectionHeading -> carryDirection = parsed.direction to parsed.reason
                        is Segment.Transaction -> {
                            transactions += parsed.value
                            producedAny = true
                            headerOnly = false
                        }
                        is Segment.Unknown -> headerOnly = false
                    }
                }
                if (!producedAny && !headerOnly && text.any { it.isLetterOrDigit() }) unparsed += pageIndex to line
            }
        }
        return NoteExtraction(transactions, unparsed, pageKinds)
    }

    /**
     * Pre-fill for the quick-correction form from a line OCR only partly
     * read: an amount only if exactly one is clear, a type only from a clear
     * word, a name only when read confidently (otherwise just a hint).
     */
    fun prefill(line: OcrLine, today: LocalDate = LocalDate.now()): LinePrefill {
        val (text, repairedAmounts) = prepare(line.text)
        if (text.isBlank()) return LinePrefill(null, null, null, false, TxnDirection.UNKNOWN, null)
        val date = DocumentDates.findMatch(text, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)
        val masked = maskDates(text)
        // Only digits (e.g. a date OCR garbled into "2373426"): never an amount.
        val amounts = if (isDigitsOnly(text)) emptyList() else amountPattern.findAll(masked).toList()
        val marked = amounts.filter { it.groupValues[1].isNotBlank() || it.groupValues[3].isNotBlank() || it.groupValues[4].isNotBlank() }
        val chosen = when {
            marked.size == 1 -> marked.single()
            marked.isEmpty() && amounts.size == 1 -> amounts.single()
            else -> null
        }
        val tokens = Regex("""[\p{L}][\p{L}\p{M}.']*""").findAll(mask(masked, amounts.map { it.range }))
            .map { it.value.trimEnd('.', '\'') }.toList()
        val nameTokens = pickName(tokens)
        val name = nameTokens?.joinToString(" ") { stripDative(it).first }
        val confident = nameTokens != null && confidenceOf(line, nameTokens) >= CONFIDENCE_THRESHOLD
        val direction = classify(text, tokens, hasPerson = nameTokens != null)?.first ?: TxnDirection.UNKNOWN
        return LinePrefill(
            personName = name.takeIf { confident },
            nameHint = name.takeUnless { confident },
            amount = chosen?.let { toAmount(it) },
            amountUnclear = chosen != null && chosen.groupValues[2].replace(",", "") in repairedAmounts,
            direction = direction,
            date = date?.date,
        )
    }

    /** The first date written anywhere on the pages (usually the heading), or null. */
    fun noteDate(pages: List<List<OcrLine>>, today: LocalDate = LocalDate.now()): LocalDate? =
        pages.asSequence().flatten().mapNotNull { line ->
            DocumentDates.findMatch(prepare(line.text).first, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)?.date
        }.firstOrNull()

    /**
     * How much transaction structure a reading has, to choose between OCR
     * passes: 9 per complete row (name + amount + Credit/Debit), less for
     * partial rows, 1 per recognised page date. Readable text breaks ties.
     */
    fun readingScore(lines: List<OcrLine>, today: LocalDate = LocalDate.now()): Int {
        val result = parse(listOf(lines), today)
        val rows = result.transactions.sumOf { t ->
            (if (t.personName != null) 3 else 0) + (if (t.amount != null) 3 else 0) + (if (t.direction != TxnDirection.UNKNOWN) 3 else 0)
        }
        val dates = if (noteDate(listOf(lines), today) != null) 1 else 0
        return rows * 1000 + dates * 100 + lines.sumOf { l -> l.text.count { it.isLetterOrDigit() } }.coerceAtMost(99)
    }

    // ------------------------------------------------ handwriting clean-up

    /** Normalises one OCR line before parsing; returns the text and the OCR-repaired amounts. */
    private fun prepare(raw: String): Pair<String, Set<String>> {
        val (text, repaired) = repairOcrDigits(spacedDate(normalise(raw)))
        return fixDirectionSlips(text) to repaired
    }

    // "28 09 26" / "28 09 2026": a date written (or read) with spaces.
    private val spacedDatePattern = Regex("""^(\d{1,2})\s+(\d{1,2})\s+(\d{2}|\d{4})$""")

    private fun spacedDate(text: String): String {
        val m = spacedDatePattern.matchEntire(text) ?: return text
        val (d, mo, y) = m.destructured
        return if (d.toInt() in 1..31 && mo.toInt() in 1..12) "$d/$mo/$y" else text
    }

    /**
     * After an amount, a word one letter off "Debit"/"Credit" ("Debi",
     * "Deblt", "Creait", "Credi") is that word. Only right after a number,
     * so a name like "Debi" elsewhere is never touched.
     */
    private fun fixDirectionSlips(text: String): String =
        Regex("""(\d\s*(?:/-)?\s+)([A-Za-z]{4,7})(?![\p{L}])""").replace(text) { m ->
            val word = m.groupValues[2].lowercase(Locale.ROOT)
            val fixed = when {
                editDistance(word, "debit") <= 1 -> "Debit"
                editDistance(word, "credit") <= 1 -> "Credit"
                else -> m.groupValues[2]
            }
            m.groupValues[1] + fixed
        }

    /** Only digits and separators, e.g. "2373426" or "28 0926". */
    private fun isDigitsOnly(text: String) = text.any { it.isDigit() } && text.all { it.isDigit() || it in " /.-|,:" }

    // ------------------------------------------------------- OCR digit slips

    // Mostly-digit words with letters OCR often confuses for digits.
    private val digitLike = Regex("""(?<![\p{L}\p{M}\d])[0-9OoIl|SsZzB][0-9OoIl|SsZzB,.]*[0-9OoIl|SsZzB](?![\p{L}\p{M}\d])""")

    /**
     * Handwriting OCR often reads "2000" as "200O" / "2o00" / "l000". Such a
     * word — at least half real digits — is turned into the number, and the
     * repaired value is returned so its row is flagged "check this amount".
     */
    private fun repairOcrDigits(text: String): Pair<String, Set<String>> {
        val repaired = mutableSetOf<String>()
        val out = digitLike.replace(text) { m ->
            val raw = m.value
            val digits = raw.count { it.isDigit() }
            val letters = raw.count { it.isLetter() || it == '|' }
            // "2OOO": a digit followed only by O's is plainly zeros.
            val zerosAfterDigit = raw.first().isDigit() && raw.filter { it.isLetter() }.all { it == 'O' || it == 'o' }
            if (letters == 0 || digits == 0 || (digits * 2 < digits + letters && !zerosAfterDigit)) return@replace raw
            val fixed = raw.map { c ->
                when (c) {
                    'O', 'o' -> '0'
                    'I', 'l', '|' -> '1'
                    'S', 's' -> '5'
                    'Z', 'z' -> '2'
                    'B' -> '8'
                    else -> c
                }
            }.joinToString("")
            repaired += fixed.replace(",", "")
            fixed
        }
        return out to repaired
    }

    // ---------------------------------------------------------------- split

    // Date-like tokens (kept out of amount reading). A dotted form needs a
    // year ("10.10.2026"), so decimals like "250.50" stay amounts.
    private val anyDate = Regex("""(?<![\d/.\-])(?:\d{1,4}[/\-]\d{1,2}(?:[/\-]\d{2,4})?|\d{1,2}\.\d{1,2}\.\d{2,4})(?![\d/.\-])""")

    // A handwritten date OCR mangled the separator of ("23]09Jel", "28|09|26"):
    // neither its digits nor its letters are an amount or a name.
    private val garbledDate = Regex("""(?<![\d])\d{1,2}[\]\[|\\)(}{:;]\d{1,2}\S*""")

    // ₹500 · Rs.500 · Rs 500 · 500 ரூபாய் · 500/- · ₹1,500 · 1.5K · 2K
    private val amountPattern = Regex(
        """(?<![\p{L}\p{M}\d.,])((?:₹|rs\.?|inr|ரூ\.?)\s*)?(\d{1,3}(?:,\d{2,3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)(\s*[kK](?![\p{L}\p{M}]))?(\s*(?:/-|rs\.?(?![\p{L}])|rupees|ரூபாய்|ரூ\.?))?(?![\p{L}\p{M}\d])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Ravi 500 10/10 Kumar 300 11/10" → two segments. A new person starts
     * where a name follows an amount. Also splits on ";".
     */
    private fun splitPeople(text: String): List<String> =
        text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
            val masked = maskDates(part)
            val amounts = amountPattern.findAll(masked).toList()
            if (amounts.size < 2) return@flatMap listOf(part)
            val cuts = mutableListOf<Int>()
            for (i in 0 until amounts.lastIndex) {
                val between = masked.substring(amounts[i].range.last + 1, amounts[i + 1].range.first)
                val nameStart = firstNameTokenStart(between) ?: continue
                cuts += amounts[i].range.last + 1 + nameStart
            }
            if (cuts.isEmpty()) listOf(part)
            else (listOf(0) + cuts + part.length).zipWithNext { a, b -> part.substring(a, b).trim(' ', ',', '-', '|') }
                .filter { it.isNotBlank() }
        }

    private fun firstNameTokenStart(text: String): Int? =
        Regex("""[\p{L}][\p{L}\p{M}.']*""").findAll(text)
            .firstOrNull { isNameLike(it.value) && !isKeyword(it.value) }
            ?.range?.first

    private fun mask(text: String, ranges: List<IntRange>): String {
        val chars = text.toCharArray()
        ranges.forEach { r -> for (i in r) if (i in chars.indices) chars[i] = ' ' }
        return String(chars)
    }

    /**
     * Blanks out every date on the line — "10/10", "10 Oct", "Oct 10",
     * "10 அக்டோபர்" — so its digits are never read as an amount and its
     * month is never read as a person.
     */
    private fun maskDates(text: String): String {
        var masked = mask(text, (anyDate.findAll(text) + garbledDate.findAll(text)).map { it.range }.toList())
        repeat(6) {
            val match = DocumentDates.findMatch(masked, LocalDate.now(), allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)
                ?: return masked
            val start = masked.indexOf(match.text).takeIf { it >= 0 } ?: return masked
            masked = mask(masked, listOf(start until start + match.text.length))
        }
        return masked
    }

    // -------------------------------------------------------------- segment

    private sealed interface Segment {
        data class DateHeading(val date: DateMatch, val confidence: Int?) : Segment
        data class DirectionHeading(val direction: TxnDirection, val reason: String) : Segment
        data class Transaction(val value: ExtractedTransaction) : Segment
        data object Unknown : Segment
    }

    private fun parseSegment(
        segment: String,
        line: OcrLine,
        pageIndex: Int,
        lineIndex: Int,
        today: LocalDate,
        carryDate: DateMatch?,
        carryDateConfidence: Int?,
        carryDirection: Pair<TxnDirection, String>?,
        repairedAmounts: Set<String> = emptySet(),
    ): Segment {
        // Date: this segment's own, or one from a heading above.
        val ownDate = DocumentDates.findMatch(segment, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)
        val datelessMasked = maskDates(segment)

        // Amount: one clear amount, or nothing (several plain numbers = ambiguous).
        val amountMatches = amountPattern.findAll(datelessMasked).toList()
        val marked = amountMatches.filter { it.groupValues[1].isNotBlank() || it.groupValues[3].isNotBlank() || it.groupValues[4].isNotBlank() }
        val chosen = when {
            marked.size == 1 -> marked.single()
            marked.size > 1 -> null
            amountMatches.size == 1 -> amountMatches.single()
            else -> null
        }
        val amountAmbiguous = chosen == null && amountMatches.size > 1
        val amount = chosen?.let { toAmount(it) }
        // Came from "200O"-style OCR repair: never silently trusted.
        val amountRepaired = chosen != null && chosen.groupValues[2].replace(",", "") in repairedAmounts
        val unreadableMarks = Regex("""\?{2,}|\?\s*\?""").containsMatchIn(segment) ||
            (chosen == null && amountMatches.isEmpty() && segment.contains('?'))

        // Words left after removing the date and amount(s).
        val remainder = mask(datelessMasked, amountMatches.map { it.range })
        val tokens = Regex("""[\p{L}][\p{L}\p{M}.']*""").findAll(remainder).map { it.value.trimEnd('.', '\'') }.toList()

        val nameTokens = pickName(tokens)
        val directionResult = classify(segment, tokens, hasPerson = nameTokens != null)
        val name = nameTokens?.joinToString(" ") { stripDative(it).first }
        val description = tokens
            .filterNot { t -> nameTokens?.contains(t) == true || isKeyword(t) }
            .filter { it.count(Char::isLetter) >= 2 }
            .joinToString(" ")
            .ifBlank { null }

        // Headings: a lone date, or a lone "To give" / "To receive".
        if (amount == null && !amountAmbiguous && !unreadableMarks && name == null) {
            if (ownDate != null && directionResult == null) return Segment.DateHeading(ownDate, confidenceOf(line, listOf(ownDate.text)))
            if (directionResult != null && ownDate == null) return Segment.DirectionHeading(directionResult.first, directionResult.second)
        }

        // A "Total 5000" line sums other rows; it is not a person's transaction.
        val isTotalLine = name == null && Regex("""(?i)\btotal\b|மொத்தம்""").containsMatchIn(segment)
        // A transaction needs evidence of both a person and an amount; a
        // missing Credit/Debit is left for review. Digits alone (a garbled
        // date), a name alone, or near-zero OCR confidence (background
        // marks) are not transactions — the line stays listed, unfilled.
        val barelyRead = line.confidence in 1..9
        val isTransaction = !isTotalLine && !barelyRead && name != null && (amount != null || amountAmbiguous)
        if (!isTransaction) return Segment.Unknown

        val date = ownDate ?: carryDate
        val (direction, reason) = directionResult ?: carryDirection ?: (TxnDirection.UNKNOWN to null)

        val nameConfidence = nameTokens?.let { confidenceOf(line, it) }
        val amountConfidence = chosen?.let { confidenceOf(line, listOf(it.value.trim())) }
        val dateConfidence = when {
            ownDate != null -> confidenceOf(line, listOf(ownDate.text))
            date != null -> carryDateConfidence
            else -> null
        }
        val directionConfidence = if (direction == TxnDirection.UNKNOWN) null else line.confidence

        val issues = buildSet {
            if (name == null) add(ReviewIssue.NAME_MISSING)
            if (amountAmbiguous) add(ReviewIssue.AMOUNT_AMBIGUOUS)
            else if (amount == null) add(ReviewIssue.AMOUNT_MISSING)
            if (direction == TxnDirection.UNKNOWN) add(ReviewIssue.TYPE_UNKNOWN)
            if (nameConfidence != null && nameConfidence < CONFIDENCE_THRESHOLD) add(ReviewIssue.NAME_UNCLEAR)
            if (amountRepaired || (amountConfidence != null && amountConfidence < CONFIDENCE_THRESHOLD)) add(ReviewIssue.AMOUNT_UNCLEAR)
            if (dateConfidence != null && dateConfidence < CONFIDENCE_THRESHOLD) add(ReviewIssue.DATE_UNCLEAR)
            if (date == null) add(ReviewIssue.DATE_MISSING)
            else if (date.yearAssumed) add(ReviewIssue.YEAR_ASSUMED)
        }

        return Segment.Transaction(
            ExtractedTransaction(
                personName = name,
                amount = amount,
                amountText = chosen?.value?.trim(),
                date = date?.date,
                dateText = date?.text,
                direction = direction,
                directionReason = reason,
                description = description,
                originalLine = line.text,
                pageIndex = pageIndex,
                lineIndex = lineIndex,
                box = line.box,
                nameConfidence = nameConfidence,
                amountConfidence = amountConfidence,
                dateConfidence = dateConfidence,
                directionConfidence = directionConfidence,
                issues = issues,
            ),
        )
    }

    // ------------------------------------------------------------ bill page

    // Quantity units written on bill item lines ("2 kg", "1L", "5 pcs", "3 கிலோ").
    private val unitWord = Regex(
        """(?i)(?<=\d|\s|^)(kg|kgs|kilo|g|gm|gms|gram|grams|ltr|ltrs|lit|litre|litres|l|ml|pcs|pc|nos|box|bag|bags|pkt|pkts|packet|doz|dozen|mtr|mtrs|bottle|btl|bundle|கிலோ|லிட்டர்|பாக்கெட்)(?![\p{L}])""",
    )
    private val qtyTimesRate = Regex("""\d\s*[x×*@]\s*\d""")
    private val billWord = Regex("""(?i)\b(bill|invoice|estimate|cash\s*memo|receipt|particulars|qty|rate)\b|பில்|ரசீது""")
    private val totalWord = Regex("""(?i)\b(grand\s*total|net\s*total|net\s*amount|total|nett?)\b|மொத்தம்""")
    private val partyLabel = Regex("""(?i)^\s*(?:name|to|m\s*/\s*s\.?|m[|l1]s\.?|ms\.?|customer|party|buyer|sold\s*to|bill\s*to|mr\.?|mrs\.?|பெயர்|திரு(?:மதி)?)(?![\p{L}])\s*[:.\-]?\s*(.*)$""")
    private val billNumber = Regex("""(?i)\b(?:bill|inv|invoice)\s*(?:no|number|#)\.?\s*[:\-]?\s*([A-Za-z0-9\-/]+)""")
    // Bill footer lines: what was paid, what is left, and when it is due.
    private val paidWord = Regex("""(?i)\b(paid|advance|adv|received|recd|rcvd|amount\s*paid|cash\s*paid)\b|வரவு|செலுத்த|கட்டிய|அட்வான்ஸ்|முன்பணம்""")
    private val balanceWord = Regex("""(?i)\b(balance|bal|pending|baki|baaki|due\s*amount|amount\s*due|remaining)\b|பாக்கி|மீதி|நிலுவை""")
    private val dueWord = Regex("""(?i)\b(due\s*date|due\s*on|due\s*by|due|pay\s*by|payable\s*by|last\s*date)\b|கடைசி\s*தேதி|செலுத்த\s*வேண்டிய\s*தேதி""")
    private val fullyPaidStamp = Regex("""(?i)^\s*(fully\s*paid|paid|settled|cleared)\s*[.!]?\s*$""")

    /**
     * What kind of page this is, from what is written on it:
     * - BILL: item rows (qty / unit / rate) with a Total / Paid / Balance line or a bill heading;
     * - UNKNOWN: some bill signs but not enough to be sure — the owner chooses;
     * - NOTES: "who owes whom" lines.
     */
    fun classify(lines: List<OcrLine>, today: LocalDate = LocalDate.now()): PageKind = billOf(0, lines, today).first

    /**
     * A bill page is ONE entry: party, date and the written total (never the items
     * added up — no written total means the owner enters it). Returns the page kind
     * and that entry (built for any page with lines, so the owner may choose "Bill").
     */
    private fun billOf(pageIndex: Int, lines: List<OcrLine>, today: LocalDate): Pair<PageKind, ExtractedTransaction?> {
        data class Row(val index: Int, val line: OcrLine, val text: String, val amounts: List<BigDecimal>)
        val rows = lines.mapIndexed { i, l ->
            val text = prepare(l.text).first
            // Phone numbers, GSTINs and the bill number are not amounts.
            val masked = maskDates(text).let { m -> mask(m, (phoneLike.findAll(m) + gstinLike.findAll(m) + billNumber.findAll(m)).map { it.range }.toList()) }
            Row(i, l, text, amountPattern.findAll(masked).mapNotNull { toAmount(it) }.toList())
        }
        fun findDate(text: String) = DocumentDates.findMatch(text, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)
        // Footer: "Paid / Advance 500", "Balance 300", "Due date 15/10" — not items, not the total.
        fun isFooter(r: Row) = paidWord.containsMatchIn(r.text) || balanceWord.containsMatchIn(r.text) ||
            (dueWord.containsMatchIn(r.text) && findDate(r.text) != null)
        val paidRow = rows.lastOrNull { paidWord.containsMatchIn(it.text) && !balanceWord.containsMatchIn(it.text) && it.amounts.isNotEmpty() }
        val balanceRow = rows.lastOrNull { balanceWord.containsMatchIn(it.text) && it.amounts.isNotEmpty() }
        val dueRow = rows.lastOrNull { dueWord.containsMatchIn(it.text) && findDate(it.text) != null }
        val totalRow = rows.lastOrNull { totalWord.containsMatchIn(it.text) && it.amounts.isNotEmpty() && !isFooter(it) }
        val itemRows = rows.filter { r ->
            r !== totalRow && !isFooter(r) && r.amounts.isNotEmpty() && r.text.any { it.isLetter() } &&
                (unitWord.containsMatchIn(r.text) || qtyTimesRate.containsMatchIn(r.text) ||
                    // "Rice 2 120" is an item; "Kumar 500 Ravi 300" is two people, not an item.
                    (r.amounts.size >= 2 && splitPeople(r.text).size == 1))
        }
        val hasBillWord = rows.any { billWord.containsMatchIn(it.text) }
        val hasFooter = totalRow != null || paidRow != null || balanceRow != null
        val kind = when {
            itemRows.size >= 2 && (hasFooter || hasBillWord) -> PageKind.BILL
            itemRows.size >= 2 || (itemRows.size == 1 && (totalRow != null || hasBillWord)) -> PageKind.UNKNOWN
            else -> PageKind.NOTES
        }
        if (rows.isEmpty()) return kind to null

        // Total: only what is written — the Total line, else Paid + Balance (both written; checked by the owner).
        val writtenPaid = paidRow?.amounts?.lastOrNull()
        val writtenBalance = balanceRow?.amounts?.lastOrNull()
        val total = totalRow?.amounts?.maxOrNull()
            ?: if (writtenPaid != null && writtenBalance != null) writtenPaid + writtenBalance else null

        // Paid: written, else Total − Balance, else a "PAID" stamp means all of it.
        val fullyPaid = rows.any { fullyPaidStamp.matches(it.text) }
        val paidRaw = when {
            writtenPaid != null -> writtenPaid
            total != null && writtenBalance != null -> total - writtenBalance
            fullyPaid -> total
            else -> null
        }
        // More paid than the bill, or Paid + Balance ≠ Total: something was misread — the owner checks.
        val paidMismatch = total != null && paidRaw != null &&
            (paidRaw.signum() < 0 || paidRaw > total || (writtenPaid != null && writtenBalance != null && (writtenPaid + writtenBalance).compareTo(total) != 0))
        val paid = paidRaw?.takeIf { total != null && it.signum() >= 0 && it <= total }

        fun cleanName(raw: String): String? = maskDates(raw)
            .let { m -> mask(m, (phoneLike.findAll(m) + gstinLike.findAll(m) + billNumber.findAll(m)).map { it.range }.toList()) }
            .replace(Regex("""(?i)\b(?:ph|phone|cell|mob(?:ile)?|gstin|gst)\b\.?\s*:?"""), " ")
            .replace(Regex("""[^\p{L}\p{M}\s.&']"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '.', '\'')
            .takeIf { it.count(Char::isLetter) >= 2 }
        fun isDate(text: String) = DocumentDates.findMatch(text, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR) != null

        // Customer: "Name / M/s / To / Customer: …" — or, when the label stands alone, the next line.
        val customer: Pair<Row, String>? = rows.withIndex().firstNotNullOfOrNull { (i, r) ->
            val m = partyLabel.find(r.text) ?: return@firstNotNullOfOrNull null
            cleanName(m.groupValues[1])?.let { r to it } ?: rows.getOrNull(i + 1)
                ?.takeIf { next -> next.amounts.isEmpty() && !isDate(next.text) && !billWord.containsMatchIn(next.text) }
                ?.let { next -> cleanName(next.text)?.let { next to it } }
        }
        // Company / shop: the first plain name line in the header (above the items), not the
        // customer line, not an address / phone / e-mail / GST line.
        val firstItem = itemRows.minOfOrNull { it.index } ?: (totalRow ?: paidRow ?: balanceRow)?.index ?: rows.size
        val company: Pair<Row, String>? = rows.filter { it.index < firstItem }.firstNotNullOfOrNull { r ->
            r.takeIf {
                it !== customer?.first && partyLabel.find(it.text) == null && it.amounts.isEmpty() &&
                    !billWord.containsMatchIn(it.text) && !totalWord.containsMatchIn(it.text) && !isDate(it.text) &&
                    !addressLine.containsMatchIn(it.text) && !it.text.contains('@') && it.text.count(Char::isLetter) >= 3
            }?.let { cleanName(it.text) }?.let { r to it }
        }

        // The row's name until the owner picks Credit (customer) or Debit (company) on review.
        val (partyRow, party) = customer ?: company ?: (null to null)
        val labelled = customer

        // The bill date is the first date that is not the due date.
        val date = rows.filter { it !== dueRow }.firstNotNullOfOrNull { findDate(it.text) }
        val dueDate = dueRow?.let { findDate(it.text) }?.date
        val number = rows.firstNotNullOfOrNull { billNumber.find(it.text)?.groupValues?.get(1) }
        val nameConfidence = partyRow?.line?.confidence
        val amountConfidence = totalRow?.line?.confidence

        val issues = buildSet {
            if (party == null) add(ReviewIssue.NAME_MISSING)
            // Not labelled: the top line may be the seller's own name — the owner confirms who it is.
            else if (labelled == null || (nameConfidence != null && nameConfidence < CONFIDENCE_THRESHOLD)) add(ReviewIssue.NAME_UNCLEAR)
            if (total == null) add(ReviewIssue.AMOUNT_MISSING)
            else if (totalRow == null || paidMismatch || (amountConfidence != null && amountConfidence < CONFIDENCE_THRESHOLD)) add(ReviewIssue.AMOUNT_UNCLEAR)
            add(ReviewIssue.TYPE_UNKNOWN)
            if (date == null) add(ReviewIssue.DATE_MISSING) else if (date.yearAssumed) add(ReviewIssue.YEAR_ASSUMED)
        }
        val anchor = totalRow ?: itemRows.lastOrNull() ?: paidRow ?: balanceRow ?: rows.last()
        return kind to ExtractedTransaction(
            personName = party,
            amount = total?.setScale(2, RoundingMode.HALF_UP),
            amountText = totalRow?.text,
            date = date?.date,
            dateText = date?.text,
            direction = TxnDirection.UNKNOWN,
            directionReason = "Handwritten bill (${itemRows.size} items) — choose Credit if the customer owes, Debit if you owe the shop",
            description = listOfNotNull(HANDWRITTEN_BILL_LABEL, number?.let { "No. $it" }, "${itemRows.size} items").joinToString(" · "),
            originalLine = anchor.line.text,
            pageIndex = pageIndex,
            lineIndex = anchor.index,
            box = anchor.line.box,
            nameConfidence = nameConfidence,
            amountConfidence = amountConfidence,
            dateConfidence = null,
            directionConfidence = null,
            issues = issues,
            billCompany = company?.second,
            billCustomer = customer?.second,
            billPaid = paid?.setScale(2, RoundingMode.HALF_UP),
            billDueDate = dueDate,
        )
    }

    // Header lines that are not the shop's name.
    private val phoneLike = Regex("""(?<![\d])(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?![\d])""")
    private val gstinLike = Regex("""(?i)\b\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\b""")
    private val addressLine = Regex(
        """(?i)\b(road|rd|street|st|nagar|bazaar|bazar|main|cross|near|opp|opposite|lane|salai|theru|colony|layout|post|dist|district|taluk|pin|pincode|door|no\s*\d|ph|phone|cell|mob|mobile|email|e-mail|gst|gstin|tin|website|www)\b|தெரு|சாலை|நகர்""",
    )

    private fun toAmount(match: MatchResult): BigDecimal? {
        val number = match.groupValues[2].replace(",", "").toBigDecimalOrNull() ?: return null
        val value = if (match.groupValues[3].isNotBlank()) number.multiply(BigDecimal(1000)) else number
        return value.takeIf { it.signum() > 0 && it < BigDecimal("1000000000") }?.setScale(2, RoundingMode.HALF_UP)
    }

    // ----------------------------------------------------------------- name

    private val stopWords = setOf(
        "give", "gives", "given", "gave", "pay", "pays", "paid", "payable", "debit", "dr", "credit", "cr",
        "receive", "receives", "received", "receivable", "get", "collect", "owes", "owe", "me", "my", "i",
        "to", "from", "for", "the", "a", "an", "will", "should", "has", "have", "is", "must", "need",
        "rs", "inr", "rupees", "rupee", "only", "amount", "amt", "name", "date", "dt", "on", "at", "by",
        "and", "with", "total", "lent", "lend", "borrowed", "borrow", "return", "back", "balance", "due",
        "us", "we", "our", "him", "her", "them", "list", "k", "got", "took", "taken", "money", "advance",
        "குடுத்தேன்", "குடுத்தது", "கொடுத்தோம்", "கொடுத்த", "வாங்கியது", "வாங்கினோம்", "வாங்கிய", "வந்தது",
        "எனக்கு", "நான்", "நாம்", "ரூபாய்", "ரூ", "தேதி", "வேண்டும்", "வேணும்",
        "வேண்டியது", "வேண்டியவை", "வேண்டிய", "பட்டியல்",
        "கொடுக்கணும்", "குடுக்கணும்", "கொடுக்க", "குடுக்க", "கொடு", "கொடுத்தது", "கொடுத்தேன்",
        "தரணும்", "தர", "தா", "தந்தது", "வாங்கணும்", "வாங்க", "வாங்கினது", "வாங்கினேன்", "வரணும்", "வர",
        "வரவு", "செலவு", "கடன்", "பாக்கி",
    )

    private fun isKeyword(token: String): Boolean {
        val lower = token.lowercase(Locale.ROOT).trim('.', '\'')
        return lower in stopWords || stripDative(token).first.lowercase(Locale.ROOT) in stopWords ||
            isFuzzyDebit(token) || isFuzzyCredit(token)
    }

    // One letter off "debit"/"credit" (5+ letters, so a name like "Debi" is safe),
    // plus common short forms OCR produces from handwriting.
    private fun isFuzzyDebit(token: String): Boolean {
        val t = token.lowercase(Locale.ROOT).trim('.', '\'')
        return t in setOf("debt", "dbt", "dbit", "debit") || (t.length >= 5 && editDistance(t, "debit") <= 1)
    }

    private fun isFuzzyCredit(token: String): Boolean {
        val t = token.lowercase(Locale.ROOT).trim('.', '\'')
        return t in setOf("credt", "crdt", "credit") || (t.length >= 5 && editDistance(t, "credit") <= 1)
    }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }

    // Tamil vowel signs are "marks", not letters, so count both.
    private fun isNameLike(token: String): Boolean {
        val letters = token.count { it.isLetter() }
        val lettersAndMarks = token.count { it.isLetter() || Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() } }
        return letters >= 2 && lettersAndMarks * 10 >= token.length * 6
    }

    /** First run (up to 3) of name-like, non-keyword words. */
    private fun pickName(tokens: List<String>): List<String>? {
        val start = tokens.indexOfFirst { isNameLike(it) && !isKeyword(it) }
        if (start < 0) return null
        val run = tokens.drop(start).takeWhile { isNameLike(it) && !isKeyword(it) }.take(3)
        return run.takeIf { it.isNotEmpty() }
    }

    /** "Raviக்கு" → ("Ravi", true): Tamil "to Ravi". */
    private fun stripDative(token: String): Pair<String, Boolean> {
        for (suffix in listOf("க்கு", "உக்கு", "கு")) {
            if (token.endsWith(suffix) && token.length - suffix.length >= 2) {
                return token.removeSuffix(suffix) to true
            }
        }
        return token to false
    }

    // ------------------------------------------------------------ direction

    private val englishCredit = listOf("receivable", "to receive", "receive", "to get", "get", "collect", "owes me", "owe me", "credit", "cr", "lent", "lend to")
    private val englishDebit = listOf("payable", "to pay", "pay", "to give", "give", "debit", "dr", "i owe", "owe to", "borrowed", "borrow")
    // "Money given" / "money received" notebooks (khata): money I GAVE someone
    // is owed back to me (Credit); money I RECEIVED from someone I owe back (Debit).
    private val givenWords = listOf("gave", "given", "lent", "gave to", "given to", "advance given")
    private val receivedWords = listOf("received", "received from", "got", "took", "taken", "borrowed from")
    private val tamilGiven = listOf("கொடுத்தேன்", "கொடுத்தது", "குடுத்தேன்", "குடுத்தது", "கொடுத்தோம்", "கொடுத்த")
    private val tamilReceived = listOf("வாங்கினேன்", "வாங்கியது", "வாங்கினது", "வாங்கினோம்", "வாங்கிய", "வந்தது")
    // Settled events ("paid", "returned") don't say who owes whom now.
    private val settled = listOf("paid", "returned", "settled")
    private val tamilGive = listOf("கொடுக்க", "குடுக்க", "தரணும்", "தர வேண்ட", "தர வேணும்", "கொடு")
    private val tamilGet = listOf("வாங்கணும்", "வாங்க வேண்ட", "வாங்க வேணும்", "வரணும்", "வர வேண்ட", "வரவு")
    private val credit = TxnDirection.CREDIT to "receive"
    private val debit = TxnDirection.DEBIT to "give"

    /**
     * Who pays whom, from the relationship in the sentence, not single words:
     *  "Raviக்கு 500 கொடுக்கணும்" (give TO Ravi) → Debit
     *  "Ravi 500 கொடுக்கணும்" (Ravi must give) → Credit
     *  "Give Kumar 1000" / "Kumar - 1000 - give" / "payable" → Debit
     *  "Receive from Kumar" / "Priya owes me" / "receivable" → Credit
     *  "Kumar will give me" / "எனக்கு தர வேண்டும்" → Credit
     * Mixed or settled signals → null (Unknown, needs review).
     */
    private fun classify(segment: String, tokens: List<String>, hasPerson: Boolean): Pair<TxnDirection, String>? {
        val lower = " " + segment.lowercase(Locale.ROOT).replace(Regex("""[\-–—:,|()]"""), " ").replace(Regex("""\s+"""), " ") + " "
        fun has(phrase: String) = lower.contains(" $phrase ")
        fun hasStem(stem: String) = lower.contains(stem)

        val signals = mutableSetOf<TxnDirection>()
        var reason: String? = null

        // Past tense "gave / received" (money given / received notebooks) is decided below.
        val gaveIt = givenWords.any { has(it) } || tamilGiven.any { hasStem(it) }
        val gotIt = receivedWords.any { has(it) } || tamilReceived.any { hasStem(it) }

        // Tamil: who is giving depends on "எனக்கு" (to me) / "-க்கு" (to them).
        val tamilGiveVerb = tamilGive.any { hasStem(it) } && !tamilGiven.any { hasStem(it) }
        val tamilGetVerb = tamilGet.any { hasStem(it) }
        val toMe = hasStem("எனக்கு")
        val datives = tokens.filter { stripDative(it).second && !isKeyword(it) }
        if (tamilGiveVerb) {
            when {
                toMe -> { signals += TxnDirection.CREDIT; reason = "எனக்கு தர வேண்டும் → receive" }
                datives.isNotEmpty() || hasStem("நான்") -> { signals += TxnDirection.DEBIT; reason = "…க்கு கொடுக்கணும் → give" }
                // "கொடுக்க வேண்டியது:" heading with no person: what I have to give.
                !hasPerson -> { signals += TxnDirection.DEBIT; reason = "கொடுக்க வேண்டியது → give" }
                else -> { signals += TxnDirection.CREDIT; reason = "person must give → receive" }
            }
        }
        if (tamilGetVerb) { signals += TxnDirection.CREDIT; reason = reason ?: "வாங்கணும் → receive" }

        // English with an object: "X will give me", "X owes me", "gives me".
        val givesMe = Regex(""" (will give|gives|should give|has to give|to give|will pay|pays|should pay|to pay|owes|owe) me """).containsMatchIn(lower) ||
            has("owes me") || has("owe me")
        if (givesMe) { signals += TxnDirection.CREDIT; reason = "they give me → receive" }
        else {
            // "Ravi will give 500" (person is the subject) → they pay me.
            val subjectGives = Regex(""" (will give|gives|should give|has to give|will pay|should pay|has to pay) """).containsMatchIn(lower) &&
                !has("i will give") && !has("i will pay") && !has("i should give") && !has("i should pay") && !has("i have to give") && !has("i have to pay")
            if (subjectGives) { signals += TxnDirection.CREDIT; reason = "person will give → receive" }
            else {
                if (englishCredit.any { has(it) }) { signals += TxnDirection.CREDIT; reason = reason ?: "receive" }
                if (englishDebit.any { has(it) }) { signals += TxnDirection.DEBIT; reason = reason ?: "give" }
            }
        }

        // "கொடுத்தேன்" (I gave) / "வாங்கினேன்" (I received) are settled too.
        // Handwriting OCR slips: "Debt", "Dbit", "Credt"; and "2000 D" / "2000 C".
        if (tokens.any { isFuzzyDebit(it) } || Regex("""\d\s*d\.?\s*$""").containsMatchIn(lower.trimEnd())) {
            signals += TxnDirection.DEBIT; reason = reason ?: "debit"
        }
        if (tokens.any { isFuzzyCredit(it) } || Regex("""\d\s*c\.?\s*$""").containsMatchIn(lower.trimEnd())) {
            signals += TxnDirection.CREDIT; reason = reason ?: "credit"
        }

        // Money given → they owe me (Credit); money received → I owe them (Debit).
        // An explicit opposite word on the same line ("… gave … debit") stays for review.
        if (gaveIt != gotIt) {
            val dir = if (gaveIt) TxnDirection.CREDIT else TxnDirection.DEBIT
            return if (signals.all { it == dir }) {
                dir to if (gaveIt) "money given → they owe you" else "money received → you owe them"
            } else {
                null
            }
        }

        // "தந்த" (gave — but who?) and "paid / returned" don't say who owes whom now.
        val isSettled = settled.any { has(it) } || hasStem("தந்த")
        return when {
            signals.size != 1 -> null
            isSettled -> null
            else -> signals.single() to (reason ?: if (signals.single() == TxnDirection.CREDIT) credit.second else debit.second)
        }
    }

    // ----------------------------------------------------------- confidence

    /**
     * Confidence of the OCR words that make up [parts]; the lowest wins.
     * Falls back to the line's confidence when word detail is missing.
     */
    private fun confidenceOf(line: OcrLine, parts: List<String>): Int {
        if (line.words.isEmpty()) return line.confidence
        val wanted = parts.flatMap { it.split(Regex("""\s+""")) }.map { clean(it) }.filter { it.isNotEmpty() }
        val matched = line.words.filter { word ->
            val w = clean(word.text)
            w.isNotEmpty() && wanted.any { part -> w.contains(part) || part.contains(w) }
        }
        return matched.minOfOrNull { it.confidence } ?: line.confidence
    }

    private fun clean(text: String) = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun normalise(text: String) = text.replace('\t', ' ').replace(Regex("""\s+"""), " ").trim()
}

/** Formats ₹1,500.00 / ₹1,24,000.00 (Indian lakh grouping) for display. */
fun formatRupees(amount: BigDecimal): String {
    val value = amount.setScale(2, RoundingMode.HALF_UP)
    val negative = value.signum() < 0
    val plain = value.abs().toPlainString()
    val whole = plain.substringBefore('.')
    val paise = plain.substringAfter('.', "00")
    val grouped = if (whole.length <= 3) whole else {
        val head = whole.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
    }
    return (if (negative) "-₹" else "₹") + grouped + "." + paise
}
