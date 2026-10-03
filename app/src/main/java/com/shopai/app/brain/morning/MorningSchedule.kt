package com.shopai.app.brain.morning

import com.shopai.app.brain.tools.ScheduleResult
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/** The owner's "Morning Work Notification" setting. OFF until the owner turns it on; the time is the owner's. */
data class MorningNotificationSetting(val enabled: Boolean = false, val hour: Int = 8, val minute: Int = 0) {
    init {
        require(hour in 0..23 && minute in 0..59) { "invalid time" }
    }
}

/** Where the settings live (per owner + business — another owner's setting is never read). */
interface MorningScheduleStore {
    fun setting(owner: MorningOwner): MorningNotificationSetting
    fun save(owner: MorningOwner, setting: MorningNotificationSetting)
    /** The day (epoch day) the morning notification was last shown to this owner. */
    fun lastShownDay(owner: MorningOwner): Long?
    fun markShown(owner: MorningOwner, day: Long)
}

/**
 * The phone's alarm for the morning notification. There is ONE morning alarm
 * on the phone (one fixed id): arming again replaces it, so it can never be
 * scheduled twice, and it carries the owner + business it was armed for.
 */
interface MorningAlarmPort {
    fun arm(owner: MorningOwner, atMillis: Long): ScheduleResult
    fun cancel()
}

/** What to do when the morning alarm rings. */
sealed interface MorningFire {
    /** Build the brief for [owner] from the records NOW and show the notification. */
    data class Show(val owner: MorningOwner) : MorningFire

    data class Skip(val reason: String) : MorningFire
}

/**
 * The daily Morning Work notification: when it rings, for whom, and that it
 * rings once. Plain rules (no AI), the phone's alarm behind [MorningAlarmPort].
 *  - OFF by default; the owner picks the time.
 *  - The time is the owner's wall-clock time in the phone's time zone: a DST
 *    gap moves it forward, a zone / clock change re-arms it ([restore]).
 *  - Login / account switch / boot / app update / time change → [restore] for
 *    the signed-in owner only; logout → [restore] (null) cancels it.
 *  - A ring for an owner who is not the signed-in one shows nothing.
 *  - At most one notification per owner per day.
 */
class MorningScheduler(
    private val store: MorningScheduleStore,
    private val alarms: MorningAlarmPort,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    fun setting(owner: MorningOwner): MorningNotificationSetting = store.setting(owner)

    /** The settings switch / time picker. Returns how the alarm could be armed (null: switched off). */
    fun update(owner: MorningOwner, setting: MorningNotificationSetting): ScheduleResult? {
        store.save(owner, setting)
        return restore(owner)
    }

    /** Arms the signed-in owner's next morning (or cancels: signed out / switched off). */
    fun restore(current: MorningOwner?): ScheduleResult? {
        val s = current?.let(store::setting)
        if (current == null || s == null || !s.enabled) {
            alarms.cancel()
            return null
        }
        val now = clock()
        val shownToday = store.lastShownDay(current) == now.toLocalDate().toEpochDay()
        return alarms.arm(current, nextTrigger(now, s.hour, s.minute, notToday = shownToday).toInstant().toEpochMilli())
    }

    /** The alarm rang for [firedFor]; [current] is the signed-in login now. */
    fun onFire(firedFor: MorningOwner?, current: MorningOwner?): MorningFire {
        if (current == null) {
            alarms.cancel()
            return MorningFire.Skip("signed out")
        }
        if (firedFor == null || firedFor.key != current.key) {
            restore(current)
            return MorningFire.Skip("another owner")
        }
        if (!store.setting(current).enabled) {
            restore(current)
            return MorningFire.Skip("switched off")
        }
        val day = clock().toLocalDate().toEpochDay()
        if (store.lastShownDay(current) == day) {
            restore(current)
            return MorningFire.Skip("already shown today")
        }
        store.markShown(current, day)
        restore(current) // tomorrow's
        return MorningFire.Show(current)
    }

    companion object {
        /** The one morning alarm / notification id on the phone. */
        const val ALARM_ID = 0x4D4F524E // "MORN"

        /**
         * The next [hour]:[minute] in [now]'s zone after [now] (tomorrow when
         * it has passed, or [notToday]). A time that doesn't exist (DST gap) is
         * moved forward by the gap; a repeated hour (DST overlap) uses the first.
         */
        fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int, notToday: Boolean = false): ZonedDateTime {
            val at = LocalTime.of(hour, minute)
            val today = ZonedDateTime.of(LocalDateTime.of(now.toLocalDate(), at), now.zone)
            if (!notToday && today.isAfter(now)) return today
            return ZonedDateTime.of(LocalDateTime.of(now.toLocalDate().plusDays(1), at), now.zone)
        }
    }
}

/** Tests, and a fallback store. */
class InMemoryMorningScheduleStore : MorningScheduleStore {
    private val settings = HashMap<String, MorningNotificationSetting>()
    private val shown = HashMap<String, Long>()
    override fun setting(owner: MorningOwner) = settings[owner.key] ?: MorningNotificationSetting()
    override fun save(owner: MorningOwner, setting: MorningNotificationSetting) { settings[owner.key] = setting }
    override fun lastShownDay(owner: MorningOwner) = shown[owner.key]
    override fun markShown(owner: MorningOwner, day: Long) { shown[owner.key] = day }
}
