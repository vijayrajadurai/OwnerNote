package com.shopai.app.brain.home

import com.shopai.app.data.model.PartySummary
import com.shopai.app.util.parseIsoToLocalDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** When a party's money is due, against today. */
enum class DueWhen { LATE, TODAY, LATER }

/** One person / shop on the home screen: who, how much, and when it is due. */
data class HomeParty(
    val id: String,
    val name: String,
    val phone: String?,
    val amount: Double,
    val due: LocalDate?,
    val whenDue: DueWhen,
    /** Days late (LATE), else 0. */
    val lateDays: Int,
)

/**
 * One side of the books for the home screen (owner, 18 Oct 2026: the "Kadai Bold" home): the total, how many, how much is late,
 * due today and later, and the people themselves — late first, the longest late first; then today; then later, the nearest
 * first. A party with nothing pending is left out; a party with no due date counts as later (never made "late").
 */
data class HomeSide(
    val total: Double,
    val late: Double,
    val today: Double,
    val later: Double,
    val parties: List<HomeParty>,
) {
    val count: Int get() = parties.size
    val lateParties: List<HomeParty> get() = parties.filter { it.whenDue == DueWhen.LATE }
    val todayParties: List<HomeParty> get() = parties.filter { it.whenDue == DueWhen.TODAY }
    val laterParties: List<HomeParty> get() = parties.filter { it.whenDue == DueWhen.LATER }
}

object HomeMoney {
    fun side(parties: List<PartySummary>, today: LocalDate): HomeSide {
        val rows = parties.filter { it.pendingTotal > 0.004 }.map { p ->
            val due = parseIsoToLocalDate(p.nextDueDate)
            val whenDue = when {
                due == null -> DueWhen.LATER
                due.isBefore(today) -> DueWhen.LATE
                due.isEqual(today) -> DueWhen.TODAY
                else -> DueWhen.LATER
            }
            HomeParty(p.id, p.name, p.phone, p.pendingTotal, due, whenDue,
                if (whenDue == DueWhen.LATE) ChronoUnit.DAYS.between(due, today).toInt() else 0)
        }.sortedWith(compareBy<HomeParty>({ it.whenDue.ordinal }, { if (it.whenDue == DueWhen.LATE) -it.lateDays else 0 },
            { it.due ?: LocalDate.MAX }, { -it.amount }))
        fun sum(w: DueWhen) = rows.filter { it.whenDue == w }.sumOf { it.amount }
        return HomeSide(rows.sumOf { it.amount }, sum(DueWhen.LATE), sum(DueWhen.TODAY), sum(DueWhen.LATER), rows)
    }

    /** "Kumar" → "K", "ABC Traders" → "AT", "குமார்" → "கு". */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        fun first(w: String): String {
            val head = String(Character.toChars(w.codePointAt(0)))
            val marks = w.drop(head.length).takeWhile {
                val t = Character.getType(it)
                t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
            }
            return (head + marks).uppercase()
        }
        return when (words.size) {
            0 -> "?"
            1 -> first(words[0])
            else -> first(words[0]) + first(words[1])
        }
    }
}
