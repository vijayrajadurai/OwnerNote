package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner's expanded stock catalogues (14 Oct 2026), five business categories each — 06-10: electronics and mobile accessories,
 * beauty and personal care, stationery and office supplies, automobile and spare parts, agriculture supplies; 21-25: jewellery and watches, optical and
 * eyewear, pet supplies, footwear and leather goods, electrical and lighting; 16-20: beverages and water, bakery and
 * confectionery, dairy and frozen foods, industrial supplies and machinery, chemicals and cleaning supplies; 11-15: meat, fish
 * and poultry, home and kitchen, furniture and furnishings, toys, gifts and sports goods, packaging and disposables. Each item in
 * Small / Medium / Large / Family packs ("Fresh milk Small pack"), and the catalogue's own codes for items not yet named
 * ("ON-0151 Small pack"). Each is said typed and as the ta-IN mic writes it, the pack the owner's way too ("small", "chinna pack",
 * "periya pack"): Kai takes exactly that item and pack — and a name said without its pack is asked (which pack), never guessed.
 */
abstract class KaiCatalogueShopTest {
    /** The catalogue's files: `/kai/<catalogue>-stock.txt` and `/kai/<catalogue>-mic.txt`. */
    protected abstract val catalogue: String

    private val now = LocalDateTime.of(2026, 10, 14, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private val rows: List<List<String>> by lazy {
        javaClass.getResource("/kai/$catalogue-stock.txt")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('|') }
    }
    protected val names by lazy { rows.map { it[0] } }

    private val base by lazy { Inventory().also { inv ->
        rows.forEachIndexed { i, r -> inv.items += Inventory.Item("c$i", r[0], r[1], BigDecimal("50"), emptyMap(), null, null, r[2], null) }
    } }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    /** Plays [lines] in one chat and confirms the last one's draft: the product whose stock moved and by how much, and Kai's reply. */
    protected fun talk(vararg lines: String): Triple<String?, BigDecimal?, String> {
        val inv = Inventory().also { it.items += base.items.map { i -> i.copy(stock = BigDecimal("50")) } }
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
        var t: KaiTurn? = null
        for (l in lines) {
            t = runBlocking { kai.ask(l) }
            if (inv.items.any { it.stock.compareTo(BigDecimal("50")) != 0 }) return Triple("(saved before Confirm)", null, all(t))
        }
        val c = t!!.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") } ?: return Triple(null, null, all(t))
        runBlocking { kai.act(c.action, KaiLang.TANGLISH) }
        val m = inv.items.filter { it.stock.compareTo(BigDecimal("50")) != 0 }.singleOrNull() ?: return Triple(null, null, all(t))
        return Triple(m.name, m.stock.subtract(BigDecimal("50")), all(t))
    }

    protected fun check(fails: MutableList<String>, want: String, vararg lines: String) {
        val (got, qty, reply) = talk(*lines)
        if (got != want || qty?.compareTo(BigDecimal("7")) != 0) fails += "${lines.toList()} → $got ${qty ?: ""} (want $want +7) — $reply"
    }

    private val pack = Regex("""\s(Small|Medium|Large|Family) pack$""")
    private fun itemOf(n: String) = pack.find(n)?.let { n.substring(0, it.range.first) } ?: n
    private fun packOf(n: String) = pack.find(n)!!.groupValues[1]

