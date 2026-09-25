package com.shopai.app.util

import com.shopai.app.data.model.DailyCashEntry
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.model.DailyCashPaymentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyCashNoteTest {
    private fun entry(
        type: DailyCashEntryType,
        amount: Double,
        paymentMode: DailyCashPaymentMode,
        createdAt: String = "2026-09-21T10:00:00.000Z",
    ) = DailyCashEntry(
        id = "e1",
        date = "2026-09-21",
        type = type,
        amount = amount,
        paymentMode = paymentMode,
        note = null,
        createdAt = createdAt,
    )

    @Test
    fun computeDailyCashTotals_empty() {
        val totals = computeDailyCashTotals(emptyList())
        assertEquals(0.0, totals.net, 0.01)
        assertEquals(0.0, totals.cashNet, 0.01)
        assertEquals(0.0, totals.upiNet, 0.01)
    }

    @Test
    fun computeDailyCashTotals_mixedModes() {
        val totals = computeDailyCashTotals(
            listOf(
                entry(DailyCashEntryType.IN, 100.0, DailyCashPaymentMode.CASH),
                entry(DailyCashEntryType.IN, 200.0, DailyCashPaymentMode.UPI),
                entry(DailyCashEntryType.OUT, 30.0, DailyCashPaymentMode.CASH),
                entry(DailyCashEntryType.OUT, 70.0, DailyCashPaymentMode.UPI),
            ),
        )
        assertEquals(300.0, totals.totalIn, 0.01)
        assertEquals(100.0, totals.totalOut, 0.01)
        assertEquals(200.0, totals.net, 0.01)
        assertEquals(130.0, totals.upiNet, 0.01)
        assertEquals(70.0, totals.cashNet, 0.01)
    }

    @Test
    fun sortEntriesNewestFirst_ordersByCreatedAt() {
        val older = entry(DailyCashEntryType.IN, 1.0, DailyCashPaymentMode.CASH, "2026-09-21T08:00:00.000Z")
        val newer = entry(DailyCashEntryType.IN, 2.0, DailyCashPaymentMode.CASH, "2026-09-21T12:00:00.000Z")
        val sorted = sortEntriesNewestFirst(listOf(older, newer))
        assertEquals(newer.id, sorted.first().id)
    }

    @Test
    fun isValidAmount_rejectsInvalidValues() {
        assertFalse(isValidAmount(""))
        assertFalse(isValidAmount("0"))
        assertFalse(isValidAmount("-5"))
        assertFalse(isValidAmount("abc"))
    }

    @Test
    fun isValidAmount_acceptsPositiveValues() {
        assertTrue(isValidAmount("12.5"))
        assertTrue(isValidAmount("  100  "))
    }

    @Test
    fun isValidNonNegativeAmount_allowsZero() {
        assertTrue(isValidNonNegativeAmount("0"))
        assertTrue(isValidNonNegativeAmount("2500"))
        assertFalse(isValidNonNegativeAmount("-1"))
        assertFalse(isValidNonNegativeAmount(""))
    }

    @Test
    fun computeCashBoxAmount_addsCashInAndSubtractsCashOut() {
        val box = computeCashBoxAmount(
            openingBalance = 1000.0,
            entries = listOf(
                entry(DailyCashEntryType.IN, 200.0, DailyCashPaymentMode.CASH),
                entry(DailyCashEntryType.IN, 50.0, DailyCashPaymentMode.UPI),
                entry(DailyCashEntryType.OUT, 80.0, DailyCashPaymentMode.CASH),
            ),
        )
        assertEquals(1120.0, box, 0.01)
    }
}
