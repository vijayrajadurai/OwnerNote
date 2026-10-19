package com.shopai.app.books

import com.shopai.app.books.model.Money
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.tax.GstCalculator
import com.shopai.app.books.tax.GstException
import com.shopai.app.books.tax.Gstin
import com.shopai.app.books.tax.TaxLineInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release check (20 Oct 2026): GST figures worked out by hand — 5 / 12 / 18 / 28 % + cess, MRP-inclusive, inter-state IGST, round-off, GSTIN check. */
class BooksGstReleaseTest {
    private fun rs(v: String) = Money.ofRupees(v)

    @Test fun fivePercentIntraState() {
        val r = GstCalculator.line(TaxLineInput(Qty.of(4), rs("250"), 500), interState = false)
        assertEquals(rs("1000"), r.taxablePaise); assertEquals(rs("25"), r.cgstPaise); assertEquals(rs("25"), r.sgstPaise)
        assertEquals(0, r.igstPaise); assertEquals(rs("1050"), r.totalPaise)
    }

    @Test fun twelvePercentInterStateWithRoundOff() {
        val inv = GstCalculator.invoice(listOf(TaxLineInput(Qty.of(2), rs("499"), 1200)), interState = true)
        assertEquals(rs("998"), inv.taxablePaise); assertEquals(rs("119.76"), inv.igstPaise)
        assertEquals(0, inv.cgstPaise + inv.sgstPaise)
        assertEquals(rs("1118"), inv.grandTotalPaise); assertEquals(24, inv.roundOffPaise)
    }

    @Test fun twentyEightPlusCess() {
        val r = GstCalculator.line(TaxLineInput(Qty.of(1), rs("1000"), 2800, cessBp = 1200), interState = false)
        assertEquals(rs("140"), r.cgstPaise); assertEquals(rs("140"), r.sgstPaise); assertEquals(rs("120"), r.cessPaise)
        assertEquals(rs("1400"), r.totalPaise)
    }

    @Test fun mrpInclusiveFivePercent() {
        val r = GstCalculator.line(TaxLineInput(Qty.of(1), rs("105"), 500, priceIncludesTax = true), interState = false)
        assertEquals(rs("100"), r.taxablePaise); assertEquals(rs("2.50"), r.cgstPaise); assertEquals(rs("2.50"), r.sgstPaise)
        assertEquals(rs("105"), r.totalPaise)
    }

    @Test fun halfKgNilRated() {
        val r = GstCalculator.line(TaxLineInput(Qty.of("0.5"), rs("60"), 0, taxType = TaxType.NIL_RATED), interState = false)
        assertEquals(rs("30"), r.totalPaise); assertEquals(0, r.taxPaise)
        assertThrows(GstException::class.java) { GstCalculator.line(TaxLineInput(Qty.of(1), rs("60"), 500, taxType = TaxType.EXEMPT), false) }
    }

    @Test fun manyLinesAddUp() {
        val inv = GstCalculator.invoice(listOf(
            TaxLineInput(Qty.of(3), rs("40"), 500),        // 120 + 6
            TaxLineInput(Qty.of(1), rs("200"), 1800),      // 200 + 36
            TaxLineInput(Qty.of(2), rs("55.50"), 1200, discountBp = 1000), // 111 - 11.10 = 99.90 + 11.988 → 99.90 + 5.99 + 5.99
        ), interState = false)
        assertEquals(rs("419.90"), inv.taxablePaise)
        assertEquals(inv.taxablePaise + inv.taxPaise + inv.roundOffPaise, inv.grandTotalPaise)
        assertEquals(0L, inv.grandTotalPaise % 100)
        assertEquals(inv.cgstPaise, inv.sgstPaise)
    }

    @Test fun stateAndGstin() {
        assertFalse(GstCalculator.isInterState("33", "33")); assertTrue(GstCalculator.isInterState("33", "29")); assertFalse(GstCalculator.isInterState(null, "29"))
        val first = "33ABCDE1234F1Z"
        val ok = first + Gstin.checkChar(first)
        assertTrue(Gstin.isValid(ok)); assertTrue(Gstin.isValid(" " + ok.lowercase() + " "))
        val wrong = first + (if (ok.last() == 'A') 'B' else 'A')
        assertFalse(Gstin.isValid(wrong)); assertFalse(Gstin.isValid("99ABCDE1234F1Z5")); assertEquals("33", Gstin.stateCode(ok))
    }

    @Test fun badInputsRefused() {
        assertThrows(GstException::class.java) { GstCalculator.line(TaxLineInput(0, rs("10"), 500), false) }
        assertThrows(GstException::class.java) { GstCalculator.line(TaxLineInput(Qty.of(1), -1, 500), false) }
        assertThrows(GstException::class.java) { GstCalculator.invoice(emptyList(), false) }
    }
}
