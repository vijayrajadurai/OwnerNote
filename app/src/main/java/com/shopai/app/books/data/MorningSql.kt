package com.shopai.app.books.data

/**
 * Morning Work's bounded reads (used by [BooksDao]; plain constants so the
 * exact SQL is also run by the JVM performance test on a real SQLite).
 *
 * An "open bill" is exactly the rows of [BooksDao.openDocsAll] — a CONFIRMED
 * document of the party's kind whose total is more than its active
 * allocations — so a party's pending amount and next due day are the same
 * figures the customer / supplier screens show (LegacyBridge sums the same
 * rows). The database filters, groups, orders and limits; the app gets only
 * the rows it shows.
 */
object MorningSql {
    private const val ALLOCATED = "COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.toTxnId = t.id AND a.active = 1), 0)"

    /** Active allocations per document, summed once (the same sum openDocsAll takes per row). */
    private const val PAID = "(SELECT a.toTxnId AS txnId, SUM(a.amountPaise) AS paid FROM allocations a WHERE a.active = 1 GROUP BY a.toTxnId)"

    private const val OPEN_BILLS =
        """SELECT t.partyId AS partyId, t.totalPaise AS totalPaise, COALESCE(x.paid, 0) AS allocatedPaise, COALESCE(t.dueDate, t.date) AS dueDay
           FROM txns t JOIN parties p ON p.id = t.partyId LEFT JOIN $PAID x ON x.txnId = t.id
           WHERE t.businessId = :businessId AND t.status = 'CONFIRMED' AND t.partyId IS NOT NULL
             AND t.type IN (:types) AND t.isAdvance = 0
             AND p.businessId = :businessId AND p.kind = :partyKind AND p.active = 1
             AND t.totalPaise > COALESCE(x.paid, 0)"""

    private const val PER_PARTY =
        """SELECT o.partyId AS partyId, SUM(o.totalPaise - o.allocatedPaise) AS outstandingPaise, MIN(o.dueDay) AS nextDue,
                  COUNT(*) AS openBills, SUM(CASE WHEN o.allocatedPaise > 0 THEN 1 ELSE 0 END) AS partBills
           FROM ($OPEN_BILLS) o GROUP BY o.partyId"""

    /**
     * Parties with work this morning: earliest open bill due on or before
     * :untilDay, or (:withPartPaid = 1, customers) a part-paid bill — in
     * MorningAnalyzer's order (earliest due, then the bigger amount), so the
     * first :limit rows are exactly the top of the day's list.
     */
    const val DUE_PARTIES =
        """SELECT d.partyId, d.outstandingPaise, d.nextDue, d.openBills, d.partBills FROM ($PER_PARTY) d
           WHERE d.nextDue <= :untilDay OR (:withPartPaid = 1 AND d.partBills > 0)
           ORDER BY d.nextDue, d.outstandingPaise DESC, d.partyId LIMIT :limit"""

    /**
     * One pass for the summary: money due today or overdue (and from how many
     * parties), parties due by :untilDay, part-paid parties due later.
     */
    const val PARTY_SUMMARY =
        """SELECT COALESCE(SUM(CASE WHEN d.nextDue <= :today THEN d.outstandingPaise ELSE 0 END), 0) AS duePaise,
                  COALESCE(SUM(CASE WHEN d.nextDue <= :today THEN 1 ELSE 0 END), 0) AS dueParties,
                  COALESCE(SUM(CASE WHEN d.nextDue <= :untilDay THEN 1 ELSE 0 END), 0) AS soonParties,
                  COALESCE(SUM(CASE WHEN d.nextDue > :untilDay AND d.partBills > 0 THEN 1 ELSE 0 END), 0) AS partPaidLater
           FROM ($PER_PARTY) d"""

    /** The open bills of a few parties (for the partial-payment facts on their task). */
    const val OPEN_BILLS_OF =
        """SELECT t.id, t.partyId, t.type, t.number, t.date, t.dueDate, t.totalPaise, $ALLOCATED AS allocatedPaise
           FROM txns t
           WHERE t.businessId = :businessId AND t.partyId IN (:partyIds) AND t.status = 'CONFIRMED'
             AND t.type IN (:types) AND t.isAdvance = 0
             AND t.totalPaise > $ALLOCATED
           ORDER BY COALESCE(t.dueDate, t.date), t.date"""

