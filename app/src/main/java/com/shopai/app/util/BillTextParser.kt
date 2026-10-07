package com.shopai.app.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One itemised line of a bill: what it was and what it cost. On a GST table
 * row the HSN/SAC code, quantity, unit and unit price are read too — only
 * when qty × unit price comes to the line amount (never guessed).
 */
data class BillLineItem(
    val description: String,
    val amount: BigDecimal,
    val hsn: String? = null,
    val quantity: BigDecimal? = null,
    val unit: String? = null,
    val unitPrice: BigDecimal? = null,
)

/** CGST / SGST (or UTGST) / IGST as printed; [suspect] when the lines disagree with each other. */
data class GstBreakdown(
    val cgst: BigDecimal?,
    val sgst: BigDecimal?,
    val igst: BigDecimal?,
    /** A plain "Tax / GST amount" line when no CGST/SGST/IGST line is printed. */
    val otherTax: BigDecimal?,
    /** GST rates printed next to the tax lines ("@9%" → 9). Rates, never money. */
    val rates: List<BigDecimal>,
    val suspect: Boolean,
) {
    /** CGST + SGST, or IGST, or the plain tax line; null when no tax is printed. */
    val total: BigDecimal?
        get() = when {
            cgst != null && sgst != null -> cgst + sgst
            igst != null -> igst
            else -> otherTax
        }
}

/**
 * What could be read from a bill photo. Every field is optional; the
 * screen asks the owner to fill in whatever is missing.
 */
data class ExtractedBill(
    val merchantName: String?,
    val total: BigDecimal?,
    /** True when [total] came from a "Total" line or the amount in words, not a best guess. */
    val totalFromLabel: Boolean,
    /** Total written out in words ("Rupees ... Only"), if printed on the bill. */
    val amountInWords: BigDecimal? = null,
    val items: List<BillLineItem>,
    val date: java.time.LocalDate? = null,
    /** "Customer: Ravi" / "Name:" / "Bill to" / "M/s" on the bill. */
    val customerName: String? = null,
    /**
     * How much of [total] was paid on the bill, worked out from Cash/UPI/
     * Paid/Advance, Change and Balance lines. Null when the bill doesn't say.
     */
    val paid: BigDecimal? = null,
    /** "Invoice No: INV-2041" / "Bill No. 118". */
    val invoiceNumber: String? = null,
    /**
     * The tax on the bill: CGST + SGST, or IGST, or a "Tax / GST" line — null
     * when it isn't printed or when the checks ([check]) found it impossible.
     */
    val tax: BigDecimal? = null,
    /** "Taxable Value" / "Sub Total" before tax, when printed. */
    val taxableValue: BigDecimal? = null,
    /** CGST / SGST / IGST as read (all rate lines added up); see [check] before trusting them. */
    val gst: GstBreakdown? = null,
    val roundOff: BigDecimal? = null,
    /** "Discount 100.00" / "Less: Discount" (the amount; a "10%" rate alone is not read as money). */
    val discount: BigDecimal? = null,
    /** The issuer's GSTIN (first one on the bill) and the buyer's, each with its checksum result. */
    val sellerGstin: GstinRead? = null,
    val buyerGstin: GstinRead? = null,
    /** Financial and field checks on what was read: READY / REVIEW_REQUIRED / INVALID / PARTIAL. */
    val check: BillCheck = BillCheck.READY_EMPTY,
)

/** What could be read from a handwritten note. */
data class ExtractedNote(
    val name: String?,
    val amount: BigDecimal?,
    val date: java.time.LocalDate?,
)

/**
 * Reads a short handwritten note like "Ravi  ₹500  21/9". Handwriting
 * recognition is unreliable, so this only proposes values; the owner
 * checks them before saving.
 */
object HandwrittenNoteParser {
    private val currencyAmount = Regex("""(?:₹|rs\.?|inr)\s*(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*(?:/-|rs\.?|rupees|ரூ)""", RegexOption.IGNORE_CASE)
    private val plainNumber = Regex("""(?<![\d/.\-:])(\d[\d,]*(?:\.\d{1,2})?)(?![\d/.\-:])""")
    private val anyDate = Regex("""\d{1,4}[/\-.]\d{1,2}(?:[/\-.]\d{2,4})?""")
    private val labelWords = Regex("""(?i)\b(name|amount|amt|rs|date|dt|total|paid|given|to|from)\b\s*[:\-]?""")

    fun parse(text: String, today: java.time.LocalDate = java.time.LocalDate.now()): ExtractedNote {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Remove dates first so "21/9" is never read as an amount.
        val withoutDates = lines.map { it.replace(anyDate, " ") }
        return ExtractedNote(
            name = findName(withoutDates),
            amount = findAmount(withoutDates),
            date = DocumentDates.find(text, today, allowYearless = true),
        )
    }

