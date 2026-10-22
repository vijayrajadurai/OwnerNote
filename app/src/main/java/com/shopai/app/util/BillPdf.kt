package com.shopai.app.util

import com.shopai.app.books.integration.PdfText

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
        val text = runCatching { PdfText.lines(pdf) }.getOrNull().orEmpty().joinToString("\n") { it.text }.trim()
        if (text.count { it.isLetter() } < MIN_LETTERS) return null
        val bill = runCatching { BillTextParser.parse(text) }.getOrNull() ?: return null
        return text.takeIf { bill.items.isNotEmpty() || bill.totalFromLabel }
    }

    // A real bill has a name, its items and a total: fewer letters than this is a scan with a stray stamp or page number.
    private const val MIN_LETTERS = 20
}
