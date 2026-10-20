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

/**
 * The owner's list of 20 Oct 2026, each typed and as the ta-IN mic writes it; nothing is saved before Confirm.
 * 1. One category at a time: a stock question waiting and a credit / debit line said (or the other way) → "stock-aa, credit-aa,
 *    debit-aa?", then the flow picked; "stock add pannu" never confirms a money draft; "credit add pannu" is never a product.
 * 2. "ABC Traders invoice kudu" → that supplier's PDF.
 * 4. "Onion 5 kg 100 rupees-ku vithuchu" (never 105 kg) … "innaiku total sale evlo" → ₹100 + ₹50 = ₹150; the profit summed.
 * 5. Stock-in of goods that can expire asks the expiry; hardware is never asked.
 * 6. One name on both sides: "Lokesh … vanganum" then "Lokesh ku … kudukanum" → "avare-vaa?" — the same: set off on his record.
 * 7. "செல்வத்துக்கு" is Selvam (or the shop's Selva), "பரத்துக்கு" Bharath, typed "selvathukku" Selvam — never "செல்வத்த்".
 */
class KaiOwnerListTest {
    private val now = LocalDateTime.of(2026, 10, 20, 11, 0)

    private class Party(val id: String, val name: String, val customer: Boolean, var pending: BigDecimal)

