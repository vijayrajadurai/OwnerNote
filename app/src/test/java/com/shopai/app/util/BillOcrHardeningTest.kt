package com.shopai.app.util

import com.shopai.app.ui.screens.BillEntryState
import com.shopai.app.ui.screens.BillField
import com.shopai.app.ui.screens.BillScanWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * GST bill OCR hardening: multi-rate GST, rates never read as money, decimal
 * slips (TEST-024), text loss with high confidence (TEST-025), GSTIN check
 * digits, buyer/side-column merges, GST table rows, financial reconciliation
 * and the review gate — what was read is never trusted beyond what adds up.
 */
class BillOcrHardeningTest {
    private fun r(v: String) = BigDecimal(v).setScale(2)
    private fun parse(text: String) = BillTextParser.parse(text.trimIndent())

    // Valid check digits (verified with the GSTIN algorithm on real GSTINs too).
    private val sellerGstin = "33AABCS1429B1Z1"
    private val buyerGstin = "33AAGFK2265M1Z7"

    private val clean = """
        SRI LAKSHMI TRADERS
        GSTIN: $sellerGstin
        TAX INVOICE
        Invoice No: SLT-1042
        Date: 12/09/2026
        Bill To: Kumar Stores
        GSTIN: $buyerGstin
        Description HSN Qty Rate Amount
        Basmati Rice 1006 10 kg 85.00 850.00
        Sugar 1701 5 kg 44.00 220.00
        Taxable Value 1,070.00
        CGST @ 2.5% 26.75
        SGST @ 2.5% 26.75
        Grand Total 1,123.50
        Terms & Conditions: Goods once sold will not be taken back
        Authorised Signatory
    """

    // ---------------------------------------------------------------- 1, 4, 10–17, 20

    @Test
    fun cleanInvoiceIsReadyAndFullyRead() {
        val bill = parse(clean)
        assertEquals(r("1123.50"), bill.total)
        assertEquals(r("53.50"), bill.tax)
        assertEquals(r("26.75"), bill.gst!!.cgst)
        assertEquals(r("26.75"), bill.gst!!.sgst)
        assertEquals(r("1070.00"), bill.taxableValue)
        assertEquals("SLT-1042", bill.invoiceNumber)
        assertEquals(LocalDate.of(2026, 9, 12), bill.date)
        assertEquals("Kumar Stores", bill.customerName)
        assertEquals("SRI LAKSHMI TRADERS", bill.merchantName)
        assertEquals(sellerGstin, bill.sellerGstin!!.value)
        assertEquals(buyerGstin, bill.buyerGstin!!.value)
        assertEquals(BillStatus.READY, bill.check.status)
        assertTrue(bill.check.issues.toString(), bill.check.issues.isEmpty())
    }

    @Test
    fun gstTableRowsGiveHsnQtyUnitAndPrice() {
        val items = parse(clean).items
        assertEquals(listOf("Basmati Rice", "Sugar"), items.map { it.description })
        assertEquals(listOf("1006", "1701"), items.map { it.hsn })
        assertEquals(listOf(BigDecimal("10"), BigDecimal("5")), items.map { it.quantity })
        assertEquals(listOf("kg", "kg"), items.map { it.unit })
        assertEquals(listOf(r("85.00"), r("44.00")), items.map { it.unitPrice })
        assertEquals(listOf(r("850.00"), r("220.00")), items.map { it.amount })
    }

    // 16 + 19: summary and footer rows are not things bought.
    @Test
    fun summaryAndFooterRowsAreNeverItems() {
        val bill = parse(
            """
            ANBU AGENCIES
            Pen 1 20.00 20.00
            Taxable Value 20.00
            CGST 9% 1.80
            SGST 9% 1.80
            Round Off 0.40
            Grand Total 24.00
            Declaration: we declare this invoice 1
            Authorised Signatory 2
            Bank IFSC SBIN0001234
            """,
        )
        assertEquals(listOf("Pen"), bill.items.map { it.description })
    }

    @Test
    fun indoBurmaStyleRowDropsSerialAndHsnFromTheName() {
        val bill = parse(
            """
            1 Odonil Room Freshner 33074900 18 % 10 NOS 130.00 NOS 1,300.00
            2 Godrej Aer Pocket 33074900 18 % 18 NOS 55.08 NOS 991.44
            Grand Total 2,291.44
            """,
        )
        assertEquals(listOf("Odonil Room Freshner", "Godrej Aer Pocket"), bill.items.map { it.description })
        assertEquals(listOf("33074900", "33074900"), bill.items.map { it.hsn })
        assertEquals(listOf(BigDecimal("10"), BigDecimal("18")), bill.items.map { it.quantity })
        assertEquals(listOf(r("130.00"), r("55.08")), bill.items.map { it.unitPrice })
        assertEquals(listOf("nos", "nos"), bill.items.map { it.unit })
    }

