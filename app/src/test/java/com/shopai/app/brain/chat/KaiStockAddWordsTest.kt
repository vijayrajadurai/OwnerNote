package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.KaiStock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner's own lines (9 Oct 2026): "men shorts 60 add pannu", "bhaniyan 70 add pannu", "5inch tape 10 add pannu",
 * "10inch pipe 90 add pannu". A size said in the name ("5inch") is the product's size, never the quantity; a spelling of a
 * product the shop has ("bhaniyan" for Baniyan) is that product; shorts / baniyan are garments. Checked in the inventory.
 */
class KaiStockAddWordsTest {
    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot()
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private fun kai(shop: Inventory) = KaiAgent(KaiBusinessBrain(Books()), Books(), shop, { LocalDateTime.of(2026, 10, 9, 10, 0) })

    /** Says [line], answers each question the way an owner would (pieces, skip the optional ones) and says "aama" to the summary. */
    private fun addNew(line: String): Inventory {
        val shop = Inventory()
        val k = kai(shop)
        var t = runBlocking { k.ask(line) }
        repeat(8) {
            val txt = t.reply.text
            val answer = when {
                txt.contains("Stock add pannattuma") -> "aama"
                txt.contains("pieces-aa") -> "pieces"
                txt.contains("rate evlo") || txt.contains("'skip'") -> "skip"
                else -> return shop
            }
            assertTrue("saved before Confirm: $txt", answer == "aama" || shop.items.isEmpty())
            t = runBlocking { k.ask(answer) }
        }
        return shop
    }

    @Test fun newProductsSaveWithTheirNameQuantityAndCategory() {
        data class Want(val line: String, val name: String, val qty: String, val category: String, val size: String?)
        for (w in listOf(
            Want("men shorts 60 add pannu", "Men Shorts", "60", "Garments & Textiles", null),
            Want("bhaniyan  70  add  pannu", "Bhaniyan", "70", "Garments & Textiles", null),
            Want("5inch tape 10 add pannu", "5 inch Tape", "10", "Hardware & Plumbing", "5 inch"),
            Want("10inch pipe 90 add pannu", "10 inch Pipe", "90", "Hardware & Plumbing", "10 inch"),
            Want("10 inch pipe 90 add pannu", "10 inch Pipe", "90", "Hardware & Plumbing", "10 inch"),
        )) {
            val shop = addNew(w.line)
            val i = shop.items.singleOrNull() ?: throw AssertionError("${w.line}: saved ${shop.items.map { it.name }}")
            assertEquals(w.line, w.name, i.name)
            assertEquals(w.line, "PCS", i.unit)
            assertEquals(w.line, 0, i.stock.compareTo(BigDecimal(w.qty)))
            assertEquals(w.line, w.category, i.category)
            assertEquals(w.line, w.size, i.size)
            assertEquals(w.line, listOf("OPENING"), shop.moves.map { it.type })
        }
    }

    @Test fun stockForProductsTheShopHasGoesToThoseProducts() {
        for ((line, name, after) in listOf(
            Triple("men shorts 60 add pannu", "Men Shorts", "65"),
            Triple("bhaniyan  70  add  pannu", "Baniyan", "75"),
            Triple("5inch tape 10 add pannu", "5 inch Tape", "15"),
            Triple("10inch pipe 90 add pannu", "10 inch Pipe", "95"),
        )) {
            val shop = Inventory()
            for ((id, n, cat) in listOf(Triple("a", "Men Shorts", "GARMENTS"), Triple("b", "Baniyan", "GARMENTS"),
                    Triple("c", "5 inch Tape", "HARDWARE"), Triple("d", "10 inch Pipe", "HARDWARE")))
                shop.items += Inventory.Item(id, n, "PCS", BigDecimal("5"), emptyMap(), null, null, cat, null)
            val k = kai(shop)
            val t = runBlocking { k.ask(line) }
            assertTrue("$line: ${t.reply.text}", t.reply.text.startsWith("$name — "))
            assertEquals("nothing saved before Confirm", 0, shop.moves.size)
            val b = t.card!!.buttons.first { it.action is KaiAction.ConfirmStock }
            runBlocking { k.act(b.action, KaiLang.TANGLISH) }
            assertEquals(line, 0, shop.item(name).stock.compareTo(BigDecimal(after)))
            assertEquals(line, 4, shop.items.size)
        }
    }

    @Test fun aSizeAloneIsStillTheQuantityAndShortNamesAreNotGuessed() {
        // One number only: "20 feet" is what came in, not a size.
        val pipe = KaiStock.understand("pipe 20 feet vandhuchu", emptyList())!!
        assertEquals(0, pipe.qty!!.compareTo(BigDecimal(20)))
        assertNull(pipe.size)
        // Spelling match needs five letters or more and one product only: "Rise" is not "Rice".
        val rice = com.shopai.app.brain.tools.ProductRef("r", "Rice", "KG", BigDecimal("10"))
        assertNull(KaiStock.understand("rise 5 kg vandhuchu", listOf(rice))?.product)
    }
}
