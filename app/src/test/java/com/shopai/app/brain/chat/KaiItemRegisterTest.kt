package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiCommands
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.RegisterLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The item register (owner, 21 Oct 2026: "item wise purchase and sales register aaganum, report ketta sollanum"), with
 * Murugan Store's stock: every purchase / sales bill line summed item by item, or one product's lines bill by bill — typed and
 * as the ta-IN mic writes it. Principles: 3 (only from the books' bill lines, said when there are none), 2 (purchase ≠ sales),
 * 5 (a question saves nothing), 1 (typed = mic).
 */
class KaiItemRegisterTest {
    private val now = LocalDateTime.of(2026, 10, 21, 18, 0)
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot()
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory, val bought: List<RegisterLine>, val sold: List<RegisterLine>, val services: Boolean, val readable: Boolean) : KaiTools by inv {
        val asked = mutableListOf<Triple<Boolean, LocalDate, LocalDate>>()
        override suspend fun itemRegister(purchase: Boolean, from: LocalDate, to: LocalDate): List<RegisterLine>? {
            if (!readable) return null
            asked += Triple(purchase, from, to)
            return (if (purchase) bought else sold).filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        }
        override suspend fun sellsServices() = services
    }

    private fun line(date: LocalDate, no: String, party: String?, product: String, qty: String, rate: String, back: Boolean = false) =
        RegisterLine(date, no, party, product, BigDecimal(qty), "PCS", BigDecimal(qty), "PCS", BigDecimal(rate), BigDecimal(qty).multiply(BigDecimal(rate)), back)

    /** Murugan Store, as the owner gave it: Cinthol 100g / 200g, Red Dabur paste 100g / 50g, Rin soap. */
    private val bought = listOf(
        line(d(10, 20), "PUR-0001", "Sri Balaji Agencies", "Cinthol 100g", "50", "45"),
        line(d(10, 20), "PUR-0001", "Sri Balaji Agencies", "Cinthol 200g", "100", "90"),
        line(d(10, 20), "PUR-0001", "Sri Balaji Agencies", "Red Dabur Paste 100g", "120", "30"),
        line(d(10, 20), "PUR-0001", "Sri Balaji Agencies", "Red Dabur Paste 50g", "120", "10"),
        line(d(10, 21), "PUR-0002", "Lakshmi Traders", "Rin Soap", "150", "8"),
        line(d(9, 12), "PUR-0000", "Lakshmi Traders", "Rin Soap", "50", "8"),
    )
    private val sold = listOf(
        line(d(10, 21), "INV-0001", null, "Cinthol 200g", "4", "104"),
        line(d(10, 21), "INV-0001", null, "Rin Soap", "10", "12"),
        line(d(10, 21), "INV-0002", "Kumar", "Red Dabur Paste 100g", "3", "38"),
        line(d(10, 21), "CN-0001", null, "Rin Soap", "1", "12", back = true),
    )

    private inner class Shop(bought: List<RegisterLine> = this.bought, sold: List<RegisterLine> = this.sold, services: Boolean = false, readable: Boolean = true) {
        val inv = Inventory().apply {
            items.clear()
            items += Inventory.Item("p1", "Cinthol 100g", "PCS", BigDecimal("50"), emptyMap(), BigDecimal("45"), BigDecimal("52"), "FMCG", "100 g")
            items += Inventory.Item("p2", "Cinthol 200g", "PCS", BigDecimal("96"), emptyMap(), BigDecimal("90"), BigDecimal("104"), "FMCG", "200 g")
            items += Inventory.Item("p3", "Red Dabur Paste 100g", "PCS", BigDecimal("117"), emptyMap(), BigDecimal("30"), BigDecimal("38"), "FMCG", "100 g")
            items += Inventory.Item("p4", "Red Dabur Paste 50g", "PCS", BigDecimal("120"), emptyMap(), BigDecimal("10"), BigDecimal("12"), "FMCG", "50 g")
            items += Inventory.Item("p5", "Rin Soap", "PCS", BigDecimal("141"), emptyMap(), BigDecimal("8"), BigDecimal("12"), "FMCG", null)
        }
        val tools = Tools(inv, bought, sold, services, readable)
        val kai = KaiAgent(KaiBusinessBrain(Books(), today = { now.toLocalDate() }), Books(), tools, { now })
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    /** The owner's line typed, then as the mic writes it (a fresh shop each time): both must pass [check]. */
    private fun bothWays(line: String, check: (String, KaiTurn, Shop) -> Unit) {
        listOfNotNull(line, KaiMicForm.of(line)).forEach { said -> Shop().let { s -> check(said, s.say(said), s) } }
    }

    @Test fun purchaseRegisterItemByItem() = bothWays("purchase register") { said, t, s ->
        val a = all(t)
        assertTrue("$said → $a", a.contains("₹17,250"))
        assertTrue("$said → $a", a.contains("Cinthol 200g — 100 PCS — ₹9,000"))
        assertTrue("$said → $a", a.contains("Rin Soap — 150 PCS — ₹1,200"))
        // This month only: September's Rin Soap bill is not in it.
        assertFalse("$said → $a", a.contains("200 PCS"))
        assertTrue(s.tools.asked.all { it.first })
    }

    @Test fun salesRegisterCountsReturnsAgainst() = bothWays("sales register") { said, t, s ->
        val a = all(t)
        // 416 + 120 + 114 − 12 (Rin Soap returned)
        assertTrue("$said → $a", a.contains("₹638"))
        assertTrue("$said → $a", a.contains("Rin Soap — 9 PCS — ₹108"))
        assertFalse("$said → $a", a.contains("Cinthol 100g"))
        assertTrue(s.tools.asked.none { it.first })
    }

    @Test fun itemWiseReportHasBothSides() = bothWays("item wise report") { said, t, _ ->
        val a = all(t)
        assertTrue("$said → $a", a.contains("₹17,250") && a.contains("₹638"))
        assertTrue("$said → $a", Regex("""Cinthol 200g — \S+ 100 PCS ₹9,000 · \S+ 4 PCS ₹416""").containsMatchIn(a))
    }

    @Test fun otherWaysToAsk() {
        listOf("item wise purchase report", "purchase report", "indha maasam enna enna vaanginen", "kolmudhal register").forEach { line ->
            bothWays(line) { said, t, _ -> assertTrue("$said → ${all(t)}", all(t).contains("₹17,250")) }
        }
        listOf("item wise sales report", "sales item wise kaattu").forEach { line ->
            bothWays(line) { said, t, _ -> assertTrue("$said → ${all(t)}", all(t).contains("₹638")) }
        }
    }

    @Test fun oneProductBillByBill() {
        bothWays("Cinthol evlo vaanginen") { said, t, _ ->
            val a = all(t)
            // Both Cinthol packs, their bills: 50 × ₹45 + 100 × ₹90.
            assertTrue("$said → $a", a.contains("150 PCS") && a.contains("₹11,250"))
            assertTrue("$said → $a", a.contains("PUR-0001 · Sri Balaji Agencies"))
        }
        bothWays("Rin Soap sales evlo") { said, t, _ ->
            val a = all(t)
            assertTrue("$said → $a", a.contains("9 PCS — ₹108"))
            assertTrue("$said → $a", a.contains("CN-0001"))
        }
    }

    @Test fun lastMonthIsLastMonth() = bothWays("pona maasam purchase register") { said, t, s ->
        val a = all(t)
        assertTrue("$said → $a", a.contains("Rin Soap — 50 PCS — ₹400"))
        assertEquals(d(9, 1), s.tools.asked.first().second)
    }

    @Test fun nothingRecordedIsSaid() {
        val s = Shop(bought = emptyList(), sold = emptyList())
        val a = all(s.say("purchase register"))
        assertTrue(a, a.contains("purchase bill-la item edhuvum record-la illa"))
        val b = all(s.say("Cinthol 200g sales evlo"))
        assertTrue(b, b.contains("Cinthol 200g") && b.contains("illa"))
        assertFalse(b, b.contains("₹"))
    }

    @Test fun booksUnreadableIsSaid() {
        val a = all(Shop(readable = false).say("sales register"))
        assertTrue(a, a.contains("verify panna mudiyala"))
    }

    @Test fun tamilMicGetsTamil() {
        val s = Shop()
        val a = all(s.say("பர்சேஸ் ரெஜிஸ்டர்"))
        assertTrue(a, a.contains("₹17,250") && a.contains("ஓனர்"))
        val b = all(Shop().say("ஐட்டம் வைஸ் சேல்ஸ் ரிப்போர்ட்"))
        assertTrue(b, b.contains("₹638"))
    }

    /** "report" alone gets "Endha report?"; "purchase" to it is the purchase register, typed and by mic. */
    @Test fun reportThenPurchase() {
        listOf("report" to "purchase", "ரிப்போர்ட்" to "பர்சேஸ்").forEach { (first, then) ->
            val s = Shop()
            val q = all(s.say(first))
            assertTrue(q, q.contains("purchase"))
            val a = all(s.say(then))
            assertTrue("$then → $a", a.contains("₹17,250"))
        }
    }

    /** A question saves nothing: no draft, no stock change. */
    @Test fun aQuestionSavesNothing() {
        val s = Shop()
        listOf("purchase register", "sales register", "item wise report", "Cinthol evlo vaanginen").forEach { s.say(it) }
        assertTrue(s.inv.moves.isEmpty())
        assertTrue(s.inv.payments.isEmpty())
        assertTrue(s.inv.logs.none { it.second == ActionStatus.DRAFT || it.second == ActionStatus.CONFIRMED })
    }

    /** Lines that are not this question stay what they were. */
    @Test fun notARegisterQuestion() {
        listOf("Cinthol 100g 10 vandhuchu", "register 5 vaanginen", "purchase bill podu", "indha maasam purchase evlo", "sales evlo",
            "Kumar 500 kuduthan", "register stock evlo", "sales report").forEach { line ->
            val ask = KaiCommands.asksItemRegister(line)
            if (line == "sales report") assertTrue(line, ask?.plain == true) else assertNull(line, ask)
        }
        // Words that name no product of the shop are not one product's register ("Kumar kitta evlo vaanginen" is about Kumar).
        val k = Shop()
        k.say("Kumar kitta evlo vaanginen")
        assertTrue(k.tools.asked.isEmpty() || all(k.say("Kumar kitta evlo vaanginen")).let { !it.contains("register") && !it.contains("PUR-") })
        // A stock line is still a stock draft.
        val s = Shop()
        val a = all(s.say("Cinthol 100g 10 vandhuchu"))
        assertFalse(a, a.contains("register"))
        // "sales report" in a shop that sells services stays the Daily Sales Note's.
        val salon = Shop(services = true)
        val b = all(salon.say("sales report"))
        assertFalse(b, b.contains("sales register"))
        assertTrue(salon.tools.asked.isEmpty())
        // In a goods shop it is the sales register.
        assertTrue(all(Shop().say("sales report")).contains("₹638"))
    }
}