    // eng+tam slips Tamil letters into English label words: the labels still work, Tamil names are kept.
    @Test
    fun tamilLettersInsideEnglishLabelsAreRepaired() {
        val bill = parse(
            """
            ஸ்ரீ முருகன் ஸ்டோர்ஸ்
            Invoிce No: MUR-1041
            Pen 1 20.00 20.00
            Taxaபble Value 20.00
            CGST @ 9% 1.80
            SGST @ 9% 1.80
            Grand Toடtal 23.60
            """,
        )
        assertEquals(listOf("Pen"), bill.items.map { it.description })
        assertEquals("MUR-1041", bill.invoiceNumber)
        assertEquals(r("23.60"), bill.total)
        assertEquals(r("20.00"), bill.taxableValue)
        assertEquals("ஸ்ரீ முருகன் ஸ்டோர்ஸ்", BillTextParser.repairMixedScript("ஸ்ரீ முருகன் ஸ்டோர்ஸ்"))
    }

    // ---------------------------------------------------------------- 3: multi-rate GST

    @Test
    fun multiRateGstAddsEveryRateLine() {
        val bill = parse(
            """
            SAI SUPER MARKET
            Item A 1 1,000.00 1,000.00
            Item B 1 1,000.00 1,000.00
            Item C 1 1,000.00 1,000.00
            Taxable Value 3,000.00
            CGST @ 2.5% 25.00
            SGST @ 2.5% 25.00
            CGST @ 6% 60.00
            SGST @ 6% 60.00
            CGST @ 9% 90.00
            SGST @ 9% 90.00
            Grand Total 3,350.00
            """,
        )
        // Before: only the first CGST/SGST pair (₹50) was taken.
        assertEquals(r("350.00"), bill.tax)
        assertEquals(r("175.00"), bill.gst!!.cgst)
        assertEquals(listOf(BigDecimal("2.5"), BigDecimal("6"), BigDecimal("9")), bill.gst!!.rates)
        assertEquals(r("3350.00"), bill.total)
        assertFalse(bill.check.issues.toString(), bill.check.moneyNeedsCheck)
    }

    @Test
    fun footerTotalOfRateLinesIsNotCountedTwice() {
        val bill = parse(
            """
            Taxable Value 2,000.00
            CGST @ 2.5% 25.00
            CGST @ 9% 90.00
            SGST @ 2.5% 25.00
            SGST @ 9% 90.00
            CGST 115.00
            SGST 115.00
            Grand Total 2,230.00
            """,
        )
        assertEquals(r("230.00"), bill.tax)
        assertFalse(bill.gst!!.suspect)
    }

    // ---------------------------------------------------------------- 5: IGST

    @Test
    fun igstInvoice() {
        val bill = parse(
            """
            CHENNAI TOOLS
            Drill Machine 1 1,000.00 1,000.00
            Taxable Value 1,000.00
            IGST @ 18% 180.00
            Grand Total 1,180.00
            """,
        )
        assertEquals(r("180.00"), bill.tax)
        assertEquals(r("180.00"), bill.gst!!.igst)
        assertNull(bill.gst!!.cgst)
        assertFalse(bill.check.moneyNeedsCheck)
    }

    @Test
    fun igstTogetherWithCgstSgstNeedsReview() {
        val bill = parse(
            """
            Taxable Value 1,000.00
            CGST @ 9% 90.00
            SGST @ 9% 90.00
            IGST @ 18% 180.00
            Grand Total 1,180.00
            """,
        )
        assertTrue(bill.check.has(BillIssue.IGST_WITH_CGST_SGST))
        assertEquals(BillStatus.REVIEW_REQUIRED, bill.check.status)
    }

    // ---------------------------------------------------------------- rates are never money

    @Test
    fun aRateIsNeverReadAsAnAmount() {
        val after = parse(
            """
            Taxable Value 1,000.00
            CGST 90.00 @ 9%
            SGST 90.00 @ 9.00%
            Grand Total 1,180.00
            """,
        )
        assertEquals(r("180.00"), after.tax)
        val nextLine = parse(
            """
            Taxable Value 1,000.00
            CGST @ 9%
            90.00
            SGST @ 9%
            90.00
            Grand Total 1,180.00
            """,
        )
        assertEquals(r("180.00"), nextLine.tax)
    }

