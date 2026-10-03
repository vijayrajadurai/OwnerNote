package com.shopai.app.data.morning

import com.shopai.app.books.data.BooksDao
import com.shopai.app.books.data.MorningPartyDue
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TxnType
import com.shopai.app.brain.morning.MorningBatch
import com.shopai.app.brain.morning.MorningDoc
import com.shopai.app.brain.morning.MorningDraft
import com.shopai.app.brain.morning.MorningParty
import com.shopai.app.brain.morning.MorningPartyKind
import com.shopai.app.brain.morning.MorningProduct
import com.shopai.app.brain.morning.MorningQueries
import com.shopai.app.brain.morning.MorningTotals

/**
 * [MorningQueries] over the books (Room): every read is one bounded SQL query
 * ([com.shopai.app.books.data.MorningSql]) for the signed-in business only.
 * Figures are converted exactly as the existing screens do (paise → rupees,
 * milli-units → quantity); no balance is calculated here.
 */
class BooksMorningQueries(private val dao: BooksDao, private val businessId: String) : MorningQueries {
    private fun kindOf(k: MorningPartyKind) = if (k == MorningPartyKind.CUSTOMER) PartyKind.CUSTOMER else PartyKind.SUPPLIER

    /** The same document types the customer / supplier summaries use. */
    private fun types(k: MorningPartyKind) = if (k == MorningPartyKind.CUSTOMER) {
        listOf(TxnType.SALE.name, TxnType.OPENING_BALANCE.name)
    } else {
        listOf(TxnType.PURCHASE.name, TxnType.OPENING_BALANCE.name)
    }

    override suspend fun dueParties(kind: MorningPartyKind, untilDay: Long, limit: Int): List<MorningParty> {
        // Customers who paid part of a bill are a follow-up whatever the due date (suppliers are not).
        val withPartPaid = if (kind == MorningPartyKind.CUSTOMER) 1 else 0
        return parties(kind, dao.morningDueParties(businessId, types(kind), kindOf(kind).name, untilDay.toInt(), withPartPaid, limit))
    }

    /** Names / phones and the open bills of just these parties (two queries, however many parties the business has). */
    private suspend fun parties(kind: MorningPartyKind, dues: List<MorningPartyDue>): List<MorningParty> {
        if (dues.isEmpty()) return emptyList()
        val ids = dues.map { it.partyId }
        val rows = dao.morningPartiesById(businessId, ids).associateBy { it.id }
        val bills = dao.morningOpenBillsOf(businessId, ids, types(kind)).groupBy { it.partyId }
        return dues.mapNotNull { d ->
            val p = rows[d.partyId] ?: return@mapNotNull null
            MorningParty(
                id = p.id,
                name = p.name,
                kind = kind,
                phone = p.mobile?.let { "+91$it" },
                pending = Money.toRupees(d.outstandingPaise).toDouble(),
                nextDueDay = d.nextDue.toLong(),
                docs = bills[p.id].orEmpty().map { b ->
                    MorningDoc(
                        id = b.id,
                        number = b.number,
                        total = Money.toRupees(b.totalPaise).toDouble(),
                        paid = Money.toRupees(b.allocatedPaise).toDouble(),
                        dueDay = b.dueDate?.toLong(),
                        dateDay = b.date.toLong(),
                    )
                },
            )
        }
    }

    override suspend fun lowStock(limit: Int): List<MorningProduct> = dao.morningLowStock(businessId, limit).map { p ->
        MorningProduct(
            id = p.id,
            name = p.name,
            unit = p.unit,
            stock = Qty.toDecimal(p.qtyMilli).toDouble(),
            minimum = p.minStockMilli?.let { Qty.toDecimal(it).toDouble() },
            reorderLevel = p.reorderLevelMilli?.let { Qty.toDecimal(it).toDouble() },
        )
    }

    override suspend fun expiring(untilDay: Long, limit: Int): List<MorningBatch> = dao.morningExpiring(businessId, untilDay.toInt(), limit).map { b ->
        MorningBatch(b.id, b.productId, b.productName, b.batchNo, b.expiryDay.toLong(), Qty.toDecimal(b.qtyMilli).toDouble(), b.unit)
    }

    override suspend fun drafts(limit: Int): List<MorningDraft> =
        dao.morningOpenDrafts(businessId, limit).map { MorningDraft(it.id, it.kind, it.createdAt) }

    override suspend fun totals(today: Long): MorningTotals {
        val c = MorningPartyKind.CUSTOMER
        val s = MorningPartyKind.SUPPLIER
        val soon = (today + com.shopai.app.brain.morning.MorningAnalyzer.UPCOMING_DAYS).toInt()
        val customers = dao.morningPartySummary(businessId, types(c), kindOf(c).name, today.toInt(), soon)
        val suppliers = dao.morningPartySummary(businessId, types(s), kindOf(s).name, today.toInt(), soon)
        val expiryUntil = (today + com.shopai.app.brain.morning.MorningAnalyzer.EXPIRY_DAYS).toInt()
        return MorningTotals(
            collectionsDue = Money.toRupees(customers.duePaise).toDouble(),
            paymentsDue = Money.toRupees(suppliers.duePaise).toDouble(),
            // Suppliers have no part-paid follow-up task (MorningAnalyzer), so only customers' count.
            candidates = customers.soonParties + customers.partPaidLater + suppliers.soonParties + dao.morningLowStockCount(businessId) +
                dao.morningExpiringCount(businessId, expiryUntil) + dao.morningOpenDraftsCount(businessId),
        )
    }
}
