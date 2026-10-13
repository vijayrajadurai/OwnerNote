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
 * A medical shop's products, any of them, typed and as the mic writes them (owner, 13 Oct 2026: "Medical la yentha product sonalum
 * add panni kuma mic and text la"): tablets by the strip, syrups, ointments, injections, ORS, bandages, gloves, masks, sprays,
 * syringes. A new one is added in the chat (its pack size, batch and expiry asked; "Dolo 650 10 strip" is 10 strips of Dolo 650,
 * never 650 strips); one the shop has gets its stock draft, and its stock is then asked — the same typed and spoken. Nothing is
 * written before Confirm.
 */
class KaiMedicalShopTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)
    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** name, unit, conv, typed add line, spoken add line, qty added in base unit */
    data class P(val name: String, val unit: String, val conv: Map<String, String>, val typed: String, val spoken: String, val delta: String)

    private val products = listOf(
        P("Dolo 650", "TABLET", mapOf("STRIP" to "15"), "Dolo 650 10 strip add pannu", "டோலோ 650 10 ஸ்ட்ரிப் ஆட் பண்ணு", "150"),
        P("Crocin", "TABLET", mapOf("STRIP" to "15"), "Crocin 5 strip add pannu", "குரோசின் 5 ஸ்ட்ரிப் ஆட் பண்ணு", "75"),
        P("Benadryl Syrup", "BOTTLE", emptyMap(), "Benadryl syrup 10 bottle add pannu", "பெனட்ரில் சிரப் 10 பாட்டில் ஆட் பண்ணு", "10"),
        P("Betadine Ointment", "TUBE", emptyMap(), "Betadine ointment 5 tube add pannu", "பீட்டாடின் ஆயின்மென்ட் 5 டியூப் ஆட் பண்ணு", "5"),
        P("ORS", "PACKET", emptyMap(), "ORS 20 packet add pannu", "ஓஆர்எஸ் 20 பாக்கெட் ஆட் பண்ணு", "20"),
        P("Insulin Injection", "VIAL", emptyMap(), "Insulin injection 5 vial add pannu", "இன்சுலின் இன்ஜெக்ஷன் 5 வயல் ஆட் பண்ணு", "5"),
        P("Cotton Bandage", "ROLL", emptyMap(), "Cotton bandage 10 roll add pannu", "காட்டன் பேண்டேஜ் 10 ரோல் ஆட் பண்ணு", "10"),
        P("Azithromycin 500", "TABLET", mapOf("STRIP" to "3"), "Azithromycin 500 3 strip add pannu", "அசித்ரோமைசின் 500 3 ஸ்ட்ரிப் ஆட் பண்ணு", "9"),
        P("Pantoprazole", "TABLET", mapOf("STRIP" to "10"), "Pantoprazole 10 strip add pannu", "பான்டோபிரசோல் 10 ஸ்ட்ரிப் ஆட் பண்ணு", "100"),
        P("Amoxicillin Capsule", "CAPSULE", mapOf("STRIP" to "10"), "Amoxicillin capsule 10 strip add pannu", "அமோக்ஸிசிலின் கேப்சூல் 10 ஸ்ட்ரிப் ஆட் பண்ணு", "100"),
        P("Hand Sanitizer", "BOTTLE", emptyMap(), "Hand sanitizer 6 bottle add pannu", "ஹேண்ட் சானிடைசர் 6 பாட்டில் ஆட் பண்ணு", "6"),
        P("Surgical Gloves", "PCS", mapOf("BOX" to "100"), "Surgical gloves 2 box add pannu", "சர்ஜிக்கல் கிளவுஸ் 2 பாக்ஸ் ஆட் பண்ணு", "200"),
        P("Face Mask", "PCS", mapOf("BOX" to "50"), "Face mask 5 box add pannu", "ஃபேஸ் மாஸ்க் 5 பாக்ஸ் ஆட் பண்ணு", "250"),
        P("Volini Spray", "PCS", emptyMap(), "Volini spray 4 add pannu", "வொலினி ஸ்ப்ரே 4 ஆட் பண்ணு", "4"),
        P("Digene", "BOTTLE", emptyMap(), "Digene 5 bottle add pannu", "டைஜீன் 5 பாட்டில் ஆட் பண்ணு", "5"),
        P("Cetirizine", "TABLET", mapOf("STRIP" to "10"), "Cetirizine 10 strip add pannu", "செட்ரிசின் 10 ஸ்ட்ரிப் ஆட் பண்ணு", "100"),
        P("Thermometer", "PCS", emptyMap(), "Thermometer 2 add pannu", "தெர்மாமீட்டர் 2 ஆட் பண்ணு", "2"),
        P("Glucon D", "PACKET", emptyMap(), "Glucon D 10 packet add pannu", "குளுக்கான் டி 10 பாக்கெட் ஆட் பண்ணு", "10"),
        P("Vicks", "PCS", emptyMap(), "Vicks 12 add pannu", "விக்ஸ் 12 ஆட் பண்ணு", "12"),
        P("Eye Drops", "BOTTLE", emptyMap(), "Eye drops 10 bottle add pannu", "ஐ டிராப்ஸ் 10 பாட்டில் ஆட் பண்ணு", "10"),
        P("Syringe", "PCS", emptyMap(), "Syringe 100 add pannu", "சிரிஞ்ச் 100 ஆட் பண்ணு", "100"),
        P("Metformin 500", "TABLET", mapOf("STRIP" to "10"), "Metformin 500 20 strip add pannu", "மெட்ஃபார்மின் 500 20 ஸ்ட்ரிப் ஆட் பண்ணு", "200"),
    )

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")
    private fun confirmOf(t: KaiTurn) = t.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") }

    /** Plays a new product's chat to its Confirm, answering each question as an owner would; the product saved (null: none). */
    private fun addNew(inv: Inventory, line: String, p: P): Pair<com.shopai.app.brain.tools.NewProduct?, List<String>> {
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
        var t = runBlocking { kai.ask(line) }
        val log = mutableListOf("> $line", "  ${all(t)}")
        var n = 0
        val asked = mutableSetOf<String>()
        while (confirmOf(t) == null && n++ < 10) {
            val q = t.reply.text
            val a = when {
                q.contains("Batch") -> "B1".also { asked += "batch" }
                q.contains("Expiry") -> "12/2027".also { asked += "expiry" }
                Regex("""1 \S+-?(la|ல)""").containsMatchIn(q) || q.contains("evlo tablets") || q.contains("எத்தனை") || q.contains("evlo pieces") -> p.conv.values.firstOrNull() ?: "1"
                q.contains("-aa,") || q.contains("-ஆ,") -> "pieces"
                q.contains("skip") || q.contains("purchase") || q.contains("selling") || q.contains("rate") || q.contains("விலை") -> "skip"
                else -> null
            } ?: break
            t = runBlocking { kai.ask(a) }
            log += "> $a"; log += "  ${all(t)}"
        }
        val c = confirmOf(t) ?: return null to log
        if (inv.created.isNotEmpty()) return null to log + "saved before Confirm"
        runBlocking { kai.act(c.action, KaiLang.TANGLISH) }
        return inv.created.singleOrNull() to log
    }

    @Test fun anyNewMedicalProductTypedAndSpoken() {
        val fails = mutableListOf<String>()
        for (p in products) for (line in listOf(p.typed, p.spoken)) {
            val (cr, log) = addNew(Inventory(), line, p)
            // Said aloud, "ORS" and "Digene" don't tell they are medicines in an empty shop (see aMedicalShopAsksBatchOfAnyName).
            val batchNeeded = line == p.typed || p.name !in setOf("ORS", "Digene")
            if (cr == null || cr.openingQty?.compareTo(BigDecimal(p.delta)) != 0 || (batchNeeded && (cr.batchNo != "B1" || cr.expiry != LocalDate.of(2027, 12, 31))))
                fails += "[$line] → ${cr?.let { "${it.name} ${it.openingQty} ${it.unit} batch=${it.batchNo} exp=${it.expiry}" }}\n" + log.joinToString("\n")
        }
        assertTrue(fails.joinToString("\n\n"), fails.isEmpty())
    }

    @Test fun theStrengthIsPartOfTheName() {
        for (line in listOf("Dolo 650 10 strip add pannu", "டோலோ 650 10 ஸ்ட்ரிப் ஆட் பண்ணு", "Metformin 500 20 strip add pannu")) {
            val (cr, log) = addNew(Inventory(), line, products.first { it.typed == line || it.spoken == line })
            assertTrue("$line → $cr\n$log", cr != null && cr.name.endsWith(" 650").or(cr.name.endsWith(" 500")) && cr.secondaryUnit == "STRIP")
        }
    }

    @Test fun aMedicalShopAsksBatchOfAnyName() {
        // The shop already keeps a medicine by batch: any new name said aloud is a medicine too — its batch / expiry asked.
        for (p in products.filter { it.name in setOf("ORS", "Digene") }) {
            val inv = Inventory()
            inv.items += Inventory.Item("x1", "Paracetamol", "TABLET", BigDecimal("100"), mapOf("STRIP" to BigDecimal("10")), null, null, "Medical", null, batchTracked = true)
            val (cr, log) = addNew(inv, p.spoken, p)
            assertTrue("${p.spoken} → $cr\n$log", cr != null && cr.batchNo == "B1" && cr.expiry == LocalDate.of(2027, 12, 31))
        }
    }

    @Test fun aProductTheShopHasTypedAndSpoken() {
        val fails = mutableListOf<String>()
        for (p in products) for ((line, ask) in listOf(p.typed to "${p.name} evlo stock iruku", p.spoken to (p.spoken.substringBefore(Regex("\\d").find(p.spoken)!!.value).trim() + " எவ்வளவு ஸ்டாக் இருக்கு"))) {
            val inv = Inventory()
            products.forEachIndexed { i, x -> inv.items += Inventory.Item("m$i", x.name, x.unit, BigDecimal("50"), x.conv.mapValues { BigDecimal(it.value) }, null, null, "Medical", null) }
            val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
            var t = runBlocking { kai.ask(line) }
            // No unit said ("Syringe 100"): the unit is asked, never guessed — the owner says it.
            if (confirmOf(t) == null && (t.reply.text.contains("-aa,") || t.reply.text.contains("-ஆ,"))) t = runBlocking { kai.ask("pieces") }
            if (inv.items.any { it.stock.compareTo(BigDecimal("50")) != 0 }) { fails += "[$line] changed before Confirm"; continue }
            val c = confirmOf(t)
            if (c != null) runBlocking { kai.act(c.action, KaiLang.TANGLISH) }
            val want = BigDecimal("50") + BigDecimal(p.delta)
            val after = inv.item(p.name).stock
            val others = inv.items.filter { it.name != p.name && it.stock.compareTo(BigDecimal("50")) != 0 }.map { it.name }
            if (after.compareTo(want) != 0 || others.isNotEmpty()) fails += "[$line] ${p.name} ${after.toPlainString()}, want ${want.toPlainString()}, others $others — ${all(t)}"
            val a = runBlocking { kai.ask(ask) }
            if (!all(a).contains(want.toPlainString())) fails += "[$ask] ${all(a)}"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
