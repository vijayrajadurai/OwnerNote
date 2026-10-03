package com.shopai.app.brain.morning

import com.shopai.app.brain.KaiLang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Kai — Do My Morning Work: one engine for voice and text, over real (fake-here) records. */
class MorningWorkEngineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = ZonedDateTime.of(2026, 10, 3, 8, 30, 0, 0, zone)
    private val today: LocalDate get() = now.toLocalDate()
    private val day: Long get() = today.toEpochDay()

    private fun engine(store: MorningTaskStore = InMemoryMorningTaskStore(), max: Int = MorningWorkEngine.MAX_TASKS) =
        MorningWorkEngine(store, clock = { now }, maxTasks = max)

    private fun customer(id: String, name: String, pending: Double, due: LocalDate?, phone: String? = "9876543210", docs: List<MorningDoc> = emptyList()) =
        MorningParty(id, name, MorningPartyKind.CUSTOMER, phone, pending, due?.toEpochDay(), docs)

    private fun supplier(id: String, name: String, pending: Double, due: LocalDate?, phone: String? = "9123456780") =
        MorningParty(id, name, MorningPartyKind.SUPPLIER, phone, pending, due?.toEpochDay())

    private val kumar = customer("c-kumar", "Kumar", 12_000.0, LocalDate.of(2026, 10, 3))
    private val abc = supplier("s-abc", "ABC Traders", 20_000.0, LocalDate.of(2026, 10, 3))
    private val rice = MorningProduct("p-rice", "Rice", "BAG", stock = 8.0, minimum = null, reorderLevel = 20.0)
    private val ravi = customer(
        "c-ravi", "Ravi Stores", 5_000.0, null,
        docs = listOf(MorningDoc("inv-1", "INV-7", total = 8_500.0, paid = 3_500.0, dueDay = null, dateDay = LocalDate.of(2026, 9, 25).toEpochDay())),
    )
    private val tenAm = ZonedDateTime.of(2026, 10, 3, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val reminder = MorningReminder("r-1", "Bank-ku poganum", tenAm, done = false, custom = true)

    private fun snapshot(biz: String = "biz-1", offline: Boolean = false, vararg parties: MorningParty) = MorningSnapshot(
        businessId = biz,
        parties = parties.toList(),
        products = listOf(rice),
        reminders = listOf(reminder),
        offline = offline,
    )

    private fun fullSnapshot(biz: String = "biz-1", offline: Boolean = false) = snapshot(biz, offline, kumar, abc, ravi)

    private fun MorningPlan.task(type: MorningTaskType, source: String) = tasks.single { it.taskType == type && it.sourceId == source }

    // 1
    @Test
    fun customerOverduePaymentCreatesCriticalCollectTask() = runBlocking {
        val e = engine()
        e.prepare(snapshot(parties = arrayOf(customer("c-1", "Selvam", 3_000.0, today.minusDays(4)))))
        val t = e.state.plan!!.task(MorningTaskType.COLLECT_PAYMENT, "c-1")
        assertEquals(MorningPriority.CRITICAL, t.priority)
        assertEquals(PriorityReason.OVERDUE, t.reason)
        assertEquals(3_000.0, t.facts.amount!!, 0.001)
    }

    // 2
    @Test
    fun paymentDueTodayCreatesHighCollectTask() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val t = e.state.plan!!.task(MorningTaskType.COLLECT_PAYMENT, "c-kumar")
        assertEquals(MorningPriority.HIGH, t.priority)
        assertEquals(PriorityReason.DUE_TODAY, t.reason)
    }

    // 3
    @Test
    fun supplierPaymentDueTodayCreatesTask() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val t = e.state.plan!!.task(MorningTaskType.SUPPLIER_PAYMENT, "s-abc")
        assertEquals(MorningPriority.HIGH, t.priority)
        assertEquals(20_000.0, t.facts.amount!!, 0.001)
    }

    // 4
    @Test
    fun lowStockCreatesTaskWithStockAndReorderLevel() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val t = e.state.plan!!.task(MorningTaskType.LOW_STOCK, "p-rice")
        assertEquals(MorningPriority.HIGH, t.priority)
        assertEquals(8.0, t.facts.stock!!, 0.0)
        assertEquals(20.0, t.facts.reorderLevel!!, 0.0)
        // Out of stock is critical; a product with no level set is not judged.
        e.prepare(MorningSnapshot("biz-1", products = listOf(rice.copy(id = "p-oil", name = "Oil", stock = 0.0), rice.copy(id = "p-salt", name = "Salt", reorderLevel = null))))
        assertEquals(MorningPriority.CRITICAL, e.state.plan!!.task(MorningTaskType.LOW_STOCK, "p-oil").priority)
        assertTrue(e.state.plan!!.tasks.none { it.sourceId == "p-salt" })
    }

    // 5
    @Test
    fun todaysReminderCreatesTask() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val t = e.state.plan!!.task(MorningTaskType.REMINDER, "r-1")
        assertEquals(PriorityReason.REMINDER_LATER_TODAY, t.reason)
        // Payment-due reminders come from the parties themselves, not as extra tasks.
        e.prepare(MorningSnapshot("biz-1", reminders = listOf(reminder.copy(id = "r-2", custom = false))))
        assertTrue(e.state.plan!!.tasks.none { it.sourceId == "r-2" })
    }

    // 6 + 18 (dedup key)
    @Test
    fun duplicateSourceDoesNotCreateDuplicateTasks() = runBlocking {
        val e = engine()
        // Same customer twice, with a partial bill and due today: ONE collection task with the context inside.
        val kumarWithBill = kumar.copy(docs = listOf(MorningDoc("inv-9", "INV-9", 15_000.0, 3_000.0, day, day - 3)))
        e.prepare(snapshot(parties = arrayOf(kumarWithBill, kumarWithBill)))
        val kumarTasks = e.state.plan!!.tasks.filter { it.sourceId == "c-kumar" }
        assertEquals(1, kumarTasks.size)
        assertEquals(MorningTaskType.COLLECT_PAYMENT, kumarTasks.single().taskType)
        assertEquals(1, kumarTasks.single().facts.partialBills)
        // Preparing again the same day keeps one task per source.
        e.prepare(snapshot(parties = arrayOf(kumarWithBill)))
        assertEquals(1, e.state.plan!!.tasks.count { it.sourceId == "c-kumar" })
        assertEquals(MorningTask.idFor("biz-1", MorningTaskType.COLLECT_PAYMENT, "c-kumar", day), kumarTasks.single().taskId)
    }

    // 7
    @Test
    fun confirmedPaymentResolvesCollectionTask() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val id = e.state.plan!!.task(MorningTaskType.COLLECT_PAYMENT, "c-kumar").taskId
        // Money received elsewhere (the books now say 0 pending) → the task resolves, nothing kept separately.
        e.prepare(snapshot(parties = arrayOf(kumar.copy(pending = 0.0), abc, ravi)), greet = false)
        val t = e.state.plan!!.tasks.single { it.taskId == id }
        assertEquals(MorningTaskStatus.COMPLETED, t.status)
        assertEquals(MorningWorkEngine.ACTION_RESOLVED, t.actionTaken)

        // Through Kai: draft → explicit confirm → posted → completed.
        val e2 = engine()
        e2.prepare(fullSnapshot())
        e2.act(MorningCommand.Start)
        val draftReply = e2.handle("Kumar kitta 12000 vaangiten", ResponseMode.TEXT)
        assertTrue(draftReply.effects.single() is MorningEffect.ReviewPayment)
        val draft = (draftReply.effects.single() as MorningEffect.ReviewPayment).draft
        assertFalse(draft.outgoing)
        val confirm = e2.handle("yes", ResponseMode.TEXT)
        val post = confirm.effects.single() as MorningEffect.PostPayment
        e2.onResult(MorningResult.PaymentPosted(post.draft.id))
        assertEquals(MorningTaskStatus.COMPLETED, e2.state.plan!!.task(MorningTaskType.COLLECT_PAYMENT, "c-kumar").status)
    }

    // 8
    @Test
    fun postponedTaskGetsNewReminderFromRealCurrentTime() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        e.act(MorningCommand.Start)
        val current = e.state.current!!
        val r = e.handle("remind me after 30 minutes", ResponseMode.TEXT)
        val create = r.effects.single() as MorningEffect.CreateReminder
        assertEquals(current.taskId, create.taskId)
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(), create.atMillis)
        assertTrue(create.title.isNotBlank())
        e.onResult(MorningResult.ReminderCreated(create.taskId, "rem-77"))
        val t = e.state.plan!!.tasks.single { it.taskId == current.taskId }
        assertEquals(MorningTaskStatus.POSTPONED, t.status)
        assertEquals("rem-77", t.reminderId)
        // "Later" with no time: Kai asks instead of guessing.
        val ask = e.handle("later remind pannu", ResponseMode.TEXT)
        assertTrue(ask.effects.single() is MorningEffect.ShowRemindOptions)
        val tomorrow = e.handle("tomorrow morning", ResponseMode.TEXT).effects.single() as MorningEffect.CreateReminder
        assertEquals(today.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), tomorrow.atMillis)
    }

    // 9
    @Test
    fun skippedTaskStaysSkippedAndRecordIsUntouched() = runBlocking {
        val e = engine()
        val snap = fullSnapshot()
        e.prepare(snap)
        e.act(MorningCommand.Start)
        val first = e.state.current!!
        e.act(MorningCommand.Skip(SkipReason.ALREADY_HANDLED))
        assertEquals(MorningTaskStatus.SKIPPED, e.state.plan!!.tasks.single { it.taskId == first.taskId }.status)
        assertEquals(SkipReason.ALREADY_HANDLED, e.state.plan!!.tasks.single { it.taskId == first.taskId }.skipReason)
        // The data still has the condition; a refresh keeps the task SKIPPED (not re-opened, not resolved).
        e.prepare(snap, greet = false)
        assertEquals(MorningTaskStatus.SKIPPED, e.state.plan!!.tasks.single { it.taskId == first.taskId }.status)
        assertNotNull(e.state.current)
        assertTrue(e.state.current!!.taskId != first.taskId)
    }

    // 10
    @Test
    fun generatingTasksNeverChangesBusinessData() = runBlocking {
        val snap = fullSnapshot()
        val copy = snap.copy(parties = snap.parties.map { it.copy() }, products = snap.products.map { it.copy() })
        val e = engine()
        val reply = e.prepare(snap)
        assertEquals(copy, snap)
        // Generating tasks asks the app to do nothing (no payment, stock change or message).
        assertTrue(reply.effects.isEmpty())
        // Even a payment command only drafts; posting needs an explicit "yes".
        e.act(MorningCommand.Start)
        val r = e.handle("ABC-ku 20000 payment pannu", ResponseMode.TEXT)
        assertTrue(r.effects.none { it is MorningEffect.PostPayment })
        val maybe = e.handle("hmm", ResponseMode.TEXT)
        assertTrue(maybe.effects.none { it is MorningEffect.PostPayment })
    }

    // 11
    @Test
    fun worksWithZeroTasks() = runBlocking {
        val e = engine()
        val r = e.prepare(MorningSnapshot("biz-1"), lang = KaiLang.TANGLISH)
        assertTrue(e.state.plan!!.tasks.isEmpty())
        assertTrue(r.line.display.contains("edhuvum illa"))
        val start = e.act(MorningCommand.Start)
        assertTrue(start.effects.none { it is MorningEffect.PostPayment })
    }

    // 12
    @Test
    fun hundredPlusCandidatesGiveTheRightPrioritisedSubset() = runBlocking {
        val parties = (1..120).map { i ->
            when {
                i <= 5 -> customer("c-$i", "Overdue $i", 1_000.0 * i, today.minusDays(i.toLong()))
                i <= 40 -> customer("c-$i", "Today $i", 500.0 + i, today)
                else -> customer("c-$i", "Later $i", 100.0 + i, null)
            }
        }
        val e = engine(max = 15)
        e.prepare(MorningSnapshot("biz-1", parties = parties))
        val plan = e.state.plan!!
        assertEquals(120, plan.candidateCount)
        assertEquals(15, plan.tasks.size)
        // All 5 overdue (critical) first, then due-today by amount; nothing LOW makes the cut.
        assertTrue(plan.tasks.take(5).all { it.priority == MorningPriority.CRITICAL })
        assertTrue(plan.tasks.drop(5).all { it.priority == MorningPriority.HIGH })
        assertEquals("c-5", plan.tasks.first().sourceId) // oldest overdue first
        val high = plan.tasks.drop(5).map { it.facts.amount!! }
        assertEquals(high.sortedDescending(), high)
    }

    // 13
    @Test
    fun tamilInputOpensAndAnswersInTamil() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        assertEquals(MorningCommand.Open, MorningCommands.parse("கை, இன்னைக்கு என்ன வேலை இருக்கு?", now.toLocalDateTime()))
        val r = e.handle("இன்னைக்கு என்ன வேலை இருக்கு?", ResponseMode.TEXT)
        assertTrue(r.line.display.contains("இன்னைக்கு"))
        assertEquals("ta-IN", r.line.speechLanguage)
        assertEquals(MorningCommand.Next, MorningCommands.parse("அடுத்து", now.toLocalDateTime()))
    }

    // 14
    @Test
    fun tanglishInput() = runBlocking {
        val t = now.toLocalDateTime()
        for (s in listOf("Kai morning work ready pannu", "Kai innaiku enna important?", "Innaiku enna pending?", "Today enna panna vendiyathu?", "Morning tasks kaatu", "Kai, innaiku enna work irukku?")) {
            assertEquals(s, MorningCommand.Open, MorningCommands.parse(s, t))
        }
        assertEquals(MorningCommand.Start, MorningCommands.parse("Kai morning work start pannu", t))
        assertTrue(MorningCommands.parse("later remind pannu", t) is MorningCommand.RemindLater)
        assertTrue(MorningCommands.parse("WhatsApp anuppu", t) is MorningCommand.WhatsApp)
        val e = engine()
        e.prepare(fullSnapshot())
        val r = e.handle("Kai morning work ready pannu", ResponseMode.TEXT)
        assertTrue(r.line.display, r.line.display.contains("Innaiku 5 important tasks irukku"))
    }

    @Test
    fun kaiChatAndPesungaHandOverOnlyClearMorningRequests() {
        assertEquals(MorningCommand.Open, MorningCommands.morningRequest("Kai, morning work ready pannu"))
        assertEquals(MorningCommand.Open, MorningCommands.morningRequest("Kai, innaiku enna work irukku?"))
        assertEquals(MorningCommand.Open, MorningCommands.morningRequest("Do my morning work"))
        assertEquals(MorningCommand.Open, MorningCommands.morningRequest("இன்னைக்கு என்ன வேலை இருக்கு?"))
        assertEquals(MorningCommand.Start, MorningCommands.morningRequest("Kai morning work start pannu"))
        // Existing Kai questions stay with Kai Chat / Pesunga.
        assertNull(MorningCommands.morningRequest("Innaiku enna pending?"))
        assertNull(MorningCommands.morningRequest("Kumar evlo tharanum?"))
        assertNull(MorningCommands.morningRequest("Kumar kitta 3000 vanganum"))
    }

    // 15
    @Test
    fun englishInput() = runBlocking {
        val t = now.toLocalDateTime()
        assertEquals(MorningCommand.Open, MorningCommands.parse("Do my morning work", t))
        for (s in listOf("Start my morning", "Let's start", "First task")) assertEquals(s, MorningCommand.Start, MorningCommands.parse(s, t))
        for (s in listOf("Next", "Next one")) assertEquals(MorningCommand.Next, MorningCommands.parse(s, t))
        assertTrue(MorningCommands.parse("Skip this", t) is MorningCommand.Skip)
        assertEquals(MorningCommand.Done, MorningCommands.parse("Done", t))
        assertEquals(MorningCommand.RemindLater(t.plusMinutes(30)), MorningCommands.parse("Remind me after 30 minutes", t))
        val friday = MorningCommands.parse("Friday", t) as MorningCommand.RemindLater
        assertEquals(LocalDateTime.of(2026, 10, 9, 10, 0), friday.at) // 3 Oct 2026 is a Saturday
        val lunch = MorningCommands.parse("After lunch", t) as MorningCommand.RemindLater
        assertEquals(LocalDateTime.of(2026, 10, 3, 14, 0), lunch.at)
        val e = engine()
        e.prepare(fullSnapshot())
        val r = e.handle("Do my morning work", ResponseMode.TEXT)
        assertTrue(r.line.display, r.line.display.contains("You have 5 important tasks today"))
        assertEquals("en-IN", r.line.speechLanguage)
    }

    // 16
    @Test
    fun offlineShowsLastSyncedNoticeAndQueuesReminder() = runBlocking {
        val store = InMemoryMorningTaskStore()
        val e = engine(store)
        val r = e.prepare(fullSnapshot(offline = true), lang = KaiLang.ENGLISH)
        assertTrue(e.state.plan!!.offline)
        assertTrue(r.line.display.contains("Showing last synced business information."))
        // Offline data never marks tasks as resolved.
        e.prepare(snapshot(offline = true, parties = arrayOf(abc)), greet = false)
        assertTrue(e.state.plan!!.task(MorningTaskType.COLLECT_PAYMENT, "c-kumar").open)
        // A reminder asked for offline waits, then is created once online (no duplicate).
        e.act(MorningCommand.Start)
        val id = e.state.current!!.taskId
        val at = now.plusHours(1).toInstant().toEpochMilli()
        e.onResult(MorningResult.ReminderQueued(id, at))
        assertEquals(MorningTaskStatus.POSTPONED, store.load("biz-1", day).single { it.taskId == id }.status)
        val online = e.prepare(fullSnapshot(), greet = false)
        val retry = online.effects.filterIsInstance<MorningEffect.CreateReminder>()
        assertEquals(listOf(id), retry.map { it.taskId })
        e.onResult(MorningResult.ReminderCreated(id, "rem-1"))
        assertTrue(store.queuedReminders("biz-1").isEmpty())
    }

    // 17
    @Test
    fun appRestartKeepsTaskStatesAndHistory() = runBlocking {
        val store = InMemoryMorningTaskStore()
        val first = engine(store)
        first.prepare(fullSnapshot())
        first.act(MorningCommand.Start)
        val done = first.state.current!!.taskId
        first.act(MorningCommand.Done)
        // A new engine (app restarted) reads the same store.
        val second = engine(store)
        second.prepare(fullSnapshot())
        assertEquals(MorningTaskStatus.COMPLETED, second.state.plan!!.tasks.single { it.taskId == done }.status)
        val start = second.act(MorningCommand.Start)
        assertTrue(second.state.current!!.taskId != done)
        assertTrue(start.line.display.isNotBlank())
        // Next day: yesterday's history is kept.
        now = now.plusDays(1)
        second.prepare(fullSnapshot())
        assertTrue(store.all("biz-1").any { it.taskId == done && it.status == MorningTaskStatus.COMPLETED })
    }

    // 18
    @Test
    fun businessesCannotSeeEachOthersTasks() = runBlocking {
        val store = InMemoryMorningTaskStore()
        val a = engine(store)
        a.prepare(fullSnapshot("biz-A"))
        val b = engine(store)
        b.prepare(MorningSnapshot("biz-B", parties = listOf(customer("c-x", "Mani", 700.0, today))))
        assertTrue(store.load("biz-B", day).all { it.businessId == "biz-B" })
        assertEquals(listOf("c-x"), store.load("biz-B", day).map { it.sourceId })
        assertTrue(store.load("biz-A", day).none { it.sourceId == "c-x" })
        // Acting on another business's task id does nothing.
        val foreign = store.load("biz-A", day).first().taskId
        b.act(MorningCommand.Done, taskId = foreign)
        assertTrue(store.load("biz-A", day).single { it.taskId == foreign }.open)
        // The same engine switched to another business starts clean.
        a.prepare(MorningSnapshot("biz-B"))
        assertTrue(a.state.plan!!.tasks.all { it.businessId == "biz-B" })
    }

    // 19
    @Test
    fun ownerCanConfirmPayment() = runBlocking {
        val e = engine()
        e.role = MorningRole.OWNER
        e.prepare(fullSnapshot())
        val draft = e.handle("ABC-ku 20000 payment pannu", ResponseMode.TEXT)
        val review = draft.effects.single() as MorningEffect.ReviewPayment
        assertTrue(review.draft.outgoing)
        assertEquals(20_000.0, review.draft.amount, 0.0)
        assertEquals("ABC Traders", review.draft.partyName)
        assertTrue(draft.line.display.contains("Confirm pannalama"))
        val post = e.handle("yes", ResponseMode.TEXT).effects.single() as MorningEffect.PostPayment
        // A double "yes" never posts twice.
        assertTrue(e.handle("yes", ResponseMode.TEXT).effects.isEmpty())
        val done = e.onResult(MorningResult.PaymentPosted(post.draft.id))
        assertTrue(done.line.display.contains("Payment ₹20,000 confirmed Owner"))
    }

    // 20
    @Test
    fun staffFollowsRoleRules() = runBlocking {
        val e = engine()
        e.role = MorningRole("STAFF")
        e.prepare(fullSnapshot())
        val r = e.handle("ABC-ku 20000 payment pannu", ResponseMode.TEXT)
        assertTrue(r.effects.isEmpty())
        assertTrue(r.line.display.contains("permission"))
        // Non-financial actions stay available.
        e.act(MorningCommand.Start)
        assertTrue(e.act(MorningCommand.Call(null)).effects.single() is MorningEffect.OpenDialer)
        // Staff given the PAYMENTS permission may record payments.
        e.role = MorningRole("STAFF", setOf("PAYMENTS"))
        assertTrue(e.handle("ABC-ku 20000 payment pannu", ResponseMode.TEXT).effects.single() is MorningEffect.ReviewPayment)
    }

    // ------------------------------------------------ voice + text, one brain

    @Test
    fun voiceSessionAnswersInVoiceAndKeepsMode() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val open = e.handle("Kai morning work start pannu", ResponseMode.VOICE)
        assertEquals(ResponseMode.VOICE, open.mode)
        assertTrue(open.speak)
        assertTrue(open.line.display.startsWith("1 of 5"))
        // Next: spoken.
        val next = e.handle("next", ResponseMode.VOICE)
        assertTrue(next.speak && next.line.display.contains("2 of 5"))
        // A button press (text input) during a voice session does not switch the mode.
        assertEquals(ResponseMode.VOICE, e.act(MorningCommand.Next).mode)
        // Only an explicit request switches it.
        val typed = e.handle("Type-la sollu", ResponseMode.VOICE)
        assertEquals(ResponseMode.TEXT, typed.mode)
        assertFalse(typed.speak)
        assertEquals(ResponseMode.VOICE, e.handle("Voice reply", ResponseMode.TEXT).mode)
    }

    @Test
    fun textSessionAnswersInTextAndSkipMovesOn() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val open = e.handle("Kai morning work ready pannu", ResponseMode.TEXT)
        assertEquals(ResponseMode.TEXT, open.mode)
        assertFalse(open.speak)
        e.handle("start", ResponseMode.TEXT)
        val first = e.state.current!!.taskId
        val skip = e.handle("skip", ResponseMode.TEXT)
        assertEquals(MorningTaskStatus.SKIPPED, e.state.plan!!.tasks.single { it.taskId == first }.status)
        assertTrue(skip.line.display.contains("2 of 5"))
        assertFalse(skip.speak)
    }

    @Test
    fun callNeverClaimsCompletedAndHandlesMissingNumber() = runBlocking {
        val e = engine()
        e.prepare(snapshot(parties = arrayOf(kumar, customer("c-n", "Nandhu", 900.0, today, phone = null))))
        e.act(MorningCommand.Start)
        val call = e.handle("Call Kumar", ResponseMode.TEXT)
        assertTrue(call.line.display.contains("Kumar-ku call panna ready"))
        assertFalse(call.line.display.lowercase().contains("completed"))
        val dial = call.effects.single() as MorningEffect.OpenDialer
        assertEquals("9876543210", dial.phone)
        // The task is marked only once the dialer actually opened.
        assertTrue(e.state.plan!!.task(MorningTaskType.COLLECT_PAYMENT, "c-kumar").open)
        e.onResult(MorningResult.DialerOpened(dial.taskId))
        assertFalse(e.state.plan!!.task(MorningTaskType.COLLECT_PAYMENT, "c-kumar").open)
        val none = e.handle("Call Nandhu", ResponseMode.TEXT)
        assertTrue(none.effects.isEmpty())
        assertTrue(none.line.display.contains("Nandhu phone number available illa"))
    }

    @Test
    fun whatsAppIsPreparedNeverSentAutomatically() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        e.act(MorningCommand.Start)
        val wa = e.act(MorningCommand.WhatsApp("Kumar"))
        val draft = wa.effects.single() as MorningEffect.WhatsAppDraft
        assertEquals("Kumar, ₹12,000 pending irukku. Please settle pannunga.", draft.message)
        assertTrue(e.state.whatsApp != null)
        // Cancel: nothing opened.
        assertTrue(e.handle("cancel", ResponseMode.TEXT).effects.isEmpty())
        e.act(MorningCommand.WhatsApp("Kumar"))
        val send = e.sendWhatsApp("Kumar, ₹12,000 pending. Indru settle pannunga.")
        val open = send.effects.single() as MorningEffect.OpenWhatsApp
        assertEquals("Kumar, ₹12,000 pending. Indru settle pannunga.", open.message)
    }

    @Test
    fun ambiguousAmountIsAskedNeverGuessed() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val unclear = e.handle("Kumar-ku ... 12...", ResponseMode.VOICE)
        assertTrue(unclear.effects.isEmpty())
        assertTrue(unclear.line.display.contains("amount clear-ah kekala"))
        val missing = e.handle("Kumar payment pannu", ResponseMode.TEXT)
        assertTrue(missing.effects.isEmpty())
        assertTrue(missing.line.display, missing.line.display.contains("Kumar kitta evlo amount"))
        // The answer fills only the missing amount.
        val filled = e.handle("12000", ResponseMode.TEXT)
        val review = filled.effects.single() as MorningEffect.ReviewPayment
        assertEquals(12_000.0, review.draft.amount, 0.0)
        // Silence / unclear words are never a confirmation.
        assertTrue(e.handle("hmm sari ah", ResponseMode.TEXT).effects.isEmpty())
        assertTrue(e.handle("cancel", ResponseMode.TEXT).line.display.contains("cancel"))
    }

    @Test
    fun completingEveryTaskShowsCompletionAndSummary() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        e.act(MorningCommand.Start)
        var last: MorningReply? = null
        repeat(5) { i ->
            last = if (i == 1) {
                val id = e.state.current!!.taskId
                e.handle("tomorrow morning", ResponseMode.TEXT)
                e.onResult(MorningResult.ReminderCreated(id, "rem-x"))
            } else {
                e.act(MorningCommand.Done)
            }
        }
        assertTrue(last!!.effects.contains(MorningEffect.Completed))
        assertTrue(last!!.line.display, last!!.line.display.contains("Morning Work Complete"))
        val s = e.state.summary!!
        assertEquals(5, s.total)
        assertEquals(4, s.completed)
        assertEquals(1, s.postponed)
        assertEquals(12_000.0, s.collections, 0.0)
        assertEquals(20_000.0, s.payments, 0.0)
        assertEquals(1, s.lowStockProducts)
        assertEquals(1, s.reminders)
        assertEquals(1, s.followUps)
    }

    @Test
    fun partiallyPaidInvoiceBecomesFollowUp() = runBlocking {
        val e = engine()
        e.prepare(fullSnapshot())
        val t = e.state.plan!!.task(MorningTaskType.PAYMENT_FOLLOWUP, "c-ravi")
        assertEquals(MorningPriority.MEDIUM, t.priority)
        assertEquals(3_500.0, t.facts.paid!!, 0.0)
        assertEquals(5_000.0, t.facts.amount!!, 0.0)
    }

    @Test
    fun expiredBatchIsCritical() = runBlocking {
        val e = engine()
        e.prepare(MorningSnapshot("biz-1", batches = listOf(
            MorningBatch("b-1", "p-milk", "Milk", "B12", day - 1, 4.0, "PCS"),
            MorningBatch("b-2", "p-curd", "Curd", "C3", day + 3, 2.0, "PCS"),
            MorningBatch("b-3", "p-ghee", "Ghee", "G1", day - 10, 0.0, "PCS"),
        )))
        val plan = e.state.plan!!
        assertEquals(MorningPriority.CRITICAL, plan.task(MorningTaskType.EXPIRY, "b-1").priority)
        assertEquals(MorningPriority.MEDIUM, plan.task(MorningTaskType.EXPIRY, "b-2").priority)
        assertNull(plan.tasks.firstOrNull { it.sourceId == "b-3" }) // nothing left in stock
    }
}
