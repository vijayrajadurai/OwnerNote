package com.shopai.app.brain.note

import com.shopai.app.brain.KaiLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Owner, 22 Oct 2026: the stock question card's number pad — keys, quick rates and the profit line (screen help, never saved). */
class KaiAskPadTest {

    @Test fun theKeysTypeANumber() {
        var v = ""
        for (k in listOf("0", "6", "0")) v = KaiAskPad.press(v, k)
        assertEquals("60", v)
        assertEquals("6", KaiAskPad.press(v, "del"))
        assertEquals("0.", KaiAskPad.press("", "."))
        assertEquals("12.5", KaiAskPad.press("12.5", "."))
        assertEquals("12.55", KaiAskPad.press("12.5", "5"))
        assertEquals("12.55", KaiAskPad.press("12.55", "9"))
        assertEquals("1234567", KaiAskPad.press("1234567", "8"))
        assertEquals("60", KaiAskPad.press("60", "x"))
        assertNull(KaiAskPad.amount("0"))
        assertNull(KaiAskPad.amount(""))
    }

    @Test fun quickRatesSitAboveTheCost() {
        assertEquals(listOf("55", "60", "65"), KaiAskPad.quickRates(BigDecimal("50")).map { it.toPlainString() })
        assertEquals(listOf("5.5", "6", "6.5"), KaiAskPad.quickRates(BigDecimal("5")).map { it.toPlainString() })
        assertEquals(listOf("2640", "2880", "3120"), KaiAskPad.quickRates(BigDecimal("2400")).map { it.toPlainString() })
        // A rate that rounds back to the cost is never offered.
        assertTrue(KaiAskPad.quickRates(BigDecimal("1")).all { it > BigDecimal.ONE })
        assertTrue(KaiAskPad.quickRates(null).isEmpty())
        assertEquals("₹10", KaiAskPad.gain(BigDecimal("60"), BigDecimal("50")))
    }

    @Test fun theProfitIsShownAndALossSaidPlainly() {
        val p = KaiAskPad.profit("60", BigDecimal("50"), BigDecimal("100"), "100 pcs", "piece", KaiLang.TANGLISH)!!
        assertFalse(p.loss)
        assertEquals("Oru piece-ku labam ₹10 · 100 pcs-ku ₹1,000", p.text)
        val ta = KaiAskPad.profit("60", BigDecimal("50"), BigDecimal("100"), "100 pcs", "piece", KaiLang.TAMIL)!!
        assertTrue(ta.text, ta.text.contains("லாபம் ₹10") && ta.text.contains("₹1,000"))
        val loss = KaiAskPad.profit("45", BigDecimal("50"), BigDecimal("100"), "100 pcs", "piece", KaiLang.TANGLISH)!!
        assertTrue(loss.loss)
        assertEquals("Nashtam! Oru piece-ku ₹5 kammi — sariyaa?", loss.text)
        // No cost said, or nothing typed: no line (never a guessed profit).
        assertNull(KaiAskPad.profit("60", null, BigDecimal("100"), "100 pcs", "piece", KaiLang.TANGLISH))
        assertNull(KaiAskPad.profit("", BigDecimal("50"), BigDecimal("100"), "100 pcs", "piece", KaiLang.TANGLISH))
    }
}