    private class Books(val ps: MutableList<Party>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = ps.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "2026-10-25T00:00:00Z") },
            suppliers = ps.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "2026-10-28T00:00:00Z") })
        override suspend fun history(party: PartyFacts): PartyHistory? =
            PartyHistory(party, listOf(PartyHistory.LedgerEntry(party.pending, 0.0, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 9, 25), emptyList())))
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory, val ps: MutableList<Party>) : KaiTools by inv {
        var n = 0
        val saved = mutableListOf<ActionPlan>()
        override suspend fun parties(name: String) = ps.filter { it.name.contains(name, true) }.map { PartyMatch(it.id, it.name, it.customer, null, it.pending) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan =
            ActionPlan("p${n++}", kind, partyName, partyId, amount, mode, balanceBefore = ps.firstOrNull { it.id == partyId }?.pending, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            saved += plan
            val customer = plan.kind == PlanKind.CREDIT_GIVEN || plan.kind == PlanKind.PAYMENT_IN
            val p = ps.firstOrNull { it.id == plan.partyId } ?: Party("n${ps.size}", plan.partyName, customer, BigDecimal.ZERO).also { ps += it }
            p.pending = if (plan.kind == PlanKind.CREDIT_GIVEN || plan.kind == PlanKind.DEBIT_TAKEN) p.pending + plan.amount else p.pending - plan.amount
            return ActionOutcome.Done("TXN-${saved.size}", p.pending)
        }
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private inner class Shop(extra: List<Party> = emptyList()) {
        val ps = (listOf(Party("c1", "Kumar", true, BigDecimal("4000")), Party("s1", "ABC Traders", false, BigDecimal("10000"))) + extra).toMutableList()
        val inv = Inventory().apply {
            parties.clear()
            items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g")
            items += Inventory.Item("p3", "Onion", "KG", BigDecimal("100"), emptyMap(), BigDecimal("20"), BigDecimal("40"), "Vegetables", null)
            items += Inventory.Item("p4", "Tomato", "KG", BigDecimal("50"), emptyMap(), BigDecimal("15"), BigDecimal("40"), "Vegetables", null)
            items += Inventory.Item("p5", "Hammer", "PCS", BigDecimal("10"), emptyMap(), BigDecimal("150"), BigDecimal("200"), "Hardware", null)
        }
        val tools = Tools(inv, ps)
        val kai = KaiAgent(KaiBusinessBrain(Books(ps), today = { now.toLocalDate() }), Books(ps), tools, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore())))
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { last = it }
        fun tap(pick: (KaiAction) -> Boolean): KaiTurn {
            val b = last?.card?.buttons?.firstOrNull { pick(it.action) } ?: error("no such button: ${all(last)}")
            return runBlocking { kai.act(b.action, KaiLang.TANGLISH)!! }.also { last = it }
        }
        fun confirmPlan() = tap { it is KaiAction.ConfirmPlan }
        fun confirmStock() = tap { it is KaiAction.ConfirmStock }
        fun nothingSaved() = tools.saved.isEmpty() && inv.moves.isEmpty()
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    /** The line typed, and as the mic writes it (Tamil script). */
    private fun ways(typed: String, mic: String? = null): List<String> = listOfNotNull(typed, mic ?: KaiMicForm.of(typed)).distinct()

    // ------------------------------------------------------------ 1. one category at a time

    private fun asksCategory(t: KaiTurn) = t.card?.buttons?.count { it.action is KaiAction.PickCategory } == 3 &&
        Regex("""stock-(?:aa|ஆ), credit-(?:aa|ஆ), debit-(?:aa|ஆ)|stock, credit, or debit""").containsMatchIn(t.reply.text)

    @Test fun aStockQuestionWaitingAndACreditLineAsksWhichAndFollowsThePick() {
        for ((first, money) in listOf("Horlicks 10 pcs vandhuchu" to "Kumar ku 500 credit", "Horlicks 10 pcs vandhuchu" to "Kumar 500 kuduthan",
            "ஹார்லிக்ஸ் 10 pcs வந்துச்சு" to "குமாருக்கு 500 கிரெடிட்")) {
            // Credit picked: the credit line's own draft.
            val s = Shop()
            s.say(first)
            val ask = s.say(money)
            assertTrue("$first → $money: ${all(ask)}", asksCategory(ask))
            val credit = s.say(if (money.first() in 'அ'..'ௐ') "கிரெடிட்" else "credit")
            assertTrue("credit picked: ${all(credit)}", credit.card?.buttons?.any { it.action is KaiAction.ConfirmPlan } == true && all(credit).contains("Kumar") && all(credit).contains("₹500"))
            assertTrue(s.nothingSaved())
            // Stock picked: the question that was waiting, and its answer still taken.
            val s2 = Shop()
            s2.say(first); s2.say(money)
            val back = s2.say(if (money.first() in 'அ'..'ௐ') "ஸ்டாக்" else "stock")
            assertTrue("stock picked: ${all(back)}", Regex("""purchase rate""").containsMatchIn(back.reply.text))
            val next = s2.say("30")
            assertTrue("rate answered: ${all(next)}", Regex("""selling rate""").containsMatchIn(next.reply.text))
            assertTrue(s2.nothingSaved())
        }
    }

    @Test fun aMoneyQuestionWaitingAndAStockLineIsNeverReadAsTheAmount() {
        for ((first, stock, pick) in listOf(Triple("Kumar ku credit kudukkanum", "Colgate 2 box vandhuchu", "stock"),
            Triple("குமாருக்கு கிரெடிட் குடுக்கணும்", "கோல்கேட் 2 பாக்ஸ் வந்துச்சு", "ஸ்டாக்"))) {
            val s = Shop()
            s.say(first)
            val ask = s.say(stock)
            assertTrue("$first → $stock: ${all(ask)}", asksCategory(ask) && !ask.reply.text.contains("₹2"))
            val draft = s.say(pick)
            assertNotNull("stock picked: ${all(draft)}", draft.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock })
            // The button does the same as the word.
            val s2 = Shop()
            s2.say(first); s2.say(stock)
            val tapped = s2.tap { it == KaiAction.PickCategory("STOCK") }
            assertNotNull("Stock tapped: ${all(tapped)}", tapped.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock })
            assertTrue(s.nothingSaved() && s2.nothingSaved())
        }
    }

    @Test fun aStockWordNeverConfirmsAMoneyDraft() {
        for ((credit, stock) in listOf("Kumar ku 500 credit" to "stock add pannu", "குமாருக்கு 500 கிரெடிட்" to "ஸ்டாக் ஆட் பண்ணு")) {
            val s = Shop()
            s.say(credit)
            val t = s.say(stock)
            assertTrue("$credit → $stock saved it: ${all(t)}", s.tools.saved.isEmpty())
            assertTrue(all(t), Regex("""save aagala|சேவ் ஆகல""").containsMatchIn(t.reply.text) && Regex("""(?i)stock""").containsMatchIn(t.reply.text))
        }
    }

    @Test fun creditOrDebitAddIsTheMoneyFlowNeverAProduct() {
        for (line in ways("credit add pannu", "கிரெடிட் ஆட் பண்ணு") + ways("debit add pannu", "டெபிட் ஆட் பண்ணு")) {
            val s = Shop()
            s.say("Colgate 2 box vandhuchu"); s.confirmStock()
            val t = s.say(line)
            assertFalse("$line: ${all(t)}", Regex("""(?i)pudhu product|புது product""").containsMatchIn(all(t)))
            assertTrue("$line: ${all(t)}", Regex("""(?i)yaarukku|யாருக்கு""").containsMatchIn(t.reply.text))
        }
    }

    // ------------------------------------------------------------ 2. a supplier's invoice as a PDF

    @Test fun aSuppliersInvoiceByNameIsTheirPdf() {
        for (line in ways("ABC Traders invoice kudu", "ஏபிசி டிரேடர்ஸ் இன்வாய்ஸ் குடு") + ways("ABC Traders bill pdf anuppu")) {
            val t = Shop().say(line)
            assertTrue("$line: ${all(t)}", t.card?.buttons?.any { (it.action as? KaiAction.ShareStatement)?.name == "ABC Traders" } == true)
        }
        // A bill to make, a bill paid: as before.
        assertTrue(Shop().say("bill kudu").card?.buttons?.none { it.action is KaiAction.ShareStatement } == true)
        val paid = Shop().also { it.say("ABC Traders bill kattinen 500") }
        assertTrue(all(paid.last), paid.last?.card?.buttons?.any { it.action is KaiAction.ConfirmPlan } == true)
    }

    // ------------------------------------------------------------ 4. today's stock sales

    @Test fun todaysStockSalesAddUpWithTheAmountSaid() {
        for (mic in listOf(false, true)) {
            val s = Shop()
            val onion = s.say(if (mic) "Onion 5 கிலோ 100 ரூபாயுக்கு வித்துச்சு" else "Onion 5 kg 100 rupees ku vithuchu")
            assertTrue("never 105 kg: ${all(onion)}", all(onion).contains("5 kg") && !all(onion).contains("105") && all(onion).contains("₹100"))
            assertTrue(s.nothingSaved())
            s.confirmStock()
            s.say(if (mic) "Tomato 2 கிலோ ₹50 ku சேல் ஆச்சு" else "Tomato 2 kg ₹50 ku sale aachu"); s.confirmStock()
            val total = s.say(if (mic) "இன்னைக்கு டோட்டல் சேல் எவ்வளவு" else "innaiku total sale evlo")
            assertTrue("[mic=$mic] ${all(total)}", total.reply.text.contains("₹100 + ₹50 = ₹150"))
            assertEquals(0, BigDecimal("95").compareTo(s.inv.items.first { it.id == "p3" }.stock))
        }
    }

    @Test fun todaysStockSalesAtTheSellingPriceWithTheProfitSummed() {
        val s = Shop()
        s.say("Onion 5 kg vithuchu"); s.confirmStock()
        s.say("Tomato 2 kg vithuchu"); s.confirmStock()
        val t = s.say("innaiku labam evlo")
        assertTrue(all(t), t.reply.text.contains("₹100 + ₹50 = ₹150"))
        // Damage is never a sale.
        val d = Shop()
        d.say("Onion 2 kg kettupochu"); d.confirmStock()
        assertFalse(all(d.say("innaiku total sale evlo")), d.last!!.reply.text.contains("stock-la vithadhu"))
    }

    // ------------------------------------------------------------ 5. the expiry asked on stock-in

    @Test fun stockInOfGoodsThatExpireAsksTheExpiry() {
        for ((line, date) in listOf("Onion 10 kg vandhuchu" to "25/12/2026", "Onion 10 கிலோ வந்துச்சு" to "25/12/2026")) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("$line: ${all(t)}", Regex("""(?i)expiry date""").containsMatchIn(t.reply.text))
            val dated = s.say(date)
            assertTrue("$date: ${all(dated)}", all(dated).contains("Expiry: 25/12/2026"))
            assertTrue(s.nothingSaved())
        }
        // Hardware never expires: never asked.
        for (line in ways("Hammer 5 pcs vandhuchu", "Hammer 5 pcs வந்துச்சு")) assertFalse(line, Regex("""(?i)expiry""").containsMatchIn(Shop().say(line).reply.text))
    }

    // ------------------------------------------------------------ 6. one name on both sides

    @Test fun oneNameOnBothSidesIsAskedAndSetOff() {
        for ((credit, debit, same, due) in listOf(
            listOf("Lokesh 2000 vanganum", "Lokesh ku 2000 naan kudukanum", "avare", "due venam"),
            listOf("Lokesh 2000 வாங்கணும்", "Lokesh ku 2000 நான் குடுக்கணும்", "அவரே", "டியூ வேணாம்"))) {
            val s = Shop()
            s.say(credit); s.say(due); s.confirmPlan()
            s.say(debit)
            val ask = s.say(due)
            assertTrue("$debit: ${all(ask)}", ask.card?.buttons?.count { it.action is KaiAction.SameName } == 2 && all(ask).contains("₹0"))
            assertEquals(1, s.tools.saved.size)
            val draft = s.say(same)
            assertTrue("$same: ${all(draft)}", draft.card?.buttons?.any { it.action is KaiAction.ConfirmPlan } == true)
            s.confirmPlan()
            val setOff = s.tools.saved.last()
            assertEquals(PlanKind.PAYMENT_IN, setOff.kind)
            assertEquals(PaymentMode.OTHER, setOff.mode)
            assertEquals(0, BigDecimal.ZERO.compareTo(s.ps.first { it.name == "Lokesh" }.pending))
            assertEquals("one Lokesh only", 1, s.ps.count { it.name == "Lokesh" })
        }
        // Someone else: a new supplier, as before.
        val s = Shop()
        s.say("Lokesh 2000 vanganum"); s.say("due venam"); s.confirmPlan()
        s.say("Lokesh ku 3000 naan kudukanum"); s.say("due venam")
        val other = s.say("vera aal")
        assertTrue(all(other), all(other).contains("Puthu supplier"))
    }

    // ------------------------------------------------------------ 7. names with -த்துக்கு

    @Test fun aNameEndingInMWithItsCaseIsTheName() {
        fun drafted(extra: List<Party>, line: String): String = Shop(extra).say(line).reply.text
        val selvam = listOf(Party("c3", "Selvam", true, BigDecimal("6000")))
        assertTrue(drafted(selvam, "செல்வத்துக்கு 5000 கொடுக்க வேண்டும்").contains("Selvam"))
        assertTrue(drafted(listOf(Party("c3", "Selva", true, BigDecimal("6000"))), "செல்வத்துக்கு 5000 கொடுக்க வேண்டும்").contains("Selva-"))
        assertTrue(drafted(emptyList(), "செல்வத்துக்கு 5000 கொடுக்க வேண்டும்").let { it.contains("செல்வம்") && !it.contains("செல்வத்த்") })
        assertTrue(Shop(listOf(Party("c3", "Bharath", true, BigDecimal("600")))).say("பரத்துக்கு 500 கடன்").let { all(it).contains("Bharath") })
        // Typed the Tamil way, with Selvam in the books: Selvam — as the mic gives it.
        assertTrue(drafted(selvam, "selvathukku 5000 kudukkanum").contains("Selvam"))
        // A name the books don't keep, typed: as typed.
        assertTrue(drafted(emptyList(), "Bharathukku 500 kudukkanum").contains("Bharath"))
        // "முத்துக்கு" stays Muthu.
        assertTrue(drafted(listOf(Party("c4", "Muthu", true, BigDecimal("600"))), "முத்துக்கு 500 கொடுக்க வேண்டும்").contains("Muthu"))
    }
}