    // ---------------------------------------------------------------- 6, 7, 26, 27

    @Test
    fun decimalPriceAndQuantity() {
        val items = parse(
            """
            KAVERI DRY FRUITS
            Cashew 0.5 kg 900.00 450.00
            Almond 0.25 kg 1,200.50 300.13
            Grand Total 750.13
            """,
        ).items
        assertEquals(BigDecimal("0.5"), items[0].quantity)
        assertEquals(r("900.00"), items[0].unitPrice)
        assertEquals(BigDecimal("0.25"), items[1].quantity)
        assertEquals(r("1200.50"), items[1].unitPrice)
    }

    @Test
    fun missingQuantityOrPriceIsLeftEmptyNeverGuessed() {
        val items = parse(
            """
            Service charge 500.00
            Rice 10 kg 850.00
            Grand Total 1,350.00
            """,
        ).items
        assertEquals(2, items.size)
        assertTrue(items.all { it.quantity == null && it.unitPrice == null })
    }

    // ---------------------------------------------------------------- 8, 9: discount, round off

    @Test
    fun discountIsReadAndItemsStillReconcile() {
        val bill = parse(
            """
            MEENA TEXTILES
            Saree 1 1,000.00 1,000.00
            Dhoti 2 250.00 500.00
            Discount 10% 150.00
            Taxable Value 1,350.00
            CGST @ 2.5% 33.75
            SGST @ 2.5% 33.75
            Grand Total 1,417.50
            """,
        )
        assertEquals(r("150.00"), bill.discount)
        assertEquals(listOf("Saree", "Dhoti"), bill.items.map { it.description })
        assertFalse(bill.check.issues.toString(), bill.check.has(BillIssue.ITEMS_MISMATCH))
        assertFalse(bill.check.moneyNeedsCheck)
    }

    @Test
    fun roundOffReconciles() {
        val bill = parse(
            """
            Taxable Value 1,016.95
            CGST @ 9% 91.53
            SGST @ 9% 91.53
            Round Off (-) 0.01
            Grand Total 1,200.00
            """,
        )
        assertEquals(r("-0.01"), bill.roundOff)
        assertEquals(r("1200.00"), bill.total)
        assertFalse(bill.check.issues.toString(), bill.check.has(BillIssue.TOTAL_MISMATCH))
    }

    // ---------------------------------------------------------------- 11, 25: GSTIN

    @Test
    fun gstinCheckDigitAndPositionAwareOcrFixes() {
        assertTrue(GstinReader.valid("33AAAFI0207R1ZJ")) // a real GSTIN (Indo Burma Agencies)
        assertTrue(GstinReader.valid(sellerGstin))
        assertFalse(GstinReader.valid("33ABCDE1234F1Z5"))
        // "l" printed where a digit belongs in the PAN: fixed only because the check digit then passes.
        assertEquals(sellerGstin, GstinReader.read("33AABCSl429B1Z1"))
        // A wrong check digit is never "fixed".
        assertNull(GstinReader.read("33AABCS1429B1Z2"))
    }

    @Test
    fun invalidGstinIsFlaggedNotAccepted() {
        val bill = parse(
            """
            SRI MURUGAN STORES
            GSTIN: 33ABCDE1234F1Z5
            Rice 1 250.00 250.00
            Grand Total 250.00
            """,
        )
        assertFalse(bill.sellerGstin!!.valid)
        assertTrue(bill.check.has(BillIssue.GSTIN_INVALID))
        assertEquals(BillStatus.REVIEW_REQUIRED, bill.check.status)
        // The money is fine: the total is not held for a GSTIN problem.
        assertFalse(bill.check.moneyNeedsCheck)
    }

    @Test
    fun anInvoiceNumberIsNotAGstin() {
        val bill = parse(
            """
            Invoice No: INV2026091200001
            Grand Total 100.00
            """,
        )
        assertNull(bill.sellerGstin)
    }

    // ---------------------------------------------------------------- 14: buyer / side column

    @Test
    fun buyerNameIsNeverTheRightHandColumn() {
        val bill = parse(
            """
            SRI LAKSHMI TRADERS
            Bill To:            Invoice No: CSH-2041
            Ravi Traders         Date: 12/09/2026
            Rice 1 100.00 100.00
            Grand Total 100.00
            """,
        )
        assertEquals("Ravi Traders", bill.customerName)
        assertEquals("CSH-2041", bill.invoiceNumber)
    }

