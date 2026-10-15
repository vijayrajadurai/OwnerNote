package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.data.model.DailyCashTotals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The shop's category decides which services are its sales (owner, 15 Oct 2026, before the Play Store launch): every one of the
 * 13 categories a shop picks at setup, the same lines typed and as the ta-IN mic writes them. A beauty salon's "ஒரு கட்டிங்"
 * and a restaurant's "2 டீ" are sales (Kai asks the price); a grocery's "2 tea" is never a tea shop's sale; a hardware shop's
 * "drill machine 2 naal vaadagai" is a rental; money and stock lines are never sales anywhere. Nothing is written in any of it.
 */
class KaiCategoryMatrixTest {
    private val now = LocalDateTime.of(2026, 10, 15, 11, 0)

    private val categories = listOf("TEXTILE", "GROCERY", "HARDWARE", "ELECTRICAL", "MOBILE_ACCESSORIES", "AUTO_PARTS", "FURNITURE", "FOOTWEAR",
        "PHARMACY", "STATIONERY", "RESTAURANT_FOOD", "BEAUTY_SALON", "OTHER")

    /** A line, as typed and as the mic writes it; the service a sale of it names; the categories where it is a sale. */
    private data class Line(val typed: String, val mic: String, val service: String?, val saleIn: Set<String>)

    private val salon = setOf("BEAUTY_SALON", "OTHER")
    private val food = setOf("RESTAURANT_FOOD", "OTHER")
    private val lines = listOf(
        Line("oru cutting", "ஒரு கட்டிங்", "Hair cutting", salon),
        Line("oru shaving", "ஒரு ஷேவிங்", "Shaving", salon),
        Line("threading", "த்ரெட்டிங்", "Threading", salon),
        Line("oru facial", "ஒரு ஃபேஷியல்", "Facial", salon),
        // ("tea" is a grocery's goods too: a sale only where the shop sells it by the cup)
        Line("2 tea", "2 டீ", "Tea", setOf("RESTAURANT_FOOD")),
        Line("3 idli", "3 இட்லி", "Idli", food),
        Line("oru meals", "ஒரு மீல்ஸ்", "Meals", food),
        Line("drill machine 2 naal vaadagai", "டிரில் மெஷின் 2 நாள் வாடகை", "Drill machine rent", setOf("HARDWARE", "ELECTRICAL", "OTHER")),
        // Money and stock: never a sale, in any shop.
        Line("Kumar 500 kuduthan", "குமார் 500 குடுத்தான்", null, emptySet()),
        Line("Colgate 2 box vandhuchu", "கோல்கேட் 2 பாக்ஸ் வந்துச்சு", null, emptySet()),
    )

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
        override suspend fun cashNote(day: LocalDate) = CashNoteView(day, 0, DailyCashTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0, 0.0, false)
    }

    private class Tools(val inv: Inventory, val category: String) : KaiTools by inv {
        val sales = mutableListOf<String>()
        override suspend fun cashOpening(day: LocalDate) = BigDecimal.ZERO
        override suspend fun addCashSale(day: LocalDate, amount: BigDecimal, upi: Boolean, note: String): ActionOutcome { sales += note; return ActionOutcome.Done("c", amount) }
        override suspend fun businessCategory() = category
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private fun kai(category: String): Pair<KaiAgent, Tools> {
        val inv = Inventory().also { it.items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("50"), mapOf("BOX" to BigDecimal("12")), null, null, "Grocery", null) }
        val tools = Tools(inv, category)
        return KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore()))) to tools
    }

    /** Kai took the line as a sale: it asks the service's price, or shows the sale's draft. */
    private fun startedSale(t: KaiTurn, service: String?): Boolean =
        t.card?.buttons?.any { it.action is KaiAction.ConfirmSale } == true ||
            service != null && t.reply.text.contains(service) && (t.reply.text.contains("evlo") || t.reply.text.contains("எவ்வளவு"))

    @Test fun eachCategorySellsItsOwnServices() {
        val fails = mutableListOf<String>()
        var played = 0
        for (category in categories) for (line in lines) for (said in listOf(line.typed, line.mic)) {
            val (kai, tools) = kai(category)
            val t = runBlocking { kai.ask(said) }
            played++
            val sale = startedSale(t, line.service)
            val want = category in line.saleIn
            if (sale != want) fails += "[$category] «$said» → ${if (sale) "a sale" else "not a sale"} (want ${if (want) "a sale" else "not a sale"}): ${t.reply.text}"
            if (tools.sales.isNotEmpty() || tools.inv.moves.isNotEmpty()) fails += "[$category] «$said» wrote something before Confirm"
        }
        assertTrue("${fails.size} of $played fail:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun anUnknownCategoryReadsAsBefore() {
        // No category known (the profile not read yet): Kai's words as before — a salon's word is a sale, a grocery's "tea" is not.
        val (kai, _) = kai("")
        assertTrue(startedSale(runBlocking { kai.ask("oru cutting") }, "Hair cutting"))
        val (kai2, _) = kai("")
        assertTrue(!startedSale(runBlocking { kai2.ask("2 tea") }, "Tea"))
    }
}
