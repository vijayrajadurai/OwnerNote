package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.data.model.DailyCashTotals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Daily Sales Note by chat (owner, 14 Oct 2026): a salon / tea shop / beauty parlour / power tool rental / hotel says its
 * sales to Kai — "oru hair cutting", "cutting plus shaving", "2 tea", "drill machine 2 naal vaadagai". Kai asks a service's
 * price once and keeps it on Confirm; the next sale is a draft straight away; "ithuvaraikum evlo sales" is today's total by
 * service; "innaikku avlothan" closes the day; any day's sales come with their services and a PDF. Every line typed and as the
 * ta-IN mic writes it. Nothing is written before Confirm (principles 3 and 5); a price is kept only with it (principle 4).
 */
class KaiDailySalesTest {
    private val now = LocalDateTime.of(2026, 10, 14, 11, 0)
    private val today = now.toLocalDate()

    /** The phone's Daily Cash Note: entries by day, the opening Kallapetti, the closed days. */
    private class Note {
        data class Entry(val day: LocalDate, val amount: BigDecimal, val upi: Boolean, val note: String, val incoming: Boolean = true)
        val entries = mutableListOf<Entry>()
        val opening = HashMap<LocalDate, BigDecimal>()
        val closed = HashSet<LocalDate>()
        fun view(day: LocalDate): CashNoteView {
            val es = entries.filter { it.day == day }
            val cashIn = es.filter { it.incoming && !it.upi }.sumOf { it.amount.toDouble() }
            val upiIn = es.filter { it.incoming && it.upi }.sumOf { it.amount.toDouble() }
            val out = es.filter { !it.incoming }.sumOf { it.amount.toDouble() }
            val totals = DailyCashTotals(cashIn + upiIn, out, cashIn + upiIn - out, upiIn, 0.0, upiIn, cashIn, out, cashIn - out)
            return CashNoteView(day, es.size, totals, opening[day]?.toDouble(), opening[day]?.toDouble()?.plus(cashIn - out), day in closed,
                es.mapIndexed { i, e -> CashLine(e.incoming, e.amount.toDouble(), e.upi, e.note, "2026-10-14T0$i:00:00Z") })
        }
    }