    @Test
    fun unreadableBuyerIsLeftEmptyAndMarked() {
        val bill = parse(
            """
            SRI LAKSHMI TRADERS
            Bill To: Invoice No: CSH-2041
            GSTIN: $buyerGstin
            Grand Total 100.00
            """,
        )
        assertNull(bill.customerName)
        assertTrue(bill.check.has(BillIssue.BUYER_UNREADABLE))
    }

    // ---------------------------------------------------------------- 21: TEST-024

    private val test024 = """
        KRISHNA ELECTRICALS
        Invoice No: KE-0024
        Date: 03/09/2026
        Taxable Value 3,680.00
        CGST @ 12% 43620
        SGST @ 12% 436.20
        Grand Total 4,552.40
    """

    @Test
    fun test024DroppedDecimalIsNeverAccepted() {
        val bill = parse(test024)
        // Before: tax ₹44,056.20 shown for a ₹4,552.40 bill.
        assertNull("an impossible tax is never offered as read", bill.tax)
        assertEquals(r("4552.40"), bill.total)
        assertEquals(BillStatus.INVALID, bill.check.status)
        assertTrue(bill.check.has(BillIssue.TAX_IMPOSSIBLE))
        assertTrue(bill.check.has(BillIssue.DECIMAL_SUSPECT))
        assertTrue(bill.check.has(BillIssue.CGST_SGST_MISMATCH))
        assertTrue(bill.check.moneyNeedsCheck)
    }

    @Test
    fun test024ReviewFormHoldsTheTotalAndHidesTheTax() {
        val state = BillEntryState()
        state.applyScan(parse(test024), serverName = null, serverAmount = null, ocrConfidence = 92)
        assertTrue(state.taxNeedsCheck)
        assertTrue(state.totalDoesNotAddUp)
        assertTrue(BillField.TOTAL in state.toVerify)
        assertTrue(BillScanWarning.CHECK_DETAILS in state.warnings)
        state.paid = ""
        assertFalse("not savable before the owner checks the total", state.canSave)
        state.verified(BillField.TOTAL)
        assertTrue(state.canSave)
    }

    // ---------------------------------------------------------------- 22: TEST-025

    @Test
    fun test025TextLossWithHighConfidenceIsNeverReady() {
        val bill = parse(
            """
            TAX INVOICE
            Sri
            4
            """,
        )
        // A stray "4" is at most offered as a suggestion — never filled in as the bill total.
        assertFalse(bill.totalFromLabel)
        assertTrue(bill.check.has(BillIssue.TOO_LITTLE_TEXT))
        assertTrue(bill.check.status != BillStatus.READY)
        val state = BillEntryState()
        state.applyScan(bill, serverName = null, serverAmount = null, ocrConfidence = 95)
        assertTrue(BillScanWarning.CHECK_DETAILS in state.warnings)
        assertFalse(state.canSave)
    }

    // ---------------------------------------------------------------- 23, 24

    @Test
    fun impossibleTaxIsRejected() {
        val bill = parse(
            """
            Taxable Value 1,000.00
            CGST @ 9% 900.00
            SGST @ 9% 900.00
            Grand Total 1,180.00
            """,
        )
        assertNull(bill.tax)
        assertEquals(BillStatus.INVALID, bill.check.status)
    }

    @Test
    fun grandTotalMismatchNeedsReview() {
        val bill = parse(
            """
            Taxable Value 10,000.00
            CGST @ 9% 900.00
            SGST @ 9% 900.00
            Grand Total 19,800.00
            """,
        )
        assertEquals(r("19800.00"), bill.total)
        assertTrue(bill.check.has(BillIssue.TOTAL_MISMATCH))
        assertEquals(BillStatus.REVIEW_REQUIRED, bill.check.status)
        val state = BillEntryState()
        state.applyScan(bill, serverName = null, serverAmount = null)
        assertTrue(BillField.TOTAL in state.toVerify)
        assertFalse(state.taxNeedsCheck)
    }

    @Test
    fun cgstWithoutSgstIsInconsistent() {
        val bill = parse(
            """
            Taxable Value 1,000.00
            CGST @ 9% 90.00
            Grand Total 1,180.00
            """,
        )
        assertTrue(bill.check.has(BillIssue.TAX_LINES_INCONSISTENT))
    }

    // ---------------------------------------------------------------- 28: corrupted OCR

