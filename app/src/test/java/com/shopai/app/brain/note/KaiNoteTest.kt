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
                newNote = KaiNoteReader.noteFor(agent, id, t.card, lang, today),
                listed = flow.note?.let { KaiNoteReader.listed(agent, it) } == true)
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

    /** A product the shop doesn't have yet ("Rice 2 bag vandhuchu", a new product with its stock): the note shows it too. */
    @Test fun aNewProductWithItsStockIsWritten() {
        for ((lines, lang) in listOf(listOf("Basmati Rice 2 bag vandhuchu") to KaiLang.TANGLISH, listOf("பாஸ்மதி ரைஸ் 2 பேக் வந்துச்சு") to KaiLang.TAMIL)) {
            val (agent, inv) = kai()
            val chat = Chat(agent, lang, today)
            var t = chat.say(lines.first())
            // Kai asks what it needs for a new product (bag size, prices…): answered until the summary with Confirm.
            val answers = ArrayDeque(listOf("25", "1500", "70", "80", "1000", "skip"))
            while (t.card?.buttons?.any { it.primary && it.action is KaiAction.ConfirmStock } != true && answers.isNotEmpty()) t = chat.say(answers.removeFirst())
            assertTrue("«${lines.first()}» no Confirm: ${t.reply.text}", t.card?.buttons?.any { it.primary } == true)
            val note = chat.flow.note
            assertTrue("«${lines.first()}» no note: ${t.reply.text} | ${t.card?.lines}", note != null)
            assertEquals(KaiNoteKind.STOCK_IN, note!!.kind)
            assertTrue(note.rows.single().detail.orEmpty(), note.rows.single().detail.orEmpty().contains(if (lang == KaiLang.TAMIL) "புது" else "Pudhu"))
            assertTrue(inv.created.isEmpty())
            chat.tap(confirmOf(t))
            assertEquals("«${lines.first()}» ${chat.last?.reply?.text}", KaiNoteState.SAVE_SUCCESS, chat.flow.noteState)
        }
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

    /** The card's little pictures and row lines: by what the row is; a name with no picture is a plain box, never a guess. */
    @Test fun rowPicturesAndLines() {
        assertEquals("🪥", KaiNotes.icon(KaiNoteKind.STOCK_IN, "Colgate"))
        assertEquals("🧼", KaiNotes.icon(KaiNoteKind.STOCK_IN, "Lux Soap"))
        assertEquals("🌾", KaiNotes.icon(KaiNoteKind.STOCK_IN, "Aashirvaad Atta (10 Kg)"))
        assertEquals("📦", KaiNotes.icon(KaiNoteKind.STOCK_IN, "Zxq Widget"))
        assertEquals("₹", KaiNotes.icon(KaiNoteKind.CREDIT, "Kumar"))
        assertEquals("✂️", KaiNotes.icon(KaiNoteKind.SALE, "Hair cutting", KaiShopScene.BEAUTY_SALON))
        assertEquals("Stock add ready", KaiNotes.rowStatus(KaiNoteKind.STOCK_IN, KaiNoteState.REVIEWING, KaiLang.TANGLISH))
        for (lang in KaiLang.entries) for (k in KaiNoteKind.entries) for (st in KaiNoteState.entries) {
            val t = KaiNotes.rowStatus(k, st, lang)
            if (st != KaiNoteState.SAVE_SUCCESS) assertTrue("$k $st $lang: $t", !t.contains("aagiduchu") && !t.contains("சேமிச்சாச்சு") && t != "Saved")
        }
    }

    /** Kai's question card: what the owner said so far, and the one thing asked — from Kai's own pending item, typed or by mic. */
    @Test fun theQuestionCardShowsWhatIsKnownAndWhatIsAsked() {
        for ((line, lang) in listOf("Lux Soap 2 box vandhuchu" to KaiLang.TANGLISH, "லக்ஸ் சோப் 2 பாக்ஸ் வந்துச்சு" to KaiLang.TAMIL)) {
            val (agent, _) = kai()
            val t = runBlocking { agent.ask(line) }
            assertEquals("«$line» ${t.reply.text}", KaiMood.CLARIFY, t.reply.mood)
            val p = agent.askProgress(lang)
            assertTrue("«$line» no progress: ${t.reply.text}", p != null)
            assertEquals(listOf("Lux Soap", "2 box"), p!!.known.map { it.second })
            assertEquals("1 box = ? pcs", p.asking)
        }
        // A new product: its name and quantity known, a price asked; the count of questions never shrinks below this one.
        val (agent, _) = kai()
        var t = runBlocking { agent.ask("Basmati Rice 2 bag vandhuchu") }
        var seen = 0
        val answers = ArrayDeque(listOf("25", "1500", "70", "80", "1000"))
        while (t.reply.mood == KaiMood.CLARIFY && answers.isNotEmpty()) {
            val p = agent.askProgress(KaiLang.TANGLISH)
            if (p != null) {
                seen++
                assertTrue(p.known.first().second, p.known.first().second.contains("Basmati", ignoreCase = true))
                assertTrue("${p.step}/${p.steps}", p.step in 1..p.steps)
                // The row asked is a field not yet known.
                assertTrue(p.asking, p.known.none { it.first == p.asking })
            }
            t = runBlocking { agent.ask(answers.removeFirst()) }
        }
        assertTrue("no question card for the new product", seen > 0)
        // Nothing pending: no card.
        assertNull(kai().first.askProgress(KaiLang.TANGLISH))
    }

    @Test fun aServiceSaleCardIsWrittenRowByRow() {
        val note = KaiNotes.fromCard(3, KaiNoteKind.SALE, listOf("Hair cutting ×3 — ₹450", "Mothama ₹450 · Cash"),
            listOf(KaiNoteKey(KaiNoteKey.Source.SALE, "s")), KaiLang.TANGLISH, today)!!
        assertEquals("Innaikku Sales", note.title)
        assertEquals(KaiNoteRow("Hair cutting ×3", "₹450"), note.rows[0])
        assertEquals(KaiNoteRow("Mothama ₹450 · Cash", ""), note.rows[1])
    }

    /** "Stock evlo iruku" (owner, 18 Oct 2026): the stock card shows the same products and numbers as Kai's lines, typed or by mic. */
    @Test fun stockCardIsTheBooksOwnNumbers() {
        for (line in listOf("stock evlo iruku", "ஸ்டாக் எவ்வளவு இருக்கு")) {
            val (agent, inv) = kai()
            inv.items.first { it.name == "Lux Soap" }.minimum = BigDecimal("80")
            val t = runBlocking { agent.ask(line) }
            val card = t.card!!
            val rows = card.stock!!
            assertEquals(line, card.lines.size, rows.size)
            rows.forEachIndexed { i, r -> assertEquals(line, card.lines[i], "${r.name} — ${r.qty}") }
            val lux = rows.first { it.name == "Lux Soap" }
            assertEquals(KaiStockLevel.LOW, lux.level)
            assertEquals(KaiStockLevel.NO_LEVEL, rows.first { it.name == "Colgate" }.level)
            assertNull(rows.first { it.name == "Colgate" }.fill)
            assertEquals(0.25f, lux.fill!!, 0.001f)
        }
    }

    @Test fun stockLevels() {
        val q = { n: BigDecimal, u: String -> "${n.stripTrailingZeros().toPlainString()} $u" }
        val rows = KaiStockView.rows(listOf(
            com.shopai.app.brain.tools.StockFact("Rice", BigDecimal.ZERO, "KG", BigDecimal("10")),
            com.shopai.app.brain.tools.StockFact("Dal", BigDecimal("8"), "KG", BigDecimal("10")),
            com.shopai.app.brain.tools.StockFact("Oil", BigDecimal("50"), "LTR", BigDecimal("10")),
            com.shopai.app.brain.tools.StockFact("Salt", BigDecimal("5"), "KG", null, photo = " "),
            com.shopai.app.brain.tools.StockFact("Colgate", BigDecimal("5"), "PCS", null, photo = "/data/products/colgate.jpg"),
        ), q)
        // The product's own photo goes to the card; a blank one is no photo.
        assertEquals(listOf(null, null, null, null, "/data/products/colgate.jpg"), rows.map { it.photo })
        assertEquals(listOf(KaiStockLevel.OUT, KaiStockLevel.LOW, KaiStockLevel.OK, KaiStockLevel.NO_LEVEL, KaiStockLevel.NO_LEVEL), rows.map { it.level })
        assertEquals(1f, rows[2].fill!!, 0.001f)
        assertEquals("Stock illa", KaiStockView.label(rows[0], KaiLang.TANGLISH))
        assertEquals("Low · reorder 10 KG", KaiStockView.label(rows[1], KaiLang.TANGLISH))
        assertEquals("குறைவு · reorder 10 KG", KaiStockView.label(rows[1], KaiLang.TAMIL))
    }

    /**
     * Goods one by one (owner, 18 Oct 2026): a draft's Confirm only puts it in the list — the note says "in the list · not saved",
     * never "Save aagiduchu"; only the list's own Confirm saves, and then it ticks. Typed and as the mic writes it.
     */
    @Test fun aGoodsDraftConfirmedIsListedNotSaved() {
        for ((lines, lang) in listOf(
            listOf("Kadaiku saraku vandhuruku", "Noodles 100 pieces", "10", "12") to KaiLang.TANGLISH,
            listOf("கடைக்கு சரக்கு வந்திருக்கிறது", "நூடுல்ஸ் 100 பீஸ்", "10", "12") to KaiLang.TAMIL,
        )) {
            val (agent, inv) = kai()
            val chat = Chat(agent, lang, today)
            lines.forEach { chat.say(it) }
            val draft = chat.last!!
            assertEquals("${lines[1]}: ${draft.reply.text}", KaiNoteState.REVIEWING, chat.flow.noteState)
            val kept = chat.tap(confirmOf(draft))
            assertEquals("${kept.reply.text}", KaiNoteState.LISTED, chat.flow.noteState)
            assertTrue(KaiNotes.status(chat.flow.noteState, lang), !KaiNotes.status(chat.flow.noteState, lang).contains("✓"))
            // Short, with the list so far as a card.
            assertTrue(kept.reply.text, kept.reply.text.length < 90)
            assertEquals(1, kept.card!!.lines.size)
            assertTrue(kept.card!!.lines.single(), kept.card!!.lines.single().startsWith("1. "))
            val before = inv.items.size
            val list = chat.say(if (lang == KaiLang.TAMIL) "அவ்ளோ தான்" else "avlo dhaan")
            assertEquals(before, inv.items.size)
            assertEquals(KaiNoteState.REVIEWING, chat.flow.noteState)
            chat.tap(confirmOf(list))
            assertEquals(before + 1, inv.items.size)
            assertEquals("${chat.last!!.reply.text}", KaiNoteState.SAVE_SUCCESS, chat.flow.noteState)
        }
    }

    /** A new product's draft waiting for Confirm, then "Kadaiku saraku vandhuruku": the goods question — never a person "Sara". */
    @Test fun goodsSaidWithADraftWaiting() {
        for ((lines, lang) in listOf(
            listOf("Noodles 100 pieces", "10", "12", "Kadaiku saraku vandhuruku") to KaiLang.TANGLISH,
            listOf("நூடுல்ஸ் 100 பீஸ்", "10", "12", "கடைக்கு சரக்கு வந்திருக்கிறது") to KaiLang.TAMIL,
        )) {
            val (agent, inv) = kai()
            val chat = Chat(agent, lang, today)
            lines.dropLast(1).forEach { chat.say(it) }
            val draft = chat.last!!
            val asked = chat.say(lines.last())
            assertTrue(asked.reply.text, asked.reply.text.contains("Enna saaman vandhuruku") || asked.reply.text.contains("என்ன சாமான் வந்திருக்கு"))
            assertTrue(asked.reply.text, !asked.reply.text.contains("Sara "))
            // The draft's Confirm now puts it in the goods list — nothing saved.
            val before = inv.items.size
            chat.tap(confirmOf(draft))
            assertEquals(before, inv.items.size)
            assertEquals(chat.last!!.reply.text, KaiNoteState.LISTED, chat.flow.noteState)
        }
    }
}
