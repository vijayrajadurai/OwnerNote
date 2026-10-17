package com.shopai.app.brain.note

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiAgent
import com.shopai.app.brain.chat.KaiBooks
import com.shopai.app.brain.chat.KaiBusinessBrain
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.chat.KaiMemoryAccess
import com.shopai.app.brain.chat.KaiTurn
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Kai's hand-note (owner, 17 Oct 2026): the notebook and the digital card show the real draft — the same person, amount, product
 * and quantity Kai will save — in the owner's language, typed or by mic; the shop's category picks the scene; and the state only
 * follows what really happened (a tick only after the books saved, never before; Cancel writes nothing).
 */
class KaiNoteTest {
    private val now = LocalDateTime.of(2026, 10, 17, 11, 0)
    private val today = now.toLocalDate()

    /** The books read back what the test shop holds (so a save is checked against the records, as in the app). */
    private class Books(val inv: Inventory) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = inv.parties.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.balance.toDouble(), null) }, suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private fun kai(): Pair<KaiAgent, Inventory> {
        val inv = Inventory().also { inv ->
            inv.items.clear()
            inv.items += Inventory.Item("p1", "Lux Soap", "PCS", BigDecimal("60"), emptyMap(), BigDecimal("30"), BigDecimal("38"), "FMCG", null)
            inv.items += Inventory.Item("p2", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("25"), BigDecimal("30"), "FMCG", null)
            inv.parties.clear()
            inv.parties += PartyMatch("c1", "Kumar", true, null, BigDecimal("500"))
        }
        val books = Books(inv)
        val mem = KaiPrivateMemory(InMemoryKaiMemoryStore())
        val agent = KaiAgent(KaiBusinessBrain(books, today = { today }), books, inv, { now }, memory = object : KaiMemoryAccess {
            override suspend fun current() = mem.also { it.open("shop-1", "owner-1") }
            override suspend fun entities(): List<KnownEntity> = emptyList()
        })
        return agent to inv
    }

    /** Plays one line the way Kai Chat does: Kai's answer, the note it makes, the flow after it. */
    private class Chat(val agent: KaiAgent, val lang: KaiLang, val today: LocalDate) {
        var flow = KaiNoteFlow()
        var id = 0L
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn {
            flow = flow.understanding()
            val t = runBlocking { agent.ask(line) }
            return settle(t)
        }
        fun tap(action: KaiAction): KaiTurn {
            if (action is KaiAction.ConfirmPlan || action is KaiAction.ConfirmStock) flow = flow.saving()
            val t = runBlocking { agent.act(action, lang) }!!
            return settle(t)
        }
        private fun settle(t: KaiTurn): KaiTurn {
            id++
            flow = flow.answered(t.reply.mood, spent = flow.note?.let { KaiNoteReader.spent(agent, it) } == true,
                newNote = KaiNoteReader.noteFor(agent, id, t.card, lang, today))
            flow = flow.written()
            last = t
            return t
        }
    }

    private fun confirmOf(t: KaiTurn) = t.card!!.buttons.first { it.primary }.action
    private fun cancelOf(t: KaiTurn) = t.card!!.buttons.first { it.action is KaiAction.CancelPlan || it.action is KaiAction.CancelStock }.action

    @Test fun eachShopCategoryHasItsSceneAndAnUnknownOneIsTheCounter() {
        assertEquals(KaiShopScene.GROCERY, KaiShopScene.of("GROCERY"))
        assertEquals(KaiShopScene.PHARMACY, KaiShopScene.of("PHARMACY"))
        assertEquals(KaiShopScene.BEAUTY_SALON, KaiShopScene.of("beauty_salon"))
        assertEquals(KaiShopScene.RESTAURANT_FOOD, KaiShopScene.of(" RESTAURANT_FOOD "))
        for (c in listOf("TEXTILE", "HARDWARE", "ELECTRICAL", "MOBILE_ACCESSORIES", "AUTO_PARTS", "FURNITURE", "FOOTWEAR", "STATIONERY"))
            assertEquals(c, KaiShopScene.of(c).name)
        // Not a category Kai knows (a spa named as its own category, empty, none, OTHER): the neutral counter — never a guess.
        for (c in listOf("SPA", "", null, "OTHER", "NEUTRAL")) assertEquals("$c", KaiShopScene.NEUTRAL, KaiShopScene.of(c))
        // One picture per scene, each its own.
        assertEquals(KaiShopScene.entries.size, KaiShopScene.entries.map { it.image }.toSet().size)
    }

    @Test fun aCreditIsWrittenWithItsPersonAndAmountAndTicksOnlyAfterTheSave() {
        for ((line, lang) in listOf("Kumar ku 5000 credit kuduthen" to KaiLang.TANGLISH, "குமாருக்கு 5000 கிரெடிட் குடுத்தேன்" to KaiLang.TAMIL)) {
            val (agent, inv) = kai()
            val chat = Chat(agent, lang, today)
            val t = chat.say(line)
            val note = chat.flow.note
            assertTrue("«$line» no note: ${t.reply.text}", note != null)
            assertEquals(KaiNoteKind.CREDIT, note!!.kind)
            assertEquals(KaiNotes.title(KaiNoteKind.CREDIT, lang), note.title)
            assertEquals("Kumar", note.rows.single().name)
            assertEquals("₹5,000", note.rows.single().value)
            // Written and waiting: no save yet, no tick yet.
            assertEquals(KaiNoteState.REVIEWING, chat.flow.noteState)
            assertTrue(inv.payments.isEmpty())
            chat.tap(confirmOf(t))
            assertEquals(1, inv.payments.size)
            assertEquals("«$line» ${chat.last?.reply?.text}", KaiNoteState.SAVE_SUCCESS, chat.flow.noteState)
        }
    }

    @Test fun aPaymentTicksAfterTheBooksSavedIt() {
        for ((line, lang) in listOf("Kumar 200 kuduthan" to KaiLang.TANGLISH, "குமார் 200 குடுத்தான்" to KaiLang.TAMIL)) {
            val (agent, inv) = kai()
            val chat = Chat(agent, lang, today)
            val t = chat.say(line)
            val note = chat.flow.note!!
            assertEquals(KaiNoteKind.PAYMENT_IN, note.kind)
            assertEquals("Kumar", note.rows.single().name)
            assertEquals("₹200", note.rows.single().value)
            assertEquals(KaiNoteState.REVIEWING, chat.flow.noteState)
            chat.tap(confirmOf(t))
            assertEquals(1, inv.payments.size)
            assertEquals("«$line» ${chat.last?.reply?.text}", KaiMood.SUCCESS, chat.last!!.reply.mood)
            assertEquals(KaiNoteState.SAVE_SUCCESS, chat.flow.noteState)
        }
    }

    /** Sent, but the books couldn't read it back: Kai says so — and the note never ticks. */
    @Test fun aSaveTheBooksCantReadBackIsNoTick() {
        val (agent, _) = kai()
        val chat = Chat(agent, KaiLang.TANGLISH, today)
        val t = chat.say("Kumar 200 kuduthan")
        val f = chat.flow.saving().answered(KaiMood.CONCERNED, spent = true, newNote = null)
        assertEquals(KaiNoteState.SAVE_ERROR, f.noteState)
        assertTrue(t.card != null)
    }

    @Test fun cancelWritesNothingAndCrossesTheNoteOut() {
        val (agent, inv) = kai()
        val chat = Chat(agent, KaiLang.TANGLISH, today)
        val t = chat.say("Kumar 2000 kuduthan")
        assertEquals(KaiNoteKind.PAYMENT_IN, chat.flow.note!!.kind)
        chat.tap(cancelOf(t))
        assertTrue(inv.payments.isEmpty())
        assertEquals(KaiNoteState.CANCELLED, chat.flow.noteState)
    }

    @Test fun stockIsWrittenWithItsQuantityAndTheStockBeforeAndAfter() {
        for ((line, lang) in listOf("Lux Soap 10 pieces vandhuchu" to KaiLang.TANGLISH, "லக்ஸ் சோப் 10 பீஸ் வந்துச்சு" to KaiLang.TAMIL)) {
            val (agent, inv) = kai()
            val chat = Chat(agent, lang, today)
            val t = chat.say(line)
            val note = chat.flow.note
            assertTrue("«$line» no note: ${t.reply.text}", note != null)
            assertEquals(KaiNoteKind.STOCK_IN, note!!.kind)
            val row = note.rows.single()
            assertEquals("Lux Soap", row.name)
            assertEquals("10 pcs", row.value)
            assertEquals("60 → 70 pcs", row.detail)
            assertTrue(inv.moves.isEmpty())
            chat.tap(confirmOf(t))
            assertEquals(KaiNoteState.SAVE_SUCCESS, chat.flow.noteState)
            assertTrue(BigDecimal("70").compareTo(inv.item("Lux Soap").stock) == 0)
        }
    }

    @Test fun boxesAreWrittenAsSaidWithoutAGuessedStockAfter() {
        val (agent, _) = kai()
        val chat = Chat(agent, KaiLang.TANGLISH, today)
        chat.say("Colgate 2 box vandhuchu")
        val row = chat.flow.note!!.rows.single()
        assertEquals("Colgate", row.name)
        assertEquals("2 box", row.value)
    }

    @Test fun aQuestionOrAReportIsNoNote() {
        val (agent, _) = kai()
        val chat = Chat(agent, KaiLang.TANGLISH, today)
        chat.say("Kumar evlo tharanum")
        assertNull(chat.flow.note)
        chat.say("report")
        assertNull(chat.flow.note)
        assertEquals(KaiNoteState.CLARIFICATION_REQUIRED, chat.flow.state)
    }

    @Test fun theFlowMovesOnlyOnRealEvents() {
        val note = KaiNote(1, KaiNoteKind.CREDIT, "t", listOf(KaiNoteRow("Kumar", "₹5,000")), today, KaiLang.TANGLISH, listOf(KaiNoteKey(KaiNoteKey.Source.PLAN, "k")))
        var f = KaiNoteFlow()
        assertEquals(KaiNoteState.LISTENING, f.listening(true).state)
        assertEquals(KaiNoteState.IDLE, f.listening(true).listening(false).state)
        f = f.understanding().answered(KaiMood.CLARIFY, spent = false, newNote = note)
        assertEquals(KaiNoteState.DRAFT_READY, f.noteState)
        // Kai's answers while the draft waits (a question about something else) never tick it.
        assertEquals(KaiNoteState.DRAFT_READY, f.answered(KaiMood.SUCCESS, spent = false, newNote = null).noteState)
        f = f.written()
        assertEquals(KaiNoteState.REVIEWING, f.noteState)
        // Confirm: saving, then the books' answer decides.
        val saving = f.saving()
        assertEquals(KaiNoteState.SAVING, saving.noteState)
        assertEquals(KaiNoteState.SAVE_SUCCESS, saving.answered(KaiMood.SUCCESS, spent = true, newNote = null).noteState)
        assertEquals(KaiNoteState.SAVE_SUCCESS, saving.answered(KaiMood.HAPPY, spent = true, newNote = null).noteState)
        assertEquals(KaiNoteState.SAVE_ERROR, saving.answered(KaiMood.ERROR, spent = true, newNote = null).noteState)
        assertEquals(KaiNoteState.SAVE_ERROR, saving.answered(KaiMood.CONCERNED, spent = true, newNote = null).noteState)
        // Back without the draft spent: it waits again — no tick, no error.
        assertEquals(KaiNoteState.REVIEWING, saving.answered(KaiMood.CLARIFY, spent = false, newNote = null).noteState)
        // Dropped (Cancel / "venam"): crossed out.
        assertEquals(KaiNoteState.CANCELLED, f.answered(KaiMood.NEUTRAL, spent = true, newNote = null).noteState)
        // Saving without an open note does nothing; the mic while saving doesn't interrupt it.
        assertEquals(KaiNoteState.IDLE, KaiNoteFlow().saving().noteState)
        assertEquals(KaiNoteState.SAVING, saving.listening(true).state)
        assertEquals(KaiNoteFlow(), f.cleared())
    }

    @Test fun titlesAndStatusAreInTheOwnersLanguage() {
        assertEquals("Vaanga vendiyadhu (Credit)", KaiNotes.title(KaiNoteKind.CREDIT, KaiLang.TANGLISH))
        assertEquals("கொடுக்க வேண்டியது (Debit)", KaiNotes.title(KaiNoteKind.DEBIT, KaiLang.TAMIL))
        assertEquals("Stock Add", KaiNotes.title(KaiNoteKind.STOCK_IN, KaiLang.TANGLISH))
        assertEquals("Save aagiduchu ✓", KaiNotes.status(KaiNoteState.SAVE_SUCCESS, KaiLang.TANGLISH))
        assertEquals("Ready · Confirm pannunga", KaiNotes.status(KaiNoteState.REVIEWING, KaiLang.TANGLISH))
        // Every state says something in every language except IDLE, and nothing but SAVE_SUCCESS says "saved".
        for (lang in KaiLang.entries) for (s in KaiNoteState.entries) {
            val text = KaiNotes.status(s, lang)
            if (s != KaiNoteState.IDLE) assertTrue("$s $lang", text.isNotEmpty())
            if (s != KaiNoteState.SAVE_SUCCESS) assertTrue("$s $lang: $text", !text.contains("✓") && !text.contains("aagiduchu") && !text.contains("சேமிச்சாச்சு") && !text.startsWith("Saved"))
        }
    }

    @Test fun aServiceSaleCardIsWrittenRowByRow() {
        val note = KaiNotes.fromCard(3, KaiNoteKind.SALE, listOf("Hair cutting ×3 — ₹450", "Mothama ₹450 · Cash"),
            listOf(KaiNoteKey(KaiNoteKey.Source.SALE, "s")), KaiLang.TANGLISH, today)!!
        assertEquals("Innaikku Sales", note.title)
        assertEquals(KaiNoteRow("Hair cutting ×3", "₹450"), note.rows[0])
        assertEquals(KaiNoteRow("Mothama ₹450 · Cash", ""), note.rows[1])
    }
}
