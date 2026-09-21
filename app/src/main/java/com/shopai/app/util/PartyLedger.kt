package com.shopai.app.util

import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.DebitTransactionDetail
import com.shopai.app.data.model.parseMoney

enum class LedgerEntryKind {
    CREDIT_GIVEN,
    DEBIT_OWED,
    PAYMENT_RECEIVED,
    PAYMENT_MADE,
}

data class LedgerLine(
    val kind: LedgerEntryKind,
    val dateIso: String,
    val amount: Double,
    val label: String,
    val runningBalance: Double,
)

private data class MutableLedgerEvent(
    val kind: LedgerEntryKind,
    val dateIso: String,
    val amount: Double,
    val label: String,
)

fun buildReceivableLedger(transactions: List<CreditTransactionDetail>): List<LedgerLine> {
    val events = mutableListOf<MutableLedgerEvent>()
    for (txn in transactions) {
        val creditLabel = txn.description?.takeIf { it.isNotBlank() }
            ?: "Credit given"
        events += MutableLedgerEvent(
            kind = LedgerEntryKind.CREDIT_GIVEN,
            dateIso = txn.createdAt,
            amount = parseMoney(txn.amount),
            label = creditLabel,
        )
        for (payment in txn.payments) {
            events += MutableLedgerEvent(
                kind = LedgerEntryKind.PAYMENT_RECEIVED,
                dateIso = payment.createdAt,
                amount = parseMoney(payment.amount),
                label = payment.note?.takeIf { it.isNotBlank() } ?: "Payment received",
            )
        }
    }
    return events.toRunningBalanceLines()
}

fun buildPayableLedger(transactions: List<DebitTransactionDetail>): List<LedgerLine> {
    val events = mutableListOf<MutableLedgerEvent>()
    for (txn in transactions) {
        val debitLabel = txn.description?.takeIf { it.isNotBlank() }
            ?: "Debit owed"
        events += MutableLedgerEvent(
            kind = LedgerEntryKind.DEBIT_OWED,
            dateIso = txn.createdAt,
            amount = parseMoney(txn.amount),
            label = debitLabel,
        )
        for (payment in txn.payments) {
            events += MutableLedgerEvent(
                kind = LedgerEntryKind.PAYMENT_MADE,
                dateIso = payment.createdAt,
                amount = parseMoney(payment.amount),
                label = payment.note?.takeIf { it.isNotBlank() } ?: "Payment made",
            )
        }
    }
    return events.toRunningBalanceLines()
}

private fun List<MutableLedgerEvent>.toRunningBalanceLines(): List<LedgerLine> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.dateIso }
    var balance = 0.0
    val lines = sorted.map { event ->
        balance += when (event.kind) {
            LedgerEntryKind.CREDIT_GIVEN,
            LedgerEntryKind.DEBIT_OWED,
            -> event.amount
            LedgerEntryKind.PAYMENT_RECEIVED,
            LedgerEntryKind.PAYMENT_MADE,
            -> -event.amount
        }
        LedgerLine(
            kind = event.kind,
            dateIso = event.dateIso,
            amount = event.amount,
            label = event.label,
            runningBalance = balance,
        )
    }
    return lines.reversed()
}
