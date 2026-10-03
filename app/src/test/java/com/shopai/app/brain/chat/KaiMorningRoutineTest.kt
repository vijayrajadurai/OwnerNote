package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.morning.InMemoryMorningTaskStore
import com.shopai.app.brain.morning.MorningBatch
import com.shopai.app.brain.morning.MorningBriefs
import com.shopai.app.brain.morning.MorningDraft
import com.shopai.app.brain.morning.MorningOwner
import com.shopai.app.brain.morning.MorningParty
import com.shopai.app.brain.morning.MorningPartyKind
import com.shopai.app.brain.morning.MorningProduct
import com.shopai.app.brain.morning.MorningQueries
import com.shopai.app.brain.morning.MorningReminder
import com.shopai.app.brain.morning.MorningRoutineParser
import com.shopai.app.brain.morning.MorningRoutines
import com.shopai.app.brain.morning.MorningSection
import com.shopai.app.brain.morning.MorningSection.COLLECTIONS
import com.shopai.app.brain.morning.MorningSection.PAYMENTS
import com.shopai.app.brain.morning.MorningSection.REMINDERS
import com.shopai.app.brain.morning.MorningSection.STOCK
import com.shopai.app.brain.morning.MorningSnapshot
import com.shopai.app.brain.morning.MorningSnapshotLoader
import com.shopai.app.brain.morning.MorningTotals
import com.shopai.app.brain.morning.MorningWorkEngine
import com.shopai.app.brain.morning.RoutinePlace
import com.shopai.app.brain.morning.RoutineRequest
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
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * PHASE 2 — B: the owner's own Morning Routine (private per owner + business,
 * saved only on [Save]); C: bounded loading; D: owner isolation / account switch.
 */
class KaiMorningRoutineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val nowZ = ZonedDateTime.of(2026, 10, 3, 8, 30, 0, 0, zone)
    private val today: LocalDate = nowZ.toLocalDate()
    private val tenAm = ZonedDateTime.of(2026, 10, 3, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    // Controlled test data (shared business records — both owners of biz-A see the same figures).
    private val kumar = MorningParty("c-kumar", "Kumar", MorningPartyKind.CUSTOMER, "9000000001", 8_000.0, today.minusDays(2).toEpochDay())
    private val abc = MorningParty("s-abc", "ABC Traders", MorningPartyKind.SUPPLIER, null, 12_000.0, today.toEpochDay())
    private val colgate = MorningProduct("p-colgate", "Colgate", "PCS", stock = 5.0, minimum = null, reorderLevel = 10.0)
    private val kumarCall = MorningReminder("r-1", "Kumar call", tenAm, done = false, custom = true)
    private fun records(biz: String) = MorningSnapshot(biz, parties = listOf(kumar, abc), products = listOf(colgate), reminders = listOf(kumarCall))

    /** The signed-in login (what the books session / backend token says) — the UI never passes an owner id. */
    private class Login(var business: String, var owner: String?)

    private class Access(val store: InMemoryKaiMemoryStore, val login: Login) : KaiMemoryAccess {
        var memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory? = memory.also { it.open(login.business, login.owner) }
        override suspend fun entities(): List<KnownEntity> = emptyList()
        /** App restart: a new memory object over the same phone storage. */
        fun restart() { memory = KaiPrivateMemory(store, clock = { 2_000L }) }
    }

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = listOf(PartySummary("c-kumar", "Kumar", "9000000001", 8000.0, null)), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools : KaiTools {
        val writes = mutableListOf<String>()
        override suspend fun products() = listOf(ProductRef("p-colgate", "Colgate", "PCS", BigDecimal("5")))
        override suspend fun parties(name: String) = listOf(PartyMatch("c-kumar", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("8000")))
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: com.shopai.app.books.model.PaymentMode, said: String) =
            ActionPlan("x", kind, partyName, partyId, amount, mode, said = said).also { writes += "prepare" }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { writes += "confirm"; return ActionOutcome.Done("X", null) }
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome { writes += "stock"; return ActionOutcome.Done("S", null) }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { writes += "reminder"; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders(): List<KaiReminder> = emptyList()
        override fun zone(): String = "Asia/Kolkata"
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String = "K-1"
    }

    private val store = InMemoryKaiMemoryStore()
    private val login = Login("biz-A", "owner-A")
    private val access = Access(store, login)
    private val engine = MorningWorkEngine(InMemoryMorningTaskStore(), clock = { nowZ })
    private val tools = Tools()

    /** What the app does (MorningWorkSources): the records + the signed-in owner's saved routine. */
    private suspend fun snapshot(): MorningSnapshot =
        records(login.business).copy(ownerId = login.owner, routine = access.current()?.let { MorningRoutines.load(it)?.orderedSections })

    private fun kai() = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { nowZ.toLocalDateTime() }, memory = access,
        morning = KaiMorningAccess { lang -> engine.generate(snapshot(), lang) })

    private suspend fun saved(): List<MorningSection>? = access.current()?.let { MorningRoutines.load(it)?.orderedSections }
    private suspend fun briefOrder(lang: KaiLang = KaiLang.TANGLISH) = engine.generate(snapshot(), lang).sections.map { it.first }
    private fun KaiTurn.button(label: String) = card!!.buttons.first { it.label == label }.action
    private fun parse(s: String, current: List<MorningSection>? = null, context: Boolean = false) = MorningRoutineParser.parse(s, current, context, listOf("Kumar"))

    // ---------------------------------------------------------------- understanding

    @Test
    fun routineSentencesAreUnderstood() {
        val full = parse("Morning-la first collections paakanum. Appuram stock. Last-la reminders.") as RoutineRequest.Set
        assertEquals(listOf(COLLECTIONS, STOCK), full.order.take(2))
        assertEquals(REMINDERS, full.order.last())
        assertEquals(MorningSection.entries.size, full.order.size)

        val stockFirst = parse("Morning brief-la stock first venum") as RoutineRequest.Set
        assertEquals(STOCK to RoutinePlace.FIRST, stockFirst.moved)
        assertEquals(STOCK, stockFirst.order.first())

        assertEquals(REMINDERS, (parse("Reminders last-la kaatu") as RoutineRequest.Set).order.last())
        assertEquals(REMINDERS, (parse("Reminders-a last-la podu") as RoutineRequest.Set).order.last())
        assertEquals(listOf(STOCK, COLLECTIONS), (parse("Stock first, collection next") as RoutineRequest.Set).order.take(2))

        val swap = parse("Collections first venam, stock first", current = MorningRoutines.DEFAULT) as RoutineRequest.Set
        assertEquals(STOCK to RoutinePlace.FIRST, swap.moved)
        assertEquals(STOCK, swap.order.first())

        assertEquals(RoutineRequest.Reset, parse("Default morning order-ku change pannu"))
        assertEquals(RoutineRequest.Change, parse("Morning routine change pannu"))

        // "First enakku pending payments kaatu" is the routine only right after the morning brief.
        assertNull(parse("First enakku pending payments kaatu"))
        assertEquals(COLLECTIONS to RoutinePlace.FIRST, (parse("First enakku pending payments kaatu", context = true) as RoutineRequest.Set).moved)

        val b = parse("Stock first, appuram purchases, last-la reminders") as RoutineRequest.Set
        assertEquals(listOf(STOCK, PAYMENTS), b.order.take(2))
        assertEquals(REMINDERS, b.order.last())
    }

    @Test
    fun otherSentencesAreNotARoutine() {
        for (s in listOf(
            "Kumar-ku 500 kuduthen", "tomorrow morning Kumar-ku call remind pannu", "morning brief", "Colgate stock evlo",
            "last week sales evlo", "stock first add pannu", "Morning 10 manikku Kumar-ku call remind pannu", "good morning",
            "Kumar last payment eppo", "innaiku enna important?", "reminders kaatu",
        )) assertNull(s, parse(s))
        // The router: routine sentences are MORNING_ROUTINE, the old ones keep their intent.
        val products = listOf(ProductRef("p-colgate", "Colgate", "PCS", BigDecimal("5")))
        fun intent(s: String) = KaiIntents.classify(s, nowZ.toLocalDateTime(), listOf("Kumar"), products)
        assertEquals(KaiIntentKind.MORNING_ROUTINE, intent("Morning-la first collections paakanum. Appuram stock. Last-la reminders."))
        assertEquals(KaiIntentKind.MORNING_ROUTINE, intent("Reminders-a last-la podu"))
        assertEquals(KaiIntentKind.MORNING_ROUTINE, intent("Stock first, collection next"))
        assertEquals(KaiIntentKind.MORNING_WORK, intent("morning brief kudu"))
        assertEquals(KaiIntentKind.CREATE_REMINDER, intent("tomorrow morning Kumar-ku call remind pannu"))
        assertTrue(KaiIntents.handledByKai(KaiIntentKind.MORNING_ROUTINE))
    }

    // ---------------------------------------------------------------- confirm before save

    @Test
    fun savedOnlyAfterSave() = runBlocking {
        val k = kai()
        val ask = k.ask("Morning-la first collections paakanum. Appuram stock. Last-la reminders.")
        assertEquals("Owner, இதை உங்க Morning Routine-ஆ save பண்ணவா?", ask.reply.text)
        assertEquals(listOf("Save", "Not now"), ask.card!!.buttons.map { it.label })
        assertTrue(ask.card!!.lines.first(), ask.card!!.lines.first().contains("Collections"))
        assertNull("nothing saved before Save", saved())
        assertTrue(k.waitingForLearningAnswer)

        val done = k.act(ask.button("Save"), KaiLang.TANGLISH)!!
        assertTrue(done.reply.text, done.reply.text.startsWith("Done Owner 👍 Unga Morning Routine save aagiduchu"))
        assertEquals(listOf(COLLECTIONS, STOCK), saved()!!.take(2))
        assertEquals(REMINDERS, saved()!!.last())
        // The brief follows it.
        val order = briefOrder()
        assertEquals(listOf(COLLECTIONS, STOCK, PAYMENTS, REMINDERS), order)
        assertTrue(tools.writes.isEmpty())
    }

    @Test
    fun notNowAndNoChangeNothing() = runBlocking {
        val k = kai()
        val ask = k.ask("Stock first, collection next")
        val no = k.act(ask.button("Not now"), KaiLang.TANGLISH)!!
        assertEquals("Seri Owner 👍 Routine edhuvum maathala.", no.reply.text)
        assertNull(saved())
        // Typed answers right after the question.
        k.ask("Stock first, collection next")
        assertEquals("Seri Owner 👍 Routine edhuvum maathala.", k.ask("venam").reply.text)
        assertNull(saved())
        k.ask("Stock first, collection next")
        assertTrue(k.ask("aama").reply.text.startsWith("Done Owner"))
        assertEquals(listOf(STOCK, COLLECTIONS), saved()!!.take(2))
        // An old card's Save after the owner moved on still needs that card's key (a stale key changes nothing).
        assertTrue(k.act(KaiAction.SaveRoutine("old"), KaiLang.TANGLISH)!!.reply.text.contains("palasu"))
        assertEquals(listOf(STOCK, COLLECTIONS), saved()!!.take(2))
    }

    @Test
    fun changeOneSectionThenResetToDefault() = runBlocking {
        val k = kai()
        k.act(k.ask("Morning-la first collections paakanum. Appuram stock. Last-la reminders.").button("Save"), KaiLang.TANGLISH)
        // Single change: Kai asks exactly about the moved section.
        val stock = k.ask("Collections first venam, stock first")
        assertEquals("Owner, next time Morning Work-la stock-a first kaattava?", stock.reply.text)
        k.act(stock.button("Save"), KaiLang.TANGLISH)
        assertEquals(STOCK, saved()!!.first())
        assertEquals(REMINDERS, saved()!!.last())
        // Never silently: until Save the old routine stays.
        val last = k.ask("Reminders-a last-la podu")
        assertTrue(last.reply.text, last.reply.text.contains("adhu dhaan unga Morning Routine"))
        // "Morning routine change pannu" → Kai asks the order, the next sentence is the routine.
        assertTrue(k.ask("Morning routine change pannu").reply.text.contains("enna order venum"))
        val next = k.ask("first enakku pending payments kaatu")
        assertEquals("Owner, next time Morning Work-la collections-a first kaattava?", next.reply.text)
        assertEquals(STOCK, saved()!!.first())
        k.act(next.button("Not now"), KaiLang.TANGLISH)
        // Reset to the default order (after Save).
        val reset = k.ask("Default morning order-ku change pannu")
        assertEquals("Owner, Morning Work-a default order-ku maathava?", reset.reply.text)
        assertEquals(STOCK, saved()!!.first())
        assertEquals("Done Owner 👍 Morning Work default order-ku maathitten.", k.act(reset.button("Save"), KaiLang.TANGLISH)!!.reply.text)
        assertNull(saved())
        assertEquals(listOf(COLLECTIONS, PAYMENTS, STOCK, REMINDERS), briefOrder())
    }

    @Test
    fun afterTheBriefFirstXMeansTheRoutine() = runBlocking {
        val k = kai()
        assertTrue(k.ask("morning brief kudu").reply.text.contains("Innaiku important"))
        // Collections are already first by default: Kai says so, nothing to save.
        assertTrue(k.ask("First enakku pending payments kaatu").reply.text.contains("adhu dhaan unga Morning Routine"))
        k.ask("morning brief kudu")
        val t = k.ask("First enakku stock kaatu")
        assertEquals("Owner, next time Morning Work-la stock-a first kaattava?", t.reply.text)
        // Without the brief just before, the same words are not taken as a routine.
        assertFalse(kai().ask("First enakku stock kaatu").reply.text.contains("Morning Work-la"))
    }

    // ---------------------------------------------------------------- owner isolation

    @Test
    fun ownerAAndOwnerBEachGetOnlyTheirOwnRoutine() = runBlocking {
        val k = kai()
        k.act(k.ask("Morning-la first collections paakanum. Appuram stock. Last-la reminders.").button("Save"), KaiLang.TANGLISH)
        // Owner B logs in to the same business (account switch: the app resets Kai's conversation).
        k.reset()
        login.owner = "owner-B"
        assertNull("B never sees A's routine", saved())
        assertEquals(listOf(COLLECTIONS, PAYMENTS, STOCK, REMINDERS), briefOrder())
        k.act(k.ask("Stock first, appuram purchases, last-la reminders").button("Save"), KaiLang.TANGLISH)
        assertEquals(listOf(STOCK, PAYMENTS), saved()!!.take(2))
        assertEquals(listOf(STOCK, PAYMENTS, COLLECTIONS, REMINDERS), briefOrder())
        // Shared business records are the same for both owners.
        val bBrief = engine.generate(snapshot(), KaiLang.TANGLISH)
        assertTrue(bBrief.text.contains("Kumar — ₹8,000 overdue") && bBrief.text.contains("ABC Traders"))

        // Back to A: A's routine, untouched by B.
        k.reset()
        login.owner = "owner-A"
        assertEquals(listOf(COLLECTIONS, STOCK), saved()!!.take(2))
        assertEquals(listOf(COLLECTIONS, STOCK, PAYMENTS, REMINDERS), briefOrder())

        // App restart + login again: still saved (phone storage), still per owner.
        access.restart()
        assertEquals(listOf(COLLECTIONS, STOCK), saved()!!.take(2))
        login.owner = "owner-B"
        assertEquals(listOf(STOCK, PAYMENTS), saved()!!.take(2))

        // Another business of the same owner: no routine there.
        login.business = "biz-B"
        login.owner = "owner-A"
        assertNull(saved())
        // Records stored per owner + business.
        assertTrue(store.load("biz-A", "owner-A").memories.all { it.ownerId == "owner-A" && it.businessId == "biz-A" })
        assertTrue(store.load("biz-A", "owner-B").memories.all { it.ownerId == "owner-B" })
    }

    @Test
    fun engineStartsCleanForAnotherOwner() = runBlocking {
        val a = records("biz-A").copy(ownerId = "owner-A")
        engine.prepare(a, lang = KaiLang.TANGLISH)
        engine.act(com.shopai.app.brain.morning.MorningCommand.Start)
        assertTrue(engine.state.started)
        // Owner B on the same phone + business: A's session (where A was, what A was asked) is not carried over.
        engine.prepare(a.copy(ownerId = "owner-B"), lang = KaiLang.TANGLISH)
        assertFalse(engine.state.started)
        assertNull(engine.state.currentTaskId)
    }

    // ---------------------------------------------------------------- notification text

    @Test
    fun notificationTexts() = runBlocking {
        val full = engine.generate(records("biz-A"), KaiLang.TANGLISH, com.shopai.app.brain.morning.MorningTrigger.SCHEDULED)
        assertEquals("Good morning Owner ☀️" to "Your Morning Work is ready.", MorningBriefs.notification(full))
        val empty = MorningWorkEngine(InMemoryMorningTaskStore(), clock = { nowZ }).generate(MorningSnapshot("biz-E"), KaiLang.TANGLISH)
        val (title, body) = MorningBriefs.notification(empty)
        assertEquals("Good morning Owner ☀️", title)
        assertEquals("Nothing urgent right now. You're all clear.", body)
        assertFalse(body.contains("₹"))
        // Records unavailable / last-synced copy: never claims "all clear".
        assertEquals("Your Morning Work is ready.", MorningBriefs.notification(null).second)
        val offline = MorningWorkEngine(InMemoryMorningTaskStore(), clock = { nowZ }).generate(MorningSnapshot("biz-E", offline = true), KaiLang.TANGLISH)
        assertEquals("Your Morning Work is ready.", MorningBriefs.notification(offline).second)
    }

    // ---------------------------------------------------------------- C: bounded loading

    /** A big business behind bounded queries: the loader asks a fixed number of questions and gets at most LIMIT rows each. */
    private class BigBusiness(val biz: String) : MorningQueries {
        val calls = mutableListOf<String>()
        val customers = (0 until 10_000).map { i ->
            MorningParty("$biz-c$i", "Customer $i", MorningPartyKind.CUSTOMER, null, 1_000.0 + i, LocalDate.of(2026, 10, 3).toEpochDay() - (i % 40) + 20)
        }
        val products = (0 until 10_000).map { i -> MorningProduct("$biz-p$i", "Product $i", "PCS", (i % 25).toDouble(), null, if (i % 5 == 0) 10.0 else null) }
        override suspend fun dueParties(kind: MorningPartyKind, untilDay: Long, limit: Int): List<MorningParty> {
            calls += "dueParties:$kind"
            if (kind != MorningPartyKind.CUSTOMER) return emptyList()
            return customers.filter { (it.nextDueDay ?: Long.MAX_VALUE) <= untilDay }.sortedWith(compareBy<MorningParty> { it.nextDueDay }.thenByDescending { it.pending }).take(limit)
        }
        override suspend fun lowStock(limit: Int): List<MorningProduct> {
            calls += "lowStock"
            return products.filter { it.reorderLevel != null && it.stock < it.reorderLevel!! }.sortedBy { if (it.stock <= 0) 0 else 1 }.take(limit)
        }
        override suspend fun expiring(untilDay: Long, limit: Int): List<MorningBatch> { calls += "expiring"; return emptyList() }
        override suspend fun drafts(limit: Int): List<MorningDraft> { calls += "drafts"; return (0 until limit).map { MorningDraft("$biz-d$it", "PURCHASE", it.toLong()) } }
        override suspend fun totals(today: Long): MorningTotals {
            calls += "totals"
            val due = customers.filter { (it.nextDueDay ?: Long.MAX_VALUE) <= today }
            return MorningTotals(due.sumOf { it.pending }, 0.0, customers.count { (it.nextDueDay ?: Long.MAX_VALUE) <= today + 3 } + 2_000 + 15)
        }
    }

    @Test
    fun loaderIsBoundedAndKeepsTheTopTasks() = runBlocking {
        val big = BigBusiness("biz-A")
        val t0 = System.nanoTime()
        val snap = MorningSnapshotLoader().load(MorningOwner("biz-A", "owner-A"), big, today.toEpochDay(), emptyList(), null, offline = false, syncedAtMillis = 1L)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(listOf("dueParties:CUSTOMER", "dueParties:SUPPLIER", "lowStock", "expiring", "drafts", "totals"), big.calls)
        assertTrue(snap.parties.size <= MorningWorkEngine.MAX_TASKS && snap.products.size <= MorningWorkEngine.MAX_TASKS && snap.drafts.size <= MorningWorkEngine.MAX_TASKS)
        assertEquals("owner-A", snap.ownerId)
        val engine = MorningWorkEngine(InMemoryMorningTaskStore(), clock = { nowZ })
        engine.prepare(snap, lang = KaiLang.ENGLISH)
        val plan = engine.state.plan!!
        assertEquals(MorningWorkEngine.MAX_TASKS, plan.tasks.size)
        // The same top tasks as reading everything (the old way).
        val everything = MorningWorkEngine(InMemoryMorningTaskStore(), clock = { nowZ })
        everything.prepare(MorningSnapshot("biz-A", parties = big.customers, products = big.products, drafts = snap.drafts), lang = KaiLang.ENGLISH)
        assertEquals(everything.state.plan!!.tasks.map { it.taskId }, plan.tasks.map { it.taskId })
        // Totals come from the aggregate, not from the bounded list.
        assertEquals(everything.state.summary!!.collections, engine.state.summary!!.collections, 0.001)
        assertTrue(plan.candidateCount > plan.tasks.size)
        println("bounded morning load over 10,000 customers / 10,000 products: %.1f ms, ${snap.parties.size + snap.products.size + snap.drafts.size} rows".format(ms))
    }
}
