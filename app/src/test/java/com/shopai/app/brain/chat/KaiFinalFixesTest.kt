package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiMeaning
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.brain.morning.MorningCommands
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.ContactSource
import com.shopai.app.brain.tools.KaiIntentKind
import com.shopai.app.brain.tools.KaiIntents
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiSpokenWords
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.data.model.PartySummary
import com.shopai.app.util.BillTextParser
import com.shopai.app.util.CameraPermissionFlow
import com.shopai.app.util.CameraStep
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
 * One Kai for every input: Tamil script, Tanglish, English and mixed sentences,
 * typed or spoken, reach the same intent and the same action. Covers the final
 * production fixes (reminders, stock, cameras, chat, learning, morning work,
 * business questions, voice / text delivery).
 */
class KaiFinalFixesTest {
    private val now = LocalDateTime.of(2026, 10, 3, 10, 0)
    private val nowMillis = now.atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Kumar", "9000000001", 10000.0, "2026-10-12T00:00:00Z")),
            suppliers = listOf(PartySummary("s1", "Selvam", null, 8000.0, null)),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools : KaiTools {
        val products = mutableListOf(ProductRef("p1", "Colgate", "PCS", BigDecimal("20")), ProductRef("p2", "Rice", "KG", BigDecimal("50")))
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val scheduled = mutableListOf<KaiReminder>()
        val prepared = mutableListOf<ActionPlan>()
        val logs = mutableListOf<String>()
        override suspend fun products() = products.toList()
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming)
            return ActionOutcome.Done(product.name, if (incoming) product.stock + qty else product.stock - qty)
        }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { scheduled += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun updateReminder(reminder: KaiReminder): ReminderSaved {
            scheduled.replaceAll { if (it.id == reminder.id) reminder else it }
            return ReminderSaved(reminder, false, ScheduleResult.EXACT)
        }
        override fun reminders(): List<KaiReminder> = scheduled.filter { it.open }
        override fun zone(): String = "Asia/Kolkata"
        // A phone contact saved in English; the Tamil-script name finds it.
        override suspend fun contacts(name: String, role: PartyRole?) =
            listOf(ContactMatch("phone:9", "Ruthran", "+919000000009", ContactSource.PHONE))
                .filter { it.name.equals(name, true) || com.shopai.app.util.NameSound.same(it.name, name) }
        override suspend fun parties(name: String) = listOf(
            PartyMatch("c1", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("10000")),
            PartyMatch("s1", "Selvam", customer = false, phone = null, balance = BigDecimal("8000")),
        ).filter { it.name.contains(name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: com.shopai.app.books.model.PaymentMode, said: String) =
            ActionPlan("plan-${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String {
            logs += intent
            return reference ?: "K-1"
        }
    }

    private class Access(val store: InMemoryKaiMemoryStore, var owner: String, val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory { memory.open("biz-1", owner); return memory }
        override suspend fun entities(): List<KnownEntity> = tools.products.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) }
    }

    private val store = InMemoryKaiMemoryStore()
    private val tools = Tools()
    private val access = Access(store, "owner-A", tools)
    private fun newKai(t: Tools = tools, a: Access = access) = KaiAgent(KaiBusinessBrain(Books()), Books(), t, { now }, a)
    private val kai = newKai()
    private fun KaiTurn.actions() = card?.buttons.orEmpty().map { it.action }

    // ------------------------------------------------------------ reminders: every language, one intent

    @Test
    fun theSameReminderInEveryLanguage() = runBlocking {
        val sentences = listOf(
            "2 நிமிஷத்துல ருத்ரனுக்கு call பண்ண remind பண்ணு", // Tamil script (what speech-to-text writes), mixed
            "2 nimishathula Ruthran-ku call panna remind pannu", // Tanglish
            "remind me to call Ruthran in 2 minutes", // English
            "Ruthran ku 2 mins la call reminder", // mixed
        )
        for (s in sentences) {
            val t = Tools()
            val turn = newKai(t, Access(InMemoryKaiMemoryStore(), "o", t)).askConfirmed(s)
            assertEquals(s, KaiIntentKind.CREATE_REMINDER, KaiIntents.classify(s, now, emptyList(), t.products))
            val r = t.scheduled.singleOrNull()
            assertNotNull("$s → ${turn.reply.text}", r)
            assertEquals(s, nowMillis + 120_000, r!!.triggerAt)
            assertEquals(s, ReminderAction.CALL, r.action)
            assertEquals(s, "Ruthran", r.person)
            assertFalse(s, turn.reply.text.contains("Eppo"))
        }
    }

    @Test
    fun tamilNormalizationIsInternalOnly() {
        assertEquals("2 minutes la", KaiSpokenWords.normalize("2 நிமிஷத்துல"))
        assertEquals("2 minutes la", KaiSpokenWords.normalize("2 நிமிடத்தில்"))
        assertEquals("kalichi apram piragu", KaiSpokenWords.normalize("கழிச்சு அப்புறம் பிறகு"))
        assertEquals("naalaikku kaalaila maalaila", KaiSpokenWords.normalize("நாளைக்கு காலையில் மாலையில்"))
        assertEquals("saavi pottu pannu pannunga nyabagam paduthu remind", KaiSpokenWords.normalize("சாவி போட்டு பண்ணு பண்ணுங்க நினைவுபடுத்து நினைவூட்டு"))
        assertEquals("2 hours la", KaiSpokenWords.normalize("2 மணி நேரத்துல"))
        // A Tamil-script answer stays Tamil — normalization is not translation.
        val t = runBlocking { newKai().askConfirmed("2 நிமிஷத்துல ருத்ரனுக்கு கால் பண்ணனும் ரிமைண்டர் பண்ணு") }
        assertTrue(t.reply.text, t.reply.text.startsWith("சரி ஓனர் ✅"))
    }

    @Test
    fun onlyTheMissingFieldIsAsked() = runBlocking {
        // Time said, task missing → "Enna remind pannanum?" (the time is kept).
        val q = kai.ask("2 minutes la remind pannu")
        assertEquals("Seri Owner. Enna nyabagam paduthanum?", q.reply.text)
        assertTrue(tools.scheduled.isEmpty())
        val done = kai.askConfirmed("Ruthran-ku call panna")
        assertEquals("Done Owner ✅ 2 minutes kalichi Ruthran-ku call panna remind pannuren.", done.reply.text)
        assertEquals(nowMillis + 120_000, tools.scheduled.single().triggerAt)
        // Task said, time missing → only the time is asked.
        assertTrue(kai.ask("Ruthran-ku call remind pannu").reply.text.startsWith("Seri Owner. Eppa remind pannanum?"))
    }

    @Test
    fun reminderCardAndEdit() = runBlocking {
        val t = kai.askConfirmed("2 nimishathula Ruthran-ku call panna remind pannu")
        assertEquals("Done Owner ✅ 2 minutes kalichi Ruthran-ku call panna remind pannuren.", t.reply.text)
        assertEquals(listOf("Reminder", "Call Ruthran", "When: 2 minutes kalichi", "Status: Scheduled"), t.card!!.lines)
        assertEquals(listOf("Cancel", "Edit"), t.card!!.buttons.map { it.label })
        val edit = kai.act(t.actions().filterIsInstance<KaiAction.EditReminder>().single(), KaiLang.TANGLISH)!!
        assertTrue(edit.reply.text, edit.reply.text.contains("eppo-ku maathanum"))
        kai.ask("10 minutes la")
        assertEquals(nowMillis + 600_000, tools.scheduled.single().triggerAt)
        // The notification reminds; Kai never claims a call.
        assertEquals("Owner, Ruthran-ku call panna sonneenga.", tools.scheduled.single().notificationMessage)
        assertFalse(t.reply.text.contains("called", true))
    }

    @Test
    fun contextContinues() = runBlocking {
        kai.ask("Kumar-ku reminder pannu")
        val r = kai.askConfirmed("10 minutes la")
        assertTrue(r.reply.text, r.reply.text.startsWith("Done Owner ✅ 10 minutes kalichi"))
        assertEquals("Kumar", tools.scheduled.single().person)
        kai.ask("Colgate stock add pannu")
        assertEquals("Colgate — 12 pieces stock-in draft ready. Confirm pannunga.", kai.ask("12").reply.text)
    }

    // ------------------------------------------------------------ stock

    @Test
    fun stockInPhrases() = runBlocking {
        for (s in listOf("Colgate stock vandhiruku add pannu", "Colgate new stock add pannu", "Colgate pudhu stock podu", "Colgate inventory-ku podu",
            "Colgate vandhuduchu", "New Colgate stock", "Colgate stock in")) {
            val t = newKai().ask(s)
            assertTrue("$s → ${t.reply.text}", t.reply.text.contains("Colgate stock add pannalam") || t.reply.text.contains("Let's add Colgate stock"))
            assertEquals(s, KaiIntentKind.STOCK_IN, KaiIntents.classify(s, now, emptyList(), tools.products))
        }
        val first = newKai().ask("Colgate stock vandhiruku add pannu")
        assertEquals("Seri Owner 👍 Colgate stock add pannalam.\nEvlo pieces vandhirukku?", first.reply.text)
        val withQty = newKai().ask("Colgate 12 pieces add pannu")
        assertEquals("Colgate — 12 pieces stock-in draft ready. Confirm pannunga.", withQty.reply.text)
        assertEquals(listOf("Confirm", "Edit", "Cancel"), withQty.card!!.buttons.map { it.label })
        assertTrue(tools.stockChanges.isEmpty())
    }

    @Test
    fun newProductNeverSaysNotInInventory() = runBlocking {
        val t = kai.ask("Pepsodent stock vandhiruku add pannu")
        assertFalse(t.reply.text.contains("isn't in your inventory"))
        assertEquals("Owner, Pepsodent new product madhiri theriyudhu.\nPhoto eduthu details auto-fill pannava? 📷", t.reply.text)
        assertTrue(t.direct is KaiAction.OpenStockCamera)
    }

    @Test
    fun cameraCommandsOpenTheCameraDirectly() = runBlocking {
        for (s in listOf("Colgate photo edu", "stock photo edu", "new stock add pannu")) {
            val t = newKai().ask(s)
            assertTrue("$s → ${t.reply.text}", t.direct is KaiAction.OpenStockCamera)
            assertEquals(s, KaiIntentKind.SCAN_STOCK, KaiIntents.classify(s, now, emptyList(), tools.products))
        }
        assertEquals("Colgate", (newKai().ask("Colgate photo edu").direct as KaiAction.OpenStockCamera).prefill.name)
        for (s in listOf("bill scan pannu", "bill ah scan pannu", "invoice scan", "bill photo edu", "purchase bill add pannu", "indha bill add pannu", "bill camera open pannu")) {
            assertEquals(s, KaiAction.OpenScanner, newKai().ask(s).direct)
            assertEquals(s, KaiIntentKind.SCAN_BILL, KaiIntents.classify(s, now, emptyList(), tools.products))
        }
        assertTrue(tools.stockChanges.isEmpty())
    }

    @Test
    fun stockOutPhrases() = runBlocking {
        for (s in listOf("2 Colgate pochu", "2 Colgate sale aachu", "rendu Colgate pochu", "Colgate rendu sell panniten", "Colgate 2 pieces out", "2 pieces Colgate kuduthuten")) {
            val t = newKai().ask(s)
            assertEquals(s, "Seri Owner. Colgate — 2 pieces stock-out. Remaining: 18 pieces.", t.reply.text)
            assertEquals(s, listOf("Colgate", "Stock Out: 2 pieces", "Remaining: 18 pieces"), t.card!!.lines)
            assertEquals(s, KaiIntentKind.STOCK_OUT, KaiIntents.classify(s, now, emptyList(), tools.products))
        }
        assertTrue(newKai().ask("Colgate out pannu").reply.text.contains("Colgate evlo pochu"))
        assertTrue(tools.stockChanges.isEmpty())
        // Money given to a person stays a payment, never stock.
        val pay = newKai().ask("Selvam-ku 2000 kuduthen")
        assertTrue(tools.stockChanges.isEmpty())
        assertEquals(PlanKind.PAYMENT_OUT, tools.prepared.single().kind)
        assertTrue(pay.actions().any { it is KaiAction.ConfirmPlan })
    }

    // ------------------------------------------------------------ bill draft

    @Test
    fun billDraftReadsInvoiceNumberAndTax() {
        val bill = BillTextParser.parse(
            """
            SRI MURUGAN TRADERS
            Tax Invoice No: INV-2041
            Date: 01/10/2026
            Colgate 150g x 12   1080.00
            Taxable Value 1080.00
            CGST 9% 97.20
            SGST 9% 97.20
            Grand Total 1274.40
            """.trimIndent(),
        )
        assertEquals("INV-2041", bill.invoiceNumber)
        assertEquals(BigDecimal("194.40"), bill.tax)
        assertEquals(BigDecimal("1274.40"), bill.total)
        assertNull(BillTextParser.parse("Ravi 500").invoiceNumber)
    }

    @Test
    fun cameraPermissionFlow() {
        assertEquals(CameraStep.OPEN_CAMERA, CameraPermissionFlow.onTap(granted = true))
        assertEquals(CameraStep.ASK_PERMISSION, CameraPermissionFlow.onTap(granted = false))
        assertEquals(CameraStep.SHOW_PROMPT, CameraPermissionFlow.onResult(granted = false, deniedBefore = false))
        assertEquals(CameraStep.OPEN_SETTINGS, CameraPermissionFlow.onResult(granted = false, deniedBefore = true))
        assertEquals(CameraStep.OPEN_CAMERA, CameraPermissionFlow.onResult(granted = true, deniedBefore = true))
    }

    // ------------------------------------------------------------ casual chat

    @Test
    fun casualChat() = runBlocking {
        val answers = mapOf(
            "saptiya?" to "Saapten Owner 😄 Neenga saaptingala?",
            "saaptiya Kai?" to "Saapten Owner 😄 Neenga saaptingala?",
            "good morning" to "Good morning Owner ☀️ Innaiku business-a start pannalama?",
            "super Kai" to "Thanks Owner 😄",
        )
        for ((q, a) in answers) assertEquals(q, a, kai.ask(q).reply.text)
        for (s in listOf("enna panra?", "busy ah?", "good night", "epdi iruka?", "thanks")) {
            val t = kai.ask(s)
            assertFalse("$s → ${t.reply.text}", t.reply.text.contains("clear-ah") || t.reply.text.contains("meaning"))
            assertEquals(s, KaiIntentKind.CHAT, KaiIntents.classify(s, now, emptyList(), tools.products))
        }
        assertTrue(tools.stockChanges.isEmpty() && tools.scheduled.isEmpty() && tools.prepared.isEmpty())
    }

    // ------------------------------------------------------------ learning (per owner, only after yes)

    @Test
    fun slangIsLearnedForThisOwnerOnly() = runBlocking {
        val ask = kai.ask("'ramba' na romba")
        assertEquals("Seri Owner 😄 `ramba` = `romba` nu save pannava?", ask.reply.text)
        assertNull("nothing saved before yes", access.memory.find("ramba"))
        val done = kai.ask("aama")
        assertEquals("Done Owner 👍 Inime neenga `ramba` sonna `romba` nu purinjukuren.", done.reply.text)
        assertEquals("owner-A", access.memory.find("ramba")!!.ownerId)
        assertEquals(KaiIntentKind.LEARN_SLANG, KaiIntents.classify("'ramba' na romba", now, emptyList(), tools.products))
        // Owner B on the same phone: Owner A's word is not used.
        val b = Access(store, "owner-B", tools)
        b.current()
        assertNull(b.memory.find("ramba"))
        assertEquals("innaiku ramba busy", b.memory.apply("innaiku ramba busy", emptyList()).text)
        assertEquals("innaiku romba busy", access.current().apply("innaiku ramba busy", emptyList()).text)
    }

    @Test
    fun businessMeaningNeedsExplicitConfirmation() = runBlocking {
        val t = kai.ask("puli = customer payment")
        assertEquals("Owner, `puli` = Payment In — idhu business meaning-aa save pannava Owner?", t.reply.text)
        assertNull(access.memory.find("puli"))
        assertTrue(tools.prepared.isEmpty())
        val yes = t.actions().filterIsInstance<KaiAction.LearnMeaning>().single()
        val saved = kai.act(yes, KaiLang.TANGLISH)!!
        assertTrue(saved.reply.text, saved.reply.text.contains("`puli` = Payment In"))
        assertEquals(KaiMeaning.PAYMENT_IN, access.memory.find("puli")!!.meaning)
        // Still only a draft when used: accounting rules never change by themselves.
        assertTrue(tools.prepared.isEmpty())
    }

    @Test
    fun unknownWordGetsOneQuestion() = runBlocking {
        val t = kai.ask("innaiku vyabaaram ramba")
        assertEquals("Owner, `ramba` nu sonnadhu enna meaning-la?", t.reply.text)
    }

    // ------------------------------------------------------------ morning work, business questions, calculator

    @Test
    fun morningWorkPhrases() {
        for (s in listOf("kaalai work ready pannu", "morning work pannu", "innaiku enna important?", "today enna panna vendum?",
            "shop open panna munadi enna seiyanum?", "morning brief kudu")) {
            assertNotNull(s, MorningCommands.morningRequest(s))
            assertEquals(s, KaiIntentKind.MORNING_WORK, KaiIntents.classify(s, now, emptyList(), tools.products))
        }
        // A reminder sentence about today is never Morning Work.
        assertNull(MorningCommands.morningRequest("innaiku Kumar-ku call pannanum remind pannu"))
    }

    @Test
    fun paymentDueInEveryLanguageFromTheRecords() = runBlocking {
        for (s in listOf("குமார் எனக்கு எப்ப பணம் தரணும்?", "Kumar enaku eppa payment tharanum?", "When will Kumar pay me?", "Kumar enaku entha date-la payment tharanum?")) {
            val t = newKai().ask(s)
            assertTrue("$s → ${t.reply.text}", t.reply.text.contains("10,000") && (t.reply.text.contains("October 12") || t.reply.text.contains("அக்டோபர் 12")))
        }
        for (s in listOf("Kumar enaku eppa payment tharanum?", "When will Kumar pay me?")) {
            assertEquals(s, KaiIntentKind.PAYMENT_QUERY, KaiIntents.classify(s, now, listOf("Kumar", "Selvam"), tools.products))
        }
        // Someone not in the records: no amount or date is made up.
        val unknown = newKai().ask("Ramesh enaku eppa payment tharanum?")
        assertFalse(unknown.reply.text, unknown.reply.text.contains("₹"))
        assertFalse(unknown.reply.text, unknown.reply.text.contains("October"))
    }

    @Test
    fun calculatorStillWorks() = runBlocking {
        assertEquals("2,000 g", kai.ask("2 kilo evlo gram?").reply.text)
        assertEquals(KaiIntentKind.CALCULATE, KaiIntents.classify("2 kilo evlo gram?", now, emptyList(), tools.products))
    }

    // ------------------------------------------------------------ voice vs text delivery

    @Test
    fun voiceOnlyWhenSpoken() {
        assertNull(KaiSpeech.forReply("Saapten Owner 😄 Neenga saaptingala?", voice = false))
        val spoken = KaiSpeech.forReply("Saapten Owner 😄 Neenga saaptingala?", voice = true)!!
        assertEquals("Saapten Owner Neenga saaptingala?", spoken.speech)
        assertEquals("en-IN", spoken.languageTag)
        assertEquals("ta-IN", KaiSpeech.forReply("சாப்டேன் ஓனர் 😄", voice = true)!!.languageTag)
        // Same answer for the same words, whatever the input mode.
        val a = runBlocking { newKai(Tools(), Access(InMemoryKaiMemoryStore(), "x", Tools())).ask("saaptiya?") }
        val b = runBlocking { newKai(Tools(), Access(InMemoryKaiMemoryStore(), "x", Tools())).ask("saaptiya?") }
        assertEquals(a.reply.text, b.reply.text)
    }
}