    @Test
    fun corruptedOcrNeverCrashesOrClaimsReady() {
        for (garbage in listOf("", "~~~ ### !!!", "|||| ---- ....", "₹₹₹ 0.00 0.00", "ஒஒஒ ௧௨௩", "1\n2\n3\n4\n5")) {
            val bill = BillTextParser.parse(garbage)
            assertTrue(garbage, bill.check.status != BillStatus.READY)
            assertTrue(garbage, bill.items.isEmpty())
        }
    }

    // ---------------------------------------------------------------- 29: repeated reads (JVM)

    @Test
    fun fiftyConsecutiveReadsKeepNoStaleData() {
        val totals = (1..50).map { i ->
            val amount = BigDecimal(100 + i).setScale(2)
            val bill = BillTextParser.parse("SHOP $i\nItem $i 1 $amount $amount\nGrand Total $amount")
            assertEquals("SHOP $i", bill.merchantName)
            bill.total
        }
        assertEquals((1..50).map { BigDecimal(100 + it).setScale(2) }, totals)
    }

    // ---------------------------------------------------------------- 30: review gate

    @Test
    fun readyBillStillWaitsForTheOwnersSave() {
        val state = BillEntryState()
        state.applyScan(parse(clean), serverName = null, serverAmount = null, ocrConfidence = 90)
        assertTrue(state.toVerify.isEmpty())
        assertFalse(state.taxNeedsCheck)
        assertEquals(r("53.50"), state.tax)
        // Nothing is saved by a scan: the form only becomes savable; Save is the owner's tap.
        assertTrue(state.visible)
        assertEquals("1123.50", state.total)
    }

    // ---------------------------------------------------------------- OCR pass choice / image math

    @Test
    fun ocrChooserPrefersWhatAddsUpNotConfidence() {
        val good = clean.trimIndent()
        val mixed = good.replace("Grand Total", "Grand Toடtal").replace("Taxable", "Taxaபble")
        val lost = "TAX INVOICE\nSri"
        assertEquals(0, OcrResultChooser.best(listOf(good, mixed, lost)))
        assertEquals(1, OcrResultChooser.best(listOf(lost, good)))
        assertTrue(OcrResultChooser.mixedScriptWords(mixed) >= 2)
        assertTrue(OcrResultChooser.goodEnough(good))
        assertFalse(OcrResultChooser.goodEnough(test024.trimIndent()))
    }

    @Test
    fun adaptiveThresholdKeepsTextUnderAShadow() {
        // A page darkening left → right (shadow), with dark "ink" strokes in both halves.
        val w = 200
        val h = 60
        val gray = IntArray(w * h) { i -> val x = i % w; 230 - x / 2 }
        for (x in listOf(30, 31, 32, 160, 161, 162)) for (y in 20 until 40) gray[y * w + x] = (230 - x / 2) - 70
        val out = OcrImageMath.bradley(gray, w, h)
        val black = 0xFF000000.toInt()
        assertEquals(black, out[30 * w + 31])
        assertEquals(black, out[30 * w + 161])
        // Shadowed paper stays paper.
        assertTrue(out[10 * w + 180] != black)
        assertTrue(out[10 * w + 20] != black)
    }

    @Test
    fun skewIsFoundOnATiltedPage() {
        val w = 300
        val h = 200
        val gray = IntArray(w * h) { 255 }
        val slope = Math.tan(Math.toRadians(3.0))
        for (row in listOf(40, 80, 120, 160)) for (x in 0 until w) {
            val y = (row + x * slope).toInt()
            if (y in 0 until h) gray[y * w + x] = 0
        }
        val angle = OcrImageMath.skewDegrees(gray, w, h)
        assertTrue("angle $angle", angle in 2.5f..3.5f)
        val straight = IntArray(w * h) { 255 }.also { g -> for (row in listOf(40, 80, 120)) for (x in 0 until w) g[row * w + x] = 0 }
        assertEquals(0f, OcrImageMath.skewDegrees(straight, w, h))
    }

    @Test
    fun grandTotalOfExistingGstFixturesUnchanged() {
        // The supermarket and Tally fixtures of the older suites still give the same grand totals.
        val supermarket = """
            SRI MURUGAN STORES
            Ponni Rice 5kg   1   250.00   250.00
            Sub Total                     570.00
            CGST 2.5%                      14.25
            SGST 2.5%                      14.25
            Round Off                       0.50
            Grand Total                   599.00
        """
        val bill = parse(supermarket)
        assertEquals(r("599.00"), bill.total)
        assertEquals(r("28.50"), bill.tax)
        assertNotNull(bill.check)
    }
}
