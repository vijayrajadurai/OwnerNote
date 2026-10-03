package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/** Any phrasing → a structured reminder request. */
class KaiReminderUnderstandingTest {
    private val now = LocalDateTime.of(2026, 10, 3, 10, 0)
    private val people = listOf("Kumar", "Ramesh", "Ravi")
    private fun req(text: String) = KaiReminderUnderstanding.understand(text, now, people)
    private fun create(text: String) = (req(text) as? ReminderRequest.Create ?: error("not a create: $text -> ${req(text)}")).draft

    @Test
    fun theSameCallReminderInFourWays() {
        for (text in listOf(
            "Kumar-ku 10 minutes kalichi call panna remind pannu.",
            "10 minutes la Kumar-ku call panna nyabagam paduthu.",
            "Kumar-ku call panna 10 mins later remind me.",
            "10 mins kalichu Kumar call.",
            "10 minutes later remind me to call Kumar.",
        )) {
            val d = create(text)
            assertEquals(text, ReminderAction.CALL, d.action)
            assertEquals(text, "Kumar", d.person)
            assertEquals(text, "Call Kumar", d.title)
            assertEquals(text, Duration.ofMinutes(10), d.time?.relative)
            assertEquals(text, Repeat.ONCE, d.time?.repeat)
        }
    }

    @Test
    fun allKindsOfReminders() {
        assertEquals(ReminderAction.MESSAGE, create("Ramesh-ku WhatsApp message panna remind pannu tomorrow 10 am").action)
        assertEquals(ReminderAction.PAYMENT, create("Tomorrow supplier-ku payment panna remind pannu.").action)
        assertEquals(ReminderAction.COLLECTION, create("Naalaikku Kumar kita payment collect panna remind pannu.").action)
        assertEquals(ReminderAction.TASK, create("Daily morning stock check panna remind pannu.").action)
        assertEquals("medicine eduka", create("Night medicine eduka remind pannu.").task)
        assertEquals("machine check panna", create("1 hour later machine check panna sollu.").task)
        assertEquals("Kadai close panna munadi cash check panna", create("Kadai close panna munadi cash check panna remind pannu.").task)
        assertEquals(PartyRole.CUSTOMER, create("Customer Kumar-ku call panna remind pannu").role)
        assertEquals(PartyRole.SUPPLIER, create("Supplier Ravi-ku payment remind pannu").role)
        // Every example from the brief is understood as a reminder.
        listOf(
            "10 minutes later remind me to call Kumar.", "Naalaikku morning supplier-ku call panna remind pannu.",
            "Daily 10 manikku cash check panna remind pannu.", "Every Monday stock check panna reminder podu.",
            "Friday evening Ramesh-ku payment follow up panna remind pannu.", "1 hour later machine check panna sollu.",
            "Tonight 9 manikku GST documents check panna remind pannu.", "Next month 10th rent pay panna remind pannu.",
            "Tomorrow 5 PM Kumar-ku call reminder.", "Every month 1st rent pay panna remind pannu.",
            "Daily kadaiku pogumbothu cash check panna nyabagam paduthu.", "Friday GST documents check panna remind pannu.",
        ).forEach { assertTrue(it, req(it) is ReminderRequest.Create) }
    }

    @Test
    fun manageExistingReminders() {
        assertEquals(ReminderRequest.Complete(ReminderTarget.Last), req("Done."))
        assertEquals(ReminderRequest.Complete(ReminderTarget.Last), req("Completed"))
        assertEquals(ReminderRequest.Snooze(ReminderTarget.Last, 10), req("Innum 10 minutes later remind pannu."))
        assertEquals(ReminderRequest.Snooze(ReminderTarget.Last, 30), req("30 minutes snooze pannu."))
        assertEquals(ReminderRequest.Cancel(ReminderTarget.Last), req("Cancel that reminder."))
        assertEquals(ReminderRequest.Cancel(ReminderTarget.Last), req("Andha reminder cancel pannu."))
        val kumar = req("Kumar call reminder cancel pannu.") as ReminderRequest.Cancel
        assertEquals("Kumar", (kumar.target as ReminderTarget.Matching).person)
        val change = req("Kumar call reminder-a 30 minutes-ku change pannu.") as ReminderRequest.Update
        assertEquals("Kumar", (change.target as ReminderTarget.Matching).person)
        assertEquals(Duration.ofMinutes(30), change.time?.relative)
        val adha = req("Adha tomorrow 10 AM-ku change pannu.") as ReminderRequest.Update
        assertEquals(ReminderTarget.Last, adha.target)
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), adha.time?.at)
        val weekdays = req("Daily reminder-a weekdays mattum change pannu.") as ReminderRequest.Update
        assertEquals(Recurrence.WEEKDAYS, weekdays.time?.recurrence?.days)
    }

    @Test
    fun listing() {
        assertEquals(ReminderRequest.ListAll(false), req("En reminders enna?"))
        assertEquals(ReminderRequest.ListAll(true), req("Today's reminders?"))
        assertEquals(ReminderRequest.ListAll(true), req("Today enna reminders iruku?"))
        assertEquals(ReminderRequest.ListAll(false), req("Enakku pending reminders sollu."))
    }

    @Test
    fun notReminders() {
        assertNull(req("Ramesh ku 5000 kuduthen"))
        assertNull(req("Inniku evlo sales?"))
        assertNull(req("Kumar-ku call pannu"))
        assertNull(req("25000 la 18% GST evlo?"))
    }

    @Test
    fun timingIsExactAndUsesTheLocalZone() {
        val d = create("Kumar-ku 10 minutes kalichi call panna remind pannu")
        val nowMillis = 1_700_000_000_000
        val r = KaiReminderSchedule.build("r1", d, d.time!!, ZoneId.of("Asia/Kolkata"), nowMillis, KaiLang.TANGLISH)
        assertEquals(nowMillis + 600_000, r.triggerAt)
        assertEquals("Owner, Kumar-ku call panna sonneenga.", r.notificationMessage)
        // A clock time is in the phone's zone, never UTC.
        val ten = create("Tomorrow 10 AM supplier-ku call panna remind pannu")
        val ist = KaiReminderSchedule.build("r2", ten, ten.time!!, ZoneId.of("Asia/Kolkata"), nowMillis, KaiLang.TANGLISH)
        val utc = KaiReminderSchedule.build("r3", ten, ten.time!!, ZoneId.of("UTC"), nowMillis, KaiLang.TANGLISH)
        assertEquals(5.5 * 3600_000, (utc.triggerAt - ist.triggerAt).toDouble(), 0.0)
        assertEquals(java.time.LocalTime.of(10, 0), ist.time)
    }

    @Test
    fun duplicatesAndNextOccurrence() {
        val d = create("Daily morning 10 manikku stock check panna remind pannu")
        val z = ZoneId.of("Asia/Kolkata")
        val millis = now.atZone(z).toInstant().toEpochMilli()
        val a = KaiReminderSchedule.build("a", d, d.time!!, z, millis, KaiLang.TANGLISH)
        val b = KaiReminderSchedule.build("b", d, d.time!!, z, millis, KaiLang.TANGLISH)
        assertTrue(KaiReminderSchedule.sameAs(a, b))
        // After it rings tomorrow 10:00, the next is the day after at 10:00.
        val next = KaiReminderSchedule.next(a, a.triggerAt, z)!!
        assertEquals(LocalDateTime.of(2026, 10, 5, 10, 0), java.time.Instant.ofEpochMilli(next).atZone(z).toLocalDateTime())
        assertNull(KaiReminderSchedule.next(a.copy(recurrence = Recurrence.ONCE), a.triggerAt, z))
    }
}
