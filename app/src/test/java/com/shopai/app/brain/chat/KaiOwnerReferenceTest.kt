package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemorySource
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Owner-defined references: the owner keeps two customers both named "Kumar" apart as "Kumar Anna" (c1, Sri Stores)
 * and "Kumar House" (c2, Velachery). The canonical record id is the identity; a reference resolves to it, a bare
 * "Kumar" is asked about (never the last one talked about), "rendu Kumar" lists each one, and a write only ever goes
 * to a CONFIRMED record. References are stored per business and survive a restart (a new memory over the same store).
 * The fake books / tools hold balances by record id and change only on Confirm.
 */
class KaiOwnerReferenceTest {

    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)

    private class P(val id: String, val name: String, var pending: BigDecimal, val city: String? = null, val details: String? = null)

    private class Books(val ps: List<P>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = ps.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), null) })
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val ps: List<P>) : KaiTools {
        val saved = mutableListOf<ActionPlan>()
        override suspend fun parties(name: String) =
            ps.filter { it.name.contains(name, true) }.map { PartyMatch(it.id, it.name, true, null, it.pending, city = it.city, details = it.details) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p${saved.size}-${System.nanoTime()}", kind, partyName, partyId, amount, mode,
                balanceBefore = ps.firstOrNull { it.id == partyId }?.pending, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            val p = ps.first { it.id == plan.partyId }
            p.pending = if (plan.kind == PlanKind.PAYMENT_IN) p.pending - plan.amount else p.pending + plan.amount
            saved += plan
            return ActionOutcome.Done("TXN-${saved.size}", p.pending)
        }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
    }

    private class Access(val ps: List<P>, store: KaiMemoryStore, val business: String = "biz-A") : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory = memory.also { it.open(business, "owner-A") }
        override suspend fun entities(): List<KnownEntity> = ps.map { KnownEntity(it.id, it.name, MemoryType.CUSTOMER_ALIAS) }
    }

    private inner class Shop(val store: KaiMemoryStore = InMemoryKaiMemoryStore(), teach: Boolean = true) {
        val ps = listOf(P("c1", "Kumar", BigDecimal.ZERO, details = "Sri Stores"), P("c2", "Kumar", BigDecimal.ZERO, city = "Velachery"),
            P("c3", "Selvi", BigDecimal("1000")))
        val tools = Tools(ps)
        val books = Books(ps)

        init {
            if (teach) runBlocking {
                val m = Access(ps, store).current()
                m.teachEntity("Kumar Anna", KnownEntity("c1", "Kumar", MemoryType.CUSTOMER_ALIAS), MemorySource.OWNER_CONFIRMED)
                m.teachEntity("Kumar House", KnownEntity("c2", "Kumar", MemoryType.CUSTOMER_ALIAS), MemorySource.OWNER_CONFIRMED)
            }
        }

        fun agent(business: String = "biz-A") =
            KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }, random = Random(1)), books, tools, now = { now }, memory = Access(ps, store, business))

        fun balance(id: String): String = ps.first { it.id == id }.pending.stripTrailingZeros().toPlainString()
        fun saved() = tools.saved.map { "${it.partyId}/${it.kind}/${it.amount.stripTrailingZeros().toPlainString()}" }
    }

    private fun KaiAgent.say(vararg lines: String): String = runBlocking { lines.map { ask(it).reply.text }.last() }

    // ------------------------------------------------------------ the acceptance journey

    @Test fun acceptance_journey_two_kumars_by_owner_reference() {
        val shop = Shop()
        val kai = shop.agent()
        kai.say("Kumar Anna-ku 500 enaku tharanum", "save panniko")
        assertTrue(kai.say("seri").contains("Save aagiduchu"))
        kai.say("Kumar House enaku 600 tharanum", "save panniko", "seri")
        assertEquals(listOf("c1/CREDIT_GIVEN/500", "c2/CREDIT_GIVEN/600"), shop.saved())

        val which = kai.say("Kumar evlo tharanum?")
        assertTrue(which, which.contains("Kumar Anna — ₹500") && which.contains("Kumar House — ₹600"))

        val house = kai.say("Kumar House")
        assertTrue(house, house.contains("Kumar House") && house.contains("₹600"))

        kai.say("Kumar House enaku 400 tharanum", "save panniko")
        val saved = kai.say("seri")
        assertTrue(saved, saved.contains("Kumar House") && saved.contains("₹1,000"))
        assertEquals("1000", shop.balance("c2"))
        assertEquals("500", shop.balance("c1"))
        assertTrue(kai.say("Kumar House evlo tharanum?").contains("₹1,000"))
        assertTrue(kai.say("Kumar Anna evlo tharanum?").contains("₹500"))
    }

    // ------------------------------------------------------------ resolution

    @Test fun reference_pins_the_canonical_record_for_a_write_without_asking() {
        val shop = Shop()
        val kai = shop.agent()
        val draft = runBlocking { kai.ask("Kumar House enaku 600 tharanum"); kai.ask("save panniko") }
        assertEquals("Kumar House — ₹600", draft.card!!.lines.first())
        kai.say("seri")
        assertEquals(listOf("c2/CREDIT_GIVEN/600"), shop.saved())
    }

    @Test fun bare_name_write_with_two_records_asks_and_saves_nothing() {
        val shop = Shop()
        val kai = shop.agent()
        val r = kai.say("Kumar enaku 500 tharanum")
        assertTrue(r, r.contains("Kumar Anna") && r.contains("Kumar House"))
        kai.say("seri")
        assertTrue(shop.saved().isEmpty())
    }

    @Test fun bare_name_question_asks_even_right_after_the_other_kumar_was_discussed() {
        val shop = Shop()
        val kai = shop.agent()
        kai.say("Kumar House enaku 600 tharanum", "save panniko", "seri")
        val r = kai.say("Kumar evlo tharanum?")
        assertTrue(r, r.contains("1. Kumar Anna") && r.contains("2. Kumar House"))
    }

    @Test fun pronoun_follow_up_means_the_record_just_talked_about() {
        val shop = Shop()
        val kai = shop.agent()
        kai.say("Kumar House enaku 600 tharanum", "save panniko", "seri")
        val r = kai.say("Avan innum 200 tharanum")
        assertTrue(r, r.contains("Kumar House") && r.contains("₹200"))
        kai.say("save panniko", "seri")
        assertEquals(listOf("c2/CREDIT_GIVEN/600", "c2/CREDIT_GIVEN/200"), shop.saved())
        assertTrue(kai.say("Avan evlo tharanum?").contains("₹800"))
    }

    @Test fun payment_received_through_a_reference_reduces_that_record_only() {
        val shop = Shop()
        shop.ps[1].pending = BigDecimal("600")
        val kai = shop.agent()
        kai.say("Kumar House 200 kuduthutaan")
        kai.say("seri")
        assertEquals(listOf("c2/PAYMENT_IN/200"), shop.saved())
        assertEquals("400", shop.balance("c2"))
        assertEquals("0", shop.balance("c1"))
    }

    // ------------------------------------------------------------ all / both / total

    @Test fun rendu_kumar_lists_each_record_separately_without_a_total() {
        val shop = Shop()
        shop.ps[0].pending = BigDecimal("500"); shop.ps[1].pending = BigDecimal("600")
        val r = shop.agent().say("Rendu Kumar-oda balance sollu")
        assertTrue(r, r.contains("1. Kumar Anna — ₹500") && r.contains("2. Kumar House — ₹600"))
        assertFalse(r, r.contains("₹1,100"))
    }

    @Test fun both_kumar_details_in_english_lists_each_record() {
        val shop = Shop()
        shop.ps[0].pending = BigDecimal("500"); shop.ps[1].pending = BigDecimal("600")
        val r = shop.agent().say("Both Kumar details sollu")
        assertTrue(r, r.contains("Kumar Anna — ₹500") && r.contains("Kumar House — ₹600"))
        assertFalse(r, r.contains("₹1,100"))
    }

    @Test fun combined_total_only_when_the_owner_asks_for_it() {
        val shop = Shop()
        shop.ps[0].pending = BigDecimal("500"); shop.ps[1].pending = BigDecimal("600")
        val kai = shop.agent()
        kai.say("Kumar evlo tharanum?")
        val r = kai.say("rendu perum total evlo?")
        assertTrue(r, r.contains("Kumar Anna — ₹500") && r.contains("Kumar House — ₹600") && r.contains("₹1,100"))
    }

    @Test fun read_questions_never_write() {
        val shop = Shop()
        val kai = shop.agent()
        kai.say("Kumar evlo tharanum?", "Kumar House", "Rendu Kumar-oda balance sollu", "Kumar Anna evlo tharanum?", "seri")
        assertTrue(shop.saved().isEmpty())
    }

    // ------------------------------------------------------------ learning / correction

    @Test fun reference_learned_from_explicit_context_is_used_for_the_next_write() {
        val shop = Shop(teach = false)
        val kai = shop.agent()
        val learned = kai.say("Kumar Mama-na Sri Stores Kumar")
        assertTrue(learned, learned.contains("`Kumar Mama`") && learned.contains("Sri Stores"))
        kai.say("Kumar Mama enaku 100 tharanum", "save panniko", "seri")
        assertEquals(listOf("c1/CREDIT_GIVEN/100"), shop.saved())
    }

    @Test fun reference_without_a_distinguishing_word_is_asked_then_learned_from_the_pick() {
        val shop = Shop(teach = false)
        val kai = shop.agent()
        val asked = kai.say("Kumar Bro-na Kumar")
        assertTrue(asked, asked.contains("`Kumar Bro`") && asked.contains("1.") && asked.contains("2."))
        val learned = kai.say("1")
        assertTrue(learned, learned.contains("`Kumar Bro` = Kumar (Sri Stores)"))
        kai.say("Kumar Bro enaku 70 tharanum", "save panniko", "seri")
        assertEquals(listOf("c1/CREDIT_GIVEN/70"), shop.saved())
    }

    @Test fun correction_drops_the_reference_and_asks_again() {
        val shop = Shop()
        val kai = shop.agent()
        val asked = kai.say("Illai bro, Kumar Anna vera Kumar")
        assertTrue(asked, asked.contains("`Kumar Anna`") && asked.contains("Velachery"))
        val learned = kai.say("Velachery")
        assertTrue(learned, learned.contains("`Kumar Anna` = Velachery Kumar"))
        kai.say("Kumar Anna enaku 50 tharanum", "save panniko", "seri")
        assertEquals(listOf("c2/CREDIT_GIVEN/50"), shop.saved())
    }

    // ------------------------------------------------------------ persistence / scope

    @Test fun reference_survives_a_restart_over_the_same_store() {
        val store = InMemoryKaiMemoryStore()
        val shop = Shop(store, teach = false)
        shop.agent().say("Kumar Kadai-na Velachery Kumar")
        val afterRestart = shop.agent()
        afterRestart.say("Kumar Kadai enaku 300 tharanum", "save panniko", "seri")
        assertEquals(listOf("c2/CREDIT_GIVEN/300"), shop.saved())
    }

    @Test fun reference_belongs_to_its_business_only() {
        val store = InMemoryKaiMemoryStore()
        val shop = Shop(store, teach = false)
        shop.agent().say("Kumar Kadai-na Velachery Kumar")
        val other = shop.agent(business = "biz-B")
        val r = other.say("Kumar Kadai enaku 300 tharanum")
        assertTrue(r, r.contains("Kumar (Sri Stores)") && r.contains("Velachery Kumar"))
        other.say("seri")
        assertTrue(shop.saved().isEmpty())
    }

    // ------------------------------------------------------------ the pieces

    @Test fun memory_reports_only_the_aliases_the_owner_said() = runBlocking {
        val store = InMemoryKaiMemoryStore()
        val shop = Shop(store)
        val access = Access(shop.ps, store)
        val applied = access.current().apply("Kumar House enaku 600 tharanum", access.entities())
        assertEquals("Kumar enaku 600 tharanum", applied.text)
        assertEquals(listOf("c2"), applied.entities.map { it.second.id })
    }

    @Test fun resolver_confidence_and_owner_reference_step() {
        val c1 = PartyMatch("c1", "Kumar", true, null, BigDecimal.ZERO, details = "Sri Stores")
        val c2 = PartyMatch("c2", "Kumar", true, null, BigDecimal.ZERO, city = "Velachery")
        val both = listOf(c1, c2)
        assertEquals(KaiEntityResolver.Confidence.AMBIGUOUS, KaiEntityResolver.resolve("Kumar", "Kumar evlo", both).confidence)
        assertEquals(KaiEntityResolver.Confidence.UNKNOWN, KaiEntityResolver.resolve("Ravi", "Ravi evlo", both).confidence)
        val pinned = KaiEntityResolver.resolve("Kumar", "Kumar evlo", both, referenceId = "c2")
        assertEquals(KaiEntityResolver.Confidence.CONFIRMED, pinned.confidence)
        assertEquals("c2", (pinned as KaiEntityResolver.Result.One).party.id)
        // A reference to a record that is not one of these is not used.
        assertEquals(KaiEntityResolver.Confidence.AMBIGUOUS, KaiEntityResolver.resolve("Kumar", "Kumar evlo", both, referenceId = "c9").confidence)
    }

    @Test fun reference_words_next_to_the_name() {
        assertEquals("Kumar Anna", KaiEntityResolver.referenceIn("Kumar Anna-ku 500 enaku tharanum", "Kumar"))
        assertEquals("Kumar Shop", KaiEntityResolver.referenceIn("Kumar Shop enaku 300 tharanum", "Kumar"))
        assertNull(KaiEntityResolver.referenceIn("Kumar-ku 500 kuduthen", "Kumar"))
        assertNull(KaiEntityResolver.referenceIn("Kumar enaku 500 tharanum", "Kumar"))
    }
}
