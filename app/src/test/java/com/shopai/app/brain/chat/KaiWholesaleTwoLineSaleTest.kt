package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.data.model.BusinessKind
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Owner, 23 Oct 2026 — a wholesaler tells Kai "Mithun store ku 80 gram Colgate 20 piece", then "aprm 50 liter water bottle 10 can":
 * both go out of stock to Mithun Store (drafted, Confirm first; the sizes are the products' own names, never "80 grams sold"). Then,
 * the same day, the next day or days later, "Mithun store ku enna / evlo / ennaiku sale pannom" is answered from the books. Typed and
 * as the mic writes it.
 */
class KaiWholesaleTwoLineSaleTest {
    private class Books(val inv: Inventory) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = inv.parties.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.balance.toDouble(), "2026-10-19T00:00:00Z") },
            suppliers = inv.parties.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.balance.toDouble(), "2026-10-19T00:00:00Z") })
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private inner class Wholesaler {
        var now: LocalDateTime = LocalDateTime.of(2026, 10, 23, 11, 0)
        val inv = Inventory().apply {
            items.clear(); parties.clear()
            items += Inventory.Item("p1", "Colgate 80g", "PCS", BigDecimal("500"), emptyMap(), BigDecimal("38"), BigDecimal("45"), "FMCG", null)
            items += Inventory.Item("p2", "Water Bottle 50L", "CAN", BigDecimal("60"), emptyMap(), BigDecimal("60"), BigDecimal("80"), "Water", null)
            items += Inventory.Item("p3", "Colgate 150g", "PCS", BigDecimal("100"), emptyMap(), BigDecimal("70"), BigDecimal("85"), "FMCG", null)
            parties += PartyMatch("c1", "Mithun Store", true, null, BigDecimal.ZERO)
            billDay = LocalDate.of(2026, 10, 23)
        }
        private val tools = object : KaiTools by inv { override suspend fun businessKinds() = setOf(BusinessKind.WHOLESALE) }
        val books = Books(inv)
        val kai = KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }), books, tools, { now })
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }
        fun stock(name: String) = inv.items.first { it.name == name }.stock

        fun sell(line: String) {
            val before = inv.moves.size to inv.bills.size
            val t = say(line)
            val confirm = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock } ?: error("«$line» no Confirm: ${all(t)}")
            assertEquals("«$line» changed before Confirm", before, inv.moves.size to inv.bills.size)
            runBlocking { kai.act(confirm.action, KaiLang.TANGLISH) }
        }
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" ; ")).replace("\n", " ")
    private fun bothWays(line: String) = listOfNotNull(line, KaiMicForm.of(line))

    @Test fun twoLinesToMithunStoreTakeTheStockDown() {
        for (mic in listOf(false, true)) {
            val w = Wholesaler()
            fun way(l: String) = if (mic) KaiMicForm.of(l) ?: l else l
            w.sell(way("Mithun store ku 80 gram Colgate 20 piece"))
            w.sell(way("aprm 50 liter water bottle 10 can"))
            assertEquals("mic=$mic", 0, BigDecimal("480").compareTo(w.stock("Colgate 80g")))
            assertEquals("mic=$mic", 0, BigDecimal("50").compareTo(w.stock("Water Bottle 50L")))
            assertEquals("mic=$mic: the other Colgate is untouched", 0, BigDecimal("100").compareTo(w.stock("Colgate 150g")))
            assertEquals("mic=$mic", listOf("Mithun Store", "Mithun Store"), w.inv.bills.map { it.partyName })
            assertTrue("mic=$mic: on credit (wholesale)", w.inv.bills.all { it.credit })
        }
    }

    @Test fun whatHowMuchAndWhenAreAnsweredOnAnyDay() {
        for ((day, whenWord) in listOf(0L to listOf("innaikku", "இன்னைக்கு"), 1L to listOf("nethu", "நேத்து"), 6L to listOf("23"))) {
            for (q in listOf("Mithun store ku enna sale pannom", "Mithun store ku evlo sale pannom", "Mithun store ku ennaiku sale pannom")) for (line in bothWays(q)) {
                val w = Wholesaler()
                w.sell("Mithun store ku 80 gram Colgate 20 piece")
                w.sell("aprm 50 liter water bottle 10 can")
                w.now = LocalDateTime.of(2026, 10, 23, 18, 0).plusDays(day)
                val a = all(w.say(line))
                assertTrue("day+$day «$line» $a", a.contains("₹1,700"))
                if (q.contains("ennaiku")) assertTrue("day+$day «$line» $a", whenWord.any { a.contains(it) })
                else assertTrue("day+$day «$line» $a", a.contains("Colgate 80g") && a.contains("Water Bottle 50L"))
            }
        }
    }

    @Test fun aQuestionOrAnotherColgateIsNeverASale() {
        val w = Wholesaler()
        // A price question with a count: nothing goes out.
        w.say("Mithun store ku Colgate 80g 20 piece evlo")
        // "Colgate" alone with two Colgates in the shop: never guessed as one of them.
        w.say("Mithun store ku Colgate 20 piece")
        assertTrue(w.inv.moves.isEmpty() && w.inv.bills.isEmpty())
        // "apram" with nothing drafted before it: not a sale to anyone.
        val v = Wholesaler()
        v.say("aprm 50 liter water bottle 10 can")
        assertTrue(v.inv.bills.isEmpty())
    }
}
