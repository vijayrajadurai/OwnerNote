package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.MoneyBalanceFact
import com.shopai.app.brain.tools.MoneyKind
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductSalesFact
import com.shopai.app.brain.tools.Repeat
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.brain.tools.StockFact
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** Kai's agent: understands → right tool → draft + confirm for money → verified reply. */
class KaiAgentTest {
    private val now = LocalDateTime.of(2026, 10, 3, 16, 20)

    private class FakeBooks : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Kumar", "9000000001", 8500.0, null)),
            suppliers = listOf(PartySummary("s1", "Ramesh", null, 8000.0, null)),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Records what Kai asked the tools to do. */
    private class FakeTools(val booksReady: Boolean = true) : KaiTools {
        val confirmed = mutableListOf<ActionPlan>()
        val discarded = mutableListOf<ActionPlan>()
        val scheduled = mutableListOf<KaiReminder>()
        val logged = mutableListOf<Pair<String, ActionStatus>>()
        private val parties = listOf(
            PartyMatch("c1", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("8500.00")),
            PartyMatch("s1", "Ramesh", customer = false, phone = null, balance = BigDecimal("8000.00")),
        )

        override suspend fun sales(from: LocalDate, to: LocalDate) = if (booksReady) BigDecimal("12450.00") else null
        override suspend fun moneyBalances() = if (booksReady) listOf(MoneyBalanceFact("Cash in hand", MoneyKind.CASH, BigDecimal("30600.00"))) else null
        override suspend fun stock(product: String?) = if (!booksReady) null else listOf(StockFact("Rice", BigDecimal("45.5"), "KG")).filter { product == null || it.name.equals(product, true) }
        override suspend fun lowStock() = if (booksReady) listOf(StockFact("Sugar", BigDecimal("2"), "KG", BigDecimal("10"))) else null
        override suspend fun topProducts(from: LocalDate, to: LocalDate, limit: Int) = if (booksReady) listOf(ProductSalesFact("Rice", BigDecimal("120"), BigDecimal("6000.00"))) else null
        override suspend fun parties(name: String) = if (!booksReady) null else parties.filter { it.name.contains(name, true) }

        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p-$partyName", kind, partyName, partyId, amount, mode, said = said,
                balanceBefore = BigDecimal("8000.00"), balanceAfter = BigDecimal("8000.00") - amount)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { confirmed += plan; return ActionOutcome.Done("PO-7", plan.balanceAfter) }
        override suspend fun discard(plan: ActionPlan) { discarded += plan }