    // An amount marked with ₹ / Rs / "/-" wins; otherwise the largest number.
    private fun findAmount(lines: List<String>): BigDecimal? {
        val joined = lines.joinToString("\n")
        currencyAmount.findAll(joined)
            .mapNotNull { m -> BillTextParser.parseAmount(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
            .maxOrNull()
            ?.let { return it }
        return plainNumber.findAll(joined)
            .filter { it.value.replace(",", "").length <= 7 }
            .mapNotNull { BillTextParser.parseAmount(it.value) }
            .maxOrNull()
    }

    private fun findName(lines: List<String>): String? =
        lines.asSequence()
            .map { line ->
                line.replace(labelWords, " ")
                    .replace(Regex("""[₹\d.,/\-:=*#]+"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }
            .firstOrNull { it.count(Char::isLetter) >= 2 }
}

/**
 * Pulls the shop name, bill total and item lines out of OCR text from a
 * printed Indian bill. Pure Kotlin so it can be unit-tested.
 */
object BillTextParser {

    // Checked in this order, so "Grand Total" wins over a plain "Total".
    private val totalLabels = listOf(
        "grand total", "net amount", "net payable", "amount payable", "net total",
        "total amount", "bill amount", "total payable", "மொத்தம்", "total",
    )

    // Lines that are never an item, even when they end in a number.
    private val nonItemWords = listOf(
        "total", "subtotal", "sub total", "tax", "gst", "cgst", "sgst", "igst", "vat", "cess",
        "discount", "round", "roundoff", "cash", "change", "balance", "paid", "tender", "upi",
        "card", "savings", "saved", "mrp", "invoice", "bill no", "date", "time", "phone", "mobile",
        "ph", "mob", "gstin", "fssai", "qty", "items", "rate", "amount", "amt", "tel", "mode",
        // GST invoice summary / footer rows: "Taxable Value 4,000.00" is not something bought.
        "taxable", "utgst", "terms", "declaration", "summary", "signature", "authorised", "authorized",
        "ifsc", "a/c", "e & o.e", "e.& o.e", "e. & o.e",
        "மொத்தம்",
    )

    // Lines that are never the shop name.
    private val nonNameWords = listOf(
        "invoice", "tax invoice", "cash memo", "bill", "receipt", "estimate", "gstin", "gst",
        "phone", "mobile", "ph", "mob", "tel", "date", "time", "no.", "address", "fssai",
        "welcome", "thank", "customer", "cashier", "counter", "original", "duplicate",
    )

    private val dateRegex = Regex("""\b\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}\b""")
    private val timeRegex = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")
    // Qty / rate / amount columns at the end of an item line. Stops at a
    // number with a unit ("5kg", "1L") so the pack size stays in the name.
    private val trailingNumbersRegex = Regex("""(?:[\s:x@*/=\-]*(?:₹|rs\.?|inr)?\s*\d[\d,]*(?:\.\d+)?)+[\s/\-]*$""", RegexOption.IGNORE_CASE)

    // Anything bigger is almost certainly a phone/GST/bill number, not money.
    private val maxPlausibleAmount = BigDecimal("10000000")

    fun parse(ocrText: String): ExtractedBill {
        val lines = cleanLines(ocrText)

        val (labelledTotal, totalLineIndex) = findLabelledTotal(lines)
        // "Rupees Thirty One Thousand ... Only": letters survive OCR far
        // better than digits, so the written-out total is the best check.
        val wordsTotal = AmountInWords.find(lines)
        val wordsMatchADigitAmount = wordsTotal != null &&
            lines.any { line -> amountsIn(line).any { it.compareTo(wordsTotal) == 0 } }
        // GST invoices: taxable value + CGST + SGST (or IGST) ± round off,
        // printed as an amount on the bill, is the grand total.
        val gstTotal = gstGrandTotal(lines)
        val total = when {
            wordsMatchADigitAmount -> wordsTotal
            gstTotal != null -> gstTotal
            labelledTotal != null -> labelledTotal
            wordsTotal != null -> wordsTotal
            else -> largestAmount(lines)
        }
        val items = findItems(lines, endExclusive = totalLineIndex ?: lines.size)
        val gst = gstBreakdown(lines)
        val gstins = GstinReader.findAll(lines)
        val read = ExtractedBill(
            merchantName = findMerchantName(lines),
            total = total,
            totalFromLabel = labelledTotal != null || wordsTotal != null || gstTotal != null,
            amountInWords = wordsTotal,
            items = items,
            date = DocumentDates.find(lines.joinToString("\n")),
            customerName = findCustomerName(lines),
            paid = findPaid(lines, total),
            invoiceNumber = findInvoiceNumber(lines),
            tax = gst.total,
            taxableValue = findTaxableValue(lines),
            gst = gst,
            roundOff = findRoundOff(lines),
            discount = findDiscount(lines),
            sellerGstin = gstins.firstOrNull { !it.buyer },
            buyerGstin = gstins.firstOrNull { it.buyer },
        )
        val check = BillValidation.check(read, lines)
        // A tax the checks found impossible (e.g. a dropped decimal point: "436.20" read as "43620")
        // is never shown as read — the owner is asked to check the bill instead.
        return read.copy(tax = read.tax?.takeUnless { check.has(BillIssue.TAX_IMPOSSIBLE) }, check = check)
    }

    /** The OCR text as lines: spaces tidied, Tamil letters slipped into English label words removed. */
    internal fun cleanLines(ocrText: String): List<String> = ocrText.lines()
        .map { repairMixedScript(it.replace('\t', ' ').replace(Regex("""\s+"""), " ").trim()) }
        .filter { it.isNotEmpty() }

    // Bill label words the eng+tam model garbles with a Tamil letter ("Taxaபble", "Invoிce", "Toடtal").
    private val labelWords = listOf(
        "total", "grand", "invoice", "taxable", "value", "amount", "cgst", "sgst", "igst", "utgst", "discount", "round",
        "terms", "conditions", "gstin", "date", "bill", "subtotal", "declaration", "authorised", "signatory", "rate", "qty",
    )
    private val tamilLetters = Regex("""[\u0B80-\u0BFF]+""")

    /**
     * A word mixing Latin letters and Tamil script is an OCR slip, never real
     * text: the Tamil letters are dropped, and when what is left is one letter
     * away from a bill label word ("Invoce" → "Invoice") the label is restored.
     * Pure-Tamil words (a Tamil shop name) are left alone.
     */
    internal fun repairMixedScript(line: String): String {
        if (!tamilLetters.containsMatchIn(line)) return line
        return line.split(' ').joinToString(" ") { word ->
            val latin = word.count { it in 'A'..'Z' || it in 'a'..'z' }
            if (latin < 2 || !tamilLetters.containsMatchIn(word)) return@joinToString word
            val stripped = word.replace(tamilLetters, "")
            val core = stripped.trimEnd(':', '.', ',', '-')
            val label = labelWords.firstOrNull { editDistance(core.lowercase(), it) <= 1 && it.length >= 4 }
            if (label == null) stripped else matchCase(core, label) + stripped.substring(core.length)
        }
    }

    private fun matchCase(like: String, word: String) = when {
        like.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        like.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    // ---- Invoice number / tax ----

    private val invoiceNo = Regex(
        """(?i)(?<![\p{L}])(?:tax\s*)?(?:invoice|inv|bill|receipt)\s*(?:no|number|num|#)\.?\s*[:\-#.]?\s*([A-Za-z0-9][A-Za-z0-9/\-]{0,19})""",
    )

    private fun findInvoiceNumber(lines: List<String>): String? = lines.firstNotNullOfOrNull { line ->
        invoiceNo.find(line)?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) }
    }

    private val ratePercent = Regex("""(\d{1,2}(?:[.,]\d{1,2})?)\s*%""")

    /**
     * CGST / SGST (UTGST) / IGST from every tax line on the bill — a bill with
     * items at 5 %, 12 % and 18 % prints one CGST and one SGST line per rate,
     * and all of them count. "@9%" is a rate, never an amount. When a line
     * without a rate repeats the sum of the rate lines (the footer total), it
     * is not counted twice; when it doesn't match, the breakdown is [GstBreakdown.suspect].
     */
    internal fun gstBreakdown(lines: List<String>): GstBreakdown {
        var suspect = false
        val rates = mutableListOf<BigDecimal>()
        fun component(vararg words: String): BigDecimal? {
            val rated = mutableListOf<BigDecimal>()
            val plain = mutableListOf<BigDecimal>()
            lines.forEachIndexed { i, line ->
                val key = labelKey(line)
                if (words.none { key.startsWith(it) } || isTaxSummaryRow(line)) return@forEachIndexed
                // The amount on the line, or alone on the next line ("CGST @ 9%" / "90.00").
                val amount = lastAmount(line)
                    ?: lines.getOrNull(i + 1)?.takeIf { labelKey(it).isEmpty() && !ratePercent.containsMatchIn(it) }?.let { lastAmount(it) }
                    ?: return@forEachIndexed
                val rate = ratePercent.find(line)?.groupValues?.get(1)?.replace(',', '.')?.toBigDecimalOrNull()
                if (rate != null) { rated += amount; rates += rate } else plain += amount
            }
            val ratedSum = rated.fold(BigDecimal.ZERO, BigDecimal::add)
            return when {
                rated.isEmpty() && plain.isEmpty() -> null
                plain.isEmpty() -> ratedSum
                rated.isEmpty() -> when {
                    plain.size == 1 -> plain.single()
                    // "CGST 12.50 / CGST 90.00 / CGST 102.50": the last line is the total of the others.
                    plain.size >= 3 && near(plain.last(), plain.dropLast(1).fold(BigDecimal.ZERO, BigDecimal::add)) -> plain.last()
                    else -> plain.fold(BigDecimal.ZERO, BigDecimal::add)
                }
                // Rate lines plus a footer line: the footer must be their sum.
                plain.any { near(it, ratedSum) } -> ratedSum
                else -> { suspect = true; ratedSum }
            }
        }
        val cgst = component("cgst")
        val sgst = component("sgst", "utgst")
        val igst = component("igst")
        val other = if (cgst == null && sgst == null && igst == null) {
            listOf("totaltax", "taxamount", "gstamount", "gst").firstNotNullOfOrNull { word ->
                lines.firstNotNullOfOrNull { line ->
                    val key = labelKey(line)
                    // "GSTIN: 33AB…" / "GST No" is an identity line, not a tax amount.
                    if (!key.startsWith(word) || key.startsWith("gstin") || key.startsWith("gstno") || isTaxSummaryRow(line)) null
                    else lastAmount(line)
                }
            }
        } else null
        // Only CGST or only SGST: half of the tax is missing.
        if ((cgst == null) != (sgst == null)) suspect = true
        return GstBreakdown(cgst, sgst, igst, other, rates.distinct(), suspect)
    }

    private fun near(a: BigDecimal, b: BigDecimal) = (a - b).abs() <= BigDecimal("0.05")

    /** "Taxable Value 10,000.00", "Taxable Amount", "Sub Total" — the value before tax. */
    private fun findTaxableValue(lines: List<String>): BigDecimal? =
        listOf("totaltaxablevalue", "taxablevalue", "taxableamount", "taxableamt", "taxable", "subtotal").firstNotNullOfOrNull { word ->
            lines.indices.reversed().firstNotNullOfOrNull { i ->
                val line = lines[i]
                if (!labelKey(line).startsWith(word) || isTaxSummaryRow(line)) null
                else lastAmount(line) ?: lines.getOrNull(i + 1)?.takeIf { labelKey(it).isEmpty() }?.let { lastAmount(it) }
            }
        }

    private fun findDiscount(lines: List<String>): BigDecimal? = lines.firstNotNullOfOrNull { line ->
        val key = labelKey(line)
        if (!(key.startsWith("discount") || key.startsWith("lessdiscount") || key.startsWith("less")) || !key.contains("discount")) null
        else lastAmount(line)
    }

    /** "Round Off (-) 0.40" → -0.40; "Round Off 0.50" → 0.50 (the sign as printed). */
    private fun findRoundOff(lines: List<String>): BigDecimal? = lines.firstNotNullOfOrNull { line ->
        if (!labelKey(line).startsWith("round")) return@firstNotNullOfOrNull null
        val amount = lastAmount(line, allowZero = true) ?: return@firstNotNullOfOrNull null
        if (Regex("""\(-\)|-\s*\d|less""", RegexOption.IGNORE_CASE).containsMatchIn(line)) amount.negate() else amount
    }

    // ---- Customer name ----

    private val customerLabel = Regex(
        // Whole label only: "Customer's Seal and Signature" is not a customer name.
        """^(?:customer\s*name|cust(?:omer)?\.?\s*name|party\s*name|bill(?:ed)?\s*to|customer|cust\.?|party|name|m/s\.?)(?![\p{L}'’])\s*[:\-.]?\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val notAName = listOf("mobile", "phone", "ph", "mob", "gst", "gstin", "address", "id", "no", "code", "copy")

    private fun findCustomerName(lines: List<String>): String? =
        lines.firstNotNullOfOrNull { line ->
            val value = customerLabel.find(line)?.groupValues?.get(1)?.trim() ?: return@firstNotNullOfOrNull null
            cleanCustomer(value)
        } ?: buyerOnNextLine(lines)

    /** A "Buyer / Bill to / Customer" heading is printed, so the bill names a buyer (read or not). */
    internal fun namesABuyer(lines: List<String>): Boolean =
        lines.any { buyerHeading.containsMatchIn(it) || customerLabel.find(it)?.groupValues?.get(0)?.lowercase()?.let { l -> !l.startsWith("name") } == true }

    // "Bill To:            Invoice No: CSH-2041": the right-hand column, with the name on the next line.
    private val sideColumnStart = Regex(
        """^(?:inv(?:oice)?\.?\s*(?:no|number|#|date)|bill\s*no|dated|date\s*[:\-]|dispatch|delivery\s*note|place\s*of\s*supply|state\s*(?:name|code)|gstin|e-?way)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun cleanCustomer(value: String): String? {
        val lower = value.lowercase()
        if (notAName.any { lower.startsWith(it) && (lower.length == it.length || !lower[it.length].isLetter()) }) return null
        // Never a name: another field from the next column.
        if (sideColumnStart.containsMatchIn(value.trim())) return null
        // Drop a phone number or other digits printed after the name.
        return value.replace(Regex("""[\d+()]{4,}.*$"""), "")
            .replace(sideColumnFields, "")
            // A date from the right-hand column ("… LIMITED 29-Oct-25").
            .replace(Regex("""\s+\d{1,2}[/\-.](?:\d{1,2}|[A-Za-z]{3,9})[/\-.]\d{2,4}.*$"""), "")
            .trim(' ', ',', '-', ':', '.')
            .takeIf { it.count(Char::isLetter) >= 2 }
    }

    // GST (Tally-style) invoices: "Buyer (Bill to)" / "Consignee (Ship to)" /
    // "Billed To" on its own line, the party's name on the line below.
    private val buyerHeading = Regex(
        """^(?:buyer|consignee|billed\s*to|bill\s*to|sold\s*to|customer)\b(?!')\s*(?:\((?:bill|ship)\s*to\))?\s*[:\-.]?\s*""",
        RegexOption.IGNORE_CASE,
    )

    // The right-hand header column OCR merges into the same line
    // ("SAMPATHI CREDITS PRIVATE LIMITED Dispatch Doc No.").
    private val sideColumnFields = Regex(
        """\s+(?:dispatch|delivery\s*note|inv(?:oice)?\.?\s*(?:no|number|#|date)|bill\s*no|dated|date\s*[:\-]|place\s*of\s*supply|buyer'?s\s*order|reference|other\s*references|mode\s*/?\s*terms|terms\s*of|destination|e-?way).*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun buyerOnNextLine(lines: List<String>): String? {
        val index = lines.indexOfFirst { line ->
            val m = buyerHeading.find(line) ?: return@indexOfFirst false
            // Nothing but the heading (or a merged right-column field) on this line.
            (" " + line.substring(m.range.last + 1)).replace(sideColumnFields, "").isBlank()
        }
        if (index < 0) return null
        // "Ravi Traders         Date: 12/09/2026": the right-hand column goes first.
        val next = lines.getOrNull(index + 1)?.replace(sideColumnFields, "")?.trim() ?: return null
        val lower = next.lowercase()
        if (looksLikeMetadata(lower) || lower.startsWith("gstin") || lower.startsWith("no.")) return null
        return cleanCustomer(next)?.takeIf { name -> name.count { it.isLetter() } >= 3 && !name.first().isDigit() }
    }

    // ---- Paid / balance ----

    private val explicitPaidWords = listOf("amount paid", "paid amount", "amount received", "received", "advance", "paid")
    private val paymentMethodWords = listOf("cash", "upi", "card", "gpay", "google pay", "phonepe", "paytm", "tendered", "tender", "neft", "online")
    private val balanceWords = listOf("balance due", "balance amount", "amount due", "due amount", "net due", "outstanding", "pending", "to pay", "balance", "credit")
    private val notBalanceWords = listOf("previous balance", "opening balance", "old balance", "credit card", "due date")

    /**
     * Paid part of the bill:
     *  - "Change" line, or Cash/UPI/Paid at least the total → fully paid
     *  - "Balance due" line → total − balance
     *  - "Paid"/"Advance"/Cash below the total → that amount
     * Null when none of these are printed.
     */
    // "Cash / Credit : Cash", "Payment Mode : UPI", "Mode of Payment: Credit".
    private val paymentModeField = Regex(
        """(?:cash\s*/\s*credit|payment\s*mode|mode\s*of\s*payment|pay\s*mode|mode)\s*[:\-]\s*([a-z]+)""",
        RegexOption.IGNORE_CASE,
    )

    private fun findPaid(lines: List<String>, total: BigDecimal?): BigDecimal? {
        val lowers = lines.map { it.lowercase() }
        // A payment amount is printed after its word ("Cash 600.00"); a number
        // earlier on the line (a bill number) doesn't belong to it.
        val explicitPaid = lines.indices.reversed().firstNotNullOfOrNull { i ->
            if (containsWord(lowers[i], "unpaid")) null else amountAfter(lines[i], explicitPaidWords)
        }
        val methodSum = lines.indices
            .filterNot { lowers[it].contains("cash memo") || lowers[it].contains("cash bill") }
            .mapNotNull { amountAfter(lines[it], paymentMethodWords) }
            .takeIf { it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO, BigDecimal::add)
        val hasChange = lines.any { amountAfter(it, listOf("change")) != null }
        val balance = lines.indices.reversed().firstNotNullOfOrNull { i ->
            if (notBalanceWords.any { lowers[i].contains(it) }) null
            else amountAfter(lines[i], balanceWords, allowZero = true)
        }
        // "Cash / Credit : Cash" — cash (or UPI/card) sale means paid in full.
        val mode = lines.firstNotNullOfOrNull { paymentModeField.find(it)?.groupValues?.get(1)?.lowercase() }

        val tendered = explicitPaid ?: methodSum
        if (total == null) return tendered
        return when {
            hasChange -> total
            tendered != null && tendered >= total -> total
            balance != null && balance.signum() == 0 -> total
            balance != null && balance < total -> total - balance
            balance != null && balance.compareTo(total) == 0 -> BigDecimal.ZERO.setScale(2)
            tendered != null -> tendered
            mode == "credit" -> BigDecimal.ZERO.setScale(2)
            mode != null && mode in paymentMethodWords -> total
            else -> null
        }
    }

    /** Amount written after the first of [words] on the line, if any. */
    private fun amountAfter(line: String, words: List<String>, allowZero: Boolean = false): BigDecimal? {
        val lower = line.lowercase()
        val end = words.mapNotNull { word ->
            Regex("""(^|[^\p{L}])${Regex.escape(word)}($|[^\p{L}])""").find(lower)?.let { it.range.first + it.value.trimEnd().length }
        }.minOrNull() ?: return null
        return lastAmount(line.substring(end.coerceAtMost(line.length)), allowZero = allowZero)
    }

    fun sum(items: List<BillLineItem>): BigDecimal =
        items.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount }

    /** "1,250.5" -> 1250.50, or null if it isn't a positive money amount. */
    fun parseAmount(text: String): BigDecimal? {
        val cleaned = text.replace(",", "").replace("₹", "").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toBigDecimalOrNull()
            ?.takeIf { it.signum() > 0 && it < maxPlausibleAmount }
            ?.setScale(2, RoundingMode.HALF_UP)
    }

    private fun findLabelledTotal(lines: List<String>): Pair<BigDecimal?, Int?> {
        val keys = lines.map { labelKey(it) }
        for (label in totalLabels) {
            val labelKey = labelKey(label)
            // Bottom-most match: the final total is printed last.
            for (index in lines.indices.reversed()) {
                if (!keys[index].contains(labelKey)) continue
                if (label == "total" && isNotTheBillTotal(keys[index])) continue
                if ((label == "total" || label == "total amount") && isTaxSummaryRow(lines[index])) continue
                // OCR often splits a table row: the amount can land on the next line or two.
                val amount = lastAmount(lines[index])
                    ?: lines.getOrNull(index + 1)?.takeIf { labelKey(it).isEmpty() }?.let { lastAmount(it) }
                    ?: lines.getOrNull(index + 2)?.takeIf { labelKey(it).isEmpty() }?.let { lastAmount(it) }
                if (amount != null) return amount to index
            }
        }
        return null to null
    }

    // ---- GST arithmetic ----

    /**
     * On a GST invoice the grand total is taxable value + CGST + SGST (or
     * + IGST), adjusted by the round-off. When the CGST/SGST/IGST lines are
     * printed, look for an amount A and another printed amount B with
     * A + taxes (± round off, or rounded to the rupee) = B: B is the grand
     * total. Null when the tax lines aren't found or nothing adds up.
     */
    private fun gstGrandTotal(lines: List<String>): BigDecimal? {
        fun taxLine(word: String): BigDecimal? = lines.firstNotNullOfOrNull { line ->
            val key = labelKey(line)
            if (!key.startsWith(word) || isTaxSummaryRow(line)) null else lastAmount(line)
        }
        val cgst = taxLine("cgst")
        val sgst = taxLine("sgst") ?: taxLine("utgst")
        val igst = taxLine("igst")
        val tax = when {
            cgst != null && sgst != null -> cgst + sgst
            igst != null -> igst
            else -> return null
        }
        val roundOff = lines.firstNotNullOfOrNull { line ->
            if (labelKey(line).startsWith("round")) lastAmount(line, allowZero = true) else null
        } ?: BigDecimal.ZERO.setScale(2)
        // Money printed with paise (GST invoices always do); cash tendered /
        // change / balance lines are payments, not bill amounts.
        val printed = lines.filterNot { line ->
            val lower = line.lowercase()
            (paymentMethodWords + explicitPaidWords + listOf("change", "balance")).any { containsWord(lower, it) }
        }.flatMap { line -> moneyWithPaise.findAll(line).mapNotNull { tokenAmount(it.value, allowZero = false) } }.toSet()
        val candidates = printed
            // The tax must be a real GST rate of the base (0.1 % … 40 %).
            .filter { base -> tax <= base * BigDecimal("0.40") && tax >= base * BigDecimal("0.001") }
            .flatMap { base ->
                val sum = base + tax
                listOf(sum, sum + roundOff, sum - roundOff, sum.setScale(0, RoundingMode.HALF_UP).setScale(2))
                    .filter { t -> printed.any { it.compareTo(t) == 0 } && t > base }
            }
        // The smallest: a sum built on another total ("total + tax") is always larger.
        return candidates.minOrNull()
    }

    /**
     * The "Total:" row of a GST tax summary table ("Total: 12,003.04
     * 1,072.47 1,072.47 2,144.94" — taxable value, CGST, SGST, total tax):
     * three or more money amounts with paise on one line. Its last amount is
     * the tax, not the bill total. (A till's "TOTAL 5 12 1,250.00" — items,
     * qty, amount — has only one such amount and is unaffected.)
     */
    private fun isTaxSummaryRow(line: String): Boolean =
        moneyWithPaise.findAll(line).count() >= 3

    // "9.00%" is a rate with two decimals, not money.
    private val moneyWithPaise = Regex("""(?<![\d.,])\d[\d,]*[.,]\d{2}(?![\d])(?!\s*%)""")

    // "Sub Total", "Total Qty", "Total Items", "Total Tax" are not the bill total.
    private fun isNotTheBillTotal(key: String): Boolean =
        listOf("subtotal", "totalqty", "totalquantity", "totalitem", "totaltax", "totalgst",
            "totaldiscount", "totalsavings", "totalpaid", "totaldue", "totalbalance").any { key.contains(it) }

    /**
     * Letters only, lower-cased, with common OCR slips inside words fixed
     * ("Tota1" → "total", "T0tal" → "total", "Grand TotaI" → "grandtotal").
     */
    private fun labelKey(line: String): String =
        line.lowercase()
            .replace(Regex("""(?<=\p{L})[1|!](?=\p{L}|\b|$)"""), "l")
            .replace(Regex("""(?<=\p{L})0(?=\p{L})"""), "o")
            .replace(Regex("""(?<=\p{L})0(?=\b|$)"""), "o")
            .replace(Regex("""[^\p{L}\p{M}]"""), "")

    // The bill total is the largest real amount — ignoring payment lines
    // (Cash 600 for a 599 bill) and phone/date lines.
    private fun largestAmount(lines: List<String>): BigDecimal? =
        lines.filterNot { line ->
            val lower = line.lowercase()
            looksLikeMetadata(lower) ||
                (paymentMethodWords + explicitPaidWords + listOf("change", "balance")).any { containsWord(lower, it) }
        }
            .mapNotNull { lastAmount(it) }
            .maxOrNull()

    private fun findItems(lines: List<String>, endExclusive: Int): List<BillLineItem> {
        val items = mutableListOf<BillLineItem>()
        for (index in 0 until endExclusive.coerceAtMost(lines.size)) {
            val line = lines[index]
            val lower = line.lowercase()
            if (looksLikeMetadata(lower)) continue
            if (nonItemWords.any { containsWord(lower, it) }) continue
            // An item's price is printed at the end of its line.
            val amount = lastAmount(line, mustEndLine = true) ?: continue
            val description = line.replace(trailingNumbersRegex, "")
                .trim(' ', '-', ':', '.', '|', '*')
            if (description.count { it.isLetter() } < 2) continue
            items += itemColumns(line, description, amount)
        }
        return items
    }

    // "Basmati Rice 1006 18 % 10 KG 85.00 850.00": HSN, GST rate, qty, unit, rate, amount.
    // The HSN code is followed by numbers (rate / qty), never by words ("Mixer 1000 Watt" is a name).
    private val hsnInDescription = Regex("""^(.*\p{L}.*?)\s+(\d{4}|\d{6}|\d{8})(?=\s+\d|$)(.*)$""")
    private val itemUnits = setOf("nos", "no", "pcs", "pc", "kg", "kgs", "g", "gm", "gms", "l", "ltr", "ml", "btl", "pkt", "box", "bag", "bags", "dozen", "doz", "set", "mtr", "m", "unit", "units", "each", "ea")

    /**
     * Splits a GST table row: the HSN/SAC code leaves the description, and the
     * quantity / unit / unit price are kept only when qty × price = the line
     * amount (within a paisa per unit). A leading serial number ("1 ", "2.")
     * is dropped when the row has an HSN code.
     */
    private fun itemColumns(line: String, description: String, amount: BigDecimal): BillLineItem {
        var desc = description
        var hsn: String? = null
        hsnInDescription.find(desc)?.let { m ->
            hsn = m.groupValues[2]
            desc = m.groupValues[1].replace(Regex("""^\d{1,3}[.)]?\s+(?=\p{L})"""), "").trim(' ', '-', ':', '.', '|', '*')
        }
        // Numbers after the description (after the HSN code), in order; rates ("18 %") are not quantities or prices.
        val start = hsn?.let { h -> line.indexOf(h).takeIf { it >= 0 }?.plus(h.length) }
            ?: (line.indexOf(description).coerceAtLeast(0) + description.length)
        val tail = line.substring(start)
        val tokens = numberToken.findAll(tail).filterNot { isPercent(tail, it) }.toList()
        var values = tokens.map { m -> m to tokenAmountOrZero(m.groupValues[1]) }
        // "Basmati Rice 1006 10 85.00 850.00": a bare 4/6/8-digit first column is the HSN code.
        if (hsn == null && values.size >= 4 && Regex("""\d{4}|\d{6}|\d{8}""").matches(values.first().first.groupValues[1])) {
            hsn = values.first().first.groupValues[1]
            values = values.drop(1)
        }
        var quantity: BigDecimal? = null
        var unitPrice: BigDecimal? = null
        var unit: String? = null
        val before = values.dropLast(1).filter { (m, v) -> v != null && m.groupValues[1] != hsn }
        loop@ for (i in before.indices) for (j in i + 1 until before.size) {
            val q = before[i].second!!
            val p = before[j].second!!
            if (q.signum() <= 0 || p.signum() <= 0) continue
            val tolerance = BigDecimal("0.01").max(q.multiply(BigDecimal("0.01")))
            if ((q.multiply(p) - amount).abs() <= tolerance) {
                quantity = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
                unitPrice = p
                unit = tail.substring(before[i].first.range.last + 1).trim().split(' ').firstOrNull()
                    ?.lowercase()?.trim('.', ',')?.takeIf { it in itemUnits }
                break@loop
            }
        }
        // "Cashew 0.5 kg 900.00 450.00": the quantity and unit are at the end of the description.
        if (quantity == null) {
            qtyAtEnd.find(desc)?.let { m ->
                val q = m.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return@let
                val prices = values.dropLast(1).mapNotNull { it.second }
                val p = prices.firstOrNull { p -> q.signum() > 0 && (q.multiply(p) - amount).abs() <= BigDecimal("0.01").max(q.multiply(BigDecimal("0.01"))) }
                    ?: return@let
                quantity = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
                unitPrice = p
                unit = m.groupValues[2].lowercase()
                desc = desc.substring(0, m.range.first).trim()
            }
        }
        return BillLineItem(desc, amount, hsn, quantity, unit, unitPrice)
    }

    // A quantity with a space before its unit ("0.5 kg"); "5kg" glued to the name is a pack size.
    private val qtyAtEnd = Regex("""\s(\d+(?:[.,]\d+)?)\s+(${itemUnitsPattern()})\.?$""", RegexOption.IGNORE_CASE)

    private fun itemUnitsPattern() = listOf("nos", "no", "pcs", "pc", "kgs", "kg", "gms", "gm", "g", "ltr", "ml", "l", "btl", "pkt", "box", "bags", "bag", "dozen", "doz", "set", "mtr", "m", "units", "unit", "each", "ea").joinToString("|")

    private fun tokenAmountOrZero(token: String): BigDecimal? {
        val plain = token.replace(",", "")
        return plain.toBigDecimalOrNull()?.takeIf { plain.count { it == '.' } <= 1 } ?: tokenAmount(token, allowZero = false)
    }

    // "SRI BALAJI  GSTIN : 33AB…" read as one line → keep "SRI BALAJI".
    private val headerNoise = Regex("""\s*\b(gstin|gst\s*no|ph|phone|mob|mobile|tel|fssai)\b.*$""", RegexOption.IGNORE_CASE)

    private fun findMerchantName(lines: List<String>): String? =
        lines.take(6).map { it.replace(headerNoise, "").replace(sideColumnFields, "").trim() }.firstOrNull { line ->
            val lower = line.lowercase()
            val letters = line.count { it.isLetter() }
            letters >= 3 &&
                letters >= line.length / 2 &&
                nonNameWords.none { containsWord(lower, it) } &&
                customerLabel.find(line) == null &&
                totalLabels.none { lower.contains(it) } &&
                !looksLikeMetadata(lower)
        }?.trim(' ', '-', ':', '.', '*', '|')

    private fun looksLikeMetadata(lower: String): Boolean =
        dateRegex.containsMatchIn(lower) ||
            timeRegex.containsMatchIn(lower) ||
            Regex("""\d{10}""").containsMatchIn(lower.replace(" ", "")) && !lower.contains('.')

    private fun lastAmount(line: String, mustEndLine: Boolean = false, allowZero: Boolean = false): BigDecimal? {
        // "CGST 90.00 @ 9%": a number followed by % is a rate, never money.
        val match = numberToken.findAll(line).lastOrNull { !isPercent(line, it) } ?: return null
        val rest = line.substring(match.range.last + 1)
        // A number glued to letters (e.g. "5kg", "B12") is not a price.
        if (rest.firstOrNull()?.isLetter() == true) return null
        // Allow only "/-" or similar after the price when it must end the line.
        if (mustEndLine && rest.any { it.isLetterOrDigit() }) return null
        return tokenAmount(match.groupValues[1], allowZero)
    }

    /** Every money amount on a line (used to confirm the amount in words). */
    private fun amountsIn(line: String): List<BigDecimal> =
        numberToken.findAll(line).filterNot { isPercent(line, it) }.mapNotNull { tokenAmount(it.groupValues[1], allowZero = false) }.toList()

    private fun isPercent(line: String, match: MatchResult): Boolean =
        line.substring(match.range.last + 1).trimStart().startsWith("%")

    // A run of digits with any "," / "." separators, e.g. 31,930.00 / 31.930,00.
    private val numberToken = Regex("""(?:₹|rs\.?|inr)?\s*(\d[\d.,]*\d|\d)""", RegexOption.IGNORE_CASE)

    /**
     * Reads one number token the way it is printed on Indian bills, also
     * when OCR swaps separators:
     *  31,930.00 · 1,24,000 · 31,930,00 → 31930.00 (last 2-digit group = paise,
     *  since Indian/Western grouping always ends in 3 digits) · 31.930.00.
     */
    private fun tokenAmount(token: String, allowZero: Boolean): BigDecimal? {
        val separators = token.indices.filter { token[it] == ',' || token[it] == '.' }
        val (digits, fraction, grouped) = if (separators.isEmpty()) {
            Triple(token, "", false)
        } else {
            val last = separators.last()
            val tail = token.substring(last + 1)
            val head = token.substring(0, last).replace(",", "").replace(".", "")
            when {
                tail.length <= 2 -> Triple(head, tail, separators.size > 1)
                tail.length == 3 && (separators.size > 1 || token[last] == ',') -> Triple(head + tail, "", true)
                else -> Triple(head, tail, false) // "1.250": a decimal, rounded to paise
            }
        }
        // 6+ plain digits with no paise or grouping is a pincode or an ID.
        if (fraction.isEmpty() && !grouped && digits.length >= 6) return null
        val text = if (fraction.isEmpty()) digits else "$digits.$fraction"
        // "Balance: 0.00" matters (it means fully paid), so allow zero there.
        if (allowZero && text.toBigDecimalOrNull()?.signum() == 0) return BigDecimal.ZERO.setScale(2)
        return parseAmount(text)
    }

    private fun containsWord(lower: String, word: String): Boolean =
        Regex("""(^|[^\p{L}])${Regex.escape(word)}($|[^\p{L}])""").containsMatchIn(lower)
}

/**
 * Writes bill line items into the transaction description (the server
 * stores one text field), one "item – ₹amount" per line. When the items
 * add up to less than the total, the gap is written as its own line so
 * every rupee of the total is accounted for.
 */
object BillNotesFormatter {
    fun format(
        items: List<BillLineItem>,
        total: BigDecimal,
        otherChargesLabel: String,
    ): String {
        val lines = items.map { "${it.description.trim()} – ${rupees(it.amount)}" }.toMutableList()
        val remainder = total - BillTextParser.sum(items)
        if (items.isNotEmpty() && remainder.signum() > 0) {
            lines += "$otherChargesLabel – ${rupees(remainder)}"
        }
        return lines.joinToString("\n")
    }

    fun rupees(amount: BigDecimal): String = "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
