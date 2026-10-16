package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Stock, every way at once (owner, 16 Oct 2026: "same stock-kum — logic correct-a irukkanum"). Every product kind the shop keeps
 * (one word, a brand with two words, a product by its Tamil name — "thakkali" is their Tomato — kept in pieces with boxes, in
 * kg with bags) × coming in / sold / damaged / taken off / asked × units × quantities in digits and in words — typed, and as
 * the ta-IN mic writes it. Each line lands on the right product, the right way (in / out) and the right quantity in the
 * product's own unit — asked, never guessed, when the unit isn't said; nothing changes before Confirm.
 */
class KaiStockMatrixTest {
    private val now = LocalDateTime.of(2026, 10, 16, 11, 0)

    /** A product as the books keep it, and how the owner says it typed / by mic. */
    data class Product(val name: String, val unit: String, val conversions: Map<String, BigDecimal>, val typed: List<String>, val mic: List<String>)

    /** A unit the owner says: typed, by mic, and how many of the product's own unit it is (null: the product's own unit). */
    data class Unit(val typed: String, val mic: String, val per: BigDecimal?)

    private val products = listOf(
        Product("Rice", "KG", mapOf("BAG" to BigDecimal("25")), listOf("Rice", "arisi"), listOf("ரைஸ்", "அரிசி")),
        Product("Colgate", "PCS", mapOf("BOX" to BigDecimal("48")), listOf("Colgate"), listOf("கோல்கேட்")),
        Product("Britannia Biscuit", "PCS", emptyMap(), listOf("Britannia Biscuit", "Britannia"), listOf("பிரிட்டானியா பிஸ்கட்", "பிரிட்டானியா")),
        Product("Lux Soap", "PCS", emptyMap(), listOf("Lux Soap", "Lux"), listOf("லக்ஸ் சோப்", "லக்ஸ்")),
        Product("Tomato", "KG", emptyMap(), listOf("Tomato", "thakkali"), listOf("டொமேட்டோ", "தக்காளி")),
        Product("Sunflower Oil", "LITRE", mapOf("BOX" to BigDecimal("12")), listOf("Sunflower Oil"), listOf("சன்ஃப்ளவர் ஆயில்")),
    )

    private fun unitsOf(p: Product): List<Unit> = buildList {
        when (p.unit) {
            "KG" -> add(Unit("kg", "கிலோ", null))
            "PCS" -> add(Unit("pieces", "பீஸ்", null))
            "LITRE" -> add(Unit("litre", "லிட்டர்", null))
        }
        p.conversions["BAG"]?.let { add(Unit("moota", "மூட்டை", it)) }
        p.conversions["BOX"]?.let { add(Unit("box", "பாக்ஸ்", it)) }
    }

    /** A way of saying it: {p} the product, {q} the quantity, {u} the unit; +1 in, -1 out. */
    data class Way(val typed: String, val mic: String, val sign: Int)

    private val ways = listOf(
        Way("{p} {q} {u} vandhuchu", "{p} {q} {u} வந்துச்சு", +1),
        Way("{q} {u} {p} vandhuchu", "{q} {u} {p} வந்துச்சு", +1),
        Way("{p} {q} {u} stock add pannu", "{p} {q} {u} ஸ்டாக் ஆட் பண்ணு", +1),
        Way("{p} {q} {u} irakkinen", "{p} {q} {u} இறக்கினேன்", +1),
        Way("{p} {q} {u} vithuchu", "{p} {q} {u} வித்துச்சு", -1),
        Way("{p} {q} {u} damage", "{p} {q} {u} டேமேஜ்", -1),
        Way("{p} {q} {u} kettupochu", "{p} {q} {u} கெட்டுப்போச்சு", -1),
        Way("{p} {q} {u} kammi pannu", "{p} {q} {u} கம்மி பண்ணு", -1),
    )

    /** (typed, as the mic writes it, the number). */
    private val quantities = listOf(Triple("2", "2", BigDecimal("2")), Triple("rendu", "ரெண்டு", BigDecimal("2")), Triple("anju", "அஞ்சு", BigDecimal("5")))

    private val start = BigDecimal("500")

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = listOf(PartySummary("c1", "Kumar", null, 500.0, null)), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private fun kai(): Pair<KaiAgent, Inventory> {
        val inv = Inventory().also { inv ->
            inv.items.clear()
            products.forEachIndexed { i, p -> inv.items += Inventory.Item("p$i", p.name, p.unit, start, p.conversions, BigDecimal("10"), BigDecimal("12"), "Grocery", null) }
        }
        val books = Books()
        return KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }), books, inv, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore()))) to inv
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    @Test fun everyProductEveryWayTypedAndSpoken() {
        val fails = mutableListOf<String>()
        var played = 0
        for (p in products) for ((said, spoken) in p.typed.zip(p.mic)) for (u in unitsOf(p)) for (w in ways) for ((tq, mq, q) in quantities) for (mic in listOf(false, true)) {
            val line = (if (mic) w.mic.replace("{p}", spoken).replace("{u}", u.mic).replace("{q}", mq)
                else w.typed.replace("{p}", said).replace("{u}", u.typed).replace("{q}", tq)).replace(Regex("""\s+"""), " ").trim()
            val (kai, inv) = kai()
            played++
            var t = runBlocking { kai.ask(line) }
            val want = start + q.multiply(u.per ?: BigDecimal.ONE) * BigDecimal(w.sign)
            var done = false
            for (step in 0 until 4) {
                val b = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan }
                if (b != null) {
                    if (inv.moves.isNotEmpty()) { fails += "«$line» stock changed before Confirm"; break }
                    if (inv.payments.isNotEmpty()) { fails += "«$line» a money entry: ${all(t)}"; break }
                    runBlocking { kai.act(b.action, KaiLang.TANGLISH) }
                    val changed = inv.items.filter { it.stock.compareTo(start) != 0 }
                    val mine = inv.items.single { it.name == p.name }
                    when {
                        changed.any { it.name != p.name } -> fails += "«$line» → ${changed.map { "${it.name} ${it.stock.toPlainString()}" }} (want ${p.name} ${want.toPlainString()})"
                        mine.stock.compareTo(want) != 0 -> fails += "«$line» → ${p.name} ${mine.stock.toPlainString()} (want ${want.toPlainString()}): ${all(t)}"
                    }
                    done = true
                    break
                }
                // "5 pieces-aa, 5 boxes-aa?" — only when the unit isn't said; here it always is.
                fails += "«$line» → no draft: ${all(t)}"
                done = true
                break
            }
            if (!done) fails += "«$line» never reached Confirm"
        }
        // "{p} stock evlo": the product's stock from the records, nothing written.
        for (p in products) for ((said, spoken) in p.typed.zip(p.mic)) for (line in listOf("$said stock evlo", "$spoken ஸ்டாக் எவ்வளவு")) {
            val (kai, inv) = kai()
            played++
            val t = runBlocking { kai.ask(line) }
            if (!all(t).contains("500") || !all(t).contains(p.name) && !all(t).contains(spoken)) fails += "«$line» → ${all(t)}"
            if (inv.moves.isNotEmpty()) fails += "«$line» wrote stock"
        }
        assertTrue("${fails.size} of $played fail:\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
