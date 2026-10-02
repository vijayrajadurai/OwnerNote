package com.shopai.app.books.engine

import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.data.HsnSummaryRow
import com.shopai.app.books.data.OpenDoc
import com.shopai.app.books.model.Account
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.TxnType
import java.time.LocalDate

data class AgeingBuckets(
    val currentPaise: Long = 0,
    val days1to30Paise: Long = 0,
    val days31to60Paise: Long = 0,
    val days61to90Paise: Long = 0,
    val over90Paise: Long = 0,
) {
    val totalPaise: Long get() = currentPaise + days1to30Paise + days31to60Paise + days61to90Paise + over90Paise

    fun add(doc: OpenDoc, today: LocalDate): AgeingBuckets {
        val due = (doc.dueDate ?: doc.date).toLong()
        val late = today.toEpochDay() - due
        val amount = doc.outstandingPaise
        return when {
            late <= 0 -> copy(currentPaise = currentPaise + amount)
            late <= 30 -> copy(days1to30Paise = days1to30Paise + amount)
            late <= 60 -> copy(days31to60Paise = days31to60Paise + amount)
            late <= 90 -> copy(days61to90Paise = days61to90Paise + amount)
            else -> copy(over90Paise = over90Paise + amount)
        }
    }
}

data class CashBook(val openingPaise: Long, val inPaise: Long, val outPaise: Long) {
    val closingPaise: Long get() = openingPaise + inPaise - outPaise
}

data class PartyTotals(
    /** What parties owe (customers) / are owed (suppliers) — positive balances only. */
    val duePaise: Long,
    /** Advances held: balances in the party's favour. */
    val advancePaise: Long,
) {
    val netPaise: Long get() = duePaise - advancePaise
}

/**
 * Read side of the books. Every figure here is a SUM over the confirmed
 * postings / stock movements the engine wrote — screens, reports and Kai all
 * read these functions, so no screen ever computes a balance of its own.
 *
 *  Customer receivable = credit sales − customer payments − credit notes (+ opening)
 *  Supplier payable    = credit purchases − supplier payments − debit notes (+ opening)
 *  Stock               = all valid stock movements
 *  Cash / bank         = opening + inflows − outflows
 */
class BooksLedger(private val db: BooksDatabase, private val businessId: String) {
    private val dao = db.dao()
    private val forever = Int.MAX_VALUE

    /** Positive = the customer owes the business; negative = advance held. */
    suspend fun receivable(partyId: String): Long = dao.netDebit(businessId, Account.RECEIVABLE.name, partyId, null, forever)

    /** Positive = the business owes the supplier; negative = advance paid. */
    suspend fun payable(partyId: String): Long = -dao.netDebit(businessId, Account.PAYABLE.name, partyId, null, forever)

    suspend fun receivables(): PartyTotals = totals(dao.partyNetDebits(businessId, Account.RECEIVABLE.name).map { it.balancePaise })

    suspend fun payables(): PartyTotals = totals(dao.partyNetDebits(businessId, Account.PAYABLE.name).map { -it.balancePaise })

    private fun totals(balances: List<Long>) = PartyTotals(balances.filter { it > 0 }.sum(), -balances.filter { it < 0 }.sum())

    /** What is still due on one invoice / bill / opening balance. */
    suspend fun outstanding(txnId: String): Long {
        val txn = dao.txn(txnId) ?: return 0
        return txn.totalPaise - dao.allocatedTo(txnId)
    }

    suspend fun openInvoices(partyId: String): List<OpenDoc> = dao.openDocs(businessId, partyId, listOf(TxnType.SALE.name, TxnType.OPENING_BALANCE.name))

    suspend fun openBills(partyId: String): List<OpenDoc> = dao.openDocs(businessId, partyId, listOf(TxnType.PURCHASE.name, TxnType.OPENING_BALANCE.name))

    suspend fun receivableAgeing(today: LocalDate): AgeingBuckets =
        dao.openDocsAll(businessId, listOf(TxnType.SALE.name, TxnType.OPENING_BALANCE.name), PartyKind.CUSTOMER.name)
            .fold(AgeingBuckets()) { acc, d -> acc.add(d, today) }

    suspend fun payableAgeing(today: LocalDate): AgeingBuckets =
        dao.openDocsAll(businessId, listOf(TxnType.PURCHASE.name, TxnType.OPENING_BALANCE.name), PartyKind.SUPPLIER.name)
            .fold(AgeingBuckets()) { acc, d -> acc.add(d, today) }

    /** Balance of one cash / bank / UPI account at the end of [upTo] (default: everything). */
    suspend fun moneyBalance(moneyAccountId: String, upTo: LocalDate? = null): Long =
        dao.netDebit(businessId, Account.MONEY.name, null, moneyAccountId, upTo?.toEpochDay()?.toInt() ?: forever)

