package com.shopai.app.util

import com.shopai.app.books.integration.PdfText
import kotlin.math.abs

/**
 * A bill PDF's own text (owner, 22 Oct 2026: a 125-item PDF "romba neram load aguthu"). A PDF made by a billing app carries its
 * words: read straight from the file — every page, in milliseconds, nothing guessed — instead of drawing each page and reading it
 * back with OCR. A scanned PDF (only pictures) has no text: null, and the pages go to OCR as before.
 */
object BillPdf {

    /**
     * The file's text lines, top to bottom, page by page — null when it carries none worth reading: a scan, or text that reads
     * as no bill (no item, no total — a font whose letters the file doesn't map); those pages go to OCR.
     */
    fun text(pdf: ByteArray): String? {
        val lines = runCatching { joinWrapped(PdfText.lines(pdf)) }.getOrNull().orEmpty()
        val text = lines.joinToString("\n") { it.text }.trim()
        if (text.count { it.isLetter() } < MIN_LETTERS) return null
        val bill = runCatching { BillTextParser.parse(text) }.getOrNull() ?: return null
        return text.takeIf { bill.items.isNotEmpty() || bill.totalFromLabel }
    }

    // A real bill has a name, its items and a total: fewer letters than this is a scan with a stray stamp or page number.
    private const val MIN_LETTERS = 20

    /**
     * An item's name that wraps inside its cell (a Word / LibreOffice bill: "Ponni Rice 25kg" above the row's numbers, "Bag"
     * below them) is put back into its row: a line of words only, a line or two above or below an item row, whose words sit in
     * that row's gap or over its name — never over its numbers (a table header's "S.No / Qty / Rate" sit over numbers).
     */
    internal fun joinWrapped(lines: List<PdfText.Line>): List<PdfText.Line> {
        fun end(c: PdfText.Run) = if (c.endX > c.x) c.endX else c.x + c.size * 0.55f * c.text.length
        fun overlaps(a: PdfText.Run, b: PdfText.Run) = a.x < end(b) && b.x < end(a)
        fun words(c: PdfText.Run) = c.text.count { it.isLetter() } >= 2
        // (a row whose whole name wrapped has only its numbers left on its own line: "1 | 1006 10 | 1,250.00 12,500.00")
        fun itemRow(l: PdfText.Line) = l.cells.size >= 2 && money.containsMatchIn(l.text) && !notItem.containsMatchIn(l.text)
        fun piece(l: PdfText.Line) = !money.containsMatchIn(l.text) && l.cells.any(::words) && !notItem.containsMatchIn(l.text) && !l.text.contains(':')
        fun fits(frag: PdfText.Line, row: PdfText.Line) = frag.cells.all { c -> row.cells.none { r -> overlaps(c, r) && !words(r) } }

        val owner = arrayOfNulls<Int>(lines.size)
        for (i in lines.indices) {
            val l = lines[i]
            if (!piece(l)) continue
            val size = l.cells.maxOf { it.size }
            var best: Int? = null
            for (dir in listOf(-1, 1)) {
                var j = i + dir
                var steps = 1
                // (over the other wrapped lines of the same name, up to two)
                while (j in lines.indices && steps <= 3 && lines[j].page == l.page && abs(lines[j].y - l.y) <= size * 1.6f * steps) {
                    if (itemRow(lines[j])) {
                        if (fits(l, lines[j]) && (best == null || abs(lines[j].y - l.y) < abs(lines[best].y - l.y))) best = j
                        break
                    }
                    if (!piece(lines[j])) break
                    j += dir; steps++
                }
            }
            owner[i] = best
        }
        if (owner.all { it == null }) return lines
        return lines.indices.mapNotNull { i ->
            if (owner[i] != null) return@mapNotNull null
            val pieces = lines.indices.filter { owner[it] == i }
            if (pieces.isEmpty()) return@mapNotNull lines[i]
            val cells = lines[i].cells.toMutableList()
            // Nearest first: the lines above go in front of the name, the lines below after it.
            for (k in pieces.sortedBy { abs(lines[it].y - lines[i].y) }) {
                val above = lines[k].y < lines[i].y
                for (c in lines[k].cells) {
                    val at = cells.indexOfFirst { overlaps(c, it) }
                    if (at < 0) { cells += c; continue }
                    val t = cells[at]
                    cells[at] = t.copy(x = minOf(t.x, c.x), endX = maxOf(end(t), end(c)), text = if (above) c.text + " " + t.text else t.text + " " + c.text)
                }
            }
            lines[i].copy(cells = cells.sortedBy { it.x })
        }
    }

    private val money = Regex("""\d[\d,]*\.\d{2}(?!\d)""")
    private val notItem = Regex("""(?i)(?<![\p{L}])(total|rupees|amount in words|only|gstin|page)(?![\p{L}])""")
}
