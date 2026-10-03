package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Natural-language time, resolved against a fixed "now" (Saturday 3 Oct 2026, 10:00:00). */
class KaiTimeTest {
    private val now = LocalDateTime.of(2026, 10, 3, 10, 0, 0)
    private fun at(text: String) = KaiTime.parse(text, now) ?: error("no time in: $text")

    @Test
    fun exactRelativeTimeFromNow() {
        // "10 minutes later" at 10:00:00 → exactly 10:10:00 (never 10 AM).
        for (text in listOf("10 minutes later", "10 mins la", "10 nimisham kalichu", "10 nimishathula", "10 minutes after",
            "innum 10 minutes la", "after 10 minutes", "Kumar-ku 10 minutes kalichi call panna remind pannu", "10 mins kalichu Kumar call")) {
            val w = at(text)
            assertEquals(text, LocalDateTime.of(2026, 10, 3, 10, 10, 0), w.at)
            assertEquals(text, Duration.ofMinutes(10), w.relative)
        }
        assertEquals(now.plusMinutes(5), at("5 minutes later").at)
        assertEquals(now.plusMinutes(30), at("30 minutes la").at)
        assertEquals(now.plusMinutes(45), at("45 mins").at)
        assertEquals(now.plusHours(1), at("1 hour later").at)
        assertEquals(now.plusHours(3), at("3 hours kalichu").at)
        assertEquals(now.plusSeconds(10), at("10 seconds la").at)
        assertEquals(now.plusSeconds(30), at("30 seconds").at)
        assertEquals(now.plusMinutes(90), at("1 hour 30 minutes la").at)
        assertEquals(now.plusMinutes(30), at("half an hour").at)
        assertEquals(now.plusMinutes(15), at("15 நிமிடம் கழிச்சு").at)
        assertEquals(now.plusMinutes(1), at("1 minute").at)
    }

    @Test
    fun absoluteTimes() {
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), at("Tomorrow 10 AM").at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), at("Naalaikku 10 mani").at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), at("Naalaikku kaalaila 10 manikku").at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 18, 0), at("Tomorrow evening 6").at)
        assertEquals(LocalDateTime.of(2026, 10, 3, 20, 0), at("Today 8 PM").at)
        assertEquals(LocalDateTime.of(2026, 10, 3, 20, 0), at("இன்று இரவு 8 மணிக்கு").at)
        assertEquals(LocalDateTime.of(2026, 10, 3, 21, 0), at("Tonight 9 manikku GST documents check panna remind pannu").at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 17, 0), at("Tomorrow 5 PM Kumar-ku call reminder").at)
        assertEquals(LocalDateTime.of(2026, 10, 3, 18, 30), at("6:30 pm").at)
        // "5 maniku" alone: evening, said back to the owner.
        val five = at("5 maniku")
        assertEquals(LocalTime.of(17, 0), five.at.toLocalTime())
        assertTrue(five.amPmAssumed)
    }

    @Test
    fun datesAreResolved() {
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0).toLocalDate(), at("Day after tomorrow 9 am").at.toLocalDate())
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0).toLocalDate(), at("Naalai marunaal 9 mani").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 5), at("Next Monday 9 AM").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 5), at("Adutha Monday 9 AM").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 9), at("This Friday 6 PM").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 9), at("Indha Friday 6 PM").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 10), at("10th 11 am").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 10), at("10 thethi 11 am").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 10), at("10 October 11 am").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 10), at("10/10/2026 11 am").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 11, 10), at("Next month 10th rent pay panna remind pannu").at.toLocalDate())
        assertEquals(java.time.LocalDate.of(2026, 10, 10), at("Next week").at.toLocalDate())
        // "1st" has passed this month: next month's.
        assertEquals(java.time.LocalDate.of(2026, 11, 1), at("1st 10 am").at.toLocalDate())
    }

    @Test
    fun missingTimeIsNotInvented() {
        val morning = at("Naalaikku morning supplier-ku call panna remind pannu")
        assertTrue(morning.needsTime)
        assertEquals(DayPart.MORNING, morning.dayPart)
        assertEquals(java.time.LocalDate.of(2026, 10, 4), morning.at.toLocalDate())
        assertTrue(at("Friday evening Ramesh-ku payment follow up panna remind pannu").needsTime)
        assertTrue(at("Every Monday stock check panna reminder podu").needsTime)
        assertTrue(at("Tomorrow supplier-ku payment panna remind pannu").needsTime)
        val monthly = at("Every month rent pay panna remind pannu")
        assertTrue(Missing.DAY_OF_MONTH in monthly.missing)
        assertNull(KaiTime.parse("Kumar ku call panna remind pannu", now))
        assertNull(KaiTime.parse("Ramesh ku 5000 kuduthen", now))
    }

    @Test
    fun pastTimesAreFlagged() {
        assertTrue(at("inniku 9 maniku").alreadyPassed)
        assertTrue(at("10/09/2026 9 am").alreadyPassed)
        // Without "today" it is simply the next 9 AM.
        assertFalse(at("9 maniku").alreadyPassed)
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 0), at("9 maniku").at)
        // "Saturday 9 AM" said on Saturday at 10: next Saturday.
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 0), at("Saturday 9 AM").at)
    }

    @Test
    fun recurring() {
        val daily = at("Daily morning 10 manikku saavi eduthuka remind pannu")
        assertEquals(Repeat.DAILY, daily.repeat)
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), daily.at)
        val monday = at("Every Monday 9 AM supplier payment check panna remind pannu")
        assertEquals(Recurrence(Repeat.WEEKLY, setOf(DayOfWeek.MONDAY)), monday.recurrence)
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), monday.at)
        assertEquals(LocalDateTime.of(2026, 10, 9, 18, 0), at("Every Friday evening 6 stock check panna remind pannu").at)
        val weekdays = at("weekdays 9 am shop open")
        assertEquals(Recurrence.WEEKDAYS, weekdays.recurrence.days)
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), weekdays.at)
        val rent = at("Every month 1st rent pay panna remind pannu 10 am")
        assertEquals(Recurrence(Repeat.MONTHLY, dayOfMonth = 1), rent.recurrence)
        assertEquals(LocalDateTime.of(2026, 11, 1, 10, 0), rent.at)
        assertEquals(Repeat.WEEKLY, at("every Monday and Thursday 8 am").repeat)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), at("every Monday and Thursday 8 am").recurrence.days)
    }

    @Test
    fun nextOccurrence() {
        val nine = LocalTime.of(9, 0)
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 0), Recurrence(Repeat.DAILY).nextAt(nine, now))
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), Recurrence(Repeat.WEEKLY, Recurrence.WEEKDAYS).nextAt(nine, now))
        // "31st" in November (30 days): the 30th.
        assertEquals(LocalDateTime.of(2026, 10, 31, 9, 0), Recurrence(Repeat.MONTHLY, dayOfMonth = 31).nextAt(nine, now))
        assertEquals(LocalDateTime.of(2026, 11, 30, 9, 0), Recurrence(Repeat.MONTHLY, dayOfMonth = 31).nextAt(nine, LocalDateTime.of(2026, 10, 31, 9, 0)))
    }

    @Test
    fun stripLeavesTheTask() {
        assertEquals("saavi eduthuka remind pannu", KaiTime.strip("Daily morning 10 manikku saavi eduthuka remind pannu"))
        assertEquals("Kumar-ku call panna remind pannu", KaiTime.strip("Kumar-ku 10 minutes kalichi call panna remind pannu"))
        assertEquals("rent pay panna remind pannu", KaiTime.strip("Next month 10th rent pay panna remind pannu"))
    }
}
