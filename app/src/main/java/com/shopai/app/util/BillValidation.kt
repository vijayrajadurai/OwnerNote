package com.shopai.app.util

import java.math.BigDecimal

/** How far what was read from a bill can be trusted. Only the owner's Save ever writes anything. */
enum class BillStatus {
    /** The critical fields agree with each other (the owner still confirms before saving). */
    READY,
    /** Something is uncertain or doesn't add up: the owner must check it. */
    REVIEW_REQUIRED,
    /** A value is clearly impossible (corrupted OCR): it is never offered as read. */
    INVALID,
    /** Nothing wrong was found, but some fields could not be read. */
    PARTIAL,
}

/** What a check found. [severity]: the status the issue alone would give the bill. */
enum class BillIssue(val severity: BillStatus, val field: BillCheckField) {
    /** Tax larger than any GST rate allows (e.g. a dropped decimal point: ₹436.20 read as 43620). */
    TAX_IMPOSSIBLE(BillStatus.INVALID, BillCheckField.TAX),
    /** CGST and SGST are always equal on a real invoice. */
    CGST_SGST_MISMATCH(BillStatus.REVIEW_REQUIRED, BillCheckField.TAX),
    /** One of CGST/SGST is ~100× the other: a decimal point lost or added by the OCR. */
    DECIMAL_SUSPECT(BillStatus.REVIEW_REQUIRED, BillCheckField.TAX),
    /** IGST printed together with CGST/SGST: one of them was misread. */
    IGST_WITH_CGST_SGST(BillStatus.REVIEW_REQUIRED, BillCheckField.TAX),
    /** The tax lines disagree with their own footer, or only half of CGST/SGST was read. */
    TAX_LINES_INCONSISTENT(BillStatus.REVIEW_REQUIRED, BillCheckField.TAX),
    /** Tax ÷ taxable value is not a GST rate (0.1 % … 40 %). */
    RATE_IMPLAUSIBLE(BillStatus.REVIEW_REQUIRED, BillCheckField.TAX),
    /** Taxable value + tax ± round off ≠ grand total. */
    TOTAL_MISMATCH(BillStatus.REVIEW_REQUIRED, BillCheckField.TOTAL),
    /** The items don't add up to the taxable value / total. */
    ITEMS_MISMATCH(BillStatus.REVIEW_REQUIRED, BillCheckField.ITEMS),
    /** A GSTIN was printed but fails its check digit. */
    GSTIN_INVALID(BillStatus.REVIEW_REQUIRED, BillCheckField.GSTIN),
    /** The bill names a buyer but no clean name could be read. */
    BUYER_UNREADABLE(BillStatus.PARTIAL, BillCheckField.PARTY),
    /** No grand total could be read. */
    TOTAL_MISSING(BillStatus.PARTIAL, BillCheckField.TOTAL),
    /** No "Total" line or amount in words was read: the total shown is the largest amount (the bill screen says so). */
    TOTAL_GUESSED(BillStatus.PARTIAL, BillCheckField.TOTAL),
    /** Very little text came out of the photo — confidence alone is never trusted. */
    TOO_LITTLE_TEXT(BillStatus.REVIEW_REQUIRED, BillCheckField.TOTAL),
}

enum class BillCheckField { TOTAL, TAX, ITEMS, GSTIN, PARTY }

data class BillCheck(val status: BillStatus, val issues: List<BillIssue>) {
    fun has(issue: BillIssue) = issue in issues
    fun fieldNeedsCheck(field: BillCheckField) = issues.any { it.field == field }

    /** Money values (total, tax) need the owner's check before the bill is saved. */
    val moneyNeedsCheck: Boolean
        get() = issues.any { it.field == BillCheckField.TOTAL || it.field == BillCheckField.TAX } &&
            issues.any { it.severity == BillStatus.INVALID || it.severity == BillStatus.REVIEW_REQUIRED }

    companion object {
        val READY_EMPTY = BillCheck(BillStatus.READY, emptyList())
    }
}

/** A GSTIN found on the bill: as printed ([raw]) and, when its check digit passes, [value]. */
data class GstinRead(val raw: String, val value: String?, val buyer: Boolean) {
    val valid: Boolean get() = value != null
}

