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
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.brain.tools.ScheduleResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Section 1: a reminder is understood, then CONFIRMED by the owner, and only
 * then saved and scheduled ("Seri Owner. Praba-ku 2 minutes-la call reminder
 * set pannalama?" [Confirm] [Edit] [Cancel]).
 */
class KaiReminderConfirmTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.of(2026, 10, 5, 9, 0)
    private fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(var fullScreen: Boolean? = true) : KaiTools {
        val scheduled = mutableListOf<KaiReminder>()
        val logs = mutableListOf<String>()
        override suspend fun parties(name: String) = emptyList<com.shopai.app.brain.tools.PartyMatch>()
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: com.shopai.app.books.model.PaymentMode, said: String) =
            ActionPlan("x", kind, partyName, partyId, amount, mode, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome = ActionOutcome.Done("X", null)
        override fun createReminder(reminder: KaiReminder): ReminderSaved { scheduled += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun cancelReminder(id: String): Boolean {
            val i = scheduled.indexOfFirst { it.id == id && it.open }
            if (i < 0) return false
            scheduled[i] = KaiReminderFlow.cancel(scheduled[i], 0)!!
            return true
        }
        override fun reminders(): List<KaiReminder> = scheduled.filter { it.open }
        override fun zone(): String = "Asia/Kolkata"
        override fun fullScreenAllowed(): Boolean? = fullScreen
        override suspend fun contacts(name: String, role: PartyRole?) =
            listOf(ContactMatch("phone:1", "Praba", "+919000000011", ContactSource.PHONE), ContactMatch("c1", "Kumar", "+919000000001", ContactSource.CUSTOMER))
                .filter { it.name.equals(name, true) }
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String { logs += intent; return "K" }
    }

    private val tools = Tools()
    private val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now })
    private fun KaiTurn.labels() = card?.buttons.orEmpty().map { it.label }
    private fun KaiTurn.button(label: String) = card!!.buttons.first { it.label == label }.action

    // 1, 2, 3, 4, 5, 6: understood → asked → nothing scheduled → Confirm → scheduled 2 minutes from the Confirm.
    @Test
    fun praba2MinutesCallIsAskedThenScheduledOnConfirm() = runBlocking {
        val ask = kai.ask("Praba-ku 2 minutes-la call pannanum, nyabagam paduthu")
        assertEquals("Seri Owner. Praba-ku 2 minutes-la call reminder set pannalama?", ask.reply.text)
        assertEquals(listOf("Confirm", "Edit", "Cancel"), ask.labels())
        assertTrue("nothing scheduled before Confirm", tools.scheduled.isEmpty())
        now = now.plusSeconds(20) // the owner reads it first
        val done = kai.act(ask.button("Confirm"), KaiLang.TANGLISH)!!
        val r = tools.scheduled.single()
        assertEquals(ReminderAction.CALL, r.action)
        assertEquals("Praba", r.person)
        assertEquals("+919000000011", r.phone)
        assertEquals(millis(now) + 120_000, r.triggerAt)
        assertEquals(ReminderStatus.ACTIVE, r.status)
        assertEquals(0, r.attemptCount)
        assertEquals(5, r.maxAttempts)
        assertTrue(done.reply.text, done.reply.text.startsWith("Done Owner ✅ 2 minutes kalichi Praba-ku call panna remind pannuren."))
        assertFalse(done.reply.text.contains("call pannitten", true))
    }

    // The natural variations from the spec.
    @Test
    fun naturalVariations() = runBlocking {
        val cases = listOf(
            "2 nimishathula Praba-ku call remind pannu" to 2L,
            "Praba-ku 10 minutes kalichi call nyabagam paduthu" to 10L,
            "5 minutes apram Kumar-ku call remind pannu" to 5L,
        )
        for ((said, minutes) in cases) {
            val t = Tools()
            val k = KaiAgent(KaiBusinessBrain(Books()), Books(), t, { now })
            val ask = k.ask(said)
            assertTrue("$said → ${ask.reply.text}", ask.reply.text.startsWith("Seri Owner.") && ask.reply.text.endsWith("reminder set pannalama?"))
            assertTrue(t.scheduled.isEmpty())
            k.act(ask.button("Confirm"), KaiLang.TANGLISH)
            assertEquals(said, millis(now) + minutes * 60_000, t.scheduled.single().triggerAt)
            assertEquals(said, ReminderAction.CALL, t.scheduled.single().action)
        }
        // "2 minutes-la remind pannu": the task is asked, then confirmed.
        val q = kai.ask("2 minutes-la remind pannu")
        assertEquals("Sure Owner. Enna remind pannanum?", q.reply.text)
        val c = kai.ask("Praba-ku call panna")
        assertEquals("Seri Owner. Praba-ku 2 minutes-la call reminder set pannalama?", c.reply.text)
        assertTrue(tools.scheduled.isEmpty())
    }

    // 7: tomorrow morning 10 o'clock.
    @Test
    fun tomorrowMorningTen() = runBlocking {
        val ask = kai.ask("Naalaikku kaalaila 10 manikku Praba-ku call remind pannu")
        assertTrue(ask.reply.text, ask.reply.text.startsWith("Seri Owner. Praba-ku") && ask.reply.text.contains("10:00 AM") && ask.reply.text.endsWith("call reminder set pannalama?"))
        kai.act(ask.button("Confirm"), KaiLang.TANGLISH)
        assertEquals(LocalDateTime.of(2026, 10, 6, 10, 0), java.time.Instant.ofEpochMilli(tools.scheduled.single().triggerAt).atZone(zone).toLocalDateTime())
    }

    // 8: cancel before it is set — nothing is ever scheduled; typed "venam" too.
    @Test
    fun cancelBeforeConfirm() = runBlocking {
        val ask = kai.ask("Praba-ku 2 minutes-la call pannanum, nyabagam paduthu")
        val no = kai.act(ask.button("Cancel"), KaiLang.TANGLISH)!!
        assertEquals("Seri Owner, reminder vekkala.", no.reply.text)
        assertTrue(tools.scheduled.isEmpty())
        // A stale Confirm after Cancel does nothing.
        kai.act(ask.button("Confirm"), KaiLang.TANGLISH)
        assertTrue(tools.scheduled.isEmpty())
        kai.ask("Praba-ku 2 minutes-la call pannanum, nyabagam paduthu")
        assertEquals("Seri Owner, reminder vekkala.", kai.ask("venam").reply.text)
        assertTrue(tools.scheduled.isEmpty())
    }

    // Typed "aama" confirms; Edit asks the time again and confirms the new one.
    @Test
    fun typedYesAndEdit() = runBlocking {
        kai.ask("Praba-ku 2 minutes-la call pannanum, nyabagam paduthu")
        assertTrue(kai.ask("aama").reply.text.startsWith("Done Owner ✅"))
        assertEquals(1, tools.scheduled.size)

        val ask = kai.ask("Kumar-ku 5 minutes apram call remind pannu")
        val edit = kai.act(ask.button("Edit"), KaiLang.TANGLISH)!!
        assertTrue(edit.reply.text, edit.reply.text.startsWith("Seri Owner. Eppo remind pannanum?"))
        val again = kai.ask("10 minutes la")
        assertEquals("Seri Owner. Kumar-ku 10 minutes-la call reminder set pannalama?", again.reply.text)
        assertEquals(1, tools.scheduled.size)
        kai.act(again.button("Confirm"), KaiLang.TANGLISH)
        assertEquals(millis(now) + 600_000, tools.scheduled.last().triggerAt)
    }

    // 16: the same reminder confirmed twice is not created twice.
    @Test
    fun duplicateIsPrevented() = runBlocking {
        val dedup = object : KaiTools by tools {
            override fun createReminder(reminder: KaiReminder): ReminderSaved {
                tools.scheduled.firstOrNull { com.shopai.app.brain.tools.KaiReminderSchedule.sameAs(it, reminder) }?.let { return ReminderSaved(it, true, ScheduleResult.EXACT) }
                return tools.createReminder(reminder)
            }
        }
        val k = KaiAgent(KaiBusinessBrain(Books()), Books(), dedup, { now })
        k.askConfirmed("Praba-ku 2 minutes-la call pannanum, nyabagam paduthu")
        val second = k.askConfirmed("Praba-ku 2 minutes-la call pannanum, nyabagam paduthu")
        assertTrue(second.reply.text, second.reply.text.contains("already irukku"))
        assertEquals(1, tools.scheduled.size)
    }

    // Full-screen alerts not allowed (Android 14+): said on the card with a way to allow it.
    @Test
    fun fullScreenNotAllowedIsSaid() = runBlocking {
        tools.fullScreen = false
        val done = kai.askConfirmed("Praba-ku 2 minutes-la call pannanum, nyabagam paduthu")
        assertTrue(done.card!!.warning!!, done.card!!.warning!!.contains("Full-screen alerts"))
        assertTrue(done.card!!.buttons.any { it.action == KaiAction.OpenFullScreenSettings })
        tools.fullScreen = true
        val ok = kai.askConfirmed("Kumar-ku 5 minutes apram call remind pannu")
        assertFalse(ok.card!!.buttons.any { it.action == KaiAction.OpenFullScreenSettings })
    }

    // Payment reminder keeps the amount the owner said, shown on the confirmation.
    @Test
    fun paymentAmountIsShownBeforeConfirm() = runBlocking {
        val ask = kai.ask("naalaikku 10 manikku Kumar-ku 5000 payment panna remind pannu")
        assertTrue(ask.card!!.lines.toString(), ask.card!!.lines.contains("Amount: ₹5,000"))
        kai.act(ask.button("Confirm"), KaiLang.TANGLISH)
        assertEquals(0, BigDecimal("5000").compareTo(tools.scheduled.single().amount))
        assertEquals(ReminderAction.PAYMENT, tools.scheduled.single().action)
    }
}
