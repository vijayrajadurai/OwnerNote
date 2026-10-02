package com.shopai.app.data.repository

import com.shopai.app.data.local.room.NoteTransactionEntity
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.STATUS_NEEDS_REVIEW
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.TYPE_CREDIT
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.TYPE_DEBIT
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** The parts of a transaction the totals need; built from saved rows or review drafts. */
data class LedgerLine(
    val type: String,
    val amount: BigDecimal?,
    val date: LocalDate?,
    val needsReview: Boolean,
)

data class LedgerSummary(
    val count: Int,
    val creditCount: Int,
    val debitCount: Int,
    val totalCredit: BigDecimal,
    val totalDebit: BigDecimal,
    val needsReview: Int,
    val todayCount: Int,
    val upcomingCount: Int,
    val overdueCount: Int,
) {
    /** Net Balance = Total Credit − Total Debit. Positive: others owe me. */
    val net: BigDecimal get() = totalCredit - totalDebit
}

enum class NoteFilter { ALL, CREDIT, DEBIT, NEEDS_REVIEW, TODAY, UPCOMING, OVERDUE, DATE_RANGE }
enum class NoteSort { DATE, AMOUNT, PERSON, TYPE }

data class PersonBalance(
    val name: String,
    val totalCredit: BigDecimal,
    val totalDebit: BigDecimal,
    val transactions: List<NoteTransactionEntity>,
) {
    val net: BigDecimal get() = totalCredit - totalDebit
}

fun NoteTransactionEntity.toLedgerLine() = LedgerLine(
    type = transactionType,
    amount = amount?.toBigDecimalOrNull(),
    date = localDate(),
    needsReview = reviewStatus == STATUS_NEEDS_REVIEW,
)

fun NoteTransactionEntity.localDate(): LocalDate? = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Same person regardless of case/spacing: "ravi " and "Ravi" are one person. */
fun personKey(name: String): String = name.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)

object NoteLedger {
    fun summarize(lines: List<LedgerLine>, today: LocalDate = LocalDate.now()): LedgerSummary {
        val credits = lines.filter { it.type == TYPE_CREDIT }
        val debits = lines.filter { it.type == TYPE_DEBIT }
        fun sum(items: List<LedgerLine>) = items.fold(BigDecimal.ZERO) { acc, l -> acc + (l.amount ?: BigDecimal.ZERO) }
        return LedgerSummary(
            count = lines.size,
            creditCount = credits.size,
            debitCount = debits.size,
            totalCredit = sum(credits),
            totalDebit = sum(debits),
            needsReview = lines.count { it.needsReview },
            todayCount = lines.count { it.date == today },
            upcomingCount = lines.count { it.date?.isAfter(today) == true },
            overdueCount = lines.count { it.date?.isBefore(today) == true },
        )
    }

    fun filterAndSort(
        rows: List<NoteTransactionEntity>,
        query: String,
        filter: NoteFilter,
        sort: NoteSort,
        descending: Boolean = false,
        range: ClosedRange<LocalDate>? = null,
        today: LocalDate = LocalDate.now(),
    ): List<NoteTransactionEntity> {
        val q = query.trim().lowercase(Locale.ROOT)
        val filtered = rows.filter { row ->
            val date = row.localDate()
            (q.isEmpty() || row.personName.lowercase(Locale.ROOT).contains(q)) && when (filter) {
                NoteFilter.ALL -> true
                NoteFilter.CREDIT -> row.transactionType == TYPE_CREDIT
                NoteFilter.DEBIT -> row.transactionType == TYPE_DEBIT
                NoteFilter.NEEDS_REVIEW -> row.reviewStatus == STATUS_NEEDS_REVIEW
                NoteFilter.TODAY -> date == today
                NoteFilter.UPCOMING -> date?.isAfter(today) == true
                NoteFilter.OVERDUE -> date?.isBefore(today) == true
                NoteFilter.DATE_RANGE -> range == null || (date != null && date in range)
            }
        }
        val comparator: Comparator<NoteTransactionEntity> = when (sort) {
            // Undated rows ("Not specified") go last.
            NoteSort.DATE -> compareBy<NoteTransactionEntity> { it.localDate() == null }.thenBy { it.localDate() }
            NoteSort.AMOUNT -> compareBy { it.amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO }
            NoteSort.PERSON -> compareBy { personKey(it.personName) }
            NoteSort.TYPE -> compareBy { it.transactionType }
        }
        val sorted = filtered.sortedWith(comparator.thenBy { it.id })
        return if (descending) sorted.reversed() else sorted
    }

    /** One entry per person (case-insensitive), transactions kept separate inside. */
    fun byPerson(rows: List<NoteTransactionEntity>): List<PersonBalance> =
        rows.groupBy { personKey(it.personName) }.values.map { group ->
            PersonBalance(
                name = group.first().personName.trim(),
                totalCredit = group.filter { it.transactionType == TYPE_CREDIT }
                    .fold(BigDecimal.ZERO) { acc, r -> acc + (r.amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO) },
                totalDebit = group.filter { it.transactionType == TYPE_DEBIT }
                    .fold(BigDecimal.ZERO) { acc, r -> acc + (r.amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO) },
                transactions = group.sortedWith(compareBy<NoteTransactionEntity> { it.localDate() == null }.thenBy { it.localDate() }.thenBy { it.id }),
            )
        }.sortedBy { personKey(it.name) }

    /** Groups by date, undated last. */
    fun byDate(rows: List<NoteTransactionEntity>): List<Pair<LocalDate?, List<NoteTransactionEntity>>> =
        rows.groupBy { it.localDate() }.toList()
            .sortedWith(compareBy<Pair<LocalDate?, List<NoteTransactionEntity>>> { it.first == null }.thenBy { it.first })
}
