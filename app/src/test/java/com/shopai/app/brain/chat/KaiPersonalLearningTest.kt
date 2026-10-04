package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.LearningState
import com.shopai.app.brain.memory.MemoryType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * KAI — personal owner learning / private business memory: taught in chat
 * (text or voice), confirmed before saving, private to the owner + business,
 * listed / changed / forgotten in chat, never bypassing a draft's confirmation.
 */
class KaiPersonalLearningTest {
    private val now = LocalDateTime.of(2026, 10, 3, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Kumar", "9000000001", 8500.0, null)),
            suppliers = listOf(PartySummary("s1", "Sri Lakshmi Traders", null, 20000.0, null)),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val confirmed = mutableListOf<ActionPlan>()
        val discarded = mutableListOf<ActionPlan>()
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val reminders = mutableListOf<KaiReminder>()
        val logs = mutableListOf<Triple<String, String, String?>>()
        val partyList = listOf(
            PartyMatch("c1", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("8500.00")),
            PartyMatch("s1", "Sri Lakshmi Traders", customer = false, phone = null, balance = BigDecimal("20000.00")),
        )
        val productList = listOf(ProductRef("p1", "Colgate", "PCS", BigDecimal("8"), mapOf("BOX" to BigDecimal(12), "CARTON" to BigDecimal(48))), ProductRef("p2", "Rice", "KG", BigDecimal("50")))

        override suspend fun parties(name: String) = partyList.filter { it.name.contains(name, true) || name.contains(it.name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p-${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { confirmed += plan; return ActionOutcome.Done("PO-1", null) }
        override suspend fun discard(plan: ActionPlan) { discarded += plan }
        override suspend fun products() = productList
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming)
            return ActionOutcome.Done("ST-1", null)
        }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { reminders += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders(): List<KaiReminder> = reminders
        override fun zone(): String = "Asia/Kolkata"
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String {
            logs += Triple(intent, "$result|$status", input)
            return "K-${logs.size}"
        }
    }

    /** The signed-in login (owner + business) — what the session says, never an id from the screen. */
    private class Access(store: InMemoryKaiMemoryStore, var business: String, var owner: String?, val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory = memory.also { it.open(business, owner) }
        override suspend fun entities(): List<KnownEntity> =
            tools.productList.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) } +
                tools.partyList.map { KnownEntity(it.id, it.name, if (it.customer) MemoryType.CUSTOMER_ALIAS else MemoryType.SUPPLIER_ALIAS) }
    }

