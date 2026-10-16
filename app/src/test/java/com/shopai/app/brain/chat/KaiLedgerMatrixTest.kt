package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Credit and debit, every way at once (owner, 16 Oct 2026: "onnu onnaiyum fix pannitte pona tired aagum — ellathukum correct-a
 * logic set pannu"). Every name kind (a person, a person in Tamil letters, two-word names, shops with their last word — Tea
 * Shop, Stores, Medicals, Hotel), in the books or new, × every common way of saying who owes / paid whom × amounts in digits and
 * in words — typed, and as the ta-IN mic writes it. Each line must land on the right person, the right side and the right
 * amount: who owes me (credit), whom I owe (debit), who paid me, whom I paid. Nothing is saved before Confirm.
 *
 * What money given / taken means follows who the person is (the books' rule): money a customer gives is their payment, money
 * given to a customer is credit (they owe me); money given to a supplier is my payment, money a supplier gives is debit (I owe
 * them); a person not in the books is asked — the right Credit / Debit offered with the name, never guessed.
 */
class KaiLedgerMatrixTest {
    private val now = LocalDateTime.of(2026, 10, 16, 11, 0)

    /** A name as typed, as the mic writes it, and as the mic writes it with "-ku" (to them); in the books as customer / supplier, or new. */
    data class Name(val typed: String, val mic: String, val micKu: String, val book: String? = null, val customer: Boolean = true)

    enum class Side { OWES_ME, I_OWE, PAID_ME, I_PAID }

    /** A way of saying it: {n} the name, {nk} the name with "-ku", {a} the amount. */
    data class Way(val typed: String, val mic: String, val side: Side)

    private val names = listOf(
        Name("Kumar", "குமார்", "குமாருக்கு", book = "Kumar"),
        Name("Ravi Shankar", "ரவி சங்கர்", "ரவி சங்கருக்கு", book = "Ravi Shankar"),
        Name("Sri Balaji Stores", "ஸ்ரீ பாலாஜி ஸ்டோர்ஸ்", "ஸ்ரீ பாலாஜி ஸ்டோர்ஸுக்கு", book = "Sri Balaji Stores", customer = false),
        Name("Kumar Tea Shop", "குமார் டீ ஷாப்", "குமார் டீ ஷாப்புக்கு"),
        Name("Lakshmi Medicals", "லட்சுமி மெடிக்கல்ஸ்", "லட்சுமி மெடிக்கல்ஸுக்கு"),
        Name("Murugan Hotel", "முருகன் ஹோட்டல்", "முருகன் ஹோட்டலுக்கு"),
        Name("Selvi", "செல்வி", "செல்விக்கு"),
        // A new shop whose person isn't in the books either: the mic's name stays one name in Tamil letters ("மணி டீ ஷாப்").
        Name("Mani Tea Shop", "மணி டீ ஷாப்", "மணி டீ ஷாப்புக்கு"),
    )

    private val ways = listOf(
        Way("{n} enakku {a} tharanum", "{n} எனக்கு {a} தரணும்", Side.OWES_ME),
        Way("{n} kitta {a} vaanganum", "{n} கிட்ட {a} வாங்கணும்", Side.OWES_ME),
        Way("{n} {a} baaki tharanum", "{n} {a} பாக்கி தரணும்", Side.OWES_ME),
        Way("{nk} {a} kadan kuduthen", "{nk} {a} கடன் குடுத்தேன்", Side.OWES_ME),
        Way("naan {nk} {a} tharanum", "நான் {nk} {a} தரணும்", Side.I_OWE),
        Way("{nk} {a} kudukkanum", "{nk} {a} குடுக்கணும்", Side.I_OWE),
        Way("{n} kitta {a} kadan vaanginen", "{n} கிட்ட {a} கடன் வாங்கினேன்", Side.I_OWE),
        Way("{n} {a} kuduthan", "{n} {a} குடுத்தான்", Side.PAID_ME),
        Way("{n} {a} gpay pannaan", "{n} {a} ஜிபே பண்ணான்", Side.PAID_ME),
        Way("{n} kitta irundhu {a} vandhuchu", "{n} கிட்ட இருந்து {a} வந்துச்சு", Side.PAID_ME),
        Way("{nk} {a} kuduthen", "{nk} {a} குடுத்தேன்", Side.I_PAID),
        Way("{nk} {a} anuppinen", "{nk} {a} அனுப்பினேன்", Side.I_PAID),
        Way("{nk} {a} kattinen", "{nk} {a} கட்டினேன்", Side.I_PAID),
    )

    /** (typed, as the mic writes it, the amount). */
    private val amounts = listOf(Triple("1000", "1000", BigDecimal("1000")), Triple("moonu aayiram", "மூவாயிரம்", BigDecimal("3000")))

    private inner class Books(val parties: List<Name>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = parties.filter { it.book != null && it.customer }.mapIndexed { i, n -> PartySummary("c$i", n.book!!, null, 500.0, "2026-10-20T00:00:00Z") },
            suppliers = parties.filter { it.book != null && !it.customer }.mapIndexed { i, n -> PartySummary("s$i", n.book!!, null, 4000.0, "2026-10-20T00:00:00Z") },
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory) : KaiTools by inv {
        val prepared = mutableListOf<ActionPlan>()
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            inv.prepare(kind, partyName, partyId, amount, mode, said).also { prepared += it }
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private fun kai(): Pair<KaiAgent, Tools> {
        val inv = Inventory().also { inv ->
            inv.parties.clear()
            names.filter { it.book != null }.forEachIndexed { i, n ->
                inv.parties += PartyMatch(if (n.customer) "c$i" else "s$i", n.book!!, n.customer, null, BigDecimal(if (n.customer) "500" else "4000"))
            }
        }
        val tools = Tools(inv)
        val books = Books(names)
        return KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }), books, tools, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore()))) to tools
    }

    private fun plain(s: String) = s.lowercase().filter { it.isLetter() || it in '஀'..'௿' }

    /** The side the books give a payment by who the person is (see the class note). */
    private fun expected(n: Name, w: Way): Side = when {
        w.side == Side.PAID_ME && (n.book == null || !n.customer) -> Side.I_OWE
        w.side == Side.I_PAID && (n.book == null || n.customer) -> Side.OWES_ME
        else -> w.side
    }

    /** What Kai made of the line: the side, the person and the amount — from its draft, else the payment it is asking about. */
    private fun outcome(kai: KaiAgent, tools: Tools, t: KaiTurn, value: BigDecimal): Triple<Side, String, BigDecimal?>? {
        tools.prepared.lastOrNull()?.let { p ->
            val side = when (p.kind) { PlanKind.PAYMENT_IN -> Side.PAID_ME; PlanKind.PAYMENT_OUT -> Side.I_PAID; PlanKind.CREDIT_GIVEN -> Side.OWES_ME; PlanKind.DEBIT_TAKEN -> Side.I_OWE }
            return Triple(side, p.partyName, p.amount)
        }
        kai.conversationState.stated?.let { st ->
            val side = if (st.direction == KaiConversationPaymentDirection.PAYMENT_IN) Side.OWES_ME else Side.I_OWE
            return Triple(side, st.label ?: st.person, st.amount)
        }
        // A person not in the books: Kai asks, offering the Credit / Debit for that name (the amount is the line's).
        t.card?.buttons.orEmpty().mapNotNull { it.action as? KaiAction.ChoosePlan }.singleOrNull()?.let { c ->
            val side = when (c.kind) { PlanKind.PAYMENT_IN -> Side.PAID_ME; PlanKind.PAYMENT_OUT -> Side.I_PAID; PlanKind.CREDIT_GIVEN -> Side.OWES_ME; PlanKind.DEBIT_TAKEN -> Side.I_OWE }
            return Triple(side, c.partyName, value.takeIf { t.reply.text.contains(com.shopai.app.brain.KaiFormat.rupees(value.toDouble())) })
        }
        return null
    }

    @Test fun everyNameEveryWayTypedAndSpoken() {
        val fails = mutableListOf<String>()
        var played = 0
        for (n in names) for (w in ways) for ((ta, ma, value) in amounts) for (mic in listOf(false, true)) {
            val line = if (mic) w.mic.replace("{nk}", n.micKu).replace("{n}", n.mic).replace("{a}", ma)
                else w.typed.replace("{nk}", "${n.typed} ku").replace("{n}", n.typed).replace("{a}", ta)
            val (kai, tools) = kai()
            val t = runBlocking { kai.ask(line) }
            played++
            val got = outcome(kai, tools, t, value)
            val want = expected(n, w)
            val reply = t.reply.text.replace("\n", " ").take(140)
            val who = listOfNotNull(n.typed, n.mic, n.book).map(::plain)
            // A shop name that starts with a person in the books ("Kumar Tea Shop", Kumar kept): the same Kumar, or a new one? —
            // asked with the whole name, never merged (the owner's rule).
            val askedWhole = n.book == null && names.any { b -> b.book != null && n.typed.startsWith(b.book + " ") } &&
                Regex("""'(${Regex.escape(n.typed)}|${Regex.escape(n.mic)})'""").containsMatchIn(t.reply.text) &&
                Regex("""puthu|புது|new""").containsMatchIn(t.reply.text)
            when {
                askedWhole -> Unit
                got == null -> fails += "[$want] «$line» → nothing understood: $reply"
                got.first != want -> fails += "[$want] «$line» → ${got.first} ${got.second} ${got.third}: $reply"
                plain(got.second) !in who -> fails += "[${w.side}] «$line» → person «${got.second}»: $reply"
                got.third == null || got.third!!.compareTo(value) != 0 -> fails += "[${w.side}] «$line» → amount ${got.third}: $reply"
            }
            if (tools.inv.payments.isNotEmpty()) fails += "[${w.side}] «$line» saved before Confirm"
        }
        assertTrue("${fails.size} of $played fail:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    /**
     * A person not in the books: Kai says plainly who gave whom and what it would note, and the owner answers by voice as well as
     * by the button ("aama" / "kadan dhaan" / "ஆமா" / "credit" / "venam") — the mic owner has no button to tap.
     */
    @Test fun aNewPersonIsAnsweredByVoiceToo() {
        val cases = listOf(
            Triple("Selvi 1000 kuduthan", "aama", PlanKind.DEBIT_TAKEN), Triple("Selvi 1000 kuduthan", "kadan dhaan", PlanKind.DEBIT_TAKEN),
            Triple("செல்வி 1000 குடுத்தான்", "ஆமா", PlanKind.DEBIT_TAKEN), Triple("Selvi ku 1000 kuduthen", "credit", PlanKind.CREDIT_GIVEN),
            Triple("Selvi ku 1000 kuduthen", "seri", PlanKind.CREDIT_GIVEN), Triple("செல்விக்கு 1000 குடுத்தேன்", "ஆமா கடன்", PlanKind.CREDIT_GIVEN),
        )
        for ((line, answer, kind) in cases) {
            val (kai, tools) = kai()
            val ask = runBlocking { kai.ask(line) }
            assertTrue("«$line» → ${ask.reply.text}", Regex("""puthu per|புது பேர்""").containsMatchIn(ask.reply.text) && ask.reply.text.contains("₹1,000") &&
                Regex("""Aama|ஆமா""").containsMatchIn(ask.reply.text))
            val t = runBlocking { kai.ask(answer) }
            val p = tools.prepared.lastOrNull()
            assertTrue("«$line» «$answer» → ${t.reply.text} ${p?.kind}", p?.kind == kind && p.amount.compareTo(BigDecimal("1000")) == 0 && plain(p.partyName) in listOf("selvi", plain("செல்வி")))
            assertTrue("saved before Confirm", tools.inv.payments.isEmpty())
        }
        for (no in listOf("venam", "வேண்டாம்")) {
            val (kai, tools) = kai()
            runBlocking { kai.ask("Selvi ku 1000 kuduthen") }
            val t = runBlocking { kai.ask(no) }
            assertTrue("«$no» → ${t.reply.text}", tools.prepared.isEmpty() && tools.inv.payments.isEmpty() && Regex("""save|சேமிக்க""").containsMatchIn(t.reply.text))
        }
    }
}
