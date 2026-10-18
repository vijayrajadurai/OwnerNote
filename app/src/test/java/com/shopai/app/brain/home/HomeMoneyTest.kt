package com.shopai.app.brain.home

import com.shopai.app.data.model.PartySummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The home's Vaanganum / Kudukkanum (owner, 18 Oct 2026): late, today and later split from the books' own dues — nothing made up. */
class HomeMoneyTest {
    private val today = LocalDate.of(2026, 10, 18)

    @Test fun lateTodayLaterAndTheOrder() {
        val side = HomeMoney.side(listOf(
            PartySummary("c1", "Kumar", "9840012345", 4000.0, "2026-10-18"),
            PartySummary("c2", "Selvam", null, 6000.0, "2026-09-20"),
            PartySummary("c3", "Ramesh", null, 2500.0, "2026-10-18T00:00:00Z"),
            PartySummary("c4", "Lakshmi", null, 1000.0, "2026-10-22"),
            PartySummary("c5", "Murali", null, 700.0, null),
            PartySummary("c6", "Paid up", null, 0.0, "2026-10-01"),
        ), today)
        assertEquals(14200.0, side.total, 0.001)
        assertEquals(6000.0, side.late, 0.001)
        assertEquals(1700.0, side.later, 0.001)
        assertEquals(5, side.count)
        // Late first (the longest late first), then today, then later — the nearest first; no due date is later, never late.
        assertEquals(listOf("Selvam", "Kumar", "Ramesh", "Lakshmi", "Murali"), side.parties.map { it.name })
        assertEquals(28, side.parties.first().lateDays)
        assertEquals(DueWhen.LATER, side.parties.last().whenDue)
    }

    @Test fun initials() {
        assertEquals("K", HomeMoney.initials("Kumar"))
        assertEquals("AT", HomeMoney.initials("ABC Traders"))
        assertEquals("கு", HomeMoney.initials("குமார்"))
        assertEquals("?", HomeMoney.initials("  "))
    }
}
