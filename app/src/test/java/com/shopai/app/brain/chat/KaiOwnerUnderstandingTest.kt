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
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.KaiPaymentDirection
import com.shopai.app.brain.tools.KaiSpokenWords
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.OwedDirection
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.StockFact
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
import kotlin.random.Random

/**
 * Kai understands the owner across turns: who "avan" is, what "adhu" / "athula" is, which of three Lokeshes is
 * meant (phone, town, shop name, the one being talked about — else Kai asks), what is still pending, and that a
 * casual "saptiya?" or a sum in between does not lose the payment being discussed. One conversation state, the
 * books as the truth, the owner's taught words from their own memory, nothing saved without Confirm.
 */
class KaiOwnerUnderstandingTest {
    // Thursday 8 October 2026, 11 AM.
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)

    /** One business's records: what Kai's tools write (only on Confirm) and what the Business Brain reads. */
    class Db(threeLokesh: Boolean = true) {
        data class Party(val match: PartyMatch, var pending: BigDecimal, var due: LocalDate?)
        val parties = mutableListOf(
            Party(PartyMatch("c1", "Kumar", true, "+919000000001", BigDecimal("3000.00")), BigDecimal("3000.00"), LocalDate.of(2026, 10, 20)),
            Party(PartyMatch("c2", "Ravi", true, null, BigDecimal("1500.00")), BigDecimal("1500.00"), null),
            Party(PartyMatch("s1", "Ramesh", false, null, BigDecimal("2500.00")), BigDecimal("2500.00"), null),
            Party(PartyMatch("l1", "Lokesh", false, "+919876543210", BigDecimal.ZERO, city = "Chennai"), BigDecimal.ZERO, null),
            Party(PartyMatch("l2", "Lokesh", false, "+919123456780", BigDecimal.ZERO, city = "Nagapattinam", details = "Sri Vinayaga Hardware, Main Road"), BigDecimal.ZERO, null),
        ).apply { if (threeLokesh) add(Party(PartyMatch("l3", "Lokesh", false, null, BigDecimal.ZERO, city = "Trichy"), BigDecimal.ZERO, null)) }
        val saved = mutableListOf<ActionPlan>()
        fun summary(p: Party) = PartySummary(p.match.id, p.match.name, p.match.phone, p.pending.toDouble(), p.due?.let { "${it}T00:00:00Z" })
    }

    private class Books(val db: Db) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = db.parties.filter { it.match.customer }.map(db::summary),
            suppliers = db.parties.filter { !it.match.customer }.map(db::summary),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val db: Db) : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val productList = listOf(
            ProductRef("p1", "Colgate", "PCS", BigDecimal("20"), mapOf("BOX" to BigDecimal(12))),
            ProductRef("p2", "Rice", "KG", BigDecimal("50")),
        )
        /** Only this business's records with that name (the books' scoped search) — never the whole list. */
        override suspend fun parties(name: String) = db.parties.map { it.match.copy(balance = it.pending) }.filter { it.name.contains(name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            val customer = plan.kind == PlanKind.CREDIT_GIVEN
            val existing = db.parties.firstOrNull { it.match.id == plan.partyId }
                ?: Db.Party(PartyMatch("n${db.parties.size}", plan.partyName, customer, null, BigDecimal.ZERO), BigDecimal.ZERO, null).also { db.parties += it }
            existing.pending = existing.pending + plan.amount
            if (plan.dueDate != null) existing.due = plan.dueDate
            db.saved += plan
            return ActionOutcome.Done("TXN-${db.saved.size}", existing.pending)
        }
        override suspend fun products() = productList
        override suspend fun stock(product: String?) = productList.filter { product == null || it.name.equals(product, true) }
            .map { StockFact(it.name, it.stock, it.unit, reorderAt = if (it.name == "Colgate") BigDecimal("25") else BigDecimal("10")) }
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming); return ActionOutcome.Done("ST-1", null)
        }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
    }

    /** The signed-in owner + business; the owner's taught words live in this business's memory only. */
    private class Access(store: InMemoryKaiMemoryStore, var business: String, val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory = memory.also { it.open(business, "owner-$business") }
        override suspend fun entities(): List<KnownEntity> = tools.productList.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) }
    }

    private var db = Db()
    private var tools = Tools(db)
    private fun kai(d: Db = Db().also { db = it }, access: Boolean = false): KaiAgent {
        tools = Tools(d)
        val books = Books(d)
        return KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }, random = Random(1)), books, tools, now = { now },
            memory = if (access) Access(InMemoryKaiMemoryStore(), "biz-A", tools) else null)
    }
    private fun KaiAgent.say(text: String) = runBlocking { ask(text) }
    private fun KaiAgent.chat(vararg lines: String): KaiTurn = lines.map { say(it) }.last()
    private fun KaiTurn.text() = reply.text
    private fun noClearAh(t: KaiTurn) = assertFalse(t.text(), t.text().contains("clear-ah", ignoreCase = true))
    private fun nothingSaved() = assertTrue("nothing saved: ${db.saved}", db.saved.isEmpty())

    // ================================================================ stock words (owner's phone, 10 Oct 2026)

    @Test
    fun poiruchiIsStockOutAndVangiirukanStockIn() {
        // "mambalam 30kg poiruchi" was asked "stock add pannradha?" — it went out.
        val out = kai(access = true).say("Rice 30 kg poiruchi")
        assertTrue(out.text(), out.text().contains("stock-out", true) || out.text().contains("Stock Out", true))
        assertFalse(out.text(), out.text().contains("stock add pannradha"))
        for (line in listOf("Rice 30kg vangiirukan", "Rice 30 kg vangi irukan", "ரைஸ் 30 கிலோ வாங்கியிருக்கான்")) {
            val t = kai(access = true).say(line)
            assertTrue("$line → ${t.text()}", t.text().contains("stock-in", true) && !t.text().contains("pannradha"))
        }
        // A word Kai doesn't know that sounds like "went" is asked as stock OUT first — never add.
        val unknown = kai(access = true).say("Rice 30 kg pochaanga")
        assertTrue(unknown.text(), unknown.text().contains("stock out pannradha"))
        // "podu" (put) stays a stock-in guess.
        assertFalse(kai(access = true).say("Rice 30 kg podunga sir").text().contains("stock out pannradha"))
        nothingSaved()
    }

    // ================================================================ context continuation (A, B, F)

    @Test
    fun testA_dueDateAcrossFourMessages() {
        val k = kai()
        assertTrue(k.say("Kumar enaku 3000 tharanum").text().contains("Due date"))
        k.say("eppa?")
        assertEquals("Owner, indha maasam 10-aa, illa adutha maasam 10-aa?", k.say("10").text())
        val t = k.say("next month")
        // The books already hold Kumar ₹3,000: same-or-new is asked with the date (nothing saved).
        assertEquals("Seri Owner, Kumar kitta irundhu ₹3,000 adutha maasam 10-m thethi vaanganum. Owner, records-la already Kumar ₹3,000 tharanum-nu irukku. Adhey ₹3,000-aa, illa pudhu ₹3,000-aa?", t.text())
        val st = k.conversationState.stated!!
        assertEquals("Kumar", st.person)
        assertEquals(BigDecimal("3000.00"), st.amount)
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, st.direction)
        assertEquals(LocalDate.of(2026, 11, 10), st.dueDate)
        nothingSaved()
    }

    @Test
    fun testB_smallTalkAndSeriKeepThePayment() {
        val k = kai()
        val t = k.chat("Kumar enaku 3000 tharanum", "saptiya?", "seri", "avan eppa tharuvaan?")
        assertTrue(t.text(), t.text().contains("Kumar") && t.text().contains("₹3,000"))
        noClearAh(t)
    }

    @Test
    fun testF_calculatorThenAvan() {
        val k = kai()
        assertEquals("1,625", k.chat("Kumar enaku 3000 tharanum", "1250 plus 375").text())
        val t = k.say("avan eppa tharuvaan?")
        assertTrue(t.text(), t.text().contains("Kumar") && t.text().contains("₹3,000"))
    }

    @Test
    fun calculatorThenSeriThenAvan() {
        val t = kai().chat("Kumar enaku 3000 tharanum", "1250 plus 375", "seri", "avan eppa tharuvaan?")
        assertTrue(t.text(), t.text().contains("Kumar") && t.text().contains("₹3,000"))
    }

    @Test
    fun theDateAlreadyGivenIsSaidBackNotAskedAgain() {
        val t = kai().chat("Kumar enaku 3000 tharanum", "10", "next month", "avan eppa tharuvaan?")
        assertEquals("Owner, Kumar ₹3,000 adutha maasam 10-m thethi tharuvaar. Innum save pannala Owner.", t.text())
    }

    @Test
    fun afterTheDraftCardTheDirectionStaysReceivable() {
        val t = kai().chat("Kumar enaku 3000 tharanum", "10", "next month", "note panniko", "avan eppa tharuvaan?")
        assertTrue(t.text(), t.text().contains("tharuvaar") && !t.text().contains("kudukkanum"))
    }

    @Test
    fun afterSavingTheSavedDateIsSaidBack() {
        val k = kai()
        // "pudhusu": the books already hold Kumar ₹3,000, so Kai asks same-or-new before the draft.
        k.chat("Kumar enaku 3000 tharanum", "next month 10", "save panniko", "pudhusu", "seri")
        val t = k.say("avan eppa tharuvaan?")
        assertEquals("Owner, Kumar ₹3,000 adutha maasam 10-m thethi tharuvaar.", t.text())
    }

    @Test
    fun payableFollowUpKeepsPayable() {
        val t = kai().chat("naan Ramesh-ku 4000 tharanum", "July 6", "saptiya?", "avanukku eppa kudukkanum?")
        assertTrue(t.text(), t.text().contains("Ramesh-ku ₹4,000") && t.text().contains("kudukkanum"))
    }

    // ================================================================ pronouns

    @Test
    fun avanAfterABalanceQuestionIsThatPerson() {
        val t = kai().chat("Kumar balance evlo?", "avan eppa tharuvaan?")
        assertTrue(t.text(), t.text().contains("Kumar") && t.text().contains("October 20"))
    }

    @Test
    fun avarAndAvangaAlsoResolve() {
        for (ref in listOf("avar eppa tharuvar?", "avanga eppa tharuvaanga?", "andha customer eppa tharuvaan?")) {
            val t = kai().chat("Kumar balance evlo?", ref)
            assertTrue("$ref → ${t.text()}", t.text().contains("Kumar"))
        }
    }

    @Test
    fun testH_twoPeopleThenAvanIsAsked() {
        val k = kai()
        val both = k.say("Kumar and Ramesh rendu perum payment pending.")
        assertTrue(both.text(), both.text().contains("Kumar ₹3,000") && both.text().contains("Ramesh-ku ₹2,500"))
        assertEquals("Kumar-aa Ramesh-aa Owner?", k.say("avan eppa?").text())
    }

    @Test
    fun theAnswerToKumarOrRameshContinuesTheQuestion() {
        val k = kai()
        k.chat("Kumar and Ramesh rendu perum payment pending.", "avan eppa?")
        val t = k.say("Kumar")
        assertTrue(t.text(), t.text().contains("Kumar") && t.text().contains("October 20"))
    }

    @Test
    fun aPronounWithNobodyInTheConversationIsNotInvented() {
        val t = kai().say("avan eppa tharuvaan?")
        assertFalse(t.text(), t.text().contains("Kumar") || t.text().contains("Lokesh"))
        assertNull(t.plan)
    }

    @Test
    fun referenceRewriteKeepsTheCaseEnding() {
        assertEquals("Lokesh-ku 500 tharanum", KaiEntityResolver.withName("avanukku 500 tharanum", "Lokesh"))
        assertEquals("Kumar eppa tharuvaan?", KaiEntityResolver.withName("avan eppa tharuvaan?", "Kumar"))
        assertEquals("Kumar oda balance", KaiEntityResolver.withName("avanoda balance", "Kumar"))
        assertTrue(KaiEntityResolver.mentionsPerson("andha customer eppa?"))
        assertFalse(KaiEntityResolver.mentionsPerson("adhu low-aa?"))
    }

    // ================================================================ stock context (C, D)

    @Test
    fun testC_shelfCountThenAdhuIsColgate() {
        val k = kai()
        assertEquals("Seri Owner, records-la-um Colgate 20 PCS dhaan irukku.", k.say("Colgate 20 pieces irukku").text())
        val t = k.say("adhu low-aa?")
        assertTrue(t.text(), t.text().startsWith("Colgate stock 20 PCS irukku") && t.text().contains("reorder level-kku keezha"))
        assertTrue(tools.stockChanges.isEmpty())
    }

    @Test
    fun aDifferentShelfCountIsComparedNotWritten() {
        val t = kai().say("Colgate 25 pieces irukku")
        assertTrue(t.text(), t.text().contains("records-la Colgate 20 PCS irukku"))
        assertTrue(tools.stockChanges.isEmpty())
        assertNull(t.card)
    }

    @Test
    fun testD_athulaAfterAStockInIsColgate() {
        val k = kai()
        k.say("Colgate 2 box vandhudhu")
        // After a box entry "5" alone is exactly the case that must be asked: pieces or boxes.
        assertTrue(k.say("athula 5 pochu").text().contains("5 pieces-aa, 5 boxes-aa"))
        val t = k.say("pieces")
        assertTrue(t.text(), t.text().contains("Colgate") && t.text().contains("5 pieces stock-out"))
        assertTrue("draft only", tools.stockChanges.isEmpty())
    }

    @Test
    fun stockQuestionThenAdhuThenAthula() {
        val k = kai()
        assertEquals("Owner, Colgate stock 20 PCS irukku.", k.say("Colgate stock evlo?").text())
        assertTrue(k.say("adhu low-aa?").text().contains("Colgate"))
        assertTrue(k.say("athula 5 pochu").text().contains("Colgate — 5 pieces-aa, 5 boxes-aa"))
        assertTrue(k.say("pieces").text().contains("Colgate — 5 pieces stock-out"))
    }

    @Test
    fun adhuLowAaIsAnsweredInTanglish() {
        val t = kai().chat("Colgate stock evlo?", "adhu low-aa?")
        assertFalse(t.text(), t.text().contains("in stock, at or below"))
    }

    // ================================================================ topic switching (G)

    @Test
    fun testG_anotherPersonsQuestionSwitchesThePerson() {
        val k = kai()
        val t = k.chat("Kumar enaku 3000 tharanum", "Ramesh enna tharanum?")
        assertTrue(t.text(), t.text().contains("Ramesh") && t.text().contains("₹2,500"))
        assertEquals("Ramesh", k.conversationState.lastPerson)
    }

    @Test
    fun stockQuestionSwitchesTheTopicAndAdhuIsTheProduct() {
        val k = kai()
        k.chat("Kumar enaku 3000 tharanum", "Colgate stock evlo?")
        val t = k.say("adhu low-aa?")
        assertTrue(t.text(), t.text().contains("Colgate"))
        assertFalse(t.text().contains("Kumar"))
    }

    @Test
    fun afterAStockSwitchABareEppaDoesNotGuessKumar() {
        val t = kai().chat("Kumar enaku 3000 tharanum", "Colgate stock evlo?", "adhu low-aa?", "eppa?")
        assertFalse(t.text(), t.text().contains("Kumar ₹3,000"))
    }

    @Test
    fun avanAfterAStockSwitchStillMeansThePerson() {
        val t = kai().chat("Kumar enaku 3000 tharanum", "Colgate stock evlo?", "avan eppa tharuvaan?")
        assertTrue(t.text(), t.text().contains("Kumar"))
    }

    // ================================================================ duplicate names: phone, place, shop, context (I–M)

    @Test
    fun testI_placeSelectsNagapattinamLokesh() {
        val k = kai()
        val t = k.say("Lokesh Nagapattinam-ku 500 tharanum")
        assertEquals("l2", k.conversationState.stated!!.partyId)
        assertTrue(t.text(), t.text().startsWith("Seri Owner, Nagapattinam Lokesh-ku ₹500 pay pannanum."))
        nothingSaved()
    }

    @Test
    fun testI_theDraftIsForThatRecord() {
        val k = kai()
        val draft = k.chat("Lokesh Nagapattinam-ku 500 tharanum", "save panniko")
        assertEquals("l2", draft.plan!!.partyId)
        assertEquals(PlanKind.DEBIT_TAKEN, draft.plan.kind)
        assertEquals("Nagapattinam Lokesh — ₹500", draft.card!!.lines.first())
        k.say("seri")
        assertEquals("l2", db.saved.single().partyId)
    }

    @Test
    fun placeBeforeTheNameWorksToo() {
        val k = kai()
        k.say("Chennai Lokesh-ku 1000 kudukkanum")
        assertEquals("l1", k.conversationState.stated!!.partyId)
    }

    @Test
    fun testJ_twoLokeshAreAskedNeverPicked() {
        val k = kai(Db(threeLokesh = false).also { db = it })
        val t = k.say("Lokesh-ku 500 tharanum")
        assertEquals("Owner, Lokesh-nu rendu records irukku. Chennai Lokesh-aa illa Nagapattinam Lokesh-aa?", t.text())
        assertNull(k.conversationState.stated!!.partyId)
        assertTrue(tools.prepared.isEmpty())
    }

    @Test
    fun threeLokeshAreListedByTown() {
        val t = kai().say("Lokesh-ku 500 tharanum")
        assertEquals("Owner, Lokesh-nu moonu records irukku. Chennai Lokesh-aa, Nagapattinam Lokesh-aa, illa Trichy Lokesh-aa?", t.text())
    }

    @Test
    fun theOwnersPickContinuesThePayment() {
        val k = kai()
        k.say("Lokesh-ku 500 tharanum")
        val t = k.say("Nagapattinam")
        assertEquals("Seri Owner, Nagapattinam Lokesh-ku ₹500 pay pannanum. Due date eppa?", t.text())
        assertEquals("l2", k.conversationState.stated!!.partyId)
        assertEquals("l2", k.chat("save panniko").plan!!.partyId)
    }

    @Test
    fun thePickCanBeAnOrdinal() {
        val k = kai()
        k.say("Lokesh-ku 500 tharanum")
        k.say("moonavadhu")
        assertEquals("l3", k.conversationState.stated!!.partyId)
    }

    @Test
    fun anUnrelatedAnswerDropsTheQuestionWithoutPicking() {
        val k = kai()
        k.say("Lokesh-ku 500 tharanum")
        k.say("saptiya?")
        assertNull(k.conversationState.stated!!.partyId)
        assertNull(k.conversationState.entityChoice)
    }

    @Test
    fun testK_contextualEntityThroughAvanukku() {
        val k = kai()
        assertEquals("Seri Owner, Nagapattinam Lokesh pathi sollunga.", k.say("Nagapattinam Lokesh pathi pesuren.").text())
        val t = k.say("avanukku 500 tharanum.")
        assertEquals("l2", k.conversationState.stated!!.partyId)
        assertTrue(t.text(), t.text().contains("Nagapattinam Lokesh-ku ₹500"))
    }

    @Test
    fun contextualEntityAlsoForTheBareName() {
        val k = kai()
        k.say("Nagapattinam Lokesh pathi pesuren.")
        k.say("Lokesh-ku 700 kudukkanum")
        assertEquals("l2", k.conversationState.stated!!.partyId)
    }

    @Test
    fun aboutAnAmbiguousNameAsksWhichOne() {
        val k = kai()
        val t = k.say("Lokesh pathi pesuren")
        assertTrue(t.text(), t.text().startsWith("Owner, Lokesh-nu moonu records irukku."))
        assertEquals("Seri Owner, Trichy Lokesh pathi sollunga.", k.say("Trichy").text())
        k.say("avanukku 200 kudukkanum")
        assertEquals("l3", k.conversationState.stated!!.partyId)
    }

    @Test
    fun chennaiLokeshThenAvanStaysChennai() {
        val k = kai()
        k.say("Chennai Lokesh-ku 1000 kudukkanum")
        val t = k.say("avanukku eppa kudukkanum?")
        assertTrue(t.text(), t.text().contains("Chennai Lokesh"))
    }

    @Test
    fun testL_shopNameSelectsTheRecord() {
        val k = kai()
        k.say("Lokesh Sri Vinayaga Hardware-ku 500 tharanum")
        assertEquals("l2", k.conversationState.stated!!.partyId)
    }

    @Test
    fun testM_phoneIsTheStrongestIdentifier() {
        val k = kai()
        val t = k.say("Lokesh 9876543210-ku 500 tharanum")
        assertEquals("l1", k.conversationState.stated!!.partyId)
        assertEquals(KaiConversationPaymentDirection.PAYMENT_OUT, k.conversationState.stated!!.direction)
        assertTrue(t.text(), t.text().contains("Chennai Lokesh"))
    }

    @Test
    fun phoneBeatsAPlaceWord() {
        val k = kai()
        k.say("Lokesh Trichy 9123456780-ku 500 tharanum")
        assertEquals("l2", k.conversationState.stated!!.partyId)
    }

    @Test
    fun aSimilarNameIsNeverTakenAsLokesh() {
        val k = kai()
        k.say("Lokeshwaran-ku 500 tharanum")
        assertNull(k.conversationState.stated!!.partyId)
        assertEquals("Lokeshwaran", k.conversationState.stated!!.person)
    }

    @Test
    fun anotherBusinessesLokeshIsNeverSelected() {
        val other = Db().apply { parties.removeAll { it.match.name == "Lokesh" } }
        val k = kai(other.also { db = it })
        k.say("Lokesh Nagapattinam-ku 500 tharanum")
        assertNull(k.conversationState.stated!!.partyId)
        val draft = k.say("save panniko")
        assertNull(draft.plan!!.partyId)
        assertTrue(draft.card!!.lines.contains("Puthu supplier-ah add aagum"))
    }

    @Test
    fun existingPaymentFlowAlsoResolvesByPlace() {
        val k = kai()
        val t = k.say("Lokesh Trichy-ku 500 kuduthen")
        assertEquals("l3", t.plan?.partyId)
    }

    @Test
    fun existingPaymentFlowAsksWithTownLabels() {
        val t = kai().say("Lokesh-ku 500 kuduthen")
        val labels = t.card!!.buttons.map { it.label }
        assertTrue(labels.toString(), labels.any { it.startsWith("Chennai Lokesh") } && labels.any { it.startsWith("Nagapattinam Lokesh") })
    }

    @Test
    fun resolverRules() {
        val l = db.parties.map { it.match }
        assertEquals("l2", (KaiEntityResolver.resolve("Lokesh", "Lokesh 9123456780", l) as KaiEntityResolver.Result.One).party.id)
        assertEquals("l1", (KaiEntityResolver.resolve("Lokesh", "chennai lokesh", l) as KaiEntityResolver.Result.One).party.id)
        assertEquals("l3", (KaiEntityResolver.resolve("Lokesh", "lokesh", l, contextId = "l3") as KaiEntityResolver.Result.One).party.id)
        assertEquals(3, (KaiEntityResolver.resolve("Lokesh", "lokesh", l) as KaiEntityResolver.Result.Many).candidates.size)
        assertEquals(KaiEntityResolver.Result.None, KaiEntityResolver.resolve("Lokeshwaran", "Lokeshwaran", l))
        assertEquals("c1", (KaiEntityResolver.resolve("Kumar", "Kumar", l) as KaiEntityResolver.Result.One).party.id)
    }

    // ================================================================ name preservation

    @Test
    fun kumaranStaysKumaran() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        assertEquals("Kumaran", k.conversationState.stated!!.person)
        assertNull(k.conversationState.stated!!.partyId)
        assertEquals("Owner, Kumaran-nu separate customer-aa? Kumar-a?", k.say("save panniko").text())
    }

    // ================================================================ partial information

    @Test
    fun piecesCombineIntoOnePayment() {
        val k = kai()
        assertTrue(k.say("Mahesh").text().startsWith("Owner, Mahesh pathi enna?"))
        assertTrue(k.say("3000").text().startsWith("Mahesh ₹3,000"))
        val t = k.say("enakku tharanum")
        assertEquals("Seri Owner, Mahesh kitta irundhu ₹3,000 collect pannanum. Due date eppa?", t.text())
        assertEquals("Owner, adutha maasam endha thethi?", k.say("next month").text())
        assertEquals(LocalDate.of(2026, 11, 5), k.chat("5").let { k.conversationState.stated!!.dueDate })
        nothingSaved()
    }

    @Test
    fun piecesWithoutAnAmountAskTheAmount() {
        val k = kai()
        k.say("Mahesh")
        assertEquals("Owner, Mahesh-ku evlo kudukkanum?", k.say("naan kudukkanum").text())
    }

    @Test
    fun aLoneLowercaseWordIsNotTakenAsAName() {
        val k = kai()
        k.say("seri")
        k.say("3000")
        assertNull(k.conversationState.fragmentAmount)
    }

    @Test
    fun oldPiecesExpire() {
        val k = kai()
        k.chat("Mahesh", "saptiya?", "epdi iruka?", "super Kai", "thanks Kai")
        k.say("enakku tharanum")
        assertNull(k.conversationState.stated)
    }

    // ================================================================ payment direction

    @Test
    fun directionFromGrammar() {
        fun dir(t: String) = KaiPaymentDirection.of(KaiSpokenWords.normalize(t))
        assertEquals(OwedDirection.RECEIVABLE, dir("Kumar enaku 3000 tharanum"))
        assertEquals(OwedDirection.PAYABLE, dir("naan Kumar-ku 3000 tharanum"))
        assertEquals(OwedDirection.RECEIVABLE, dir("Kumar enakku 3000 tharan"))
        assertEquals(OwedDirection.PAYABLE, dir("Lokesh 9876543210-ku 500 tharanum"))
        assertEquals(OwedDirection.RECEIVABLE, dir("Kumar kitta 3000 vangan"))
    }

    @Test
    fun receivableAndPayableStatesAreStructured() {
        val k = kai()
        k.say("Kumar enaku 3000 tharanum")
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, k.conversationState.stated!!.direction)
        k.say("naan Ravi-ku 1000 tharanum")
        assertEquals(KaiConversationPaymentDirection.PAYMENT_OUT, k.conversationState.stated!!.direction)
        assertEquals("Ravi", k.conversationState.stated!!.person)
    }

    // ================================================================ confirmation, save, cancel, persistence (E)

    @Test
    fun testE_saveReachesTheBooksAndTheBrainReadsIt() {
        val k = kai()
        k.chat("Mahesh enaku 2000 tharanum", "save panniko")
        nothingSaved()
        k.say("seri")
        assertEquals(BigDecimal("2000.00"), db.saved.single().amount)
        val t = k.say("Mahesh enaku evlo tharanum?")
        assertTrue(t.text(), t.text().contains("₹2,000"))
    }

    @Test
    fun savedDataSurvivesANewKai() {
        kai().chat("Mahesh enaku 2000 tharanum", "save panniko", "seri")
        val fresh = KaiAgent(KaiBusinessBrain(Books(db), today = { now.toLocalDate() }, random = Random(1)), Books(db), Tools(db), now = { now })
        assertTrue(fresh.say("Mahesh enaku evlo tharanum?").text().contains("₹2,000"))
    }

    @Test
    fun cancelSavesNothing() {
        val k = kai()
        k.chat("Mahesh enaku 2000 tharanum", "save panniko", "venam")
        nothingSaved()
    }

    @Test
    fun neverClaimsSuccessWithoutTheBooks() {
        val k = kai()
        for (line in listOf("Mahesh enaku 2000 tharanum", "eppa?", "10", "next month", "add pannitiya?"))
            assertFalse(line, k.say(line).text().contains("pannitten"))
        nothingSaved()
    }

    // ================================================================ action vs question

    @Test
    fun questionLooksUpActionStates() {
        val k = kai()
        val q = k.say("Mahesh enaku evlo tharanum?")
        assertEquals("Owner, Mahesh-nu customer record enakku kidaikala.", q.text())
        assertNull(k.conversationState.stated)
        val a = k.say("Mahesh enaku 2000 tharanum, save panniko")
        assertEquals(PlanKind.CREDIT_GIVEN, a.plan?.kind)
    }

    @Test
    fun paymentWhenIsALookupRemindIsAReminder() {
        assertTrue(kai().say("Kumar-ku payment eppa?").text().contains("October 20"))
        assertTrue(kai().say("Kumar-ku payment remind pannu").text().contains("remind"))
    }

    @Test
    fun businessQuestionsUseTheRecords() {
        assertTrue(kai().say("Kumar balance evlo?").text().contains("₹3,000"))
        assertTrue(kai().say("Ramesh-ku evlo kudukkanum?").text().contains("₹2,500"))
        assertTrue(kai().say("Colgate stock evlo?").text().contains("20 PCS"))
    }

    // ================================================================ owner memory

    @Test
    fun taughtWordIsUsedLater() {
        val k = kai(access = true)
        k.say("potti na box")
        assertTrue(k.say("aama").text().contains("potti"))
        val t = k.say("Colgate 2 potti vandhudhu")
        assertTrue(t.text(), t.text().contains("2 boxes = 24 pieces"))
        assertTrue(tools.stockChanges.isEmpty())
    }

    @Test
    fun anotherBusinessStartsWithAnEmptyConversation() {
        val d = Db()
        val t = Tools(d)
        val access = Access(InMemoryKaiMemoryStore(), "biz-A", t)
        val k = KaiAgent(KaiBusinessBrain(Books(d), today = { now.toLocalDate() }, random = Random(1)), Books(d), t, now = { now }, memory = access)
        k.say("Kumar enaku 3000 tharanum")
        access.business = "biz-B"
        k.say("saptiya?")
        assertNull("business B never sees A's conversation", k.conversationState.stated)
        assertNull(k.conversationState.lastPerson)
    }

    // ================================================================ language, STT, casual

    @Test
    fun tamilScriptAndTanglishReachTheSameState() {
        val a = kai().also { it.say("குமார் எனக்கு 3000 தரணும்") }.conversationState.stated!!
        val b = kai().also { it.say("Kumar enakku 3000 tharanum") }.conversationState.stated!!
        assertEquals(b.person, a.person)
        assertEquals(b.amount, a.amount)
        assertEquals(b.direction, a.direction)
    }

    @Test
    fun spokenNumberWordsMatchTypedDigits() {
        val spoken = kai().also { it.say("Kumar enakku moonu aayiram tharanum") }.conversationState.stated!!
        assertEquals(BigDecimal("3000.00"), spoken.amount)
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, spoken.direction)
    }

    @Test
    fun clippedVerbsFromSpeechAreUnderstood() {
        val k = kai()
        val t = k.say("Kumar enakku 3000 tharan")
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, k.conversationState.stated!!.direction)
        assertFalse(t.text().contains("mean pannureengala"))
    }

    @Test
    fun englishStatementWorks() {
        val k = kai()
        k.say("Mahesh owes me 2000")
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, k.conversationState.stated!!.direction)
    }

    @Test
    fun tamilFollowUpStaysTamil() {
        val t = kai().chat("குமார் எனக்கு 3000 தரணும்", "எப்போ?")
        assertTrue(t.text(), t.text().contains("Kumar") && t.text().contains("₹3,000") && t.text().contains("தரணும்"))
    }

    @Test
    fun casualVariantsAreAnsweredAndKeepContext() {
        val k = kai()
        k.say("Kumar enaku 3000 tharanum")
        for (c in listOf("saptia?", "saptya", "saaptacha?", "epdi iruka?", "super Kai", "thanks Kai")) {
            val t = k.say(c)
            noClearAh(t)
            assertNull(c, t.plan)
        }
        assertEquals("Kumar", k.conversationState.stated!!.person)
        assertTrue(k.say("avan eppa tharuvaan?").text().contains("Kumar"))
    }

    @Test
    fun noValueIsInvented() {
        val t = kai().say("innaikku sales evlo?")
        assertFalse(t.text(), Regex("""₹\d""").containsMatchIn(t.text()))
    }

    @Test
    fun englishAmbiguityQuestion() {
        assertEquals("Owner, there are 2 records named Lokesh. Chennai Lokesh or Nagapattinam Lokesh?",
            KaiEntityResolver.question("Lokesh", Db(false).parties.filter { it.match.name == "Lokesh" }.map { it.match }, KaiLang.ENGLISH))
    }
}
