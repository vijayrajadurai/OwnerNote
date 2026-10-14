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
 * The owner's vegetable shop stock master (14 Oct 2026, 397 rows — leafy greens, common vegetables, roots and tubers, exotic,
 * seasonal, chillies and herbs): each produce kept loose in kg ("Tomato") and in 250 g / 500 g / 1 kg packs ("Tomato 250 g").
 * Each is said typed and as the ta-IN mic writes it, by its English name, by either name where the master gives two
 * ("Spinach / Palak"), and by the owner's own Tamil name ("thakkali" / "தக்காளி"): Kai takes exactly that produce — loose kilos
 * on the loose one, a pack size on that pack — and a vegetable the shop doesn't keep is never turned into another one.
 */
class KaiVegetableShopTest {
    private val now = LocalDateTime.of(2026, 10, 14, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private val rows: List<List<String>> = javaClass.getResource("/kai/vegetable-stock.txt")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('|') }
    private val names = rows.map { it[0] }
    private val unitOf = rows.associate { it[0] to it[1] }

    private val base = Inventory().also { inv ->
        rows.forEachIndexed { i, r -> inv.items += Inventory.Item("v$i", r[0], r[1], BigDecimal("50"), emptyMap(), null, null, r[2], null) }
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    /** Runs [line] and confirms its draft: the product whose stock moved and by how much (null: none / not exactly one), and Kai's reply. */
    private fun moved(line: String): Triple<String?, BigDecimal?, String> {
        val inv = Inventory().also { it.items += base.items.map { i -> i.copy(stock = BigDecimal("50")) } }
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
        val t = runBlocking { kai.ask(line) }
        if (inv.items.any { it.stock.compareTo(BigDecimal("50")) != 0 }) return Triple("(saved before Confirm)", null, all(t))
        val c = t.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") } ?: return Triple(null, null, all(t))
        runBlocking { kai.act(c.action, KaiLang.TANGLISH) }
        val m = inv.items.filter { it.stock.compareTo(BigDecimal("50")) != 0 }.singleOrNull() ?: return Triple(null, null, all(t))
        return Triple(m.name, m.stock.subtract(BigDecimal("50")), all(t))
    }

    private fun check(fails: MutableList<String>, line: String, want: String, by: String) {
        val (got, qty, reply) = moved(line)
        if (got != want || qty?.compareTo(BigDecimal(by)) != 0) fails += "[$line] → $got ${qty ?: ""} (want $want +$by) — $reply"
    }

    private val pack = Regex("""\s(\d+ (?:g|kg))$""")
    private fun baseOf(n: String) = pack.find(n)?.let { n.substring(0, it.range.first) } ?: n
    private fun packOf(n: String) = pack.find(n)?.groupValues?.get(1)
    private fun loose(n: String) = unitOf[n] == "KG"

    /**
     * The names a product goes by: "Spinach / Palak" → Spinach, Palak (each said alone); "Ginger (tender)" → also "tender Ginger"
     * (never a name another product has: "Radish (red)" is not "Red radish").
     */
    private fun aliases(b: String): List<String> {
        if (b.contains(" / ")) return b.split(" / ").map { it.trim() }
        val r = Regex("""^(.+?) \((.+)\)$""").find(b) ?: return listOf(b)
        val other = "${r.groupValues[2]} ${r.groupValues[1]}"
        return if (names.any { it.equals(other, ignoreCase = true) }) listOf(b) else listOf(b, other)
    }

    @Test fun everyItemTyped() {
        val fails = mutableListOf<String>()
        for (n in names) check(fails, if (loose(n)) "$n 7 kg add pannu" else "$n 7 add pannu", n, "7")
        assertTrue("${fails.size} of ${names.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    @Test fun eitherNameTyped() {
        val fails = mutableListOf<String>()
        for (n in names.filter { aliases(baseOf(it)).size > 1 }) for (a in aliases(baseOf(n))) {
            val p = packOf(n)
            check(fails, if (p == null) "$a 7 kg add pannu" else "$a $p 7 add pannu", n, "7")
        }
        assertTrue("${fails.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    @Test fun packsSaidOtherwiseTyped() {
        val fails = mutableListOf<String>()
        val other = mapOf("250 g" to listOf("250 gram", "kaal kilo"), "500 g" to listOf("500 gram", "arai kilo"), "1 kg" to listOf("1 kilo"))
        for (n in names) {
            val p = packOf(n) ?: continue
            for (o in other.getValue(p)) check(fails, "${aliases(baseOf(n)).last()} $o 7 add pannu", n, "7")
        }
        assertTrue("${fails.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    // ------------------------------------------------------------------ the mic

    private val mic: Map<String, String> = javaClass.getResource("/kai/vegetable-mic.txt")!!.readText().lines()
        .filter { it.contains('=') && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    /** A name as the mic writes it: a phrase kept in Tamil whole ("Arai keerai" = அரைக்கீரை), else word by word. */
    private fun spoken(name: String): String = mic[name.lowercase()]
        ?: name.split(Regex("""[\s/()]+""")).filter { it.isNotEmpty() }.joinToString(" ") { w -> mic[w.lowercase()] ?: error("no mic form for $w") }

    private fun packSpoken(p: String) = p.replace(" g", " கிராம்").replace(" kg", " கிலோ")

    @Test fun everyItemSpoken() {
        val fails = mutableListOf<String>()
        // Names that sound the same in Tamil letters: said aloud, Kai asks which (never drafts the other one).
        val said = names.flatMap { n -> aliases(baseOf(n)).map { a -> n to spoken(a) } }
        val homophones = said.groupBy({ it.second }, { baseOf(it.first) }).filter { it.value.distinct().size > 1 }.keys
        for ((n, s) in said) {
            val p = packOf(n)
            val line = when {
                p != null -> "$s ${packSpoken(p)} 7 ஆட் பண்ணு"
                loose(n) -> "$s 7 கிலோ ஆட் பண்ணு"
                else -> "$s 7 ஆட் பண்ணு"
            }
            if (s in homophones) {
                val (got, _, _) = moved(line)
                if (got != null && got != n) fails += "[$n] [$line] drafted $got"
                continue
            }
            check(fails, line, n, "7")
        }
        assertTrue("${fails.size} of ${said.size} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    // ------------------------------------------------------------------ the owner's own names

    private val tamil: List<List<String>> = javaClass.getResource("/kai/vegetable-tamil.txt")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { l -> l.split('|').map { it.trim() } }

    @Test fun theOwnersOwnNames() {
        val fails = mutableListOf<String>()
        for ((product, typed, said) in tamil) {
            if (!loose(product)) {
                check(fails, "$typed 20 vandhuchu", product, "20")
                check(fails, "$said 20 வந்துச்சு", product, "20")
                continue
            }
            // Loose kilos go on the loose produce; a pack size said goes on that pack.
            check(fails, "$typed 5 kilo vandhuchu", product, "5")
            check(fails, "$said 5 கிலோ வந்துச்சு", product, "5")
            check(fails, "$typed 250 gram 7 vandhuchu", "$product 250 g", "7")
            check(fails, "$said 250 கிராம் 7 வந்துச்சு", "$product 250 g", "7")
        }
        assertTrue("${fails.size} of ${tamil.size * 4} fails:\n" + fails.take(80).joinToString("\n"), fails.isEmpty())
    }

    /** Plays [lines] in one chat and confirms the last one's draft: the product whose stock moved and by how much. */
    private fun talk(vararg lines: String): Triple<String?, BigDecimal?, String> {
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

    @Test fun howAVegetableShopTalks() {
        val fails = mutableListOf<String>()
        fun expect(want: String?, by: String?, vararg lines: String) {
            val (got, qty, reply) = talk(*lines)
            if (got != want || (by != null && qty?.compareTo(BigDecimal(by)) != 0)) fails += "${lines.toList()} → $got ${qty ?: ""} (want $want ${by ?: ""}) — $reply"
        }
        // Sold, rotted, wilted: loose kilos out.
        expect("Tomato", "-2", "thakkali 2 kilo vithuchu"); expect("Tomato", "-2", "தக்காளி 2 கிலோ வித்துச்சு")
        expect("Tomato", "-3", "thakkali 3 kilo azhugiduchu"); expect("Tomato", "-3", "தக்காளி 3 கிலோ அழுகிடுச்சு")
        expect("Coriander leaves", "-2", "kothamalli 2 kilo vaadiduchu"); expect("Coriander leaves", "-2", "கொத்தமல்லி 2 கிலோ வாடிடுச்சு")
        // Bunches go on the bunch produce (counted in bunches).
        expect("Curry leaves bunch", "10", "kariveppilai 10 kattu vandhuchu"); expect("Curry leaves bunch", "10", "கறிவேப்பிலை 10 கட்டு வந்துச்சு")
        expect("Coriander bunch", "5", "kothamalli 5 kattu vandhuchu"); expect("Mint bunch", "5", "புதினா 5 கட்டு வந்துச்சு")
        // A pack said with "pack": that many packs.
        expect("Tomato 250 g", "10", "thakkali 250 gram pack 10 vandhuchu"); expect("Tomato 250 g", "10", "தக்காளி 250 கிராம் பாக்கெட் 10 வந்துச்சு")
        // A weight that is also a pack, no count said: Kai asks loose or pack — nothing drafted before the answer.
        expect(null, null, "thakkali arai kilo vandhuchu"); expect(null, null, "தக்காளி அரை கிலோ வந்துச்சு")
        expect("Tomato", "0.5", "thakkali arai kilo vandhuchu", "loose"); expect("Tomato", "0.5", "தக்காளி அரை கிலோ வந்துச்சு", "லூஸ்")
        expect("Tomato 500 g", "4", "thakkali arai kilo vandhuchu", "4 packet"); expect("Tomato 500 g", "4", "தக்காளி அரை கிலோ வந்துச்சு", "4 பாக்கெட்")
        expect("Onion", "1", "vengayam 1 kilo vandhuchu", "loose")
        // A unit Kai doesn't know ("thattu", a tray) is asked — never taken as kilos.
        expect(null, null, "thakkali 5 thattu vandhuchu"); expect(null, null, "தக்காளி 5 தட்டு வந்துச்சு")
        assertTrue("${fails.size} fails:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun aVegetableTheShopDoesNotKeepIsNeverAnotherOne() {
        // Artichoke, knol-khol (நூக்கல்), "keerai" alone (many greens): nothing drafted on some other produce.
        for (line in listOf("Artichoke 5 kilo vandhuchu", "nookal 5 kilo vandhuchu", "நூக்கல் 5 கிலோ வந்துச்சு", "keerai 5 kilo vandhuchu", "கீரை 5 கிலோ வந்துச்சு")) {
            val (got, _, reply) = moved(line)
            assertTrue("[$line] → $got — $reply", got == null)
        }
    }
}
