package com.shopai.app.books

import com.shopai.app.books.model.CodeKind
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.ProductUnits
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.tax.GstCalculator
import com.shopai.app.books.tax.GstException
import com.shopai.app.books.tax.Gstin
import com.shopai.app.books.tax.HsnRules
import com.shopai.app.books.tax.TaxLineInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BooksTaxTest {
    private fun rs(v: String) = Money.ofRupees(v)

    @Test
    fun intraStateSplitsIntoCgstAndSgstOnly() {
        val r = GstCalculator.line(TaxLineInput(qtyMilli = Qty.of(10), ratePaise = rs("100"), gstBp = 1800), interState = false)
        assertEquals(rs("1000"), r.taxablePaise)
        assertEquals(rs("90"), r.cgstPaise)
        assertEquals(rs("90"), r.sgstPaise)
        assertEquals(0, r.igstPaise)
        assertEquals(rs("1180"), r.totalPaise)
    }

    @Test
    fun interStateIsIgstOnly() {
        val r = GstCalculator.line(TaxLineInput(qtyMilli = Qty.of(10), ratePaise = rs("100"), gstBp = 1800), interState = true)
        assertEquals(rs("180"), r.igstPaise)
        assertEquals(0, r.cgstPaise + r.sgstPaise)
    }

    @Test
    fun taxInclusiveRateBacksOutTheTaxAndKeepsTheLineTotal() {
        val r = GstCalculator.line(TaxLineInput(qtyMilli = Qty.of(1), ratePaise = rs("118"), gstBp = 1800, priceIncludesTax = true), interState = false)
        assertEquals(rs("100"), r.taxablePaise)
        assertEquals(rs("9"), r.cgstPaise)
        assertEquals(rs("9"), r.sgstPaise)
        assertEquals(rs("118"), r.totalPaise)
        // Awkward figures still add back to exactly the inclusive price.
        val odd = GstCalculator.line(TaxLineInput(qtyMilli = Qty.of(3), ratePaise = rs("99.99"), gstBp = 1200, priceIncludesTax = true), interState = false)
        assertEquals(rs("299.97"), odd.totalPaise)
    }

    @Test
    fun discountPercentAndAmount() {
        val pct = GstCalculator.line(TaxLineInput(Qty.of(10), rs("100"), 500, discountBp = 1000), false)
        assertEquals(rs("100"), pct.discountPaise)
        assertEquals(rs("900"), pct.taxablePaise)
        assertEquals(rs("22.50"), pct.cgstPaise)
        val flat = GstCalculator.line(TaxLineInput(Qty.of(10), rs("100"), 0, discountPaise = rs("50")), false)
        assertEquals(rs("950"), flat.taxablePaise)
        assertThrows(GstException::class.java) { GstCalculator.line(TaxLineInput(Qty.of(1), rs("10"), 0, discountPaise = rs("11")), false) }
        assertThrows(GstException::class.java) { GstCalculator.line(TaxLineInput(Qty.of(1), rs("10"), 0, discountPaise = 1, discountBp = 100), false) }
    }

    @Test
    fun itemLevelRoundingAndSeparateRoundOff() {
        val t = GstCalculator.invoice(listOf(TaxLineInput(Qty.of(3), rs("33.33"), 500)), interState = false)
        assertEquals(rs("99.99"), t.taxablePaise)
        assertEquals(250, t.cgstPaise) // 2.49975 → ₹2.50
        assertEquals(250, t.sgstPaise)
        assertEquals(rs("104.99"), t.taxablePaise + t.taxPaise)
        assertEquals(1, t.roundOffPaise)
        assertEquals(rs("105"), t.grandTotalPaise)
        val noRound = GstCalculator.invoice(listOf(TaxLineInput(Qty.of(3), rs("33.33"), 500)), interState = false, roundOff = false)
        assertEquals(0, noRound.roundOffPaise)
        assertEquals(rs("104.99"), noRound.grandTotalPaise)
    }

    @Test
    fun oddBasisPointRatesAndCess() {
        val r = GstCalculator.line(TaxLineInput(Qty.of(1), rs("10000"), 25), false) // 0.25%
        assertEquals(rs("12.50"), r.cgstPaise)
        assertEquals(rs("12.50"), r.sgstPaise)
        val cess = GstCalculator.line(TaxLineInput(Qty.of(1), rs("1000"), 2800, cessBp = 1200), true)
        assertEquals(rs("280"), cess.igstPaise)
        assertEquals(rs("120"), cess.cessPaise)
    }

    @Test
    fun nonGstItemsCannotCarryTax() {
        assertThrows(GstException::class.java) { GstCalculator.line(TaxLineInput(Qty.of(1), rs("10"), 500, taxType = TaxType.EXEMPT), false) }
        val ok = GstCalculator.line(TaxLineInput(Qty.of(1), rs("10"), 0, taxType = TaxType.NIL_RATED), false)
        assertEquals(0, ok.taxPaise)
        assertThrows(GstException::class.java) { GstCalculator.line(TaxLineInput(Qty.of(0), rs("10"), 0), false) }
        assertThrows(GstException::class.java) { GstCalculator.line(TaxLineInput(Qty.of(1), -1, 0), false) }
    }

    @Test
    fun decimalQuantitiesAndLargeAmountsAreExact() {
        val kg = GstCalculator.line(TaxLineInput(Qty.of("2.5"), rs("48.40"), 0), false)
        assertEquals(rs("121"), kg.totalPaise)
        val big = GstCalculator.line(TaxLineInput(Qty.of(1000), rs("999999.99"), 1800), true)
        assertEquals(rs("999999990"), big.taxablePaise)
        assertEquals(rs("179999998.20"), big.igstPaise)
    }

    @Test
    fun interStateOnlyWhenBothStatesKnownAndDifferent() {
        assertTrue(GstCalculator.isInterState("33", "29"))
        assertFalse(GstCalculator.isInterState("33", "33"))
        assertFalse(GstCalculator.isInterState("33", null))
    }

    @Test
    fun gstinChecksum() {
        val first14 = "33ABCDE1234F1Z"
        val valid = first14 + Gstin.checkChar(first14)
        assertTrue(Gstin.isValid(valid))
        assertTrue(Gstin.isValid(valid.lowercase()))
        val wrong = first14 + (if (valid.last() == 'A') 'B' else 'A')
        assertFalse(Gstin.isValid(wrong))
        assertFalse(Gstin.isValid("99ABCDE1234F1Z5")) // no such state
        assertFalse(Gstin.isValid("33ABCDE1234F1"))
        assertEquals("33", Gstin.stateCode(valid))
    }

    @Test
    fun hsnAndSacShapes() {
        assertTrue(HsnRules.isWellFormed("1006", CodeKind.HSN))
        assertTrue(HsnRules.isWellFormed("100630", CodeKind.HSN))
        assertTrue(HsnRules.isWellFormed("1006 3010", CodeKind.HSN))
        assertFalse(HsnRules.isWellFormed("100", CodeKind.HSN))
        assertFalse(HsnRules.isWellFormed("10063", CodeKind.HSN))
        assertFalse(HsnRules.isWellFormed("10A6", CodeKind.HSN))
        assertTrue(HsnRules.isWellFormed("998314", CodeKind.SAC))
        assertFalse(HsnRules.isWellFormed("100630", CodeKind.SAC))
        assertEquals(HsnRules.Verdict.VERIFICATION_REQUIRED, HsnRules.verdict("1006", CodeKind.HSN, inMaster = false))
        assertEquals(HsnRules.Verdict.VERIFIED, HsnRules.verdict("1006", CodeKind.HSN, inMaster = true))
        assertEquals(HsnRules.Verdict.INVALID_FORMAT, HsnRules.verdict("12", CodeKind.HSN, inMaster = true))
    }

    @Test
    fun unitConversion() {
        val box = ProductUnits("PCS", "BOX", Qty.of(12))
        assertEquals(Qty.of(60), box.toPrimary(Qty.of(5), "BOX"))
        assertEquals(Qty.of(10), box.toPrimary(Qty.of(10), "pcs"))
        assertEquals(Qty.of(6), box.toPrimary(Qty.of("0.5"), "BOX"))
        assertThrows(IllegalArgumentException::class.java) { box.toPrimary(Qty.of(1), "KG") }
        assertThrows(IllegalArgumentException::class.java) { ProductUnits("PCS", "BOX", null) }
        assertThrows(IllegalArgumentException::class.java) { ProductUnits("PCS", "PCS", Qty.of(1)) }
    }
}
