package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.morning.InMemoryMorningTaskStore
import com.shopai.app.brain.morning.MorningBriefs
import com.shopai.app.brain.morning.MorningParty
import com.shopai.app.brain.morning.MorningPartyKind
import com.shopai.app.brain.morning.MorningProduct
import com.shopai.app.brain.morning.MorningReminder
import com.shopai.app.brain.morning.MorningSnapshot
import com.shopai.app.brain.morning.MorningTaskType
import com.shopai.app.brain.morning.MorningTrigger
import com.shopai.app.brain.morning.MorningWorkEngine
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiIntentKind
import com.shopai.app.brain.tools.KaiIntents
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * MORNING_WORK is a core Kai intent: the same router for typed text and voice
 * transcripts, the one MorningWorkEngine for chat, voice and a scheduled brief,
 * real (test) records only, and nothing ever written by it.
 */
class KaiMorningWorkIntentTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val nowZ = ZonedDateTime.of(2026, 10, 3, 8, 30, 0, 0, zone)
    private val now: LocalDateTime = nowZ.toLocalDateTime()
    private val today: LocalDate = nowZ.toLocalDate()
    private val tenAm = ZonedDateTime.of(2026, 10, 3, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    // Controlled test data — the production code has none of these values.
    private val kumar = MorningParty("c-kumar", "Kumar", MorningPartyKind.CUSTOMER, "9000000001", 8_000.0, today.minusDays(2).toEpochDay())
    private val colgate = MorningProduct("p-colgate", "Colgate", "PCS", stock = 5.0, minimum = null, reorderLevel = 10.0)
    private val kumarCall = MorningReminder("r-1", "Kumar call", tenAm, done = false, custom = true)
    private fun snapshot(biz: String = "biz-A") = MorningSnapshot(biz, parties = listOf(kumar), products = listOf(colgate), reminders = listOf(kumarCall))

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = listOf(PartySummary("c-kumar", "Kumar", "9000000001", 8000.0, null)), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Records every write Kai could make — Morning Work must leave all of them empty. */
    private class Tools : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val confirmed = mutableListOf<ActionPlan>()
        val stockChanges = mutableListOf<String>()
        val scheduled = mutableListOf<KaiReminder>()
        val logs = mutableListOf<Pair<String, ActionStatus>>()
        override suspend fun products() = listOf(ProductRef("p-colgate", "Colgate", "PCS", BigDecimal("5")))
        override suspend fun parties(name: String) = listOf(PartyMatch("c-kumar", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("8000"))).filter { it.name.contains(name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: com.shopai.app.books.model.PaymentMode, said: String) =
            ActionPlan("x", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { confirmed += plan; return ActionOutcome.Done("X", null) }
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome { stockChanges += product.id; return ActionOutcome.Done("S", null) }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { scheduled += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders(): List<KaiReminder> = scheduled
        override fun zone(): String = "Asia/Kolkata"
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String { logs += intent to status; return "K-1" }
    }

    private val engine = MorningWorkEngine(InMemoryMorningTaskStore(), clock = { nowZ })
    private var current: MorningSnapshot? = snapshot()
    private val tools = Tools()
    private fun kai(access: KaiMorningAccess? = KaiMorningAccess { lang -> current?.let { engine.generate(it, lang) } }) =
        KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, morning = access)
    private val productList = listOf(ProductRef("p-colgate", "Colgate", "PCS", BigDecimal("5")))
    private fun intent(s: String) = KaiIntents.classify(s, now, listOf("Kumar"), productList)
    private fun KaiTurn.actions() = card?.buttons.orEmpty().map { it.action }
    private fun noWrites() {
        assertTrue(tools.prepared.isEmpty())
        assertTrue(tools.confirmed.isEmpty())
        assertTrue(tools.stockChanges.isEmpty())
        assertTrue(tools.scheduled.isEmpty())
        assertFalse(tools.logs.any { it.second == ActionStatus.CONFIRMED || it.second == ActionStatus.SCHEDULED })
    }

    // 1–4 + every phrasing in the spec: one intent, any language.
    @Test
    fun morningWorkPhrasesInEveryLanguage() {
        val phrases = listOf(
            "morning brief", "prepare my morning work", "what should I do today?", "what do I need to do this morning?", "prepare today's work",
            "kaalai work ready pannu", "morning brief kudu", "innaiku enna panna vendum?", "shop open panna munadi enna seiyanum?",
            "kaalai enna important?", "innaiku business la enna important?",
            "காலை வேலை தயார் பண்ணு", "காலை சுருக்கம் கொடு", "இன்று என்ன செய்ய வேண்டும்?", "கடை திறப்பதற்கு முன் என்ன செய்ய வேண்டும்?", "இன்று என்ன முக்கியம்?",
            "Good morning Kai, what should I do today?", "Morning Kai, enna important?", "Kaalaila enna work iruku?",
            "Shop open panna munadi enna check pannanum?", "Today business brief kudu.",
        )
        for (p in phrases) assertEquals(p, KaiIntentKind.MORNING_WORK, intent(p))
    }

    // 5, 6 + spec 12/13: an explicit reminder is never Morning Work.
    @Test
    fun reminderRequestsStayReminders() = runBlocking {
        for (p in listOf("tomorrow morning Kumar-ku call remind pannu", "morning 10 manikku reminder pannu", "Tomorrow morning remind me to call Kumar",
            "Morning-la 10 manikku shop-ku poganum nu remind pannu", "Morning 10 manikku Kumar-ku call remind pannu", "Tomorrow morning remind me to open shop")) {
            assertEquals(p, KaiIntentKind.CREATE_REMINDER, intent(p))
            assertFalse(p, kai().ask(p).reply.text.contains("Innaiku important"))
        }
        // Plain greetings stay conversation.
        assertEquals(KaiIntentKind.CHAT, intent("good morning"))
    }

    // 7: a voice transcript (Tamil script) and typed text reach the same intent and the same records.
    @Test
    fun voiceAndTextSameIntentSameBrief() = runBlocking {
        assertEquals(intent("innaiku enna important?"), intent("இன்று என்ன முக்கியம்?"))
        val typed = kai().ask("innaiku enna important?")
        val spoken = kai().ask("இன்று என்ன முக்கியம்?")
        for (t in listOf(typed, spoken)) {
            assertTrue(t.reply.text, t.reply.text.contains("Kumar") && t.reply.text.contains("8,000") && t.reply.text.contains("Colgate"))
        }
        assertTrue(spoken.reply.text, spoken.reply.text.contains("குட் மார்னிங்"))
    }

    // 8, 9: the brief reads the actual pending, low-stock and reminder records, in priority order.
    @Test
    fun briefShowsTheActualRecords() = runBlocking {
        val t = kai().ask("morning brief kudu")
        val text = t.reply.text
        assertTrue(text, text.startsWith("Good morning Owner ☀️\nInnaiku important:"))
        assertTrue(text, text.contains("🔴 Collections\nKumar — ₹8,000 overdue"))
        assertTrue(text, text.contains("📦 Stock\nColgate — 5 PCS dhaan irukku (reorder level 10 PCS)"))
        assertTrue(text, text.contains("⏰ Reminders\n10:00 AM — Kumar call"))
        assertTrue(text, text.contains("First priority:\nKumar collection follow-up."))
        // Collections come before stock, stock before reminders.
        assertTrue(text.indexOf("Collections") < text.indexOf("Stock") && text.indexOf("Stock") < text.indexOf("Reminders"))
        // Follow-up: asks first, nothing happens without a tap.
        assertEquals("Owner, first Kumar collection follow-up pannalama?", t.card!!.lines.single())
        assertEquals(listOf("View Kumar", "Remind Me", "Call Kumar", "Skip", "Start Morning Work"), t.card!!.buttons.map { it.label })
        assertEquals(KaiAction.OpenRecord("CUSTOMER", "c-kumar"), t.actions().first())
        noWrites()
    }

    // 10: no data → no invented values.
    @Test
    fun missingDataIsNeverInvented() = runBlocking {
        current = MorningSnapshot("biz-A")
        val empty = kai().ask("morning brief kudu")
        assertTrue(empty.reply.text, empty.reply.text.contains("Ellam clear"))
        assertFalse(empty.reply.text.contains("₹"))
        // The records couldn't be read at all.
        current = null
        val none = kai().ask("morning brief kudu")
        assertEquals("Owner, idha unga business records-la verify panna mudiyala.", none.reply.text)
        assertEquals("Owner, idha unga business records-la verify panna mudiyala.", kai(access = null).ask("kaalai work ready pannu").reply.text)
        noWrites()
    }

    // 11: Morning Work never creates a transaction, a stock change or a reminder by itself.
    @Test
    fun followUpsNeverWriteByThemselves() = runBlocking {
        val k = kai()
        val t = k.ask("kaalai work ready pannu")
        val skip = k.act(t.actions().filterIsInstance<KaiAction.MorningNext>().single(), KaiLang.TANGLISH)!!
        assertTrue(skip.reply.text, skip.reply.text.startsWith("Adutha: Colgate stock order pannalama?"))
        // Add Stock → Kai asks how many (a draft later, still confirmed by the owner).
        val add = k.act(skip.actions().filterIsInstance<KaiAction.AddStockFor>().single(), KaiLang.TANGLISH)!!
        assertTrue(add.reply.text, add.reply.text.contains("Evlo pieces vandhirukku"))
        // Remind Me → Kai asks when; nothing is scheduled yet.
        val remind = k.act(t.actions().filterIsInstance<KaiAction.RemindAbout>().single(), KaiLang.TANGLISH)!!
        assertTrue(remind.reply.text, remind.reply.text.startsWith("Eppo remind pannanum Owner?"))
        noWrites()
    }

    // 12: a scheduled morning notification uses the same engine and gets the same tasks.
    @Test
    fun scheduledTriggerUsesTheSameEngine() = runBlocking {
        val manual = engine.generate(snapshot(), KaiLang.TANGLISH, MorningTrigger.MANUAL)
        val scheduled = engine.generate(snapshot(), KaiLang.TANGLISH, MorningTrigger.SCHEDULED)
        assertEquals(manual.queue.map { it.taskId }, scheduled.queue.map { it.taskId })
        assertEquals(manual.text, scheduled.text)
        assertEquals(MorningTrigger.SCHEDULED, scheduled.trigger)
        val (title, body) = MorningBriefs.notification(scheduled)
        assertEquals("Kai — Morning Work", title)
        assertEquals("Innaiku 3 important work. First: Kumar collection follow-up.", body)
        // The Morning Work screen's list is the same list.
        assertEquals(engine.state.plan!!.openTasks.map { it.taskId }.toSet(), scheduled.queue.map { it.taskId }.toSet())
    }

    // Tenant isolation: another business's snapshot never shows the previous one's records.
    @Test
    fun anotherBusinessNeverSeesTheFirst() = runBlocking {
        engine.generate(snapshot("biz-A"), KaiLang.TANGLISH)
        val b = engine.generate(MorningSnapshot("biz-B", products = listOf(MorningProduct("p-b", "Sugar", "KG", 0.0, null, 5.0))), KaiLang.TANGLISH)
        assertEquals("biz-B", b.businessId)
        assertFalse(b.text.contains("Kumar"))
        assertTrue(b.text, b.text.contains("Sugar — out of stock"))
        assertTrue(b.queue.all { it.businessId == "biz-B" })
    }

    // Spec 13: "Morning work ready panni Kumar-ku call pannu" → Morning Work + the explicit call (a button, never dialled by itself).
    @Test
    fun morningWorkWithAnExplicitCall() = runBlocking {
        assertEquals(KaiIntentKind.MORNING_WORK, intent("Morning work ready panni Kumar-ku call pannu"))
        val t = kai().ask("Morning work ready panni Kumar-ku call pannu")
        assertTrue(t.reply.text.contains("Innaiku important"))
        val call = t.card!!.buttons.first()
        assertEquals(KaiAction.Dial("Kumar", "+919000000001"), call.action)
        assertTrue(call.primary)
        noWrites()
    }

    @Test
    fun startGoesStraightIntoGuidedMorningWork() = runBlocking {
        assertEquals(KaiAction.OpenMorningWork(start = true), kai().ask("kaalai work start pannu").direct)
        assertNull(kai().ask("morning brief kudu").direct)
    }

    @Test
    fun briefTasksAreTheEnginesTasks() = runBlocking {
        val brief = engine.generate(snapshot(), KaiLang.ENGLISH)
        assertEquals(listOf(MorningTaskType.COLLECT_PAYMENT, MorningTaskType.LOW_STOCK, MorningTaskType.REMINDER), brief.queue.map { it.taskType })
        assertTrue(brief.text, brief.text.contains("Colgate — 5 PCS left (below reorder level 10 PCS)"))
        assertTrue(brief.text, brief.text.contains("Kumar — ₹8,000 overdue"))
    }
}
