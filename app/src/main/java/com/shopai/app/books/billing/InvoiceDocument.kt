package com.shopai.app.books.billing

import com.shopai.app.books.data.AppliedPayment
import com.shopai.app.books.data.BusinessEntity
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.data.TxnEntity
import com.shopai.app.books.data.TxnItemEntity
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.TxnType

/**
 * Everything printed on an invoice / bill / note / receipt, read from the books:
 * the posted document, its lines, the money applied to it and what is still due.
 * Nothing is recalculated here.
 */
data class InvoiceDocument(
    val business: BusinessEntity,
    val txn: TxnEntity,
    val party: PartyEntity?,
    val items: List<TxnItemEntity>,
    val applied: List<AppliedPayment>,
    /** Payments applied (not credit/debit notes). */
    val paidPaise: Long,
    /** Credit / debit notes applied. */
    val returnedPaise: Long,
    val balancePaise: Long,
    val original: TxnEntity?,
    val moneyAccountName: String?,
) {
    val type: TxnType get() = TxnType.valueOf(txn.type)
    val isVoid: Boolean get() = txn.status == "VOID"

    val title: String get() = when (type) {
        TxnType.SALE -> if (business.gstin != null) "TAX INVOICE" else "INVOICE"
        TxnType.PURCHASE -> "PURCHASE BILL"
        TxnType.SALE_RETURN -> "CREDIT NOTE"
        TxnType.PURCHASE_RETURN -> "DEBIT NOTE"
        TxnType.PAYMENT_IN -> "PAYMENT RECEIPT"
        TxnType.PAYMENT_OUT -> "PAYMENT VOUCHER"
        else -> type.name.replace('_', ' ')
    }

    val hasItems: Boolean get() = items.isNotEmpty()

    companion object {
        suspend fun load(s: BooksSession, txnId: String): InvoiceDocument? {
            val dao = s.dao
            val txn = dao.txn(txnId)?.takeIf { it.businessId == s.ctx.businessId } ?: return null
            val business = dao.business(txn.businessId) ?: return null
            val applied = dao.appliedTo(txn.id)
            val notes = setOf(TxnType.SALE_RETURN.name, TxnType.PURCHASE_RETURN.name)
            return InvoiceDocument(
                business = business,
                txn = txn,
                party = txn.partyId?.let { dao.party(it) },
                items = dao.items(txn.id),
                applied = applied,
                paidPaise = applied.filter { it.type !in notes }.sumOf { it.amountPaise },
                returnedPaise = applied.filter { it.type in notes }.sumOf { it.amountPaise },
                balancePaise = if (txn.status == "VOID") 0 else s.ledger.outstanding(txn.id),
                original = txn.linkedTxnId?.let { dao.txn(it) },
                moneyAccountName = txn.moneyAccountId?.let { dao.moneyAccount(it)?.name },
            )
        }
    }
}

/** Indian-system amount in words: "Rupees One Lakh Twenty Thousand and Fifty Paise Only". */
object RupeesInWords {
    private val ones = listOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
        "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen",
    )
    private val tens = listOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

    fun of(paise: Long): String {
        val negative = paise < 0
        val abs = kotlin.math.abs(paise)
        val rupees = abs / 100
        val p = (abs % 100).toInt()
        val words = buildString {
            append("Rupees ")
            append(if (rupees == 0L) "Zero" else words(rupees))
            if (p > 0) append(" and ").append(below100(p)).append(" Paise")
            append(" Only")
        }
        return if (negative) "Minus $words" else words
    }

    private fun below100(n: Int): String = if (n < 20) ones[n] else listOf(tens[n / 10], ones[n % 10]).filter { it.isNotEmpty() }.joinToString(" ")

    private fun below1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        return listOfNotNull(if (h > 0) "${ones[h]} Hundred" else null, if (r > 0) below100(r) else null).joinToString(" ")
    }

    private fun words(n: Long): String {
        val crore = n / 1_00_00_000
        val rest = n % 1_00_00_000
        val parts = mutableListOf<String>()
        if (crore > 0) parts += "${words(crore)} Crore"
        val lakh = (rest / 1_00_000).toInt()
        val thousand = ((rest % 1_00_000) / 1000).toInt()
        val hundreds = (rest % 1000).toInt()
        if (lakh > 0) parts += "${below100(lakh)} Lakh"
        if (thousand > 0) parts += "${below100(thousand)} Thousand"
        if (hundreds > 0) parts += below1000(hundreds)
        return parts.joinToString(" ")
    }
}
