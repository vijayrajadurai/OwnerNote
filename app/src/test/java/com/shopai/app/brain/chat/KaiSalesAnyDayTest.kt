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
import com.shopai.app.data.model.DailyCashTotals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Daily Sales Note the owner's way (owner, 19 Oct 2026: "daily sales note maximum English la than service poduvanga — kai atha
 * Tamil / Tanglish ku … yentha date la evlo sales nu ketalum sollanum, pdf report keta kudukanum"): the owner's English Menu names
 * said in Tanglish and by the mic ("thaadi trim", "ஹேர் ஸ்மூத்தனிங்"), a service not on the Menu with the owner's own kinds of it
 * asked which ("mudi vettu" with Gents / Kids Haircut kept — never a new item, never one of theirs by itself), any day's sales
 * ("munnaal", "Monday", "pona Sunday", "anjaam thedhi", "October 5"), a span's ("indha vaaram", "pona maasam", "kadandha 7 naal")
 * with the top service, and the PDF of a day or a span. Today is Wednesday 14 Oct 2026; nothing is written before Confirm.
 */
class KaiSalesAnyDayTest {
    private val now = LocalDateTime.of(2026, 10, 14, 11, 0)   // Wednesday
    private val today = now.toLocalDate()

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
        override suspend fun cashBook(from: LocalDate, to: LocalDate): com.shopai.app.brain.chat.CashBookTotals? {
            val es = note.entries.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
            return CashBookTotals(es.filter { it.incoming }.sumOf { it.amount.toDouble() }, es.filter { !it.incoming }.sumOf { it.amount.toDouble() }, es.size)
        }
        override suspend fun cashNote(day: LocalDate) = note.view(day)
    }
    private class Tools(val inv: Inventory, val note: Note) : KaiTools by inv {
        override suspend fun cashOpening(day: LocalDate) = note.opening[day]
        override suspend fun setCashOpening(day: LocalDate, amount: BigDecimal): ActionOutcome { note.opening[day] = amount; return ActionOutcome.Done("k", amount) }
        override suspend fun addCashSale(day: LocalDate, amount: BigDecimal, upi: Boolean, note: String): ActionOutcome {
            this.note.entries += Note.Entry(day, amount, upi, note); return ActionOutcome.Done("c", amount) }
        override suspend fun closeCashDay(day: LocalDate): ActionOutcome { note.closed += day; return ActionOutcome.Done(day.toString(), null) }
        override suspend fun sellsServices() = true
    }
    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("salon-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private val menu = listOf("Gents Haircut" to "150", "Beard Trim" to "80", "Head Massage" to "100", "Hair Smoothening" to "2500", "Kids Haircut" to "100", "Gold Facial" to "800")

    private inner class Shop {
        val note = Note()
        val memory = KaiPrivateMemory(InMemoryKaiMemoryStore())
        val kai: KaiAgent
        init {
            runBlocking { memory.open("salon-1", "owner-1"); menu.forEach { (n, p) -> KaiMenu.save(memory, n, BigDecimal(p)) } }
            kai = KaiAgent(KaiBusinessBrain(Books(note)), Books(note), Tools(Inventory(), note), { now }, memory = Access(memory))
            note.opening[today] = BigDecimal.ZERO
            fun e(d: LocalDate, a: String, n: String) { note.entries += Note.Entry(d, BigDecimal(a), false, n) }
            e(today, "300", "Gents Haircut ×2 ₹300")
            e(today.minusDays(1), "800", "Gold Facial ×1 ₹800")
            e(today.minusDays(2), "240", "Beard Trim ×3 ₹240")
            e(today.minusDays(3), "100", "Head Massage ×1 ₹100")
            e(LocalDate.of(2026, 10, 5), "2500", "Hair Smoothening ×1 ₹2500")
            e(LocalDate.of(2026, 9, 20), "200", "Kids Haircut ×2 ₹200")
        }
        fun say(l: String) = runBlocking { kai.ask(l) }
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " / ")

    @Test fun menuNamesAnyDayAndThePdf() {
        val fails = mutableListOf<String>()
        fun rec(ok: Boolean, area: String, line: String, t: KaiTurn) { if (!ok) fails += "[$area] «$line» → ${all(t).take(300)}" }
        // ---- the owner's English Menu, said typed / Tanglish / Tamil
        for ((line, want) in listOf(
            "oru gents haircut" to "Gents Haircut", "ஒரு ஜென்ட்ஸ் ஹேர்கட்" to "Gents Haircut",
            "rendu beard trim" to "Beard Trim", "ரெண்டு பியர்ட் ட்ரிம்" to "Beard Trim", "rendu thaadi trim" to "Beard Trim",
            "oru thalai massage" to "Head Massage", "ஒரு தலை மசாஜ்" to "Head Massage", "head massage onnu" to "Head Massage",
            "hair smoothening onnu" to "Hair Smoothening", "ஹேர் ஸ்மூத்தனிங் ஒன்னு" to "Hair Smoothening", "ஹேர் ஸ்மூதனிங் ஒன்று" to "Hair Smoothening",
            "kids haircut rendu" to "Kids Haircut",
            "gold facial pannom" to "Gold Facial", "கோல்டு ஃபேஷியல் பண்ணோம்" to "Gold Facial", "கோல்ட் பேஷியல் ஒன்னு" to "Gold Facial",
        )) {
            val s = Shop()
            val t = s.say(line)
            rec(all(t).contains(want) && t.card?.buttons?.isNotEmpty() == true && s.note.entries.size == 6, "menu sale", line, t)
        }
        // ---- a service not on the Menu, with the owner's own kinds of it: asked which — then the answer drafts it
        for ((line, answer, want) in listOf(
            Triple("kuzhandhai cutting onnu", "aama", "Kids Haircut"), Triple("குழந்தை கட்டிங் ஒன்னு", "ஆமா", "Kids Haircut"),
            Triple("oru mudi vettu", "gents", "Gents Haircut"), Triple("ஒரு முடி வெட்டு", "ஜென்ட்ஸ்", "Gents Haircut"),
            Triple("oru haircut oru thaadi", "Gents Haircut", "Beard Trim"), Triple("rendu cutting", "kids", "Kids Haircut ×2"),
            Triple("oru facial", "aama", "Gold Facial"),
        )) {
            val s = Shop()
            val q = s.say(line)
            val asked = q.card?.buttons.isNullOrEmpty() && s.note.entries.size == 6 && (all(q).contains("Gents Haircut") || all(q).contains("Kids Haircut") || all(q).contains("Gold Facial"))
            val t = s.say(answer)
            rec(asked && all(t).contains(want) && t.card?.buttons?.isNotEmpty() == true && s.note.entries.size == 6, "menu which", "$line → $answer", t)
            if (!asked) fails += "[menu which] «$line» was not asked which: ${all(q).take(250)}"
        }
        // ---- any day's sales, typed / Tamil / English
        for ((line, want) in listOf(
            "innaiku sales evlo" to "₹300", "nethu sales evlo" to "₹800", "நேத்து சேல்ஸ் எவ்வளவு" to "₹800", "yesterday sales" to "₹800",
            "munnaal sales evlo" to "₹240", "nethu munnaal evlo sales" to "₹240", "Monday sales evlo" to "₹240", "திங்கக்கிழமை சேல்ஸ் எவ்வளவு" to "₹240",
            "pona Sunday evlo sales" to "₹100", "ஞாயிற்றுக்கிழமை எவ்வளவு சேல்ஸ்" to "₹100",
            "5th sales evlo" to "₹2,500", "October 5 sales" to "₹2,500", "அஞ்சாம் தேதி சேல்ஸ் எவ்வளவு" to "₹2,500", "sales on 5th October" to "₹2,500",
            "indha vaaram sales evlo" to "₹1,340", "இந்த வாரம் சேல்ஸ் எவ்வளவு" to "₹1,340",
            "indha maasam sales evlo" to "₹3,940", "இந்த மாசம் சேல்ஸ் எவ்வளவு" to "₹3,940", "this month sales" to "₹3,940",
            "pona maasam sales evlo" to "₹200", "kadandha 7 naal sales evlo" to "₹1,440",
            "sani kizhamai sales evlo" to "edhuvum illa", "10 thethi sales evlo" to "edhuvum illa", "Tuesday sales" to "₹800", "செவ்வாய்க்கிழமை சேல்ஸ் எவ்வளவு" to "₹800",
            "pona vaaram sales evlo" to "₹2,600", "போன மாசம் சேல்ஸ் எவ்வளவு" to "₹200", "last 7 days sales" to "₹1,440",
            "nethu Gold Facial evlo aachu" to "Gold Facial", "indha maasam endha service adhigama pochu" to "Hair Smoothening",
        )) {
            val s = Shop()
            val t = s.say(line)
            rec(all(t).contains(want), "sales day", line, t)
        }
        // ---- the PDF of a day (and a week / month when asked)
        for ((line, day) in listOf(
            "nethu sales PDF kudu" to today.minusDays(1), "Monday sales report PDF anuppu" to today.minusDays(2), "5th report PDF venum" to LocalDate.of(2026, 10, 5),
            "நேத்து சேல்ஸ் ரிப்போர்ட் பிடிஎஃப் குடு" to today.minusDays(1), "innaiku sales report kudu" to today, "yesterday sales pdf" to today.minusDays(1),
            "indha maasam sales PDF kudu" to null, "pona vaaram report PDF" to null, "இந்த மாசம் சேல்ஸ் பிடிஎஃப் குடு" to null,
        )) {
            val s = Shop()
            val t = s.say(line)
            val share = t.card?.buttons?.mapNotNull { it.action as? KaiAction.ShareCashNote }.orEmpty()
            rec(if (day != null) share.any { it.day == day && it.to == null } else share.any { it.to != null }, "pdf", line, t)
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
