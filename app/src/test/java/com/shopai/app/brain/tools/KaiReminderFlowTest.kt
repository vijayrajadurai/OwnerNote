package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Kai Smart Persistent Reminder: the state machine the one reminder engine follows
 * (SCHEDULED → TRIGGERED → DONE / SNOOZED / CANCELLED, retry every 5 minutes,
 * at most 5 attempts → EXHAUSTED), the Urgent Action Mode words, the
 * full-screen / fallback decision and owner / business isolation.
 * Time is a plain number here — no test waits 5 real minutes.
 */
class KaiReminderFlowTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    private fun call(person: String? = "Praba", status: ReminderStatus = ReminderStatus.ACTIVE, lang: KaiLang = KaiLang.TANGLISH) = KaiReminder(
        id = "R1", title = "Call Praba", task = "Praba-ku call panna", action = ReminderAction.CALL, person = person, phone = "+919000000001",
        triggerAt = t0, zone = "Asia/Kolkata", status = status, notificationMessage = "", sourceText = "Praba-ku 2 minutes-la call pannanum nyabagam paduthu",
        createdAt = t0 - 2 * min, lang = lang,
    )

    // 9, 13, 14, 15, 29: trigger → retry in 5 min → … → 5th ring is the last.
    @Test
    fun retriesEveryFiveMinutesThenExhausts() {
        var r = KaiReminderFlow.trigger(call(), t0, newOccurrence = true)
        assertEquals(ReminderStatus.RANG, r.status)
        assertEquals(1, r.attemptCount)
        assertEquals(t0 + 5 * min, r.snoozedUntil)
        assertEquals(t0 + 5 * min, r.nextTriggerAt)
        for (attempt in 2..4) {
            val at = r.snoozedUntil!!
            assertTrue(KaiReminderFlow.canRing(r))
            r = KaiReminderFlow.trigger(r, at, newOccurrence = false)
            assertEquals(attempt, r.attemptCount)
            assertEquals(ReminderStatus.RANG, r.status)
            assertEquals(at + 5 * min, r.snoozedUntil)
        }
        r = KaiReminderFlow.trigger(r, r.snoozedUntil!!, newOccurrence = false)
        assertEquals(5, r.attemptCount)
        assertEquals(ReminderStatus.EXHAUSTED, r.status)
        assertNull("no retry after the 5th", r.snoozedUntil)
        assertNull(r.nextTriggerAt)
        assertFalse("never rings a 6th time", KaiReminderFlow.canRing(r))
        assertFalse("no snooze on the last attempt", KaiReminderFlow.canSnooze(r))
        assertNull(KaiReminderFlow.snooze(r, t0))
        // History keeps it; Done on the last screen still completes it.
        assertEquals(ReminderStatus.COMPLETED, KaiReminderFlow.complete(r, t0)!!.status)
    }

    // 10, 30: Done stops everything; a finished or cancelled reminder can never ring again.
    @Test
    fun doneAndCancelStopFutureRings() {
        val rang = KaiReminderFlow.trigger(call(), t0, newOccurrence = true)
        val done = KaiReminderFlow.complete(rang, t0 + min)!!
        assertEquals(ReminderStatus.COMPLETED, done.status)
        assertEquals(t0 + min, done.completedAt)
        assertNull(done.snoozedUntil)
        assertFalse(KaiReminderFlow.canRing(done))
        assertNull(done.nextTriggerAt)
        // 8: cancel before it rings.
        val cancelled = KaiReminderFlow.cancel(call(), t0 - min)!!
        assertEquals(ReminderStatus.CANCELLED, cancelled.status)
        assertFalse(KaiReminderFlow.canRing(cancelled))
        assertNull("a cancelled reminder can't be cancelled / snoozed again", KaiReminderFlow.cancel(cancelled, t0))
        assertNull(KaiReminderFlow.snooze(cancelled, t0))
    }

    // 12, 13: snooze → SNOOZED, one next ring in exactly 5 minutes; the next ring is attempt 2.
    @Test
    fun snoozeSchedulesExactlyOneNextRing() {
        val rang = KaiReminderFlow.trigger(call(), t0, newOccurrence = true)
        val snoozed = KaiReminderFlow.snooze(rang, t0 + 30_000)!!
        assertEquals(ReminderStatus.SNOOZED, snoozed.status)
        assertEquals(1, snoozed.snoozeCount)
        assertEquals(t0 + 30_000 + 5 * min, snoozed.snoozedUntil)
        assertTrue(snoozed.open)
        val again = KaiReminderFlow.trigger(snoozed, snoozed.snoozedUntil!!, newOccurrence = false)
        assertEquals(ReminderStatus.RANG, again.status)
        assertEquals(2, again.attemptCount)
    }

    // A repeating reminder keeps its series: attempts restart at its next time; Done keeps it scheduled.
    @Test
    fun repeatingReminderCountsPerOccurrence() {
        val daily = call().copy(recurrence = Recurrence(Repeat.DAILY))
        var r = KaiReminderFlow.trigger(daily, t0, newOccurrence = true)
        r = KaiReminderFlow.trigger(r, r.snoozedUntil!!, newOccurrence = false)
        assertEquals(2, r.attemptCount)
        assertEquals(ReminderStatus.ACTIVE, r.status)
        val tomorrow = KaiReminderFlow.trigger(r.copy(triggerAt = t0 + 86_400_000), t0 + 86_400_000, newOccurrence = true)
        assertEquals(1, tomorrow.attemptCount)
        val done = KaiReminderFlow.complete(tomorrow, t0 + 86_400_000 + min)!!
        assertEquals(ReminderStatus.ACTIVE, done.status)
        assertEquals(0, done.attemptCount)
    }

    // 22: Kai speaks each attempt once — the key doesn't change when the screen is recreated.
    @Test
    fun speechKeyIsPerAttempt() {
        val first = KaiReminderFlow.trigger(call(), t0, newOccurrence = true)
        assertEquals(KaiReminderFlow.speechKey(first), KaiReminderFlow.speechKey(first.copy(updatedAt = t0 + 1)))
        val second = KaiReminderFlow.trigger(first, first.snoozedUntil!!, newOccurrence = false)
        assertTrue(KaiReminderFlow.speechKey(first) != KaiReminderFlow.speechKey(second))
    }

    // 11 + section 13: the urgent words come only from the stored reminder.
    @Test
    fun urgentWordsPerAttemptAndAction() {
        val r1 = KaiReminderFlow.trigger(call(), t0, newOccurrence = true)
        val w1 = KaiUrgentWords.text(r1)
        assertEquals("Praba-ku call panna vendiya neram aachu", w1.headline)
        assertEquals("Ippo call pannalama?", w1.question)
        assertEquals("Owner, Praba-ku call panna vendiya neram aachu. Ippo call pannalama?", w1.speech)
        assertEquals("Reminder 1 of 5", w1.attemptLine)
        assertEquals("REMINDER", w1.header)
        val r2 = KaiReminderFlow.trigger(r1, r1.snoozedUntil!!, newOccurrence = false)
        assertEquals("Owner, innum Praba-ku call pannala. Ippo call pannalama?", KaiUrgentWords.text(r2).speech)
        assertEquals("Reminder 2 of 5", KaiUrgentWords.text(r2).attemptLine)
        val r3 = KaiReminderFlow.trigger(r2, r2.snoozedUntil!!, newOccurrence = false)
        assertTrue(KaiUrgentWords.text(r3).speech, KaiUrgentWords.text(r3).speech.startsWith("Owner, Praba call reminder innum pending-la irukku."))

        fun first(r: KaiReminder) = KaiUrgentWords.text(KaiReminderFlow.trigger(r, t0, newOccurrence = true))
        val pay = call().copy(action = ReminderAction.PAYMENT, person = "Kumar", task = "Kumar-ku 5000 payment", amount = BigDecimal("5000"))
        assertTrue(first(pay).speech, first(pay).speech.startsWith("Owner, Kumar-ku ₹5,000 payment panna vendiya reminder."))
        val collect = call().copy(action = ReminderAction.COLLECTION, person = "Kumar", task = "Kumar kitta 5000 collect", amount = BigDecimal("5000"))
        assertTrue(first(collect).speech, first(collect).speech.startsWith("Owner, Kumar kitta ₹5,000 collect panna vendiya reminder."))
        val stock = call().copy(action = ReminderAction.STOCK, person = null, task = "Colgate stock check panna")
        assertTrue(first(stock).speech, first(stock).speech.startsWith("Owner, Colgate stock check panna vendiya reminder."))
        val general = call().copy(action = ReminderAction.TASK, person = null, task = "kadai saavi eduthuka")
        assertTrue(first(general).speech, first(general).speech.startsWith("Owner, neenga remind panna sonna task pending-la irukku."))
        // Never invented: no amount stored → no ₹ said; no person → no name.
        val payNoAmount = call().copy(action = ReminderAction.PAYMENT, person = null, task = "rent kattanum", amount = null)
        assertFalse(first(payNoAmount).speech.contains("₹"))
        assertEquals("Owner, payment panna vendiya reminder. Mudinjadhum Done press pannunga.", first(payNoAmount).speech)
        // Kai's own languages.
        assertEquals("ஓனர், Praba-க்கு call பண்ண வேண்டிய நேரம் ஆச்சு. இப்போ call பண்ணலாமா?", first(call(lang = KaiLang.TAMIL)).speech)
        assertEquals("Owner, time to call Praba. Call now?", first(call(lang = KaiLang.ENGLISH)).speech)
    }

    // 25: replies never claim a call happened.
    @Test
    fun replyWordsNeverClaimACall() {
        assertEquals("Call screen open pannitten Owner.", KaiUrgentWords.callOpened(KaiLang.TANGLISH))
        assertEquals("Seri Owner. Reminder complete.", KaiUrgentWords.done(KaiLang.TANGLISH))
        assertEquals("Seri Owner. 5 minutes-ku snooze pannitten.", KaiUrgentWords.snoozed(5, KaiLang.TANGLISH))
        for (l in KaiLang.values()) {
            val all = listOf(KaiUrgentWords.callOpened(l), KaiUrgentWords.done(l), KaiUrgentWords.snoozed(5, l))
            assertFalse(all.toString(), all.any { it.contains("call pannitten", true) || it.contains("called", true) || it.contains("answered", true) })
        }
        assertEquals("Set 2 minutes ago", KaiUrgentWords.setAgo(t0 - 2 * min, t0, KaiLang.TANGLISH))
    }

    // 23, 24, 26, 27, 28: how Urgent Action Mode reaches the owner.
    @Test
    fun presentationFollowsTheRealCapability() {
        // Foreground, unlocked: Urgent Action Mode directly.
        assertEquals(UrgentPresentation.DIRECT_ACTIVITY, KaiUrgentPresentation.decide(canUseFullScreenIntent = false, interactive = true, locked = false, appInForeground = true))
        // Locked (screen off): full screen only when Android allows it.
        assertEquals(UrgentPresentation.FULL_SCREEN_INTENT, KaiUrgentPresentation.decide(true, interactive = false, locked = true, appInForeground = true))
        assertEquals(UrgentPresentation.NOTIFICATION_ONLY, KaiUrgentPresentation.decide(false, interactive = false, locked = true, appInForeground = true))
        // Background, unlocked.
        assertEquals(UrgentPresentation.FULL_SCREEN_INTENT, KaiUrgentPresentation.decide(true, interactive = true, locked = false, appInForeground = false))
        assertEquals(UrgentPresentation.NOTIFICATION_ONLY, KaiUrgentPresentation.decide(false, interactive = true, locked = false, appInForeground = false))
        // Foreground but the screen is locked: never a direct start over the keyguard.
        assertEquals(UrgentPresentation.FULL_SCREEN_INTENT, KaiUrgentPresentation.decide(true, interactive = true, locked = true, appInForeground = true))
        assertEquals("FULL_SCREEN_PERMITTED", KaiUrgentPresentation.reportLabel(true))
        // No duplicate banner over Kai: removed once his screen shows — except the fallback, where the notification IS the reminder.
        assertTrue(KaiUrgentPresentation.dismissNotificationWhenShown(UrgentPresentation.FULL_SCREEN_INTENT))
        assertTrue(KaiUrgentPresentation.dismissNotificationWhenShown(UrgentPresentation.DIRECT_ACTIVITY))
        assertFalse(KaiUrgentPresentation.dismissNotificationWhenShown(UrgentPresentation.NOTIFICATION_ONLY))
        // App open + unlocked: Kai opens directly, the notification is silent (no heads-up banner) and has no full-screen intent.
        assertTrue(KaiUrgentPresentation.silentNotification(UrgentPresentation.DIRECT_ACTIVITY))
        assertFalse(KaiUrgentPresentation.silentNotification(UrgentPresentation.FULL_SCREEN_INTENT))
        assertFalse(KaiUrgentPresentation.silentNotification(UrgentPresentation.NOTIFICATION_ONLY))
        assertFalse(KaiUrgentPresentation.useFullScreenIntent(UrgentPresentation.DIRECT_ACTIVITY))
        assertTrue(KaiUrgentPresentation.useFullScreenIntent(UrgentPresentation.FULL_SCREEN_INTENT))
        assertFalse(KaiUrgentPresentation.useFullScreenIntent(UrgentPresentation.NOTIFICATION_ONLY))
        assertEquals("FULL_SCREEN_NOT_PERMITTED", KaiUrgentPresentation.reportLabel(false))
    }

    // 20, 21: owner A never sees owner B's reminders; business A never sees business B's.
    @Test
    fun ownerAndBusinessIsolation() {
        val a = call().copy(id = "a", businessId = "biz-A", ownerId = "owner-A")
        val b = call().copy(id = "b", businessId = "biz-A", ownerId = "owner-B")
        val c = call().copy(id = "c", businessId = "biz-B", ownerId = "owner-A")
        val legacy = call().copy(id = "old")
        assertEquals(listOf("a", "old"), KaiReminderScope.visible(listOf(a, b, c, legacy), "biz-A", "owner-A").map { it.id })
        assertEquals(listOf("b", "old"), KaiReminderScope.visible(listOf(a, b, c, legacy), "biz-A", "owner-B").map { it.id })
        assertEquals(listOf("c", "old"), KaiReminderScope.visible(listOf(a, b, c, legacy), "biz-B", "owner-A").map { it.id })
    }

    // Amounts only when said; times are never read as money.
    @Test
    fun amountsComeOnlyFromTheOwnersWords() {
        val pay = KaiReminderUnderstanding.understand("naalaikku 10 manikku Kumar-ku 5000 payment panna remind pannu", java.time.LocalDateTime.of(2026, 10, 5, 9, 0), listOf("Kumar")) as ReminderRequest.Create
        assertEquals(ReminderAction.PAYMENT, pay.draft.action)
        assertEquals(0, BigDecimal("5000").compareTo(pay.draft.amount))
        val noMoney = KaiReminderUnderstanding.understand("naalaikku 10 manikku Kumar-ku payment remind pannu", java.time.LocalDateTime.of(2026, 10, 5, 9, 0), listOf("Kumar")) as ReminderRequest.Create
        assertNull(noMoney.draft.amount)
        val call = KaiReminderUnderstanding.understand("Praba-ku 2 minutes-la call pannanum nyabagam paduthu", java.time.LocalDateTime.of(2026, 10, 5, 9, 0), emptyList()) as ReminderRequest.Create
        assertNull(call.draft.amount)
        assertNotNull(call.draft.time?.relative)
    }
}
