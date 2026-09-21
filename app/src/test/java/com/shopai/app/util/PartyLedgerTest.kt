package com.shopai.app.util

import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.PaymentRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class PartyLedgerTest {
    @Test
    fun buildsRunningBalanceForCreditAndPayment() {
        val lines = buildReceivableLedger(
            listOf(
                CreditTransactionDetail(
                    id = "t1",
                    amount = "5000",
                    paidAmount = "2000",
                    description = "Goods on credit",
                    dueDate = null,
                    status = "PARTIALLY_PAID",
                    createdAt = "2026-09-20T10:00:00.000Z",
                    payments = listOf(
                        PaymentRecord(
                            id = "p1",
                            amount = "2000",
                            note = null,
                            createdAt = "2026-09-21T10:00:00.000Z",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(2, lines.size)
        assertEquals(3000.0, lines[0].runningBalance, 0.01)
        assertEquals(5000.0, lines[1].runningBalance, 0.01)
    }
}
