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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Owner, 21 Oct 2026: "Ammu anna ku 2000 tharanum" was read as Ammu — "Ammu illa Ammu anna" must go on with Ammu anna; the same
 * "X illa Y" for stock, reports, profit and every feature but reminders. And when Kai doesn't understand: it asks (with buttons for
 * the likely meanings) — never a report the owner didn't ask for. Every line typed and as the mic writes it; nothing saved before
 * Confirm.
 */
class KaiCorrectionReplayTest {
    private val now = LocalDateTime.of(2026, 10, 21, 11, 0)
    private val today = now.toLocalDate()

    private class P(val id: String, val name: String, val customer: Boolean, var pending: BigDecimal)

    private inner class Books(val ps: List<P>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = ps.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "${today.minusDays(3)}T00:00:00Z") },
            suppliers = ps.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), "${today.minusDays(3)}T00:00:00Z") })
        override suspend fun history(party: PartyFacts): PartyHistory? = ps.firstOrNull { it.id == party.id }?.let { p ->
            PartyHistory(party, listOf(PartyHistory.LedgerEntry(p.pending.toDouble() + 500, 500.0, today.minusDays(3), today.minusDays(20),
                listOf(PartyHistory.Payment(500.0, today.minusDays(5))))))
        }
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private inner class Tools(val inv: Inventory, val ps: List<P>) : KaiTools by inv {
        var n = 0
        val prepared = mutableListOf<ActionPlan>()
        val saved = mutableListOf<ActionPlan>()
        val noted = mutableListOf<Pair<KaiUnderstood, String>>()
        override suspend fun parties(name: String) = ps.filter { it.name.contains(name, true) }.map { PartyMatch(it.id, it.name, it.customer, null, it.pending) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan =
            ActionPlan("p${n++}", kind, partyName, partyId, amount, mode, balanceBefore = ps.firstOrNull { it.id == partyId }?.pending, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { saved += plan; return ActionOutcome.Done("TXN-${saved.size}", BigDecimal.ZERO) }
        override fun zone() = "Asia/Kolkata"
        override fun noteUnderstanding(outcome: KaiUnderstood, said: String) { noted += outcome to said }
        override fun understandingCounts() = KaiUnderstandingCounts(KaiUnderstood.entries.associateWith { o -> noted.count { it.first == o } },
            noted.filter { it.first != KaiUnderstood.UNDERSTOOD }.map { it.second })
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("b-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private inner class Shop {
        val ps = listOf(P("c1", "Kumar", true, BigDecimal("1000")), P("c2", "Ramesh", true, BigDecimal("1250")))
        val inv = Inventory().apply {
            parties.clear()
            items += Inventory.Item("i1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g", BigDecimal("50"))
            items += Inventory.Item("i2", "Rice", "KG", BigDecimal("250"), mapOf("BAG" to BigDecimal("25")), BigDecimal("56"), BigDecimal("65"), "Grocery", null, BigDecimal("50"))
        }
        val tools = Tools(inv, ps)
        val books = Books(ps)
        val kai = KaiAgent(KaiBusinessBrain(books, today = { today }), books, tools, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore())))
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { last = it }
        fun tap(label: String): KaiTurn {
            val b = last?.card?.buttons?.firstOrNull { it.label.contains(label, true) } ?: error("no '$label' in ${all(last)}")
            return runBlocking { kai.act(b.action, KaiLang.TANGLISH)!! }.also { last = it }
        }
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ") + " | " +
        t?.card?.buttons.orEmpty().joinToString { it.label }).replace("\n", " ")

    /** [typed] and, when the mic writes it otherwise, as the mic writes it. */
    private fun bothWays(vararg typed: String): List<List<String>> = listOf(typed.toList()) +
        listOfNotNull(typed.map { KaiMicForm.of(it) ?: it }.takeIf { it != typed.toList() })

    @Test fun aNameCorrectedGoesOnWithTheOneMeant() {
        // A stated payment waiting for its due date.
        for ((first, fix) in bothWays("Ammu anna ku 2000 tharanum", "Ammu illa Ammu anna").map { it[0] to it[1] } + listOf("Ammu anna ku 2000 tharanum" to "illa Ammu anna")) {
            val s = Shop()
            s.say(first)
            val t = s.say(fix)
            assertTrue("$first / $fix → ${all(t)}", all(t).contains("Ammu Anna") && all(t).contains("2,000"))
            assertTrue(s.tools.saved.isEmpty())
        }
        // An open draft: the same entry for the person meant.
        val s = Shop()
        s.say("Kumar 2000 kuduthan")
        val t = s.say("Kumar illa Ramesh")
        assertTrue(all(t), all(t).contains("Ramesh") && t.card?.buttons?.any { it.action is KaiAction.ConfirmPlan } == true)
        assertEquals(PlanKind.PAYMENT_IN, s.tools.prepared.last().kind)
        assertEquals("Ramesh", s.tools.prepared.last().partyName)
        assertTrue(s.tools.saved.isEmpty())
    }

    @Test fun everyFeatureButRemindersTakesTheCorrection() {
        for ((first, fix, want) in listOf(
            Triple("Colgate stock evlo", "Colgate illa Rice", "Rice stock"),
            Triple("Colgate stock evlo", "illa Rice", "Rice stock"),
            Triple("Colgate profit evlo", "Colgate illa Rice", "Rice oru kg"),
            Triple("Kumar evlo tharanum", "Kumar illa Ramesh", "₹1,250"),
            Triple("Kumar statement PDF kudu", "Kumar illa Ramesh", "Ramesh"),
        )) {
            val s = Shop()
            s.say(first)
            val t = s.say(fix)
            assertTrue("$first / $fix → ${all(t)}", all(t).contains("illa — ") && all(t).contains(want))
        }
        // "Rice profit evlo" right after Colgate's: Rice — never Colgate's again.
        val s = Shop()
        s.say("Colgate profit evlo")
        assertTrue(all(s.say("Rice profit evlo")).contains("Rice oru kg"))
    }

    @Test fun anAnswerIsNeverTakenForAName() {
        // A word not a name ("naalaiku", "sari", "venam") after "illa" is the answer it is, never a correction.
        for (answer in listOf("illa naalaiku", "illa sari", "illa venam")) {
            val s = Shop()
            s.say("Ammu anna ku 2000 tharanum")
            val t = s.say(answer)
            assertFalse("$answer → ${all(t)}", all(t).contains("illa — "))
        }
        // A reminder is not replayed by "X illa Y".
        val s = Shop()
        s.say("naalaiku 10 manikku Kumar ku call panna remind pannu")
        assertFalse(all(s.say("Kumar illa Ramesh")).contains("Kumar illa — Ramesh"))
    }

    @Test fun notUnderstoodIsAskedWithButtonsNeverAReport() {
        for (line in listOf("Kumar blah", "Kumar 9876543210 credit", "குமார் ப்ளா")) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", t.reply.mood == com.shopai.app.brain.KaiMood.CLARIFY && t.card?.buttons?.any { it.action is KaiAction.Say } == true)
            assertFalse("$line → ${all(t)}", t.reply.text.contains("₹1,000"))
        }
        // The likely meanings as buttons — the tap says the line for the owner, still nothing saved before Confirm.
        val s = Shop()
        assertTrue(all(s.say("Kumar 2000")), s.last!!.card!!.buttons.size == 3)
        val credit = s.tap("Kadan")
        assertTrue(all(credit), all(credit).contains("2,000") && s.tools.saved.isEmpty())
        val stock = Shop()
        stock.say("Colgate 5")
        assertTrue(all(stock.last), stock.last!!.card!!.buttons.any { it.label.contains("Vandhuchu") })
        for (menu in listOf("report", "stock", "blah blah blah")) {
            val t = Shop().say(menu)
            assertTrue("$menu → ${all(t)}", t.card?.buttons?.any { it.action is KaiAction.Say } == true || t.reply.text.contains("`"))
        }
    }

    @Test fun kaiCountsHowWellItUnderstood() {
        val s = Shop()
        s.say("Kumar evlo tharanum")
        s.say("Kumar blah")
        s.say("Colgate stock evlo")
        s.say("Colgate illa Rice")
        val t = s.say("Kai evlo purinjuchu")
        assertTrue(all(t), all(t).contains("%") && all(t).contains("Kumar blah"))
        assertTrue(s.tools.noted.any { it.first == KaiUnderstood.CORRECTED && it.second.contains("Colgate") })
    }
}
