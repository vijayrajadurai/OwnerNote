package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who owes whom, from grammar — never from the verb alone: "Kumar enakku 3000 tharanum" and
 * "naan Kumar-ku 3000 tharanum" share the verb and mean opposite things.
 */
class KaiPaymentDirectionTest {

    private fun dir(text: String) = KaiPaymentDirection.of(KaiSpokenWords.normalize(text))

    @Test
    fun sameVerbOppositeDirections() {
        assertEquals(OwedDirection.RECEIVABLE, dir("Kumaran enaku 3000 tharanum"))
        assertEquals(OwedDirection.PAYABLE, dir("nan kumaran ku 3000 tharanum"))
    }

    @Test
    fun receivableVariations() {
        for (t in listOf(
            "Kumar enaku 3000 tharanum", "Kumar enakku 3000 tharanum", "Kumar enna 3000 tharanum", "Kumar enakku 3k tharanum",
            "Kumar 3000 kudukanum", "Kumar kitta 3000 vanganu", "Kumar enakku 3000 kudukanum", "Kumar 3000 tharanum",
            "Kumar owes me 3000", "I have to get 3000 from Kumar",
        )) assertEquals(t, OwedDirection.RECEIVABLE, dir(t))
    }

    @Test
    fun payableVariations() {
        for (t in listOf(
            "naan Kumar-ku 3000 tharanum", "nan Kumar ku 3000 kudukanum", "Kumar-ku 3000 pay pannanum", "Kumar-ku 3k kudukanum",
            "Kumar-ku cash kudukanum", "naan Kumar kitta 3000 kudukanum", "I owe Kumar 3000", "Kumar en kitta 3000 vanganum",
        )) assertEquals(t, OwedDirection.PAYABLE, dir(t))
    }

    @Test
    fun tamilScript() {
        assertEquals(OwedDirection.RECEIVABLE, dir("குமார் எனக்கு 3000 தரணும்"))
        assertEquals(OwedDirection.RECEIVABLE, dir("குமார் எனக்கு 3000 தர வேண்டும்"))
        assertEquals(OwedDirection.PAYABLE, dir("நான் குமாருக்கு 3000 தரணும்"))
        assertEquals(OwedDirection.PAYABLE, dir("குமாருக்கு 3000 கொடுக்கணும்"))
    }

    @Test
    fun timeAndOwnerWordsAreNotAPersonsDative() {
        // "innaikku", "enakku", "yaarukku" end in -kku but are not someone being paid.
        assertEquals(OwedDirection.RECEIVABLE, dir("Kumar innaikku 3000 tharanum"))
        assertEquals(OwedDirection.RECEIVABLE, dir("Kumar naalaikku enakku 3000 tharanum"))
    }

    @Test
    fun noMoneyVerbNoDirection() {
        assertNull(dir("Kumar evlo?"))
        assertNull(dir("saptiya?"))
        assertFalse(KaiPaymentDirection.mentionsMoneyOwed("supermarket enga irukku?"))
        assertTrue(KaiPaymentDirection.mentionsMoneyOwed("Kumar kitta 3000 vanganu"))
    }
}
