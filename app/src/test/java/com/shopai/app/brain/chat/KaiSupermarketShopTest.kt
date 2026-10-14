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
 * The owner's supermarket stock master (14 Oct 2026, 2,200 SKU rows — rice, flours, dals, oils, ghee, spices, masalas, snacks,
 * biscuits, noodles, beverages, dairy, bakery, personal care, cleaning, kitchen, baby, pet, stationery), all kept in one shop in
 * packs, each brand and pack size its own product ("Aachi Chilli powder 100 g", "Fortune Sunflower oil 1 L"). Each is said typed
 * and as the ta-IN mic writes it: Kai takes exactly that product and pack — the pack size in the name is never the quantity; a
 * name said without the pack it comes in is asked (which pack), never guessed.
 */
class KaiSupermarketShopTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private val names: List<String> = javaClass.getResource("/kai/supermarket-stock.txt")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('|') }

    private fun inventory() = Inventory().also { inv ->
        names.forEachIndexed { i, n -> inv.items += Inventory.Item("h$i", n, "PCS", BigDecimal("50"), emptyMap(), null, null, "Supermarket", null) }
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    private val base = inventory()
    private val products = runBlocking { base.products() }

    /** Adds 7 by [line]: the product whose stock moved (null: none / not exactly one), and Kai's reply. */
    private fun added(line: String): Pair<String?, String> {
        val inv = Inventory().also { it.items += base.items.map { i -> i.copy(stock = BigDecimal("50")) } }
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
        val t = runBlocking { kai.ask(line) }
        if (inv.items.any { it.stock.compareTo(BigDecimal("50")) != 0 }) return "(saved before Confirm)" to all(t)
        val c = t.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") } ?: return null to all(t)
        runBlocking { kai.act(c.action, KaiLang.TANGLISH) }
        val moved = inv.items.filter { it.stock.compareTo(BigDecimal("50")) != 0 }
        return moved.singleOrNull()?.takeIf { it.stock.compareTo(BigDecimal("57")) == 0 }?.name to all(t)
    }

    @Test fun everyItemTyped() {
        val fails = mutableListOf<String>()
        for (n in names) {
            val (got, reply) = added("$n 7 add pannu")
            if (got != n) fails += "[$n 7 add pannu] → $got — $reply"
        }
        assertTrue("${fails.size} of ${names.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    // ------------------------------------------------------------------ sizes as the owner says them, and the mic

    private val size = Regex("""\s(\d+(?:\.\d+)? (?:g|kg|ml|L|bags)|Small|Medium|Large|XL|Family Pack|Pack of 4)$""")
    private fun baseOf(n: String) = size.find(n)?.let { n.substring(0, it.range.first) } ?: n
    private fun sizeOf(n: String) = size.find(n)?.groupValues?.get(1)

    /** A pack as the owner types it in other words: "100 gram", "1 kilo", "arai kilo" (500 g), "1 litre" (null: no other way). */
    private fun saidOtherwise(s: String): List<String> = when {
        s == "500 g" -> listOf("500 gram", "arai kilo", "half kg")
        s == "250 g" -> listOf("250 gm", "kaal kilo")
        s.endsWith(" g") -> listOf(s.replace(" g", " gram"), s.replace(" g", "gm"))
        s.endsWith(" kg") -> listOf(s.replace(" kg", " kilo"))
        s.endsWith(" L") -> listOf(s.replace(" L", " litre"))
        s.endsWith(" ml") -> listOf(s.replace(" ml", "ml"))
        else -> emptyList()
    }

    private val mic: Map<String, String> = javaClass.getResource("/kai/supermarket-mic.txt")!!.readText().lines()
        .filter { it.contains('=') && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    /** A pack as the mic writes it: "100 கிராம்", "1 கிலோ", "500 எம்எல்", "1 லிட்டர்", "ஃபேமிலி பேக்". */
    private fun sizeSpoken(s: String): String = when {
        s.endsWith(" g") -> s.replace(" g", " கிராம்")
        s.endsWith(" kg") -> s.replace(" kg", " கிலோ")
        s.endsWith(" ml") -> s.replace(" ml", " எம்எல்")
        s.endsWith(" L") -> s.replace(" L", " லிட்டர்")
        s.endsWith(" bags") -> s.replace(" bags", " பேக்ஸ்")
        else -> mapOf("Small" to "ஸ்மால்", "Medium" to "மீடியம்", "Large" to "லார்ஜ்", "XL" to "எக்ஸ்எல்", "Family Pack" to "ஃபேமிலி பேக்", "Pack of 4" to "பேக் ஆஃப் 4").getValue(s)
    }

    /** The whole name as the mic writes it. */
    private fun spoken(n: String): String {
        val b = baseOf(n).split(' ').joinToString(" ") { w -> mic[w.lowercase()] ?: mic[w.lowercase().replace("’", "'")] ?: error("no mic form for $w") }
        return sizeOf(n)?.let { "$b ${sizeSpoken(it)}" } ?: b
    }

    @Test fun sizesSaidOtherwiseTyped() {
        val fails = mutableListOf<String>()
        for (n in names) {
            val s = sizeOf(n) ?: continue
            for (other in saidOtherwise(s)) {
                val line = "${baseOf(n)} $other 7 add pannu"
                val (got, reply) = added(line)
                if (got != n) fails += "[$line] → $got — $reply"
            }
        }
        assertTrue("${fails.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    @Test fun everyItemSpoken() {
        val fails = mutableListOf<String>()
        // Names that sound the same in Tamil letters: said aloud, Kai asks which (never drafts the other one).
        val homophones = names.groupBy(::spoken).filter { it.value.size > 1 }.values.flatten().toSet()
        for (n in names) {
            val line = "${spoken(n)} 7 ஆட் பண்ணு"
            val (got, reply) = added(line)
            if (n in homophones) { if (got != null && got != n) fails += "[$n] [$line] drafted $got"; continue }
            if (got != n) fails += "[$n] [$line] → $got — $reply"
        }
        assertTrue("${fails.size} of ${names.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    @Test fun aNameWithoutItsSizeIsAsked() {
        val fails = mutableListOf<String>()
        val families = names.groupBy(::baseOf).filter { (b, v) -> v.size > 1 && b !in names }
        fun spokenBase(v: List<String>) = spoken(v.first()).let { sp -> sizeOf(v.first())?.let { sp.removeSuffix(" " + sizeSpoken(it)) } ?: sp }
        // Names the same in Tamil letters: said aloud, Kai asks which — never picks one.
        val homophones = families.values.groupBy(::spokenBase).filter { it.value.size > 1 }.keys
        for ((b, v) in families) for (line in listOf("$b 7 add pannu", "${spokenBase(v)} 7 ஆட் பண்ணு")) {
            if (spokenBase(v) in homophones && line.startsWith(spokenBase(v))) {
                val t = runBlocking { KaiAgent(KaiBusinessBrain(Books()), Books(), Inventory().also { it.items += base.items }, { now }).ask(line) }
                if (t.card?.buttons?.any { it.label.contains("Confirm") || it.label.contains("உறுதி") } == true) fails += "HOMOPHONE [$line] drafted: ${all(t)}"
                continue
            }
            val inv = Inventory().also { it.items += base.items.map { i -> i.copy(stock = BigDecimal("50")) } }
            val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
            val t = runBlocking { kai.ask(line) }
            val confirm = t.card?.buttons?.any { it.label.contains("Confirm") || it.label.contains("உறுதி") } == true
            if (confirm || v.count { all(t).contains(it) } < 2) { fails += "ASK [$line] → ${all(t)}"; continue }
            // The size said next goes on to that product's draft.
            val pick = v[1]
            val d = runBlocking { kai.ask(sizeOf(pick)!!) }
            val c = d.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") }
            if (c == null) { fails += "PICK [$line → ${sizeOf(pick)}] → ${all(d)}"; continue }
            runBlocking { kai.act(c.action, KaiLang.TANGLISH) }
            if (inv.item(pick).stock.compareTo(BigDecimal("57")) != 0) fails += "PICK [$line → ${sizeOf(pick)}] moved ${inv.items.filter { it.stock.compareTo(BigDecimal("50")) != 0 }.map { it.name }}"
        }
        assertTrue("${fails.size} of ${families.size * 2} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }
}
