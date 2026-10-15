package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner's phone (15 Oct 2026): "pulungal arisi 10bgs" → Kai asked "edha pathi sollureenga?" → "stock" → "vandhuchu" →
 * "Evlo amount Owner?" — the first line was forgotten, and "vandhuchu" read as money. The line Kai could not place is kept:
 * once the owner says it is stock and which way it went, it is that line — "pulungal arisi 10 bags vandhuchu", a stock-in
 * draft of 250 kg (bag = 25 kg) — saved only on Confirm. "10bgs" is 10 bags. Typed and as the mic writes it.
 */
class KaiGuidedLineTest {
    private val now = LocalDateTime.of(2026, 10, 15, 16, 20)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private fun kai(): Pair<KaiAgent, Inventory> {
        val inv = Inventory().also {
            it.items += Inventory.Item("p1", "Pulungal Arisi", "KG", BigDecimal("300"), mapOf("BAG" to BigDecimal("25")), null, null, "Grocery", null)
            it.items += Inventory.Item("p2", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), null, null, "FMCG", null)
        }
        return KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore()))) to inv
    }

    private fun play(lines: List<String>, wantStock: String) {
        val (kai, inv) = kai()
        val said = lines.joinToString(" → ")
        val t = runBlocking { lines.map { kai.ask(it) }.last() }
        assertFalse("$said: ${t.reply.text}", Regex("""(?i)amount|தொகை""").containsMatchIn(t.reply.text) && t.card == null)
        assertTrue("$said: nothing is saved before Confirm", inv.moves.isEmpty())
        val confirm = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock }
        assertNotNull("$said: no stock draft — ${t.reply.text} ${t.card?.lines}", confirm)
        runBlocking { kai.act(confirm!!.action, KaiLang.TANGLISH) }
        assertEquals(said, 0, BigDecimal(wantStock).compareTo(inv.items.first { it.id == "p1" }.stock))
    }

    @Test fun theLineKaiAskedAboutIsTheStockLine() {
        play(listOf("pulungal arisi 10bgs", "stock", "vandhuchu"), "550")
        play(listOf("pulungal arisi 10bgs", "stock", "vithuchu"), "50")
        play(listOf("புழுங்கல் அரிசி 10 பேக்ஸ்", "ஸ்டாக்", "வந்துச்சு"), "550")
        // Kai asked "vandhucha, vithucha?" first: "stock" says yes, stock — the line stays, the next word finishes it.
        play(listOf("pulungal arisi 10 bags", "stock", "vandhuchu"), "550")
        play(listOf("pulungal arisi 10 bags", "stock ah", "vithuchu"), "50")
        play(listOf("புழுங்கல் அரிசி 10 பேக்ஸ்", "ஸ்டாக்", "வந்துச்சு"), "550")
    }

    @Test fun aOneWordAnswerKeepsTheOwnersLanguage() {
        val (kai, _) = kai()
        runBlocking { kai.ask("pulungal arisi 10 bags") }
        val t = runBlocking { kai.ask("stock") }
        assertTrue(t.reply.text, t.reply.text.contains("vandhucha"))
        val (kai2, _) = kai()
        runBlocking { kai2.ask("புழுங்கல் அரிசி 10 பேக்ஸ்") }
        val t2 = runBlocking { kai2.ask("ஸ்டாக்") }
        assertTrue(t2.reply.text, t2.reply.text.contains("வந்துச்சா"))
    }

    @Test fun aNewLineIsNeverGluedToTheOldOne() {
        // After "stock", a full line of its own (its own number) is read on its own: Colgate 2 box in, the rice untouched.
        val (kai, inv) = kai()
        val t = runBlocking { listOf("pulungal arisi 10bgs", "stock", "Colgate 2 box vandhuchu").map { kai.ask(it) }.last() }
        runBlocking { kai.act(t.card!!.buttons.first { it.action is KaiAction.ConfirmStock }.action, KaiLang.TANGLISH) }
        assertEquals(0, BigDecimal("336").compareTo(inv.items.first { it.id == "p2" }.stock))
        assertEquals(0, BigDecimal("300").compareTo(inv.items.first { it.id == "p1" }.stock))
    }

    @Test fun tenBgsIsTenBags() {
        play(listOf("pulungal arisi 10bgs vandhuchu"), "550")
        play(listOf("pulungal arisi 10 bgs vandhuchu"), "550")
        play(listOf("pulungal arisi 10 bags vandhuchu"), "550")
    }
}
