package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Owner, 22 Oct 2026: "100 box noodles Kumaran store ku / kadaiku / Malligai ku sale pannitan / vithutan / vithachu / kuduthurukan" —
 * the stock goes down (one bill: stock and the shop's balance together, after Confirm); then "Kumaran store ku enna vithan / evlo
 * vithan" is answered from the books. Wholesale or retail, every line typed and as the mic writes it.
 */
class KaiSaleToShopTest {
    private val now = LocalDateTime.of(2026, 10, 22, 11, 0)

    private class Books(val inv: Inventory) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = inv.parties.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.balance.toDouble(), "2026-10-19T00:00:00Z") },
            suppliers = inv.parties.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.balance.toDouble(), "2026-10-19T00:00:00Z") })
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private inner class Shop {
        val inv = Inventory().apply {
            items.clear(); parties.clear()
            items += Inventory.Item("p1", "Noodles", "PCS", BigDecimal("9600"), mapOf("BOX" to BigDecimal("48")), BigDecimal("10"), BigDecimal("12"), "FMCG", null)
            items += Inventory.Item("p2", "Rice", "KG", BigDecimal("500"), mapOf("BAG" to BigDecimal("25")), BigDecimal("50"), BigDecimal("60"), "Grocery", null)
            parties += PartyMatch("c1", "Kumaran Store", true, null, BigDecimal.ZERO)
            parties += PartyMatch("c2", "Malligai Kadai", true, null, BigDecimal("500"))
            parties += PartyMatch("s1", "ABC Traders", false, null, BigDecimal.ZERO)
        }
        val books = Books(inv)
        val kai = KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }), books, inv, { now })
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }
        fun stock(name: String) = inv.items.first { it.name == name }.stock

        /** [line], answering "credit-aa cash-aa?" with credit, then Confirm. */
        fun sell(line: String): KaiTurn {
            val before = inv.moves.size to inv.bills.size
            var t = say(line)
            if (t.reply.text.contains("cash-aa") || t.reply.text.contains("cash-ஆ")) t = say(if (line.any { it in '஀'..'௿' }) "கிரெடிட்" else "credit")
            val confirm = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock } ?: error("«$line» no Confirm: ${all(t)}")
            assertEquals("«$line» stock changed before Confirm", before, inv.moves.size to inv.bills.size)
            return runBlocking { kai.act(confirm.action, KaiLang.TANGLISH)!! }
        }
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" ; ")).replace("\n", " ")
    private fun bothWays(line: String) = listOfNotNull(line, KaiMicForm.of(line))

    @Test fun goodsSoldToAShopSaidAnyWayTakeTheStockDown() {
        for ((typed, shop) in listOf(
            "100 box noodles Kumaran store ku sale pannitan" to "Kumaran Store",
            "100 box noodles Kumaran store ku vithuten" to "Kumaran Store",
            "Kumaran store ku 100 box noodles vithachu" to "Kumaran Store",
            "Kumaran store ku 100 box noodles vithutan" to "Kumaran Store",
            "Kumaran kadaiku 100 box noodles kuduthurukan" to "Kumaran Store",
            "Malligai ku 100 box noodles kuduthurukken" to "Malligai Kadai",
            "Malligai kadaiku 100 box noodles sale pannen" to "Malligai Kadai",
        )) for (line in bothWays(typed)) {
            val s = Shop()
            val done = s.sell(line)
            assertEquals("«$line» ${all(done)}", 0, BigDecimal("4800").compareTo(s.stock("Noodles")))
            assertEquals("«$line»", shop, s.inv.bills.single().partyName)
            assertFalse(s.inv.bills.single().purchase)
        }
    }

    @Test fun whatWasSoldToTheShopIsAnswered() {
        for (q in listOf("Kumaran store ku enna vithen", "Kumaran store ku evlo vithen", "Kumaran kadaiku enna vithan", "Kumaran store ku enna kuduthen",
            "Kumaran store enna vaanginaanga", "Kumaran store ku indha maasam enna vithen")) for (line in bothWays(q)) {
            val s = Shop()
            // Before any sale: never a made-up figure.
            val none = s.say(line)
            assertFalse("«$line» ${all(none)}", all(none).contains("₹57,600") || all(none).contains("vithadhu"))
            s.sell("Kumaran store ku 100 box noodles vithen")
            s.sell("Kumaran store ku 2 bag rice vithen")
            val t = s.say(line)
            val a = all(t)
            assertTrue("«$line» $a", a.contains("Noodles") && a.contains("100 boxes") && a.contains("₹57,600"))
            assertTrue("«$line» $a", a.contains("Rice") && a.contains("50 kg") && a.contains("₹3,000"))
            assertTrue("«$line» $a", a.contains("₹60,600"))
        }
        // "nethu" (yesterday): nothing sold then.
        val s = Shop()
        s.sell("Kumaran store ku 100 box noodles vithen")
        assertTrue(all(s.say("Kumaran store ku nethu enna vithen")).contains("vikkala"))
        // "evlo kuduthen" is money, not goods.
        assertFalse(all(s.say("Kumaran store ku evlo kuduthen")).contains("vithadhu"))
    }

    @Test fun aShopNotInTheBooksIsAskedNeverLost() {
        for (line in bothWays("Selvi stores ku 5 box noodles vithen")) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("«$line» ${all(t)}", (t.reply.text.contains("customers-la illa") || t.reply.text.contains("customers-ல இல்ல")) &&
                t.card!!.buttons.any { it.action is KaiAction.BuyerCash })
            assertEquals(0, BigDecimal("9600").compareTo(s.stock("Noodles")))
        }
        // A word Kai can't read as a sale: nothing goes out.
        val s = Shop()
        s.say("Kumaran store ku 100 box noodles blah")
        assertTrue(s.inv.moves.isEmpty() && s.inv.bills.isEmpty())
    }

    @Test fun goodsBoughtFromASupplierAreAnsweredToo() {
        val s = Shop()
        var t = s.say("ABC Traders kitta 10 box noodles vaanginen")
        if (t.reply.text.contains("cash-aa")) t = s.say("credit")
        runBlocking { s.kai.act(t.card!!.buttons.first { it.action is KaiAction.ConfirmStock }.action, KaiLang.TANGLISH) }
        val a = all(s.say("ABC Traders kitta enna vaanginen"))
        assertTrue(a, a.contains("Noodles") && a.contains("10 boxes") && a.contains("₹4,800"))
    }
}
