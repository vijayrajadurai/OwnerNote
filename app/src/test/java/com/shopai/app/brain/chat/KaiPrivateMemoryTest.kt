package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiMeaning
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.LearningState
import com.shopai.app.brain.memory.MemorySource
import com.shopai.app.brain.memory.MemoryStatus
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductRef
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

/** Kai's private shop language: learned per business, used by voice and text alike, never acting on its own. */
class KaiPrivateMemoryTest {
    private val now = LocalDateTime.of(2026, 10, 3, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(
                PartySummary("c1", "Kumar Traders", "9000000001", 8500.0, null),
                PartySummary("c2", "Kumar Stores", "9000000002", 1200.0, null),
            ),
            suppliers = listOf(
                PartySummary("s1", "Selvam", null, 8000.0, null),
                PartySummary("s2", "ABC Traders", null, 20000.0, null),
            ),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val confirmed = mutableListOf<ActionPlan>()
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val partyList = listOf(
            PartyMatch("c1", "Kumar Traders", customer = true, phone = "+919000000001", balance = BigDecimal("8500.00")),
            PartyMatch("c2", "Kumar Stores", customer = true, phone = "+919000000002", balance = BigDecimal("1200.00")),
            PartyMatch("s1", "Selvam", customer = false, phone = null, balance = BigDecimal("8000.00")),
            PartyMatch("s2", "ABC Traders", customer = false, phone = null, balance = BigDecimal("20000.00")),
        )
        val productList = listOf(
            ProductRef("p1", "Colgate 200g", "PCS", BigDecimal("8")),
            ProductRef("p2", "Rice", "KG", BigDecimal("50")),
        )

        override suspend fun parties(name: String) = partyList.filter { it.name.contains(name, true) || name.contains(it.name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p-${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { confirmed += plan; return ActionOutcome.Done("PO-1", null) }
        override suspend fun products() = productList
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming)
            return ActionOutcome.Done("ST-1", if (incoming) product.stock + qty else product.stock - qty)
        }
    }

    /** The signed-in business (switchable, like a different login). */
    private class Access(val store: InMemoryKaiMemoryStore, var business: String, val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        var loggedIn = true
        override suspend fun current(): KaiPrivateMemory? {
            if (!loggedIn) return null
            memory.open(business, "owner-$business")
            return memory
        }
        override suspend fun entities(): List<KnownEntity> =
            tools.productList.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) } +
                tools.partyList.map { KnownEntity(it.id, it.name, if (it.customer) MemoryType.CUSTOMER_ALIAS else MemoryType.SUPPLIER_ALIAS) }
    }

    private val store = InMemoryKaiMemoryStore()
    private val tools = Tools()
    private val access = Access(store, "biz-A", tools)
    private val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, access)

    private fun KaiTurn.buttonActions() = card?.buttons.orEmpty().map { it.action }

    // 1 + never UNKNOWN → CONFIRMED
    @Test
    fun unknownSlangIsAskedNeverActedOn() = runBlocking {
        val t = kai.ask("Selvam-ku 5K thooki kudu")
        assertTrue(t.reply.text, t.reply.text.contains("`thooki kudu`-na Payment Out-nu mean pannureengala"))
        assertTrue(t.buttonActions().any { it is KaiAction.LearnMeaning })
        assertTrue(tools.prepared.isEmpty())
        assertTrue(access.memory.usable().isEmpty())
        assertEquals(LearningState.SUGGESTED, access.memory.observation("thooki kudu")!!.state)
    }

    // 2 + 26
    @Test
    fun ownerConfirmsThenDraftStillNeedsConfirmation() = runBlocking {
        kai.ask("Selvam-ku 5K thooki kudu")
        val t = kai.ask("Aama")
        assertTrue(t.reply.text, t.reply.text.startsWith("Got it Owner 👍 Indha shop-la: `thooki kudu` = Payment Out"))
        val m = access.memory.find("thooki kudu")!!
        assertEquals(KaiMeaning.PAYMENT_OUT, m.meaning)
        assertEquals(MemorySource.OWNER_CONFIRMED, m.source)
        assertEquals("biz-A", m.businessId)
        // The original message became a draft (Selvam, ₹5,000, payment out) — not saved yet.
        assertEquals(PlanKind.PAYMENT_OUT, tools.prepared.single().kind)
        assertEquals(BigDecimal("5000.00"), tools.prepared.single().amount)
        assertTrue(tools.confirmed.isEmpty())
        val confirm = t.buttonActions().filterIsInstance<KaiAction.ConfirmPlan>().single()
        kai.act(confirm, com.shopai.app.brain.KaiLang.TANGLISH)
        assertEquals(1, tools.confirmed.size)
    }

    // 3
    @Test
    fun ownerRejectsSlang() = runBlocking {
        kai.ask("Selvam-ku 5K thooki kudu")
        val t = kai.ask("illa")
        assertTrue(t.reply.text.contains("edhuvum remember pannala"))
        assertNull(access.memory.find("thooki kudu"))
        assertTrue(tools.prepared.isEmpty())
        // Asked again later: not the guess the owner refused.
        val again = kai.ask("Selvam-ku 5K thooki kudu")
        assertTrue(again.reply.text, again.reply.text.contains("Payment In"))
    }

    // 4
    @Test
    fun ownerCorrectsMeaning() = runBlocking {
        kai.ask("pottudu na stock out")
        val t = kai.ask("Illai, pottudu-na stock IN")
        assertTrue(t.reply.text, t.reply.text.contains("Indha business-la `pottudu` = Stock In-nu remember pannikiren"))
        assertEquals(KaiMeaning.STOCK_IN, access.memory.find("pottudu")!!.meaning)
        assertEquals(1, access.memory.list().size)
    }

    // 5 + 12 (English)
    @Test
    fun explicitRememberWorksImmediately() = runBlocking {
        val t = kai.ask("From now on `thooki kudu` means payment out")
        assertTrue(t.reply.text, t.reply.text.contains("`thooki kudu` = Payment Out"))
        assertEquals(MemorySource.OWNER_CREATED, access.memory.find("thooki kudu")!!.source)
        kai.ask("Selvam-ku 5000 thooki kudu")
        assertEquals(PlanKind.PAYMENT_OUT, tools.prepared.single().kind)
        // An English custom phrase.
        kai.ask("From now on 'send off' means payment out")
        kai.ask("send off 4000 to Selvam")
        assertEquals(BigDecimal("4000.00"), tools.prepared.last().amount)
    }

    // 6
    @Test
    fun forgetRemovesMeaning() = runBlocking {
        kai.ask("thooki kudu na payment out")
        val t = kai.ask("thooki kudu marandhudu")
        assertTrue(t.reply.text, t.reply.text.contains("marandhutten"))
        assertNull(access.memory.find("thooki kudu"))
        val again = kai.ask("Selvam-ku 5000 thooki kudu")
        assertTrue(tools.prepared.isEmpty())
        assertTrue(again.reply.text.contains("mean pannureengala"))
        // "Idha marandhudu" forgets the last one learned.
        kai.ask("pottudu na stock in")
        kai.ask("Idha marandhudu")
        assertNull(access.memory.find("pottudu"))
    }

    // 7
    @Test
    fun productNicknameStoresProductId() = runBlocking {
        // A name close to a product Kai has: Kai asks which product (a new product is offered too).
        val q = kai.ask("colgate paste 10 add pannu")
        assertTrue(q.reply.text, q.reply.text.contains("`Colgate Paste`-na Colgate 200g-aa"))
        assertTrue(q.buttonActions().any { it is KaiAction.OpenStockCamera })
        assertTrue(tools.stockChanges.isEmpty())
        val t = kai.ask("Colgate 200g")
        assertTrue(t.reply.text, t.reply.text.contains("`Colgate Paste` = Colgate 200g"))
        assertEquals("p1", access.memory.find("colgate paste")!!.referenceEntityId)
        assertTrue(t.reply.text.contains("Colgate 200g — 10 pieces stock-in"))
        // Next time it resolves straight away — still a draft.
        val out = kai.ask("colgate paste 5 stock out")
        assertTrue(out.reply.text, out.reply.text.contains("Colgate 200g — 5 pieces stock-out"))
        assertTrue(tools.stockChanges.isEmpty())
        kai.act(out.buttonActions().filterIsInstance<KaiAction.ConfirmStock>().single(), com.shopai.app.brain.KaiLang.TANGLISH)
        assertEquals(Triple("p1", BigDecimal("5"), false), tools.stockChanges.single())
    }

    // 8
    @Test
    fun customerNickname() = runBlocking {
        kai.ask("Kumar anna na Kumar Traders")
        assertEquals("c1", access.memory.find("kumar anna")!!.referenceEntityId)
        kai.ask("Kumar anna kitta 2000 vanginen")
        val p = tools.prepared.single()
        assertEquals("c1", p.partyId)
        assertEquals(PlanKind.PAYMENT_IN, p.kind)
        // Picking between two Kumars offers to remember the nickname (never silently).
        val which = kai.ask("Kumar-ku 500 kuduthen")
        val choose = which.buttonActions().filterIsInstance<KaiAction.ChoosePlan>().first { it.partyId == "c2" }
        val draft = kai.act(choose, com.shopai.app.brain.KaiLang.TANGLISH)!!
        assertTrue(draft.buttonActions().any { it is KaiAction.LearnAlias })
        assertNull(access.memory.find("kumar"))
    }

    // 9
    @Test
    fun supplierNickname() = runBlocking {
        kai.ask("ABC kadai na ABC Traders")
        kai.ask("ABC kadai-ku 3000 kuduthen")
        assertEquals("s2", tools.prepared.single().partyId)
        assertEquals(PlanKind.PAYMENT_OUT, tools.prepared.single().kind)
    }

    // 10 + 13
    @Test
    fun tamilAndMixedSlang() = runBlocking {
        val t = kai.ask("பொட்டுடு-னா stock in")
        assertTrue(t.reply.text, t.reply.text.contains("பொட்டுடு"))
        val d = kai.ask("Colgate 200g 10 பொட்டுடு")
        assertTrue(d.reply.text, d.reply.text.contains("Colgate 200g"))
        assertTrue(d.buttonActions().any { it is KaiAction.ConfirmStock })
        kai.ask("thooki kudu na payment out")
        kai.ask("Selvam-ku 2k thooki kudu please")
        assertEquals(BigDecimal("2000.00"), tools.prepared.last().amount)
    }

    // 14 + 15 — voice (speech-to-text) and text use the same brain and memory.
    @Test
    fun learnedByTextUsedByVoiceAndBack() = runBlocking {
        kai.ask("thooki kudu na payment out") // typed
        kai.ask("Selvam ku 5000 thooki kudu") // spoken → STT text (no hyphen)
        assertEquals(PlanKind.PAYMENT_OUT, tools.prepared.single().kind)
        kai.ask("pottudu na stock in") // spoken
        val typed = kai.ask("Colgate 200g 10 pottudu") // typed
        assertTrue(typed.reply.text.contains("stock-in"))
    }

    // 16 + 17 + 22 + 27
    @Test
    fun businessesNeverShareMemory() = runBlocking {
        kai.ask("thooki kudu na payment out")
        // Another business on the same phone: its own Kai, its own memory.
        val accessB = Access(store, "biz-B", tools)
        val kaiB = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, accessB)
        val unknownForB = kaiB.ask("Selvam-ku 5000 thooki kudu")
        assertTrue(unknownForB.reply.text.contains("mean pannureengala"))
        assertTrue(tools.prepared.isEmpty())
        kaiB.ask("thooki kudu na stock out")
        val stock = kaiB.ask("Colgate 200g 2 thooki kudu")
        assertTrue(stock.reply.text, stock.reply.text.contains("stock-out"))
        // Business A keeps its own meaning.
        kai.ask("Selvam-ku 5000 thooki kudu")
        assertEquals(PlanKind.PAYMENT_OUT, tools.prepared.single().kind)
        runBlocking {
            assertTrue(store.load("biz-A", "owner-biz-A").memories.all { it.businessId == "biz-A" })
            assertTrue(store.load("biz-B", "owner-biz-B").memories.all { it.businessId == "biz-B" })
            assertEquals(KaiMeaning.PAYMENT_OUT, store.load("biz-A", "owner-biz-A").memories.single().meaning)
            assertEquals(KaiMeaning.STOCK_OUT, store.load("biz-B", "owner-biz-B").memories.single().meaning)
        }
        // The same engine switched to B (a different login) sees only B.
        access.business = "biz-B"
        access.current()
        assertEquals(KaiMeaning.STOCK_OUT, access.memory.find("thooki kudu")!!.meaning)
    }

    // 18
    @Test
    fun ambiguousPhraseIsAsked() = runBlocking {
        kai.ask("pottudu na stock in")
        val t = kai.ask("Kumar account-la 500 pottudu")
        assertTrue(tools.prepared.isEmpty())
        assertTrue(tools.stockChanges.isEmpty())
        assertTrue(t.reply.text, t.reply.text.contains("?"))
    }

    // 19
    @Test
    fun currentInstructionOverridesMemory() = runBlocking {
        kai.ask("pottudu na stock in")
        val q = kai.ask("Today pottudu means stock out")
        assertTrue(q.reply.text, q.reply.text.contains("Should it always mean this"))
        kai.act(q.buttonActions().filterIsInstance<KaiAction.OnlyNow>().single(), com.shopai.app.brain.KaiLang.TANGLISH)
        val d = kai.ask("Colgate 200g 3 pottudu")
        assertTrue(d.reply.text, d.reply.text.contains("stock-out"))
        // The saved meaning didn't change.
        assertEquals(KaiMeaning.STOCK_IN, access.memory.find("pottudu")!!.meaning)
    }

    // 20 + 21 + 23
    @Test
    fun memorySurvivesRestartAndLogin() = runBlocking {
        kai.ask("thooki kudu na payment out")
        // App restart: a new memory object reads the same store.
        val restarted = KaiPrivateMemory(store)
        restarted.open("biz-A", "owner-biz-A")
        assertEquals(KaiMeaning.PAYMENT_OUT, restarted.find("thooki kudu")!!.meaning)
        // Logout: nothing loaded; login again: back.
        access.memory.close()
        assertNull(access.memory.find("thooki kudu"))
        access.loggedIn = false
        assertNull(access.current())
        access.loggedIn = true
        access.current()
        assertNotNull(access.memory.find("thooki kudu"))
    }

    // 24 + 25
    @Test
    fun disabledAndDeletedMemoriesAreNotUsed() = runBlocking {
        kai.ask("thooki kudu na payment out")
        val m = access.memory.find("thooki kudu")!!
        access.memory.setStatus(m.id, MemoryStatus.DISABLED)
        kai.ask("Selvam-ku 5000 thooki kudu")
        assertTrue(tools.prepared.isEmpty())
        assertEquals(1, access.memory.list().size) // still visible, switched off
        access.memory.setStatus(m.id, MemoryStatus.ACTIVE)
        kai.ask("Selvam-ku 5000 thooki kudu")
        assertEquals(1, tools.prepared.size)
        access.memory.setStatus(m.id, MemoryStatus.DELETED)
        assertTrue(access.memory.list().isEmpty())
    }

    @Test
    fun similarSpellingIsAskedNotAssumed() = runBlocking {
        kai.ask("thooki kudu na payment out")
        val q = kai.ask("Selvam-ku 500 thooki kudunga")
        assertTrue(q.reply.text, q.reply.text.contains("`thooki kudunga`-um"))
        assertTrue(tools.prepared.isEmpty())
        kai.ask("aama")
        assertEquals(PlanKind.PAYMENT_OUT, tools.prepared.single().kind)
        assertTrue("thooki kudunga" in access.memory.find("thooki kudu")!!.variants)
    }

    @Test
    fun owenerCanSeeWhatKaiLearned() = runBlocking {
        kai.ask("thooki kudu na payment out")
        kai.ask("red paste na Colgate 200g")
        val t = kai.ask("enna enna kathukitta?")
        val lines = t.card!!.lines
        assertTrue(lines.toString(), lines.any { it.contains("thooki kudu") && it.contains("Payment Out") })
        assertTrue(lines.any { it.contains("red paste") && it.contains("Colgate 200g") })
        // No database words in what the owner sees.
        val all = (lines + t.reply.text).joinToString(" ").lowercase()
        assertFalse(all.contains("businessid") || all.contains("embedding") || all.contains("training"))
    }

    @Test
    fun normalQuestionsAreNotTeachings() = runBlocking {
        assertNull(com.shopai.app.brain.memory.KaiTeaching.parse("Kumar na yaaru?", emptyList()))
        assertNull(com.shopai.app.brain.memory.KaiTeaching.parse("Rice stock evlo?", emptyList()))
        kai.ask("Selvam-ku 5000 kuduthen")
        assertEquals(PlanKind.PAYMENT_OUT, tools.prepared.single().kind)
        assertTrue(access.memory.list().isEmpty())
    }

    @Test
    fun globalStockInStillWorksWithoutMemory() = runBlocking {
        val t = kai.ask("Colgate 200g 20 stock in pannu")
        assertTrue(t.reply.text, t.reply.text.contains("Colgate 200g — 20 pieces stock-in"))
        // Text → voice → text continuity on the same product.
        val agent = KaiAgent(KaiBusinessBrain(Books()), Books(), object : KaiTools by tools {
            override suspend fun stock(product: String?) = listOf(com.shopai.app.brain.tools.StockFact("Rice", BigDecimal("8"), "KG"))
        }, { now }, access)
        agent.ask("Rice stock evlo?")
        val more = agent.ask("20 add pannu")
        assertTrue(more.reply.text, more.reply.text.contains("Rice — 20 kg stock-in"))
    }
}
