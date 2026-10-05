package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KaiTeaching
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.KaiIntentKind
import com.shopai.app.brain.tools.KaiIntents
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiReminderUnderstanding
import com.shopai.app.brain.tools.KaiSpokenWords
import com.shopai.app.brain.tools.KaiTime
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.NewProduct
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderRequest
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Regression tests for the production bugs: stock in said without a number,
 * a new product, stock out, the bill scanner, relative reminders (typed and
 * spoken Tamil script), small talk, inline word learning per owner, and the
 * intent priority shared by voice and text.
 */
class KaiProductionFixesTest {
    private val now = LocalDateTime.of(2026, 10, 3, 10, 0)
    private val zone = ZoneId.of("Asia/Kolkata")
    private val nowMillis = now.atZone(zone).toInstant().toEpochMilli()

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Kumar Traders", "9000000001", 8500.0, null)),
            suppliers = listOf(PartySummary("s1", "Selvam", null, 8000.0, null)),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools : KaiTools {
        val products = mutableListOf(
            ProductRef("p1", "Colgate", "PCS", BigDecimal("8"), mapOf("BOX" to BigDecimal(12))),
            ProductRef("p2", "Rice", "KG", BigDecimal("50")),
        )
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val created = mutableListOf<NewProduct>()
        val scheduled = mutableListOf<KaiReminder>()
        val logs = mutableListOf<Triple<String, ActionStatus, String?>>()

        override suspend fun products() = products.toList()
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming)
            val after = if (incoming) product.stock + qty else product.stock - qty
            products.replaceAll { if (it.id == product.id) it.copy(stock = after) else it }
            return ActionOutcome.Done(product.name, after)
        }
        override suspend fun createProduct(product: NewProduct): ProductRef {
            created += product
            return ProductRef("new-${created.size}", product.name, product.unit, BigDecimal.ZERO).also { products += it }
        }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { scheduled += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders(): List<KaiReminder> = scheduled.filter { it.open }
        override fun zone(): String = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) =
            if (name.equals("Ruthran", true)) listOf(ContactMatch("phone:9", "Ruthran", "+919000000009", com.shopai.app.brain.tools.ContactSource.PHONE)) else emptyList()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String {
            logs += Triple(intent, status, input)
            return reference ?: "K-1"
        }
    }

    /** The signed-in owner (switchable, like another login on the same phone). */
    private class Access(val store: InMemoryKaiMemoryStore, var owner: String, val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory { memory.open("biz-1", owner); return memory }
        override suspend fun entities(): List<KnownEntity> = tools.products.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) }
    }

    private val store = InMemoryKaiMemoryStore()
    private val tools = Tools()
    private val access = Access(store, "owner-A", tools)
    private val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, access)

    private fun KaiTurn.actions() = card?.buttons.orEmpty().map { it.action }

    // ------------------------------------------------------------ stock in

    @Test
    fun stockArrivedWithoutNumberIsStockInNotNotFound() = runBlocking {
        val t = kai.ask("Colgate stock vandhiruku add pannu")
        assertFalse(t.reply.text, t.reply.text.contains("isn't in your inventory") || t.reply.text.contains("inventory-la illa"))
        assertTrue(t.reply.text, t.reply.text.contains("Colgate stock add pannalam.\nEvlo pieces vandhirukku?"))
        // The quantity completes it — still a draft.
        val d = kai.ask("12")
        assertEquals("Colgate — 12 pieces stock-in draft ready. Confirm pannunga.", d.reply.text)
        assertTrue(tools.stockChanges.isEmpty())
        val done = kai.act(d.actions().filterIsInstance<KaiAction.ConfirmStock>().single(), KaiLang.TANGLISH)!!
        assertEquals("Done Owner ✅ Colgate stock-la 12 pieces add panniten. Ippo stock 20 pieces.", done.reply.text)
        assertEquals(Triple("p1", BigDecimal("12"), true), tools.stockChanges.single())
        assertTrue(tools.logs.any { it.first == KaiIntents.STOCK_IN && it.second == ActionStatus.CONFIRMED })
    }

    @Test
    fun everyStockInPhraseIsStockIn() = runBlocking {
        for (s in listOf("Colgate add stock 5", "Colgate new stock 5", "Colgate 5 stock vandhiruku", "pudhu stock Colgate 5", "Colgate 5 pudhusa vandhuruku",
            "Colgate 5 inventory-ku podu", "Colgate 5 pieces stock add pannu")) {
            val a = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, access)
            val t = a.ask(s)
            assertTrue("$s → ${t.reply.text}", t.reply.text.startsWith("Colgate — 5 pieces stock-in draft ready"))
            assertTrue(t.actions().any { it is KaiAction.ConfirmStock } && t.actions().any { it is KaiAction.EditStock } && t.actions().any { it is KaiAction.CancelStock })
        }
        assertTrue(tools.stockChanges.isEmpty())
    }

    @Test
    fun newProductOpensTheCameraDirectly() = runBlocking {
        val t = kai.ask("Pepsodent stock vandhiruku add pannu")
        assertTrue(t.reply.text, t.reply.text.startsWith("Owner, Pepsodent new product madhiri theriyudhu.\nPhoto eduthu details auto-fill pannava? 📷"))
        val camera = t.direct as KaiAction.OpenStockCamera
        assertEquals("Pepsodent", camera.prefill.name)
        assertTrue(t.actions().any { it is KaiAction.CreateProduct })
        assertTrue(tools.logs.any { it.first == KaiIntents.STOCK_IN_CAMERA })
        assertTrue(tools.created.isEmpty() && tools.stockChanges.isEmpty())
        // The owner checked the photo-filled form: product created, stock in through the inventory engine.
        val done = kai.addProduct(ProductForm("Pepsodent", "Personal Care", "Germicheck", "Pepsodent", "PCS", "150 g", BigDecimal("12")), KaiLang.TANGLISH)
        assertTrue(done.reply.text, done.reply.text.startsWith("Done Owner ✅ Pepsodent stock-la 12 pieces add panniten."))
        assertEquals("Personal Care", tools.created.single().category)
        assertEquals(Triple("new-1", BigDecimal("12"), true), tools.stockChanges.single())
    }

    @Test
    fun editedDraftKeepsConfirmation() = runBlocking {
        val d = kai.ask("Colgate 10 pieces stock add pannu")
        val key = d.actions().filterIsInstance<KaiAction.EditStock>().single().key
        assertEquals(Triple("Colgate", BigDecimal("10"), "PCS"), kai.stockDraftOf(key))
        val edited = kai.reviseStock(key, BigDecimal("6"), "BOX", KaiLang.TANGLISH)!!
        assertTrue(edited.reply.text, edited.reply.text.startsWith("Colgate — 6 boxes = 72 pieces stock-in"))
        assertTrue(tools.stockChanges.isEmpty())
    }

    // ------------------------------------------------------------ stock out

    @Test
    fun stockOutPhrasesShowWhatIsLeft() = runBlocking {
        for (s in listOf("Colgate 2 out pannu", "2 Colgate pochu", "Colgate rendu sale aachu", "Colgate 2 pieces sold", "Colgate 2 eduthutanga")) {
            val a = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, access)
            val t = a.ask(s)
            assertEquals(s, "Seri Owner. Colgate — 2 pieces stock-out. Remaining: 6 pieces.", t.reply.text)
            assertEquals(s, listOf("Colgate", "Stock Out: 2 pieces", "Remaining: 6 pieces"), t.card!!.lines)
        }
        assertTrue(tools.stockChanges.isEmpty())
    }

    @Test
    fun stockOutOfAMissingProductOffersToCreateIt() = runBlocking {
        val t = kai.ask("2 Pepsodent pochu")
        assertEquals("Owner, Pepsodent inventory-la illa. Product create pannanuma?", t.reply.text)
        assertTrue(t.actions().any { it is KaiAction.CreateProduct })
        assertTrue(t.actions().any { it is KaiAction.CancelStock })
        assertNull(t.direct)
    }

    @Test
    fun moneyIsNeverStock() = runBlocking {
        assertNull(com.shopai.app.brain.tools.KaiStock.understand("cash 500 pochu", tools.products))
        // A person in the books is never a new product: Kai asks, nothing opens, nothing is saved.
        val t = kai.ask("Kumar account-la 500 vandhuchu")
        assertNull(t.direct)
        assertFalse(t.actions().any { it is KaiAction.ConfirmStock || it is KaiAction.OpenStockCamera })
        assertNull(com.shopai.app.brain.tools.KaiStock.understand("innaiku sale evlo aachu?", tools.products))
    }

    // ------------------------------------------------------------ bill scanner

    @Test
    fun billCommandsOpenTheCameraDirectly() = runBlocking {
        for (s in listOf("bill scan pannu", "bill ah scan pannu", "indha bill add pannu", "purchase bill scan pannu", "bill photo edu", "bill camera open pannu", "பில் ஸ்கேன் பண்ணு")) {
            val t = kai.ask(s)
            assertEquals(s, KaiAction.OpenScanner, t.direct)
            assertTrue(s, t.reply.text.contains("📷"))
            assertEquals(s, KaiIntentKind.SCAN_BILL, KaiIntents.classify(s, now, emptyList(), tools.products))
        }
        assertEquals("Sure Owner, bill scan pannalam 📷", kai.ask("bill scan pannu").reply.text)
        assertTrue(tools.logs.any { it.first == KaiIntents.OPEN_BILL_SCANNER && it.second == ActionStatus.OPENED })
        // A question about a bill is not the scanner.
        assertFalse(KaiIntents.isBillScan("Kumar bill evlo?"))
    }

    // ------------------------------------------------------------ reminders

    @Test
    fun relativeReminderNeverAsksForATime() = runBlocking {
        val t = kai.askConfirmed("2 minutes la Ruthran-ku call pannanum reminder pannu")
        assertEquals("Done Owner ✅ 2 minutes kalichi Ruthran-ku call panna remind pannuren.", t.reply.text)
        val r = tools.scheduled.single()
        assertEquals(nowMillis + 120_000, r.triggerAt)
        assertEquals(ReminderAction.CALL, r.action)
        assertEquals("Ruthran", r.person)
        assertEquals("+919000000009", r.phone)
        assertEquals("Owner, Ruthran-ku call panna sonneenga.", r.notificationMessage)
        assertFalse(t.reply.text.contains("called", true) || t.reply.text.contains("call panniten", true))
        assertTrue(tools.logs.any { it.first == KaiIntents.CREATE_REMINDER && it.third != null })
    }

    @Test
    fun spokenTamilScriptReminderIsTheSameAsTyped() = runBlocking {
        // What the phone's Tamil speech-to-text writes.
        for (spoken in listOf(
            "2 நிமிஷத்துல ருத்ரனுக்கு கால் பண்ணனும் ரிமைண்டர் பண்ணு",
            "டூ மினிட்ஸ்ல ருத்ரனுக்கு கால் பண்ணனும் ரிமைண்டர் பண்ணு",
            "ரெண்டு நிமிஷம் கழிச்சு ருத்ரனுக்கு கால் பண்ண ஞாபகப்படுத்து",
        )) {
            val w = KaiTime.parse(spoken, now)
            assertEquals(spoken, Duration.ofMinutes(2), w?.relative)
            val req = KaiReminderUnderstanding.understand(spoken, now, emptyList()) as ReminderRequest.Create
            assertEquals(spoken, ReminderAction.CALL, req.draft.action)
            assertEquals(spoken, "ருத்ரன்", req.draft.person)
        }
        val t = kai.askConfirmed("2 நிமிஷத்துல ருத்ரனுக்கு கால் பண்ணனும் ரிமைண்டர் பண்ணு")
        assertEquals("சரி ஓனர் ✅ 2 நிமிடம் கழிச்சு ருத்ரன்-க்கு call பண்ண நினைவூட்டுறேன்.", t.reply.text)
        assertEquals(nowMillis + 120_000, tools.scheduled.single().triggerAt)
    }

    @Test
    fun everyTimePhrase() {
        fun rel(s: String) = KaiTime.parse(s, now)?.relative
        assertEquals(Duration.ofSeconds(30), rel("30 seconds la remind pannu"))
        assertEquals(Duration.ofMinutes(2), rel("2 nimishathula"))
        assertEquals(Duration.ofMinutes(2), rel("2 nimisham kalichi"))
        assertEquals(Duration.ofMinutes(10), rel("10 nimisham apram"))
        assertEquals(Duration.ofHours(2), rel("2 mani nerathula"))
        assertEquals(Duration.ofMinutes(10), rel("in 10 minutes"))
        assertEquals(Duration.ofMinutes(10), rel("10 mins later"))
        assertEquals(Duration.ofDays(2), rel("2 days la"))
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), KaiTime.parse("naalaikku kaalaila 10 manikku", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 3, 18, 0), KaiTime.parse("maalai 6 manikku", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 3, 18, 0), KaiTime.parse("evening 6", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), KaiTime.parse("tomorrow 10 AM", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), KaiTime.parse("நாளைக்கு காலையில 10 மணிக்கு", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 3, 21, 0), KaiTime.parse("tonight 9", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 3, 16, 30), KaiTime.parse("4:30 pm", now)!!.at)
    }

    @Test
    fun timeWordsAreNeverPeople() {
        assertNull(KaiReminderUnderstanding.draft("maalai 6 manikku rent remind pannu", null, emptyList()).person)
        assertEquals("Ravi", KaiReminderUnderstanding.draft("naalaikku kaalaila 10 manikku Ravi call remind pannu", null, emptyList()).person)
        assertEquals("Ruthran", KaiReminderUnderstanding.draft("in 2 minutes remind me to call Ruthran", null, emptyList()).person)
        assertEquals("Ruthran", KaiReminderUnderstanding.draft("2 mani nerathula Ruthran call reminder pannu", null, emptyList()).person)
    }

    @Test
    fun reminderFollowUpTakesARelativeTime() = runBlocking {
        val q = kai.ask("Kumar-ku reminder pannu")
        assertTrue(q.reply.text, q.reply.text.startsWith("Eppo remind pannanum Owner?"))
        assertTrue(tools.scheduled.isEmpty())
        val done = kai.askConfirmed("10 minutes la")
        assertTrue(done.reply.text, done.reply.text.startsWith("Done Owner ✅ 10 minutes kalichi"))
        assertEquals(nowMillis + 600_000, tools.scheduled.single().triggerAt)
    }

    // ------------------------------------------------------------ conversation

    @Test
    fun smallTalkGetsAFriendlyAnswer() = runBlocking {
        assertEquals("Saapten Owner 😄 Neenga saaptingala?", kai.ask("saaptiya?").reply.text)
        assertTrue(kai.ask("enna panra?").reply.text.contains("kadai kanakku"))
        assertTrue(kai.ask("innaiku romba busy").reply.text.contains("busy-ah irukkeenga"))
        assertTrue(kai.ask("good morning").reply.text.startsWith("Good morning Owner"))
        assertEquals("சாப்டேன் ஓனர் 😄 நீங்க சாப்டீங்களா?", kai.ask("சாப்டியா?").reply.text)
        for (s in listOf("saaptiya?", "enna panra?", "innaiku romba busy", "good morning")) {
            assertFalse(s, kai.ask(s).reply.text.contains("clear-ah sollunga"))
            assertEquals(s, KaiIntentKind.CHAT, KaiIntents.classify(s, now, emptyList(), tools.products))
        }
        // Business words are never small talk.
        assertNull(KaiSmallTalk.kindOf("innaiku sales evlo?"))
        assertNull(KaiSmallTalk.kindOf("ok Kumar-ku 500 kuduthen"))
        assertTrue(tools.scheduled.isEmpty() && tools.stockChanges.isEmpty())
    }

    // ------------------------------------------------------------ inline learning (per owner)

    @Test
    fun wordIsLearnedInlineOnlyAfterYes() = runBlocking {
        val q = kai.ask("'ramba' nu enna meaning?")
        assertTrue(q.reply.text, q.reply.text.contains("`ramba`-na enna?"))
        val confirm = kai.ask("romba nu sonna mari")
        assertEquals("Seri Owner 😄 `ramba` = `romba` nu save pannava?", confirm.reply.text)
        assertNull("nothing saved before yes", access.memory.find("ramba"))
        val yes = confirm.actions().filterIsInstance<KaiAction.LearnWord>().single()
        val saved = kai.act(yes, KaiLang.TANGLISH)!!
        assertEquals("Done Owner 👍 Inime neenga `ramba` sonna `romba` nu purinjukuren.", saved.reply.text)
        val m = access.memory.find("ramba")!!
        assertEquals(MemoryType.WORD, m.memoryType)
        assertEquals("romba", m.meaningValue)
        assertEquals("owner-A", m.ownerId)
        // Asked again: Kai knows it now.
        assertTrue(kai.ask("ramba na enna meaning").reply.text.contains("`romba`"))
        // Another owner on the same phone never sees it.
        access.owner = "owner-B"
        access.current()
        assertNull(access.memory.find("ramba"))
        assertTrue(store.load("biz-1", "owner-B").memories.isEmpty())
        assertEquals(1, store.load("biz-1", "owner-A").memories.size)
    }

    @Test
    fun aMoneyMeaningIsNeverAPlainWord() = runBlocking {
        kai.ask("thooki nu enna meaning?")
        val t = kai.ask("kuduthen maadhiri")
        // Asked as an action meaning with its own confirmation — not saved as a word.
        assertTrue(t.reply.text, t.reply.text.contains("Payment Out"))
        assertTrue(t.actions().any { it is KaiAction.LearnMeaning })
        assertNull(access.memory.find("thooki"))
    }

    @Test
    fun unknownSlangInACommandIsAskedYesNo() = runBlocking {
        val t = kai.ask("Colgate 5 box podu")
        assertEquals("Owner, `podu` na stock add pannradha?", t.reply.text)
        assertEquals(listOf("Yes, add stock", "No"), t.card!!.buttons.map { it.label })
        assertTrue(tools.stockChanges.isEmpty())
        val learned = kai.act(t.actions().filterIsInstance<KaiAction.LearnMeaning>().single(), KaiLang.TANGLISH)!!
        assertTrue(learned.reply.text, learned.reply.text.contains("Colgate — 5 boxes = 60 pieces stock-in"))
        assertTrue(tools.stockChanges.isEmpty())
    }

    @Test
    fun teachingParsers() {
        assertEquals("ramba", KaiTeaching.wordQuestion("'ramba' nu enna meaning?"))
        assertEquals("ramba", KaiTeaching.wordQuestion("ramba na enna artham"))
        assertEquals("ramba", KaiTeaching.wordQuestion("what does ramba mean?"))
        assertNull(KaiTeaching.wordQuestion("inniku sales enna?"))
        assertEquals("romba", KaiTeaching.wordAnswer("romba nu sonna mari"))
        assertEquals("romba", KaiTeaching.wordAnswer("romba maadhiri"))
        assertEquals("romba", KaiTeaching.wordAnswer("it means romba"))
        assertNull(KaiTeaching.wordAnswer("illa"))
    }

    // ------------------------------------------------------------ one brain, any language

    @Test
    fun sameIntentInEveryLanguage() {
        val p = tools.products
        for (s in listOf("Colgate 5 stock add pannu", "add 5 Colgate to stock", "Colgate 5 ஸ்டாக் வந்திருக்கு", "Colgate pudhu stock 5")) {
            assertEquals(s, KaiIntentKind.STOCK_IN, KaiIntents.classify(s, now, emptyList(), p))
        }
        for (s in listOf("2 minutes la Kumar-ku call remind pannu", "remind me to call Kumar in 2 minutes", "2 நிமிஷத்துல குமாருக்கு கால் பண்ண ஞாபகப்படுத்து")) {
            assertEquals(s, KaiIntentKind.CREATE_REMINDER, KaiIntents.classify(s, now, emptyList(), p))
        }
        // Priority: a reminder about stock is a reminder; a bill reminder is not the scanner.
        assertEquals(KaiIntentKind.CREATE_REMINDER, KaiIntents.classify("10 nimisham apram Colgate stock check panna remind pannu", now, emptyList(), p))
        assertEquals(KaiIntentKind.CREATE_REMINDER, KaiIntents.classify("naalaikku bill kattanum remind pannu", now, emptyList(), p))
        assertEquals(KaiIntentKind.CALCULATE, KaiIntents.classify("25 * 4", now, emptyList(), p))
        assertEquals("2 minutes la ருத்ரன்-ku call pannanum reminder pannu", KaiSpokenWords.normalize("2 நிமிஷத்துல ருத்ரனுக்கு கால் பண்ணனும் ரிமைண்டர் பண்ணு"))
        assertEquals("Colgate 2 out pannu", KaiSpokenWords.normalize("Colgate 2 out pannu"))
    }

    @Test
    fun voiceAndTextGiveTheSameAnswer() = runBlocking {
        val typed = KaiAgent(KaiBusinessBrain(Books()), Books(), Tools(), { now }, Access(InMemoryKaiMemoryStore(), "o", Tools())).ask("bill scan pannu")
        // The phone's Tamil speech-to-text writes the same request in Tamil script: same action, answered in Tamil.
        val spoken = KaiAgent(KaiBusinessBrain(Books()), Books(), Tools(), { now }, Access(InMemoryKaiMemoryStore(), "o", Tools())).ask("பில் ஸ்கேன் பண்ணு")
        assertEquals(typed.direct, spoken.direct)
        assertEquals("சரி ஓனர், பில் ஸ்கேன் பண்ணலாம் 📷", spoken.reply.text)
        assertNotNull(typed.direct)
    }
}