/**
 * GSTIN: 2-digit state code, 10-character PAN, entity number, "Z", check
 * character. OCR slips (O↔0, I↔1, S↔5, B↔8) are corrected only where the
 * position fixes the kind of character, and only accepted when the check
 * digit then passes — a GSTIN is never invented or guessed.
 */
object GstinReader {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val candidate = Regex("""(?<![A-Za-z0-9])([0-9OoIlSB]{2}[A-Za-z0-9]{13})(?![A-Za-z0-9])""")

    /** Every GSTIN-like code in the bill; one after a buyer / bill-to heading is the buyer's. */
    fun findAll(lines: List<String>): List<GstinRead> {
        val heading = Regex("""(?i)\b(buyer|bill(?:ed)?\s*to|consignee|ship\s*to|customer)\b""")
        val buyerAt = lines.indexOfFirst { heading.containsMatchIn(it) }
        return lines.flatMapIndexed { i, line ->
            if (!Regex("""(?i)gst|uin""").containsMatchIn(line) && candidate.findAll(line).none { looksLikeGstin(it.value) }) return@flatMapIndexed emptyList()
            // "Bill To: Vijayalakshmi Stores   GSTIN: 33AAF…": on the heading's own line, a GSTIN after the heading is the buyer's.
            val headingEnd = if (i == buyerAt) heading.find(line)?.range?.last ?: Int.MAX_VALUE else Int.MAX_VALUE
            candidate.findAll(line).filter { looksLikeGstin(it.groupValues[1]) }.map { m ->
                val raw = m.groupValues[1]
                GstinRead(raw = raw, value = read(raw), buyer = buyerAt in 0 until i || m.range.first > headingEnd)
            }.toList()
        }.distinctBy { it.raw }
    }

    /** Shape only (letters where the PAN has letters…): a random invoice number is not a GSTIN. */
    private fun looksLikeGstin(raw: String): Boolean {
        val n = normalize(raw.uppercase())
        return Regex("""\d{2}[A-Z]{5}\d{4}[A-Z][0-9A-Z]Z[0-9A-Z]""").matches(n)
    }

    /** The GSTIN when its check digit passes (after position-aware OCR fixes), else null. */
    fun read(raw: String): String? {
        val upper = raw.uppercase()
        val base = listOf(upper, normalize(upper)).distinct()
        base.firstOrNull { valid(it) }?.let { return it }
        // The check character (15th) and the entity code (13th) can be a digit or a letter: "1Z1" read for "1ZI". The check
        // character alone first (the entity code is a digit for almost every business); one answer only, else none.
        for (tries in listOf(base.flatMap { swaps(it, 14) }, base.flatMap { swaps(it, 12) }, base.flatMap { g -> swaps(g, 12).flatMap { a -> swaps(a, 14) } })) {
            val ok = tries.distinct().filter { valid(it) }
            if (ok.size == 1) return ok.single()
            if (ok.size > 1) return null
        }
        return null
    }

    private val lookAlike = mapOf('1' to "IL", 'I' to "1L", 'L' to "1I", '0' to "OD", 'O' to "0D", 'D' to "0O", '5' to "S", 'S' to "5", '8' to "B", 'B' to "8",
        '2' to "Z", 'Z' to "2", '6' to "G", 'G' to "6")
    private fun swaps(g: String, at: Int): List<String> =
        if (at >= g.length) listOf(g) else listOf(g) + lookAlike[g[at]].orEmpty().map { c -> g.substring(0, at) + c + g.substring(at + 1) }

    fun valid(gstin: String): Boolean {
        if (!Regex("""\d{2}[A-Z]{5}\d{4}[A-Z][0-9A-Z]Z[0-9A-Z]""").matches(gstin)) return false
        var sum = 0
        for (i in 0 until 14) {
            val product = CHARS.indexOf(gstin[i]) * (if (i % 2 == 0) 1 else 2)
            sum += product / 36 + product % 36
        }
        return CHARS[(36 - sum % 36) % 36] == gstin[14]
    }

    private fun normalize(s: String): String = buildString {
        s.forEachIndexed { i, c ->
            append(
                when (i) {
                    0, 1, 7, 8, 9, 10 -> digit(c)
                    2, 3, 4, 5, 6, 11 -> letter(c)
                    13 -> if (c == '2') 'Z' else c
                    else -> c
                },
            )
        }
    }

