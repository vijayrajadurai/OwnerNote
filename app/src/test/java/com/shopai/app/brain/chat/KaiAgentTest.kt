package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.MoneyBalanceFact
import com.shopai.app.brain.tools.MoneyKind
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductSalesFact
import com.shopai.app.brain.tools.Repeat
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.brain.tools.KaiReminderSchedule
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.ContactSource
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.brain.tools.StockFact
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** Kai's agent: understands → right tool → draft + confirm for money → verified reply. */
class KaiAgentTest {
    private val now = LocalDateTime.of(2026, 10, 3, 16, 20)

    private class FakeBooks : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Kumar", "9000000001", 8500.0, null)),
            suppliers = listOf(PartySummary("s1", "Ramesh", null, 8000.0, null)),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Records what Kai asked the tools to do. */
    private class FakeTools(val booksReady: Boolean = true) : KaiTools {
        val confirmed = mutableListOf<ActionPlan>()
        val discarded = mutableListOf<ActionPlan>()
        val scheduled = mutableListOf<KaiReminder>()
        val logged = mutableListOf<Pair<String, ActionStatus>>()
        private val parties = listOf(
            PartyMatch("c1", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("8500.00")),
            PartyMatch("s1", "Ramesh", customer = false, phone = null, balance = BigDecimal("8000.00")),
        )

        override suspend fun sales(from: LocalDate, to: LocalDate) = if (booksReady) BigDecimal("12450.00") else null
        override suspend fun moneyBalances() = if (booksReady) listOf(MoneyBalanceFact("Cash in hand", MoneyKind.CASH, BigDecimal("30600.00"))) else null
        override suspend fun stock(product: String?) = if (!booksReady) null else listOf(StockFact("Rice", BigDecimal("45.5"), "KG")).filter { product == null || it.name.equals(product, true) }
        override suspend fun lowStock() = if (booksReady) listOf(StockFact("Sugar", BigDecimal("2"), "KG", BigDecimal("10"))) else null
        override suspend fun topProducts(from: LocalDate, to: LocalDate, limit: Int) = if (booksReady) listOf(ProductSalesFact("Rice", BigDecimal("120"), BigDecimal("6000.00"))) else null
        override suspend fun parties(name: String) = if (!booksReady) null else parties.filter { it.name.contains(name, true) }

        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p-$partyName", kind, partyName, partyId, amount, mode, said = said,
                balanceBefore = BigDecimal("8000.00"), balanceAfter = BigDecimal("8000.00") - amount)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { confirmed += plan; return ActionOutcome.Done("PO-7", plan.balanceAfter) }
        override suspend fun discard(plan: ActionPlan) { discarded += plan }

