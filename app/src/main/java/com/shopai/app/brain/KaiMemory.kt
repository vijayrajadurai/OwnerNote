package com.shopai.app.brain

import com.shopai.app.data.model.CashFlowSummary
import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.DebitTransactionDetail
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.model.PriorityItem
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.util.parseIsoToLocalDate
import java.time.LocalDate
import java.util.Locale

/** One customer (they owe the owner: [Direction.RECEIVABLE]) or supplier (owner owes: [Direction.PAYABLE]). */
data class PartyFacts(
    val id: String,
    val name: String,
    val side: Direction,
    val pending: Double,
    val nextDue: LocalDate?,
)

/** A party's transactions, from the ledger, for history questions. */
data class PartyHistory(
    val party: PartyFacts,
    val entries: List<LedgerEntry>,
) {
    data class LedgerEntry(val amount: Double, val paid: Double, val dueDate: LocalDate?, val createdAt: LocalDate?, val payments: List<Payment>)
    data class Payment(val amount: Double, val date: LocalDate?)

    val lastPayment: Payment?
        get() = entries.flatMap { it.payments }.filter { it.date != null }.maxByOrNull { it.date!! }
    val totalBilled: Double get() = entries.sumOf { it.amount }
    val totalPaid: Double get() = entries.sumOf { it.paid }

    companion object {
        fun ofCustomer(party: PartyFacts, transactions: List<CreditTransactionDetail>) = PartyHistory(
            party,
            transactions.map { t ->
                LedgerEntry(
                    amount = t.amount.toDoubleOrNull() ?: 0.0,
                    paid = t.paidAmount.toDoubleOrNull() ?: 0.0,
                    dueDate = parseIsoToLocalDate(t.dueDate),
                    createdAt = parseIsoToLocalDate(t.createdAt),
                    payments = t.payments.map { Payment(it.amount.toDoubleOrNull() ?: 0.0, parseIsoToLocalDate(it.createdAt)) },
                )
            },
        )

        fun ofSupplier(party: PartyFacts, transactions: List<DebitTransactionDetail>) = PartyHistory(
            party,
            transactions.map { t ->
                LedgerEntry(
                    amount = t.amount.toDoubleOrNull() ?: 0.0,
                    paid = t.paidAmount.toDoubleOrNull() ?: 0.0,
                    dueDate = parseIsoToLocalDate(t.dueDate),
                    createdAt = parseIsoToLocalDate(t.createdAt),
                    payments = t.payments.map { Payment(it.amount.toDoubleOrNull() ?: 0.0, parseIsoToLocalDate(it.createdAt)) },
                )
            },
        )
    }
}

/**
 * Kai's Business Memory: the owner's real ledger at one moment — customers,
 * suppliers, reminders, cash flow and priorities, all read from the existing
 * backend (the source of truth). Nothing here is stored separately or made
 * up; every answer Kai gives comes from these facts. Pure queries: testable.
 */
data class BusinessSnapshot(
    val customers: List<PartySummary> = emptyList(),
    val suppliers: List<PartySummary> = emptyList(),
    val reminders: List<ReminderItem> = emptyList(),
    val cashFlow: CashFlowSummary? = null,
    val priorities: List<PriorityItem> = emptyList(),
) {
    val parties: List<PartyFacts> by lazy {
        customers.map { PartyFacts(it.id, it.name, Direction.RECEIVABLE, it.pendingTotal, parseIsoToLocalDate(it.nextDueDate)) } +
            suppliers.map { PartyFacts(it.id, it.name, Direction.PAYABLE, it.pendingTotal, parseIsoToLocalDate(it.nextDueDate)) }
    }

    /** Names Kai can recognise in the owner's words. */
    val people: List<String> get() = parties.map { it.name }.distinct()

    /**
     * Parties matching a spoken name: exact first, then a name that starts
     * with it ("Kumar" → "Kumar Stores"), then one letter off ("Kumaar").
     * Several matches are returned so Kai can ask which one — never picks.
     */
    fun find(name: String): List<PartyFacts> {
        val n = name.trim().lowercase(Locale.ROOT)
        if (n.isEmpty()) return emptyList()
        parties.filter { it.name.trim().lowercase(Locale.ROOT) == n }.takeIf { it.isNotEmpty() }?.let { return it }
        parties.filter { p -> p.name.lowercase(Locale.ROOT).split(' ').any { it == n } || p.name.lowercase(Locale.ROOT).startsWith("$n ") }
            .takeIf { it.isNotEmpty() }?.let { return it }
        parties.filter { editDistance(it.name.lowercase(Locale.ROOT), n) <= 1 && n.length >= 4 }.takeIf { it.isNotEmpty() }?.let { return it }
        // "Kumar" typed for a customer saved as "குமார்" (and the other way).
        return parties.filter { com.shopai.app.util.NameSound.same(it.name, name) }
    }

    /** Customers who owe the owner money, soonest due (and biggest) first. */
    fun owesMe(): List<PartyFacts> = pendingOn(Direction.RECEIVABLE)

    /** Suppliers the owner owes, soonest due (and biggest) first. */
    fun iOwe(): List<PartyFacts> = pendingOn(Direction.PAYABLE)

    /** Everyone with money pending on [side]: soonest due first, then the biggest, then by name (always the same order). */
    fun pendingOn(side: Direction) = parties
        .filter { it.side == side && it.pending > 0.005 }
        .sortedWith(compareBy<PartyFacts, LocalDate?>(nullsLast()) { it.nextDue }.thenByDescending { it.pending }.thenBy { it.name })

    fun totalReceivable(): Double = cashFlow?.pendingReceivables ?: owesMe().sumOf { it.pending }
    fun totalPayable(): Double = cashFlow?.pendingPayables ?: iOwe().sumOf { it.pending }

    /** Pending amounts due on [date] (collections and payments). */
    fun dueOn(date: LocalDate): List<PartyFacts> = parties.filter { it.pending > 0.005 && it.nextDue == date }

    /** Pending amounts whose due date has passed. */
    fun overdue(today: LocalDate): List<PartyFacts> = parties.filter { it.pending > 0.005 && it.nextDue != null && it.nextDue.isBefore(today) }

    /** Collections expected in the next 7 days (the backend's own forecast when available). */
    fun collectionsNext7Days(today: LocalDate): Double =
        cashFlow?.next7Days?.expectedCollections
            ?: owesMe().filter { it.nextDue != null && !it.nextDue.isBefore(today) && !it.nextDue.isAfter(today.plusDays(7)) }.sumOf { it.pending }

    /** Open owner reminders (not payment dues) for [date]. */
    fun customRemindersOn(date: LocalDate): List<ReminderItem> =
        reminders.filter { !it.isDone && it.kind.equals("CUSTOM", ignoreCase = true) && parseIsoToLocalDate(it.dueDate) == date }

    /** True when a reminder for this money and due date exists (so Kai may say he set one). */
    fun hasReminderFor(amount: Double, dueDate: LocalDate): Boolean =
        reminders.any { !it.isDone && parseIsoToLocalDate(it.dueDate) == dueDate && (it.amount == null || kotlin.math.abs(it.amount - amount) < 0.01) }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }
}
