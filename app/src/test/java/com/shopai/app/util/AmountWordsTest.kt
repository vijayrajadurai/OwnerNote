package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner, 22 Oct 2026: the amount typed on Add Credit / Debit said in words, so an extra zero is seen before Save. */
class AmountWordsTest {
    @Test fun tamil() {
        assertEquals("இரண்டு ஆயிரத்து ஐநூறு ரூபாய்", AmountWords.of(2500.0, tamil = true))
        assertEquals("ஆயிரம் ரூபாய்", AmountWords.of(1000.0, tamil = true))
        assertEquals("இருபத்து ஒன்று ரூபாய்", AmountWords.of(21.0, tamil = true))
        assertEquals("ஒரு லட்சத்து ஐம்பது ஆயிரம் ரூபாய்", AmountWords.of(150000.0, tamil = true))
        assertEquals("பன்னிரண்டு லட்சத்து நாற்பத்து ஏழு ஆயிரத்து முந்நூற்று அறுபத்து ஐந்து ரூபாய்", AmountWords.of(1247365.0, tamil = true))
        assertEquals("ஒரு கோடி ரூபாய்", AmountWords.of(10_000_000.0, tamil = true))
        assertEquals("ஐநூறு ரூபாய் ஐம்பது பைசா", AmountWords.of(500.5, tamil = true))
    }

    @Test fun english() {
        assertEquals("Two thousand five hundred rupees", AmountWords.of(2500.0, tamil = false))
        assertEquals("Twelve lakh forty-seven thousand three hundred sixty-five rupees", AmountWords.of(1247365.0, tamil = false))
        assertEquals("Ninety-nine crore rupees", AmountWords.of(990_000_000.0, tamil = false))
        assertEquals("One rupee", AmountWords.of(1.0, tamil = false))
        assertEquals("Five hundred rupees and fifty paise", AmountWords.of(500.5, tamil = false))
    }

    @Test fun nothingTypedSaysNothing() {
        assertEquals("", AmountWords.of(0.0, tamil = true))
        assertEquals("", AmountWords.of(-5.0, tamil = false))
    }
}