    private class Books(val note: Note) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
        override suspend fun cashNote(day: LocalDate) = note.view(day)
    }

    private class Tools(val inv: Inventory, val note: Note, val services: Boolean) : KaiTools by inv {
        override suspend fun cashOpening(day: LocalDate) = note.opening[day]
        override suspend fun setCashOpening(day: LocalDate, amount: BigDecimal): ActionOutcome { note.opening[day] = amount; return ActionOutcome.Done("kallapetti", amount) }
        override suspend fun addCashSale(day: LocalDate, amount: BigDecimal, upi: Boolean, note: String): ActionOutcome {
            if (day in this.note.closed) return ActionOutcome.Failed("Day already submitted")
            // (the kallapetti is optional — owner, 21 Oct 2026 — as in DailyCashRepository)
            this.note.entries += Note.Entry(day, amount, upi, note)
            return ActionOutcome.Done("cash-${this.note.entries.size}", amount)
        }
        override suspend fun closeCashDay(day: LocalDate): ActionOutcome { note.closed += day; return ActionOutcome.Done(day.toString(), null) }
        override suspend fun sellsServices() = services
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("salon-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private inner class Shop(services: Boolean = true, products: List<String> = emptyList(), val store: InMemoryKaiMemoryStore = InMemoryKaiMemoryStore()) {
        val note = Note()
        val inv = Inventory().also { inv -> products.forEachIndexed { i, n -> inv.items += Inventory.Item("p$i", n, "PCS", BigDecimal("50"), emptyMap(), null, null, "Grocery", null) } }
        val tools = Tools(inv, note, services)
        val kai = KaiAgent(KaiBusinessBrain(Books(note)), Books(note), tools, { now }, memory = Access(KaiPrivateMemory(store)))
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { last = it }
        fun tap(label: String): KaiTurn {
            val b = last?.card?.buttons?.firstOrNull { it.label.contains(label) } ?: error("no '$label' button: ${all(last)}")
            return runBlocking { kai.act(b.action, KaiLang.TANGLISH)!! }.also { last = it }
        }
        fun confirm() = tap(if (last?.card?.buttons?.any { it.label.contains("உறுதி") } == true) "உறுதி" else "Confirm")
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    /** [typed] and, when the mic would write it otherwise, the same line as the mic writes it. */
    private fun bothWays(typed: String): List<String> = listOfNotNull(typed, KaiMicForm.of(typed)?.takeIf { it != typed })

    // ------------------------------------------------------------ the kallapetti: asked, never required (owner, 21 Oct 2026)

    @Test fun theKallapettiIsAskedOnceAndSkippingGoesOnWithTheSale() {
        for (skip in listOf("skip", "venam", "illa", "வேணாம்", "ஸ்கிப்")) {
            val s = Shop()
            s.say("oru hair cutting 150")
            val asked = s.last!!
            assertTrue("$skip: asked → ${all(asked)}", all(asked).contains("Kallapetti", ignoreCase = true) || all(asked).contains("கல்லாப்பெட்டி"))
            val draft = s.say(skip)
            assertTrue("$skip: the sale goes on → ${all(draft)}", draft.card?.buttons?.any { it.action is KaiAction.ConfirmSale } == true)
            s.confirm()
            assertTrue("$skip: saved without a kallapetti", s.note.entries.size == 1 && s.note.opening.isEmpty())
            // Not asked again today: the next sale is a draft straight away.
            val next = s.say("oru hair cutting")
            assertTrue("$skip: not asked again → ${all(next)}", next.card?.buttons?.any { it.action is KaiAction.ConfirmSale } == true)
        }
        // "cancel" still drops the sale.
        val c = Shop()
        c.say("oru hair cutting 150")
        c.say("cancel")
        assertTrue(c.note.entries.isEmpty())
    }

    // ------------------------------------------------------------ a first sale: the price once, the opening once, Confirm

    @Test fun aFirstSaleAsksThePriceOnceAndSavesOnlyOnConfirm() {
        for (line in bothWays("oru hair cutting add pannu")) {
            val s = Shop()
            val ask = s.say(line)
            assertTrue("$line → ${all(ask)}", ask.reply.text.contains("Hair cutting") && ask.card == null)
            s.say("150")
            val opening = s.last!!
            assertTrue("[$line] opening asked: ${all(opening)}", all(opening).contains("Kallapetti", ignoreCase = true) || all(opening).contains("கல்லாப்பெட்டி"))
            val draft = s.say("500")
            assertTrue("[$line] draft: ${all(draft)}", all(draft).contains("₹150") && draft.card?.buttons?.isNotEmpty() == true)
            assertTrue("[$line] nothing before Confirm", s.note.entries.isEmpty() && s.note.opening.isEmpty())
            val saved = s.confirm()
            assertEquals(line, 1, s.note.entries.size)
            assertEquals(line, "Hair cutting ×1 ₹150", s.note.entries.single().note)
            assertEquals(line, BigDecimal("500"), s.note.opening[today])
            assertTrue("[$line] saved: ${all(saved)}", saved.reply.text.contains("✅") && saved.reply.text.contains("₹150"))
            // Next time the price is known: straight to the draft.
            val again = s.say(line)
            assertTrue("[$line] again: ${all(again)}", all(again).contains("₹150") && again.card?.buttons?.isNotEmpty() == true)
        }
    }

    @Test fun cuttingPlusShavingAsksOnlyTheNewPriceAndAmaSaves() {
        for ((first, second) in listOf("oru cutting" to "cutting plus shaving", "ஒரு கட்டிங்" to "கட்டிங் பிளஸ் ஷேவிங்")) {
            val s = Shop()
            s.say(first); s.say("150"); s.say("0"); s.confirm()
            val ask = s.say(second)
            assertTrue("$second → ${all(ask)}", ask.reply.text.contains("Shaving") && !ask.reply.text.contains("Hair cutting"))
            val draft = s.say("80")
            assertTrue("$second draft: ${all(draft)}", all(draft).contains("₹230"))
            s.say(if (second.first() in 'அ'..'ௐ') "ஆமா" else "ama")
            assertEquals(second, "Hair cutting ×1 ₹150 · Shaving ×1 ₹80", s.note.entries.last().note)
            // The third time, both known: the draft at once, paid by GPay.
            val third = s.say(if (second.first() in 'அ'..'ௐ') "ஒரு கட்டிங் ஒரு ஷேவிங் ஜிபே" else "oru cutting oru shaving gpay")
            assertTrue("third: ${all(third)}", all(third).contains("₹230") && all(third).contains("UPI"))
            s.confirm()
            assertTrue(s.note.entries.last().upi)
        }
    }

    @Test fun twoOfOneAndAPriceSaidWithTheSale() {
        val s = Shop()
        s.say("rendu cutting 300"); s.say("0")
        assertTrue(all(s.last), all(s.last).contains("₹300"))
        s.confirm()
        assertEquals("Hair cutting ×2 ₹300", s.note.entries.single().note)
        // The price of one is not learned from a price said for two: Kai asks it next time.
        assertTrue(s.say("oru cutting").reply.text.contains("evlo"))
    }

    // ------------------------------------------------------------ so far, a day, the close, the PDF

    @Test fun salesSoFarByServiceTypedAndSaid() {
        val s = Shop()
        s.note.opening[today] = BigDecimal.ZERO
        s.note.entries += Note.Entry(today, BigDecimal("230"), false, "Hair cutting ×1 ₹150 · Shaving ×1 ₹80")
        s.note.entries += Note.Entry(today, BigDecimal("150"), true, "Hair cutting ×1 ₹150")
        for (line in listOf("ithuvaraikum evlo sales", "innaikku evlo sales", "இதுவரைக்கும் எவ்வளவு சேல்ஸ்", "ithuvaraikum evlo")) {
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", all(t).contains("₹380") && all(t).contains("Hair cutting ×2") && all(t).contains("Shaving ×1"))
        }
    }

    @Test fun aDaysSalesWithItsServicesAndAPdf() {
        val s = Shop()
        val yesterday = today.minusDays(1)
        s.note.entries += Note.Entry(yesterday, BigDecimal("300"), false, "Hair dye ×1 ₹300")
        s.note.entries += Note.Entry(LocalDate.of(2026, 10, 10), BigDecimal("450"), false, "Facial ×1 ₹450")
        for ((line, want) in listOf("nethu evlo sales" to "Hair dye ×1", "நேத்து எவ்வளவு சேல்ஸ்" to "Hair dye ×1", "October 10 sales evlo" to "Facial ×1", "10th sales evlo" to "Facial ×1")) {
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", all(t).contains(want))
        }
        val pdf = s.say("nethu sales pdf kudu")
        assertEquals(KaiAction.ShareCashNote(yesterday), pdf.card?.buttons?.firstOrNull()?.action)
        val tamilPdf = s.say("இன்னைக்கு சேல்ஸ் பிடிஎஃப் குடு")
        assertTrue(all(tamilPdf), all(tamilPdf).contains("PDF"))
    }

    @Test fun thatsAllForTodayClosesTheDayOnlyOnConfirm() {
        for (line in listOf("innaikku avlothan", "இன்னைக்கு அவ்ளோதான்", "day close pannu")) {
            val s = Shop()
            s.note.opening[today] = BigDecimal.ZERO
            s.note.entries += Note.Entry(today, BigDecimal("230"), false, "Hair cutting ×1 ₹150 · Shaving ×1 ₹80")
            val ask = s.say(line)
            assertTrue("$line → ${all(ask)}", all(ask).contains("₹230") && all(ask).contains("Hair cutting ×1") && today !in s.note.closed)
            val done = s.tap("Day close")
            assertTrue("$line closed: ${all(done)}", today in s.note.closed && done.reply.text.contains("✅"))
            // A sale after the close is never saved.
            val after = s.say("oru cutting 150")
            assertTrue(all(after), all(after).contains("close"))
        }
    }

    @Test fun nothingToCloseIsSaidPlainly() {
        val s = Shop()
        val t = s.say("innaikku avlothan")
        assertTrue(all(t), today !in s.note.closed)
    }

    // ------------------------------------------------------------ prices, the owner's own services

    @Test fun aPriceChangedOnlyOnConfirm() {
        val s = Shop()
        s.say("oru cutting"); s.say("150"); s.say("0"); s.confirm()
        var old = "₹150"
        for ((line, now) in listOf("cutting rate 200" to "₹200", KaiMicForm.of("cutting rate 220")!! to "₹220")) {
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", all(t).contains(old) && all(t).contains(now) && s.note.entries.size == 1)
            s.confirm()
            old = now
        }
        assertTrue(all(s.say("cutting rate 220")), all(s.last).contains("already"))
        assertTrue(all(s.say("cutting evlo")), all(s.last).contains("₹220"))
        assertTrue(all(s.say("oru cutting")), all(s.last).contains("₹220"))
        assertTrue(all(s.say("rate list")), all(s.last).contains("Hair cutting"))
    }

    @Test fun aServiceKaiHasNoWordForIsAddedByTheOwner() {
        val s = Shop()
        // A word Kai can't read is never turned into a service.
        val unknown = s.say("oru hair botox")
        assertTrue(all(unknown), s.note.entries.isEmpty() && !all(unknown).contains("Hair botox ×"))
        s.note.opening[today] = BigDecimal.ZERO
        val keep = s.say("pudhu service hair botox 1500")
        assertTrue(all(keep), all(keep).contains("Hair botox") && all(keep).contains("₹1,500"))
        s.confirm()
        val sale = s.say("oru hair botox")
        assertTrue(all(sale), all(sale).contains("₹1,500") && sale.card?.buttons?.isNotEmpty() == true)
    }

    @Test fun dropsTheSaleWhenTold() {
        for (no in listOf("venam", "வேணாம்")) {
            val s = Shop()
            s.say("oru facial")
            val t = s.say(no)
            assertTrue(all(t), s.note.entries.isEmpty() && t.card == null)
        }
    }

    // ------------------------------------------------------------ tea shop, rental, a grocery's tea

    @Test fun teaShopAndToolRental() {
        for (line in bothWays("2 tea")) {
            val s = Shop()
            s.note.opening[today] = BigDecimal.ZERO
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", t.reply.text.contains("Tea"))
            assertTrue(all(s.say("15")), all(s.last).contains("₹30"))
            s.confirm()
            assertEquals(line, "Tea ×2 ₹30", s.note.entries.single().note)
        }
        val s = Shop()
        s.note.opening[today] = BigDecimal.ZERO
        val r = s.say("drill machine 2 naal vaadagai")
        assertTrue(all(r), r.reply.text.contains("Drill machine rent"))
        assertTrue(all(s.say("300")), all(s.last).contains("₹600"))
    }

    @Test fun aGroceryTeaIsNotATeaShopSale() {
        val s = Shop(services = false, products = listOf("Tea powder 250 g"))
        val t = s.say("2 tea")
        assertTrue(all(t), !t.reply.text.contains("Tea evlo") && !t.reply.text.contains("Tea-ku"))
        // A salon's own word is still a sale in any shop.
        assertTrue(all(s.say("oru shaving")), s.last!!.reply.text.contains("Shaving"))
    }

    @Test fun pricesStayWithTheBusiness() {
        val store = InMemoryKaiMemoryStore()
        val s = Shop(store = store)
        s.say("oru cutting"); s.say("150"); s.say("0"); s.confirm()
        // The app restarted: the same business's memory still has the price.
        val again = Shop(store = store)
        again.note.opening[today] = BigDecimal.ZERO
        val t = again.say("oru cutting")
        assertTrue(all(t), all(t).contains("₹150") && t.card?.buttons?.isNotEmpty() == true)
    }

    /**
     * The owner (15 Oct 2026): "hair cutting onnu" — the count said after the service, as a word ("onnu", "rendu", "moonu"), and
     * the name said as one word ("haircutting", "ஹேர்கட்டிங்"). Typed and as the mic writes it; nothing before Confirm.
     */
    @Test fun aCountWordAfterTheServiceAndTheNameSaidAsOneWord() {
        for ((typed, want) in listOf("hair cutting onnu" to "Hair cutting ×1 ₹150", "hair cutting rendu" to "Hair cutting ×2 ₹300",
            "haircutting onnu" to "Hair cutting ×1 ₹150", "haircutting rendu" to "Hair cutting ×2 ₹300", "kids cutting moonu" to "Kids cutting ×3 ₹450")) {
            for (line in bothWays(typed)) {
                val s = Shop()
                val ask = s.say(line)
                assertTrue("$line → ${all(ask)}", ask.reply.text.contains(want.substringBefore(" ×")) && ask.card == null)
                s.say("150")
                val draft = s.say("0")
                assertTrue("[$line] draft: ${all(draft)}", all(draft).contains(want.substringBefore(" ₹")))
                assertTrue("[$line] nothing before Confirm", s.note.entries.isEmpty())
                s.confirm()
                assertEquals(line, want, s.note.entries.single().note)
            }
        }
    }

    @Test fun kaiServicesReadsALine() {
        val k = com.shopai.app.brain.tools.KaiServices
        val line = k.saleIn("cutting 150 shaving 80", emptyMap(), emptyList(), false)!!
        assertEquals(listOf("Hair cutting" to BigDecimal("150"), "Shaving" to BigDecimal("80")), line.items.map { it.name to it.amount })
        val teas = k.saleIn("2 tea 3 coffee", emptyMap(), emptyList(), true)!!
        assertEquals(listOf("Tea" to 2, "Coffee" to 3), teas.items.map { it.name to it.qty })
        assertNull(k.saleIn("cutting machine 2 vandhuchu", emptyMap(), emptyList(), true))
        assertNotNull(k.saleIn("cutting machine 2 naal vaadagai", emptyMap(), emptyList(), false))
        assertEquals(listOf(Triple("Hair cutting", 1, BigDecimal("150")), Triple("Shaving", 2, BigDecimal("160"))),
            k.fromNote("Hair cutting ×1 ₹150 · Shaving ×2 ₹160"))
        // A count word after one service, right before the next, is the next one's ("cutting rendu shaving" = 1 cutting, 2 shaving).
        assertEquals(listOf("Hair cutting" to 1, "Shaving" to 2), k.saleIn("cutting rendu shaving", emptyMap(), emptyList(), true)!!.items.map { it.name to it.qty })
        assertEquals(listOf("Hair cutting" to 2, "Shaving" to 1), k.saleIn("cutting rendu oru shaving", emptyMap(), emptyList(), true)!!.items.map { it.name to it.qty })
        assertEquals(listOf("Hair cutting" to 2), k.saleIn("ஹேர்கட்டிங் ரெண்டு", emptyMap(), emptyList(), true)!!.items.map { it.name to it.qty })
        // A word after it that is no number is not a count; a name Kai doesn't have, said as one word, is still not a service.
        assertEquals(listOf("Hair cutting" to 1), k.saleIn("hair cutting pannunga", emptyMap(), emptyList(), true)!!.items.map { it.name to it.qty })
        assertNull(k.saleIn("hairbotox rendu", emptyMap(), emptyList(), true))
        // The owner's own service said as one word: "Hair botox" kept → "hairbotox 1".
        assertEquals(listOf("Hair botox" to 1), k.saleIn("hairbotox onnu", mapOf("Hair botox" to BigDecimal("1500")), emptyList(), true)!!.items.map { it.name to it.qty })
    }
}
