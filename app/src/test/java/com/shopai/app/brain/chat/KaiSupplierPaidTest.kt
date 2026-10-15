package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.books.model.PaymentMode
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
 * The owner's phone (15 Oct 2026): "na yar yaruku tharanum" listed SRI BALAJI ₹786 due today, then "sri balaji ku 786
 * kuduthutan" was drafted as a new ₹786 Debit — "neenga avarukku tharanum", balance 786 → 1,572. "-ku" says the money went TO
 * Sri Balaji: the owner paid, so it is a payment out and the ₹786 owed comes down to nothing. Typed and as the mic writes it;
 * nothing is saved before Confirm.
 */
class KaiSupplierPaidTest {
    private val now = LocalDateTime.of(2026, 10, 15, 16, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = emptyList(),
            suppliers = listOf(
                PartySummary("s1", "SRI BALAJI", null, 786.0, "2026-10-15T00:00:00Z"),
                PartySummary("s2", "SRIBALAJIHARDWARES 6", null, 500.0, "2026-10-16T00:00:00Z"),
            ),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory) : KaiTools by inv {
        val prepared = mutableListOf<ActionPlan>()
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            inv.prepare(kind, partyName, partyId, amount, mode, said).also { prepared += it }
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private fun kai(): Pair<KaiAgent, Tools> {
        val inv = Inventory().also {
            it.parties.clear()
            it.parties += PartyMatch("s1", "SRI BALAJI", false, null, BigDecimal("786"))
            it.parties += PartyMatch("s2", "SRIBALAJIHARDWARES 6", false, null, BigDecimal("500"))
        }
        val tools = Tools(inv)
        return KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore()))) to tools
    }

    private fun KaiTurn.actions() = card?.buttons.orEmpty().map { it.action }

    @Test fun payingTheSupplierBringsTheBalanceDown() {
        val ways = listOf(
            listOf("na yar yaruku tharanum", "sri balaji ku 786 kuduthutan"),
            listOf("sri balaji ku 786 kuduthuttan"),
            listOf("naan sri balaji ku 786 kuduthuten"),
            listOf("நா யார் யாருக்கு தரணும்", "ஸ்ரீ பாலாஜிக்கு 786 குடுத்துட்டான்"),
        )
        for (lines in ways) {
            val (kai, tools) = kai()
            val t = runBlocking { lines.map { kai.ask(it) }.last() }
            val said = lines.joinToString(" → ")
            val plan = tools.prepared.lastOrNull()
            assertEquals("$said: ${t.reply.text} ${t.card?.lines}", PlanKind.PAYMENT_OUT, plan?.kind)
            assertEquals(said, "SRI BALAJI", plan!!.partyName)
            assertEquals(said, 0, BigDecimal("786").compareTo(plan.amount))
            assertFalse("$said: ${t.card?.lines}", t.card?.lines.orEmpty().any { it.contains("1,572") })
            assertTrue(said, t.actions().any { it is KaiAction.ConfirmPlan })
            assertTrue("$said: saved before Confirm", tools.inv.payments.isEmpty())
        }
    }
}
