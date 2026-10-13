package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.BatchFact
import com.shopai.app.brain.tools.KaiExpiry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Stock about to expire (owner, 13 Oct 2026), for the shops that track it — medical, grocery, bakery & sweets, cosmetics, seeds /
 * fertilizer / pesticides, dairy & frozen, pet food, paint & adhesives, catering, FMCG wholesale: "expiry aagapora stock ellam sollu
 * / kudu" typed and spoken, a window ("intha vaaram", "innaikku", "expired"), one product, FEFO (which batch first), and the daily
 * alert's text. Read-only from the batch records; nothing written; what has no batch record isn't in it.
 */
class KaiExpiryTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)
    private val today: LocalDate = now.toLocalDate()

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private fun b(product: String, batch: String, inDays: Long, qty: String, unit: String) = BatchFact(product, batch, today.plusDays(inDays), BigDecimal(qty), unit)

    /** One batch-tracked product from each kind of shop, with expiries from 3 days ago to 4 months ahead. */
    private val batches = listOf(
        b("Paracetamol", "P1", 20, "40", "STRIP"), b("Paracetamol", "P2", 120, "60", "STRIP"),       // medical
        b("Cough Syrup", "S7", -3, "6", "BOTTLE"),                                                   // medical, expired
        b("Milk", "M2", 1, "20", "PACK"), b("Milk", "M3", 3, "30", "PACK"),                         // grocery / dairy
        b("Bread", "B5", 0, "12", "PCS"),                                                            // grocery, today
        b("Cream Cake", "C1", 2, "4", "PCS"),                                                        // bakery
        b("Sunscreen", "SN4", 25, "10", "PCS"),                                                      // cosmetics
        b("Paddy Seeds", "PS9", 6, "15", "PACK"),                                                    // seeds
        b("Pesticide", "PT2", 90, "8", "BOTTLE"),                                                    // pesticides, far
        b("Paneer", "PN1", -1, "3", "KG"),                                                           // dairy, expired
        b("Dog Food", "DF3", 40, "5", "BAG"),                                                        // pet food
        b("Fevicol", "FV2", 15, "9", "KG"),                                                          // adhesives
        b("Tomato Sauce", "TS6", 10, "24", "BOTTLE"),                                                // catering
        b("Mango Juice", "J8", 5, "48", "PCS"),                                                      // FMCG wholesale
    )

    private fun kai(): Pair<KaiAgent, Inventory> {
        val inv = Inventory()
        batches.map { it.product }.distinct().forEachIndexed { i, n ->
            inv.items += Inventory.Item("e$i", n, batches.first { it.product == n }.unit, batches.filter { it.product == n }.fold(BigDecimal.ZERO) { a, x -> a + x.qty }, emptyMap(), null, null, "Shop", null)
        }
        inv.batches += batches
        return KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now }) to inv
    }

    private fun ask(q: String): Pair<String, Inventory> {
        val (k, inv) = kai()
        val t = runBlocking { k.ask(q) }
        return (t.reply.text + "\n" + t.card?.lines.orEmpty().joinToString("\n")) to inv
    }

    private fun shows(text: String, vararg batch: String) = batch.filter { !text.contains("Batch $it ") }
    private fun hides(text: String, vararg batch: String) = batch.filter { text.contains("Batch $it ") }

    @Test fun everythingExpiringSoonTypedAndSpoken() {
        // Within 30 days (and the expired ones): not Paracetamol P2 (120 days), Pesticide (90), Dog Food (40).
        for (q in listOf("expiry aagapora stock ellam sollu", "expiry aagapora stock kudu", "Expiry aagura medicine list kudu", "best before mudiyura items enna",
                "எக்ஸ்பைரி ஆகப்போற ஸ்டாக் எல்லாம் சொல்லு", "காலாவதி ஆகப்போற ஸ்டாக் குடு")) {
            val (text, inv) = ask(q)
            assertTrue("$q → missing ${shows(text, "P1", "S7", "M2", "M3", "B5", "C1", "SN4", "PS9", "PN1", "FV2", "TS6", "J8")}\n$text",
                shows(text, "P1", "S7", "M2", "M3", "B5", "C1", "SN4", "PS9", "PN1", "FV2", "TS6", "J8").isEmpty())
            assertTrue("$q → too far ${hides(text, "P2", "PT2", "DF3")}", hides(text, "P2", "PT2", "DF3").isEmpty())
            assertTrue("$q wrote", inv.moves.isEmpty() && inv.created.isEmpty())
        }
    }

    @Test fun theWindowSaid() {
        ask("intha vaaram expiry aagura stock enna").first.let { t ->
            assertTrue(t, shows(t, "M2", "M3", "B5", "C1", "PS9", "J8", "S7", "PN1").isEmpty() && hides(t, "TS6", "FV2", "P1", "SN4").isEmpty())
        }
        ask("innaikku expiry aagura stock").first.let { t -> assertTrue(t, shows(t, "B5", "S7", "PN1").isEmpty() && hides(t, "M2", "C1").isEmpty()) }
        // Only what has already expired.
        for (q in listOf("expired stock enna", "expire aachu stock list kudu", "எக்ஸ்பைர் ஆச்சு ஸ்டாக் சொல்லு")) {
            val t = ask(q).first
            assertTrue("$q → $t", shows(t, "S7", "PN1").isEmpty() && hides(t, "B5", "M2", "P1").isEmpty())
        }
    }

    @Test fun oneProduct() {
        ask("Milk expiry eppo").first.let { t -> assertTrue(t, shows(t, "M2", "M3").isEmpty() && hides(t, "B5", "P1", "C1").isEmpty()) }
        ask("Paracetamol expiry list").first.let { t -> assertTrue(t, shows(t, "P1").isEmpty() && hides(t, "M2").isEmpty()) }
    }

    @Test fun fefoTheBatchToSellFirst() {
        for (q in listOf("endha batch first vikkanum", "FEFO list kudu")) {
            val t = ask(q).first
            // Paracetamol: P1 (20 days) before P2; Milk: M2 before M3 — one line each product.
            assertTrue("$q → $t", shows(t, "P1", "M2").isEmpty() && hides(t, "P2", "M3").isEmpty())
        }
    }

    @Test fun nothingDueAndNotAnExpiryQuestion() {
        val (k, _) = Inventory().let { inv -> KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now }) to inv }
        assertTrue(runBlocking { k.ask("expiry aagapora stock sollu") }.reply.text.contains("edhuvum illa"))
        // A product added with its expiry is not the list.
        assertTrue(!KaiExpiryAsks.asks("Paracetamol 10 strip add pannu batch B1 expiry 12/2026"))
        assertTrue(!KaiExpiryAsks.asks("Kumar evlo tharanum"))
    }

    @Test fun theDailyAlert() {
        val a = KaiExpiry.alert(batches, today, KaiLang.TANGLISH)!!
        // Within a week or expired: S7, PN1, B5, M2, C1, M3, J8, PS9 = 8; the 5 soonest shown, then "+ innum 3".
        assertEquals(8, a.count)
        assertTrue(a.title, a.title.contains("8") && a.title.contains("2 expire aachu"))
        assertTrue(a.body, a.body.lines().first().startsWith("Cough Syrup") && a.body.contains("+ innum 3"))
        assertTrue(a.body, !a.body.contains("Tomato Sauce") && !a.body.contains("Pesticide"))
        // Nothing within a week: no alert at all.
        assertNull(KaiExpiry.alert(listOf(b("Pesticide", "PT2", 90, "8", "BOTTLE")), today, KaiLang.TANGLISH))
        assertTrue(KaiExpiry.alert(batches, today, KaiLang.TAMIL)!!.title.contains("expire ஆச்சு"))
    }

    private object KaiExpiryAsks { fun asks(t: String) = com.shopai.app.brain.tools.KaiCommands.asksExpiry(t) }
}
