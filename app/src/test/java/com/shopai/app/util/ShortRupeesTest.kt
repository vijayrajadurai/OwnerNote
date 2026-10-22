package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner, 22 Oct 2026: Home tiles show money short (₹18.2K, ₹4.8L) the way a shop says it. */
class ShortRupeesTest {
    @Test
    fun tileAmountsAreShort() {
        assertEquals("₹0", shortRupees(0.0))
        assertEquals("₹850", shortRupees(850.0))
        assertEquals("₹18K", shortRupees(18_000.0))
        assertEquals("₹18.2K", shortRupees(18_240.0))
        assertEquals("₹14.5K", shortRupees(14_500.0))
        assertEquals("₹4.8L", shortRupees(4_82_500.0))
        assertEquals("₹2.1L", shortRupees(2_10_000.0))
        assertEquals("₹1Cr", shortRupees(1_00_00_000.0))
        assertEquals("₹1L", shortRupees(99_990.0))
        assertEquals("₹999", shortRupees(999.0))
        assertEquals("₹1Cr", shortRupees(99_96_000.0))
    }

    @Test
    fun negativeKeepsTheSign() {
        assertEquals("-₹3K", shortRupees(-3_000.0))
    }
}
