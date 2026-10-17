package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
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
 * Kai goes where the owner means, by meaning — not by a word in the line (owner, 17 Oct 2026: "kai owner soldratha understand
 * panni correct-a flow-ku poganum; puriyala-na question kettu antha flow-ku poganum"). Each group is one meaning said many ways,
 * typed and as the ta-IN mic writes it: an entry to note goes to the entry (its amount asked when missing, never a balance
 * report), a name with an amount and nothing else is asked which way, a product line after "stock add pannu" is stock in, a
 * question is answered from the records — and nothing is written before Confirm.
 */
class KaiMeaningTest {
    private val now = LocalDateTime.of(2026, 10, 17, 11, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c0", "Arun", null, 2000.0, "2026-10-20T00:00:00Z"), PartySummary("c1", "Kumar", null, 500.0, null)),
            suppliers = listOf(PartySummary("s0", "Sri Balaji Stores", null, 4000.0, null)))
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

    private inner class Shop {
        val inv = Inventory().also { i ->
            i.parties.clear()
            i.parties += PartyMatch("c0", "Arun", true, null, BigDecimal(2000)); i.parties += PartyMatch("c1", "Kumar", true, null, BigDecimal(500))
            i.parties += PartyMatch("s0", "Sri Balaji Stores", false, null, BigDecimal(4000))
            i.items += Inventory.Item("p1", "Rice", "KG", BigDecimal("100"), mapOf("BAG" to BigDecimal("25")), BigDecimal("50"), BigDecimal("60"), "Grocery", null)
        }
        val tools = Tools(inv)
        val books = Books()
        val kai = KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }), books, tools, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore())))
        val log = StringBuilder()
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { t ->
            log.append("\n   » $line\n     ${t.reply.text.replace("\n", " ")}")
            t.card?.let { c -> log.append(" | ${c.lines.joinToString(" | ")} {${c.buttons.joinToString { it.label }}}") }
        }
        fun nothingWritten() = inv.payments.isEmpty() && inv.moves.isEmpty()
    }

    private val reports = setOf(ChatIntent.CUSTOMER_BALANCE, ChatIntent.TOTAL_PAYABLE, ChatIntent.TOTAL_RECEIVABLE, ChatIntent.DAILY_CASH)

    /** Every line typed, and again as the mic writes it (when it would hear it differently). */
    private fun bothWays(lines: List<String>): List<List<String>> =
        listOf(lines) + listOfNotNull(lines.map { KaiMicForm.of(it) ?: it }.takeIf { it != lines })

    private fun all(t: KaiTurn) = t.reply.text + " " + t.card?.lines.orEmpty().joinToString(" ")

    @Test fun anEntryToNoteIsAnEntryEveryWay() {
        val fails = mutableListOf<String>()
        // (lines, the kind drafted, the person, the amount) — Arun is a customer (an entry is credit), Sri Balaji Stores a supplier (debit).
        val cases = listOf(
            listOf("Arun ku 5000 note pannu") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            listOf("naan Arun ku 5000 note pannu") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            listOf("Arun 5000 ezhudhu") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            listOf("Arun ku 5000 entry podu") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            listOf("Arun ku 5000 add pannu") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            listOf("அருணுக்கு 5000 நோட் பண்ணு") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            listOf("Sri Balaji Stores ku 3000 note pannu") to Triple(PlanKind.DEBIT_TAKEN, "Sri Balaji Stores", 3000),
            // The amount missing: asked, then the answer finishes the same entry — never the balance report.
            listOf("Arun-ku amount note pannu", "5000") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            listOf("Arun ku note pannu", "4000") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 4000),
            listOf("Arun account la podu", "2000") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 2000),
            listOf("அருணுக்கு அமௌண்ட் நோட் பண்ணு", "5000") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            // No one named: who and how much asked; "Arun 5000" then is that entry.
            listOf("amount note pannu", "Arun 5000") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            listOf("oru entry podu", "Arun 5000") to Triple(PlanKind.CREDIT_GIVEN, "Arun", 5000),
            // A new name: both sides offered — the owner's word picks one, typed or said.
            listOf("Selvi ku 2000 note pannu", "naan tharanum") to Triple(PlanKind.DEBIT_TAKEN, "Selvi", 2000),
            listOf("Selvi ku 2000 note pannu", "aama", "credit") to Triple(PlanKind.CREDIT_GIVEN, "Selvi", 2000),
            listOf("amount note pannu", "Selvi 2000", "avanga tharanum") to Triple(PlanKind.CREDIT_GIVEN, "Selvi", 2000),
        )
        for ((lines0, want) in cases) for (lines in bothWays(lines0)) {
            val s = Shop()
            val turns = lines.map { s.say(it) }
            turns.dropLast(1).forEach { t -> if (t.reply.intent in reports) fails += "$lines: a report while asking${s.log}" }
            val p = s.tools.prepared.lastOrNull()
            when {
                p == null -> fails += "$lines: no draft${s.log}"
                p.kind != want.first || !p.partyName.equals(want.second, true) && !com.shopai.app.util.NameSound.same(p.partyName, want.second) ||
                    p.amount.compareTo(BigDecimal(want.third)) != 0 -> fails += "$lines: ${p.kind} ${p.partyName} ${p.amount} (want $want)${s.log}"
            }
            if (!s.nothingWritten()) fails += "$lines: written before Confirm${s.log}"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun aHalfSaidLineIsAskedNeverAReport() {
        val fails = mutableListOf<String>()
        // A name and an amount with nothing that says which way — "account", "balance", "kanakku" included: one question, the records'
        // figure in it, nothing drafted or written.
        for (line in listOf("Arun-ku 5000", "Arun account 5000", "Arun 5000 balance", "அருண் கணக்கு 5000", "Arun kanakku 5000")) for (l in bothWays(listOf(line))) {
            val s = Shop()
            val t = s.say(l.single())
            if (t.reply.intent in reports || t.reply.mood != KaiMood.CLARIFY || !all(t).contains("₹5,000") || s.tools.prepared.isNotEmpty())
                fails += "$l: not asked which way${s.log}"
            if (!s.nothingWritten()) fails += "$l: written${s.log}"
        }
        // An entry with nothing else: asked, never "`note`-na enna?" and never a report.
        for (line in listOf("amount note pannu", "oru entry podu", "Arun-ku amount note pannu", "Arun ku note pannu")) for (l in bothWays(listOf(line))) {
            val s = Shop()
            val t = s.say(l.single())
            if (t.reply.intent in reports || t.reply.text.contains("meaning") || t.reply.text.contains("mean pannureengala")) fails += "$l: ${t.reply.text}"
            if (!s.nothingWritten()) fails += "$l: written${s.log}"
        }
        // "venam" to "Evlo amount?": dropped, nothing kept.
        for (no in listOf("venam", "வேண்டாம்", "cancel")) {
            val s = Shop()
            s.say("Arun-ku amount note pannu")
            val t = s.say(no)
            if (s.tools.prepared.isNotEmpty() || !Regex("""save|சேமிக்க""").containsMatchIn(t.reply.text)) fails += "«$no»: ${s.log}"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun whatTheOwnerAlreadySaidIsKept() {
        val fails = mutableListOf<String>()
        // "Stock add pannu" → "Rice 2 bag": stock in, never asked in or out again (draft only).
        for (lines in bothWays(listOf("Stock add pannu", "Rice 2 bag")) + listOf(listOf("ஸ்டாக் ஆட் பண்ணு", "அரிசி 2 மூட்டை"))) {
            val s = Shop()
            lines.forEach { s.say(it) }
            val last = s.log.toString().substringAfterLast("»")
            if (!last.contains("150 kg") || last.contains("vithucha") || last.contains("வித்துச்சா")) fails += "$lines: not a stock-in draft${s.log}"
            if (!s.nothingWritten()) fails += "$lines: written${s.log}"
        }
        // "kuda" / "kooda" is "kitta", never part of the name.
        for (line in listOf("Arun kuda 10000 vaanganum", "Arun kooda 10000 vaanganum")) {
            val s = Shop()
            val t = s.say(line)
            val st = s.kai.conversationState.stated
            if (st == null || st.person != "Arun" || st.amount?.compareTo(BigDecimal(10000)) != 0) fails += "$line: ${t.reply.text}"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun questionsAreAnsweredInTheChatsLanguage() {
        val fails = mutableListOf<String>()
        // Real questions still get the records' answer.
        for (line in listOf("Arun balance", "Arun evlo tharanum", "Arun pending", "அருண் எவ்வளவு தரணும்")) {
            val s = Shop()
            val t = s.say(line)
            if (t.reply.intent != ChatIntent.CUSTOMER_BALANCE || !t.reply.text.contains("₹2,000")) fails += "$line: ${t.reply.text}"
        }
        // "Arun balance" — words Tanglish shares with English — answered in the chat's language, never switched to English mid-chat.
        Shop().let { s ->
            s.say("Kumar evlo tharanum")
            val t = s.say("Arun balance")
            if (Regex("""\bhas\b|\bowes\b""").containsMatchIn(t.reply.text)) fails += "Tanglish chat: ${t.reply.text}"
        }
        Shop().let { s ->
            s.say("அருண் எவ்வளவு தரணும்")
            val t = s.say("Arun balance")
            if (Regex("""\bhas\b|\bowes\b""").containsMatchIn(t.reply.text)) fails += "Tamil chat: ${t.reply.text}"
        }
        Shop().let { s ->
            s.say("how much does Arun owe")
            val t = s.say("Arun balance")
            if (!Regex("""\bhas\b|\bowes\b|pending\.""").containsMatchIn(t.reply.text)) fails += "English chat: ${t.reply.text}"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
