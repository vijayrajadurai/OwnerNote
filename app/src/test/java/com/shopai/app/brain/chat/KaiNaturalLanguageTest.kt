package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Natural small talk however it is spelled or heard ("saptiya / saptia / saptya / saaptiyaa",
 * "un name enna / un peru enna / nee yaaru") — the same meaning for typed and spoken words,
 * through the one KaiAgent, without ever taking a business message.
 */
@RunWith(Parameterized::class)
class KaiNaturalLanguageTest(private val area: String, private val input: String, private val expected: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}: {1}")
        fun cases(): List<Array<Any>> = listOf(
            // A. identity
            arrayOf("identity", "nee yaaru?", "WHO"),
            arrayOf("identity", "Kai nee yaaru?", "WHO"),
            arrayOf("identity", "neenga yaaru?", "WHO"),
            arrayOf("identity", "who are you?", "WHO"),
            arrayOf("identity", "who r u", "WHO"),
            arrayOf("identity", "நீ யாரு?", "WHO"),
            arrayOf("identity", "நீங்க யார்?", "WHO"),
            // B. name
            arrayOf("name", "un name enna?", "NAME"),
            arrayOf("name", "unga name enna?", "NAME"),
            arrayOf("name", "un peru enna?", "NAME"),
            arrayOf("name", "unga peru enna?", "NAME"),
            arrayOf("name", "Kai un name enna", "NAME"),
            arrayOf("name", "what is your name?", "NAME"),
            arrayOf("name", "whats ur name", "NAME"),
            arrayOf("name", "உன் பேர் என்ன?", "NAME"),
            arrayOf("name", "உங்க பெயர் என்ன?", "NAME"),
            // C. status
            arrayOf("status", "dei Kai inniku romba tired ah iruka?", "STATUS"),
            arrayOf("status", "busy ah irukiya?", "STATUS"),
            arrayOf("status", "are you tired?", "STATUS"),
            arrayOf("status", "epdi iruka Kai?", "HOW_ARE_YOU"),
            arrayOf("status", "eppadi irukeenga?", "HOW_ARE_YOU"),
            // D + L. eating, every spelling
            arrayOf("ate", "saptiya?", "ATE"),
            arrayOf("ate", "saptia?", "ATE"),
            arrayOf("ate", "saptya?", "ATE"),
            arrayOf("ate", "saaptiya?", "ATE"),
            arrayOf("ate", "saaptiyaa?", "ATE"),
            arrayOf("ate", "saaptacha?", "ATE"),
            arrayOf("ate", "saaptingala?", "ATE"),
            arrayOf("ate", "saptingala?", "ATE"),
            arrayOf("ate", "saapiteengala?", "ATE"),
            arrayOf("ate", "kai saptiya?", "ATE"),
            arrayOf("ate", "nee saptiya?", "ATE"),
            arrayOf("ate", "saptya Kai?", "ATE"),
            arrayOf("ate", "did you eat?", "ATE"),
            arrayOf("ate", "have you eaten?", "ATE"),
            arrayOf("ate", "சாப்டியா?", "ATE"),
            arrayOf("ate", "சாப்பிட்டியா?", "ATE"),
            // E. greeting
            arrayOf("greeting", "hi", "HELLO"),
            arrayOf("greeting", "hiii Kai", "HELLO"),
            arrayOf("greeting", "vanakkam", "HELLO"),
            arrayOf("greeting", "good morning", "GREETING_MORNING"),
            // F. thanks
            arrayOf("thanks", "thanks Kai", "THANKS"),
            arrayOf("thanks", "thanku", "THANKS"),
            arrayOf("thanks", "tq", "THANKS"),
            arrayOf("thanks", "romba nandri", "THANKS"),
            // G. praise
            arrayOf("praise", "super Kai", "PRAISE"),
            arrayOf("praise", "semma", "PRAISE"),
            // OK / acknowledgement
            arrayOf("ok", "hmm", "OK"),
            arrayOf("ok", "seri", "OK"),
            // N. business messages are never small talk
            arrayOf("business", "Kumar-ku 5000 kuduthen", "NONE"),
            arrayOf("business", "Colgate 2 box add", "NONE"),
            arrayOf("business", "Kumar-ku 10 minutes kalichi call pannanum", "NONE"),
            arrayOf("business", "25000 la 18% GST evlo?", "NONE"),
            arrayOf("business", "Colgate stock evlo?", "NONE"),
            arrayOf("business", "inniku sales evlo?", "NONE"),
            arrayOf("business", "un name enna 5000", "NONE"),
            arrayOf("business", "saptiya Kumar-ku call pannu", "NONE"),
        )
    }

    @Test
    fun meaning() {
        val kind = KaiSmallTalk.kindOf(input)
        assertEquals(input, expected, kind?.name ?: "NONE")
        // Voice screen parity: the same words take the same road (Kai Chat) when spoken on Pesunga.
        if (expected != "NONE") {
            assertEquals(input, KaiIntentKind.CHAT, KaiIntents.classify(input, LocalDateTime.of(2026, 10, 6, 10, 0), emptyList(), emptyList()))
        }
    }
}

