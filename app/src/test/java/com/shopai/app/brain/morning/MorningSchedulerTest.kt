package com.shopai.app.brain.morning

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.ScheduleResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** PHASE 2 — A: the daily Morning Work notification (scheduling rules; the phone's alarm is a fake). */
class MorningSchedulerTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private var now = ZonedDateTime.of(2026, 10, 3, 7, 0, 0, 0, ist)
    private val ownerA = MorningOwner("biz-A", "owner-A")
    private val ownerB = MorningOwner("biz-A", "owner-B")

    /** The phone's alarm: one id — arming replaces, like AlarmManager with one PendingIntent. */
    private class Phone : MorningAlarmPort {
        var armed: Pair<MorningOwner, Long>? = null
        var arms = 0
        var cancels = 0
        var result = ScheduleResult.EXACT
        override fun arm(owner: MorningOwner, atMillis: Long): ScheduleResult { armed = owner to atMillis; arms++; return result }
        override fun cancel() { armed = null; cancels++ }
    }

    private val store = InMemoryMorningScheduleStore()
    private val phone = Phone()
    private fun scheduler() = MorningScheduler(store, phone) { now }
    private fun at(h: Int, m: Int = 0, day: LocalDate = now.toLocalDate(), zone: ZoneId = ist) =
        ZonedDateTime.of(day.atTime(h, m), zone).toInstant().toEpochMilli()

    @Test
    fun offByDefault() {
        assertEquals(MorningNotificationSetting(enabled = false, hour = 8, minute = 0), scheduler().setting(ownerA))
        assertNull(scheduler().restore(ownerA))
        assertNull(phone.armed)
    }

    @Test
    fun enableAt8ThenDisable() {
        val s = scheduler()
        assertEquals(ScheduleResult.EXACT, s.update(ownerA, MorningNotificationSetting(true, 8, 0)))
        assertEquals(ownerA to at(8), phone.armed)
        // The owner's own time, not a fixed one.
        s.update(ownerA, MorningNotificationSetting(true, 6, 45))
        assertEquals(ownerA to at(6, 45, now.toLocalDate().plusDays(1)), phone.armed) // 6:45 has passed today → tomorrow
        // Disable → the alarm is cancelled.
        assertNull(s.update(ownerA, MorningNotificationSetting(false, 6, 45)))
        assertNull(phone.armed)
        assertTrue(phone.cancels > 0)
    }

    @Test
    fun neverSilent() {
        phone.result = ScheduleResult.APPROXIMATE
        assertEquals(ScheduleResult.APPROXIMATE, scheduler().update(ownerA, MorningNotificationSetting(true, 8, 0)))
        phone.result = ScheduleResult.NOTIFICATIONS_OFF
        assertEquals(ScheduleResult.NOTIFICATIONS_OFF, scheduler().restore(ownerA))
    }

    @Test
    fun restoredAfterRestartAndUpdate() {
        scheduler().update(ownerA, MorningNotificationSetting(true, 8, 0))
        phone.armed = null // reboot: alarms are gone
        // Boot / app update / app start: a new scheduler over the same saved setting.
        assertEquals(ScheduleResult.EXACT, scheduler().restore(ownerA))
        assertEquals(ownerA to at(8), phone.armed)
    }

    @Test
    fun noDuplicates() {
        val s = scheduler()
        s.update(ownerA, MorningNotificationSetting(true, 8, 0))
        s.restore(ownerA)
        s.restore(ownerA)
        assertEquals(ownerA to at(8), phone.armed) // one alarm (same id), re-armed in place
        now = now.withHour(8)
        assertEquals(MorningFire.Show(ownerA), s.onFire(ownerA, ownerA))
        // The same morning again (a second ring / restore race): not shown twice.
        assertEquals(MorningFire.Skip("already shown today"), s.onFire(ownerA, ownerA))
        // Tomorrow's is armed.
        assertEquals(ownerA to at(8, 0, now.toLocalDate().plusDays(1)), phone.armed)
        // A restore later today keeps tomorrow (today was shown).
        now = now.withMinute(30)
        s.restore(ownerA)
        assertEquals(ownerA to at(8, 0, now.toLocalDate().plusDays(1)), phone.armed)
    }

    @Test
    fun ownerANeverGetsOwnerBsNotification() {
        val s = scheduler()
        s.update(ownerA, MorningNotificationSetting(true, 8, 0))
        // Owner B signs in on the same phone (B has it off): A's alarm is cancelled.
        assertNull(s.restore(ownerB))
        assertNull(phone.armed)
        // An alarm armed for A that still rings while B is signed in: nothing is shown.
        now = now.withHour(8)
        assertEquals(MorningFire.Skip("another owner"), s.onFire(ownerA, ownerB))
        // Signed out: nothing is shown, nothing stays armed.
        assertEquals(MorningFire.Skip("signed out"), s.onFire(ownerA, null))
        assertNull(phone.armed)
        // B turns it on at 9:30: B's own alarm, B's own setting; A's setting unchanged.
        s.update(ownerB, MorningNotificationSetting(true, 9, 30))
        assertEquals(ownerB to at(9, 30), phone.armed)
        assertEquals(MorningNotificationSetting(true, 8, 0), s.setting(ownerA))
        // A logs in again: A's 8:00 (tomorrow — it's 8:00 now).
        s.restore(ownerA)
        assertEquals(ownerA, phone.armed!!.first)
        // Another business of the same owner is a separate setting.
        assertFalse(s.setting(MorningOwner("biz-B", "owner-A")).enabled)
    }

    @Test
    fun timeZoneAndDst() {
        // A clock / zone change: restore uses the phone's zone now (the owner's wall-clock time).
        scheduler().update(ownerA, MorningNotificationSetting(true, 8, 0))
        val dubai = ZoneId.of("Asia/Dubai")
        now = ZonedDateTime.of(2026, 10, 3, 6, 0, 0, 0, dubai)
        scheduler().restore(ownerA)
        assertEquals(at(8, 0, LocalDate.of(2026, 10, 3), dubai), phone.armed!!.second)

        // DST gap (New York, 8 Mar 2026: 02:00 → 03:00): 02:30 doesn't exist → 03:30 that day.
        val ny = ZoneId.of("America/New_York")
        val gap = MorningScheduler.nextTrigger(ZonedDateTime.of(2026, 3, 8, 0, 30, 0, 0, ny), 2, 30)
        assertEquals(LocalDate.of(2026, 3, 8), gap.toLocalDate())
        assertEquals(3, gap.hour)
        // DST end (1 Nov 2026: 01:00–02:00 twice): one trigger, the first 01:30.
        val overlap = MorningScheduler.nextTrigger(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ny), 1, 30)
        assertEquals(ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ny).withEarlierOffsetAtOverlap(), overlap)
        // The next day across the change is still 8:00 local.
        val after = MorningScheduler.nextTrigger(ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, ny), 8, 0)
        assertEquals(8, after.hour)
        assertEquals(LocalDate.of(2026, 3, 8), after.toLocalDate())
    }

    // ---------------------------------------------------------------- the notification's content

    private val kumar = MorningParty("c-kumar", "Kumar", MorningPartyKind.CUSTOMER, "9000000001", 8_000.0, LocalDate.of(2026, 10, 1).toEpochDay())

    @Test
    fun notificationUsesTheRecordsNow() = runBlocking {
        val engine = MorningWorkEngine(InMemoryMorningTaskStore(), clock = { ZonedDateTime.of(2026, 10, 3, 8, 0, 0, 0, ist) })
        // Real records → "ready"; then the money is received (records change) → the next morning reads the new state.
        val withWork = engine.generate(MorningSnapshot("biz-A", parties = listOf(kumar), ownerId = "owner-A"), KaiLang.TANGLISH, MorningTrigger.SCHEDULED)
        assertEquals("Good morning Owner ☀️" to "Your Morning Work is ready.", MorningBriefs.notification(withWork))
        val settled = engine.generate(MorningSnapshot("biz-A", parties = listOf(kumar.copy(pending = 0.0)), ownerId = "owner-A"), KaiLang.TANGLISH, MorningTrigger.SCHEDULED)
        val (title, body) = MorningBriefs.notification(settled)
        assertEquals("Good morning Owner ☀️", title)
        assertEquals("Nothing urgent right now. You're all clear.", body)
        assertFalse(body.contains("₹0"))
        // Tamil.
        assertEquals("குட் மார்னிங் ஓனர் ☀️", MorningBriefs.notification(engine.generate(MorningSnapshot("biz-A", parties = listOf(kumar)), KaiLang.TAMIL)).first)
    }

    @Test
    fun alarmIdIsFixed() {
        // One morning alarm / notification on the phone (the request code the Android side uses).
        assertEquals(0x4D4F524E, MorningScheduler.ALARM_ID)
        assertEquals("biz-A~owner-A", ownerA.key)
        assertEquals("biz-A~-", MorningOwner("biz-A", null).key)
        assertTrue(Instant.ofEpochMilli(at(8)).atZone(ist).hour == 8)
    }
}
