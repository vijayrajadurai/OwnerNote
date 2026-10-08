package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.ContactSource
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiReminderFlow
import com.shopai.app.brain.tools.KaiReminderKind
import com.shopai.app.brain.tools.KaiReminderSchedule
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderKind
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.Repeat
import com.shopai.app.brain.tools.ScheduleResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Personal daily-life reminders through the one existing reminder engine: understood from the
 * owner's own words (any order, Tanglish), asked only for what is missing, confirmed before
 * anything is scheduled, never turned into a customer / supplier / transaction / stock / payment.
 */
class KaiPersonalReminderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    // Monday 5 October 2026, 9:00 AM.
    private var now = LocalDateTime.of(2026, 10, 5, 9, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** The reminder engine's behaviour that matters here: saves, duplicates, updates — and nothing in the books. */
    private class Tools : KaiTools {
        val scheduled = mutableListOf<KaiReminder>()
        val booksTouched = mutableListOf<String>()
        override suspend fun parties(name: String) = emptyList<com.shopai.app.brain.tools.PartyMatch>()
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: com.shopai.app.books.model.PaymentMode, said: String): ActionPlan {
            booksTouched += "prepare $kind $partyName"
            return ActionPlan("x", kind, partyName, partyId, amount, mode, said = said)
        }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { booksTouched += "confirm ${plan.kind}"; return ActionOutcome.Done("X", null) }
        override fun createReminder(reminder: KaiReminder): ReminderSaved {
            scheduled.firstOrNull { KaiReminderSchedule.sameAs(it, reminder) }?.let { return ReminderSaved(it, true, ScheduleResult.EXACT) }
            scheduled += reminder
            return ReminderSaved(reminder, false, ScheduleResult.EXACT)
        }
        override fun updateReminder(reminder: KaiReminder): ReminderSaved {
            val i = scheduled.indexOfFirst { it.id == reminder.id }
            if (i < 0) return ReminderSaved(reminder, false, ScheduleResult.FAILED)
            scheduled[i] = reminder
            return ReminderSaved(reminder, false, ScheduleResult.EXACT)
        }
        override fun cancelReminder(id: String): Boolean {
            val i = scheduled.indexOfFirst { it.id == id && it.open }
            if (i < 0) return false
            scheduled[i] = KaiReminderFlow.cancel(scheduled[i], 0)!!
            return true
        }
        override fun reminders(): List<KaiReminder> = scheduled.filter { it.open }
        override fun zone(): String = "Asia/Kolkata"
        override fun fullScreenAllowed(): Boolean? = true
        override suspend fun contacts(name: String, role: PartyRole?) =
            listOf(ContactMatch("phone:7", "Amma", "+919000000077", ContactSource.PHONE), ContactMatch("c1", "Kumar", "+919000000001", ContactSource.CUSTOMER))
                .filter { it.name.equals(name, true) }
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String = "K"
    }

    private val tools = Tools()
    private fun kai() = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now })
    private fun KaiTurn.button(label: String) = card!!.buttons.first { it.label == label }.action
    private fun at(r: KaiReminder): LocalDateTime = Instant.ofEpochMilli(r.triggerAt).atZone(zone).toLocalDateTime()

    /** Asks, checks nothing is scheduled yet, confirms; returns the one reminder it scheduled. */
    private fun confirmed(said: String): KaiReminder = runBlocking {
        val k = kai()
        val before = tools.scheduled.size
        val ask = k.ask(said)
        assertTrue("$said → ${ask.reply.text}", ask.reply.text.startsWith("Seri Owner.") && ask.reply.text.endsWith("reminder set pannalama?"))
        assertEquals("nothing before Confirm: $said", before, tools.scheduled.size)
        k.act(ask.button("Confirm"), KaiLang.TANGLISH)
        tools.scheduled.drop(before).single()
    }

    // 1. "Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu" → 4 PM today, PERSONAL, the owner's words kept.
    @Test
    fun sonSchoolPickup() {
        val r = confirmed("Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu")
        assertEquals(LocalDateTime.of(2026, 10, 5, 16, 0), at(r))
        assertEquals("Paiyana school-la irundhu kootitu vara", r.task)
        assertEquals(ReminderAction.TASK, r.action)
        assertNull(r.person)
        assertEquals(ReminderKind.PERSONAL, KaiReminderKind.of(r))
        assertTrue(KaiReminderKind.personal(r))
        assertEquals(Repeat.ONCE, r.recurrence.repeat)
    }

    // NL variations of the same reminder: all 4 PM today, no time words / "-ku" / fillers left in the task.
    @Test
    fun naturalVariationsOfThePickup() {
        for (said in listOf(
            "4 PM-ku son-a school-la irundhu pickup pannanum, remind pannu",
            "4-ku school pickup irukku, remind me",
            "4 mani aagumbodhu paiyana kootitu vara solli nyabagam paduthu",
        )) {
            val r = confirmed(said)
            assertEquals(said, LocalDateTime.of(2026, 10, 5, 16, 0), at(r))
            assertNull("$said → ${r.person}", r.person)
            assertFalse("$said → '${r.task}'", Regex("""(?i)(^|\s|-)(ku|4|pm|mani|aagumbodhu|solli|remind|irukku)(\s|$)""").containsMatchIn(r.task))
            assertTrue("$said → '${r.task}'", Regex("""(?i)school|paiyana""").containsMatchIn(r.task))
            assertEquals(said, ReminderKind.PERSONAL, KaiReminderKind.of(r))
        }
    }

    // 2. A parent's call: a CALL to the owner's own phone contact, personal, not a customer.
    @Test
    fun parentCall() {
        val r = confirmed("Amma-ku 7 manikku call panna remind pannu")
        assertEquals(ReminderAction.CALL, r.action)
        assertEquals("Amma", r.person)
        assertEquals("phone:7", r.contactId)
        // "7 manikku" with no part of the day: the timing engine's own rule (7–11 is morning) — unchanged.
        assertEquals(LocalDateTime.of(2026, 10, 6, 7, 0), at(r))
        assertEquals(ReminderKind.CALL, KaiReminderKind.of(r))
        assertTrue(KaiReminderKind.personal(r))
        assertTrue(tools.booksTouched.isEmpty())
    }

    // 3. Medicine.
    @Test
    fun medicine() {
        val r = confirmed("Medicine 9 manikku edukkanum remind pannu")
        assertEquals(LocalTime.of(9, 0), at(r).toLocalTime())
        assertEquals("Medicine edukkanum", r.task)
        assertEquals(ReminderKind.PERSONAL, KaiReminderKind.of(r))
    }

    // 4. "Tomorrow morning gym": only the exact time is asked (a part of the day is not a time).
    @Test
    fun gymTomorrowMorningAsksOnlyTheTime() = runBlocking {
        val k = kai()
        val q = k.ask("Naalaikku morning gym poganum remind pannu")
        assertEquals("Morning-la exact time sollunga Owner.", q.reply.text)
        assertTrue(tools.scheduled.isEmpty())
        val ask = k.ask("8 mani")
        assertTrue(ask.reply.text, ask.reply.text.endsWith("reminder set pannalama?"))
        k.act(ask.button("Confirm"), KaiLang.TANGLISH)
        val r = tools.scheduled.single()
        assertEquals(LocalDateTime.of(2026, 10, 6, 8, 0), at(r))
        assertEquals("gym poganum", r.task)
        assertEquals(ReminderKind.PERSONAL, KaiReminderKind.of(r))
    }

    // 5. Current bill: a payment-kind reminder with no amount — never a payment entry in the books.
    @Test
    fun currentBillIsAReminderNotAPayment() {
        val r = confirmed("Evening 6 manikku current bill pay panna remind pannu")
        assertEquals(LocalTime.of(18, 0), at(r).toLocalTime())
        assertEquals(ReminderAction.PAYMENT, r.action)
        assertNull("no amount invented", r.amount)
        assertNull(r.person)
        assertEquals(ReminderKind.PAYMENT, KaiReminderKind.of(r))
        assertTrue(KaiReminderKind.personal(r))
        assertTrue("nothing drafted or posted in the books", tools.booksTouched.isEmpty())
    }

    // 6. Office task; also "car service-ku" is never a person called "Service".
    @Test
    fun officeTaskAndCarService() {
        val office = confirmed("4 manikku office-la irundhu documents eduthutu vara remind pannu")
        assertEquals("office-la irundhu documents eduthutu vara", office.task)
        assertEquals(LocalTime.of(16, 0), at(office).toLocalTime())
        val car = confirmed("Sunday 10 manikku car service-ku kondu poganum remind pannu")
        assertNull(car.person)
        assertEquals(LocalDateTime.of(2026, 10, 11, 10, 0), at(car))
        assertEquals(ReminderKind.PERSONAL, KaiReminderKind.of(car))
    }

    // 7. Generic: "Night 10 manikku gate lock" and "Tomorrow 8 AM wife-ku call".
    @Test
    fun genericAndWifeCall() {
        val gate = confirmed("Night 10 manikku gate lock pannunga nu remind pannu")
        assertEquals("gate lock", gate.task)
        assertEquals(LocalTime.of(22, 0), at(gate).toLocalTime())
        val wife = confirmed("Tomorrow 8 AM wife-ku call panna remind pannu")
        assertEquals(LocalDateTime.of(2026, 10, 6, 8, 0), at(wife))
        assertEquals("Wife", wife.person)
        assertEquals(ReminderAction.CALL, wife.action)
        assertTrue(KaiReminderKind.personal(wife))
    }

    // 8. Daily: through the existing recurrence (no new engine).
    @Test
    fun dailyReminders() {
        val walking = confirmed("Daily 7 manikku walking remind pannu")
        assertEquals(Repeat.DAILY, walking.recurrence.repeat)
        assertEquals(LocalTime.of(7, 0), walking.time)
        val medicine = confirmed("Every morning 8 manikku medicine remind pannu")
        assertEquals("every morning = daily", Repeat.DAILY, medicine.recurrence.repeat)
        assertEquals(LocalTime.of(8, 0), medicine.time)
        assertEquals("medicine", medicine.task)
        val gate = confirmed("Daily night 10 manikku gate check panna remind pannu")
        assertEquals(Repeat.DAILY, gate.recurrence.repeat)
        assertEquals(LocalTime.of(22, 0), gate.time)
    }

    // 9. Weekly / weekdays.
    @Test
    fun weeklyAndWeekdays() = runBlocking {
        val office = confirmed("Weekdays 8:30-ku office-ku kelambanum remind pannu")
        assertEquals(Repeat.WEEKLY, office.recurrence.repeat)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), office.recurrence.days)
        assertEquals(LocalTime.of(8, 30), office.time)
        assertEquals("office-ku kelambanum", office.task)
        // "Every Sunday car clean" has no time: asked, then weekly on Sunday.
        val k = kai()
        val q = k.ask("Every Sunday car clean panna remind pannu")
        assertTrue(q.reply.text, q.reply.text.startsWith("Seri Owner. Eppa remind pannanum?"))
        val ask = k.ask("10 mani")
        k.act(ask.button("Confirm"), KaiLang.TANGLISH)
        val car = tools.scheduled.last()
        assertEquals(Repeat.WEEKLY, car.recurrence.repeat)
        assertEquals(setOf(DayOfWeek.SUNDAY), car.recurrence.days)
        assertEquals(DayOfWeek.SUNDAY, at(car).dayOfWeek)
    }

    // 10. Missing time: "Seri Owner. Eppa remind pannanum?" — then the time completes the same reminder.
    @Test
    fun missingTimeIsAsked() = runBlocking {
        val k = kai()
        val q = k.ask("Paiyana school-la irundhu kootitu vara remind pannu")
        assertTrue(q.reply.text, q.reply.text.startsWith("Seri Owner. Eppa remind pannanum?"))
        assertTrue(tools.scheduled.isEmpty())
        val ask = k.ask("4 mani")
        assertTrue(ask.reply.text, ask.reply.text.contains("Paiyana school-la irundhu kootitu vara"))
        k.act(ask.button("Confirm"), KaiLang.TANGLISH)
        assertEquals(LocalDateTime.of(2026, 10, 5, 16, 0), at(tools.scheduled.single()))
    }

    // 11. Missing task: "Seri Owner. Enna nyabagam paduthanum?" — the time is kept, never asked again.
    @Test
    fun missingTaskIsAsked() = runBlocking {
        val k = kai()
        val q = k.ask("4 manikku remind pannu")
        assertEquals("Seri Owner. Enna nyabagam paduthanum?", q.reply.text)
        val ask = k.ask("Paiyana school-la irundhu kootitu vara")
        assertTrue(ask.reply.text, ask.reply.text.endsWith("reminder set pannalama?"))
        k.act(ask.button("Confirm"), KaiLang.TANGLISH)
        val r = tools.scheduled.single()
        assertEquals(LocalDateTime.of(2026, 10, 5, 16, 0), at(r))
        assertEquals("Paiyana school-la irundhu kootitu vara", r.task)
    }

    // 12 + 13. Confirmation first: Confirm / Edit / Cancel; Cancel schedules nothing.
    @Test
    fun confirmationAndCancel() = runBlocking {
        val k = kai()
        val ask = k.ask("Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu")
        assertEquals(listOf("Confirm", "Edit", "Cancel"), ask.card!!.buttons.map { it.label })
        val cancelled = k.act(ask.button("Cancel"), KaiLang.TANGLISH)!!
        assertEquals("Seri Owner, reminder vekkala.", cancelled.reply.text)
        assertTrue(tools.scheduled.isEmpty())
        // "venam" by voice does the same.
        val again = k.ask("Medicine 9 manikku edukkanum remind pannu")
        k.ask("venam")
        assertTrue(again.reply.text.endsWith("reminder set pannalama?"))
        assertTrue(tools.scheduled.isEmpty())
    }

    // 14. Edit before it is set: the time is asked again, the task stays.
    @Test
    fun editTimeBeforeConfirm() = runBlocking {
        val k = kai()
        val ask = k.ask("Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu")
        val edit = k.act(ask.button("Edit"), KaiLang.TANGLISH)!!
        assertTrue(edit.reply.text, edit.reply.text.startsWith("Seri Owner. Eppa remind pannanum?"))
        val again = k.ask("5 mani")
        k.act(again.button("Confirm"), KaiLang.TANGLISH)
        val r = tools.scheduled.single()
        assertEquals(LocalDateTime.of(2026, 10, 5, 17, 0), at(r))
        assertEquals("Paiyana school-la irundhu kootitu vara", r.task)
    }

    // 15 + 16. "Time maathu" → "5 mani" → the same reminder moved ("Seri Owner, … 5:00 PM-ku maathitten"), never a second one.
    @Test
    fun timeMaathuMovesTheSameReminder() = runBlocking {
        val k = kai()
        k.askConfirmed("Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu")
        val id = tools.scheduled.single().id
        val q = k.ask("Time maathu")
        assertEquals("Eppo-ku maathanum Owner?", q.reply.text)
        val done = k.ask("5 mani")
        assertTrue(done.reply.text, done.reply.text.startsWith("Seri Owner,") && done.reply.text.contains("5:00 PM-ku maathitten"))
        assertEquals("no duplicate", 1, tools.scheduled.size)
        assertEquals(id, tools.scheduled.single().id)
        assertEquals(LocalDateTime.of(2026, 10, 5, 17, 0), at(tools.scheduled.single()))
        // "Neram 6 manikku maathu" in one go.
        k.ask("Neram 6 manikku maathu")
        assertEquals(1, tools.scheduled.size)
        assertEquals(LocalDateTime.of(2026, 10, 5, 18, 0), at(tools.scheduled.single()))
    }

    // 16. The same reminder said twice is not created twice.
    @Test
    fun duplicateIsPrevented() = runBlocking {
        val k = kai()
        k.askConfirmed("Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu")
        val second = kai().askConfirmed("Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu")
        assertTrue(second.reply.text, second.reply.text.startsWith("Owner, indha reminder already irukku"))
        assertEquals(1, tools.scheduled.size)
    }

    // 17. Owner isolation: one owner's personal reminders are never another owner's (or business's).
    @Test
    fun ownerIsolation() {
        val mine = confirmed("Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu").copy(businessId = "B1", ownerId = "owner-1")
        val theirs = mine.copy(id = "R-other", ownerId = "owner-2")
        val otherShop = mine.copy(id = "R-shop", businessId = "B2")
        val all = listOf(mine, theirs, otherShop)
        assertEquals(listOf(mine.id), com.shopai.app.brain.tools.KaiReminderScope.visible(all, "B1", "owner-1").map { it.id })
        assertEquals(listOf("R-other"), com.shopai.app.brain.tools.KaiReminderScope.visible(all, "B1", "owner-2").map { it.id })
    }

    // Business and personal stay apart: a shop reminder is BUSINESS, a customer's call is not personal.
    @Test
    fun businessAndPersonalStaySeparate() {
        val stock = confirmed("6 manikku Colgate stock check panna remind pannu")
        assertEquals(ReminderKind.BUSINESS, KaiReminderKind.of(stock))
        assertFalse(KaiReminderKind.personal(stock))
        val kumar = confirmed("Kumar-ku 11 manikku call panna remind pannu")
        assertEquals("c1", kumar.contactId)
        assertFalse("a customer from the books is business", KaiReminderKind.personal(kumar))
        val pickup = confirmed("Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu")
        assertTrue(KaiReminderKind.personal(pickup))
        assertTrue("no ledger / stock / payment entry from any reminder", tools.booksTouched.isEmpty())
    }
}
