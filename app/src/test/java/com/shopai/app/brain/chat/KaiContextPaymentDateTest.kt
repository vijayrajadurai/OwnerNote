package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ScheduleResult
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
 * The real device conversations: payment direction from grammar, short follow-ups that keep the
 * context ("10", "eppa?", "next month 10"), a day without a month is asked (never guessed),
 * business questions answered from the records (never a reminder), nearby places never answered
 * from the books, and nothing written or invented along the way.
 */
class KaiContextPaymentDateTest {
    // Saturday 3 October 2026, 4:20 PM.
    private val now = LocalDateTime.of(2026, 10, 3, 16, 20)

    private class Books(val empty: Boolean = false) : KaiBooks {
        override suspend fun snapshot() = if (empty) BusinessSnapshot(customers = emptyList(), suppliers = emptyList()) else BusinessSnapshot(
            customers = listOf(
                PartySummary("c1", "Kumar", "9000000001", 3000.0, "2026-10-03T00:00:00Z"),
                PartySummary("c2", "Ravi", null, 1500.0, "2026-10-20T00:00:00Z"),
            ),
            suppliers = listOf(PartySummary("s1", "Ramesh", null, 2500.0, "2026-10-03T00:00:00Z")),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Records every write: nothing may be drafted, posted or scheduled by these conversations unless asked. */
    private class Tools : KaiTools {
        val scheduled = mutableListOf<KaiReminder>()
        val writes = mutableListOf<String>()
        override suspend fun parties(name: String) = listOf(
            PartyMatch("c1", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("3000.00")),
            PartyMatch("s1", "Ramesh", customer = false, phone = null, balance = BigDecimal("2500.00")),
        ).filter { it.name.contains(name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan {
            writes += "prepare $kind"
            return ActionPlan("p-$partyName", kind, partyName, partyId, amount, mode, said = said)
        }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { writes += "confirm"; return ActionOutcome.Done("X", null) }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { scheduled += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders() = scheduled.filter { it.open }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K"
    }

    private val tools = Tools()
    private fun kai(books: Books = Books(), at: LocalDateTime = now) =
        KaiAgent(KaiBusinessBrain(books, today = { at.toLocalDate() }, random = Random(1)), books, tools, now = { at })
    private fun KaiAgent.say(text: String) = runBlocking { ask(text) }
    private fun noWrites() {
        assertTrue("nothing drafted / posted: ${tools.writes}", tools.writes.isEmpty())
        assertTrue("no reminder: ${tools.scheduled.map { it.title }}", tools.scheduled.isEmpty())
    }

    // ------------------------------------------------------------ TEST 1, 2: direction

    @Test
    fun test01_kumaranOwesTheOwner() {
        val k = kai()
        val t = k.say("Kumaran enaku 3000 tharanum")
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, k.conversationState.pendingPaymentDirection)
        // "Kumaran" is not the "Kumar" in the books — never the wrong person.
        assertEquals("Kumaran", k.conversationState.pendingEntity)
        assertEquals(BigDecimal("3000.00"), k.conversationState.pendingAmount)
        assertTrue(t.reply.text, t.reply.text.contains("Kumaran") && t.reply.text.contains("₹3,000") && t.reply.text.contains("collect"))
        noWrites()
    }

    @Test
    fun test02_ownerOwesKumaran() {
        val k = kai()
        val t = k.say("nan kumaran ku 3000 tharanum")
        assertEquals(KaiConversationPaymentDirection.PAYMENT_OUT, k.conversationState.pendingPaymentDirection)
        assertEquals("Kumaran", k.conversationState.pendingEntity)
        assertTrue(t.reply.text, t.reply.text.contains("pay"))
        noWrites()
    }

    @Test
    fun receivableVariationsInTheConversation() {
        for (t in listOf("Kumar enaku 3000 tharanum", "Kumar enakku 3000 tharanum", "Kumar enna 3000 tharanum", "Kumar enakku 3k tharanum",
            "Kumar 3000 kudukanum", "Kumar kitta 3000 vanganu", "Kumar enakku 3000 kudukanum", "குமார் எனக்கு 3000 தரணும்", "குமார் எனக்கு 3000 தர வேண்டும்")) {
            val k = kai()
            k.say(t)
            assertEquals(t, KaiConversationPaymentDirection.PAYMENT_IN, k.conversationState.pendingPaymentDirection)
            assertEquals(t, BigDecimal("3000.00"), k.conversationState.pendingAmount)
            assertEquals(t, "Kumar", k.conversationState.pendingEntity)
        }
        noWrites()
    }

    @Test
    fun payableVariationsInTheConversation() {
        for (t in listOf("naan Kumar-ku 3000 tharanum", "nan Kumar ku 3000 kudukanum", "Kumar-ku 3000 pay pannanum", "Kumar-ku 3k kudukanum",
            "naan Kumar kitta 3000 kudukanum", "நான் குமாருக்கு 3000 தரணும்", "குமாருக்கு 3000 கொடுக்கணும்")) {
            val k = kai()
            k.say(t)
            assertEquals(t, KaiConversationPaymentDirection.PAYMENT_OUT, k.conversationState.pendingPaymentDirection)
            assertEquals(t, BigDecimal("3000.00"), k.conversationState.pendingAmount)
            assertEquals(t, "Kumar", k.conversationState.pendingEntity)
        }
        noWrites()
    }

    @Test
    fun tamilStatementGetsATamilAnswer() {
        val k = kai()
        val t = k.say("குமார் எனக்கு 3000 தரணும்")
        assertTrue(t.reply.text, t.reply.text.contains("₹3,000") && t.reply.text.contains("collect") && t.reply.text.contains("தேதி"))
    }

    // ------------------------------------------------------------ TEST 3–6: short follow-ups keep the context

    @Test
    fun test03_eppaAsksForTheDueDate() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        val t = k.say("eppa?")
        assertTrue(t.reply.text, t.reply.text.contains("Kumaran") && t.reply.text.contains("₹3,000") && t.reply.text.contains("date"))
        assertFalse(t.reply.text, t.reply.text.contains("clear-ah") || t.reply.text.contains("Yaar pathi"))
        assertEquals(KaiPendingQuestion.DUE_DATE, k.conversationState.pendingQuestion)
    }

    @Test
    fun test04_bareDayAsksWhichMonth() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        val t = k.say("10")
        assertEquals("Owner, indha maasam 10-aa, illa adutha maasam 10-aa?", t.reply.text)
        assertNull("never guessed", k.conversationState.lastDate)
        // The payment is still the same one.
        assertEquals("Kumaran", k.conversationState.pendingEntity)
        assertEquals(BigDecimal("3000.00"), k.conversationState.pendingAmount)
        assertEquals(10, k.conversationState.pendingDay)
    }

    @Test
    fun test05_nextMonth10Resolves() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        val t = k.say("next month 10")
        assertEquals(LocalDate.of(2026, 11, 10), k.conversationState.lastDate)
        // "Kumaran" is not "Kumar": the draft step asks whether it is a separate customer — never merged silently.
        assertEquals("Seri Owner, Kumaran kitta irundhu ₹3,000 adutha maasam 10-m thethi vaanganum. Owner, Kumaran-nu separate customer-aa? Kumar-a?", t.reply.text)
        assertNull(k.conversationState.pendingQuestion)
        assertEquals("IN", k.conversationState.lastPaymentDirection)
        noWrites()
    }