    private fun digit(c: Char) = when (c) { 'O', 'D', 'Q' -> '0'; 'I', 'L' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; 'G' -> '6'; else -> c }
    private fun letter(c: Char) = when (c) { '0' -> 'O'; '1' -> 'I'; '5' -> 'S'; '8' -> 'B'; '2' -> 'Z'; '6' -> 'G'; else -> c }
}

/**
 * Picks the best of several OCR passes over the same bill photo (English-only,
 * English + Tamil, a cleaned-up image) by what the text *says*, not by
 * Tesseract's confidence: a labelled grand total, amounts that add up, real
 * lines of text, and no Tamil letters glued inside English words (the eng+tam
 * model's typical slip on printed bills).
 */
object OcrResultChooser {
    /** Higher is better. Deterministic: the same text always gets the same score. */
    fun score(text: String): Int {
        val lines = BillTextParser.cleanLines(text)
        if (lines.isEmpty()) return Int.MIN_VALUE / 2
        val bill = BillTextParser.parse(text)
        var s = minOf(lines.size, 40)
        if (bill.total != null) s += 20
        if (bill.totalFromLabel) s += 30
        s += when (bill.check.status) {
            BillStatus.READY -> 40
            BillStatus.PARTIAL -> 25
            BillStatus.REVIEW_REQUIRED -> 0
            BillStatus.INVALID -> -40
        }
        s -= 15 * bill.check.issues.count { it.field == BillCheckField.TAX || it.field == BillCheckField.TOTAL }
        if (bill.date != null) s += 5
        if (bill.merchantName != null) s += 5
        // Items that add up to the bill: the rows were read, not just the column of amounts.
        if (bill.items.size >= 2 && !bill.check.has(BillIssue.ITEMS_MISMATCH)) s += 3 * minOf(bill.items.size, 10)
        // Rows read whole — quantity × rate = amount — even when the bill's own totals disagree (owner's hardware bill, 17 Oct
        // 2026: a pass with no rows at all tied the pass with all 12, and the empty one would have been kept).
        s += minOf(bill.items.count { it.quantity != null && it.unitPrice != null }, 30)
        s -= 3 * mixedScriptWords(text)
        return s
    }

    /** Good enough to stop after one pass: a labelled total, nothing impossible, no money check needed. */
    fun goodEnough(text: String): Boolean {
        val bill = BillTextParser.parse(text)
        // REVIEW_REQUIRED (items not adding up, a line missing) also tries the other passes: a tilted photo's first read can
        // lose half the bill and still find its amount in words (test bill, 12 Oct 2026).
        return bill.totalFromLabel && !bill.check.moneyNeedsCheck && bill.check.status != BillStatus.INVALID &&
            bill.check.status != BillStatus.REVIEW_REQUIRED && mixedScriptWords(text) <= 1 && !BillTextParser.readAsColumns(text)
    }

    /** "Tota்l", "GSTஇN": words mixing Latin letters and Tamil script. */
    fun mixedScriptWords(text: String): Int = text.split(Regex("""\s+""")).count { w ->
        w.any { it in 'A'..'Z' || it in 'a'..'z' } && w.any { it in '஀'..'௿' }
    }

    /**
     * Index of the best text (first one wins a tie, so the cheapest pass is kept). Passes that read the same total back each
     * other up: a noisy pass with more (garbage) lines doesn't win over three that agree (dot-matrix bill, 12 Oct 2026).
     */
    fun best(texts: List<String>): Int {
        val totals = texts.map { BillTextParser.parse(it).total }
        val scores = texts.indices.map { i -> score(texts[i]) + 20 * totals.indices.count { j -> j != i && totals[i] != null && totals[j]?.compareTo(totals[i]) == 0 } }
        return texts.indices.maxWithOrNull(compareBy<Int>({ scores[it] }, { -it })) ?: -1
    }
}

/**
 * Deterministic checks on what was read from a bill — plain arithmetic, no
 * AI: taxable value → CGST/SGST/IGST → round off → grand total, GST rate
 * plausibility, CGST = SGST, decimal-point slips, items against the totals,
 * GSTIN check digits. OCR confidence never makes a bill READY on its own.
 */
object BillValidation {
    private val maxGstShare = BigDecimal("0.40")
    private val minGstShare = BigDecimal("0.001")
    private val rupee = BigDecimal("1.00")

