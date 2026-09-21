package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.local.room.DailyCashDao
import com.shopai.app.data.local.room.DailyCashDayEntity
import com.shopai.app.data.local.room.DailyCashEntryEntity
import com.shopai.app.data.local.room.toDomain
import com.shopai.app.data.model.DailyCashDayStatus
import com.shopai.app.data.model.DailyCashEntry
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.model.DailyCashPaymentMode
import com.shopai.app.data.model.SubmitDailyCashReportEntry
import com.shopai.app.data.model.SubmitDailyCashReportRequest
import com.shopai.app.data.model.TodayCashSummary
import com.shopai.app.util.computeDailyCashTotals
import com.shopai.app.util.localDateKey
import com.shopai.app.util.sortEntriesNewestFirst
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DailyCashRepository(
    private val dao: DailyCashDao,
    private val api: ShopAiApi,
) {
    suspend fun listDailyCashEntries(dateKey: String): List<DailyCashEntry> = withContext(Dispatchers.IO) {
        sortEntriesNewestFirst(dao.listEntriesByDate(dateKey).map { it.toDomain() })
    }

    suspend fun getTodayCashSummary(dateKey: String): TodayCashSummary = withContext(Dispatchers.IO) {
        val entries = dao.listEntriesByDate(dateKey)
        val totals = computeDailyCashTotals(entries.map { it.toDomain() })
        TodayCashSummary(
            date = dateKey,
            totalIn = totals.totalIn,
            totalOut = totals.totalOut,
            entryCount = entries.size,
        )
    }

    suspend fun getDayStatus(dateKey: String): DailyCashDayStatus = withContext(Dispatchers.IO) {
        when (dao.getDay(dateKey)?.status) {
            "SUBMITTED" -> DailyCashDayStatus.SUBMITTED
            else -> DailyCashDayStatus.OPEN
        }
    }

    suspend fun createDailyCashEntry(
        date: String,
        type: DailyCashEntryType,
        amount: Double,
        paymentMode: DailyCashPaymentMode,
        note: String?,
    ): DailyCashEntry = withContext(Dispatchers.IO) {
        requireOpenDay(date)
        dao.ensureDayOpen(DailyCashDayEntity(date = date, status = "OPEN", submittedAt = null))
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val id = "cash-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}"
        val createdAt = Instant.now().toString()
        val entity = DailyCashEntryEntity(
            id = id,
            date = date,
            type = type.name,
            amount = amount,
            paymentMode = paymentMode.name,
            note = trimmedNote,
            createdAt = createdAt,
        )
        dao.insertEntry(entity)
        entity.toDomain()
    }

    suspend fun updateDailyCashEntry(
        id: String,
        type: DailyCashEntryType,
        amount: Double,
        paymentMode: DailyCashPaymentMode,
        note: String?,
    ): DailyCashEntry = withContext(Dispatchers.IO) {
        val existing = dao.getEntryById(id) ?: throw IllegalStateException("Daily cash entry not found")
        requireOpenDay(existing.date)
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val updated = existing.copy(
            type = type.name,
            amount = amount,
            paymentMode = paymentMode.name,
            note = trimmedNote,
        )
        dao.updateEntry(updated)
        updated.toDomain()
    }

    suspend fun deleteDailyCashEntry(id: String): Unit = withContext(Dispatchers.IO) {
        val entry = dao.getEntryById(id) ?: return@withContext
        requireOpenDay(entry.date)
        dao.deleteEntry(id)
    }

    /** Manually closes a day and sends the full report to the server. */
    suspend fun submitDayReport(dateKey: String): Unit = withContext(Dispatchers.IO) {
        if (getDayStatus(dateKey) == DailyCashDayStatus.SUBMITTED) return@withContext
        val entries = dao.listEntriesByDate(dateKey).map { it.toDomain() }
        if (entries.isEmpty()) {
            throw IllegalStateException("No entries to submit")
        }
        pushReportToServer(dateKey, entries)
        markSubmitted(dateKey)
        dao.deleteEntriesForDate(dateKey)
    }

    /** Submits all completed days (before today) that still have local entries. */
    suspend fun syncPendingReports(): Int = withContext(Dispatchers.IO) {
        val today = localDateKey()
        val pendingDates = dao.listDatesNeedingSubmit(today)
        var syncedCount = 0
        for (date in pendingDates) {
            val entries = dao.listEntriesByDate(date).map { it.toDomain() }
            if (entries.isEmpty()) {
                markSubmitted(date)
                continue
            }
            pushReportToServer(date, entries)
            markSubmitted(date)
            dao.deleteEntriesForDate(date)
            syncedCount += 1
        }
        syncedCount
    }

    private suspend fun pushReportToServer(dateKey: String, entries: List<DailyCashEntry>) {
        val totals = computeDailyCashTotals(entries)
        api.submitDailyCashReport(
            SubmitDailyCashReportRequest(
                date = dateKey,
                totalIn = totals.totalIn,
                totalOut = totals.totalOut,
                net = totals.net,
                cashIn = totals.cashIn,
                cashOut = totals.cashOut,
                upiIn = totals.upiIn,
                upiOut = totals.upiOut,
                entries = entries.map { entry ->
                    SubmitDailyCashReportEntry(
                        type = entry.type.name,
                        amount = entry.amount,
                        paymentMode = entry.paymentMode.name,
                        note = entry.note,
                        createdAt = entry.createdAt,
                    )
                },
            ),
        ).data
    }

    private suspend fun markSubmitted(dateKey: String) {
        dao.upsertDay(
            DailyCashDayEntity(
                date = dateKey,
                status = "SUBMITTED",
                submittedAt = Instant.now().toString(),
            ),
        )
    }

    private suspend fun requireOpenDay(dateKey: String) {
        if (getDayStatus(dateKey) == DailyCashDayStatus.SUBMITTED) {
            throw IllegalStateException("Day already submitted")
        }
        if (dateKey < localDateKey()) {
            throw IllegalStateException("Past day is closed")
        }
    }
}
