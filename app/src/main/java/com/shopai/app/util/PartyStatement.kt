package com.shopai.app.util

import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.DebitTransactionDetail
import com.shopai.app.data.model.parseMoney
import java.time.LocalDate

/** One entry of a party's ledger, as a statement reads it: what was given (credit / purchase) and what was paid against it. */
data class StatementTxn(
    val amount: Double,
    val paid: Double,
    val label: String?,
    val createdAt: LocalDate?,
    val dueDate: LocalDate?,
    val payments: List<StatementPayment>,
)

data class StatementPayment(val amount: Double, val date: LocalDate?, val note: String?)

/** A line of the statement: credit given (or goods bought) or a payment, and the balance after it. */
data class StatementRow(
    val date: LocalDate?,
    val given: Double,
    val paid: Double,
    val label: String?,
    val dueDate: LocalDate?,
    val balance: Double,
)

/**
 * A customer's (or supplier's) credit / payment statement for a period — the PDF the owner shares on WhatsApp.
 * Read only from the books: [opening] is the balance before [from], [closing] the balance at the end of the period.
 */
data class PartyStatement(
    val name: String,
    val phone: String?,
    /** A customer (they owe the owner); false: a supplier (the owner owes them). */
    val customer: Boolean,
    /** Null: the whole history. */
    val from: LocalDate?,
    val to: LocalDate,
    val opening: Double,
    val rows: List<StatementRow>,
    val given: Double,
    val paid: Double,
    val closing: Double,
    /** Pending on entries already past their due date (as of the day the statement is made). */
    val overdue: Double,
    /** The next due date of an entry still pending. */
    val nextDue: LocalDate?,
)

fun List<CreditTransactionDetail>.statementTxns(): List<StatementTxn> = map { t ->
    StatementTxn(
        parseMoney(t.amount), parseMoney(t.paidAmount), t.description?.takeIf { it.isNotBlank() },
        parseIsoToLocalDate(t.createdAt), parseIsoToLocalDate(t.dueDate),
        t.payments.map { StatementPayment(parseMoney(it.amount), parseIsoToLocalDate(it.createdAt), it.note?.takeIf { n -> n.isNotBlank() }) },
    )
}

@JvmName("debitStatementTxns")
fun List<DebitTransactionDetail>.statementTxns(): List<StatementTxn> = map { t ->
    StatementTxn(
        parseMoney(t.amount), parseMoney(t.paidAmount), t.description?.takeIf { it.isNotBlank() },
        parseIsoToLocalDate(t.createdAt), parseIsoToLocalDate(t.dueDate),
        t.payments.map { StatementPayment(parseMoney(it.amount), parseIsoToLocalDate(it.createdAt), it.note?.takeIf { n -> n.isNotBlank() }) },
    )
}

/**
 * The statement of [txns] from [from] (null: the beginning) to [to]: every credit and payment in date order (a credit before a
 * payment on the same day), with the running balance; what came before [from] is the opening balance.
 */
fun partyStatement(
    name: String,
    phone: String?,
    customer: Boolean,
    txns: List<StatementTxn>,
    from: LocalDate?,
    to: LocalDate,
    today: LocalDate,
): PartyStatement {
    data class Event(val date: LocalDate?, val given: Double, val paid: Double, val label: String?, val due: LocalDate?, val order: Int)
    val events = txns.flatMap { t ->
        listOf(Event(t.createdAt, t.amount, 0.0, t.label, t.dueDate, 0)) +
            t.payments.map { p -> Event(p.date, 0.0, p.amount, p.note, null, 1) }
    }.sortedWith(compareBy<Event, LocalDate?>(nullsFirst()) { it.date }.thenBy { it.order })
    var balance = 0.0
    var opening = 0.0
    val rows = mutableListOf<StatementRow>()
    for (e in events) {
        if (e.date != null && e.date.isAfter(to)) continue
        balance += e.given - e.paid
        if (from != null && (e.date == null || e.date.isBefore(from))) {
            opening = balance
            continue
        }
        rows += StatementRow(e.date, e.given, e.paid, e.label, e.due, balance)
    }
    val pendingTxns = txns.filter { it.amount - it.paid > 0.005 }
    return PartyStatement(
        name = name,
        phone = phone,
        customer = customer,
        from = from,
        to = to,
        opening = opening,
        rows = rows,
        given = rows.sumOf { it.given },
        paid = rows.sumOf { it.paid },
        closing = balance,
        overdue = pendingTxns.filter { it.dueDate != null && it.dueDate.isBefore(today) }.sumOf { it.amount - it.paid },
        nextDue = pendingTxns.mapNotNull { it.dueDate }.filter { !it.isBefore(today) }.minOrNull(),
    )
}

/**
 * The number WhatsApp opens a chat with, in international form without "+" ("98400 12345" → 919840012345). Null: not a
 * phone number Kai can be sure of (then the owner picks the contact in WhatsApp).
 */
fun whatsappNumber(phone: String?): String? {
    val digits = phone?.filter(Char::isDigit) ?: return null
    return when {
        digits.length == 10 && digits.first() in '6'..'9' -> "91$digits"
        digits.length == 11 && digits.first() == '0' && digits[1] in '6'..'9' -> "91${digits.drop(1)}"
        digits.length == 12 && digits.startsWith("91") && digits[2] in '6'..'9' -> digits
        digits.length in 11..15 && phone.trim().startsWith("+") -> digits
        else -> null
    }
}
