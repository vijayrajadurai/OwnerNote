package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.ActionOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The shop's Menu (owner, 16 Oct 2026): "menu la cost and item name-a user add pannum — ethukkuna shop cost different aagum".
 * The owner adds each item and its own price (Cutting 100, Shaving 80, Facial 110) on the Menu screen or in chat with Confirm;
 * Kai sells from it in any shop and never adds an item or a price by itself. Expiry / batch is a medical shop's only. Typed and
 * as the mic writes it; nothing is kept before Confirm.
 */
class KaiMenuTest {
    private val now = LocalDateTime.of(2026, 10, 16, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
        override suspend fun cashNote(day: LocalDate) = CashNoteView(day, 0, com.shopai.app.data.model.DailyCashTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0, 0.0, false)
    }

    private class Tools(val inv: Inventory, val category: String) : com.shopai.app.brain.tools.KaiTools by inv {
        val sales = mutableListOf<String>()
        override suspend fun cashOpening(day: LocalDate) = BigDecimal.ZERO
        override suspend fun addCashSale(day: LocalDate, amount: BigDecimal, upi: Boolean, note: String): ActionOutcome { sales += note; return ActionOutcome.Done("c", amount) }
        override suspend fun businessCategory() = category
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private inner class Shop(category: String) {
        val memory = KaiPrivateMemory(InMemoryKaiMemoryStore())
        val inv = Inventory().also { it.items += Inventory.Item("p1", "Bread", "PACKET", BigDecimal("20"), emptyMap(), null, null, "Grocery", null) }
        val tools = Tools(inv, category)
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { LocalDateTime.of(2026, 10, 16, 10, 0) }, memory = Access(memory))
        var last: KaiTurn? = null
        fun say(line: String) = runBlocking { kai.ask(line) }.also { last = it }
        fun confirm() = runBlocking {
            val b = last?.card?.buttons?.first { it.label.contains("Confirm") || it.label.contains("உறுதி") } ?: error("no Confirm: ${all(last)}")
            kai.act(b.action, KaiLang.TANGLISH)!!
        }.also { last = it }
        fun menu() = runBlocking { memory.open("shop-1", "owner-1"); KaiMenu.items(memory) }
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    @Test fun theMenuLineIsReadTypedAndSpoken() {
        val want = listOf("Cutting" to BigDecimal("100"), "Shaving" to BigDecimal("80"), "Facial" to BigDecimal("110"))
        assertEquals(want, KaiMenu.parse("menu: cutting 100, shaving 80, facial 110"))
        assertEquals(want, KaiMenu.parse("menu la cutting 100 shaving 80 facial 110 add pannu"))
        assertEquals(want, KaiMenu.parse("Menu - Cutting ₹100, Shaving ₹80, Facial ₹110"))
        // The mic writes numbers in words: read as the mic's line with digits.
        val spoken = "மெனு கட்டிங் நூறு ஷேவிங் எண்பது"
        assertEquals(listOf("கட்டிங்" to BigDecimal("100"), "ஷேவிங்" to BigDecimal("80")),
            KaiMenu.parse(spoken, com.shopai.app.brain.tools.KaiSpokenWords.normalize(spoken)))
        // No price, or not a Menu line: nothing.
        assertNull(KaiMenu.parse("menu kaattu"))
        assertNull(KaiMenu.parse("cutting 100"))
        assertNull(KaiMenu.parse("Kumar 500 kuduthan"))
    }

    @Test fun theOwnerAddsTheMenuAndKaiSellsFromItInAnyShop() {
        for (category in listOf("GROCERY", "BEAUTY_SALON", "TEXTILE")) for (mic in listOf(false, true)) {
            val s = Shop(category)
            val line = if (mic) "மெனு கட்டிங் 100 ஷேவிங் 80 ஃபேஷியல் 110" else "menu: cutting 100, shaving 80, facial 110"
            val ask = s.say(line)
            assertTrue("[$category] $line → ${all(ask)}", ask.card?.lines.orEmpty().size == 3 && all(ask).contains("₹100") && all(ask).contains("₹110"))
            assertTrue("[$category] nothing kept before Confirm", s.menu().isEmpty())
            s.confirm()
            assertEquals("[$category] $line", 3, s.menu().size)
            // A sale of a Menu item: straight to the draft at the owner's price, typed or by mic, in a shop of any category.
            for (sale in listOf("cutting onnu", "hair cutting onnu", "கட்டிங் ஒன்னு")) {
                val t = s.say(sale)
                assertTrue("[$category] «$sale» → ${all(t)}", all(t).contains("₹100") && t.card?.buttons?.isNotEmpty() == true)
                s.say("venam")
            }
            // "menu": the owner's own list.
            val list = s.say("menu")
            assertTrue("[$category] menu → ${all(list)}", all(list).contains("₹80") && all(list).contains("₹110"))
            assertTrue("[$category] nothing sold before Confirm", s.tools.sales.isEmpty())
        }
    }

    @Test fun kaiNeverAddsAnItemByItself() {
        val s = Shop("GROCERY")
        // An item not on the Menu: said plainly, how to add it — nothing kept, no sale.
        val t = s.say("oru facial")
        assertTrue(all(t), all(t).contains("menu-la illa") && all(t).contains("menu:"))
        assertTrue(s.menu().isEmpty() && s.tools.sales.isEmpty())
        // Removed from the Menu: no longer sold.
        s.say("menu: facial 110"); s.confirm()
        runBlocking { KaiMenu.remove(s.memory, "Facial") }
        assertTrue(s.menu().isEmpty())
        assertTrue(all(s.say("oru facial")).contains("menu-la illa"))
    }

    @Test fun expiryIsAMedicalShopsOnly() {
        for (line in listOf("Bread 10 packet vandhuchu expiry 20/10/2026", KaiMicForm.of("Bread 10 packet vandhuchu expiry 20/10/2026")!!)) {
            val g = Shop("GROCERY")
            val t = g.say(line)
            assertTrue("[GROCERY] $line → ${all(t)}", all(t).contains("Medical shop") && g.inv.moves.isEmpty() && g.inv.batchIns.isEmpty())
            assertTrue("[GROCERY] expiry list", all(g.say("expiry aagura list kudu")).contains("Medical shop"))
        }
        // A medical shop (and a shop with no category yet) keeps reading the batch / expiry, as before.
        val m = Shop("PHARMACY")
        assertTrue(all(m.say("Bread 10 packet vandhuchu expiry 20/10/2026")).let { !it.contains("Medical shop-ku mattum") })
    }
}
