package com.shopai.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shopai.app.MainActivity
import com.shopai.app.R
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiReminderSchedule
import com.shopai.app.brain.tools.Recurrence
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.brain.tools.Repeat
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.receiver.KaiReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * OwnerNote's reminder engine for Kai — the one place reminders are created,
 * updated, cancelled, completed and snoozed. Stored on the phone (works
 * offline), each reminder has its own exact alarm; the timing is plain code,
 * never an AI. Repeating reminders are re-armed after they ring; everything is
 * re-armed on boot, app start, update and time-zone change.
 */
class KaiReminderEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0L)

    /** Ticks when reminders change (screens refresh on it). */
    val changes: StateFlow<Long> = _changes

    private fun now() = System.currentTimeMillis()
    private fun zone(): ZoneId = ZoneId.systemDefault()

    // ------------------------------------------------------------ store

    @Synchronized
    fun all(): List<KaiReminder> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> runCatching { read(a.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(list: List<KaiReminder>) {
        // Finished reminders are kept 30 days for history.
        val cutoff = now() - 30L * 86_400_000
        val kept = list.filter { it.open || (it.completedAt ?: it.cancelledAt ?: it.updatedAt) > cutoff }
        val a = JSONArray()
        kept.forEach { a.put(write(it)) }
        prefs.edit().putString(KEY, a.toString()).apply()
        _changes.value = now()
    }

    private fun put(r: KaiReminder) = save(all().filter { it.id != r.id } + r)

    fun find(id: String): KaiReminder? = all().firstOrNull { it.id == id }

    /** Open reminders, soonest first. */
    fun open(): List<KaiReminder> = all().filter { it.open }.sortedBy { it.snoozedUntil ?: it.triggerAt }

    // ------------------------------------------------------------ operations

    /** Creates a reminder; the same reminder said twice is not created again. */
    fun create(r: KaiReminder): ReminderSaved {
        all().firstOrNull { KaiReminderSchedule.sameAs(it, r) }?.let { return ReminderSaved(it, duplicate = true, result = status()) }
        put(r)
        return ReminderSaved(r, duplicate = false, result = arm(r))
    }

    /** Saves changes to an existing reminder (same id) and arms it again. */
    fun update(r: KaiReminder): ReminderSaved {
        val updated = r.copy(status = ReminderStatus.ACTIVE, updatedAt = now(), snoozedUntil = null, lastFiredAt = null)
        cancelAlarms(r.id)
        put(updated)
        return ReminderSaved(updated, duplicate = false, result = arm(updated))
    }

    fun cancel(id: String): Boolean {
        val r = find(id)?.takeIf { it.open } ?: return false
        cancelAlarms(id)
        put(r.copy(status = ReminderStatus.CANCELLED, cancelledAt = now(), updatedAt = now(), snoozedUntil = null))
        return true
    }

    /** Done: a one-time reminder is finished; a repeating one waits for its next time. */
    fun complete(id: String): Boolean {
        val r = find(id)?.takeIf { it.open } ?: return false
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(id, snooze = true))
        if (r.recurrence.repeat == Repeat.ONCE) {
            cancelAlarms(id)
            put(r.copy(status = ReminderStatus.COMPLETED, completedAt = now(), updatedAt = now(), snoozedUntil = null))
        } else {
            put(r.copy(status = ReminderStatus.ACTIVE, snoozedUntil = null, updatedAt = now()))
        }
        if (prefs.getString(KEY_LAST_RANG, null) == id) prefs.edit().remove(KEY_LAST_RANG).apply()
        return true
    }

    /** Rings again in exactly [minutes]; a repeating reminder keeps its own schedule. */
    fun snooze(id: String, minutes: Long): KaiReminder? {
        val r = find(id)?.takeIf { it.open } ?: return null
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        val at = now() + minutes * 60_000
        val updated = r.copy(snoozedUntil = at, updatedAt = now())
        put(updated)
        armAt(id, at, snooze = true)
        return updated
    }

    /** The reminder that rang last and is still open (for "Done", "innum 10 minutes"). */
    fun lastRang(): KaiReminder? = prefs.getString(KEY_LAST_RANG, null)?.let(::find)?.takeIf { it.open }

    /** The alarm went off. Guards make sure an occurrence never rings twice. */
    fun fired(id: String, snooze: Boolean) {
        val r = find(id)?.takeIf { it.open } ?: return
        val t = now()
        if (snooze) {
            val until = r.snoozedUntil ?: return
            if (t < until - 60_000) { armAt(id, until, snooze = true); return }
            show(r)
            put(r.copy(snoozedUntil = null))
        } else {
            if (r.status != ReminderStatus.ACTIVE) return
            // Too early (clock change): wait for the real time.
            if (t < r.triggerAt - 60_000) { arm(r); return }
            if (r.lastFiredAt != null && r.lastFiredAt >= r.triggerAt) return
            show(r)
            val next = KaiReminderSchedule.next(r, maxOf(t, r.triggerAt), zone())
            if (next != null) {
                val moved = r.copy(triggerAt = next, lastFiredAt = r.triggerAt)
                put(moved)
                arm(moved)
            } else {
                put(r.copy(status = ReminderStatus.RANG, lastFiredAt = r.triggerAt))
            }
        }
        prefs.edit().putString(KEY_LAST_RANG, id).apply()
    }

    /**
     * Boot / app start / update / time change: arm every open reminder again
     * (the same alarm id replaces the old one — no duplicates). A one-time
     * reminder missed while the phone was off rings once now; a repeating one
     * moves to its next time in the current time zone.
     */
    fun rearmAll() {
        val t = now()
        val z = zone()
        all().filter { it.status == ReminderStatus.ACTIVE }.forEach { r ->
            var current = r
            if (r.recurrence.repeat != Repeat.ONCE && r.time != null) {
                // Follow the phone's time zone: the next local occurrence.
                val next = KaiReminderSchedule.next(r, t - 1, z)
                if (next != null && next != r.triggerAt && (r.triggerAt < t || ZoneId.of(r.zone) != z)) {
                    current = r.copy(triggerAt = next, zone = z.id)
                    put(current)
                }
            }
            arm(current)
            current.snoozedUntil?.takeIf { it > t }?.let { armAt(current.id, it, snooze = true) }
        }
    }

    /** On logout: this account's reminders stop. */
    fun clear() {
        all().forEach { cancelAlarms(it.id) }
        prefs.edit().clear().apply()
        _changes.value = now()
    }

    // ------------------------------------------------------------ alarms

    /** Notifications off / exact alarms not allowed: what the owner needs to know. */
    private fun status(): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return ScheduleResult.FAILED
        return when {
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> ScheduleResult.NOTIFICATIONS_OFF
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms() -> ScheduleResult.APPROXIMATE
            else -> ScheduleResult.EXACT
        }
    }

    private fun arm(r: KaiReminder): ScheduleResult = armAt(r.id, r.triggerAt, snooze = false)

    private fun armAt(id: String, at: Long, snooze: Boolean): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return ScheduleResult.FAILED
        // A missed time rings now (in 2 seconds), never silently dropped.
        val millis = at.coerceAtLeast(now() + 2_000)
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(id, snooze))
            } else {
                // Without the exact-alarm permission Android may delay it a little.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(id, snooze))
            }
            status()
        }.getOrDefault(ScheduleResult.FAILED)
    }

    private fun cancelAlarms(id: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager?.cancel(pending(id, snooze = false))
        manager?.cancel(pending(id, snooze = true))
        NotificationManagerCompat.from(context).cancel(notificationId(id))
    }

    private fun pending(id: String, snooze: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, notificationId(id) + if (snooze) 1 else 0,
        Intent(context, KaiReminderReceiver::class.java).apply {
            action = if (snooze) ACTION_FIRE_SNOOZE else ACTION_FIRE
            putExtra(EXTRA_ID, id)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------ notification

    private fun show(r: KaiReminder) {
        ensureChannel()
        val tamil = Locale.getDefault().language == "ta"
        // Tapping opens Kai Chat on this reminder (Snooze 5 / 10 / 30 / 60, Done, Call).
        val open = PendingIntent.getActivity(
            context, notificationId(r.id) + 2,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_REMINDER, r.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Kai")
            .setContentText(r.notificationMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.notificationMessage))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        // Android shows up to three buttons: the reminder's action, Snooze 10 min, Done.
        when {
            r.action == ReminderAction.CALL && r.person != null -> builder.addAction(0, if (tamil) "${r.person}-க்கு call" else "Call ${r.person}",
                activity(r, 3, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (r.phone ?: "")))))
            r.action == ReminderAction.MESSAGE && r.person != null && r.phone != null -> builder.addAction(0, if (tamil) "WhatsApp" else "WhatsApp ${r.person}",
                activity(r, 3, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + r.phone.filter(Char::isDigit)))))
        }
        builder.addAction(0, if (tamil) "10 நிமிடம் கழிச்சு" else "Snooze 10 min", action(r.id, ACTION_SNOOZE, 4))
        builder.addAction(0, if (tamil) "முடிஞ்சது" else "Done", action(r.id, ACTION_DONE, 5))
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(r.id), builder.build()) }
    }

    private fun activity(r: KaiReminder, offset: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context, notificationId(r.id) + offset, intent.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(id: String, name: String, offset: Int): PendingIntent = PendingIntent.getBroadcast(
        context, notificationId(id) + offset,
        Intent(context, KaiReminderReceiver::class.java).apply { action = name; putExtra(EXTRA_ID, id) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.kai_reminder_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.kai_reminder_channel_description)
                enableVibration(true)
            },
        )
    }

    private fun notificationId(id: String) = NOTIFICATION_BASE + (id.hashCode() and 0xFFFFF) * 8

    // ------------------------------------------------------------ JSON

    private fun write(r: KaiReminder) = JSONObject()
        .put("id", r.id).put("title", r.title).put("task", r.task).put("action", r.action.name)
        .put("person", r.person ?: "").put("contactId", r.contactId ?: "").put("phone", r.phone ?: "")
        .put("triggerAt", r.triggerAt).put("time", r.time?.toString() ?: "").put("zone", r.zone)
        .put("repeat", r.recurrence.repeat.name).put("days", r.recurrence.days.joinToString(",") { it.name })
        .put("dayOfMonth", r.recurrence.dayOfMonth ?: 0)
        .put("status", r.status.name).put("message", r.notificationMessage).put("source", r.sourceText)
        .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
        .put("completedAt", r.completedAt ?: 0).put("cancelledAt", r.cancelledAt ?: 0)
        .put("snoozedUntil", r.snoozedUntil ?: 0).put("lastFiredAt", r.lastFiredAt ?: 0)
        .put("businessId", r.businessId ?: "")

    private fun read(o: JSONObject): KaiReminder {
        fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() }
        fun long(k: String) = o.optLong(k).takeIf { it > 0 }
        return KaiReminder(
            id = o.getString("id"),
            title = o.getString("title"),
            task = o.getString("task"),
            action = ReminderAction.valueOf(o.optString("action", ReminderAction.TASK.name)),
            person = str("person"), contactId = str("contactId"), phone = str("phone"),
            triggerAt = o.getLong("triggerAt"),
            time = str("time")?.let(LocalTime::parse),
            zone = o.optString("zone", ZoneId.systemDefault().id),
            recurrence = Recurrence(
                Repeat.valueOf(o.optString("repeat", Repeat.ONCE.name)),
                str("days")?.split(',')?.map(DayOfWeek::valueOf)?.toSet().orEmpty(),
                o.optInt("dayOfMonth").takeIf { it > 0 },
            ),
            status = ReminderStatus.valueOf(o.optString("status", ReminderStatus.ACTIVE.name)),
            notificationMessage = o.optString("message"),
            sourceText = o.optString("source"),
            createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
            completedAt = long("completedAt"), cancelledAt = long("cancelledAt"),
            snoozedUntil = long("snoozedUntil"), lastFiredAt = long("lastFiredAt"),
            businessId = str("businessId"),
        )
    }

    companion object {
        private const val PREFS = "kai_reminder_engine"
        private const val KEY = "reminders"
        private const val KEY_LAST_RANG = "last_rang"
        private const val CHANNEL_ID = "kai_reminders_v1"
        private const val NOTIFICATION_BASE = 0x4000000
        const val ACTION_FIRE = "com.shopai.app.ACTION_KAI_REMINDER"
        const val ACTION_FIRE_SNOOZE = "com.shopai.app.ACTION_KAI_REMINDER_SNOOZED"
        const val ACTION_DONE = "com.shopai.app.ACTION_KAI_REMINDER_DONE"
        const val ACTION_SNOOZE = "com.shopai.app.ACTION_KAI_REMINDER_SNOOZE"
        const val EXTRA_ID = "kai_reminder_id"
        /** MainActivity: open Kai Chat on this reminder. */
        const val EXTRA_OPEN_REMINDER = "open_kai_reminder"
        const val SNOOZE_MINUTES = 10L
    }
}