    private const val STOCKED_PRODUCTS =
        """SELECT p.id AS id, p.name AS name, p.primaryUnit AS unit, p.minStockMilli AS minStockMilli, p.reorderLevelMilli AS reorderLevelMilli,
                  COALESCE((SELECT SUM(m.qtyMilli) FROM stock_movements m WHERE m.businessId = p.businessId AND m.productId = p.id AND m.active = 1), 0) AS qtyMilli
           FROM products p
           WHERE p.businessId = :businessId AND p.archived = 0 AND p.active = 1 AND p.isService = 0
             AND (COALESCE(p.reorderLevelMilli, 0) > 0 OR COALESCE(p.minStockMilli, 0) > 0)"""

    private const val LOW =
        """(s.qtyMilli <= 0 OR (COALESCE(s.reorderLevelMilli, 0) > 0 AND s.qtyMilli < s.reorderLevelMilli)
             OR (COALESCE(s.minStockMilli, 0) > 0 AND s.qtyMilli < s.minStockMilli))"""

    /** Products out of stock or below their level (only products that have a level) — out of stock first. */
    const val LOW_STOCK =
        """SELECT s.id, s.name, s.unit, s.minStockMilli, s.reorderLevelMilli, s.qtyMilli FROM ($STOCKED_PRODUCTS) s
           WHERE $LOW
           ORDER BY CASE WHEN s.qtyMilli <= 0 THEN 0 ELSE 1 END, LOWER(s.name), s.id LIMIT :limit"""

    const val LOW_STOCK_COUNT = """SELECT COUNT(*) FROM ($STOCKED_PRODUCTS) s WHERE $LOW"""

    private const val EXPIRING_STOCKED =
        """SELECT b.id AS id, b.productId AS productId, p.name AS productName, p.primaryUnit AS unit, b.batchNo AS batchNo, b.expiryDay AS expiryDay,
                  COALESCE((SELECT SUM(m.qtyMilli) FROM stock_movements m
                            WHERE m.businessId = b.businessId AND m.productId = b.productId AND m.batchId = b.id AND m.active = 1), 0) AS qtyMilli
           FROM batches b JOIN products p ON p.id = b.productId
           WHERE b.businessId = :businessId AND b.expiryDay IS NOT NULL AND b.expiryDay <= :untilDay
             AND p.archived = 0 AND p.active = 1"""

    /** Batches that still have stock and expire on or before :untilDay — earliest first. */
    const val EXPIRING =
        """SELECT e.id, e.productId, e.productName, e.unit, e.batchNo, e.expiryDay, e.qtyMilli FROM ($EXPIRING_STOCKED) e
           WHERE e.qtyMilli > 0 ORDER BY e.expiryDay, e.id LIMIT :limit"""

    const val EXPIRING_COUNT = """SELECT COUNT(*) FROM ($EXPIRING_STOCKED) e WHERE e.qtyMilli > 0"""

    const val OPEN_DRAFTS = """SELECT * FROM drafts WHERE businessId = :businessId AND status = 'OPEN' ORDER BY createdAt DESC LIMIT :limit"""

    const val OPEN_DRAFTS_COUNT = """SELECT COUNT(*) FROM drafts WHERE businessId = :businessId AND status = 'OPEN'"""

    const val PARTIES_BY_ID = """SELECT * FROM parties WHERE businessId = :businessId AND id IN (:ids)"""
}

/** A party's open-bill figures, aggregated by the database. */
data class MorningPartyDue(
    val partyId: String,
    val outstandingPaise: Long,
    val nextDue: Int,
    val openBills: Int,
    val partBills: Int,
)

data class MorningPartySummary(val duePaise: Long, val dueParties: Int, val soonParties: Int, val partPaidLater: Int)

data class MorningLowStock(
    val id: String,
    val name: String,
    val unit: String,
    val minStockMilli: Long?,
    val reorderLevelMilli: Long?,
    val qtyMilli: Long,
)

data class MorningExpiringBatch(
    val id: String,
    val productId: String,
    val productName: String,
    val unit: String,
    val batchNo: String,
    val expiryDay: Int,
    val qtyMilli: Long,
)