        override fun schedule(reminder: KaiReminder): ScheduleResult { scheduled += reminder; return ScheduleResult.EXACT }
        override fun reminders() = scheduled.toList()
        override fun cancelReminder(id: String) = scheduled.removeIf { it.id == id }
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?): String { logged += intent to status; return reference ?: "K-1" }
    }

    private fun agent(tools: KaiTools) = KaiAgent(KaiBusinessBrain(FakeBooks(), today = { now.toLocalDate() }, random = Random(1)), FakeBooks(), tools, now = { now })

    @Test
    fun calculatorAnswersAtOnce() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        assertEquals("30", a.ask("10*3").reply.text)
        assertTrue(a.ask("25000 la 18% GST evlo?").reply.text.contains("₹4,500"))
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun paymentIsADraftUntilConfirmed() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.ask("Ramesh ku 5000 kuduthen")
        val plan = assertNotNull(turn.plan).let { turn.plan!! }
        // Ramesh is a supplier: money given to him is a payment out.
        assertEquals(PlanKind.PAYMENT_OUT, plan.kind)
        assertEquals(0, BigDecimal("5000").compareTo(plan.amount))
        assertTrue(tools.confirmed.isEmpty())
        assertTrue(turn.card!!.buttons.any { it.action is KaiAction.ConfirmPlan })
        val done = a.act(KaiAction.ConfirmPlan(plan.key), KaiLang.TANGLISH)!!
        assertEquals(1, tools.confirmed.size)
        assertTrue(done.reply.text.contains("PO-7"))
        // A second tap does nothing (the draft is gone).
        assertNull(a.act(KaiAction.ConfirmPlan(plan.key), KaiLang.TANGLISH))
        assertEquals(1, tools.confirmed.size)
    }

    @Test
    fun cancelSavesNothing() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.ask("Kumar kitta 10000 vanginen")
        assertEquals(PlanKind.PAYMENT_IN, turn.plan!!.kind)
        a.act(KaiAction.CancelPlan(turn.plan!!.key), KaiLang.TANGLISH)
        assertTrue(tools.confirmed.isEmpty())
        assertEquals(1, tools.discarded.size)
    }

    @Test
    fun unknownPersonIsAskedNotGuessed() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.ask("Muthu ku 2000 kuduthen")
        assertNull(turn.plan)
        val choose = turn.card!!.buttons.map { it.action }.filterIsInstance<KaiAction.ChoosePlan>().single()
        assertEquals(PlanKind.CREDIT_GIVEN, choose.kind)
        val drafted = a.act(choose, KaiLang.TANGLISH)!!
        assertEquals(PlanKind.CREDIT_GIVEN, drafted.plan!!.kind)
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun missingAmountIsAskedThenCompleted() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val ask = a.ask("Ramesh ku kuduthen")
        assertNull(ask.plan)
        val turn = a.ask("3000")
        assertEquals(0, BigDecimal("3000").compareTo(turn.plan!!.amount))
    }

    @Test
    fun remindersAreCreatedDirectly() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        a.ask("Kumar ku 10 minutes kalichi call pannanum")
        val r = tools.scheduled.single()
        assertEquals("Kumar", r.callName)
        assertEquals("+919000000001", r.phone)
        assertEquals(now.plusMinutes(10), r.at)
        a.ask("Daily kaalaila 10 maniku kadaiku pogumbothu saaviya marakkama eduthutu po remind pannu")
        assertEquals(Repeat.DAILY, tools.scheduled.last().repeat)
        // No time said: Kai asks, then the next answer completes it.
        a.ask("Supplier payment check panna remind pannu")
        assertEquals(2, tools.scheduled.size)
        a.ask("naalaikku 11 maniku")
        assertEquals(3, tools.scheduled.size)
        assertEquals(LocalDateTime.of(2026, 10, 4, 11, 0), tools.scheduled.last().at)
    }

    @Test
    fun businessAnswersComeFromTheBooksOrSayUnverified() = runBlocking {
        val a = agent(FakeTools())
        a.ask("Enakku evlo cash iruku?").reply.text.let { assertTrue(it, it.contains("₹30,600")) }
        a.ask("Inniku evlo sales?").reply.text.let { assertTrue(it, it.contains("₹12,450")) }
        a.ask("Rice stock evlo?").reply.text.let { assertTrue(it, it.contains("45.5 KG")) }
        a.ask("Which stock is low?").card!!.lines.single().let { assertTrue(it, it.startsWith("Sugar")) }
        a.ask("Indha month highest selling product enna?").reply.text.let { assertTrue(it, it.contains("Rice")) }

        val offline = agent(FakeTools(booksReady = false))
        val r = offline.ask("Enakku evlo cash iruku?").reply.text
        assertTrue(r, r.contains("verify"))
        assertTrue(offline.ask("Ramesh ku 5000 kuduthen").plan == null)
    }

    @Test
    fun callOpensTheDialerNeverClaimsACall() = runBlocking {
        val turn = agent(FakeTools()).ask("Kumar-ku call pannu")
        val dial = turn.card!!.buttons.single().action as KaiAction.Dial
        assertEquals("+919000000001", dial.phone)
        assertTrue(!turn.reply.text.contains("called", ignoreCase = true))
    }
}
