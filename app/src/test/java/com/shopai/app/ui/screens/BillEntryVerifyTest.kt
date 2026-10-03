package com.shopai.app.ui.screens

import com.shopai.app.ui.components.TransactionSaveType
import com.shopai.app.util.ExtractedBill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** A scanned shop bill fills only what is clearly on it; anything unsure is checked before saving. */
class BillEntryVerifyTest {
    private val date = LocalDate.now().minusDays(1)

    private fun bill(shop: String? = "Sri Murugan Stores", total: String? = "1250", labelled: Boolean = true, person: String? = null) =
        ExtractedBill(
            merchantName = shop,
            total = total?.let(::BigDecimal),
            totalFromLabel = labelled,
            items = emptyList(),
            date = date,
            customerName = person,
        )

    @Test
    fun clearBillIsReadyToSave() {
        val s = BillEntryState()
        s.applyScan(bill(), serverName = null, serverAmount = null, ocrConfidence = 88)
        assertEquals("Sri Murugan Stores", s.shopName)
        assertEquals("1250", s.total)
        assertTrue(s.toVerify.isEmpty())
        assertTrue(s.canSave)
        // The due date is never read from the bill — the owner picks it.
        assertEquals(null, s.dueDate)
    }

    @Test
    fun unlabelledTotalIsOfferedNotFilled() {
        val s = BillEntryState()
        s.applyScan(bill(labelled = false), serverName = null, serverAmount = 999.0, ocrConfidence = 88)
        assertEquals("", s.total)
        assertEquals(BigDecimal("1250"), s.totalSuggestion)
        assertFalse(s.canSave)
        s.useTotalSuggestion()
        assertEquals("1250", s.total)
        assertTrue(s.canSave)
    }

    @Test
    fun serverNameIsOfferedNotFilled() {
        val s = BillEntryState()
        s.applyScan(bill(shop = null), serverName = "Balaji Traders", serverAmount = null, ocrConfidence = 88)
        assertEquals("", s.shopName)
        assertEquals("Balaji Traders", s.shopSuggestion)
        assertFalse(s.canSave)
    }

    @Test
    fun unsureOcrMustBeCheckedBeforeSaving() {
        val s = BillEntryState()
        s.applyScan(bill(person = "Kumar"), serverName = null, serverAmount = null, ocrConfidence = 41)
        assertEquals(setOf(BillField.SHOP, BillField.PERSON, BillField.TOTAL), s.toVerify)
        assertFalse(s.canSave)
        s.verified(BillField.SHOP)
        s.verified(BillField.PERSON)
        s.verified(BillField.TOTAL)
        assertTrue(s.canSave)
    }

    @Test
    fun garbledNameIsMarked() {
        val s = BillEntryState()
        s.applyScan(bill(shop = "S|1 ~#9 7r"), serverName = null, serverAmount = null, ocrConfidence = 90)
        assertEquals(setOf(BillField.SHOP), s.toVerify)
        // Credit bill: the customer must be entered too.
        s.type = TransactionSaveType.CREDIT
        s.verified(BillField.SHOP)
        assertFalse(s.canSave)
        s.customerName = "Ravi"
        assertTrue(s.canSave)
    }
}
