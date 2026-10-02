package com.shopai.app.util

import com.shopai.app.ui.screens.BillEntryState
import com.shopai.app.ui.screens.BillScanWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The Sri Balaji Hardwares test bill: Grand Total ₹31,930.00, Sub Total
 * 30,535.00, amount in words "Thirty One Thousand Nine Hundred And Thirty".
 * These inputs mimic how on-device OCR reads a photographed bill.
 */
class BillTotalOcrTest {
    private val expected = BigDecimal("31930.00")

    // Rows read left to right, as OCR does on a clean photo.
    private val cleanRead = """
        SRI BALAJI GSTIN : 33ABCDE1234F1Z5
        HARDWARES & BUILDING MATERIALS
        CEMENT | STEEL | ELECTRICAL | PLUMBING | PAINTS | TOOLS
        No. 12, Velachery Main Road, Velachery, Chennai - 600 042.
        Ph: 044 - 4212 3366 | 98400 12345
        TAX INVOICE
        Bill No : SBH/24-25/0786 Cash / Credit : Cash
        Date : 26-09-2026 Salesman : Rajesh
        Time : 11:32 AM Customer : Walk-in Customer
        S.No Item Description HSN Qty Unit Rate Amount
        1 Ultratech Cement (50 Kg) 2523 20 Bags 420.00 8,400.00
        2 TMT Steel Rod 12mm (10ft) 7214 10 Pcs 730.00 7,300.00
        5 Finolex Electrical Wire (1.5 sqmm) 8544 3 Rolls 1,250.00 3,750.00
        7 Asian Paints Royale (20 Ltr) 3208 2 Buckets 3,250.00 6,500.00
        Amount in Words : Sub Total 30,535.00
        Rupees Thirty One Thousand Nine Hundred CGST @ 9% 2,747.15
        And Thirty Only SGST @ 9% 2,747.15
        Grand Total ₹ 31,930.00
    """.trimIndent()

    @Test
    fun cleanReadGivesGrandTotal() {
        val bill = BillTextParser.parse(cleanRead)
        assertEquals(expected, bill.total)
        assertTrue(bill.totalFromLabel)
        assertEquals("SRI BALAJI", bill.merchantName)
        assertEquals(java.time.LocalDate.of(2026, 9, 26), bill.date)
        // "Cash / Credit : Cash" on this bill → paid in full.
        assertEquals(expected, bill.paid)
    }

    // OCR often reads a table column by column, so "Grand Total" and its
    // amount end up far apart and the words wrap across lines.
    private val jumbledRead = """
        SRI BALAJI
        HARDWARES & BUILDING MATERIALS
        Amount in Words :
        Rupees Thirty One Thousand Nine Hundred
        And Thirty Only
        Sub Total
        CGST @ 9%
        SGST @ 9%
        Grand Total
        Edit
        30,535.00
        2,747.15
        2,747.15
        31,930.00
    """.trimIndent()

    @Test
    fun jumbledTableStillGivesGrandTotal() {
        assertEquals(expected, BillTextParser.parse(jumbledRead).total)
    }

    @Test
    fun ocrLetterSlipsInTheLabel() {
        assertEquals(expected, BillTextParser.parse("SHOP\nSub Total 30,535.00\nGrand Tota1 % 31,930.00").total)
        assertEquals(expected, BillTextParser.parse("SHOP\nSub Total 30,535.00\nGrand T0taI 31,930.00").total)
        assertEquals(expected, BillTextParser.parse("SHOP\nSub Total 30,535.00\nGrandTotal 31,930.00").total)
    }

    @Test
    fun ocrSeparatorSlipsInTheAmount() {
        // Decimal point read as a comma, or commas read as points.
        assertEquals(expected, BillTextParser.parse("SHOP\nGrand Total 31,930,00").total)
        assertEquals(expected, BillTextParser.parse("SHOP\nGrand Total 31.930.00").total)
        // Indian lakh grouping still works.
        assertEquals(BigDecimal("124000.00"), BillTextParser.parse("SHOP\nGrand Total 1,24,000").total)
        assertEquals(BigDecimal("1250000.00"), BillTextParser.parse("SHOP\nGrand Total 12,50,000.00").total)
    }

    @Test
    fun wordsCorrectAMisreadDigitTotal() {
        // ₹ glued to the number and read as a digit: "231,930.00".
        val text = "SHOP\nRupees Thirty One Thousand Nine Hundred And Thirty Only\nSub Total 30,535.00\n31,930.00\nGrand Total 231,930.00"
        assertEquals(expected, BillTextParser.parse(text).total)
    }

    @Test
    fun wordsUsedWhenNoDigitTotalIsReadable() {
        val text = "SHOP\nAmount in Words: Rupees Thirty One Thousand Nine Hundred And Thirty Only\nGrand Total"
        assertEquals(expected, BillTextParser.parse(text).total)
    }

    @Test
    fun cashTenderedIsNeverTheGuessedTotal() {
        // No total label: the largest amount must ignore "Cash 600".
        assertEquals(BigDecimal("599.00"), BillTextParser.parse("SHOP\nRice 599.00\nCash 600.00\nChange 1.00").total)
    }

    @Test
    fun paymentModeFieldDecidesPaidWhenNoAmountsAreShown() {
        assertEquals(BigDecimal("0.00"), BillTextParser.parse("SHOP\nBill No : 0786 Cash / Credit : Credit\nGrand Total 31,930.00").paid)
        assertEquals(expected, BillTextParser.parse("SHOP\nPayment Mode: UPI\nGrand Total 31,930.00").paid)
        // The bill number before "Cash" is not a payment amount.
        assertEquals(expected, BillTextParser.parse("SHOP\nBill No : SBH/24-25/0786 Cash / Credit : Cash\nGrand Total 31,930.00").paid)
    }

    @Test
    fun amountInWordsVariants() {
        assertEquals(expected, AmountInWords.parse("Thirty One Thousand Nine Hundred And Thirty"))
        assertEquals(BigDecimal("120000.50"), AmountInWords.parse("One Lakh Twenty Thousand and Fifty Paise"))
        assertEquals(BigDecimal("25000000.00"), AmountInWords.parse("Two Crore Fifty Lakh"))
        assertEquals(BigDecimal("599.00"), AmountInWords.parse("Five Hundred Ninety Nine"))
        // One-letter OCR slips.
        assertEquals(expected, AmountInWords.parse("Thirly One Thousand Nine Hundrcd And Thirty"))
        assertNull(AmountInWords.parse("Walk in Customer"))
    }

    @Test
    fun formPrefersThePhoneOverTheServerGuess() {
        val state = BillEntryState()
        // No "Total" line read; server guessed an item amount.
        state.applyScan(BillTextParser.parse("SHOP\nCement 8,400.00\n31,930.00"), serverName = null, serverAmount = 8400.0)
        assertEquals("31930.00", state.total)
        assertTrue(BillScanWarning.TOTAL_GUESSED in state.warnings)
    }

    @Test
    fun formOffersTheAmountInWordsWhenItDiffers() {
        val state = BillEntryState()
        state.applyScan(
            BillTextParser.parse("SHOP\nRupees Thirty One Thousand Nine Hundred And Thirty Only\nGrand Total 38,930.00"),
            serverName = null,
            serverAmount = null,
        )
        assertEquals("38930.00", state.total)
        assertEquals(expected, state.totalInWords)
        state.useTotalInWords()
        assertEquals("31930.00", state.total)
        assertNull(state.totalInWords)
        assertFalse(BillScanWarning.TOTAL_GUESSED in state.warnings)
    }
}
