package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner's phone, 21 Oct 2026, typed and as the ta-IN mic writes it:
 * - a new product's open question never takes another line's numbers ("Colgate 2 box", "Kumar 2000 tharanum", "podalanga 10kg");
 * - "aama" / "seri confirm pannu" said to a stock draft confirms it;
 * - "stock" alone asks which; "customer report", "weekly report", "expense report" are those reports;
 * - a stock-out that is a sale goes to the books as a cash sale (damage / wastage never); the draft says so first.
 */
class KaiFlowFixesTest {
    private val now = LocalDateTime.of(2026, 10, 21, 11, 0)

    private class Party(val id: String, val name: String, val customer: Boolean, var pending: BigDecimal)

    private class Books(val ps: MutableList<Party>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = ps.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "2026-10-25T00:00:00Z") },
            suppliers = ps.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "2026-10-28T00:00:00Z") })
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory, val ps: MutableList<Party>, val sells: Boolean) : KaiTools by inv {
        var n = 0
        val saved = mutableListOf<ActionPlan>()
        val sales = mutableListOf<Triple<String, BigDecimal, BigDecimal?>>()
        override suspend fun parties(name: String) = ps.filter { it.name.contains(name, true) }.map { PartyMatch(it.id, it.name, it.customer, null, it.pending) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan =
            ActionPlan("p${n++}", kind, partyName, partyId, amount, mode, balanceBefore = ps.firstOrNull { it.id == partyId }?.pending, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { saved += plan; return ActionOutcome.Done("TXN-${saved.size}", plan.amount) }
        /** The books' cash sale: recorded, and the stock goes down as the books would. */
        override suspend fun cashSale(product: ProductRef, qty: BigDecimal, amount: BigDecimal?, said: String): ActionOutcome? {
            if (!sells) return null
            sales += Triple(product.name, qty, amount)
            val item = inv.item(product.name)
            item.stock -= qty
            return ActionOutcome.Done("INV-${sales.size}", item.stock)
        }
    }

    private inner class Shop(sells: Boolean = true) {
        val ps = mutableListOf(Party("c1", "Kumar", true, BigDecimal("4000")), Party("s1", "ABC Traders", false, BigDecimal("10000")))
        val inv = Inventory().apply {
            parties.clear()
            items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g")
            items += Inventory.Item("p2", "Rice", "KG", BigDecimal("250"), mapOf("BAG" to BigDecimal("25")), BigDecimal("56"), BigDecimal("65"), "Grocery", null)
            items += Inventory.Item("p3", "Onion", "KG", BigDecimal("40"), emptyMap(), BigDecimal("20"), BigDecimal("40"), "Vegetables", null)
            items += Inventory.Item("p4", "Hammer", "PCS", BigDecimal("10"), emptyMap(), BigDecimal("150"), BigDecimal("200"), "Hardware", null)
        }
        val tools = Tools(inv, ps, sells)
        val kai = KaiAgent(KaiBusinessBrain(Books(ps), today = { now.toLocalDate() }), Books(ps), tools, { now })
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { last = it }
        fun tap(pick: (KaiAction) -> Boolean): KaiTurn {
            val b = last?.card?.buttons?.firstOrNull { pick(it.action) } ?: error("no such button: ${all(last)}")
            return runBlocking { kai.act(b.action, KaiLang.TANGLISH)!! }.also { last = it }
        }
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")
    private fun ways(typed: String): List<String> = listOfNotNull(typed, KaiMicForm.of(typed)).distinct()
    private fun mic(typed: String) = KaiMicForm.of(typed) ?: typed

    // ------------------------------------------------------------ 1. the open question takes only its answer

    @Test fun otherStockLinesAreTheirOwnWhileANewProductsQuestionIsOpen() {
        for (m in listOf(false, true)) {
            val s = Shop()
            fun say(l: String) = s.say(if (m) mic(l) else l)
            assertTrue(all(say("Aashirvad atta 10 bag vandhuchu")), Regex("(?i)evlo kg|எத்தனை kg").containsMatchIn(s.last!!.reply.text))
            val colgate = say("Colgate 2 box vandhuchu")
            assertTrue("$m: ${all(colgate)}", all(colgate).contains("96") && colgate.card?.buttons?.any { it.action is KaiAction.ConfirmStock } == true)
            assertTrue("$m: the new product is said put aside: ${all(colgate)}", all(colgate).contains("Aashirvad Atta"))
            val rice = say("Rice 1 bag vithuchu")
            assertTrue("$m: ${all(rice)}", all(rice).contains("25 kg"))
            val kumar = say("Kumar 2000 tharanum add panniko")
            assertTrue("$m: ${all(kumar)}", all(kumar).contains("2,000") && all(kumar).contains("Kumar"))
            assertFalse("$m: never asked the atta's kilos again: ${all(kumar)}", Regex("(?i)evlo kg|எத்தனை kg").containsMatchIn(kumar.reply.text))
            assertTrue("nothing saved: ${s.inv.moves} ${s.inv.created}", s.inv.moves.isEmpty() && s.inv.created.isEmpty())
        }
    }

    @Test fun aQuestionOrAnotherNewProductIsNotTheAnswerEither() {
        for (m in listOf(false, true)) {
            val s = Shop()
            fun say(l: String) = s.say(if (m) mic(l) else l)
            say("Aashirvad atta 10 bag vandhuchu")
            assertTrue(all(say("Rice evlo irukku")), all(s.last).contains("250"))
            val s2 = Shop()
            s2.say(if (m) mic("Aashirvad atta 10 bag vandhuchu") else "Aashirvad atta 10 bag vandhuchu")
            val pod = s2.say(if (m) mic("podalanga 10kg add panniko") else "podalanga 10kg add panniko")
            assertFalse("$m: ${all(pod)}", Regex("(?i)box").containsMatchIn(pod.reply.text))
            assertTrue("$m: ${all(pod)}", all(pod).contains("Podalanga"))
        }
    }

    @Test fun theAnswersStillAnswer() {
        for (m in listOf(false, true)) {
            val s = Shop()
            fun say(l: String) = s.say(if (m) mic(l) else l)
            say("Aashirvad atta 10 bag vandhuchu")
            assertFalse("$m: ${all(s.last)}", say("bag-ku 25 kg").card?.buttons.orEmpty().any { it.action is KaiAction.PickCategory })
            say("purchase 1200"); say("selling 1350")
            assertTrue("$m: ${all(s.last)}", all(s.last).contains("250 kg"))
            say("aama")
            assertEquals(listOf("Aashirvad Atta"), s.inv.created.map { it.name })
        }
    }

    // ------------------------------------------------------------ 2. a yes by voice confirms a stock draft

    @Test fun aYesSaidToAStockDraftConfirmsIt() {
        for (yes in listOf("aama", "ஆமா", "seri confirm pannu", "ok", "save pannu")) {
            val s = Shop()
            s.say("Hammer 2 pcs vandhuchu")
            assertTrue(s.inv.moves.isEmpty())
            val t = s.say(yes)
            assertTrue("$yes: ${all(t)}", s.inv.moves.size == 1 && t.reply.text.contains("✅"))
        }
        // A number said is a correction, never a yes.
        val s = Shop()
        s.say("Hammer 2 pcs vandhuchu")
        s.say("seri 3 pcs")
        assertTrue(s.inv.moves.isEmpty())
    }

    // ------------------------------------------------------------ 3. reports

    @Test fun stockAloneAsksWhichAndTheReportsAreTheOnesAsked() {
        for (line in ways("stock")) {
            val t = Shop().say(line)
            // (low stock / stock value / the list as buttons — owner, 21 Oct 2026 — never the list itself unasked)
            assertTrue("$line: ${all(t)}", Regex("(?i)endha stock|எந்த stock|which stock").containsMatchIn(t.reply.text) &&
                (t.card == null || t.card!!.buttons.all { it.action is KaiAction.Say } && t.card!!.lines.isEmpty()))
        }
        for (line in ways("customer report")) assertTrue(line, all(Shop().say(line)).contains("Kumar"))
        for (line in ways("expense report")) assertFalse(line, all(Shop().say(line)).contains("Daily Cash-la OUT"))
        for (line in ways("weekly report")) {
            val t = Shop().say(line)
            assertTrue("$line: ${all(t)}", Regex("(?i)vaaram|வாரம்|week").containsMatchIn(t.reply.text))
        }
        assertTrue(all(Shop().say("low stock enna")).isNotBlank())
    }

    // ------------------------------------------------------------ 4. a sale goes to the books as a sale

    @Test fun aSaleIsACashSaleInTheBooksAndDamageIsNot() {
        for (m in listOf(false, true)) {
            val s = Shop()
            val d = s.say(if (m) mic("Onion 5 kg vithuchu") else "Onion 5 kg vithuchu")
            assertTrue("the draft says it first: ${all(d)}", all(d).contains("Cash sale") && all(d).contains("200"))
            s.tap { it is KaiAction.ConfirmStock }
            assertEquals(listOf(Triple("Onion", BigDecimal("5"), null as BigDecimal?)), s.tools.sales.map { Triple(it.first, it.second.stripTrailingZeros().let { q -> BigDecimal(q.toPlainString()) }, it.third) })
            assertTrue("no second stock-out: ${s.inv.moves}", s.inv.moves.isEmpty())
        }
        // The money said is the sale's amount.
        val s = Shop()
        s.say("Onion 5 kg 100 rupees ku vithuchu")
        assertTrue(all(s.last), all(s.last).contains("100") && !all(s.last).contains("Cash sale: ₹200"))
        s.tap { it is KaiAction.ConfirmStock }
        assertEquals(0, BigDecimal("100").compareTo(s.tools.sales.single().third))
        // Damage / wastage is a stock-out, never a sale.
        val w = Shop()
        val dmg = w.say("Onion 2 kg azhugiduchu")
        assertFalse(all(dmg), all(dmg).contains("Cash sale"))
        w.tap { it is KaiAction.ConfirmStock }
        assertTrue(w.tools.sales.isEmpty() && w.inv.moves.size == 1)
        // No books to take it as a sale: the stock is taken out as before.
        val old = Shop(sells = false)
        old.say("Onion 5 kg vithuchu")
        old.tap { it is KaiAction.ConfirmStock }
        assertTrue(old.inv.moves.size == 1)
    }
}
