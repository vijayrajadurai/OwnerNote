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
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.ProductSalesFact
import com.shopai.app.data.model.BusinessKind
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
 * The owner's 1600-line training check (21 Oct 2026, "OwnerNote Kai 1600 Tamil Checked Training Examples"), typed and as the
 * ta-IN mic writes it: the lines Kai read as something else before — a service sale said in full, "Kai, …" said to Kai, "confirm
 * illa stop" (a cancel, never a save), a stock removed, a physical count (never a sale), a pack total asked, a balance worked out,
 * a part payment, a product's sales for a span, a month's report, a span's expenses, a missing piece asked, one person's PDF asked
 * before it goes to another, whether a send / a reminder went through. Nothing is written before Confirm (principles 3 and 5).
 */
class KaiTrainingLinesTest {
    private val now = LocalDateTime.of(2026, 10, 21, 11, 0)

    private class Party(val id: String, val name: String, val customer: Boolean, var pending: BigDecimal)

    private inner class Books(val ps: List<Party>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = ps.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "2026-10-18T00:00:00Z") },
            suppliers = ps.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "2026-10-28T00:00:00Z") })
        override suspend fun history(party: PartyFacts): PartyHistory? = ps.firstOrNull { it.id == party.id }?.let { p ->
            PartyHistory(party, listOf(PartyHistory.LedgerEntry(p.pending.toDouble() + 500, 500.0, now.toLocalDate().minusDays(3), now.toLocalDate().minusDays(20),
                listOf(PartyHistory.Payment(500.0, now.toLocalDate().minusDays(5))))))
        }
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory, val ps: List<Party>, val kinds: Set<BusinessKind>?) : KaiTools by inv {
        var n = 0
        val saved = mutableListOf<ActionPlan>()
        val cash = mutableListOf<Pair<BigDecimal, String>>()
        val sales = mutableListOf<String>()
        override suspend fun parties(name: String) = ps.filter { it.name.contains(name, true) }.map { PartyMatch(it.id, it.name, it.customer, null, it.pending) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p${n++}", kind, partyName, partyId, amount, mode, balanceBefore = ps.firstOrNull { it.id == partyId }?.pending, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            saved += plan
            val p = ps.firstOrNull { it.id == plan.partyId }
            if (p != null) p.pending = if (plan.kind == PlanKind.CREDIT_GIVEN || plan.kind == PlanKind.DEBIT_TAKEN) p.pending + plan.amount else p.pending - plan.amount
            return ActionOutcome.Done("TXN-${saved.size}", p?.pending ?: BigDecimal.ZERO)
        }
        override suspend fun businessKinds() = kinds
        override suspend fun businessCategory() = "OTHER"
        override suspend fun cashOpening(day: LocalDate) = BigDecimal("500")
        override suspend fun addCashSale(day: LocalDate, amount: BigDecimal, upi: Boolean, note: String): ActionOutcome { cash += amount to note; return ActionOutcome.Done("c", amount) }
        override suspend fun cashSale(product: ProductRef, qty: BigDecimal, amount: BigDecimal?, said: String): ActionOutcome? { sales += product.name; return ActionOutcome.Done("INV", BigDecimal.ZERO) }
        override suspend fun topProducts(from: LocalDate, to: LocalDate, limit: Int) =
            if (from.monthValue == 9) listOf(ProductSalesFact("Soap", BigDecimal("40"), BigDecimal("1600"))) else emptyList()
        override suspend fun sales(from: LocalDate, to: LocalDate) = if (from.monthValue == 8) BigDecimal("52000") else null
        override suspend fun expenses(from: LocalDate, to: LocalDate) = if (from == LocalDate.of(2026, 10, 15)) BigDecimal("3400") else null
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("b-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private inner class Shop(kinds: Set<BusinessKind>? = setOf(BusinessKind.SERVICE, BusinessKind.RETAIL)) {
        val ps = listOf(Party("c1", "Kumar", true, BigDecimal("1000")), Party("c2", "Selvam", true, BigDecimal("4000")), Party("c3", "Ramesh", true, BigDecimal("700")),
            Party("s1", "Meena", false, BigDecimal("900")))
        val inv = Inventory().apply {
            parties.clear()
            items += Inventory.Item("i1", "Rice", "KG", BigDecimal("250"), mapOf("BAG" to BigDecimal("25")), BigDecimal("56"), BigDecimal("65"), "Grocery", null, BigDecimal("50"))
            items += Inventory.Item("i2", "Soap", "PCS", BigDecimal("40"), mapOf("BOX" to BigDecimal("12")), BigDecimal("20"), BigDecimal("30"), "FMCG", null, BigDecimal("60"))
        }
        val tools = Tools(inv, ps, kinds)
        val books = Books(ps)
        val kai = KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }), books, tools, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore())))
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { last = it }
        fun tap(pick: (KaiAction) -> Boolean): KaiTurn? {
            val b = last?.card?.buttons?.firstOrNull { pick(it.action) } ?: return null
            return runBlocking { kai.act(b.action, KaiLang.TANGLISH) }.also { last = it }
        }
        fun nothingWritten() = tools.saved.isEmpty() && tools.cash.isEmpty() && tools.sales.isEmpty() && inv.moves.isEmpty() && inv.bills.isEmpty()
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")
    private fun ways(typed: String): List<String> = listOfNotNull(typed, KaiMicForm.of(typed)).distinct()

    @Test fun aServiceSaleSaidInFullIsADraftWithItsAmount() {
        val lines = mapOf("blouse stitching service ku 250 vaanginom" to "250", "innaikku plumbing work service sale 7500 note pannu" to "7,500",
            "hair colouring service sale 5000 record pannu" to "5,000", "laptop service service sale 250 record pannu" to "250", "alteration ku 120 vanginom" to "120")
        for ((typed, amount) in lines) for (line in ways(typed)) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", t.card?.buttons?.any { it.action is KaiAction.ConfirmSale } == true && all(t).contains("₹$amount"))
            assertTrue(line, s.nothingWritten())
            s.tap { it is KaiAction.ConfirmSale }
            assertEquals(line, 1, s.tools.cash.size)
        }
        // Without the word "service", only money that came in, at a business that picked Service: "petrol ku 500 podu" is an expense.
        for (line in ways("petrol ku 500 podu")) assertTrue(line, Shop().say(line).card?.buttons?.none { it.action is KaiAction.ConfirmSale } != false)
        for (line in ways("alteration ku 120 vanginom")) assertTrue(line, Shop(setOf(BusinessKind.RETAIL)).say(line).card?.buttons?.none { it.action is KaiAction.ConfirmSale } != false)
    }

    @Test fun confirmIllaStopCancelsAndKaiByNameIsTheLineThatFollows() {
        for (stop in listOf("Kai, confirm illa stop", "கை, confirm இல்ல stop", "save pannadhe", "add panna vendam")) {
            val s = Shop()
            s.say("Kumar 3000 kuduthan")
            s.say(stop)
            assertTrue("$stop: ${s.tools.saved}", s.tools.saved.isEmpty())
        }
        for (line in ways("Kai, Kumar balance evlo?")) assertTrue(line, all(Shop().say(line)).contains("1,000"))
    }

    @Test fun aStockRemovedIsAStockOutAndAPhysicalCountIsNeverASale() {
        for (line in ways("Rice stock la 2 bag remove pannu")) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", t.card?.buttons?.any { it.action is KaiAction.ConfirmStock } == true)
            assertTrue(line, s.nothingWritten())
        }
        for (line in ways("Rice stock count 6 bag nu set pannu")) {
            val s = Shop()
            assertTrue(line, all(s.say(line)).contains("150"))
            assertFalse(all(s.last), all(s.last).contains("Cash sale"))
            s.tap { it is KaiAction.ConfirmStock }
            assertTrue("$line: ${s.tools.sales}", s.tools.sales.isEmpty() && s.inv.moves.size == 1)
        }
    }

    @Test fun questionsAreWorkedOutAndNothingIsWritten() {
        val asks = mapOf(
            "Soap 11 box iruku; oru box la 12 pieces; total evlo?" to "132",
            "Kumar total 3000; 1800 kuduthachu; balance sollu" to "1,200",
            "Soap low stock ah iruka?" to "60",
            "Soap last month sales evlo?" to "1,600",
            "August sales report kudu" to "52,000",
            "July report illa August report venum" to "52,000",
            "last 7 days expenses PDF kudu" to "3,400",
            "kadandha 7 naal selavu evlo" to "3,400",
        )
        for ((typed, want) in asks) for (line in ways(typed)) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", all(t).contains(want))
            assertTrue("$line wrote something", s.nothingWritten() && t.card?.buttons?.none { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan } != false)
        }
        // A low-stock answer says yes or no, in the owner's language.
        assertTrue(Shop().say("Soap low stock ah iruka?").reply.text.contains("Aamaa"))
        assertTrue(Shop().say("Rice low stock ah iruka?").reply.text.contains("Illa"))
    }

    @Test fun aPartPaymentOfABillIsThatPaymentAndAMissingPieceIsAsked() {
        for (line in ways("Selvam bill 3500; 450 mattum kuduthanga")) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", all(t).contains("3,050") && t.card?.buttons?.any { it.action is KaiAction.ConfirmPlan } == true)
            s.tap { it is KaiAction.ConfirmPlan }
            assertTrue(s.tools.saved.single().let { it.kind == PlanKind.PAYMENT_IN && it.amount.compareTo(BigDecimal("450")) == 0 })
        }
        for (typed in listOf("5000 entry pannu", "Selvam ku payment pannidu", "Kai, 5 box add pannu", "Kumar-ku report send pannu", "customer name rendu per irukanga")) for (line in ways(typed)) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", t.reply.mood == com.shopai.app.brain.KaiMood.CLARIFY && s.nothingWritten())
            assertFalse(line, Regex("edha pathi sollureenga|எதைப் பத்தி").containsMatchIn(t.reply.text))
        }
        // "Meena ku 250 pending iruku" with Meena a supplier: the owner pays Meena — never a collection from her.
        for (line in ways("Meena ku 250 pending iruku")) assertTrue(line, Regex("pay pannanum|pay பண்ணணும்").containsMatchIn(Shop().say(line).reply.text))
        // "kitta irunthu 50 vaangitten" is money in.
        for (line in ways("Selvam kitta irunthu 50 vaangitten")) {
            val s = Shop()
            s.say(line)
            s.tap { it is KaiAction.ConfirmPlan }
            assertEquals(line, PlanKind.PAYMENT_IN, s.tools.saved.singleOrNull()?.kind)
        }
    }

    @Test fun onePersonsPdfIsAskedBeforeItGoesToAnotherAndSendStatusIsSaidPlainly() {
        for (line in ways("illa Ramesh-ku anuppu")) {
            val s = Shop()
            s.say("Kumar credit history PDF kudu")
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", t.reply.text.contains("Kumar") && t.reply.text.contains("Ramesh") &&
                t.card?.buttons?.any { it.action is KaiAction.StatementFor } == true)
        }
        for (line in ways("send aagala status check pannu")) assertTrue(line, Shop().say(line).reply.text.contains("WhatsApp"))
        for (line in ways("reminder save aagala pola")) assertTrue(line, Regex("(?i)reminder").containsMatchIn(Shop().say(line).reply.text))
    }
}
