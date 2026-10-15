package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.KaiCommands
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner's phone (15 Oct 2026): "குமார் ஓட்டலுக்கு 2000 தரணும்" with Kumar in the books got "குமார்-க்கு ₹2,000 pay பண்ணணும்" —
 * the hotel dropped, the money put on Kumar. A shop's last word after a name ("Kumar Hotel", "குமார் ஓட்டல்", "Murugan Stores")
 * is part of who it is (principle: context — never merged into the person the name starts with). Typed and as the mic writes it;
 * the whole name in the books is that record; nothing saved before Confirm.
 */
class KaiShopNameTest {
    private val now = LocalDateTime.of(2026, 10, 15, 23, 5)

    private class Books(val customers: List<String>, val suppliers: List<String>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = customers.mapIndexed { i, n -> PartySummary("c$i", n, null, 500.0, "2026-10-20T00:00:00Z") },
            suppliers = suppliers.mapIndexed { i, n -> PartySummary("s$i", n, null, 4000.0, "2026-10-20T00:00:00Z") },
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private fun kai(customers: List<String>, suppliers: List<String> = emptyList()): Pair<KaiAgent, Inventory> {
        val inv = Inventory().also { inv ->
            inv.parties.clear()
            customers.forEachIndexed { i, n -> inv.parties += PartyMatch("c$i", n, true, null, BigDecimal("500")) }
            suppliers.forEachIndexed { i, n -> inv.parties += PartyMatch("s$i", n, false, null, BigDecimal("4000")) }
        }
        val books = Books(customers, suppliers)
        return KaiAgent(KaiBusinessBrain(books), books, inv, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore()))) to inv
    }

    @Test fun theHotelIsPartOfTheName() {
        val fails = mutableListOf<String>()
        for (books in listOf(emptyList(), listOf("Kumar"), listOf("குமார்"))) for (line in listOf("குமார் ஓட்டலுக்கு 2000 தரணும்",
            "Kumar Hotel ku 2000 tharanum", "naan Kumar hotel-ku 2000 tharanum", KaiMicForm.of("naan Kumar hotel ku 2000 tharanum")!!)) {
            val (kai, inv) = kai(books)
            val reply = runBlocking { kai.ask(line) }.reply.text
            val said = "[books $books] «$line» → $reply"
            if (!Regex("(?i)(hotel|ஓட்டல்)-?(ku|க்கு)").containsMatchIn(reply)) fails += "$said: the hotel is not who it is to"
            if (!reply.contains("2,000")) fails += "$said: the amount is gone"
            if (inv.payments.isNotEmpty()) fails += "$said: saved before Confirm"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun theWholeNameInTheBooksIsThatRecord() {
        // "Kumar Hotel" and "Kumar" both kept: the hotel, the one said.
        assertEquals("Kumar Hotel", KaiCommands.personIn("Kumar Hotel ku 2000 tharanum", listOf("Kumar", "Kumar Hotel")))
        assertEquals("Murugan Stores", KaiCommands.personIn("Murugan Stores ku 2000 kuduthen", listOf("Murugan", "Murugan Stores")))
        // Kumar alone, or Kumar with a word that is no shop's: Kumar.
        assertEquals("Kumar", KaiCommands.personIn("Kumar ku 2000 kuduthen", listOf("Kumar")))
        assertEquals("Kumar", KaiCommands.personIn("innaiku Kumar ku 2000 kuduthen", listOf("Kumar")))
        assertEquals("Kumar", KaiCommands.personIn("Kumar ku 2000 hotel bill", listOf("Kumar")))
        // A word after the name that is no shop's last word ("spa") is not taken as a shop.
        assertEquals("Kumar", KaiCommands.personIn("Kumar spa ku 2000 kuduthen", listOf("Kumar")))
    }
}
