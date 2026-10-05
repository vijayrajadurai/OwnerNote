package com.shopai.app.notifications

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.Recurrence
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.brain.tools.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId

/** The real reminder engine on a simulated phone: alarms, notifications, restart, snooze, repeats. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KaiReminderEngineTest {
    private lateinit var context: Context
    private lateinit var engine: KaiReminderEngine
    private val zone = ZoneId.systemDefault().id

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = KaiReminderEngine(context)
        engine.clear()
    }

    private fun reminder(id: String, inMillis: Long, recurrence: Recurrence = Recurrence.ONCE, time: LocalTime? = null) = KaiReminder(
        id = id, title = "Call Kumar", task = "Kumar-ku call panna", action = ReminderAction.CALL, person = "Kumar", phone = "+919000000001",
        triggerAt = System.currentTimeMillis() + inMillis, time = time, zone = zone, recurrence = recurrence,
        notificationMessage = "Owner, Kumar-ku call panna sonneenga.", sourceText = "test", createdAt = System.currentTimeMillis(),
    )

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Test
    fun exactAlarmAtTheTriggerTime() {
        val r = reminder("r1", 600_000)
        val saved = engine.create(r)
        assertEquals(false, saved.duplicate)
        val alarm = alarms().single()
        assertEquals(r.triggerAt, alarm.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        // Said twice: the same reminder, no second alarm.
        assertTrue(engine.create(reminder("r2", 600_000)).duplicate)
        assertEquals(1, engine.open().size)
    }

    @Test
    fun ringsOnceEvenAfterRestart() {
        engine.create(reminder("r1", -1_000))
        engine.fired("r1", snooze = false)
        assertEquals(1, notifications().size)
        // Kai Urgent Action Mode's words (attempt 1), never "called".
        assertEquals("Owner, Kumar-ku call panna vendiya neram aachu. Ippo call pannalama?", shadowOf(notifications().single()).contentText)
        assertEquals(1, engine.find("r1")!!.attemptCount)
        assertEquals(ReminderStatus.RANG, engine.find("r1")!!.status)
        // App restart / boot / a duplicate alarm: no second notification.
        KaiReminderEngine(context).rearmAll()
        engine.fired("r1", snooze = false)
        assertEquals(1, notifications().size)
        assertEquals("r1", engine.lastRang()?.id)
    }

    @Test
    fun snoozeAndDone() {
        engine.create(reminder("r1", -1_000))
        engine.fired("r1", snooze = false)
        val snoozed = engine.snooze("r1", 10)
        assertNotNull(snoozed?.snoozedUntil)
        assertTrue(snoozed!!.snoozedUntil!! - System.currentTimeMillis() in 590_000..600_000)
        assertTrue(engine.complete("r1"))
        assertEquals(ReminderStatus.COMPLETED, engine.find("r1")!!.status)
        assertTrue(engine.open().isEmpty())
        assertNull(engine.lastRang())
    }

    @Test
    fun repeatingReminderMovesToItsNextTime() {
        val daily = reminder("d1", -1_000, Recurrence(Repeat.DAILY), time = LocalTime.now().withNano(0))
        engine.create(daily)
        engine.fired("d1", snooze = false)
        val next = engine.find("d1")!!
        assertEquals(ReminderStatus.ACTIVE, next.status)
        assertTrue(next.triggerAt > System.currentTimeMillis())
        assertTrue(next.triggerAt - daily.triggerAt in 86_000_000L..86_500_000L)
        // Done on a repeating reminder keeps the series.
        engine.complete("d1")
        assertEquals(ReminderStatus.ACTIVE, engine.find("d1")!!.status)
    }

    @Test
    fun cancelAndUpdate() {
        engine.create(reminder("r1", 600_000))
        val moved = engine.update(engine.find("r1")!!.copy(triggerAt = System.currentTimeMillis() + 1_800_000))
        assertEquals(1, engine.open().size)
        assertEquals(moved.reminder.triggerAt, engine.find("r1")!!.triggerAt)
        assertTrue(engine.cancel("r1"))
        assertEquals(ReminderStatus.CANCELLED, engine.find("r1")!!.status)
        assertTrue(engine.open().isEmpty())
    }

    // Kai Smart Persistent Reminder: a retry every 5 minutes, at most 5 rings, then EXHAUSTED.
    @Test
    fun retriesEveryFiveMinutesThenStops() {
        var t = System.currentTimeMillis()
        val e = KaiReminderEngine(context) { t }
        e.create(reminder("p1", -1_000))
        e.fired("p1", snooze = false)
        assertEquals(1, e.find("p1")!!.attemptCount)
        assertEquals(t + 300_000, e.find("p1")!!.snoozedUntil)
        for (attempt in 2..5) {
            t += 300_000
            e.fired("p1", snooze = true)
            assertEquals(attempt, e.find("p1")!!.attemptCount)
        }
        assertEquals(ReminderStatus.EXHAUSTED, e.find("p1")!!.status)
        // No 6th ring, no snooze.
        t += 300_000
        e.fired("p1", snooze = true)
        assertEquals(5, e.find("p1")!!.attemptCount)
        assertNull(e.snooze("p1", 5))
        assertTrue(e.lastPresentation()!!.startsWith("p1|5|"))
    }

    // Done / cancel: the retry never comes back.
    @Test
    fun doneAndCancelStopRetries() {
        var t = System.currentTimeMillis()
        val e = KaiReminderEngine(context) { t }
        e.create(reminder("p1", -1_000))
        e.fired("p1", snooze = false)
        assertTrue(e.complete("p1"))
        t += 300_000
        e.fired("p1", snooze = true)
        assertEquals(ReminderStatus.COMPLETED, e.find("p1")!!.status)
        assertEquals(1, e.find("p1")!!.attemptCount)

        e.create(reminder("p2", 600_000).copy(task = "Ravi-ku call panna", person = "Ravi"))
        assertTrue(e.cancel("p2"))
        t += 600_000
        e.fired("p2", snooze = false)
        assertEquals(ReminderStatus.CANCELLED, e.find("p2")!!.status)
        assertEquals(0, e.find("p2")!!.attemptCount)
    }

    // Snooze 5 min: SNOOZED, rings again as attempt 2; Kai speaks each attempt once.
    @Test
    fun snoozeFiveAndSpeakOnce() {
        var t = System.currentTimeMillis()
        val e = KaiReminderEngine(context) { t }
        e.create(reminder("p1", -1_000))
        e.fired("p1", snooze = false)
        val first = e.find("p1")!!
        assertTrue(e.claimSpeech(first))
        assertTrue("recreated screen: not spoken again", !e.claimSpeech(e.find("p1")!!))
        val s = e.snooze("p1", KaiReminderEngine.SNOOZE_MINUTES)!!
        assertEquals(ReminderStatus.SNOOZED, s.status)
        t += 300_000
        e.fired("p1", snooze = true)
        val second = e.find("p1")!!
        assertEquals(ReminderStatus.RANG, second.status)
        assertEquals(2, second.attemptCount)
        assertTrue(e.claimSpeech(second))
    }

    // Saved fields survive an app restart (a new engine reads the same store).
    @Test
    fun newFieldsSurviveRestart() {
        val e = KaiReminderEngine(context)
        e.create(reminder("p1", 600_000).copy(businessId = "biz", ownerId = "owner", amount = java.math.BigDecimal("5000"), lang = com.shopai.app.brain.KaiLang.TAMIL))
        val back = KaiReminderEngine(context).find("p1")!!
        assertEquals("owner", back.ownerId)
        assertEquals(0, java.math.BigDecimal("5000").compareTo(back.amount))
        assertEquals(com.shopai.app.brain.KaiLang.TAMIL, back.lang)
        assertEquals(5, back.maxAttempts)
    }
}
