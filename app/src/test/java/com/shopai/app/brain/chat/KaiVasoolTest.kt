package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.KaiUpiNotice
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.UpiCredit
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
 * Kai வசூல் (owner, 23 Oct 2026: "₹1 GPay pannina udane Kai vandhu yaar kanakku-nu kekkanum"). Money in by UPI →
 * - the payer is one customer: that customer's payment-in draft at once (Confirm / Edit / Cancel);
 * - otherwise "whose account?" with the likely customers — a tap drafts it.
 * Principles: 5 (nothing saved before Confirm), 2 (money in is never a debit; a supplier is never guessed),
 * 3 (no name invented), 1 (the same in Tamil and Tanglish — the draft is the owner's own line, typed or by mic).
 */
class KaiVasoolTest {
    private val now = LocalDateTime.of(2026, 10, 23, 20, 0)

    private class Party(val id: String, val name: String, val customer: Boolean, var owes: BigDecimal)

    private class Books(val ps: List<Party>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = ps.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.owes.toDouble(), "2026-10-25T00:00:00Z") },
            suppliers = ps.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.owes.toDouble(), "2026-10-28T00:00:00Z") })
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val ps: List<Party>, inv: Inventory = Inventory().also { it.parties.clear() }) : KaiTools by inv {
        var n = 0
        val saved = mutableListOf<ActionPlan>()
        override suspend fun parties(name: String) = ps.filter { it.name.contains(name, true) || name.contains(it.name, true) }
            .map { PartyMatch(it.id, it.name, it.customer, null, it.owes) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p${n++}", kind, partyName, partyId, amount, mode, balanceBefore = ps.firstOrNull { it.id == partyId }?.owes, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { saved += plan; return ActionOutcome.Done("TXN-${saved.size}", plan.amount) }
    }

    private inner class Shop(vararg extra: Party) {
        val ps = listOf(
            Party("c1", "Kumar", true, BigDecimal("4000")),
            Party("c2", "Selvi", true, BigDecimal("1")),
            Party("c3", "Ramesh", true, BigDecimal("2500")),
            Party("s1", "Sri Balaji Agencies", false, BigDecimal("10000")),
        ) + extra
        val tools = Tools(ps)
        val kai = KaiAgent(KaiBusinessBrain(Books(ps), today = { now.toLocalDate() }), Books(ps), tools, { now })
        fun upi(amount: String, sender: String?, lang: KaiLang = KaiLang.TANGLISH, app: String = "GPay") =
            runBlocking { kai.upiReceived(UpiCredit(BigDecimal(amount), sender, app, 0L), lang) }
        fun act(a: KaiAction, lang: KaiLang = KaiLang.TANGLISH) = runBlocking { kai.act(a, lang)!! }
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ") + " | " +
        t.card?.buttons.orEmpty().joinToString(" | ") { it.label }).replace("\n", " ")

    @Test fun aKnownCustomerIsDraftedAtOnce() {
        val s = Shop()
        val t = s.upi("2000", "Kumar")
        val a = all(t)
        assertTrue(a, a.startsWith("Owner, ₹2,000 GPay-la vandhuchu — Kumar kitta irundhu."))
        assertNotNull(t.plan)
        val plan = t.plan!!
        assertEquals(PlanKind.PAYMENT_IN, plan.kind)
        assertEquals("c1", plan.partyId)
        assertEquals(0, plan.amount.compareTo(BigDecimal("2000")))
        assertEquals(PaymentMode.UPI, plan.mode)
        // Nothing saved before Confirm.
        assertTrue(s.tools.saved.isEmpty())
        s.act(KaiAction.ConfirmPlan(plan.key))
        assertEquals(1, s.tools.saved.size)
        assertEquals(PlanKind.PAYMENT_IN, s.tools.saved.single().kind)
    }

    @Test fun tamilChatGetsTamil() {
        val t = Shop().upi("2000", "Kumar", KaiLang.TAMIL)
        val a = all(t)
        assertTrue(a, a.startsWith("ஓனர், ₹2,000 GPay-ல வந்துச்சு — Kumar-கிட்ட இருந்து."))
        assertTrue(a, a.contains("பணம் வந்தது"))
        assertEquals(PlanKind.PAYMENT_IN, t.plan?.kind)
        assertFalse(a, Regex("""(?i)\b(vandhuchu|irundhu|kanakku|podanum)\b""").containsMatchIn(a))
    }

    @Test fun anUnknownPayerIsAskedWithLikelyCustomers() {
        val s = Shop()
        val t = s.upi("1", "Praba")
        assertEquals(KaiMood.CLARIFY, t.reply.mood)
        assertNull(t.plan)
        val a = all(t)
        assertTrue(a, a.contains("₹1 GPay-la vandhuchu — Praba kitta irundhu.") && a.contains("Yaar kanakku-la podanum?"))
        val labels = t.card!!.buttons.map { it.label }
        // Selvi owes exactly ₹1: first; then the biggest dues.
        assertEquals(listOf("Selvi · baaki ₹1", "Kumar · baaki ₹4,000", "Ramesh · baaki ₹2,500"), labels)
        // Never a supplier.
        assertFalse(a, a.contains("Sri Balaji"))
        assertTrue(s.tools.saved.isEmpty())
        // A tap: that customer's draft, Confirm first.
        val d = s.act(t.card!!.buttons.first().action)
        assertEquals(PlanKind.PAYMENT_IN, d.plan?.kind)
        assertEquals("c2", d.plan?.partyId)
        assertTrue(s.tools.saved.isEmpty())
    }

    @Test fun aTapInTamilStaysTamil() {
        val s = Shop()
        val t = s.upi("1", "Praba", KaiLang.TAMIL)
        assertTrue(all(t), all(t).contains("யார் கணக்குல போடணும்?"))
        assertEquals("Selvi · பாக்கி ₹1", t.card!!.buttons.first().label)
        val d = s.act(t.card!!.buttons.first().action, KaiLang.TAMIL)
        assertEquals("c2", d.plan?.partyId)
        assertTrue(all(d), all(d).contains("பணம் வந்தது"))
    }

    @Test fun aNameLikeACustomerIsAskedNotGuessed() {
        val s = Shop(Party("c4", "Kumar Raja", true, BigDecimal("700")))
        val t = s.upi("2000", "Kumar Selvam")
        assertNull("a different full name is never drafted for Kumar", t.plan)
        val labels = t.card!!.buttons.map { it.label }
        assertTrue(labels.toString(), labels.take(2).toSet() == setOf("Kumar · baaki ₹4,000", "Kumar Raja · baaki ₹700"))
    }

    @Test fun noNameIsAsked() {
        val t = Shop().upi("300", null, app = "PhonePe")
        assertNull(t.plan)
        assertTrue(all(t), all(t).contains("₹300 PhonePe-la vandhuchu (anuppinavar per varala)."))
        assertTrue(t.card!!.buttons.isNotEmpty())
    }

    @Test fun moneyFromASupplierIsNeverADebit() {
        val s = Shop()
        val t = s.upi("500", "Sri Balaji Agencies", app = "PhonePe")
        assertNull(t.plan)
        assertFalse(all(t), all(t).contains("Debit"))
        assertTrue(s.tools.saved.isEmpty())
    }

    @Test fun aCustomerInTamilLettersIsTheSamePayer() {
        val s = Shop(Party("c5", "முருகன்", true, BigDecimal("900")))
        val t = s.upi("900", "Murugan")
        assertEquals("c5", t.plan?.partyId)
        assertEquals(PlanKind.PAYMENT_IN, t.plan?.kind)
    }

    @Test fun noCustomersSaysHowToSayIt() {
        val s = object {
            val ps = listOf(Party("s1", "Sri Balaji Agencies", false, BigDecimal("10000")))
            val kai = KaiAgent(KaiBusinessBrain(Books(ps), today = { now.toLocalDate() }), Books(ps), Tools(ps), { now })
        }
        val t = runBlocking { s.kai.upiReceived(UpiCredit(BigDecimal("1"), "Praba", "GPay", 0L), KaiLang.TANGLISH) }
        assertTrue(all(t), all(t).contains("Kanakku-la customer yaarum illa"))
        assertNull(t.plan)
    }

    /** From the phone's notification to Kai's draft: the whole way. */
    @Test fun notificationToDraft() {
        val c = KaiUpiNotice.read("com.google.android.apps.nbu.paisa.user", "₹4,000 received", "Kumar paid you ₹4,000", null, 5L)!!
        val s = Shop()
        val t = runBlocking { s.kai.upiReceived(c, KaiLang.TANGLISH) }
        assertEquals("c1", t.plan?.partyId)
        assertEquals(0, t.plan!!.amount.compareTo(BigDecimal("4000")))
        assertEquals(PaymentMode.UPI, t.plan!!.mode)
    }
}
