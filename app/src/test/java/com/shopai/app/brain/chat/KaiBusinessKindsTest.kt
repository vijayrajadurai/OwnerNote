package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.data.model.BusinessKind
import com.shopai.app.data.model.BusinessKind.RETAIL
import com.shopai.app.data.model.BusinessKind.SERVICE
import com.shopai.app.data.model.BusinessKind.WHOLESALE
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Kai by the business's type (owner, 21 Oct 2026, Phase 2), typed and as the ta-IN mic writes it:
 * - a service business's own service Kai has no word for ("blouse stitching 350") is a sale draft for the Daily Sales Note;
 * - a wholesaler's sale with no buyer is asked "யாருக்கு?" (never a walk-in cash sale by itself); a named one is a credit bill;
 * - "Kumar 2 box Colgate vaanginaan" is a sale to Kumar, for every shop;
 * - "report" offers the type's own reports; "indha vaara report" is a service shop's Daily Sales week, anyone else's business week.
 * Nothing is written before Confirm (principles 3 and 5); a type that isn't known decides nothing (principle 3).
 */
class KaiBusinessKindsTest {
    private val now = LocalDateTime.of(2026, 10, 21, 11, 0)

    private class Party(val id: String, val name: String, val customer: Boolean, val pending: BigDecimal)

