package com.shopai.app.brain.morning

/**
 * Who Morning Work is for: the AUTHENTICATED login (books session user /
 * backend login), never an id the UI passes in. Business data is shared by
 * everyone allowed into the business; the owner's routine, memory and morning
 * notification belong to [ownerId] alone.
 */
data class MorningOwner(val businessId: String, val ownerId: String?) {
    /** One key per owner per business (settings, the scheduled notification). */
    val key: String get() = businessId + "~" + (ownerId ?: "-")
}

/** Business-wide totals for the summary, read with aggregate queries (the lists themselves are bounded). */
data class MorningTotals(
    /** Customers' money due today or overdue (the same open-bill figures the ledger shows). */
    val collectionsDue: Double,
    /** Suppliers' money due today or overdue. */
    val paymentsDue: Double,
    /** How many things need attention in all (for "N more" on the Morning Work screen). */
    val candidates: Int,
)

/**
 * Morning Work's reads, each one bounded (WHERE / ORDER BY / LIMIT in the
 * database). Every figure comes from the existing source of truth — the open
 * bills and their allocations (the same rows the customer / supplier screens
 * use), the stock movements, the batches, the drafts. Nothing here computes a
 * balance of its own and nothing is written.
 */
interface MorningQueries {
    /**
     * Parties of [kind] with work today: earliest open bill due on or before
     * [untilDay] — and customers who paid part of a bill (a follow-up) — earliest
     * due first, then the bigger amount (MorningAnalyzer's order).
     */
    suspend fun dueParties(kind: MorningPartyKind, untilDay: Long, limit: Int): List<MorningParty>

    /** Products out of stock or below their minimum / reorder level (out of stock first). */
    suspend fun lowStock(limit: Int): List<MorningProduct>

    /** Batches with stock that expire on or before [untilDay], earliest first. */
    suspend fun expiring(untilDay: Long, limit: Int): List<MorningBatch>

    /** Drafts waiting for the owner, newest first. */
    suspend fun drafts(limit: Int): List<MorningDraft>

    suspend fun totals(today: Long): MorningTotals
}

/**
 * Builds the morning snapshot from bounded queries: at most [limit] rows per
 * kind of work — enough for the day's list (the engine keeps [MorningWorkEngine.MAX_TASKS])
 * because each query returns its rows in the analyzer's own order.
 * A fixed number of queries, whatever the size of the business.
 */
class MorningSnapshotLoader(private val limit: Int = MorningWorkEngine.MAX_TASKS) {
    suspend fun load(
        owner: MorningOwner,
        queries: MorningQueries,
        today: Long,
        reminders: List<MorningReminder>,
        routine: List<MorningSection>?,
        offline: Boolean,
        syncedAtMillis: Long,
    ): MorningSnapshot {
        val soon = today + MorningAnalyzer.UPCOMING_DAYS
        val parties = (queries.dueParties(MorningPartyKind.CUSTOMER, soon, limit) + queries.dueParties(MorningPartyKind.SUPPLIER, soon, limit))
            .distinctBy { it.id }
        return MorningSnapshot(
            businessId = owner.businessId,
            parties = parties,
            products = queries.lowStock(limit),
            batches = queries.expiring(today + MorningAnalyzer.EXPIRY_DAYS, limit),
            reminders = reminders,
            drafts = queries.drafts(limit),
            offline = offline,
            syncedAtMillis = syncedAtMillis,
            ownerId = owner.ownerId,
            routine = routine,
            totals = queries.totals(today),
        )
    }
}
