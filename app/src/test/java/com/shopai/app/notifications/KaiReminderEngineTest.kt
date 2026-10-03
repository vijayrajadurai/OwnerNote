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
        assertEquals("Owner, Kumar-ku call panna sonneenga.", shadowOf(notifications().single()).contentText)
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
}