    private class Books(val ps: List<Party>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = ps.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "2026-10-10T00:00:00Z") },
            suppliers = ps.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "2026-10-28T00:00:00Z") })
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory, val kinds: Set<BusinessKind>?, val category: String?) : KaiTools by inv {
        val cash = mutableListOf<Pair<BigDecimal, String>>()
        val sales = mutableListOf<String>()
        override suspend fun businessKinds() = kinds
        override suspend fun businessCategory() = category
        override suspend fun cashOpening(day: LocalDate) = BigDecimal("500")
        override suspend fun addCashSale(day: LocalDate, amount: BigDecimal, upi: Boolean, note: String): ActionOutcome { cash += amount to note; return ActionOutcome.Done("c${cash.size}", amount) }
        override suspend fun cashSale(product: ProductRef, qty: BigDecimal, amount: BigDecimal?, said: String): ActionOutcome? {
            sales += product.name
            return ActionOutcome.Done("INV-${sales.size}", BigDecimal.ZERO)
        }
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("b-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private inner class Shop(kinds: Set<BusinessKind>?, category: String? = "OTHER") {
        val ps = listOf(Party("c1", "Murugan Stores", true, BigDecimal("4000")), Party("c2", "Kumar", true, BigDecimal("1500")),
            Party("s1", "ABC Traders", false, BigDecimal("10000")))
        val inv = Inventory().apply {
            parties.clear()
            parties += ps.map { com.shopai.app.brain.tools.PartyMatch(it.id, it.name, it.customer, null, it.pending) }
            items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("2400"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g")
            items += Inventory.Item("p2", "Rice", "KG", BigDecimal("2500"), mapOf("BAG" to BigDecimal("25")), BigDecimal("56"), BigDecimal("65"), "Grocery", null)
            items += Inventory.Item("p3", "Shampoo", "PCS", BigDecimal("20"), emptyMap(), BigDecimal("80"), BigDecimal("120"), "Beauty", null)
        }
        val tools = Tools(inv, kinds, category)
        val kai = KaiAgent(KaiBusinessBrain(Books(ps), today = { now.toLocalDate() }), Books(ps), tools, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore())))
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { last = it }
        fun tap(pick: (KaiAction) -> Boolean): KaiTurn {
            val b = last?.card?.buttons?.firstOrNull { pick(it.action) } ?: error("no such button: ${all(last)}")
            return runBlocking { kai.act(b.action, KaiLang.TANGLISH)!! }.also { last = it }
        }
        fun nothingWritten() = tools.cash.isEmpty() && tools.sales.isEmpty() && inv.moves.isEmpty() && inv.bills.isEmpty() && inv.payments.isEmpty()
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")
    private fun ways(typed: String): List<String> = listOfNotNull(typed, KaiMicForm.of(typed)).distinct()
    private fun saleDraft(t: KaiTurn) = t.card?.buttons?.any { it.action is KaiAction.ConfirmSale } == true

    // ------------------------------------------------------------ a service business's own services

    @Test fun aServiceBusinessesOwnServiceIsASaleDraftAndSavedOnlyOnConfirm() {
        val lines = mapOf("blouse stitching 350" to "350", "alteration 100 panninom" to "100", "screen replacement 1500" to "1,500",
            "oru saree polish 250" to "250", "2 blouse alteration 200 gpay" to "200")
        for ((typed, amount) in lines) for (line in ways(typed)) {
            val s = Shop(setOf(SERVICE))
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", saleDraft(t) && all(t).contains("₹$amount"))
            assertTrue("$line: nothing before Confirm", s.nothingWritten())
            s.tap { it is KaiAction.ConfirmSale }
            assertEquals(line, 0, BigDecimal(amount.replace(",", "")).compareTo(s.tools.cash.single().first))
        }
        // As the mic writes an English service name in Tamil letters: the same sale, the name as said.
        for (line in listOf("பிளவுஸ் ஸ்டிச்சிங் 350", "ஆல்டரேஷன் 100 பண்ணினோம்")) {
            val t = Shop(setOf(SERVICE)).say(line)
            assertTrue("$line → ${all(t)}", saleDraft(t) && (all(t).contains("₹350") || all(t).contains("₹100")))
        }
    }

    @Test fun onlyANameAndAnAmountOfAServiceBusinessIsTakenAsAService() {
        // A person, a product, a money / stock word, one bare word: never a made-up service.
        for (line in listOf("Raja 2000", "Kumar 2000", "Shampoo 2", "selavu 500", "current bill 900", "nethu 300", "Murugan Stores 4000")) for (l in ways(line)) {
            val s = Shop(setOf(SERVICE))
            val t = s.say(l)
            assertFalse("$l → ${all(t)}", saleDraft(t))
            assertTrue(l, s.tools.cash.isEmpty())
        }
        // Not a service business, or the type not known: no service sale from a name Kai doesn't know.
        for (kinds in listOf(setOf(RETAIL), setOf(WHOLESALE), null)) for (line in ways("blouse stitching 350")) {
            val t = Shop(kinds).say(line)
            assertFalse("$kinds $line → ${all(t)}", saleDraft(t))
        }
        // The catalogue's own services still sell as before ("oru haircut 150").
        for (line in ways("oru haircut 150")) assertTrue(line, saleDraft(Shop(setOf(SERVICE), "BEAUTY_SALON").say(line)))
    }

    // ------------------------------------------------------------ a wholesaler's sales

    @Test fun aWholesalersSaleWithNoBuyerIsAskedWhomAndTheCustomerGetsACreditBill() {
        for (m in listOf(false, true)) {
            fun said(l: String) = if (m) KaiMicForm.of(l) ?: l else l
            val s = Shop(setOf(WHOLESALE))
            val ask = s.say(said("Colgate 10 box vithuchu"))
            assertTrue("$m: ${all(ask)}", Regex("(?i)yaarukku|யாருக்கு").containsMatchIn(ask.reply.text))
            assertTrue("$m: no draft to confirm yet", ask.card?.buttons?.none { it.action is KaiAction.ConfirmStock } == true && s.nothingWritten())
            val bill = s.say(said("Murugan Stores"))
            assertTrue("$m: ${all(bill)}", all(bill).contains("Murugan Stores") && all(bill).contains("16,800") && all(bill).contains("credit"))
            assertTrue(s.nothingWritten())
            s.tap { it is KaiAction.ConfirmStock }
            val b = s.inv.bills.single()
            assertTrue("$m: ${s.inv.bills}", !b.purchase && b.credit && b.partyName == "Murugan Stores" && b.qty.compareTo(BigDecimal("480")) == 0)
        }
    }

    @Test fun cashToTheQuestionIsTheCashSaleAndANameNotInTheBooksIsAskedAgain() {
        for (m in listOf(false, true)) {
            fun said(l: String) = if (m) KaiMicForm.of(l) ?: l else l
            val s = Shop(setOf(WHOLESALE))
            s.say(said("Rice 5 bag vithuchu"))
            val draft = s.say(said("cash"))
            assertTrue("$m: ${all(draft)}", all(draft).contains("Cash sale") && draft.card?.buttons?.any { it.action is KaiAction.ConfirmStock } == true)
            s.tap { it is KaiAction.ConfirmStock }
            assertEquals(listOf("Rice"), s.tools.sales)
            // The button does the same.
            val b = Shop(setOf(WHOLESALE))
            b.say(said("Rice 5 bag vithuchu"))
            assertTrue(all(b.tap { it is KaiAction.BuyerCash }).contains("Cash sale"))
            // Someone the books don't have: asked again, nothing written.
            val u = Shop(setOf(WHOLESALE))
            u.say(said("Rice 5 bag vithuchu"))
            val again = u.say("Senthil")
            assertTrue("$m: ${all(again)}", Regex("(?i)customer illa|customer இல்ல|no customer").containsMatchIn(again.reply.text) && u.nothingWritten())
        }
    }

    @Test fun aNamedWholesaleSaleIsACreditBillAndOthersAreAskedAsBefore() {
        for (line in ways("Murugan Stores-ku 10 box Colgate anuppinen")) {
            val s = Shop(setOf(WHOLESALE))
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", all(t).contains("credit") && t.card?.buttons?.any { it.action is KaiAction.ConfirmStock } == true && s.nothingWritten())
            s.tap { it is KaiAction.ConfirmStock }
            assertTrue(s.inv.bills.single().credit)
        }
        // A retailer is asked credit or cash; a retailer's sale with no buyer is a cash sale draft (no "yaarukku?").
        for (line in ways("Murugan Stores-ku 10 box Colgate anuppinen")) assertTrue(line, Regex("(?i)credit-aa, cash-aa|credit-ஆ, cash-ஆ").containsMatchIn(Shop(setOf(RETAIL)).say(line).reply.text))
        for (kinds in listOf(setOf(RETAIL), setOf(RETAIL, WHOLESALE), null)) for (line in ways("Colgate 10 box vithuchu")) {
            val t = Shop(kinds).say(line)
            assertTrue("$kinds $line → ${all(t)}", all(t).contains("Cash sale") && !Regex("(?i)yaarukku|யாருக்கு").containsMatchIn(t.reply.text))
        }
        // Damage is never asked who bought it.
        for (line in ways("Colgate 2 box damage aagiduchu")) {
            val t = Shop(setOf(WHOLESALE)).say(line)
            assertFalse("$line → ${all(t)}", Regex("(?i)yaarukku|யாருக்கு").containsMatchIn(t.reply.text))
        }
    }

    @Test fun aCustomerWhoBoughtItIsASaleToThem() {
        for (kinds in listOf(setOf(WHOLESALE), setOf(RETAIL), null)) for (line in ways("Kumar 2 box Colgate vaanginaan")) {
            val s = Shop(kinds)
            val t = s.say(line)
            assertTrue("$kinds $line → ${all(t)}", Regex("(?i)Kumar-ku Colgate|Kumar-க்கு Colgate|credit-aa, cash-aa|credit-ஆ, cash-ஆ").containsMatchIn(all(t)))
            assertTrue(s.nothingWritten() && s.inv.created.isEmpty())
        }
        val s = Shop(setOf(RETAIL))
        s.say("Kumar 2 box Colgate vaanginaan")
        s.say("credit")
        s.tap { it is KaiAction.ConfirmStock }
        assertTrue(s.inv.bills.single().let { !it.purchase && it.partyName == "Kumar" })
    }

    // ------------------------------------------------------------ reports by type

    @Test fun reportOffersTheTypesReportsAndTheWeekFollowsTheType() {
        for (line in ways("report")) {
            assertTrue(line, Regex("service vaariyaa|service வாரியா").containsMatchIn(Shop(setOf(SERVICE)).say(line).reply.text))
            assertTrue(line, Shop(setOf(WHOLESALE)).say(line).reply.text.contains("overdue"))
            val retail = Shop(setOf(RETAIL)).say(line).reply.text
            assertTrue(line, retail.contains("expiry") && !retail.contains("overdue"))
            assertTrue(line, Shop(null).say(line).reply.text.contains("expiry"))
        }
        for (line in ways("indha vaara report")) {
            assertTrue(line, Shop(setOf(SERVICE)).say(line).reply.text.contains("Daily Sales Note"))
            for (kinds in listOf(setOf(WHOLESALE), setOf(RETAIL))) {
                val t = Shop(kinds).say(line)
                assertTrue("$kinds $line → ${all(t)}", !t.reply.text.contains("Daily Sales Note") && t.reply.text.contains("5,500"))
            }
        }
    }
}
