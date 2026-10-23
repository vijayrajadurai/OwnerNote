package com.shopai.app.util

import com.shopai.app.data.model.PartySummary
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Where a customer / supplier sits on the list (owner, 23 Oct 2026): late first, then due this week, later, cleared. */
enum class PartyBucket { LATE, THIS_WEEK, LATER, CLEARED }

/** One row: the party, its bucket, and how many days late (LATE) or left (THIS_WEEK / LATER with a date). */
data class PartyRow(val party: PartySummary, val bucket: PartyBucket, val days: Int?)

fun partyRow(party: PartySummary, today: LocalDate): PartyRow {
    if (party.pendingTotal <= 0.005) return PartyRow(party, PartyBucket.CLEARED, null)
    val due = parseIsoToLocalDate(party.nextDueDate) ?: return PartyRow(party, PartyBucket.LATER, null)
    val days = ChronoUnit.DAYS.between(today, due).toInt()
    return when {
        days < 0 -> PartyRow(party, PartyBucket.LATE, -days)
        days <= 7 -> PartyRow(party, PartyBucket.THIS_WEEK, days)
        else -> PartyRow(party, PartyBucket.LATER, days)
    }
}

/** The list in buckets, in order; inside a bucket the latest-late first, then the nearest due date, then by name. */
fun groupPartyRows(parties: List<PartySummary>, today: LocalDate): List<Pair<PartyBucket, List<PartyRow>>> =
    parties.map { partyRow(it, today) }
        .groupBy { it.bucket }
        .toSortedMap()
        .map { (bucket, rows) ->
            bucket to when (bucket) {
                PartyBucket.LATE -> rows.sortedWith(compareByDescending<PartyRow> { it.days }.thenBy { it.party.name.lowercase() })
                PartyBucket.CLEARED -> rows.sortedBy { it.party.name.lowercase() }
                else -> rows.sortedWith(compareBy<PartyRow, Int?>(nullsLast()) { it.days }.thenBy { it.party.name.lowercase() })
            }
        }

/** Search by name (any part, any case) or by phone digits. A blank search matches everyone. */
fun partyMatches(party: PartySummary, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (party.name.contains(q, ignoreCase = true)) return true
    val digits = q.filter { it.isDigit() }
    return digits.length >= 3 && party.phone.orEmpty().filter { it.isDigit() }.contains(digits)
}

/** The total card on top: what is still open, from how many, and how much of it is late. */
data class PartyTotals(val pending: Double, val people: Int, val late: Double, val lateCount: Int) {
    val onTime: Double get() = pending - late
}

fun partyTotals(parties: List<PartySummary>, today: LocalDate): PartyTotals {
    val rows = parties.map { partyRow(it, today) }.filter { it.bucket != PartyBucket.CLEARED }
    val late = rows.filter { it.bucket == PartyBucket.LATE }
    return PartyTotals(rows.sumOf { it.party.pendingTotal }, rows.size, late.sumOf { it.party.pendingTotal }, late.size)
}