    fun check(bill: ExtractedBill, lines: List<String>, ocrConfidence: Int = -1): BillCheck {
        val issues = linkedSetOf<BillIssue>()
        val total = bill.total
        val gst = bill.gst
        val tax = gst?.total
        val taxable = bill.taxableValue

        if (gst != null) {
            val cgst = gst.cgst
            val sgst = gst.sgst
            if (cgst != null && sgst != null && (cgst - sgst).abs() > rupee) {
                issues += BillIssue.CGST_SGST_MISMATCH
                val big = cgst.max(sgst)
                val small = cgst.min(sgst)
                if (small.signum() > 0 && big.divide(small, 2, java.math.RoundingMode.HALF_UP) >= BigDecimal("9")) issues += BillIssue.DECIMAL_SUSPECT
            }
            if (gst.igst != null && (cgst != null || sgst != null)) issues += BillIssue.IGST_WITH_CGST_SGST
            if (gst.suspect) issues += BillIssue.TAX_LINES_INCONSISTENT
        }

        if (tax != null && tax.signum() > 0) {
            // GST is at most 40 % of the value before tax: never ≥ the bill total, never > 40 % of the base.
            val base = taxable ?: total?.minus(tax)
            if ((total != null && tax >= total) || (base != null && base.signum() > 0 && tax > base * maxGstShare) || (base != null && base.signum() <= 0)) {
                issues += BillIssue.TAX_IMPOSSIBLE
            } else if (taxable != null && taxable.signum() > 0 && tax < taxable * minGstShare) {
                issues += BillIssue.RATE_IMPLAUSIBLE
            }
        }

        // taxable + tax (± round off, or rounded to the rupee) = grand total.
        val (charges, afterTax) = BillTextParser.chargesIn(lines)
        if (taxable != null && tax != null && total != null && BillIssue.TAX_IMPOSSIBLE !in issues) {
            // TCS / cess are added after the tax.
            val sum = taxable + tax + afterTax
            val round = bill.roundOff ?: BigDecimal.ZERO
            val candidates = listOf(sum, sum + round, sum - round, sum.setScale(0, java.math.RoundingMode.HALF_UP))
            if (candidates.none { (it - total).abs() <= BigDecimal("0.05") }) issues += BillIssue.TOTAL_MISMATCH
        }

        // Items: their sum should be the taxable value (GST invoice) or the total (simple bill).
        if (bill.items.size >= 2) {
            val itemSum = BillTextParser.sum(bill.items)
            val beforeTax = listOfNotNull(taxable, total?.let { t -> tax?.let { t - it } })
            // Items are printed before the discount: their sum is the value before tax plus the discount.
            // Service charge / freight are in the value before tax but are not items.
            val bases = (beforeTax + listOfNotNull(total)).flatMap { listOf(it, it - charges, it - afterTax, it - charges - afterTax) }
            val targets = bases + bill.discount?.let { d -> bases.map { it + d } }.orEmpty()
            if (targets.isNotEmpty() && targets.none { (it - itemSum).abs() <= rupee }) issues += BillIssue.ITEMS_MISMATCH
        }

        if (listOfNotNull(bill.sellerGstin, bill.buyerGstin).any { !it.valid }) issues += BillIssue.GSTIN_INVALID
        if (bill.customerName == null && BillTextParser.namesABuyer(lines)) issues += BillIssue.BUYER_UNREADABLE
        if (total == null) issues += BillIssue.TOTAL_MISSING
        // No "Total" line and no amount in words: the total is the biggest amount seen — the owner checks it.
        else if (!bill.totalFromLabel) issues += BillIssue.TOTAL_GUESSED
        // A photo that lost most of its text can still report high confidence (TEST-025).
        if (lines.size < 4 || (total == null && lines.size < 8)) issues += BillIssue.TOO_LITTLE_TEXT

        val status = when {
            issues.any { it.severity == BillStatus.INVALID } -> BillStatus.INVALID
            issues.any { it.severity == BillStatus.REVIEW_REQUIRED } -> BillStatus.REVIEW_REQUIRED
            // A poorly read photo needs a check even when the numbers agree.
            ocrConfidence in 0 until 70 -> BillStatus.REVIEW_REQUIRED
            issues.isNotEmpty() || bill.date == null || (bill.merchantName == null && bill.customerName == null) -> BillStatus.PARTIAL
            else -> BillStatus.READY
        }
        return BillCheck(status, issues.toList())
    }
}