        // An in-memory reminder engine with the same rules as the real one.
        override fun createReminder(reminder: KaiReminder): ReminderSaved {
            scheduled.firstOrNull { KaiReminderSchedule.sameAs(it, reminder) }?.let { return ReminderSaved(it, true, ScheduleResult.EXACT) }
            scheduled += reminder
            return ReminderSaved(reminder, false, ScheduleResult.EXACT)
        }
        override fun updateReminder(reminder: KaiReminder): ReminderSaved {
            scheduled.replaceAll { if (it.id == reminder.id) reminder else it }
            return ReminderSaved(reminder, false, ScheduleResult.EXACT)
        }
        override fun cancelReminder(id: String) = replace(id) { it.copy(status = ReminderStatus.CANCELLED) }
        override fun completeReminder(id: String) = replace(id) { if (it.recurrence.repeat == Repeat.ONCE) it.copy(status = ReminderStatus.COMPLETED) else it }
        override fun snoozeReminder(id: String, minutes: Long): KaiReminder? {
            replace(id) { it.copy(snoozedUntil = 1_000L + minutes * 60_000) }
            return scheduled.firstOrNull { it.id == id }
        }
        override fun reminders() = scheduled.filter { it.open }
        var rang: String? = null
        override fun lastRang() = scheduled.firstOrNull { it.id == rang && it.open }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = if (!booksReady) null else contactList.filter { it.name.equals(name, true) }
        var contactList = listOf(ContactMatch("c1", "Kumar", "+919000000001", ContactSource.CUSTOMER))
        private fun replace(id: String, change: (KaiReminder) -> KaiReminder): Boolean {
            val i = scheduled.indexOfFirst { it.id == id && it.open }
            if (i < 0) return false
            scheduled[i] = change(scheduled[i])
            return true
        }
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String { logged += intent to status; return reference ?: "K-1" }
    }

    private fun agent(tools: KaiTools, at: LocalDateTime = now) = KaiAgent(KaiBusinessBrain(FakeBooks(), today = { at.toLocalDate() }, random = Random(1)), FakeBooks(), tools, now = { at })

    @Test
    fun calculatorAnswersAtOnce() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        assertEquals("30", a.ask("10*3").reply.text)
        assertTrue(a.ask("25000 la 18% GST evlo?").reply.text.contains("₹4,500"))
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun paymentIsADraftUntilConfirmed() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.ask("Ramesh ku 5000 kuduthen")
        val plan = assertNotNull(turn.plan).let { turn.plan!! }
        // Ramesh is a supplier: money given to him is a payment out.
        assertEquals(PlanKind.PAYMENT_OUT, plan.kind)
        assertEquals(0, BigDecimal("5000").compareTo(plan.amount))
        assertTrue(tools.confirmed.isEmpty())
        assertTrue(turn.card!!.buttons.any { it.action is KaiAction.ConfirmPlan })
        val done = a.act(KaiAction.ConfirmPlan(plan.key), KaiLang.TANGLISH)!!
        assertEquals(1, tools.confirmed.size)
        assertTrue(done.reply.text.contains("PO-7"))
        // A second tap does nothing (the draft is gone).
        assertNull(a.act(KaiAction.ConfirmPlan(plan.key), KaiLang.TANGLISH))
        assertEquals(1, tools.confirmed.size)
    }

    @Test
    fun cancelSavesNothing() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.ask("Kumar kitta 10000 vanginen")
        assertEquals(PlanKind.PAYMENT_IN, turn.plan!!.kind)
        a.act(KaiAction.CancelPlan(turn.plan!!.key), KaiLang.TANGLISH)
        assertTrue(tools.confirmed.isEmpty())
        assertEquals(1, tools.discarded.size)
    }

    @Test
    fun unknownPersonIsAskedNotGuessed() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.ask("Muthu ku 2000 kuduthen")
        assertNull(turn.plan)
        val choose = turn.card!!.buttons.map { it.action }.filterIsInstance<KaiAction.ChoosePlan>().single()
        assertEquals(PlanKind.CREDIT_GIVEN, choose.kind)
        val drafted = a.act(choose, KaiLang.TANGLISH)!!
        assertEquals(PlanKind.CREDIT_GIVEN, drafted.plan!!.kind)
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun missingAmountIsAskedThenCompleted() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val ask = a.ask("Ramesh ku kuduthen")
        assertNull(ask.plan)
        val turn = a.ask("3000")
        assertEquals(0, BigDecimal("3000").compareTo(turn.plan!!.amount))
    }

    @Test
    fun callReminderIsCreatedAtTheExactTime() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.askConfirmed("Kumar-ku 10 minutes kalichi call panna remind pannu.")
        val r = tools.scheduled.single()
        assertEquals("Call Kumar", r.title)
        assertEquals("+919000000001", r.phone)
        val nowMillis = now.atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        assertEquals(nowMillis + 600_000, r.triggerAt)
        assertEquals("Done Owner ✅ 10 minutes kalichi Kumar-ku call panna remind pannuren.", turn.reply.text)
        // A reminder, never "call panniten".
        assertTrue(!turn.reply.text.contains("panniten", true))
        assertEquals("Owner, Kumar-ku call panna sonneenga.", r.notificationMessage)
        // Said again: not duplicated.
        assertTrue(a.askConfirmed("Kumar-ku 10 minutes kalichi call panna remind pannu.").reply.text.contains("already"))
        assertEquals(1, tools.scheduled.size)
    }

    @Test
    fun missingTimeIsAskedThenCompleted() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val ask = a.askConfirmed("Naalaikku morning supplier-ku call panna remind pannu.")
        assertTrue(ask.reply.text, ask.reply.text.contains("Morning-la exact time"))
        assertTrue(tools.scheduled.isEmpty())
        // One tap on "10:00 AM" …
        val ten = ask.card!!.buttons.map { it.action }.filterIsInstance<KaiAction.RemindAt>().first { it.at.hour == 10 }
        val done = a.actConfirmed(ten)!!
        assertEquals(LocalDateTime.of(2026, 10, 4, 10, 0), java.time.Instant.ofEpochMilli(tools.scheduled.single().triggerAt).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDateTime())
        assertTrue(done.reply.text, done.reply.text.startsWith("Done Owner ✅"))
        // … or a typed time.
        a.askConfirmed("Every Monday stock check panna reminder podu.")
        a.askConfirmed("9 am")
        val monday = tools.scheduled.last()
        assertEquals(Repeat.WEEKLY, monday.recurrence.repeat)
        assertEquals(java.time.LocalTime.of(9, 0), monday.time)
    }

    @Test
    fun recurringDailyReminder() = runBlocking {
        val tools = FakeTools()
        val turn = agent(tools).askConfirmed("Daily kaalaila 10 maniku saavi eduthuka remind pannu.")
        val r = tools.scheduled.single()
        assertEquals(Repeat.DAILY, r.recurrence.repeat)
        assertEquals(java.time.LocalTime.of(10, 0), r.time)
        assertTrue(turn.reply.text, turn.reply.text.contains("Daily") && turn.reply.text.contains("10:00 AM"))
    }

    @Test
    fun sameNamePeopleAreAskedNeverGuessed() = runBlocking {
        val tools = FakeTools().apply {
            contactList = listOf(
                ContactMatch("c1", "Kumar", "+919000000001", ContactSource.CUSTOMER),
                ContactMatch("s9", "Kumar", "+919000000009", ContactSource.SUPPLIER),
            )
        }
        val a = agent(tools)
        val ask = a.askConfirmed("Kumar-ku 10 minutes kalichi call panna remind pannu")
        assertTrue(ask.reply.text, ask.reply.text.contains("2 contacts"))
        assertTrue(tools.scheduled.isEmpty())
        val pickSupplier = ask.card!!.buttons.map { it.action }.filterIsInstance<KaiAction.PickContact>()[1]
        a.actConfirmed(pickSupplier)
        assertEquals("+919000000009", tools.scheduled.single().phone)
    }

    @Test
    fun unknownPersonStillGetsTheReminderAndANumberCanBeAdded() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.askConfirmed("Muthu-ku 30 minutes kalichu call panna remind pannu")
        assertTrue(turn.card!!.warning!!.contains("Muthu contact OwnerNote-la illa"))
        a.ask("98765 43210")
        assertEquals("+919876543210", tools.scheduled.single().phone)
    }

    @Test
    fun cancelCompleteSnoozeAndUpdate() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        a.askConfirmed("Kumar-ku 10 minutes kalichi call panna remind pannu")
        a.askConfirmed("Daily morning 10 manikku stock check panna remind pannu")
        // "Kumar call reminder-a 30 minutes-ku change pannu": the same reminder moves, no duplicate.
        val kumar = tools.scheduled.first()
        val updated = a.ask("Kumar call reminder-a 30 minutes-ku change pannu")
        assertTrue(updated.reply.text, updated.reply.text.startsWith("Seri Owner,"))
        assertEquals(2, tools.scheduled.size)
        val nowMillis = now.atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        assertEquals(nowMillis + 30 * 60_000, tools.scheduled.first { it.id == kumar.id }.triggerAt)
        // It rang: "Innum 10 minutes" snoozes it, "Done" completes it.
        tools.rang = kumar.id
        a.ask("Innum 10 minutes later remind pannu")
        assertTrue(tools.scheduled.first { it.id == kumar.id }.snoozedUntil != null)
        a.ask("Done.")
        assertEquals(ReminderStatus.COMPLETED, tools.scheduled.first { it.id == kumar.id }.status)
        // Cancel by words.
        a.ask("Stock check reminder cancel pannu")
        assertTrue(tools.reminders().isEmpty())
    }

    @Test
    fun ambiguousCancelAsksWhich() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        a.askConfirmed("Kumar-ku 10 minutes kalichi call panna remind pannu")
        a.askConfirmed("Tomorrow 5 PM Kumar-ku call reminder")
        val ask = a.ask("Kumar call reminder cancel pannu")
        assertTrue(ask.reply.text, ask.reply.text.contains("Kumar-ku 2 reminders irukku"))
        assertEquals(2, tools.reminders().size)
        a.act(ask.card!!.buttons.first().action, KaiLang.TANGLISH)
        assertEquals(1, tools.reminders().size)
    }

    @Test
    fun pastTimeIsAskedAndListShowsToday() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val past = a.askConfirmed("Inniku 9 maniku shop open panna remind pannu")
        assertTrue(past.reply.text, past.reply.text.contains("already pochu"))
        assertTrue(tools.scheduled.isEmpty())
        a.askConfirmed("Kumar-ku 10 minutes kalichi call panna remind pannu")
        val list = a.ask("Today enna reminders iruku?")
        assertTrue(list.card!!.lines.joinToString("\n"), list.card!!.lines.any { it.contains("Call Kumar") })
    }
    @Test
    fun businessAnswersComeFromTheBooksOrSayUnverified() = runBlocking {
        val a = agent(FakeTools())
        a.ask("Enakku evlo cash iruku?").reply.text.let { assertTrue(it, it.contains("₹30,600")) }
        a.ask("Inniku evlo sales?").reply.text.let { assertTrue(it, it.contains("₹12,450")) }
        a.ask("Rice stock evlo?").reply.text.let { assertTrue(it, it.contains("45.5 KG")) }
        a.ask("Which stock is low?").card!!.lines.single().let { assertTrue(it, it.startsWith("Sugar")) }
        a.ask("Indha month highest selling product enna?").reply.text.let { assertTrue(it, it.contains("Rice")) }

        val offline = agent(FakeTools(booksReady = false))
        val r = offline.ask("Enakku evlo cash iruku?").reply.text
        assertTrue(r, r.contains("verify"))
        assertTrue(offline.ask("Ramesh ku 5000 kuduthen").plan == null)
    }

    @Test
    fun callOpensTheDialerNeverClaimsACall() = runBlocking {
        val turn = agent(FakeTools()).ask("Kumar-ku call pannu")
        val dial = turn.card!!.buttons.single().action as KaiAction.Dial
        assertEquals("+919000000001", dial.phone)
        assertTrue(!turn.reply.text.contains("called", ignoreCase = true))
    }

    @Test
    fun statedReceivableCarriesAmountAndPersonIntoWhenFollowUp() = runBlocking {
        val a = agent(FakeTools())
        val first = a.ask("Kumar enakku 20000 tharanum")
        assertTrue(first.reply.text, first.reply.text.contains("Kumar") && first.reply.text.contains("20,000"))
        assertNull(first.plan)
        assertEquals(BigDecimal("20000.00"), a.conversationState.lastAmount)
        val followUp = a.ask("Eppa?")
        assertTrue(followUp.reply.text, followUp.reply.text.contains("Kumar") && followUp.reply.text.contains("20,000"))
        assertTrue(followUp.reply.text, !followUp.reply.text.contains("Yaar pathi"))
    }

    @Test
    fun pendingDueDateAcceptsNaturalCalendarAnswersUsingKaiTime() = runBlocking {
        val cases = listOf(
            "next month 10" to LocalDate.of(2026, 11, 10),
            "next month 15" to LocalDate.of(2026, 11, 15),
            "October 10" to LocalDate.of(2026, 10, 10),
            "10 October" to LocalDate.of(2026, 10, 10),
            "October 10th" to LocalDate.of(2026, 10, 10),
            "naalaikku" to LocalDate.of(2026, 10, 4),
            "tomorrow" to LocalDate.of(2026, 10, 4),
            "next week" to LocalDate.of(2026, 10, 10),
            "Friday" to LocalDate.of(2026, 10, 9),
            "அடுத்த மாதம் 10" to LocalDate.of(2026, 11, 10),
            "அக்டோபர் 10" to LocalDate.of(2026, 10, 10),
        )

        cases.forEach { (answer, expectedDate) ->
            val a = agent(FakeTools())
            val prompt = a.ask("Praba ku 5000 tharanum")
            assertTrue("Expected a due-date prompt for $answer: ${prompt.reply.text}", prompt.reply.text.contains("Due date", ignoreCase = true))
            assertEquals(KaiPendingQuestion.DUE_DATE, a.conversationState.pendingQuestion)
            assertEquals("Praba", a.conversationState.pendingEntity)
            assertEquals(BigDecimal("5000.00"), a.conversationState.pendingAmount)
            assertEquals(KaiConversationPaymentDirection.PAYMENT_OUT, a.conversationState.pendingPaymentDirection)
            assertEquals("OUT", a.conversationState.lastPaymentDirection)

            val resolved = a.ask(answer)
            assertEquals("Wrong date for answer '$answer': ${resolved.reply.text}", expectedDate, a.conversationState.lastDate)
            assertEquals("Praba", a.conversationState.lastPerson)
            assertEquals(BigDecimal("5000.00"), a.conversationState.lastAmount)
            assertEquals("OUT", a.conversationState.lastPaymentDirection)
            assertNull(a.conversationState.pendingQuestion)
            assertNull(a.conversationState.pendingEntity)
            assertNull(a.conversationState.pendingAmount)
            assertNull(a.conversationState.pendingPaymentDirection)
            assertNull(resolved.plan)
            assertTrue(resolved.reply.text, resolved.reply.text.contains("Praba"))
            assertTrue(resolved.reply.text, resolved.reply.text.contains("5,000"))
        }
    }

    // A day with no month ("10th", "10th date", "10 தேதி") is never guessed: Kai asks this month or next, then resolves.
    @Test
    fun dayWithoutMonthAsksWhichMonthThenResolves() = runBlocking {
        for ((answer, month) in listOf("10th" to "next month", "10th date" to "indha month", "10 தேதி" to "adutha maasam")) {
            val a = agent(FakeTools())
            a.ask("Praba ku 5000 tharanum")
            val ask = a.ask(answer)
            assertTrue("$answer → ${ask.reply.text}", ask.reply.text.contains("10") && (ask.reply.text.contains("maasam") || ask.reply.text.contains("மாதம்")))
            assertNull("no date guessed for '$answer'", a.conversationState.lastDate)
            assertEquals(KaiPendingQuestion.DUE_DATE, a.conversationState.pendingQuestion)
            val resolved = a.ask(month)
            val expected = if (month == "indha month") LocalDate.of(2026, 10, 10) else LocalDate.of(2026, 11, 10)
            assertEquals("$answer + $month", expected, a.conversationState.lastDate)
            assertTrue(resolved.reply.text, resolved.reply.text.contains("Praba") && resolved.reply.text.contains("5,000"))
            assertEquals("OUT", a.conversationState.lastPaymentDirection)
        }
    }

    @Test
    fun pendingDateUsesCalendarRolloverAndDoesNotInventFromTimeOrUnknownText() = runBlocking {
        val monthEnd = agent(FakeTools(), LocalDateTime.of(2026, 10, 31, 16, 20))
        monthEnd.ask("Praba ku 5000 tharanum")
        monthEnd.ask("next month 10")
        assertEquals(LocalDate.of(2026, 11, 10), monthEnd.conversationState.lastDate)

        val passedMonth = agent(FakeTools(), LocalDateTime.of(2026, 11, 25, 16, 20))
        passedMonth.ask("Praba ku 5000 tharanum")
        passedMonth.ask("October 10")
        assertEquals(LocalDate.of(2027, 10, 10), passedMonth.conversationState.lastDate)

        listOf("at 10", "maybe sometime").forEach { answer ->
            val a = agent(FakeTools())
            a.ask("Praba ku 5000 tharanum")
            a.ask(answer)
            assertNull("Must not infer a calendar date from '$answer'", a.conversationState.lastDate)
            assertEquals(KaiPendingQuestion.DUE_DATE, a.conversationState.pendingQuestion)
        }
    }

    @Test
    fun pendingDueDateIsSessionLocalAndResetClearsIt() = runBlocking {
        val first = agent(FakeTools())
        first.ask("Praba ku 5000 tharanum")
        val separateSession = agent(FakeTools())
        separateSession.ask("October 10")
        assertNull(separateSession.conversationState.lastDate)
        assertNull(separateSession.conversationState.pendingQuestion)

        first.reset()
        assertNull(first.conversationState.pendingQuestion)
        assertNull(first.conversationState.pendingEntity)
        assertNull(first.conversationState.pendingAmount)
        assertNull(first.conversationState.pendingPaymentDirection)
        first.ask("October 10")
        assertNull(first.conversationState.lastDate)
    }

    @Test
    fun pendingAmountAndCollectionFollowUpStayTogether() = runBlocking {
        val a = agent(FakeTools())
        a.ask("Kumar-ku 12000 pending irukku")
        val followUp = a.ask("Adha eppo collect pannalaam?")
        assertTrue(followUp.reply.text, followUp.reply.text.contains("Kumar") && followUp.reply.text.contains("12,000"))
        assertEquals("Kumar", a.conversationState.lastPerson)
        assertEquals(BigDecimal("12000.00"), a.conversationState.lastAmount)
    }

    @Test
    fun calculatorTemporarilySwitchesTopicAndRestoresReceivableContext() = runBlocking {
        val a = agent(FakeTools())
        a.ask("Kumar enakku 20000 tharanum")
        assertEquals("1,625", a.ask("1250 plus 375 evlo?").reply.text)
        val restored = a.ask("Kumar adha eppa tharuvaan?")
        assertTrue(restored.reply.text, restored.reply.text.contains("Kumar") && restored.reply.text.contains("20,000"))
        assertEquals("RECEIVABLE_CONTEXT", a.conversationState.previousTopicBeforeCalculator)
    }

    @Test
    fun gaveMeCreatesIncomingPaymentDraftWithoutPosting() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.ask("Kumar gave me 5000 cash today")
        assertEquals(PlanKind.PAYMENT_IN, turn.plan!!.kind)
        assertEquals(BigDecimal("5000.00"), turn.plan!!.amount)
        assertTrue(turn.card!!.buttons.any { it.action is KaiAction.ConfirmPlan })
        assertTrue(tools.confirmed.isEmpty())
        assertEquals("IN", a.conversationState.lastPaymentDirection)
    }

    @Test
    fun messyAccountQuestionCreatesCashDraftForTheActualReceipt() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val turn = a.ask("Kumar inniku 5k cash kuduthan, idha account la podalama")
        assertEquals(PlanKind.PAYMENT_IN, turn.plan!!.kind)
        assertEquals(BigDecimal("5000.00"), turn.plan!!.amount)
        assertEquals(com.shopai.app.books.model.PaymentMode.CASH, turn.plan!!.mode)
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun numericCorrectionReplacesDraftAmountAndStillRequiresConfirmation() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val first = a.ask("Kumar 5000 cash kuduthaan")
        assertEquals(BigDecimal("5000.00"), first.plan!!.amount)
        val revised = a.ask("Illai Kai, 500 dhaan")
        assertEquals(BigDecimal("500.00"), revised.plan!!.amount)
        assertTrue(revised.card!!.buttons.any { it.action is KaiAction.ConfirmPlan })
        assertEquals(1, tools.discarded.size)
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun naturalVendaCancelsOpenDraftAndSavesNothing() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val draft = a.ask("Kumar gave me 1000 cash today")
        assertNotNull(draft.plan)
        val cancelled = a.ask("Venda")
        assertTrue(cancelled.reply.text, cancelled.reply.text.contains("cancel pannitten", ignoreCase = true))
        assertTrue(cancelled.reply.text, cancelled.reply.text.contains("save aagala", ignoreCase = true))
        assertTrue(tools.confirmed.isEmpty())
        assertEquals(1, tools.discarded.size)
        assertNull(a.conversationState.pendingDraft)
    }

    @Test
    fun missingColgateIsStillRememberedForItsPronounFollowUp() = runBlocking {
        val a = agent(FakeTools())
        val first = a.ask("Colgate stock evlo irukku?")
        assertTrue(first.reply.text, first.reply.text.contains("Colgate"))
        val next = a.ask("Adhu low-aa irukka?")
        assertTrue(next.reply.text, next.reply.text.contains("Colgate") && next.reply.text.contains("record"))
        assertEquals("Colgate", a.conversationState.lastProduct)
    }

    @Test
    fun missingAmountAsksForAmountThenDirectionWithoutDrafting() = runBlocking {
        val tools = FakeTools()
        val a = agent(tools)
        val missing = a.ask("Kumar-ku amount add pannu")
        assertTrue(missing.reply.text, missing.reply.text.contains("amount", ignoreCase = true) || missing.reply.text.contains("Evlo"))
        assertNull(missing.plan)
        assertTrue(tools.confirmed.isEmpty())
        val amountOnly = a.ask("5000")
        assertNull(amountOnly.plan)
        assertTrue(amountOnly.reply.text, amountOnly.reply.text.contains("received", ignoreCase = true) || amountOnly.reply.text.contains("vandhucha"))
        assertTrue(tools.confirmed.isEmpty())
        assertTrue(tools.discarded.isEmpty())
    }
}
