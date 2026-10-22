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

    // The same 4-item GST bill made three ways (owner, 22 Oct 2026: "intha format mattum read pannuma, illa all format ah").
    private fun fourItems(file: String) {
        val bill = BillTextParser.parse(BillPdf.text(pdf(file))!!)
        assertEquals(file, "Kumar Traders", bill.merchantName)
        assertEquals(file, 0, BigDecimal("22323.00").compareTo(bill.total))
        assertEquals(file, "KT-2041", bill.invoiceNumber)
        // Every item, its name whole (a name wrapped in its cell put back together), without the S.No column in front.
        assertEquals(file, listOf("Ponni Rice 25kg Bag", "Sugar 1kg", "Sunflower Oil 1L", "துவரம் பருப்பு 1kg"), bill.items.map { it.description })
        assertEquals(file, listOf(1, 2, 3, 4), bill.items.map { it.rowNo })
        assertEquals(file, 0, BigDecimal("21260.00").compareTo(BillTextParser.sum(bill.items)))
    }

    @Test fun aBrowserPrintedBillIsRead() = fourItems("browser-gst-bill.pdf")

    // Word / LibreOffice: "Ponni Rice 25kg" above the row's numbers and "Bag" below them; the Tamil "ப்" drawn as joined glyphs.
    @Test fun aWordProcessorBillIsRead() = fourItems("libreoffice-gst-bill.pdf")

    @Test fun aNumberThatIsTheProductsNameStays() {
        val bill = BillTextParser.parse("Ravi Stores\nItem Qty Rate Amount\n7 Up 500ml 2 40.00 80.00\n5 Star Chocolate 3 10.00 30.00\nTotal 110.00")
        assertEquals(listOf("7 Up 500ml", "5 Star Chocolate"), bill.items.map { it.description })
    }

    /** A bill PDF its app saved compressed (PDF 1.5 object streams: Word's "minimum size", iText): the page and font are packed. */
    @Test fun aPdfWithObjectStreamsIsRead() {
        val content = "BT /F1 11 Tf 40 800 Td (Anbu Agencies) Tj 0 -20 Td (Item Qty Rate Amount) Tj 0 -16 Td (Ponni Rice 25kg 2 1,250.00 2,500.00) Tj " +
            "0 -16 Td (Toor Dal 1kg 5 160.00 800.00) Tj 0 -20 Td (Grand Total 3,300.00) Tj ET"
        fun deflate(b: ByteArray): ByteArray {
            val d = java.util.zip.Deflater(); d.setInput(b); d.finish()
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        }
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        )
        var off = 0
        val header = StringBuilder(); val body = StringBuilder()
        objs.forEachIndexed { k, o -> header.append("${listOf(1, 2, 3, 4)[k]} $off "); body.append(o).append('\n'); off += o.length + 1 }
        val packed = deflate((header.toString() + body).toByteArray(Charsets.ISO_8859_1))
        val stream = deflate(content.toByteArray(Charsets.ISO_8859_1))
        val out = java.io.ByteArrayOutputStream()
        fun w(t: String) = out.write(t.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.5\n")
        w("5 0 obj << /Length ${stream.size} /Filter /FlateDecode >> stream\n"); out.write(stream); w("\nendstream endobj\n")
        w("6 0 obj << /Type /ObjStm /N 4 /First ${header.length} /Length ${packed.size} /Filter /FlateDecode >> stream\n"); out.write(packed); w("\nendstream endobj\n")
        w("%%EOF")
        val bill = BillTextParser.parse(BillPdf.text(out.toByteArray())!!)
        assertEquals("Anbu Agencies", bill.merchantName)
        assertEquals(0, BigDecimal("3300.00").compareTo(bill.total))
        assertEquals(listOf("Ponni Rice 25kg", "Toor Dal 1kg"), bill.items.map { it.description })
    }
}
