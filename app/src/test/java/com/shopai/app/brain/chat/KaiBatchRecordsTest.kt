package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.BatchFact
import com.shopai.app.brain.tools.KaiSpokenWords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The batch / expiry said in chat is the books' own batch record (owner, 13 Oct 2026: "Fix pannu" — a medicine added by chat with
 * its batch and expiry must be in "expiry aagapora stock" and the daily alert). A product added with a batch / expiry is
 * batch-tracked; new stock of it asks its batch / expiry (or goes into the current batch on Confirm); stock out takes the batch
 * that expires first. Typed and spoken alike; nothing written before Confirm.
 */
class KaiBatchRecordsTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private fun r(s: String) = BigDecimal(s)

    /** Paracetamol kept in tablets (strip = 10), batch-tracked: P1 40 tablets expiring in 10 days, P2 60 in 5 months. */
    private fun shop(): Pair<KaiAgent, Inventory> {
        val inv = Inventory()
        inv.items += Inventory.Item("m1", "Paracetamol", "TABLET", r("100"), mapOf("STRIP" to r("10")), r("1"), r("2"), "Medical", null, batchTracked = true)
        inv.items += Inventory.Item("p1", "Colgate", "PCS", r("240"), mapOf("BOX" to r("48")), r("28"), r("35"), "FMCG", null)
        inv.batches += BatchFact("Paracetamol", "P1", now.toLocalDate().plusDays(10), r("40"), "TABLET")
        inv.batches += BatchFact("Paracetamol", "P2", now.toLocalDate().plusMonths(5), r("60"), "TABLET")
        return KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now }) to inv
    }

    private fun KaiAgent.say(q: String) = runBlocking { ask(q) }
    private fun KaiAgent.confirm(t: KaiTurn) = runBlocking { act(t.card!!.buttons.first { it.action is KaiAction.ConfirmStock }.action, KaiLang.TANGLISH) }!!
    private fun all(t: KaiTurn) = t.reply.text + "\n" + t.card?.lines.orEmpty().joinToString("\n")
    /** The line typed and as the mic writes it. */
    private fun both(typed: String) = listOfNotNull(typed, KaiMicForm.of(typed))

    @Test fun aMedicineAddedByChatIsInTheExpiryList() {
        for (first in listOf("paracetamol 30 strip add pannu", "பாராசிட்டமால் 30 ஸ்ட்ரிப் ஆட் பண்ணு")) {
            val inv = Inventory()
            val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
            kai.say(first); kai.say("10"); kai.say("B12")
            kai.say("10/2026")                                        // expires 31 Oct 2026 — within the next 30 days
            kai.say("skip")
            val summary = kai.say("skip")
            assertTrue("nothing before Confirm", inv.created.isEmpty() && inv.batches.isEmpty())
            runBlocking { kai.act(summary.card!!.buttons.first { it.label.contains("Confirm") || it.label.contains("உறுதி") }.action, KaiLang.TANGLISH) }
            assertTrue("batch-tracked ${inv.items}", inv.items.single().batchTracked)
            for (q in listOf("expiry aagapora stock sollu", "எக்ஸ்பைரி ஆகப்போற ஸ்டாக் சொல்லு")) {
                val text = all(kai.say(q))
                // (Spoken, the new product keeps the name as the mic wrote it.)
                assertTrue("$first / $q → $text", text.contains("— Batch B12 — 300 tablets"))
            }
        }
    }

    @Test fun aNewProductWithItsBatchInTheSameLine() {
        for (line in both("Dolo 10 strip add pannu batch D5 expiry 12/2027")) {
            val inv = Inventory()
            val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
            kai.say(line)
            val next = kai.say("10").reply.text
            assertTrue("$line → $next", !next.contains("Batch number") && !next.contains("Expiry date"))
            var t = kai.say("skip")
            if (t.card?.buttons?.any { it.label.contains("Confirm") || it.label.contains("உறுதி") } != true) t = kai.say("skip")
            runBlocking { kai.act(t.card!!.buttons.first { it.label.contains("Confirm") || it.label.contains("உறுதி") }.action, KaiLang.TANGLISH) }
            val p = inv.created.single()
            assertTrue("$line → $p", p.batchNo == "D5" && p.expiry == LocalDate.of(2027, 12, 31) && p.openingQty?.compareTo(r("100")) == 0)
        }
    }

    @Test fun newStockWithItsBatchSaid() {
        for (line in both("Paracetamol 20 strip vandhuchu batch B20 expiry 06/2027")) {
            val (kai, inv) = shop()
            val draft = kai.say(line)
            val text = all(draft)
            assertTrue("$line → $text", text.contains("Batch: B20") && text.contains("Expiry:") && !text.contains("Puthu batch-na"))
            assertTrue("nothing before Confirm", inv.moves.isEmpty() && inv.batchIns.isEmpty())
            kai.confirm(draft)
            assertEquals(listOf("Paracetamol" to com.shopai.app.brain.tools.StockBatch("B20", LocalDate.of(2027, 6, 30))), inv.batchIns)
            assertTrue(inv.batches.any { it.batchNo == "B20" && it.qty.compareTo(r("200")) == 0 })
        }
    }

    @Test fun newStockAsksItsBatchThenTakesTheAnswer() {
        for ((line, answer) in listOf(
            "Paracetamol 20 strip vandhuchu" to "batch B21 expiry 03/2027",
            "பாராசிட்டமால் 20 ஸ்ட்ரிப் வந்துச்சு" to "பேட்ச் பி 21 எக்ஸ்பைரி 03/2027",
        )) {
            val (kai, inv) = shop()
            val draft = kai.say(line)
            assertTrue("$line → ${draft.reply.text}", draft.reply.text.contains("batch number, expiry") || draft.reply.text.contains("batch number"))
            val again = kai.say(answer)
            val text = all(again)
            assertTrue("$answer → $text", text.contains("Batch: B21") && text.contains("Expiry: 03/2027"))
            assertTrue("nothing before Confirm", inv.moves.isEmpty())
            kai.confirm(again)
            assertEquals("B21", inv.batchIns.single().second.batchNo)
            assertEquals(LocalDate.of(2027, 3, 31), inv.batchIns.single().second.expiry)
        }
        // A short reply: the code alone, then the date alone, build the same batch.
        val (kai, inv) = shop()
        kai.say("Paracetamol 20 strip vandhuchu")
        assertTrue(all(kai.say("B22")).contains("Batch: B22"))
        val t = kai.say("08/2027")
        assertTrue(all(t), all(t).contains("Batch: B22") && all(t).contains("Expiry: 08/2027"))
        kai.confirm(t)
        assertEquals(com.shopai.app.brain.tools.StockBatch("B22", LocalDate.of(2027, 8, 31)), inv.batchIns.single().second)
    }

    @Test fun confirmWithoutABatchGoesIntoTheCurrentBatch() {
        val (kai, inv) = shop()
        val draft = kai.say("Paracetamol 20 tablet vandhuchu")
        kai.confirm(draft)
        assertTrue("no batch said", inv.batchIns.isEmpty())
        assertTrue(inv.item("Paracetamol").stock.compareTo(r("120")) == 0)
        // A correction ("illa 30") is still a correction, not a batch.
        val (kai2, _) = shop()
        kai2.say("Paracetamol 20 tablet vandhuchu")
        assertTrue(all(kai2.say("illa 30")).contains("30 tablets"))
    }

    @Test fun stockOutTakesTheBatchThatExpiresFirst() {
        val (kai, inv) = shop()
        kai.confirm(kai.say("Paracetamol 50 tablet damage"))
        // P1 (10 days) had 40: all of it, then 10 from P2.
        assertTrue(inv.batches.map { it.qty }.toString(), inv.batches.map { it.qty.toInt() } == listOf(0, 50))
        assertTrue(all(kai.say("expiry aagapora stock sollu")).let { !it.contains("Batch P1 ") })
    }

    @Test fun aProductNotKeptByBatchIsNotAsked() {
        for (line in both("Colgate 1 box vandhuchu")) {
            val (kai, _) = shop()
            val t = kai.say(line)
            assertTrue("$line → ${all(t)}", !all(t).contains("Batch") && !t.reply.text.contains("batch"))
        }
        // A batch / expiry said for it (toothpaste can expire — owner, 17 Oct 2026): the quantity is still 1 box, and on Confirm Colgate
        // is kept by batch from this stock on — its stock so far one batch with no expiry; the new 48 pieces batch B7, expiry 06/2027.
        for (line in both("Colgate 1 box vandhuchu batch B7 expiry 06/2027")) {
            val (kai, inv) = shop()
            val t = kai.say(line)
            assertTrue("$line → ${all(t)}", all(t).contains("Batch: B7") && all(t).contains("48 pieces") && Regex("""expiry-wise|expiry வாரியா""").containsMatchIn(t.reply.text))
            assertTrue("not before Confirm", inv.moves.isEmpty() && !inv.item("Colgate").batchTracked)
            kai.confirm(t)
            assertTrue(inv.item("Colgate").stock.compareTo(r("288")) == 0 && inv.item("Colgate").batchTracked && inv.batchIns.single().second.batchNo == "B7")
        }
    }

    @Test fun aBatchCodeSaidAloud() {
        assertEquals("batch B21", KaiSpokenWords.normalize(KaiSpokenWords.code("பேட்ச் பி 21")))
        assertEquals("BC20", KaiSpokenWords.code("பிசி20"))
        // Letters only become a code right before a number.
        assertEquals("பி ஸ்டாக்", KaiSpokenWords.code("பி ஸ்டாக்"))
    }
}
