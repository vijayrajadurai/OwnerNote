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
/**
 * The owner (16 Oct 2026): products added in English from a purchase bill ("Groundnut Oil idhayam", "Sunflower Oil Fortune") —
 * found typed in Tanglish and by the Tamil mic, by any part of the name in any order, with no word list per product.
 */
class KaiBillProductNamesTest {
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

    private val cases = listOf(
        "groundnut oil stock evlo" to "Groundnut Oil idhayam", "கிரவுண்ட்நட் ஆயில் ஸ்டாக் எவ்வளவு" to "Groundnut Oil idhayam",
        "இதயம் ஆயில் ஸ்டாக் எவ்வளவு" to "Groundnut Oil idhayam", "கடலை எண்ணெய் ஸ்டாக் எவ்வளவு" to "Groundnut Oil idhayam",
        "surf excel stock evlo" to "Detergent Powder Surf Excel", "சர்ஃப் எக்செல் ஸ்டாக் எவ்வளவு" to "Detergent Powder Surf Excel",
        "ஆட்டா ஸ்டாக் எவ்வளவு" to "Wheat Flour (Atta) Aashirvaad", "பொன்னி அரிசி ஸ்டாக் எவ்வளவு" to "Rice Ponni Aachi",
        "டாடா டீ ஸ்டாக் எவ்வளவு" to "Tea Powder Tata Tea", "நெஸ்கஃபே ஸ்டாக் எவ்வளவு" to "Coffee Powder Nescafe",
        "துவரம் பருப்பு ஸ்டாக் எவ்வளவு" to "Toor Dal Aachi", "உளுந்து ஸ்டாக் எவ்வளவு" to "Urad Dal Aachi",
        "fortune oil 5 litre vithuchu" to "Sunflower Oil Fortune", "ஃபார்ச்சூன் ஆயில் 5 லிட்டர் வித்துச்சு" to "Sunflower Oil Fortune",
        "groundnut oil 3 litre vandhuchu" to "Groundnut Oil idhayam", "கிரவுண்ட்நட் ஆயில் 3 லிட்டர் வந்துச்சு" to "Groundnut Oil idhayam",
        "pillsbury maida 1 kg vithuchu" to "Maida Pillsbury", "பில்ஸ்பரி மைதா 1 கிலோ வித்துச்சு" to "Maida Pillsbury",
        "aachi salt 2 packet vandhuchu" to "Salt Aachi", "ஆச்சி உப்பு 2 பாக்கெட் வந்துச்சு" to "Salt Aachi",
    )

    @Test fun aBillsEnglishNamesAreFoundTypedAndByMic() {
        val names = listOf("Rice Ponni Aachi" to "KG", "Toor Dal Aachi" to "KG", "Urad Dal Aachi" to "KG", "Sugar Ohampure" to "KG", "Sunflower Oil Fortune" to "LITRE",
            "Groundnut Oil idhayam" to "LITRE", "Tea Powder Tata Tea" to "PACKET", "Coffee Powder Nescafe" to "PACKET", "Wheat Flour (Atta) Aashirvaad" to "KG",
            "Maida Pillsbury" to "KG", "Salt Aachi" to "PACKET", "Detergent Powder Surf Excel" to "PACKET")
        val fails = mutableListOf<String>()
        for ((line, want) in cases) {
            val s = Shop("GROCERY")
            names.forEachIndexed { i, (n, u) -> s.inv.items += com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory.Item("b" + i, n, u, BigDecimal("20"), emptyMap(), null, null, "Grocery", null) }
            val t = s.say(line)
            if (!all(t).contains(want)) fails += "«" + line + "» → " + all(t) + " (want " + want + ")"
            val other = if (want == "Sunflower Oil Fortune") "Groundnut Oil idhayam" else if (want == "Groundnut Oil idhayam") "Sunflower Oil Fortune" else null
            if (other != null && all(t).contains(other)) fails += "«" + line + "» → " + all(t) + ": the other oil"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
