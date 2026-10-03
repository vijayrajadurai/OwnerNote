package com.shopai.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shopai.app.MainActivity
import com.shopai.app.R
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.receiver.ReminderAlarmReceiver
import com.shopai.app.receiver.ReminderDoneReceiver
import com.shopai.app.util.formatInr
import com.shopai.app.util.parseIsoToLocalDate
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Loud "due today" notifications for reminders.
 *
 * One daily alarm at [CHECK_HOUR]:00 fetches the reminders (falling back to
 * the last list the app saw, so it still works offline) and rings once for
 * every reminder due that day, using the alarm sound on the alarm stream.
 * The same alarm is re-armed on boot, on app start, and after each check.
 */
class ReminderAlarms(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Local copy of the reminder list (for offline checks) ----

    fun cache(items: List<ReminderItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind)
                    .put("title", item.title)
                    .put("amount", item.amount ?: JSONObject.NULL)
                    .put("dueDate", item.dueDate)
                    .put("isDone", item.isDone),
            )
        }
        prefs.edit().putString(KEY_CACHE, array.toString()).apply()
    }

    fun cached(): List<ReminderItem> {
        val raw = prefs.getString(KEY_CACHE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                ReminderItem(
                    id = obj.getString("id"),
                    kind = obj.optString("kind"),
                    title = obj.getString("title"),
                    amount = if (obj.isNull("amount")) null else obj.getDouble("amount"),
                    dueDate = obj.getString("dueDate"),
                    isDone = obj.optBoolean("isDone"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun updateCached(transform: (List<ReminderItem>) -> List<ReminderItem>) {
        cache(transform(cached()))
    }

    /** On logout: forget the list and stop ringing for this account. */
    fun clear() {
        prefs.edit().clear().apply()
        context.getSystemService(AlarmManager::class.java)?.cancel(checkPendingIntent())
    }

    // ---- Daily check alarm ----

    /**
     * Arms the next daily check. If today's check was missed (phone off,
     * app force-stopped, first install after 9 AM) it runs in a few seconds.
     */
    fun ensureScheduled() {
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        val todayCheck = today.atTime(CHECK_HOUR, 0)
        val checkedToday = prefs.getString(KEY_LAST_CHECK, null) == today.toString()
        val trigger = when {
            now.isBefore(todayCheck) -> todayCheck
            !checkedToday -> now.plusSeconds(10)
            else -> today.plusDays(1).atTime(CHECK_HOUR, 0)
        }
        val triggerMillis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, checkPendingIntent())
            } else {
                // Without the exact-alarm permission Android may delay this a little.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, checkPendingIntent())
            }
        }
    }

    fun markCheckedToday() {
        prefs.edit().putString(KEY_LAST_CHECK, LocalDate.now().toString()).apply()
    }

    // ---- Notifications ----

    /** Rings once per reminder per day for every open reminder due today. */
    fun notifyDueToday(items: List<ReminderItem>) {
        val today = LocalDate.now()
        val notifiedKey = KEY_NOTIFIED_PREFIX + today
        val alreadyNotified = prefs.getStringSet(notifiedKey, emptySet()).orEmpty()
        val due = items.filter { !it.isDone && parseIsoToLocalDate(it.dueDate) == today && it.id !in alreadyNotified }
        if (due.isEmpty()) return

        ensureChannel()
        due.forEach { show(it) }

        // Keep only today's "already rang" list.
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_NOTIFIED_PREFIX) && it != notifiedKey }.forEach { editor.remove(it) }
        editor.putStringSet(notifiedKey, alreadyNotified + due.map { it.id }).apply()
    }

    /**
     * A reminder with an exact time (Morning Work's "remind me after 30
     * minutes"): rings at that time, through the same notification as the
     * daily check. The daily check still covers it if the phone restarts.
     */
    fun ringAt(item: ReminderItem, atMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            notificationIdFor(item.id),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_TIMED
                putExtra(EXTRA_REMINDER_ID, item.id)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_DUE, item.dueDate)
                item.amount?.let { putExtra(EXTRA_AMOUNT, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            }
        }
    }

    /** The exact-time alarm went off: ring unless the reminder was already marked done. */
    fun notifyTimed(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        if (cached().any { it.id == id && it.isDone }) return
        val item = ReminderItem(
            id = id,
            kind = "CUSTOM",
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            amount = if (intent.hasExtra(EXTRA_AMOUNT)) intent.getDoubleExtra(EXTRA_AMOUNT, 0.0) else null,
            dueDate = intent.getStringExtra(EXTRA_DUE).orEmpty(),
            isDone = false,
        )
        ensureChannel()
        show(item)
    }

    fun dismiss(reminderId: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(reminderId))
    }

    private fun show(item: ReminderItem) {
        val body = buildString {
            append(item.title)
            item.amount?.let { append(" • ").append(formatInr(it)) }
        }
        val openApp = PendingIntent.getActivity(
            context,
            notificationIdFor(item.id),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markDone = PendingIntent.getBroadcast(
            context,
            notificationIdFor(item.id),
            Intent(context, ReminderDoneReceiver::class.java).apply {
                action = ACTION_MARK_DONE
                putExtra(EXTRA_REMINDER_ID, item.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Pre-Android 8 devices take sound/vibration from the notification itself.
            .setSound(alarmSoundUri(), AudioManager.STREAM_ALARM)
            .setVibrate(VIBRATION_PATTERN)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.reminder_done), markDone)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationIdFor(item.id), notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            // USAGE_ALARM plays at the alarm volume, which is usually loudest.
            setSound(
                alarmSoundUri(),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            enableLights(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun alarmSoundUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun checkPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE_CHECK,
        Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_DAILY_CHECK },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationIdFor(reminderId: String): Int = NOTIFICATION_ID_BASE + (reminderId.hashCode() and 0xFFFFF)

    companion object {
        const val CHECK_HOUR = 9
        // Channel sound can't change after creation, so a new sound needs a new id.
        private const val CHANNEL_ID = "reminder_alarm_v1"
        private const val PREFS_NAME = "reminder_alarms"
        private const val KEY_CACHE = "cache"
        private const val KEY_LAST_CHECK = "last_check_date"
        private const val KEY_NOTIFIED_PREFIX = "notified_"
        private const val REQUEST_CODE_CHECK = 9201
        private const val NOTIFICATION_ID_BASE = 0x3000000
        const val ACTION_DAILY_CHECK = "com.shopai.app.ACTION_REMINDER_DAILY_CHECK"
        const val ACTION_MARK_DONE = "com.shopai.app.ACTION_REMINDER_MARK_DONE"
        const val ACTION_TIMED = "com.shopai.app.ACTION_REMINDER_TIMED"
        private const val EXTRA_TITLE = "reminder_title"
        private const val EXTRA_DUE = "reminder_due"
        private const val EXTRA_AMOUNT = "reminder_amount"
        const val EXTRA_REMINDER_ID = "reminder_id"
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 400, 800, 400, 800)
    }
}