    @Test
    fun test06_indhaMonth10Resolves() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        k.say("indha month 10")
        assertEquals(LocalDate.of(2026, 10, 10), k.conversationState.lastDate)
        for (answer in listOf("this month 10", "indha maasam 10", "intha maasam 10")) {
            val a = kai()
            a.say("Kumaran enaku 3000 tharanum")
            a.say(answer)
            assertEquals(answer, LocalDate.of(2026, 10, 10), a.conversationState.lastDate)
        }
    }

    @Test
    fun dayThenMonthChoiceResolvesTheSamePayment() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        k.say("10")
        val t = k.say("next month 10")
        assertEquals(LocalDate.of(2026, 11, 10), k.conversationState.lastDate)
        assertTrue(t.reply.text, t.reply.text.contains("Kumaran") && t.reply.text.contains("₹3,000"))
        for ((choice, date) in listOf("adutha maasam" to LocalDate.of(2026, 11, 10), "next" to LocalDate.of(2026, 11, 10),
            "indha maasam" to LocalDate.of(2026, 10, 10), "this month" to LocalDate.of(2026, 10, 10))) {
            val a = kai()
            a.say("Kumaran enaku 3000 tharanum")
            a.say("10")
            a.say(choice)
            assertEquals(choice, date, a.conversationState.lastDate)
        }
    }

    @Test
    fun monthFirstThenDay() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        val q = k.say("adutha maasam")
        assertEquals("Owner, adutha maasam endha thethi?", q.reply.text)
        k.say("10")
        assertEquals(LocalDate.of(2026, 11, 10), k.conversationState.lastDate)
    }

    @Test
    fun dayVariationsAllAskTheMonth() {
        for (answer in listOf("10", "10th", "10 தேதி", "10-ம் தேதி", "10th date")) {
            val k = kai()
            k.say("Kumaran enaku 3000 tharanum")
            val t = k.say(answer)
            assertTrue("$answer → ${t.reply.text}", t.reply.text.contains("indha maasam 10") && t.reply.text.contains("adutha maasam 10"))
            assertNull(answer, k.conversationState.lastDate)
        }
    }

    @Test
    fun payableDayAlsoAsksTheMonth() {
        val k = kai()
        k.say("nan kumaran ku 3000 tharanum")
        val t = k.say("10")
        assertTrue(t.reply.text, t.reply.text.contains("adutha maasam 10"))
        val done = k.say("next month 10")
        assertEquals("Seri Owner, Kumaran-ku ₹3,000 adutha maasam 10-m thethi kudukkanum. Add pannalama?", done.reply.text)
        assertEquals(LocalDate.of(2026, 11, 10), done.plan!!.dueDate)
        assertEquals("OUT", k.conversationState.lastPaymentDirection)
    }

    @Test
    fun tamilFollowUpAnswersInTamil() {
        val k = kai()
        k.say("குமார் எனக்கு 3000 தரணும்")
        assertEquals("இந்த மாதம் 10-ஆ Owner, அடுத்த மாதம் 10-ஆ?", k.say("10").reply.text)
        val t = k.say("அடுத்த மாதம் 10")
        assertEquals(LocalDate.of(2026, 11, 10), k.conversationState.lastDate)
        // The books already hold Kumar ₹3,000: the draft step asks same-or-new right away (in Tamil).
        assertEquals("சரி Owner, Kumar கிட்ட இருந்து ₹3,000 அடுத்த மாதம் 10-ம் தேதி வாங்கணும். Owner, Records-ல ஏற்கனவே Kumar ₹3,000 தரணும்-னு இருக்கு. அதே ₹3,000-ஆ, இல்ல புது ₹3,000-ஆ?", t.reply.text)
    }

    @Test
    fun tomorrowAndWeekdayUseKaiTime() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        k.say("naalaiku")
        assertEquals(LocalDate.of(2026, 10, 4), k.conversationState.lastDate)
        val f = kai()
        f.say("Kumaran enaku 3000 tharanum")
        f.say("Friday-ku")
        assertEquals(LocalDate.of(2026, 10, 9), f.conversationState.lastDate)
    }

    @Test
    fun amountCorrectionKeepsThePayment() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        k.say("amount 5000")
        assertEquals(BigDecimal("5000.00"), k.conversationState.pendingAmount)
        assertEquals("Kumaran", k.conversationState.pendingEntity)
        k.say("next month 10")
        assertEquals(BigDecimal("5000.00"), k.conversationState.lastAmount)
        assertEquals(LocalDate.of(2026, 11, 10), k.conversationState.lastDate)
    }

    @Test
    fun missingAmountIsAskedThenTheDate() {
        val k = kai()
        val q = k.say("Kumar-ku cash kudukanum")
        assertEquals("Owner, Kumar-ku evlo kudukkanum?", q.reply.text)
        assertEquals(KaiPendingQuestion.AMOUNT, k.conversationState.pendingQuestion)
        val t = k.say("2500")
        assertEquals(BigDecimal("2500.00"), k.conversationState.pendingAmount)
        assertEquals(KaiPendingQuestion.DUE_DATE, k.conversationState.pendingQuestion)
        assertTrue(t.reply.text, t.reply.text.contains("₹2,500") && t.reply.text.contains("Due date"))
        noWrites()
    }

    @Test
    fun calculatorInTheMiddleDoesNotLoseThePayment() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        assertEquals("1,625", k.say("1250 plus 375 evlo?").reply.text)
        k.say("next month 10")
        assertEquals(LocalDate.of(2026, 11, 10), k.conversationState.lastDate)
        assertEquals("Kumaran", k.conversationState.lastPerson)
    }

    // A bare "10" answers Kai's question only on the very next turn — later it may mean something else.
    @Test
    fun aLaterBareNumberIsNotTakenAsTheDueDay() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        k.say("Rice stock evlo?")
        val t = k.say("10")
        assertNull(k.conversationState.pendingDay)
        assertFalse(t.reply.text, t.reply.text.contains("adutha maasam 10"))
        // The payment is still waiting; a full date still completes it.
        k.say("next month 10")
        assertEquals(LocalDate.of(2026, 11, 10), k.conversationState.lastDate)
        assertEquals("Kumaran", k.conversationState.lastPerson)
    }

    @Test
    fun insideTheShopIsNotLocalDiscovery() {
        assertNull(KaiLocalDiscovery.request("kadai-la Colgate enga irukku?"))
        assertNull(KaiLocalDiscovery.request("kadaila tape enga irukku?"))
    }

    @Test
    fun aBareNumberWithNoPaymentPendingIsNotADate() {
        val k = kai()
        k.say("10")
        assertNull(k.conversationState.lastDate)
        assertNull(k.conversationState.pendingDay)
    }

    // ------------------------------------------------------------ TEST 7–10: business questions, never reminders

    @Test
    fun test07_whoPaysTodayIsAnswerFromTheRecords() {
        val t = kai().say("Innaikku yaar payment tharanum?")
        assertTrue(t.reply.text, t.reply.text.contains("Kumar") && t.reply.text.contains("₹3,000"))
        assertFalse(t.reply.text, t.reply.text.contains("remind", ignoreCase = true))
        assertFalse("Ravi is due on the 20th, not today", t.reply.text.contains("Ravi"))
        noWrites()
    }

    @Test
    fun test08_whomDoIPayToday() {
        val t = kai().say("innaikku yaarukku payment pannanum?")
        assertTrue(t.reply.text, t.reply.text.contains("Ramesh") && t.reply.text.contains("₹2,500"))
        assertFalse(t.reply.text, t.reply.text.contains("Kumar"))
        assertFalse(t.reply.text, t.reply.text.contains("remind", ignoreCase = true))
        noWrites()
    }

    @Test
    fun test09_whoDoICollectFrom() {
        val t = kai().say("yar kitta cash vanganu?")
        assertTrue(t.reply.text, t.reply.text.contains("Kumar") && t.reply.text.contains("Ravi"))
        assertFalse(t.reply.text, t.reply.text.contains("Ramesh"))
        noWrites()
    }

    @Test
    fun test10_whomDoIPay() {
        val t = kai().say("yarukku cash kudukanum?")
        assertTrue(t.reply.text, t.reply.text.contains("Ramesh") && t.reply.text.contains("₹2,500"))
        assertFalse(t.reply.text, t.reply.text.contains("Kumar"))
        noWrites()
    }

    @Test
    fun businessQuestionVariations() {
        for ((q, who) in listOf(
            "innaikku collection yaaru?" to "Kumar", "today collection yaaru?" to "Kumar", "today payment yaarukku?" to "Ramesh",
            "yaroda due irukku?" to "Kumar", "yaroda payment pending?" to "Kumar", "Innaiku yaar payment tharanum?" to "Kumar",
        )) {
            val t = kai().say(q)
            assertTrue("$q → ${t.reply.text}", t.reply.text.contains(who))
            assertFalse("$q → ${t.reply.text}", t.reply.text.contains("remind", ignoreCase = true) || t.reply.text.contains("clear-ah"))
        }
        noWrites()
    }

    @Test
    fun noRecordsNeverInventsAnyone() {
        val t = kai(Books(empty = true)).say("Innaikku yaar payment tharanum?")
        assertFalse(t.reply.text, Regex("""₹\s*[1-9]""").containsMatchIn(t.reply.text))
        assertFalse(t.reply.text, t.reply.text.contains("Kumar") || t.reply.text.contains("Ramesh"))
        val p = kai(Books(empty = true)).say("innaikku yaarukku payment pannanum?")
        assertFalse(p.reply.text, Regex("""₹\s*[1-9]""").containsMatchIn(p.reply.text))
        noWrites()
    }

    @Test
    fun anActionIsStillAReminder() {
        val k = kai()
        val t = k.say("innaikku Kumar payment remind pannu")
        assertTrue(t.reply.text, t.reply.text.contains("remind", ignoreCase = true))
        val call = kai().say("Kumar-ku 10 minutes kalichi call pannanum")
        assertTrue(call.reply.text, call.reply.text.endsWith("reminder set pannalama?"))
    }

    // ------------------------------------------------------------ TEST 11, 12: local discovery

    @Test
    fun test11_nearbyHardwareShopIsLocalDiscovery() {
        for (q in listOf("pakkathula hardware kadai irukka?", "pakkathula edhavadhu hardware kada irukka?")) {
            val t = kai().say(q)
            assertTrue("$q → ${t.reply.text}", t.reply.text.contains("hardware") && t.reply.text.contains("Maps"))
            // Never the ledger, never a made-up shop.
            assertFalse(t.reply.text, t.reply.text.contains("₹") || t.reply.text.contains("clear-ah"))
        }
        noWrites()
    }

    @Test
    fun test12_whereIsTheSupermarket() {
        val t = kai().say("supermarket enga irukku?")
        assertTrue(t.reply.text, t.reply.text.contains("supermarket") && !t.reply.text.contains("₹"))
        assertEquals("LOCAL_BUSINESS_DISCOVERY", KaiLocalDiscovery.request("supermarket enga irukku?")?.let { "LOCAL_BUSINESS_DISCOVERY" })
        noWrites()
    }

    @Test
    fun anyPlaceNearbyGetsTheSameAnswer() {
        // Hardware was answered and spa wasn't: a place Kai has no name for is still a place.
        for ((q, place) in listOf("Spa near by la iruka paru" to "Spa", "pakkathula salon irukka?" to "salon", "near by la gym iruka paru" to "gym",
                "ஸ்பா நியர் பை ல இருக்கா பாரு" to "spa", "பக்கத்துல ஹார்டுவேர் கடை இருக்கா" to "hardware")) {
            val t = kai().say(q)
            assertTrue("$q → ${t.reply.text}", t.reply.text.contains(place) && t.reply.text.contains("Maps") && !t.reply.text.contains("₹"))
        }
        // The shop's own people and goods are never a place to look for.
        assertNull(KaiLocalDiscovery.request("Kumar near by la irukka paaru", listOf("Kumar", "Ramesh")))
        assertNull(KaiLocalDiscovery.request("pakkathula Rice irukka", listOf("Rice")))
        // "pakkathula … irukku" says something; it doesn't look for anything.
        assertNull(KaiLocalDiscovery.request("pakkathula kalyanam irukku"))
        noWrites()
    }

    @Test
    fun notEveryWhereIsLocalDiscovery() {
        assertNull(KaiLocalDiscovery.request("Rice stock enga irukku?"))
        assertNull(KaiLocalDiscovery.request("Kumar evlo tharanum?"))
        assertNull(KaiLocalDiscovery.request("kadai saavi enga vechen?"))
        // A reminder about going there stays a reminder.
        val t = kai().say("pakkathula hardware kadai-ku poganum 5 manikku remind pannu")
        assertTrue(t.reply.text, t.reply.text.endsWith("reminder set pannalama?"))
    }

    // ------------------------------------------------------------ existing behaviour stays

    @Test
    fun casualAndCalculatorStillWork() {
        for (q in listOf("saptiya?", "saptia?", "saptya?", "un name enna?", "nee yaaru?")) {
            val t = kai().say(q)
            assertFalse("$q → ${t.reply.text}", t.reply.text.contains("clear-ah") || t.reply.text.contains("₹"))
        }
        val gst = kai().say("25000 la 18% GST evlo?").reply.text
        assertTrue(gst, gst.contains("₹4,500") && gst.contains("₹29,500"))
        noWrites()
    }
}