    @Test fun everyItemTyped() {
        val fails = mutableListOf<String>()
        for (n in names) check(fails, n, "$n 7 add pannu")
        assertTrue("${fails.size} of ${names.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    /** A pack as the owner types it in other words: "small", "chinna pack" (small), "periya pack" (large). */
    private fun saidOtherwise(p: String) = when (p) {
        "Small" -> listOf("small", "chinna pack")
        "Medium" -> listOf("medium")
        "Large" -> listOf("large", "periya pack")
        else -> listOf("family")
    }

    @Test fun packsSaidOtherwiseTyped() {
        val fails = mutableListOf<String>()
        for (n in names) for (o in saidOtherwise(packOf(n))) check(fails, n, "${itemOf(n)} $o 7 add pannu")
        assertTrue("${fails.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    // ------------------------------------------------------------------ the mic

    private val mic: Map<String, String> by lazy {
        javaClass.getResource("/kai/$catalogue-mic.txt")!!.readText().lines().filter { it.contains('=') && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /** The item's name as the mic writes it ("ON-0151" = ஓஎன் 0151: the letters said, the number as it is). */
    private fun spoken(item: String): String = item.split(Regex("""[\s-]+""")).filter { it.isNotEmpty() }
        .joinToString(" ") { w -> if (w.first().isDigit()) w else mic[w.lowercase()] ?: error("no mic form for $w") }

    private fun packSpoken(p: String) = mapOf("Small" to "ஸ்மால் பேக்", "Medium" to "மீடியம் பேக்", "Large" to "லார்ஜ் பேக்", "Family" to "ஃபேமிலி பேக்").getValue(p)
    /** The pack in the owner's Tamil: "சின்ன பேக்" (small), "பெரிய பேக்" (large). */
    private fun packTamil(p: String) = mapOf("Small" to "சின்ன பேக்", "Large" to "பெரிய பேக்")[p]

    @Test fun everyItemSpoken() {
        val fails = mutableListOf<String>()
        // Items that sound the same in Tamil letters: said aloud, Kai asks which (never drafts the other one).
        val homophones = names.map(::itemOf).distinct().groupBy(::spoken).filter { it.value.size > 1 }.keys
        for (n in names) {
            val s = spoken(itemOf(n))
            for (p in listOfNotNull(packSpoken(packOf(n)), packTamil(packOf(n)))) {
                val line = "$s $p 7 ஆட் பண்ணு"
                if (s in homophones) {
                    val (got, _, _) = talk(line)
                    if (got != null && got != n) fails += "[$n] [$line] drafted $got"
                    continue
                }
                check(fails, n, line)
            }
        }
        assertTrue("${fails.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    // ------------------------------------------------------------------ a name without its pack

    @Test fun aNameWithoutItsPackIsAsked() {
        val fails = mutableListOf<String>()
        val homophones = names.map(::itemOf).distinct().groupBy(::spoken).filter { it.value.size > 1 }.keys
        for ((item, packs) in names.groupBy(::itemOf)) {
            val pick = packs[1]
            val answers = listOf("$item 7 add pannu" to packOf(pick).lowercase(), "${spoken(item)} 7 ஆட் பண்ணு" to packSpoken(packOf(pick)).substringBefore(' '))
            for ((line, answer) in answers) {
                if (spoken(item) in homophones && line.startsWith(spoken(item))) continue
                val inv = Inventory().also { it.items += base.items.map { i -> i.copy(stock = BigDecimal("50")) } }
                val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
                val t = runBlocking { kai.ask(line) }
                val confirm = t.card?.buttons?.any { it.label.contains("Confirm") || it.label.contains("உறுதி") } == true
                if (confirm || packs.count { all(t).contains(it) } < 2) { fails += "ASK [$line] → ${all(t)}"; continue }
                // The pack said next goes on to that pack's draft.
                val d = runBlocking { kai.ask(answer) }
                val c = d.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") }
                if (c == null) { fails += "PICK [$line → $answer] → ${all(d)}"; continue }
                runBlocking { kai.act(c.action, KaiLang.TANGLISH) }
                if (inv.item(pick).stock.compareTo(BigDecimal("57")) != 0) fails += "PICK [$line → $answer] moved ${inv.items.filter { it.stock.compareTo(BigDecimal("50")) != 0 }.map { it.name }}"
            }
        }
        assertTrue("${fails.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    // ------------------------------------------------------------------ the owner's own names

    /** `/kai/<catalogue>-tamil.txt` where the catalogue has one: item | typed Tanglish | as the mic writes it. */
    private val tamil: List<List<String>> by lazy {
        javaClass.getResource("/kai/$catalogue-tamil.txt")?.readText()?.lines().orEmpty().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.split('|').map { it.trim() } }
    }

    /** The owner's own names (நண்டு = Crab, ஊதுபத்தி = Agarbatti, துடைப்பம் = Broom): the pack said goes on that pack; none said is asked. */
    @Test fun theOwnersOwnNames() {
        val fails = mutableListOf<String>()
        for ((item, typed, said) in tamil) {
            check(fails, "$item Small pack", "$typed small pack 7 add pannu")
            check(fails, "$item Small pack", "$said சின்ன பேக் 7 ஆட் பண்ணு")
            check(fails, "$item Medium pack", "$typed 7 add pannu", "medium")
            check(fails, "$item Medium pack", "$said 7 ஆட் பண்ணு", "மீடியம்")
        }
        assertTrue("${fails.size} of ${tamil.size * 4} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }
}

class KaiCatalogue0105ShopTest : KaiCatalogueShopTest() {
    override val catalogue = "catalogue-01-05"
}

class KaiCatalogue0610ShopTest : KaiCatalogueShopTest() {
    override val catalogue = "catalogue-06-10"
}

class KaiCatalogue1620ShopTest : KaiCatalogueShopTest() {
    override val catalogue = "catalogue-16-20"
}

class KaiCatalogue1115ShopTest : KaiCatalogueShopTest() {
    override val catalogue = "catalogue-11-15"
}

class KaiCatalogue2125ShopTest : KaiCatalogueShopTest() {
    override val catalogue = "catalogue-21-25"
}

class KaiCatalogue2630ShopTest : KaiCatalogueShopTest() {
    override val catalogue = "catalogue-26-30"
}