/** The same understanding through the whole KaiAgent: replies, language, context, business priority, no silent failure. */
class KaiNaturalLanguageAgentTest {
    private val now = LocalDateTime.of(2026, 10, 6, 10, 0)

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
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val reminders = mutableListOf<KaiReminder>()
        val partyList = listOf(
            PartyMatch("c1", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("8500.00")),
            PartyMatch("s1", "Sri Lakshmi Traders", customer = false, phone = null, balance = BigDecimal("20000.00")),
        )
        val productList = listOf(ProductRef("p1", "Colgate", "PCS", BigDecimal("8"), mapOf("BOX" to BigDecimal(12))), ProductRef("p2", "Rice", "KG", BigDecimal("50")))

        override suspend fun parties(name: String) = partyList.filter { it.name.contains(name, true) || name.contains(it.name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p-${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { confirmed += plan; return ActionOutcome.Done("PO-1", null) }
        override suspend fun discard(plan: ActionPlan) {}
        override suspend fun products() = productList
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming)
            return ActionOutcome.Done("ST-1", null)
        }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { reminders += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders(): List<KaiReminder> = reminders
        override fun zone(): String = "Asia/Kolkata"
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
    }

    private class Access(store: InMemoryKaiMemoryStore, val business: String, val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory = memory.also { it.open(business, "owner-A") }
        override suspend fun entities(): List<KnownEntity> =
            tools.productList.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) } +
                tools.partyList.map { KnownEntity(it.id, it.name, if (it.customer) MemoryType.CUSTOMER_ALIAS else MemoryType.SUPPLIER_ALIAS) }
    }

    private val store = InMemoryKaiMemoryStore()
    private val tools = Tools()
    private val access = Access(store, "biz-nlu", tools)
    private val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, access)

    private fun noWrites() {
        assertTrue(tools.confirmed.isEmpty())
        assertTrue(tools.stockChanges.isEmpty())
        assertTrue(tools.reminders.isEmpty())
    }

    // D + L: every spelling of "did you eat?" gets the same friendly answer — never "what does `saptia` mean?".
    @Test
    fun eatingQuestionEverySpelling() = runBlocking {
        for (s in listOf("saptiya?", "saptia?", "saptya?", "saaptiya?", "saaptiyaa?", "saptya Kai?")) {
            val t = kai.ask(s)
            assertEquals(s, "Saapten Owner 😄 Neenga saaptingala?", t.reply.text)
            assertFalse(s, kai.waitingForLearningAnswer)
        }
        noWrites()
    }

    // B + H + I + J: Kai's name, in the owner's language.
    @Test
    fun nameInTheOwnersLanguage() = runBlocking {
        assertEquals("En peru Kai Owner 😊 Naan unga business assistant.", kai.ask("un name enna?").reply.text)
        assertEquals("En peru Kai Owner 😊 Naan unga business assistant.", kai.ask("un peru enna?").reply.text)
        assertEquals("My name is Kai, Owner 😊 I'm your business assistant.", kai.ask("what is your name?").reply.text)
        assertEquals("என் பேர் Kai ஓனர் 😊 நான் உங்க business assistant.", kai.ask("உன் பேர் என்ன?").reply.text)
        assertFalse(kai.waitingForLearningAnswer)
    }

    // A + H: who Kai is — typed Tanglish and spoken Tamil script both.
    @Test
    fun identityTypedAndSpoken() = runBlocking {
        assertTrue(kai.ask("nee yaaru?").reply.text.startsWith("Naan Kai"))
        assertTrue(kai.ask("Kai nee yaaru?").reply.text.startsWith("Naan Kai"))
        assertTrue(kai.ask("who are you?").reply.text.startsWith("I'm Kai"))
        val ta = kai.ask("நீ யாரு?").reply.text
        assertTrue(ta, ta.startsWith("நான் Kai"))
        assertFalse(kai.waitingForLearningAnswer)
    }

    // C: asked whether Kai is tired — Kai answers about himself (not "take rest, Owner").
    @Test
    fun statusQuestionIsAboutKai() = runBlocking {
        assertEquals("Konjam busy dhaan Owner 😄 Aana ungalukku eppavum ready. Enna help venum?", kai.ask("dei Kai inniku romba tired ah iruka?").reply.text)
        assertEquals("A little busy, Owner 😄 but always ready for you. What do you need?", kai.ask("are you tired?").reply.text)
        // The owner saying THEY are tired keeps its own answer.
        assertTrue(kai.ask("innaiku romba tired").reply.text.contains("rest"))
    }

    // E + F + G + K: greeting / thanks / praise; mixed language answered naturally.
    @Test
    fun greetingThanksPraise() = runBlocking {
        assertTrue(kai.ask("hiii Kai").reply.text.startsWith("Vanakkam Owner"))
        assertEquals("Welcome Owner 🙏 Eppo venumnaalum sollunga.", kai.ask("thanku Kai").reply.text)
        assertEquals("Thanks Owner 😄", kai.ask("super Kai").reply.text)
        val mixed = kai.ask("Kai, your name enna?").reply.text
        assertTrue(mixed, mixed.contains("Kai"))
        noWrites()
    }

    // O + N: a clear payment keeps the existing deterministic engine and its draft — nothing saved.
    @Test
    fun paymentStillDraftsAndWaitsForConfirm() = runBlocking {
        val t = kai.ask("Kumar-ku 5000 kuduthen")
        assertEquals(PlanKind.CREDIT_GIVEN, t.plan!!.kind)
        assertEquals(BigDecimal("5000.00"), t.plan!!.amount)
        noWrites()
    }

    // P: stock stays with the stock engine (draft, converted units).
    @Test
    fun stockStillDrafts() = runBlocking {
        val t = kai.ask("Colgate 2 box add")
        assertTrue(t.reply.text, t.reply.text.contains("Colgate — 2 boxes = 24 pieces stock-in"))
        assertTrue(t.card!!.buttons.any { it.action is KaiAction.ConfirmStock })
        noWrites()
    }

    // Q: a reminder stays a confirm-first reminder.
    @Test
    fun reminderStillAsksToConfirm() = runBlocking {
        val t = kai.ask("Kumar-ku 10 minutes kalichi call pannanum")
        assertTrue(t.reply.text, t.reply.text.contains("reminder set pannalama"))
        assertTrue(t.card!!.buttons.any { it.action is KaiAction.ConfirmReminder })
        noWrites()
    }

    // R: the calculator answers exactly as before.
    @Test
    fun calculatorUnchanged() = runBlocking {
        assertEquals("GST ₹4,500. Total ₹29,500.", kai.ask("25000 la 18% GST evlo?").reply.text)
    }

    // M: small talk in the middle of a payment draft neither cancels nor confirms it; the correction still works.
    @Test
    fun smallTalkDoesNotStealAnOpenDraft() = runBlocking {
        kai.ask("Kumar-ku 5000 kuduthen")
        assertEquals("Saapten Owner 😄 Neenga saaptingala?", kai.ask("saptia?").reply.text)
        val corrected = kai.ask("500 dhaan")
        assertEquals(BigDecimal("500.00"), corrected.plan!!.amount)
        noWrites()
    }

    // M: a receivable follow-up still understands Kumar, ₹20,000 and the due-date question.
    @Test
    fun receivableFollowUpPreserved() = runBlocking {
        kai.ask("Kumar enakku 20000 tharanum")
        val t = kai.ask("eppa?")
        assertTrue(t.reply.text, t.reply.text.contains("₹20,000") && t.reply.text.contains("Kumar") && t.reply.text.contains("due date"))
        assertEquals(KaiPendingQuestion.DUE_DATE, kai.conversationState.pendingQuestion)
        noWrites()
    }

    // M: "athula 5 pochu" after Colgate stock is Colgate stock-out — a draft, saved only on Confirm.
    @Test
    fun stockFollowUpRefersToTheLastProduct() = runBlocking {
        val first = kai.ask("Colgate stock 20 pieces vandhiruku")
        kai.act(first.card!!.buttons.first { it.action is KaiAction.ConfirmStock }.action, KaiLang.TANGLISH)
        assertEquals(Triple("p1", BigDecimal("20"), true), tools.stockChanges.single())
        val t = kai.ask("athula 5 pochu")
        assertTrue(t.reply.text, t.reply.text.contains("Colgate") && t.reply.text.contains("5"))
        assertTrue(t.card!!.buttons.any { it.action is KaiAction.ConfirmStock })
        assertEquals("only the first, confirmed change is saved", 1, tools.stockChanges.size)
    }

    // A named product always wins over the reference word.
    @Test
    fun namedProductBeatsReference() = runBlocking {
        kai.ask("Colgate stock 20 pieces vandhiruku")
        val t = kai.ask("adhu illa, Rice 5 kg pochu")
        assertTrue(t.reply.text, t.reply.text.contains("Rice"))
        assertFalse(t.reply.text, t.reply.text.contains("Colgate"))
    }

    // S: personal learning still comes first and still applies after confirmation.
    @Test
    fun personalLearningStillWorks() = runBlocking {
        val teach = kai.ask("Kai, enga kadaiyila 'potti' na 1 box.")
        assertEquals("Sari Owner 👍 Indha business-ku `potti` = 1 box-nu purinjukitten. Save pannava?", teach.reply.text)
        kai.act(teach.card!!.buttons.first { it.label == "Save" }.action, KaiLang.TANGLISH)
        val draft = kai.ask("Colgate 2 potti vandhudhu")
        assertTrue(draft.reply.text, draft.reply.text.contains("Colgate — 2 boxes = 24 pieces stock-in"))
        noWrites()
    }

    // T: ambiguous money is never guessed — no draft, no write.
    @Test
    fun ambiguousMoneyIsNotGuessed() = runBlocking {
        val t = kai.ask("Kumar 5000")
        assertNull(t.plan)
        noWrites()
        val hand = kai.ask("kai vali")
        assertTrue(hand.reply.text.isNotBlank())
        noWrites()
    }

    // U: unknown input always gets an answer, never a blank, never a write.
    @Test
    fun unknownInputNeverSilent() = runBlocking {
        for (s in listOf("asdf qwerty", "sollu", "enna?", "hmm", "blah blah blah", "?", "Kai Kai Kai", "dei")) {
            val t = kai.ask(s)
            assertTrue("'$s' got a blank reply", t.reply.text.isNotBlank())
        }
        noWrites()
    }

    // Safety: the casual spelling key is only for recognising small talk — names keep their spelling.
    @Test
    fun namesAreNeverRespelled() = runBlocking {
        val t = kai.ask("Saaravanan-ku 5000 kuduthen")
        assertTrue(t.reply.text, t.reply.text.contains("Saaravanan"))
        assertEquals("saptiya", KaiSmallTalk.casualKey("saaptiyaa"))
        assertEquals("saptiya", KaiSmallTalk.casualKey("saptia"))
        assertEquals("saptiya", KaiSmallTalk.casualKey("saptya"))
        assertNotEquals("Saaravanan", KaiSmallTalk.casualKey("Saaravanan"))
        noWrites()
    }

    // Text and voice parity: Tamil-script speech-to-text and typed Tanglish mean the same thing.
    @Test
    fun spokenTamilAndTypedTanglishMeanTheSame() {
        for ((spoken, typed) in listOf("சாப்டியா?" to "saptiya?", "உன் பேர் என்ன?" to "un peru enna?", "நீ யாரு?" to "nee yaaru?")) {
            assertNotNull(spoken, KaiSmallTalk.kindOf(spoken))
            assertEquals(spoken, KaiSmallTalk.kindOf(typed), KaiSmallTalk.kindOf(spoken))
        }
    }
}
