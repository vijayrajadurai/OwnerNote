package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiMeaning
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemorySource
import com.shopai.app.books.model.PaymentMode
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
 * The owner's phone (15 Oct 2026): with "store" learned as a payment-out word, "முருகன் ஸ்டோருக்கு பத்தாயிரம் நான் தரணும்" got
 * "Kuduthen-க்கு ₹10,000 pay பண்ணணும்" — the learned word replaced the shop's own name. A word with "-ku" / "kitta" on it is
 * the person it is TO (principle: memory, context): a learned action word never stands in for it. Typed and as the mic writes it;
 * the learned word still works where it is the action, and nothing is saved before Confirm.
 */
class KaiLearnedWordNameTest {
    private val now = LocalDateTime.of(2026, 10, 15, 20, 4)

    private class Books(val suppliers: List<String>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Ramesh", null, 500.0, "2026-10-20T00:00:00Z")),
            suppliers = suppliers.mapIndexed { i, n -> PartySummary("s$i", n, null, 4000.0, "2026-10-20T00:00:00Z") },
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
        override suspend fun current() = memory
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    /** Kai with [learned] words saved as payment out (the owner confirmed them earlier), and [suppliers] in the books. */
    private fun kai(learned: List<String>, suppliers: List<String>): Pair<KaiAgent, Tools> {
        val memory = KaiPrivateMemory(InMemoryKaiMemoryStore())
        runBlocking {
            memory.open("shop-1", "owner-1")
            learned.forEach { memory.teachMeaning(it, KaiMeaning.PAYMENT_OUT, MemorySource.OWNER_CONFIRMED) }
        }
        val inv = Inventory().also { inv ->
            inv.parties.clear()
            inv.parties += PartyMatch("c1", "Ramesh", true, null, BigDecimal("500"))
            suppliers.forEachIndexed { i, n -> inv.parties += PartyMatch("s$i", n, false, null, BigDecimal("4000")) }
        }
        val tools = Tools(inv)
        return KaiAgent(KaiBusinessBrain(Books(suppliers)), Books(suppliers), tools, { now }, memory = Access(memory)) to tools
    }

    private val lines = listOf(
        "முருகன் ஸ்டோருக்கு பத்தாயிரம் நான் தரணும்", // the mic, as on the owner's phone
        "murugan store ku 10000 na tharanum",
        "Murugan Store-ku 10000 naan tharanum",
        "murugan store kitta 10000 tharanum",
    )

    @Test fun aLearnedWordNeverReplacesTheNameItIsSaidTo() {
        val fails = mutableListOf<String>()
        for (suppliers in listOf(emptyList(), listOf("Murugan Stores"))) for (line in lines) {
            val (kai, tools) = kai(listOf("ஸ்டோர்", "store"), suppliers)
            val reply = runBlocking { kai.ask(line) }.reply.text
            val said = "[books: $suppliers] $line → $reply"
            if (!Regex("(?i)murugan|முருகன்").containsMatchIn(reply)) fails += "$said: the supplier's name is gone"
            if (Regex("(?i)kuduthen|vanginen").containsMatchIn(reply)) fails += "$said: a learned word became the name"
            if (!reply.contains("10,000")) fails += "$said: the amount is gone"
            if (tools.inv.payments.isNotEmpty()) fails += "$said: saved before Confirm"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun theLearnedWordStillWorksWhereItIsTheAction() {
        for (line in listOf("Murugan Stores ku 2000 pottachu", "முருகன் ஸ்டோர்ஸ்க்கு 2000 பொட்டாச்சு")) {
            val (kai, tools) = kai(listOf("pottachu", "பொட்டாச்சு"), listOf("Murugan Stores"))
            val t = runBlocking { kai.ask(line) }
            val plan = tools.prepared.lastOrNull()
            assertEquals("$line: ${t.reply.text}", PlanKind.PAYMENT_OUT, plan?.kind)
            assertEquals(line, "Murugan Stores", plan!!.partyName)
            assertEquals(line, 0, BigDecimal("2000").compareTo(plan.amount))
            assertTrue("$line: saved before Confirm", tools.inv.payments.isEmpty())
        }
    }

    @Test fun replaceActionLeavesTheNameAndChangesTheAction() {
        assertEquals("Murugan store-ku 500 kuduthen", KaiPrivateMemory.replaceAction("Murugan store-ku 500 store", "store", "kuduthen"))
        assertEquals(null, KaiPrivateMemory.replaceAction("Murugan store ku 500 tharanum", "store", "kuduthen"))
        assertEquals(null, KaiPrivateMemory.replaceAction("முருகன் ஸ்டோர்-ku 10000 naan tharanum", "ஸ்டோர்", "kuduthen"))
        assertEquals(null, KaiPrivateMemory.replaceAction("Murugan store kitta 500 vanginen", "store", "vanginen"))
        assertFalse(KaiPrivateMemory.replaceAction("Kumar ku 500 thooki kudu", "thooki kudu", "kuduthen").orEmpty().contains("thooki"))
    }
}
