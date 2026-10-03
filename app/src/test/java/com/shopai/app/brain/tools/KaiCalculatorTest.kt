package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kai's calculator: exact decimal arithmetic, never an estimate. */
class KaiCalculatorTest {
    private fun result(text: String): String {
        val a = KaiCalculator.solve(text) ?: error("not a calculation: $text")
        return a.figures.joinToString(" | ") { "${it.label}=${KaiCalculator.format(it)}" }
    }

    @Test
    fun plainArithmetic() {
        assertEquals("RESULT=30", result("10*3"))
        assertEquals("RESULT=30", result("10 * 3 evlo?"))
        assertEquals("RESULT=14", result("2 + 3 * 4"))
        assertEquals("RESULT=20", result("(2 + 3) * 4"))
        assertEquals("RESULT=3.333333", result("10 / 3"))
        assertEquals("RESULT=0.3", result("0.1 + 0.2"))
        assertEquals("RESULT=220", result("200 + 10%"))
        assertEquals("RESULT=1,00,000", result("1000 x 100"))
    }

    @Test
    fun gstDiscountPercent() {
        assertEquals("GST=₹4,500 | TOTAL=₹29,500", result("25000 la 18% GST evlo?"))
        assertEquals("GST=₹4,500 | TOTAL=₹29,500", result("18% GST on 25000"))
        assertEquals("BASE=₹1,000 | GST=₹180", result("1180 incl 18% GST"))
        assertEquals("DISCOUNT=₹100 | FINAL=₹900", result("1000 la 10% discount"))
        assertEquals("RESULT=4,500", result("25000 la 18%"))
    }

    @Test
    fun quantityTimesRateAndProfit() {
        assertEquals("RESULT=₹4,100", result("50 kg × ₹82"))
        assertEquals("RESULT=₹4,100", result("50 kg x 82 rupees"))
        assertEquals("MARGIN_PERCENT=25% | PROFIT=₹20", result("cost 80 sell 100"))
        assertEquals("MARGIN_PERCENT=10% | LOSS=₹10", result("cost 100 sell 90"))
    }

    @Test
    fun unitConversion() {
        assertEquals("RESULT=2,000 g", result("2 kg evlo gram"))
        assertEquals("RESULT=1.5 kg", result("1500 g in kg"))
        assertEquals("RESULT=36 pcs", result("3 dozen evlo pieces"))
    }

    @Test
    fun notCalculations() {
        assertNull(KaiCalculator.solve("Ramesh ku 5000 kuduthen"))
        assertNull(KaiCalculator.solve("Kumar ku 10 minutes kalichi call pannanum"))
        assertNull(KaiCalculator.solve("10/10/2026 la Kumar tharanum"))
        assertNull(KaiCalculator.solve("call 98765-43210"))
        assertNull(KaiCalculator.solve("Inniku evlo sales?"))
        assertNull(KaiCalculator.solve("10 / 0"))
    }
}
