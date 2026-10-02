package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.Direction
import com.shopai.app.brain.KaiBrain
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.data.model.DailyCashDayStatus
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.repository.DailyCashRepository
import com.shopai.app.data.repository.PartyRepository
import com.shopai.app.util.computeDailyCashTotals
import com.shopai.app.util.localDateKey
import java.time.LocalDate

/**
 * Kai Chat's books over the app's existing data — nothing new is stored
 * and nothing is recalculated that the backend already calculates:
 *  - customers, suppliers and their balances: the shared Business Memory
 *    (backend ledger, via [KaiBrain.memory]);
 *  - a party's entries and payments: the existing party details;
 *  - money in / out: the Daily Cash Note kept on the phone.
 * Only the owner's own backend and phone database are read — no AI service.
 */
class RepositoryKaiBooks(
    private val memory: KaiBrain,
    private val parties: PartyRepository,
    private val dailyCash: DailyCashRepository,
) : KaiBooks {

    private val histories = HashMap<String, Pair<Long, PartyHistory>>()

    override suspend fun snapshot(): BusinessSnapshot? = runCatching { memory.memory() }.getOrNull()

    override suspend fun history(party: PartyFacts): PartyHistory? {
        val key = "${party.side}:${party.id}"
        histories[key]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second }
        val loaded = runCatching {
            if (party.side == Direction.RECEIVABLE) PartyHistory.ofCustomer(party, parties.getCustomer(party.id).transactions)
            else PartyHistory.ofSupplier(party, parties.getSupplier(party.id).transactions)
        }.getOrNull() ?: return null
        histories[key] = System.currentTimeMillis() to loaded
        return loaded
    }

    override suspend fun cashBook(from: LocalDate, to: LocalDate): CashBookTotals? = runCatching {
        var totalIn = 0.0
        var totalOut = 0.0
        var entries = 0
        var day = from
        var days = 0
        // At most ~2 months of days (a month question reads ≤ 31 local lookups).
        while (!day.isAfter(to) && days < 62) {
            for (e in dailyCash.listDailyCashEntries(localDateKey(day))) {
                entries++
                if (e.type == DailyCashEntryType.IN) totalIn += e.amount else totalOut += e.amount
            }
            day = day.plusDays(1)
            days++
        }
        CashBookTotals(totalIn, totalOut, entries)
    }.getOrNull()

    /**
     * One day of the Daily Cash Note, read only, from the app's own figures:
     * the entries (listDailyCashEntries), their totals (the existing
     * computeDailyCashTotals), the Kallapetti opening and current amount
     * (the existing cash-box snapshot) and the day-close status. No entry,
     * opening, balance, report or day-close is ever created or changed here.
     */
    override suspend fun cashNote(day: LocalDate): CashNoteView? = runCatching {
        val key = localDateKey(day)
        val entries = dailyCash.listDailyCashEntries(key)
        val box = dailyCash.getCashBoxSnapshot(key)
        CashNoteView(
            date = day,
            entries = entries.size,
            totals = computeDailyCashTotals(entries),
            opening = box?.opening,
            kallapetti = box?.current,
            dayClosed = dailyCash.getDayStatus(key) == DailyCashDayStatus.SUBMITTED,
        )
    }.getOrNull()

    /** New entries were saved: re-read histories next time. */
    fun forget() {
        histories.clear()
        memory.forget()
    }
}
