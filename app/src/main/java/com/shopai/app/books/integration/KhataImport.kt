package com.shopai.app.books.integration

import com.shopai.app.books.model.PartyKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bringing a shop's khata from Khatabook (owner, 19 Oct 2026: "pudhu user maara idhu romba mukkiyam"): the "Customer List Report"
 * / "Supplier List Report" PDF Khatabook exports — each person's name, phone, what you'll get / give and the collection date — read,
 * shown to the owner, and written only on Confirm: the person, and their balance as an opening balance with the collection date as
 * its due date, through the books' own [com.shopai.app.books.engine.BooksEngine.postPartyOpening]. Nothing else in the books
 * changes; a person already in the books is left as they are (never a second balance), and bringing the same file again adds
 * nothing twice.
 */
object KhataImport {

    /** One person on the report: what you'll get from them / give them (paise), and the collection date when there is one. */
    data class Row(val name: String, val phone: String?, val getPaise: Long, val givePaise: Long, val collectionDate: LocalDate?)

    /**
     * The report read: [kind] (null when the report doesn't say customers or suppliers), the shop's name on it, the people, and
     * whether their amounts add up to the report's own Grand Total ([checked]).
     */
    data class Sheet(val kind: PartyKind?, val shop: String?, val rows: List<Row>, val totalGetPaise: Long?, val totalGivePaise: Long?) {
        val checked: Boolean get() = totalGetPaise != null && totalGivePaise != null &&
            rows.sumOf { it.getPaise } == totalGetPaise && rows.sumOf { it.givePaise } == totalGivePaise
    }

    private val amount = Regex("""^₹?\s*(\d{1,3}(?:,\d{2,3})*|\d+)(?:\.(\d{1,2}))?$""")
    private val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    private fun paise(text: String): Long? {
        val m = amount.find(text.trim()) ?: return null
        val rupees = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        val p = m.groupValues[2].padEnd(2, '0').take(2).ifEmpty { "00" }.toLong()
        return rupees * 100 + p
    }

    private fun phoneOf(text: String): String? = text.filter(Char::isDigit).takeLast(10).takeIf { it.length == 10 }

    /** Khatabook's report from its lines (see [PdfText.lines]) — null when it isn't one (no Name / Details / You'll Get header). */
    fun parse(lines: List<PdfText.Line>): Sheet? {
        val headerAt = lines.indexOfFirst { l -> l.cells.any { it.text.equals("Name", true) } && l.cells.any { it.text.contains("Get", true) } && l.cells.any { it.text.contains("Give", true) } }
        if (headerAt < 0) return null
        val header = lines[headerAt]
        fun col(pred: (String) -> Boolean) = header.cells.firstOrNull { pred(it.text) }?.x
        val xName = col { it.equals("Name", true) } ?: return null
        val xDetails = col { it.contains("Detail", true) }
        val xGet = col { it.contains("Get", true) } ?: return null
        val xGive = col { it.contains("Give", true) } ?: return null
        val xDate = col { it.contains("Date", true) }
        // A cell's column: the header it sits under (amounts are right-aligned under their header, so a little to the left of it counts).
        val starts = listOfNotNull("name" to xName, xDetails?.let { "details" to it }, "get" to xGet, "give" to xGive, xDate?.let { "date" to it }).sortedBy { it.second }
        fun columnOf(x: Float): String = starts.lastOrNull { (_, sx) -> x >= sx - 12f }?.first ?: "name"

        val endAt = lines.indexOfFirst { l -> l.y > header.y && l.cells.firstOrNull()?.text?.startsWith("Grand Total", true) == true }.takeIf { it > headerAt } ?: lines.size
        val body = lines.subList(headerAt + 1, endAt)
        // Each person's line has an amount under You'll Get or You'll Give; their name may wrap onto the lines just above and below.
        val anchors = body.filter { l -> l.cells.any { c -> columnOf(c.x) in setOf("get", "give") && paise(c.text) != null } }
        if (anchors.isEmpty()) return Sheet(kindOf(lines), lines.firstOrNull()?.text, emptyList(), null, null)
        val rows = anchors.mapIndexed { i, a ->
            val prev = anchors.getOrNull(i - 1)?.takeIf { it.page == a.page }
            val next = anchors.getOrNull(i + 1)?.takeIf { it.page == a.page }
            val top = prev?.let { (it.y + a.y) / 2 } ?: Float.NEGATIVE_INFINITY
            val bottom = next?.let { (a.y + it.y) / 2 } ?: Float.POSITIVE_INFINITY
            val band = body.filter { l -> l.page == a.page && l.y > top && l.y <= bottom || l === a }.sortedBy { it.y }
            val cells = band.flatMap { l -> l.cells.map { c -> columnOf(c.x) to c.text } }
            val name = cells.filter { it.first == "name" }.joinToString(" ") { it.second }.replace(Regex("""\s+"""), " ").trim()
            val phone = cells.filter { it.first == "details" }.firstNotNullOfOrNull { phoneOf(it.second) }
            val get = a.cells.firstOrNull { columnOf(it.x) == "get" }?.text?.let(::paise) ?: 0L
            val give = a.cells.firstOrNull { columnOf(it.x) == "give" }?.text?.let(::paise) ?: 0L
            val date = cells.filter { it.first == "date" }.firstNotNullOfOrNull { runCatching { LocalDate.parse(it.second.trim(), dateFmt) }.getOrNull() }
            Row(name.ifEmpty { phone ?: "?" }, phone, get, give, date)
        }
        val grand = lines.getOrNull(endAt)?.cells.orEmpty()
        val totalGet = grand.firstOrNull { columnOf(it.x) == "get" }?.text?.let(::paise)
        val totalGive = grand.firstOrNull { columnOf(it.x) == "give" }?.text?.let(::paise)
        return Sheet(kindOf(lines), lines.firstOrNull()?.text, rows, totalGet, totalGive)
    }

    private fun kindOf(lines: List<PdfText.Line>): PartyKind? {
        val all = lines.take(8).joinToString(" ") { it.text }.lowercase(Locale.ROOT)
        return when {
            "supplier list" in all || "suppliers" in all -> PartyKind.SUPPLIER
            "customer list" in all || "customers" in all -> PartyKind.CUSTOMER
            else -> null
        }
    }

    /** The PDF's bytes → the report, or null when the file is not a Khatabook list (or carries no text). */
    fun read(pdf: ByteArray): Sheet? = runCatching { parse(PdfText.lines(pdf)) }.getOrNull()

}
