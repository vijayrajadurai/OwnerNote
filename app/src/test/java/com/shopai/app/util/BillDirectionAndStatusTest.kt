package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BillDirectionAndStatusTest {
    private val me = MyBusiness("Anbu Traders", "33ABCDE1234F1Z" + "5", "9876543210")
    private fun bill(text: String) = BillDirection.decide(text, BillTextParser.parse(text), me)

    @Test
    fun myOwnBillToACustomerIsCredit() {
        val g = bill("ANBU TRADERS\nGandhi Road, Chennai\nPh 98765 43210\nBill to: Ravi Stores\nRice 10 kg 600\nTotal 600")
        assertEquals(TxnDirection.CREDIT, g.direction)
        assertEquals(BillDirectionReason.MY_BILL_TO_CUSTOMER, g.reason)
        assertTrue(g.confident)
    }

    @Test
    fun supplierBillAddressedToMeIsDebit() {
        val g = bill("SRI BALAJI HARDWARES\nGSTIN 33ZZZZZ9999Z1Z9\nBuyer (Bill to)\nAnbu Traders\nCement 10 bags 4000\nGrand Total 4720")
        assertEquals(TxnDirection.DEBIT, g.direction)
        assertEquals(BillDirectionReason.ADDRESSED_TO_ME, g.reason)
        // GSTIN under the buyer heading also means it is addressed to me.
        val byGst = bill("SRI BALAJI\nBill to\nGSTIN: 33ABCDE1234F1Z5\nTotal 500")
        assertEquals(TxnDirection.DEBIT, byGst.direction)
    }

    @Test
    fun billFromAnotherShopWithoutMyDetailsIsDebitButNotSure() {
        val g = bill("KUMAR AGENCIES\nSoap 10 x 40 400\nTotal 400")
        assertEquals(TxnDirection.DEBIT, g.direction)
        assertEquals(BillDirectionReason.FROM_ANOTHER_SHOP, g.reason)
        assertFalse(g.confident)
    }

    @Test
    fun paymentStatuses() {
        val today = LocalDate.of(2026, 10, 2)
        fun s(total: Int, paid: Int, due: LocalDate?) = PaymentStatus.of(BigDecimal(total), BigDecimal(paid), due, today)
        assertEquals(PaymentState.PAID, s(1000, 1000, today.minusDays(9)).state)
        assertEquals(PaymentState.OVERDUE, s(1000, 0, today.minusDays(3)).state)
        with(s(1000, 400, today.minusDays(3))) {
            assertEquals(PaymentState.OVERDUE, state)
            assertTrue(partlyPaid)
            assertEquals(BigDecimal(600), outstanding)
            assertEquals(3L, daysLate)
        }
        assertEquals(PaymentState.PARTIALLY_PAID, s(1000, 400, today.plusDays(5)).state)
        assertEquals(PaymentState.UPCOMING, s(1000, 0, today.plusDays(5)).state)
        assertEquals(PaymentState.UPCOMING, s(1000, 0, today).state) // due today is not late yet
        assertEquals(PaymentState.PENDING, s(1000, 0, null).state)
    }
}
