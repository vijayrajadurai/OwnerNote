package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/** Natural-language time, resolved against a fixed "now" (Saturday 3 Oct 2026, 4:20 PM). */
class KaiTimeTest {
    private val now = LocalDateTime.of(2026, 10, 3, 16, 20)
    private fun at(text: String) = KaiTime.parse(text, now) ?: error("no time in: $text")

    @Test
    fun relativeTimes() {
        assertEquals(now.plusMinutes(10), at("Kumar ku 10 minutes kalichi call pannanum").at)
        assertEquals(now.plusMinutes(10), at("10 minutes later remind me").at)
        assertEquals(now.plusHours(1), at("1 hour later").at)
        assertEquals(now.plusMinutes(30), at("half an hour la").at)
        assertEquals(now.plusMinutes(30), at("after 30 minutes remind me to check the shop").at)
        assertEquals(now.plusDays(2), at("2 naal kalichu").at)
        assertEquals(now.plusMinutes(15), at("15 நிமிடம் கழிச்சு").at)
    }

    @Test
    fun dayAndClock() {
        assertEquals(LocalDateTime.of(2026, 10, 4, 17, 0), at("tomorrow 5 PM").at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), at("naalaikku kaalaila 10 maniku").at)
        val morning = at("tomorrow morning")
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 0), morning.at)
        assertTrue(morning.timeAssumed)
        assertEquals(LocalDateTime.of(2026, 10, 3, 20, 0), at("tonight").at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 14, 0), at("after lunch").at)
        // "5 maniku" with no AM/PM: evening (said back to the owner).
        val five = at("5 maniku")
        assertEquals(LocalDateTime.of(2026, 10, 3, 17, 0), five.at)
        assertTrue(five.amPmAssumed)
        assertEquals(LocalDateTime.of(2026, 10, 3, 18, 30), at("6:30 pm").at)
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 0), at("next week").at)
        assertEquals(LocalDateTime.of(2026, 10, 9, 9, 0), at("Friday").at)
    }

    @Test
    fun aTimeAlreadyPassedTodayIsFlagged() {
        val t = at("inniku 9 maniku")
        assertTrue(t.alreadyPassed)
        // Without "today" it is simply the next 9 AM.
        assertFalse(at("9 maniku").alreadyPassed)
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 0), at("9 maniku").at)
    }

    @Test
    fun recurring() {
        val daily = at("Daily kaalaila 10 maniku kadaiku pogumbothu saaviya marakkama eduthutu po")
        assertEquals(Repeat.DAILY, daily.repeat)
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), daily.at)
        val everyMonday = at("Remind me every Monday to check supplier payments")
        assertEquals(Repeat.WEEKLY, everyMonday.repeat)
        assertEquals(DayOfWeek.MONDAY, everyMonday.weekday)
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), everyMonday.at)
        assertEquals(Repeat.WEEKLY, at("every Friday 6 pm").repeat)
        assertEquals(LocalDateTime.of(2026, 10, 9, 18, 0), at("every Friday 6 pm").at)
    }

    @Test
    fun noTime() {
        assertNull(KaiTime.parse("Kumar ku call panna remind pannu", now))
        assertNull(KaiTime.parse("Ramesh ku 5000 kuduthen", now))
    }

    @Test
    fun stripLeavesTheTask() {
        assertEquals("kadaiku pogumbothu saaviya marakkama eduthutu po",
            KaiTime.strip("Daily kaalaila 10 maniku kadaiku pogumbothu saaviya marakkama eduthutu po"))
        assertEquals("Kumar ku call pannanum", KaiTime.strip("Kumar ku 10 minutes kalichi call pannanum"))
    }

    @Test
    fun repeatingRemindersMoveForward() {
        val r = KaiReminder("r1", "keys", at = LocalDateTime.of(2026, 10, 4, 10, 0), repeat = Repeat.DAILY, said = "", createdAt = 0)
        assertEquals(LocalDateTime.of(2026, 10, 5, 10, 0), r.nextAfter(LocalDateTime.of(2026, 10, 4, 10, 0)))
        assertNull(r.copy(repeat = Repeat.ONCE).nextAfter(now))
    }
}