    private val store = InMemoryKaiMemoryStore()
    private val tools = Tools()
    private val access = Access(store, "biz-A", "owner-A", tools)
    private fun agent(a: Access = access) = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, a)
    private val kai = agent()

    private fun KaiTurn.actions() = card?.buttons.orEmpty().map { it.action }
    private fun KaiTurn.labels() = card?.buttons.orEmpty().map { it.label }
    private fun KaiTurn.button(label: String) = card!!.buttons.first { it.label == label }.action
    private suspend fun find(phrase: String) = access.current().find(phrase)
    private fun noCommittedWrites() {
        assertTrue(tools.confirmed.isEmpty())
        assertTrue(tools.stockChanges.isEmpty())
    }

    @Test
    fun pendingPaymentVendaIsCancellationBeforePrivateWordLearning() = runBlocking {
        val local = agent(Access(store, "biz-cancel", "owner-A", tools))
        val draft = local.ask("Kumar gave me 1000 cash today")
        assertEquals(PlanKind.PAYMENT_IN, draft.plan!!.kind)
        val cancelled = local.ask("Venda")
        assertTrue(cancelled.reply.text, cancelled.reply.text.contains("cancel pannitten", true))
        assertTrue(tools.confirmed.isEmpty())
        assertFalse(local.waitingForLearningAnswer)
        assertNull(local.conversationState.pendingDraft)
    }

    @Test
    fun numericDraftCorrectionWinsBeforePrivateMemoryLearning() = runBlocking {
        val local = agent(Access(store, "biz-correction", "owner-A", tools))
        local.ask("Kumar 5000 cash kuduthaan")
        val corrected = local.ask("Illai Kai, 500 dhaan")
        assertEquals(BigDecimal("500.00"), corrected.plan!!.amount)
        assertTrue(tools.confirmed.isEmpty())
        assertFalse(local.waitingForLearningAnswer)
        assertEquals(1, tools.discarded.size)
    }

    @Test
    fun changingBusinessScopeClearsPendingDueDate() = runBlocking {
        val isolatedAccess = Access(store, "biz-scope-a", "owner-A", tools)
        val local = agent(isolatedAccess)
        local.ask("Praba ku 5000 tharanum")
        assertEquals(KaiPendingQuestion.DUE_DATE, local.conversationState.pendingQuestion)
        assertEquals("Praba", local.conversationState.pendingEntity)
        isolatedAccess.business = "biz-scope-b"
        val nextBusinessTurn = local.ask("October 10")
        assertNull(local.conversationState.pendingQuestion)
        assertNull(local.conversationState.pendingEntity)
        assertNull(local.conversationState.pendingAmount)
        assertNull(local.conversationState.pendingPaymentDirection)
        assertNull(local.conversationState.lastDate)
    }

    @Test
    fun changingBusinessScopeClearsConversationContext() = runBlocking {
        val isolatedAccess = Access(store, "biz-scope-a", "owner-A", tools)
        val local = agent(isolatedAccess)
        local.ask("Kumar enakku 20000 tharanum")
        assertEquals(BigDecimal("20000.00"), local.conversationState.lastAmount)
        isolatedAccess.business = "biz-scope-b"
        val nextBusinessTurn = local.ask("Eppa?")
        assertTrue(nextBusinessTurn.reply.text, !nextBusinessTurn.reply.text.contains("20,000"))
        assertNull(local.conversationState.lastAmount)
        assertNull(local.conversationState.lastPerson)
    }

    // TEST 1 + section 4/6: unknown unit is asked, saved only after yes, then a normal stock-in DRAFT.
    @Test
    fun ownerTeachesPottiThenStockInDraft() = runBlocking {
        val ask = kai.ask("Colgate 2 petti vandhiruku")
        assertEquals("Owner, `petti` na box-ah? Packet-ah? Vera meaning-ah?", ask.reply.text)
        assertEquals(listOf("Box", "Packet", "Piece", "Vera meaning"), ask.labels())
        val confirm = kai.ask("Box")
        assertTrue(confirm.reply.text, confirm.reply.text.contains("`petti` = 1 box") && confirm.reply.text.endsWith("Save pannava?"))
        assertNull("not saved before the owner says yes", find("petti"))
        val done = kai.ask("Aama")
        assertTrue(done.reply.text, done.reply.text.startsWith("Done Owner 👍 Inime `petti` nu sonna box-nu purinjukkuven."))
        assertTrue(done.reply.text, done.reply.text.contains("Colgate — 2 boxes = 24 pieces stock-in"))
        assertTrue(done.actions().any { it is KaiAction.ConfirmStock })
        noCommittedWrites()

        // The owner's own teaching sentence: "Kai, enga kadaiyila 'potti' na 1 box." → Save.
        val teach = kai.ask("Kai, enga kadaiyila 'potti' na 1 box.")
        assertEquals("Sari Owner 👍 Indha business-ku `potti` = 1 box-nu purinjukitten. Save pannava?", teach.reply.text)
        assertEquals(listOf("Save", "Not now"), teach.labels())
        kai.act(teach.button("Save"), KaiLang.TANGLISH)
        val m = find("potti")!!
        assertEquals(MemoryType.UNIT_ALIAS, m.memoryType)
        assertEquals("box", m.meaningValue)
        assertEquals("UNIT_ALIAS", m.category)
        assertEquals("owner-A", m.ownerId)
        assertEquals("biz-A", m.businessId)
        val draft = kai.ask("Colgate 2 potti vandhudhu")
        assertTrue(draft.reply.text, draft.reply.text.contains("Colgate — 2 boxes = 24 pieces stock-in"))
        noCommittedWrites()
        // Only the existing Confirm writes the stock.
        kai.act(draft.actions().filterIsInstance<KaiAction.ConfirmStock>().single(), KaiLang.TANGLISH)
        // 2 potti = 2 boxes = 24 pieces (Colgate: 1 box = 12) — never 2 pieces.
        assertEquals(Triple("p1", BigDecimal("24"), true), tools.stockChanges.single())
    }

    // TEST 2 + 8 + 16: another owner never gets Owner A's meanings.
    @Test
    fun ownerBNeverKnowsOwnerAsWords() = runBlocking {
        kai.ask("'potti' na box")
        kai.ask("Save")
        kai.ask("anna na Kumar")
        kai.ask("aama")
        assertEquals("c1", find("anna")!!.referenceEntityId)
        // Owner A asks: Kai knows.
        assertTrue(kai.ask("potti na enna?").reply.text.contains("box"))
        // Owner B (same shop, different login).
        val b = Access(store, "biz-A", "owner-B", tools)
        val kaiB = agent(b)
        val q = kaiB.ask("potti na enna?")
        assertFalse(q.reply.text, q.reply.text.contains("box"))
        assertTrue(q.reply.text, q.reply.text.contains("theriyala"))
        val who = kaiB.ask("anna yaaru?")
        assertFalse(who.reply.text, who.reply.text.contains("Kumar"))
        assertNull(b.current().find("potti"))
        assertNull(b.current().find("anna"))
        // Stored only under A.
        assertTrue(store.load("biz-A", "owner-B").memories.isEmpty())
        assertTrue(store.load("biz-A", "owner-A").memories.all { it.ownerId == "owner-A" && it.businessId == "biz-A" })
    }

    // TEST 3: Tamil script teaching and use.
    @Test
    fun tamilScriptTeaching() = runBlocking {
        val t = kai.ask("'பொட்டி'ன்னா box.")
        assertTrue(t.reply.text, t.reply.text.contains("`பொட்டி` = 1 box"))
        kai.ask("ஆமா")
        assertEquals("box", find("பொட்டி")!!.meaningValue)
        val d = kai.ask("Colgate 2 பொட்டி வந்திருக்கு.")
        assertTrue(d.reply.text, d.reply.text.contains("2 boxes = 24 pieces"))
        assertTrue(d.actions().any { it is KaiAction.ConfirmStock })
        noCommittedWrites()
    }

    // TEST 4: Tanglish word → stock context.
    @Test
    fun tanglishMaalMeansStock() = runBlocking {
        val t = kai.ask("maal na stock")
        assertEquals("Seri Owner 😄 `maal` = `stock` nu save pannava?", t.reply.text)
        kai.ask("aama")
        val d = kai.ask("maal vandhuruku")
        assertTrue(d.reply.text, d.direct is KaiAction.OpenStockCamera)
        // Natural teaching: "naan 'maal' nu sonna stock meaning" is the same memory.
        assertEquals("stock", find("maal")!!.meaningValue)
        noCommittedWrites()
    }

    // Section 12/13: natural teaching sentences inside chat.
    @Test
    fun naturalTeachingSentences() = runBlocking {
        val cover = kai.ask("Enga kadaiyila packet-ku 'cover' nu solvom")
        assertTrue(cover.reply.text, cover.reply.text.contains("`cover` = 1 packet"))
        kai.ask("aama")
        assertEquals("packet", find("cover")!!.meaningValue)
        val maal = kai.ask("Kai, naan 'maal' nu sonna stock meaning")
        assertEquals("Seri Owner 😄 `maal` = `stock` nu save pannava?", maal.reply.text)
        kai.ask("Not now")
        assertNull(find("maal"))
        // Ordinary messages are not teachings (no interruption).
        for (s in listOf("Kumar-ku 500 kuduthen", "innaiku sales evlo?", "Colgate stock evlo?", "enakku theriyala na sollu")) {
            assertFalse(s, kai.ask(s).reply.text.contains("save pannava"))
        }
    }

    // TEST 5: English teaching.
    @Test
    fun englishTeaching() = runBlocking {
        val t = kai.ask("Here we call cartons 'boxes'.")
        assertTrue(t.reply.text, t.reply.text.contains("`boxes` = 1 carton") && t.reply.text.endsWith("Save it?"))
        kai.ask("yes")
        val m = find("boxes")!!
        assertEquals(MemoryType.UNIT_ALIAS, m.memoryType)
        assertEquals("carton", m.meaningValue)
        val d = kai.ask("Colgate 3 boxes stock in")
        assertTrue(d.reply.text, d.reply.text.contains("3 cartons = 144 pieces"))
    }

    // TEST 6: phrase learning → a payment DRAFT (never posted).
    @Test
    fun phraseLearningGivesPaymentDraft() = runBlocking {
        val t = kai.ask("'kaasu pottaan' means customer payment received.")
        assertTrue(t.reply.text, t.reply.text.contains("business meaning"))
        kai.ask("aama")
        assertEquals("PAYMENT_TERM", find("kaasu pottaan")!!.category)
        kai.ask("Kumar kaasu pottaan 5000")
        val p = tools.prepared.single()
        assertEquals(PlanKind.PAYMENT_IN, p.kind)
        assertEquals("c1", p.partyId)
        assertEquals(BigDecimal("5000.00"), p.amount)
        assertTrue(tools.confirmed.isEmpty())
    }

    // TEST 7: correction — the new meaning wins, the old one is history.
    @Test
    fun correctionReplacesMeaning() = runBlocking {
        kai.ask("pocha = sold")
        kai.ask("aama")
        val sold = kai.ask("Colgate 2 pocha")
        assertTrue(sold.reply.text, sold.reply.text.contains("stock-out"))
        val ask = kai.ask("pocha = damaged")
        assertTrue(ask.reply.text, ask.reply.text.contains("Ippo `pocha` = Stock Out") && ask.reply.text.contains("update pannava?"))
        assertEquals("STOCK_OUT", find("pocha")!!.meaningType) // not changed before yes
        kai.ask("aama")
        val m = find("pocha")!!
        assertEquals("damaged", m.meaningValue)
        assertEquals(LearningState.CORRECTED, m.learningState)
        assertEquals("Stock Out", m.correctedFrom)
        val after = kai.ask("Colgate 2 pocha")
        assertFalse(after.reply.text, after.reply.text.contains("stock-out"))
        assertFalse(after.reply.text, after.reply.text.contains("stock add"))
        noCommittedWrites()
    }

    // TEST 9 + 17: business scope, and owner-wide words used only where a business has none.
    @Test
    fun businessSpecificThenOwnerWide() = runBlocking {
        kai.ask("'potti' na box")
        kai.ask("aama")
        access.business = "biz-B"
        val kaiB = agent()
        assertNull(find("potti"))
        kaiB.ask("'potti' na carton")
        kaiB.ask("aama")
        assertTrue(kaiB.ask("Colgate 2 potti vandhudhu").reply.text.contains("2 cartons = 96 pieces"))
        access.business = "biz-A"
        assertTrue(agent().ask("Colgate 2 potti vandhudhu").reply.text.contains("2 boxes = 24 pieces"))

        // Owner-wide: "ella kadaiyilum 'maal' na stock" — every business of this owner, unless one says otherwise.
        val wide = kai.ask("ella kadaiyilum 'maal' na stock")
        assertTrue(wide.reply.text, wide.reply.text.contains("?"))
        kai.ask("aama")
        access.business = "biz-C"
        assertEquals("stock", find("maal")!!.meaningValue)
        assertEquals(KaiPrivateMemory.OWNER_SCOPE, find("maal")!!.businessId)
        access.business = "biz-B"
        kaiB.ask("maal na goods")
        kaiB.ask("aama")
        assertEquals("goods", find("maal")!!.meaningValue) // the business's own meaning wins
        access.business = "biz-C"
        assertEquals("stock", find("maal")!!.meaningValue)
        // Another owner never sees this owner's owner-wide words.
        access.owner = "owner-Z"
        assertNull(find("maal"))
    }

    // TEST 10 + 11: taught by text → used by voice (speech-to-text words), and the other way round.
    @Test
    fun voiceAndTextShareMemory() = runBlocking {
        kai.ask("'potti' na box") // typed
        kai.ask("Save")
        val voice = kai.ask("colgate rendu potti vandhudhu") // speech-to-text: lower case, number word
        assertTrue(voice.reply.text, voice.reply.text.contains("Colgate — 2 boxes = 24 pieces stock-in"))
        kai.ask("cover na packet") // spoken
        kai.ask("ஆமா") // spoken answer in Tamil script
        val typed = kai.ask("Colgate 4 cover vandhiruku") // typed
        // "cover" = packet; Colgate's packet size isn't known yet → Kai asks, never counts packets as pieces.
        assertEquals("Owner, 1 packet-la evlo pieces irukku?", typed.reply.text)
        assertTrue(kai.ask("6").reply.text.contains("4 packets = 24 pieces"))
        // The voice screen's router sends teaching to the same Kai.
        assertEquals(KaiIntentKind.LEARN_SLANG, KaiIntents.classify("potti na box", now, emptyList(), tools.productList))
        assertTrue(KaiIntents.handledByKai(KaiIntentKind.LEARN_SLANG))
    }

    // TEST 12 + 13: unknown business-critical words are asked; nothing is drafted or committed.
    @Test
    fun unknownCriticalWordIsAskedNeverGuessed() = runBlocking {
        val money = kai.ask("Kumar-ku 5000 thooki kudu")
        assertTrue(money.reply.text, money.reply.text.contains("?"))
        val stock = kai.ask("Colgate 5 seetu vandhiruku")
        assertTrue(stock.reply.text, stock.reply.text.contains("`seetu` na box-ah?"))
        assertTrue(tools.prepared.isEmpty())
        noCommittedWrites()
        assertNull(find("seetu"))
        // "Not now" → nothing saved, and Kai doesn't ask the same word again.
        kai.ask("illa")
        assertNull(find("seetu"))
    }

    // TEST 14 + 18/19: forget (asked first), change meaning, direct correction.
    @Test
    fun forgetAndChangeThroughChat() = runBlocking {
        kai.ask("'potti' na box")
        kai.ask("aama")
        val change = kai.ask("potti meaning change pannu")
        assertTrue(change.reply.text, change.reply.text.startsWith("`potti`-ku new meaning enna Owner?"))
        val confirm = kai.ask("carton")
        assertTrue(confirm.reply.text, confirm.reply.text.contains("update pannava?"))
        kai.ask("aama")
        assertEquals("carton", find("potti")!!.meaningValue)
        kai.ask("potti meaning carton illa box")
        kai.ask("aama")
        assertEquals("box", find("potti")!!.meaningValue)

        val forget = kai.ask("potti meaning forget")
        assertEquals("Sure Owner. `potti` memory remove pannava?", forget.reply.text)
        assertEquals(listOf("Forget", "Cancel"), forget.labels())
        assertNotNull(find("potti"))
        kai.act(forget.button("Forget"), KaiLang.TANGLISH)
        assertNull(find("potti"))
        // Kai asks again next time (it is unknown now).
        assertTrue(kai.ask("Colgate 2 potti vandhudhu").reply.text.contains("`potti` na box-ah?"))
        // Cancel keeps it.
        kai.ask("Box")
        kai.ask("aama")
        val again = kai.ask("potti marandhudu")
        kai.act(again.button("Cancel"), KaiLang.TANGLISH)
        assertNotNull(find("potti"))
    }

    // TEST 15: list — only this owner's confirmed words, with Edit / Forget / Keep.
    @Test
    fun listWhatTheOwnerTaught() = runBlocking {
        kai.ask("'potti' na box")
        kai.ask("aama")
        kai.ask("'kaasu pottaan' means customer payment received")
        kai.ask("aama")
        kai.ask("cover na packet") // asked, never confirmed → not listed
        val list = kai.ask("Kai naan enna teach panniruken?")
        assertTrue(list.reply.text, list.reply.text.startsWith("Owner, neenga enakku 2 things teach pannirukeenga:"))
        assertTrue(list.reply.text.contains("1. `potti` = box"))
        assertTrue(list.reply.text.contains("2. `kaasu pottaan` = Payment In"))
        assertEquals(listOf("Edit", "Forget", "Keep"), list.labels())
        val forget = kai.act(list.button("Forget"), KaiLang.TANGLISH)!!
        assertTrue(forget.reply.text.contains("Edha marakkanum"))
        val confirm = kai.ask("potti")
        assertEquals("Sure Owner. `potti` memory remove pannava?", confirm.reply.text)
        // Another owner's list is their own.
        val kaiB = agent(Access(store, "biz-A", "owner-B", tools))
        assertTrue(kaiB.ask("Kai naan enna teach panniruken?").reply.text.contains("innum unga shop words edhuvum kathukkala"))
    }

    // Section 9: "Illai Kai, avan bill mattum kuduthaan" right after a draft — dropped, then remembered after yes.
    @Test
    fun correctionAfterAWrongDraft() = runBlocking {
        val draft = kai.ask("Kumar bill kuduthaan 500")
        assertEquals(PlanKind.PAYMENT_IN, tools.prepared.single().kind)
        assertTrue(draft.reply.text, draft.actions().any { it is KaiAction.ConfirmPlan })
        val fix = kai.ask("Illai Kai, avan bill mattum kuduthaan")
        assertEquals(
            "Purinjuchu Owner. Naan thappa purinjukitten — andha draft save aagala.\n" +
                "Indha business context-la `bill kuduthaan` = bill handed over, payment illa-nu update pannava?",
            fix.reply.text,
        )
        assertEquals(1, tools.discarded.size) // the wrong draft is dropped, never posted
        assertNull(find("bill kuduthaan")) // not remembered before yes
        kai.ask("aama")
        val m = find("bill kuduthaan")!!
        assertEquals(MemoryType.CORRECTION, m.memoryType)
        assertEquals("NOT_PAYMENT", m.meaningType)
        // Next time it is not a payment — and Kai says why.
        val next = kai.ask("Kumar bill kuduthaan 300")
        assertTrue(next.reply.text, next.reply.text.contains("`bill kuduthaan` payment illa-nu neenga sollirukeenga"))
        assertEquals(1, tools.prepared.size)
        // A real payment still works.
        kai.ask("Kumar 400 vanginen")
        assertEquals(2, tools.prepared.size)
        assertTrue(tools.confirmed.isEmpty())
    }

    // Section 8: context — a learned stock word next to a time is a time.
    @Test
    fun contextDecidesMeaning() = runBlocking {
        kai.ask("'aachu' na stock out")
        kai.ask("aama")
        assertTrue(kai.ask("Colgate 2 aachu").reply.text.contains("stock-out"))
        val time = kai.ask("2 mani aachu")
        assertFalse(time.reply.text, time.reply.text.contains("stock-out"))
        assertFalse(time.reply.text, time.reply.text.contains("Product"))
        assertEquals(0, tools.stockChanges.size)
    }

    // Section 7: reminder phrase.
    @Test
    fun reminderTermLearning() = runBlocking {
        val t = kai.ask("'konjam nerathula' na 10 minutes")
        assertTrue(t.reply.text, t.reply.text.contains("`konjam nerathula` = 10 minutes"))
        kai.ask("aama")
        assertEquals("REMINDER_TERM", find("konjam nerathula")!!.category)
        kai.ask("Kumar-ku call pannanum konjam nerathula remind pannu")
        val r = tools.reminders.single()
        val at = java.time.Instant.ofEpochMilli(r.triggerAt).atZone(ZoneId.of("Asia/Kolkata")).toLocalDateTime()
        assertEquals(now.plusMinutes(10), at)
    }

    // Section 28: learning goes to the action log — the meaning only, no conversation text.
    @Test
    fun learningIsLoggedWithoutConversation() = runBlocking {
        kai.ask("'potti' na box")
        kai.ask("aama")
        val log = tools.logs.single { it.first == KaiIntents.LEARN_PERSONAL_TERM }
        assertEquals("potti = box|CONFIRMED", log.second)
        assertNull(log.third)
        // Sensitive guessing never happens: a plain sentence with "na" is not a teaching.
        kai.ask("enakku theriyala na sollu")
        assertEquals(1, tools.logs.count { it.first == KaiIntents.LEARN_PERSONAL_TERM })
    }
}
