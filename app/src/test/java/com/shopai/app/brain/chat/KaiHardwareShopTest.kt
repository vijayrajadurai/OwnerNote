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
 * The owner's hardware shop stock master (13 Oct 2026, 1,104 items — screws, bolts, nails, anchors, tools, power tools, paints,
 * adhesives, pipes and fittings, valves, taps, wires, switches, MCBs, lights, locks, hinges, cement, TMT, sheets, sanitaryware,
 * safety, garden, tapes, workshop), all kept in one shop in pieces, each size its own product ("Wood screw 3x10 mm", "PVC pipe
 * 1/2 inch", "SP MCB 16A"). Each is said typed and as the ta-IN mic writes it: Kai takes exactly that product and size; a name
 * said without the size it comes in is asked (which size), never guessed.
 */
class KaiHardwareShopTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private val names: List<String> = javaClass.getResource("/kai/hardware-stock.txt")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('|') }

    private fun inventory() = Inventory().also { inv ->
        names.forEachIndexed { i, n -> inv.items += Inventory.Item("h$i", n, "PCS", BigDecimal("50"), emptyMap(), null, null, "Hardware", null) }
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

    private val size = Regex("""\s(\d+x\d+ mm|M\d+|\d+ mm|\d+ ml|\d+ L|[\d./]+ inch|[\d.]+ sq mm|\d+A|\d+ m)$""")
    private fun baseOf(n: String) = size.find(n)?.let { n.substring(0, it.range.first) } ?: n
    private fun sizeOf(n: String) = size.find(n)?.groupValues?.get(1)

    /** A size as the owner types it in other words: "16 amp", "half inch", "1 litre", "3 by 10 mm" (null: no other way). */
    private fun saidOtherwise(s: String): List<String> = when {
        s.endsWith("A") -> listOf("${s.dropLast(1)} amp")
        s == "1/2 inch" -> listOf("half inch", "arai inch")
        s == "3/4 inch" -> listOf("mukkal inch")
        s == "1.25 inch" -> listOf("1 1/4 inch", "onnekaal inch")
        s == "1.5 inch" -> listOf("1 1/2 inch", "onnara inch")
        s.endsWith(" L") -> listOf("${s.dropLast(2)} litre")
        s.endsWith(" m") -> listOf("${s.dropLast(2)} meter")
        s.contains("x") -> listOf(s.replace("x", " by "))
        s.endsWith(" sq mm") -> listOf(s.replace(" sq mm", " sqmm"))
        else -> emptyList()
    }

    private val mic: Map<String, String> = javaClass.getResource("/kai/hardware-mic.txt")!!.readText().lines()
        .filter { it.contains('=') && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    /** A size as the mic writes it: "அரை இன்ச்", "16 ஆம்ப்", "எம் 8", "1 லிட்டர்", "3 இன்டு 10 எம்எம்". */
    private fun sizeSpoken(s: String): String = when {
        s == "1/2 inch" -> "அரை இன்ச்"
        s == "3/4 inch" -> "முக்கால் இன்ச்"
        s == "1.25 inch" -> "ஒன்றேகால் இன்ச்"
        s == "1.5 inch" -> "ஒன்றரை இன்ச்"
        s.endsWith(" inch") -> s.replace(" inch", " இன்ச்")
        s.contains("x") -> s.replace("x", " இன்டு ").replace(" mm", " எம்எம்")
        s.endsWith(" sq mm") -> s.replace(" sq mm", " ஸ்கொயர் எம்எம்")
        s.endsWith(" mm") -> s.replace(" mm", " எம்எம்")
        s.endsWith(" ml") -> s.replace(" ml", " எம்எல்")
        s.endsWith(" L") -> s.replace(" L", " லிட்டர்")
        s.endsWith(" m") -> s.replace(" m", " மீட்டர்")
        s.endsWith("A") -> s.dropLast(1) + " ஆம்ப்"
        s.startsWith("M") -> "எம் " + s.drop(1)
        else -> s
    }

    /** The whole name as the mic writes it. */
    private fun spoken(n: String): String {
        val b = baseOf(n).split(' ').joinToString(" ") { w -> mic[w.lowercase()] ?: error("no mic form for $w") }
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
        for (n in names) {
            val line = "${spoken(n)} 7 ஆட் பண்ணு"
            val (got, reply) = added(line)
            if (got != n) fails += "[$n] [$line] → $got — $reply"
        }
        assertTrue("${fails.size} of ${names.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    @Test fun aNameWithoutItsSizeIsAsked() {
        val fails = mutableListOf<String>()
        val families = names.groupBy(::baseOf).filter { (b, v) -> v.size > 1 && b !in names }
        for ((b, v) in families) for (line in listOf("$b 7 add pannu", "${spoken(v.first()).let { sp -> sizeOf(v.first())?.let { sp.removeSuffix(" " + sizeSpoken(it)) } ?: sp }} 7 ஆட் பண்ணு")) {
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
