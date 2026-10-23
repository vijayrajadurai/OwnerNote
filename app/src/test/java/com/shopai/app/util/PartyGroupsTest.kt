package com.shopai.app.util

import com.shopai.app.data.model.PartySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Owner, 23 Oct 2026: Customers / Suppliers list late first, with how many days late, a search, and the total on top. */
class PartyGroupsTest {
    private val today = LocalDate.of(2026, 10, 23)
    private fun p(name: String, amount: Double, due: String?, phone: String? = null) = PartySummary(name, name, phone, amount, due)

    private val list = listOf(
        p("அபி", 1000.0, "2026-09-28"),
        p("lokesh", 2000.0, "2026-09-30", "98400 12345"),
        p("செல்வம்", 3000.0, "2026-09-29"),
        p("Kumar", 800.0, "2026-10-26"),
        p("Ramesh", 1200.0, "2026-11-20"),
        p("Mani", 500.0, null),
        p("Old", 0.0, "2026-09-01"),
    )

    @Test
    fun lateFirstWithDays() {
        val groups = groupPartyRows(list, today)
        assertEquals(listOf(PartyBucket.LATE, PartyBucket.THIS_WEEK, PartyBucket.LATER, PartyBucket.CLEARED), groups.map { it.first })
        val late = groups.first().second
        assertEquals(listOf("அபி", "செல்வம்", "lokesh"), late.map { it.party.name })
        assertEquals(listOf(25, 24, 23), late.map { it.days })
        assertEquals(3, groups[1].second.single().days)
        // A date far off first, no date at the end of "later".
        assertEquals(listOf("Ramesh", "Mani"), groups[2].second.map { it.party.name })
        assertEquals("Old", groups[3].second.single().party.name)
    }

    @Test
    fun dueTodayIsThisWeekNotLate() {
        assertEquals(PartyBucket.THIS_WEEK, partyRow(p("A", 10.0, "2026-10-23"), today).bucket)
        assertEquals(PartyBucket.LATE, partyRow(p("A", 10.0, "2026-10-22"), today).bucket)
        assertEquals(PartyBucket.LATER, partyRow(p("A", 10.0, "2026-10-31"), today).bucket)
    }

    @Test
    fun searchByNameOrPhone() {
        val lokesh = list[1]
        assertTrue(partyMatches(lokesh, "LOK"))
        assertTrue(partyMatches(lokesh, "12345"))
        assertTrue(partyMatches(list[2], "செல்"))
        assertTrue(partyMatches(lokesh, "  "))
        // Two digits are too few to search phones; a name that is not there finds nobody.
        assertFalse(partyMatches(lokesh, "12"))
        assertFalse(partyMatches(lokesh, "ganesh"))
    }

    @Test
    fun totalsLeaveOutCleared() {
        val t = partyTotals(list, today)
        assertEquals(8500.0, t.pending, 0.001)
        assertEquals(6, t.people)
        assertEquals(6000.0, t.late, 0.001)
        assertEquals(3, t.lateCount)
        assertEquals(2500.0, t.onTime, 0.001)
    }
}
