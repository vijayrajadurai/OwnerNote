package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Owner, 22 Oct 2026: the 125-item purchase PDF took long to load and showed only some items — read from its own text, all pages. */
class BillPdfTest {
    private fun pdf(name: String) = javaClass.getResourceAsStream("/bills/$name")!!.readBytes()

    @Test fun aPdfMadeByAnAppIsReadFromItsTextEveryPage() {
        val started = System.nanoTime()
        val text = BillPdf.text(pdf("vendor-invoice-125-items.pdf"))!!
        val bill = BillTextParser.parse(text)
        val ms = (System.nanoTime() - started) / 1_000_000
        // Three pages, the header printed again on each: all 125 rows, numbered 1 to 125.
        assertEquals(125, bill.items.size)
        assertEquals((1..125).toList(), bill.items.map { it.rowNo })
        assertEquals(0, BigDecimal("1168423.00").compareTo(BillTextParser.sum(bill.items)))
        assertEquals(0, BigDecimal("1247365.00").compareTo(bill.total))
        assertTrue(bill.totalFromLabel)
        // The unit printed as a word ("piece", "packet", "pack", "tray") is read with the quantity.
        val soap = bill.items.first()
        assertEquals("Bath Soap Bar 100g", soap.description)
        assertEquals("piece", soap.unit)
        assertEquals(0, BigDecimal("240").compareTo(soap.quantity))
        assertEquals("pack", bill.items.first { it.description.startsWith("Paneer") }.unit)
        // Not the title's "(VENDOR)": the seller's name starts the party.
        assertTrue(bill.merchantName.orEmpty(), bill.merchantName.orEmpty().startsWith("Sri Lakshmi Wholesale Traders"))
        // No "Page 1" footer read as an item at ₹1.
        assertTrue(bill.items.none { it.description.contains("Page", ignoreCase = true) })
        assertTrue("read in $ms ms", ms < 3_000)
    }

    @Test fun aScanHasNoTextAndGoesToOcr() {
        assertNull(BillPdf.text("%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n%%EOF".toByteArray()))
        assertNull(BillPdf.text(ByteArray(0)))
        // Letters that read as no bill (no item, no total): OCR reads the pages instead.
        val noBill = "%PDF-1.4\n1 0 obj << /Type /Pages /Kids [2 0 R] >> endobj\n2 0 obj << /Type /Page /Parent 1 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents 4 0 R >> endobj\n" +
            "3 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n4 0 obj << >> stream\nBT /F1 12 Tf 50 800 Td (Qwerty uiop asdfgh jklzx cvbnm qwerty) Tj ET\nendstream endobj\n%%EOF"
        assertTrue(com.shopai.app.books.integration.PdfText.lines(noBill.toByteArray(Charsets.ISO_8859_1)).isNotEmpty())
        assertNull(BillPdf.text(noBill.toByteArray(Charsets.ISO_8859_1)))
    }
}
