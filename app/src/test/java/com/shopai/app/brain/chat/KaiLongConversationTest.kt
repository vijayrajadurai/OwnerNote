package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner (16 Oct 2026): "Kai chat-a continue-a use pannumbothu wrong answer varuthu, flow maaruthu, present date and time-a
 * eduthukka maattuthu". A question Kai asked ("Due date eppa?") and a draft on screen kept every later line: "ippo time enna" got
 * "idhu Selvam ₹3,000 payment pathiyaa?", a reminder became Selvam's due date, "innaiku yaar yaar tharanum" became the draft's
 * due date, and "Selvam eppa tharuvaan" answered with Kumar's draft. Each line is read for what it says (principle: context):
 * a question is never an answer or an edit, a reminder is a reminder, another person is that person — across midnight, typed
 * and as the mic writes it. Nothing is saved before Confirm.
 */
class KaiLongConversationTest {
    private var clock: LocalDateTime = LocalDateTime.of(2026, 10, 15, 23, 50)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Kumar", null, 4000.0, "2026-10-16T00:00:00Z"), PartySummary("c2", "Ramesh", null, 2500.0, "2026-10-14T00:00:00Z")),
            suppliers = listOf(PartySummary("s1", "ABC Traders", null, 10000.0, "2026-10-17T00:00:00Z")),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private fun kai(): Pair<KaiAgent, Inventory> {
        val inv = Inventory().also {
            it.parties.clear()
            it.parties += PartyMatch("c1", "Kumar", true, null, BigDecimal("4000"))
            it.parties += PartyMatch("c2", "Ramesh", true, null, BigDecimal("2500"))
            it.parties += PartyMatch("s1", "ABC Traders", false, null, BigDecimal("10000"))
            it.items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), null, null, "Grocery", null)
        }
        val books = Books()
        return KaiAgent(KaiBusinessBrain(books, today = { clock.toLocalDate() }), books, inv, { clock }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore()))) to inv
    }

    /** A line at a time of the night, and what its answer must (and must not) say. */
    private data class Step(val at: String, val typed: String, val has: List<String>, val hasNot: List<String> = emptyList())

    private val steps = listOf(
        Step("23:50", "Selvam enakku 3000 tharanum", listOf("Selvam", "3,000")),
        Step("23:51", "Colgate stock evlo", listOf("240")),
        Step("23:52", "Ramesh evlo tharanum", listOf("2,500"), listOf("Selvam")),
        Step("00:05", "innaiku date enna", listOf("16"), listOf("Selvam")),
        Step("00:05", "ippo time enna", listOf("12:05"), listOf("Selvam", "pathiyaa", "பத்தியா")),
        Step("00:06", "naalaiku 10 manikku Ravi call panna remind pannu", listOf("Ravi"), listOf("Selvam", "3,000")),
        Step("00:07", "Kumar 500 kuduthan", listOf("Kumar", "500"), listOf("Selvam")),
        Step("00:09", "innaiku yaar yaar tharanum", listOf("Kumar"), listOf("Selvam ₹500", "Due date: innaikku", "Due date: இன்னைக்கு")),
        Step("00:10", "Selvam eppa tharuvaan", listOf("Selvam"), listOf("500", "₹10", "Kumar")),
    )

    @Test fun eachLineIsReadForWhatItSaysAcrossMidnight() {
        val fails = mutableListOf<String>()
        for (mic in listOf(false, true)) {
            val (kai, inv) = kai()
            for (s in steps) {
                val (h, m) = s.at.split(":").map { it.toInt() }
                clock = if (h >= 12) LocalDateTime.of(2026, 10, 15, h, m) else LocalDateTime.of(2026, 10, 16, h, m)
                val line = if (mic) KaiMicForm.of(s.typed) ?: s.typed else s.typed
                val t = runBlocking { kai.ask(line) }
                val all = t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")
                // (the mic's Tamil reply says the same names and figures; the Tanglish words of a "must not" check are looked for in both)
                s.has.forEach { w -> if (!all.contains(w) && !(mic && tamilOf(w)?.let { all.contains(it) } == true)) fails += "[${if (mic) "mic" else "typed"} ${s.at}] «$line» lacks «$w»: $all" }
                s.hasNot.forEach { w -> if (all.contains(w)) fails += "[${if (mic) "mic" else "typed"} ${s.at}] «$line» has «$w»: $all" }
            }
            if (inv.payments.isNotEmpty()) fails += "[${if (mic) "mic" else "typed"}] saved before Confirm"
        }
        assertTrue(fails.size.toString() + " fails:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    /**
     * A draft on screen while the owner asks about the day and about someone else: "innaiku yaar yaar tharanum" and "naalaiku
     * evlo varanum" are the books' lists, never the draft's due date; "Selvam eppa tharuvaan" is Selvam's own payment.
     */
    @Test fun aQuestionNeverDatesTheDraftOnScreen() {
        val steps = listOf(
            Step("23:50", "Selvam enakku 3000 tharanum", listOf("Selvam", "3,000")),
            Step("23:51", "naalaiku", listOf("Selvam", "3,000")),
            Step("23:52", "Kumar 500 kuduthan", listOf("Kumar", "500")),
            Step("23:53", "innaiku yaar yaar tharanum", emptyList(), listOf("Due date", "Add pannalama", "சேர்க்கட்டுமா")),
            Step("23:54", "Selvam eppa tharuvaan", listOf("Selvam", "3,000"), listOf("Kumar", "500")),
            Step("23:56", "Ramesh 2000 kuduthan", listOf("Ramesh", "2,000")),
            Step("23:57", "naalaiku evlo varanum", listOf("Kumar"), listOf("Due date", "Ramesh — ₹2,000")),
        )
        val fails = mutableListOf<String>()
        for (mic in listOf(false, true)) {
            val (kai, inv) = kai()
            for (s in steps) {
                val (h, m) = s.at.split(":").map { it.toInt() }
                clock = LocalDateTime.of(2026, 10, 15, h, m)
                val line = if (mic) KaiMicForm.of(s.typed) ?: s.typed else s.typed
                val t = runBlocking { kai.ask(line) }
                val all = t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")
                s.has.forEach { w -> if (!all.contains(w) && !(mic && tamilOf(w)?.let { all.contains(it) } == true)) fails += "[${if (mic) "mic" else "typed"} ${s.at}] «$line» lacks «$w»: $all" }
                s.hasNot.forEach { w -> if (all.contains(w) || mic && tamilOf(w)?.let { all.contains(it) } == true) fails += "[${if (mic) "mic" else "typed"} ${s.at}] «$line» has «$w»: $all" }
            }
            if (inv.payments.isNotEmpty()) fails += "[${if (mic) "mic" else "typed"}] saved before Confirm"
        }
        assertTrue(fails.size.toString() + " fails:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    private fun tamilOf(w: String) = mapOf("Selvam" to "செல்வம்", "Kumar" to "குமார்", "Ravi" to "ரவி", "Ramesh" to "ரமேஷ்", "Add pannalama" to "சேர்க்கட்டுமா")[w]
}
