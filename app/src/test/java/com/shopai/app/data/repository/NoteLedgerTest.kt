package com.shopai.app.data.repository

import com.shopai.app.data.local.room.NoteTransactionEntity
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.STATUS_NEEDS_REVIEW
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.STATUS_VERIFIED
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.TYPE_CREDIT
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.TYPE_DEBIT
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.TYPE_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class NoteLedgerTest {
    private val today = LocalDate.of(2026, 10, 12)

    private fun row(id: Long, name: String, amount: String?, type: String, date: String?, status: String = STATUS_VERIFIED) =
        NoteTransactionEntity(
            id = id, sourceNoteId = 1, personName = name, amount = amount, date = date, transactionType = type,
            description = null, originalOcrText = null, originalPersonName = null, originalAmountText = null,
            originalDate = null, originalType = null, correctedByUser = false, nameConfidence = null,
            amountConfidence = null, dateConfidence = null, typeConfidence = null, reviewStatus = status,
        )

    private val rows = listOf(
        row(1, "Ravi", "500.00", TYPE_DEBIT, "2026-10-10"),
        row(2, "Kumar", "1200.00", TYPE_CREDIT, "2026-10-12"),
        row(3, "Priya", "750.00", TYPE_DEBIT, "2026-10-15"),
        row(4, "ravi ", "1000.00", TYPE_DEBIT, "2026-10-20"),
        row(5, "Ravi", "250.00", TYPE_CREDIT, "2026-10-25"),
        row(6, "Old note", null, TYPE_UNKNOWN, null, STATUS_NEEDS_REVIEW),
    )

    @Test
    fun summaryTotalsAndNet() {
        val s = NoteLedger.summarize(rows.map { it.toLedgerLine() }, today)
        assertEquals(6, s.count)
        assertEquals(2, s.creditCount)
        assertEquals(3, s.debitCount)
        assertEquals(BigDecimal("1450.00"), s.totalCredit)
        assertEquals(BigDecimal("2250.00"), s.totalDebit)
        assertEquals(BigDecimal("-800.00"), s.net)
        assertEquals(1, s.needsReview)
        assertEquals(1, s.todayCount)
        assertEquals(3, s.upcomingCount)
        assertEquals(1, s.overdueCount)
    }

    @Test
    fun personWiseKeepsEachTransaction() {
        val ravi = NoteLedger.byPerson(rows).single { personKey(it.name) == "ravi" }
        assertEquals(3, ravi.transactions.size)
        assertEquals(BigDecimal("1500.00"), ravi.totalDebit)
        assertEquals(BigDecimal("250.00"), ravi.totalCredit)
        assertEquals(BigDecimal("-1250.00"), ravi.net)
        assertEquals(listOf(1L, 4L, 5L), ravi.transactions.map { it.id })
    }

    @Test
    fun searchAndFilters() {
        fun ids(query: String = "", filter: NoteFilter = NoteFilter.ALL, range: ClosedRange<LocalDate>? = null) =
            NoteLedger.filterAndSort(rows, query, filter, NoteSort.DATE, range = range, today = today).map { it.id }
        assertEquals(listOf(1L, 4L, 5L), ids(query = "RAV"))
        assertEquals(listOf(2L, 5L), ids(filter = NoteFilter.CREDIT))
        assertEquals(listOf(1L, 3L, 4L), ids(filter = NoteFilter.DEBIT))
        assertEquals(listOf(6L), ids(filter = NoteFilter.NEEDS_REVIEW))
        assertEquals(listOf(2L), ids(filter = NoteFilter.TODAY))
        assertEquals(listOf(3L, 4L, 5L), ids(filter = NoteFilter.UPCOMING))
        assertEquals(listOf(1L), ids(filter = NoteFilter.OVERDUE))
        assertEquals(listOf(3L, 4L), ids(filter = NoteFilter.DATE_RANGE, range = LocalDate.of(2026, 10, 13)..LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun sortingPutsUndatedLast() {
        val byDate = NoteLedger.filterAndSort(rows, "", NoteFilter.ALL, NoteSort.DATE, today = today).map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), byDate)
        val byAmountDesc = NoteLedger.filterAndSort(rows, "", NoteFilter.ALL, NoteSort.AMOUNT, descending = true, today = today).map { it.id }
        assertEquals(listOf(2L, 4L, 3L, 1L, 5L, 6L), byAmountDesc)
        val byPerson = NoteLedger.filterAndSort(rows, "", NoteFilter.ALL, NoteSort.PERSON, today = today).map { it.personName.trim() }
        assertEquals(listOf("Kumar", "Old note", "Priya", "Ravi", "ravi", "Ravi"), byPerson)
    }

    @Test
    fun dateWiseGroupsWithUndatedLast() {
        val groups = NoteLedger.byDate(rows)
        assertEquals(null, groups.last().first)
        assertEquals(LocalDate.of(2026, 10, 10), groups.first().first)
    }
}
