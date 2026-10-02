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
import com.shopai.app.data.model.DailyCashOpeningRequest
import com.shopai.app.data.model.DailyCashReportResponse
import com.shopai.app.data.model.SubmitDailyCashReportEntry
import com.shopai.app.data.model.SubmitDailyCashReportRequest
import com.shopai.app.data.model.TodayCashSummary
import com.shopai.app.data.local.room.toEntity
import retrofit2.HttpException
import com.shopai.app.util.computeCashBoxAmount
import com.shopai.app.util.computeDailyCashTotals
import com.shopai.app.util.localDateKey
import com.shopai.app.util.sortEntriesNewestFirst
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DailyCashRepository(
    private val dao: DailyCashDao,
    private val api: ShopAiApi,
) {
    suspend fun listDailyCashEntries(dateKey: String): List<DailyCashEntry> = withContext(Dispatchers.IO) {
        val local = dao.listEntriesByDate(dateKey)
        val submitted = getDayStatus(dateKey) == DailyCashDayStatus.SUBMITTED
        val shouldFetchRemote = submitted || (local.isEmpty() && dateKey < localDateKey())
        if (shouldFetchRemote) {
            runCatching { refreshSubmittedDayFromServer(dateKey) }
        }
        sortEntriesNewestFirst(dao.listEntriesByDate(dateKey).map { it.toDomain() })
    }

    suspend fun getTodayCashSummary(dateKey: String): TodayCashSummary = withContext(Dispatchers.IO) {
        val entries = listDailyCashEntries(dateKey)
        val totals = computeDailyCashTotals(entries)
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
        if (getCashBoxSnapshot(date) == null) {
            throw IllegalStateException("Set kallapetti amount first")
        }
        dao.ensureDayOpen(
            DailyCashDayEntity(date = date, status = "OPEN", submittedAt = null, openingBalance = null),
        )
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val id = "cash-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}"
        val createdAt = isoMillisNow()
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
    suspend fun submitDayReport(dateKey: String): List<DailyCashEntry> = withContext(Dispatchers.IO) {
        if (getDayStatus(dateKey) == DailyCashDayStatus.SUBMITTED) {
            return@withContext listDailyCashEntries(dateKey)
        }
        val entries = dao.listEntriesByDate(dateKey).map { it.toDomain() }
        if (entries.isEmpty()) {
            throw IllegalStateException("No entries to submit")
        }
        val report = pushReportToServer(dateKey, entries)
        markSubmitted(dateKey)
        if (report.entries.isNotEmpty()) {
            cacheRemoteReport(dateKey, report)
        }
        sortEntriesNewestFirst(
            report.toDomainEntries(dateKey).ifEmpty { entries },
        )
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
            val report = pushReportToServer(date, entries)
            markSubmitted(date)
            cacheRemoteReport(date, report)
            syncedCount += 1
        }
        syncedCount
    }

    private suspend fun pushReportToServer(dateKey: String, entries: List<DailyCashEntry>): DailyCashReportResponse {
        val totals = computeDailyCashTotals(entries)
        return api.submitDailyCashReport(
            SubmitDailyCashReportRequest(
                date = dateKey,
                totalIn = totals.totalIn,
                totalOut = totals.totalOut,
                net = totals.net,
                cashIn = totals.cashIn,
                cashOut = totals.cashOut,
                upiIn = totals.upiIn,
                upiOut = totals.upiOut,
                openingBalance = dao.getDay(dateKey)?.openingBalance,
                entries = entries.map { entry ->
                    SubmitDailyCashReportEntry(
                        type = entry.type.name,
                        amount = entry.amount,
                        paymentMode = entry.paymentMode.name,
                        note = entry.note,
                        createdAt = toIsoMillis(entry.createdAt),
                    )
                },
            ),
        ).data
    }

    private suspend fun refreshSubmittedDayFromServer(dateKey: String) {
        try {
            val report = api.getDailyCashReport(dateKey).data
            markSubmitted(dateKey)
            if (report.entries.isNotEmpty()) {
                cacheRemoteReport(dateKey, report)
            }
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
    }

    private suspend fun cacheRemoteReport(dateKey: String, report: DailyCashReportResponse) {
        dao.deleteEntriesForDate(dateKey)
        val entities = report.toDomainEntries(dateKey).map { it.toEntity() }
        if (entities.isNotEmpty()) {
            dao.insertEntries(entities)
        }
        report.openingBalance?.let { persistLocalOpening(dateKey, it) }
    }

    private fun DailyCashReportResponse.toDomainEntries(dateKey: String): List<DailyCashEntry> =
        entries.mapNotNull { entry ->
            val type = runCatching { DailyCashEntryType.valueOf(entry.type) }.getOrNull() ?: return@mapNotNull null
            val mode = runCatching { DailyCashPaymentMode.valueOf(entry.paymentMode) }.getOrNull() ?: return@mapNotNull null
            DailyCashEntry(
                id = entry.id,
                date = dateKey,
                type = type,
                amount = entry.amount,
                paymentMode = mode,
                note = entry.note,
                createdAt = entry.createdAt,
            )
        }

    data class CashBoxSnapshot(
        val opening: Double,
        val current: Double,
    )

    suspend fun getCashBoxSnapshot(dateKey: String): CashBoxSnapshot? = withContext(Dispatchers.IO) {
        val opening = resolveOpeningBalance(dateKey) ?: return@withContext null
        val current = computeCashBoxAmount(opening, dao.listEntriesByDate(dateKey).map { it.toDomain() })
        CashBoxSnapshot(opening = opening, current = current)
    }

    suspend fun getOpeningBalance(dateKey: String): Double? =
        getCashBoxSnapshot(dateKey)?.opening

    /** Current physical cash-box amount, or null until the owner sets it the first time. */
    suspend fun getCashBoxAmount(dateKey: String): Double? =
        getCashBoxSnapshot(dateKey)?.current

    suspend fun setOpeningBalance(dateKey: String, amount: Double): Double = withContext(Dispatchers.IO) {
        requireOpenDay(dateKey)
        require(amount.isFinite() && amount >= 0) { "Opening balance must be zero or more" }
        persistLocalOpening(dateKey, amount)
        api.upsertDailyCashOpening(DailyCashOpeningRequest(date = dateKey, openingBalance = amount))
        computeCashBoxAmount(amount, dao.listEntriesByDate(dateKey).map { it.toDomain() })
    }

    private suspend fun resolveOpeningBalance(dateKey: String): Double? {
        dao.getDay(dateKey)?.openingBalance?.let { return it }
        val fromOpeningApi = runCatching { api.getDailyCashOpening(dateKey).data.openingBalance }.getOrElse { error ->
            if (error is HttpException && error.code() != 404) return null
            null
        }
        if (fromOpeningApi != null) {
            persistLocalOpening(dateKey, fromOpeningApi)
            return fromOpeningApi
        }
        if (dateKey >= localDateKey()) return null
        val fromReport = runCatching { api.getDailyCashReport(dateKey).data.openingBalance }.getOrNull()
        if (fromReport != null) {
            persistLocalOpening(dateKey, fromReport)
        }
        return fromReport
    }

    private suspend fun persistLocalOpening(dateKey: String, amount: Double) {
        val existing = dao.getDay(dateKey)
        dao.upsertDay(
            DailyCashDayEntity(
                date = dateKey,
                status = existing?.status ?: "OPEN",
                submittedAt = existing?.submittedAt,
                openingBalance = amount,
            ),
        )
    }

    private suspend fun markSubmitted(dateKey: String) {
        val existing = dao.getDay(dateKey)
        dao.upsertDay(
            DailyCashDayEntity(
                date = dateKey,
                status = "SUBMITTED",
                submittedAt = isoMillisNow(),
                openingBalance = existing?.openingBalance,
            ),
        )
    }

    private fun isoMillisNow(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

    private fun toIsoMillis(iso: String): String =
        runCatching { Instant.parse(iso).truncatedTo(ChronoUnit.MILLIS).toString() }.getOrDefault(iso)

    private suspend fun requireOpenDay(dateKey: String) {
        if (getDayStatus(dateKey) == DailyCashDayStatus.SUBMITTED) {
            throw IllegalStateException("Day already submitted")
        }
        if (dateKey < localDateKey()) {
            throw IllegalStateException("Past day is closed")
        }
    }
}
