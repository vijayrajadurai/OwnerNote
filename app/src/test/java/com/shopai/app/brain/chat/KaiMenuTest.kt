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

    /**
     * The owner's phone (16 Oct 2026): "facial onnu" was sold as their "Face massage" (Kai's own list calls both Facial), and the
     * mic didn't find their items. Whatever the owner types on the Menu — English or Tanglish, a service or a product — the mic's
     * Tamil letters for it are that item by sound, before Kai's own words; an item is never another of theirs.
     */
    @Test fun theOwnersMenuNamesAreHeardFromTheMic() {
        val menu = listOf("Face massage" to "250", "Head massage" to "150", "Beard trim" to "60", "Gold facial" to "900", "Bridal makeup" to "5000",
            "Hair spa" to "400", "Thalai mudi vettu" to "120", "Masala dosa" to "60", "Filter coffee" to "25", "Chicken biryani" to "150")
        val sells = listOf(
            "face massage onnu" to "Face massage ×1", "ஃபேஸ் மசாஜ் ஒன்னு" to "Face massage ×1", "ஹெட் மசாஜ் ஒன்னு" to "Head massage ×1",
            "பியர்ட் ட்ரிம் ஒன்னு" to "Beard trim ×1", "gold facial onnu" to "Gold facial ×1", "கோல்ட் ஃபேஷியல் ஒன்னு" to "Gold facial ×1",
            "பிரைடல் மேக்கப் ஒன்னு" to "Bridal makeup ×1", "ஹேர் ஸ்பா ரெண்டு" to "Hair spa ×2", "தலை முடி வெட்டு ஒன்னு" to "Thalai mudi vettu ×1",
            "masala dosa rendu" to "Masala dosa ×2", "மசாலா தோசை ரெண்டு" to "Masala dosa ×2", "ஃபில்டர் காபி மூணு" to "Filter coffee ×3",
            "சிக்கன் பிரியாணி ஒன்னு" to "Chicken biryani ×1")
        val fails = mutableListOf<String>()
        for (category in listOf("BEAUTY_SALON", "RESTAURANT_FOOD", "GROCERY")) {
            val s = Shop(category)
            runBlocking { s.memory.open("shop-1", "owner-1"); menu.forEach { (n, p) -> KaiMenu.save(s.memory, n, BigDecimal(p)) } }
            for ((line, want) in sells) {
                val t = s.say(line)
                if (!all(t).contains(want)) fails += "[$category] «$line» → ${all(t)} (want $want)"
                s.say("venam")
            }
            // "Facial" is not on this Menu: never sold as their Face massage or Gold facial, typed or by mic.
            for (line in listOf("facial onnu", "ஃபேஷியல் ஒன்னு")) {
                val t = s.say(line)
                if (t.card?.buttons?.isNotEmpty() == true && !all(t).contains("Facial —") || all(t).contains("Face massage") || all(t).contains("Gold facial"))
                    fails += "[$category] «$line» → ${all(t)}: another Menu item"
                s.say("venam")
            }
            if (s.tools.sales.isNotEmpty()) fails += "[$category] sold before Confirm"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
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

    /**
     * Expiry is for goods that can expire, in any shop (owner, 17 Oct 2026: "grocery-ku fruits and vegetables… expiry alert 7-kulla"):
     * Bread in a grocery keeps the expiry said — kept by batch from that stock on, on Confirm — typed and by mic; goods that never
     * expire (a shirt) are told plainly and keep none; the expiry list answers in every shop.
     */
    @Test fun expiryIsForGoodsThatCanExpire() {
        for (line in listOf("Bread 10 packet vandhuchu expiry 20/10/2026", KaiMicForm.of("Bread 10 packet vandhuchu expiry 20/10/2026")!!)) {
            val g = Shop("GROCERY")
            val t = g.say(line)
            assertTrue("[GROCERY] $line → ${all(t)}", all(t).contains("Expiry: ") && !all(t).contains("Medical shop") && g.inv.moves.isEmpty())
            g.confirm()
            assertTrue("[GROCERY] $line kept by batch", g.inv.keptByBatch == listOf("Bread") && g.inv.batchIns.single().second.expiry == LocalDate.of(2026, 10, 20))
            assertTrue("[GROCERY] expiry list", !all(g.say("expiry aagura list kudu")).contains("Medical shop"))
        }
        // Goods that never expire: said plainly, nothing kept.
        val t = Shop("TEXTILE").also { it.inv.items += Inventory.Item("p2", "Cotton Shirt", "PCS", BigDecimal("5"), emptyMap(), null, null, "Garments & Textiles", null) }
        val r = t.say("Cotton Shirt 10 vandhuchu expiry 20/10/2026")
        assertTrue(all(r), all(r).contains("expiry aagura product illa") && t.inv.moves.isEmpty() && t.inv.batchIns.isEmpty() && t.inv.keptByBatch.isEmpty())
        // A medical shop keeps reading the batch / expiry, as before.
        val m = Shop("PHARMACY")
        assertTrue(all(m.say("Bread 10 packet vandhuchu expiry 20/10/2026")).contains("Expiry: "))
    }
}
