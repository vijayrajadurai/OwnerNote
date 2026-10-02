package com.shopai.app.data.repository

import com.shopai.app.data.local.room.CapturedDocumentEntity
import com.shopai.app.data.local.room.CapturedDocumentEntity.Companion.KIND_BILL
import com.shopai.app.data.local.room.CapturedDocumentEntity.Companion.KIND_NOTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CapturedDocumentGroupingTest {
    private fun doc(id: Long, kind: String, amount: String, linkedBillId: Long? = null) =
        CapturedDocumentEntity(id = id, kind = kind, name = "d$id", amount = amount, date = null, linkedBillId = linkedBillId, createdAt = id)

    @Test
    fun notesAreGroupedUnderTheirBillNewestFirst() {
        val grouped = groupCapturedDocuments(
            listOf(
                doc(1, KIND_BILL, "599.00"),
                doc(2, KIND_BILL, "100.00"),
                doc(3, KIND_NOTE, "250.00", linkedBillId = 1),
                doc(4, KIND_NOTE, "300.00", linkedBillId = 1),
                doc(5, KIND_NOTE, "40.00"),
            ),
        )
        assertEquals(listOf(2L, 1L), grouped.bills.map { it.bill.id })
        val first = grouped.bills.single { it.bill.id == 1L }
        assertEquals(listOf(4L, 3L), first.notes.map { it.id })
        assertEquals(BigDecimal("550.00"), first.notesTotal)
        assertTrue(grouped.bills.single { it.bill.id == 2L }.notes.isEmpty())
        assertEquals(listOf(5L), grouped.unlinkedNotes.map { it.id })
    }

    @Test
    fun noteOfAMissingBillIsShownAsUnlinked() {
        val grouped = groupCapturedDocuments(listOf(doc(7, KIND_NOTE, "10.00", linkedBillId = 99)))
        assertTrue(grouped.bills.isEmpty())
        assertEquals(listOf(7L), grouped.unlinkedNotes.map { it.id })
    }
}
