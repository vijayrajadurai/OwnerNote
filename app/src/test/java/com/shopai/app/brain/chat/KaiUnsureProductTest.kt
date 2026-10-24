package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.KaiProductSound
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner (24 Oct 2026): "shop enna solraangalo adhaan varanum" — "டயர் 7" was once taken as "drive" (USB pen drive). A mic
 * word that only sounds near the shop's product words is asked about, with those products as buttons — never guessed into a
 * draft. Principles 3 (ask, don't guess), 5 (nothing drafted from a guess), 1 (the mic's Tamil).
 */
class KaiUnsureProductTest {
    private val now = LocalDateTime.of(2026, 10, 24, 12, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot()
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private fun shop(vararg names: String): Pair<KaiAgent, Inventory> {
        val inv = Inventory().apply {
            items.clear()
            names.forEachIndexed { i, n -> items += Inventory.Item("p$i", n, "PCS", BigDecimal("50"), emptyMap(), null, null, "Automobile", null) }
        }
        return KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now }) to inv
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ") + " | " +
        t.card?.buttons.orEmpty().joinToString(" | ") { it.label }).replace("\n", " ")
    private fun drafted(t: KaiTurn) = t.card?.buttons?.any { it.label.contains("Confirm") || it.label.contains("உறுதி") } == true

    private val noTyre = arrayOf("USB pen drive Small pack", "USB pen drive Medium pack", "Drive belt Small pack", "Drive belt Medium pack", "Rice 25 kg")

    @Test fun aWordOnlyNearAProductIsAskedNeverDrafted() {
        for (line in listOf("டயர் 7 ஆட் பண்ணு", "டயர் சின்ன பேக் 7 ஆட் பண்ணு")) {
            val (kai, inv) = shop(*noTyre)
            val t = runBlocking { kai.ask(line) }
            val a = all(t)
            assertEquals(a, KaiMood.CLARIFY, t.reply.mood)
            assertTrue(a, a.contains("“டயர்”-னு கேட்டேன் — எந்த product?"))
            assertEquals(a, setOf("USB pen drive", "Drive belt"), t.card!!.buttons.map { it.label }.toSet())
            assertFalse(a, drafted(t))
            assertTrue(inv.moves.isEmpty())
            // A tap: that product, as if said — its pack asked, or drafted when the pack was said.
            val d = runBlocking { kai.act(t.card!!.buttons.first { it.label == "Drive belt" }.action, KaiLang.TAMIL)!! }
            val b = all(d)
            assertTrue(b, b.contains("Drive belt"))
            assertFalse(b, b.contains("USB pen drive"))
        }
    }

    @Test fun theShopsOwnProductIsStillTakenAtOnce() {
        val (kai, _) = shop("Tyre Small pack", "Tyre Medium pack", *noTyre)
        val t = runBlocking { kai.ask("டயர் சின்ன பேக் 7 ஆட் பண்ணு") }
        assertTrue(all(t), drafted(t) && all(t).contains("Tyre Small pack"))
        val u = runBlocking { shop("Tyre Small pack", "Tyre Medium pack", *noTyre).first.ask("டயர் 7 ஆட் பண்ணு") }
        assertFalse(all(u), all(u).contains("கேட்டேன்"))
        assertTrue(all(u), all(u).contains("Tyre Small pack") && all(u).contains("Tyre Medium pack"))
    }

    @Test fun moneyAndOtherLinesAreNotAskedAbout() {
        val (kai, _) = shop(*noTyre)
        val t = runBlocking { kai.ask("பிரபா 2000 குடுத்தான்") }
        assertFalse(all(t), all(t).contains("எந்த product"))
        // No number: not a stock line.
        val u = runBlocking { shop(*noTyre).first.ask("டயர் இருக்கா") }
        assertFalse(all(u), all(u).contains("கேட்டேன்"))
    }

    @Test fun sureAndUnsureWords() {
        val names = noTyre.toList()
        // Near, not sure: asked with the near words.
        val u = KaiProductSound.unsureWords("டயர் 7", names)
        assertEquals(listOf("டயர்"), u.map { it.said })
        assertTrue(u.single().candidates.contains("drive"))
        // Not turned into "drive" any more.
        assertEquals("டயர் 7", KaiProductSound.withProductWords("டயர் 7", names))
        // The same sound, surely: still taken.
        assertTrue(KaiProductSound.withProductWords("டிரைவ் 7", names).startsWith("drive"))
        assertTrue(KaiProductSound.unsureWords("டிரைவ் 7", names).isEmpty())
    }
}
