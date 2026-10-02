package com.shopai.app.util

import java.math.BigDecimal
import java.math.RoundingMode

/** One itemised line of a bill: what it was and what it cost. */
data class BillLineItem(val description: String, val amount: BigDecimal)

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
        val lines = ocrText.lines()
            .map { it.replace('\t', ' ').replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }

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
        return ExtractedBill(
            merchantName = findMerchantName(lines),
            total = total,
            totalFromLabel = labelledTotal != null || wordsTotal != null || gstTotal != null,
            amountInWords = wordsTotal,
            items = items,
            date = DocumentDates.find(lines.joinToString("\n")),
            customerName = findCustomerName(lines),
            paid = findPaid(lines, total),
        )
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

    private fun cleanCustomer(value: String): String? {
        val lower = value.lowercase()
        if (notAName.any { lower.startsWith(it) && (lower.length == it.length || !lower[it.length].isLetter()) }) return null
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
        """\s+(?:dispatch|delivery\s*note|invoice\s*no|dated|buyer'?s\s*order|reference|other\s*references|mode\s*/?\s*terms|terms\s*of|destination|e-?way).*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun buyerOnNextLine(lines: List<String>): String? {
        val index = lines.indexOfFirst { line ->
            val m = buyerHeading.find(line) ?: return@indexOfFirst false
            // Nothing but the heading (or a merged right-column field) on this line.
            (" " + line.substring(m.range.last + 1)).replace(sideColumnFields, "").isBlank()
        }
        if (index < 0) return null
        val next = lines.getOrNull(index + 1) ?: return null
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

    private val moneyWithPaise = Regex("""(?<![\d.,])\d[\d,]*[.,]\d{2}(?![\d])""")

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
            items += BillLineItem(description, amount)
        }
        return items
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
        val match = numberToken.findAll(line).lastOrNull() ?: return null
        val rest = line.substring(match.range.last + 1)
        // A number glued to letters (e.g. "5kg", "B12") is not a price.
        if (rest.firstOrNull()?.isLetter() == true) return null
        // Allow only "/-" or similar after the price when it must end the line.
        if (mustEndLine && rest.any { it.isLetterOrDigit() }) return null
        return tokenAmount(match.groupValues[1], allowZero)
    }

    /** Every money amount on a line (used to confirm the amount in words). */
    private fun amountsIn(line: String): List<BigDecimal> =
        numberToken.findAll(line).mapNotNull { tokenAmount(it.groupValues[1], allowZero = false) }.toList()

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
