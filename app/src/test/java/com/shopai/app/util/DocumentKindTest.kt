package com.shopai.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which reader suits a photo: printed GST bill vs handwriting. */
class DocumentKindTest {
    private val printedGstBill = """
        SRI MURUGAN TRADERS
        GSTIN: 33ABCDE1234F1Z5
        TAX INVOICE
        Invoice No: 1172   Date: 02/10/2026
        Item        HSN   Qty  Rate  Amount
        Rice 25kg   1006  1    1250  1250.00
        Taxable Value 1250.00
        CGST 2.5% 31.25  SGST 2.5% 31.25
        Grand Total 1312.50
        Rupees One Thousand Three Hundred Twelve Only
        E & O.E
    """.trimIndent()

    @Test
    fun printedGstBillIsClearlyPrinted() {
        assertTrue(DocumentKind.isClearlyPrinted(printedGstBill))
        assertFalse(DocumentKind.looksHandwritten(printedGstBill, tesseractConfidence = 40, foundLabelledTotal = false))
    }

    @Test
    fun billBookWithPrintedHeaderStaysHandwritten() {
        // Printed GSTIN + "Invoice No" on a bill book, the rest written by hand.
        val text = "GSTIN 33ABCDE1234F1Z5\nInvoice No 45\nRice 5 kg 300\nDal 2 kg 240\nTotal 540"
        assertFalse(DocumentKind.isClearlyPrinted(text))
    }

    @Test
    fun notebookPageIsNotPrinted() {
        assertFalse(DocumentKind.isClearlyPrinted("Ravi 500\nKumar 1200 கொடுத்தேன்\nSelvi 300"))
    }

    @Test
    fun unsureTesseractWithoutTotalLooksHandwritten() {
        val garbled = "Rv1 5o0 ~ Kmr 12oo\nSlv 3OO ."
        assertTrue(DocumentKind.looksHandwritten(garbled, tesseractConfidence = 32, foundLabelledTotal = false))
        // Tesseract confident: a printed bill that simply has no Total line.
        assertFalse(DocumentKind.looksHandwritten("Murugan Stores\nRice 1 kg 60.00\nDal 1 kg 120.00", tesseractConfidence = 84, foundLabelledTotal = false))
        // A Total line was read: it is a usable shop bill.
        assertFalse(DocumentKind.looksHandwritten(garbled, tesseractConfidence = 32, foundLabelledTotal = true))
        // Almost nothing read at all.
        assertTrue(DocumentKind.looksHandwritten("12 4", tesseractConfidence = -1, foundLabelledTotal = false))
    }
}
