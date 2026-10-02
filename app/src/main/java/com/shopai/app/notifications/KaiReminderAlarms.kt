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
import com.shopai.app.brain.tools.Repeat
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.receiver.KaiReminderReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Kai's personal / task reminders ("Kumar-ku 10 minutes kalichu call panna
 * remind pannu", "daily 10 maniku saavi eduthuka"). Kept on the phone,
 * separate from the books and from the backend payment reminders. Each one
 * has its own alarm; repeating ones are re-armed after they ring, and all
 * are re-armed on boot and app start.
 */
class KaiReminderAlarms(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- store ----

    @Synchronized
    fun all(): List<KaiReminder> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                KaiReminder(
                    id = o.getString("id"),
                    task = o.getString("task"),
                    callName = o.optString("callName").takeIf { it.isNotBlank() },
                    phone = o.optString("phone").takeIf { it.isNotBlank() },
                    at = LocalDateTime.parse(o.getString("at")),
                    repeat = Repeat.valueOf(o.optString("repeat", Repeat.ONCE.name)),
                    weekday = o.optString("weekday").takeIf { it.isNotBlank() }?.let(DayOfWeek::valueOf),
                    said = o.optString("said"),
                    createdAt = o.optLong("createdAt"),
                    rang = o.optBoolean("rang"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(list: List<KaiReminder>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(
                JSONObject().put("id", r.id).put("task", r.task).put("callName", r.callName ?: "").put("phone", r.phone ?: "")
                    .put("at", r.at.toString()).put("repeat", r.repeat.name).put("weekday", r.weekday?.name ?: "")
                    .put("said", r.said).put("createdAt", r.createdAt).put("rang", r.rang),
            )
        }
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    fun find(id: String): KaiReminder? = all().firstOrNull { it.id == id }

    /** Reminders still to ring (what Kai lists). One-time ones that rang a day ago and were never closed are dropped. */
    fun upcoming(): List<KaiReminder> {
        val old = all().filter { it.rang && it.at.isBefore(LocalDateTime.now().minusDays(1)) }
        if (old.isNotEmpty()) save(all().filter { r -> old.none { it.id == r.id } })
        return all().filter { !it.rang }
    }

    /** Update: a new time and / or task for an existing reminder (re-armed). */
    fun update(id: String, at: LocalDateTime? = null, task: String? = null): ScheduleResult {
        val r = find(id) ?: return ScheduleResult.FAILED
        return schedule(r.copy(at = at ?: r.at, task = task ?: r.task, rang = false))
    }

    /** Done (from the notification): a one-time reminder is finished; a repeating one waits for its next time. */
    fun complete(id: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        val r = find(id) ?: return
        if (r.repeat == Repeat.ONCE) save(all().filter { it.id != id })
    }

    /**
     * Snooze: ring again in [minutes]. A one-time reminder moves; a repeating
     * one gets a one-time copy so its daily / weekly time does not change.
     */
    fun snooze(id: String, minutes: Long = SNOOZE_MINUTES): ScheduleResult {
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        val r = find(id) ?: return ScheduleResult.FAILED
        val at = LocalDateTime.now().plusMinutes(minutes).withSecond(0).withNano(0)
        return if (r.repeat == Repeat.ONCE) schedule(r.copy(at = at, rang = false))
        else schedule(r.copy(id = r.id + "-s", at = at, repeat = Repeat.ONCE, weekday = null, rang = false))
    }

    /** Saves and arms one reminder. */
    fun schedule(r: KaiReminder): ScheduleResult {
        save(all().filter { it.id != r.id } + r)
        return arm(r)
    }

    fun cancel(id: String): Boolean {
        val list = all()
        if (list.none { it.id == id }) return false
        // A snoozed copy of a repeating reminder goes with it.
        val ids = setOf(id, "$id-s")
        save(list.filter { it.id !in ids })
        ids.forEach {
            context.getSystemService(AlarmManager::class.java)?.cancel(pending(it))
            NotificationManagerCompat.from(context).cancel(notificationId(it))
        }
        return true
    }

    /** Boot / app start: arm every reminder again; a missed one-time reminder rings now. */
    fun rearmAll() {
        val now = LocalDateTime.now()
        all().filter { !it.rang }.forEach { r ->
            if (r.repeat != Repeat.ONCE && !r.at.isAfter(now)) {
                val next = r.nextAfter(now) ?: return@forEach
                schedule(r.copy(at = next))
            } else arm(r)
        }
    }

    /** It rang: notify, then re-arm a repeating one or forget a one-time one. */
    fun fired(id: String) {
        val r = find(id)?.takeIf { !it.rang } ?: return
        show(r)
        val next = r.nextAfter(LocalDateTime.now())
        // A one-time reminder stays (marked rang) so Snooze can bring it back; Done removes it.
        if (next != null) schedule(r.copy(at = next)) else save(all().map { if (it.id == id) it.copy(rang = true) else it })
    }

    /** On logout: this account's reminders stop. */
    fun clear() {
        all().forEach { context.getSystemService(AlarmManager::class.java)?.cancel(pending(it.id)) }
        prefs.edit().clear().apply()
    }

    // ---- alarms ----

    private fun arm(r: KaiReminder): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return ScheduleResult.FAILED
        val millis = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 2_000)
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(r.id))
                ScheduleResult.EXACT
            } else {
                // Without the exact-alarm permission Android may delay it a little.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(r.id))
                ScheduleResult.APPROXIMATE
            }
        }.getOrDefault(ScheduleResult.FAILED)
    }

    private fun pending(id: String): PendingIntent = PendingIntent.getBroadcast(
        context, notificationId(id),
        Intent(context, KaiReminderReceiver::class.java).apply { action = ACTION_FIRE; putExtra(EXTRA_ID, id) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---- notification ----

    private fun show(r: KaiReminder) {
        ensureChannel()
        val tamil = Locale.getDefault().language == "ta"
        // A reminder TO call — Kai never says a call was made.
        val body = when {
            r.callName != null && tamil -> "ஓனர், ${r.callName}-க்கு call பண்ண சொன்னீங்க."
            r.callName != null -> "Owner, ${r.callName}-ku call panna sonneenga."
            tamil -> "ஓனர், ${r.task} — மறக்காதீங்க."
            else -> "Owner, ${r.task} — marakkadheenga."
        }
        val open = PendingIntent.getActivity(
            context, notificationId(r.id),
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Kai")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (r.callName != null) {
            // Opens the dialer with the number; the owner presses call.
            val dial = PendingIntent.getActivity(
                context, notificationId(r.id) + 1,
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (r.phone ?: ""))).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, if (tamil) "${r.callName}-க்கு call" else "Call ${r.callName}", dial)
        }
        builder.addAction(0, if (tamil) "முடிஞ்சது" else "Done", action(r.id, ACTION_DONE, 2))
        builder.addAction(0, if (tamil) "10 நிமிடம் கழிச்சு" else "Snooze 10 min", action(r.id, ACTION_SNOOZE, 3))
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(r.id), builder.build()) }
    }

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

    private fun notificationId(id: String) = NOTIFICATION_BASE + (id.hashCode() and 0xFFFFF) * 4

    companion object {
        private const val PREFS = "kai_reminders"
        private const val KEY = "list"
        private const val CHANNEL_ID = "kai_reminders_v1"
        private const val NOTIFICATION_BASE = 0x4000000
        const val ACTION_FIRE = "com.shopai.app.ACTION_KAI_REMINDER"
        const val ACTION_DONE = "com.shopai.app.ACTION_KAI_REMINDER_DONE"
        const val ACTION_SNOOZE = "com.shopai.app.ACTION_KAI_REMINDER_SNOOZE"
        const val SNOOZE_MINUTES = 10L
        const val EXTRA_ID = "kai_reminder_id"
    }
}
