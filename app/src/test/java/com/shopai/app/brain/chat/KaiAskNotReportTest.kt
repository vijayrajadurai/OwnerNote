package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Kai doesn't understand → it asks; it never shows a report nobody asked for, or one the records don't have (owner, 17 Oct 2026).
 * A word said alone ("report", "cash", "UPI", "indha maasam", a product's word) gets the question for it, and the owner's answer
 * ("sales", "selavu", "paakkanum", "vandhuchu") gets that answer — typed and by mic. What the records have is said from them
 * ("Kumar kanakku", "expiry", "low stock", "indha maasam selavu evlo"); what they don't is said plainly in the owner's language.
 */
class KaiAskNotReportTest {
    private val now = LocalDateTime.of(2026, 10, 17, 11, 0)

    private class Books(val cash: CashBookTotals?) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = listOf(PartySummary("c1", "Kumar", null, 500.0, null)),
            suppliers = listOf(PartySummary("s1", "Sri Balaji Stores", null, 4000.0, null)))
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = cash
        // Today's note: ₹3,000 cash + ₹2,000 UPI in, ₹1,200 cash out.
        override suspend fun cashNote(day: LocalDate) = cash?.let {
            CashNoteView(day, it.entries, com.shopai.app.data.model.DailyCashTotals(it.totalIn, it.totalOut, it.totalIn - it.totalOut,
                2000.0, 0.0, 2000.0, 3000.0, it.totalOut, 3000.0 - it.totalOut), null, null, false)
        }
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private fun kai(cash: CashBookTotals? = null): Pair<KaiAgent, Inventory> {
        val inv = Inventory().also { inv ->
            inv.items.clear()
            inv.items += Inventory.Item("p1", "Lux Soap", "PCS", BigDecimal("60"), emptyMap(), BigDecimal("30"), BigDecimal("38"), "FMCG", null)
            inv.items += Inventory.Item("p2", "Maggi", "PCS", BigDecimal("3"), emptyMap(), BigDecimal("12"), BigDecimal("14"), "FMCG", null, BigDecimal("10"))
        }
        val books = Books(cash)
        return KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }), books, inv, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore()))) to inv
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    /** A talk: the lines (typed, mic), what the last answer must have, and what it must never have. */
    private data class Talk(val typed: List<String>, val mic: List<String>, val must: List<String>, val mustNot: List<String> = emptyList(),
                            val cash: CashBookTotals? = null)

    private val report = listOf("vaanga vendiyadhu", "வாங்க வேண்டியது", "kudukka vendiyadhu")
    private val english = listOf("I don't have", "I couldn't", "Which report", "Okay Owner")

    private val talks = listOf(
        // Said alone: the question, never the money-to-collect report.
        Talk(listOf("report"), listOf("ரிப்போர்ட்"), listOf("report", "Sales", "expiry"), report + english),
        Talk(listOf("report kudu"), listOf("ரிப்போர்ட் குடு"), listOf("report", "Sales"), report + english),
        Talk(listOf("cash"), listOf("கேஷ்"), listOf("cash", "payment"), report + english),
        Talk(listOf("UPI"), listOf("யுபிஐ"), listOf("UPI", "payment"), report + english),
        Talk(listOf("indha maasam"), listOf("இந்த மாசம்"), listOf("Sales"), report + listOf("meaning") + english),
        Talk(listOf("Lux"), listOf("லக்ஸ்"), listOf("Lux Soap", "stock"), listOf("Evlo amount", "எவ்வளவு, யார்") + english),
        // The answer to Kai's question: that thing, whole.
        Talk(listOf("report", "sales"), listOf("ரிப்போர்ட்", "சேல்ஸ்"), listOf("sales", "Daily Cash Note"), report + english),
        Talk(listOf("report", "stock"), listOf("ரிப்போர்ட்", "ஸ்டாக்"), listOf("Low stock"), report),
        Talk(listOf("report", "expiry"), listOf("ரிப்போர்ட்", "எக்ஸ்பைரி"), listOf("expiry"), report + listOf("meaning")),
        Talk(listOf("report", "kudukkal vaangal"), listOf("ரிப்போர்ட்", "கொடுக்கல் வாங்கல்"), listOf("₹500", "₹4,000")),
        Talk(listOf("indha maasam", "selavu"), listOf("இந்த மாசம்", "செலவு"), listOf("₹1,200"), listOf("OUT-a podunga", "OUT-ஆ போடுங்க"),
            cash = CashBookTotals(5000.0, 1200.0, 4)),
        Talk(listOf("indha maasam", "sales"), listOf("இந்த மாசம்", "சேல்ஸ்"), listOf("₹5,000"), cash = CashBookTotals(5000.0, 1200.0, 4)),
        Talk(listOf("cash", "paakkanum"), listOf("கேஷ்", "பார்க்கணும்"), listOf("₹3,000"), report, cash = CashBookTotals(5000.0, 1200.0, 4)),
        Talk(listOf("UPI", "aamaa"), listOf("யுபிஐ", "ஆமா"), listOf("₹2,000"), report, cash = CashBookTotals(5000.0, 1200.0, 4)),
        Talk(listOf("UPI", "payment note pannanum"), listOf("யுபிஐ", "பேமெண்ட் நோட் பண்ணணும்"), listOf("Kumar 500"), report),
        Talk(listOf("Lux", "stock"), listOf("லக்ஸ்", "ஸ்டாக்"), listOf("60")),
        // What the records have, from them.
        Talk(listOf("Kumar kanakku"), listOf("குமார் கணக்கு"), listOf("₹500"), listOf("Net check", "நெட் செக்")),
        Talk(listOf("low stock"), listOf("லோ ஸ்டாக்"), listOf("Maggi"), english + listOf("verify")),
        Talk(listOf("Intha masam Selavu evlo"), listOf("இந்த மாசம் செலவு எவ்வளவு"), listOf("₹1,200"), listOf("OUT-a podunga", "OUT-ஆ போடுங்க"),
            cash = CashBookTotals(5000.0, 1200.0, 4)),
        // …and what they don't have, plainly, in the owner's language (never English to a Tanglish / Tamil line).
        Talk(listOf("Intha masam Selavu evlo"), listOf("இந்த மாசம் செலவு எவ்வளவு"), listOf("Daily Cash Note"), listOf("OUT-a podunga", "OUT-ஆ போடுங்க") + english),
        Talk(listOf("sales"), listOf("சேல்ஸ்"), listOf("Daily Cash Note"), english + listOf("indha information", "இந்த தகவல்")),
        // Said, not asked: never a product.
        Talk(listOf("nalla irukku"), listOf("நல்லா இருக்கு"), listOf("😄"), listOf("product-um illa", "product-உம் இல்ல")),
        // Words not in Kai's lists: asked about, never turned into a report.
        Talk(listOf("statement"), listOf("ஸ்டேட்மெண்ட்"), listOf("?"), report),
        Talk(listOf("neft"), listOf("நெஃப்ட்"), listOf("?"), report),
        // A word that isn't Kai's answer to its own question: a new line, not a guessed report.
        Talk(listOf("report", "blue colour"), listOf("ரிப்போர்ட்", "ப்ளூ கலர்"), listOf("?"), report),
    )

    @Test fun asksWhenItDoesNotUnderstandAndNeverShowsAnUnaskedReport() {
        val fails = mutableListOf<String>()
        for (talk in talks) for (mic in listOf(false, true)) {
            val lines = if (mic) talk.mic else talk.typed
            val (kai, inv) = kai(talk.cash)
            var t: KaiTurn? = null
            for (l in lines) t = runBlocking { kai.ask(l) }
            val a = all(t!!)
            val tag = (if (mic) "mic " else "typed ") + lines.joinToString(" → ") { "«$it»" }
            // The mic's Tamil answer carries the amounts and names; its words may be in Tamil.
            val must = if (mic) talk.must.filter { w -> w.any(Char::isDigit) || w == "?" || w == "Daily Cash Note" || w.first().isUpperCase() && w != "Sales" && w != "Low stock" } else talk.must
            must.firstOrNull { !a.contains(it, ignoreCase = true) }?.let { fails += "$tag lacks '$it': $a" }
            talk.mustNot.firstOrNull { a.contains(it, ignoreCase = true) }?.let { fails += "$tag has '$it': $a" }
            if (mic && Regex("""\b(?:Owner, I|I don't|I couldn't|Which report)""").containsMatchIn(a)) fails += "$tag answered in English: $a"
            if (inv.moves.isNotEmpty() || inv.payments.isNotEmpty()) fails += "$tag wrote something"
        }
        assertTrue("${fails.size} fail:\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