    /** Opening + in − out = closing, for one account (or all, when null). This is cash, not profit. */
    suspend fun cashBook(moneyAccountId: String?, from: LocalDate, to: LocalDate): CashBook {
        val f = from.toEpochDay().toInt()
        val t = to.toEpochDay().toInt()
        return CashBook(
            openingPaise = dao.netDebit(businessId, Account.MONEY.name, null, moneyAccountId, f - 1),
            inPaise = dao.debitsBetween(businessId, Account.MONEY.name, moneyAccountId, f, t),
            outPaise = dao.creditsBetween(businessId, Account.MONEY.name, moneyAccountId, f, t),
        )
    }

    /** Stock on hand in the product's primary unit (thousandths). */
    suspend fun stock(productId: String, warehouseId: String? = null): Long = dao.stock(businessId, productId, warehouseId).qtyMilli

    suspend fun stockValue(productId: String): Long = dao.stock(businessId, productId, null).valuePaise

    suspend fun batchStock(productId: String, batchId: String): Long = dao.batchStock(businessId, productId, batchId).qtyMilli

    suspend fun totalStockValue(): Long = dao.allStock(businessId).sumOf { it.valuePaise }

    /** Net sales (sales − sales returns, before tax) between two dates. */
    suspend fun netSales(from: LocalDate, to: LocalDate): Long {
        val f = from.toEpochDay().toInt()
        val t = to.toEpochDay().toInt()
        return dao.creditsBetween(businessId, Account.SALES.name, null, f, t) - dao.debitsBetween(businessId, Account.SALES_RETURN.name, null, f, t)
    }

    suspend fun netPurchases(from: LocalDate, to: LocalDate): Long {
        val f = from.toEpochDay().toInt()
        val t = to.toEpochDay().toInt()
        return dao.debitsBetween(businessId, Account.PURCHASES.name, null, f, t) - dao.creditsBetween(businessId, Account.PURCHASE_RETURN.name, null, f, t)
    }

    suspend fun expenses(from: LocalDate, to: LocalDate): Long =
        dao.debitsBetween(businessId, Account.EXPENSE.name, null, from.toEpochDay().toInt(), to.toEpochDay().toInt())

    suspend fun otherIncome(from: LocalDate, to: LocalDate): Long =
        dao.creditsBetween(businessId, Account.INCOME.name, null, from.toEpochDay().toInt(), to.toEpochDay().toInt())

    /** HSN-wise taxable value and tax for sales (net of credit notes) or purchases (net of debit notes). */
    suspend fun hsnSummary(sales: Boolean, from: LocalDate, to: LocalDate): List<HsnSummaryRow> {
        val f = from.toEpochDay().toInt()
        val t = to.toEpochDay().toInt()
        val (docType, returnType) = if (sales) TxnType.SALE to TxnType.SALE_RETURN else TxnType.PURCHASE to TxnType.PURCHASE_RETURN
        val gross = dao.hsnSummary(businessId, docType.name, f, t)
        val back = dao.hsnSummary(businessId, returnType.name, f, t).associateBy { it.hsnCode }
        val codes = (gross.map { it.hsnCode } + back.keys).distinct().sortedBy { it ?: "" }
        val byCode = gross.associateBy { it.hsnCode }
        return codes.map { code ->
            val g = byCode[code]
            val r = back[code]
            fun net(a: Long?, b: Long?) = (a ?: 0) - (b ?: 0)
            HsnSummaryRow(
                hsnCode = code,
                qtyMilli = net(g?.qtyMilli, r?.qtyMilli),
                taxablePaise = net(g?.taxablePaise, r?.taxablePaise),
                cgstPaise = net(g?.cgstPaise, r?.cgstPaise),
                sgstPaise = net(g?.sgstPaise, r?.sgstPaise),
                igstPaise = net(g?.igstPaise, r?.igstPaise),
                cessPaise = net(g?.cessPaise, r?.cessPaise),
            )
        }
    }

    /** Output tax − input tax (net GST payable before ITC rules), from the postings. */
    suspend fun netTaxPayable(from: LocalDate, to: LocalDate): Long {
        val f = from.toEpochDay().toInt()
        val t = to.toEpochDay().toInt()
        val output = dao.creditsBetween(businessId, Account.OUTPUT_TAX.name, null, f, t) - dao.debitsBetween(businessId, Account.OUTPUT_TAX.name, null, f, t)
        val input = dao.debitsBetween(businessId, Account.INPUT_TAX.name, null, f, t) - dao.creditsBetween(businessId, Account.INPUT_TAX.name, null, f, t)
        return output - input
    }
}
