package com.shopai.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shopai.app.MainActivity
import com.shopai.app.R
import com.shopai.app.brain.morning.MorningAlarmPort
import com.shopai.app.brain.morning.MorningNotificationSetting
import com.shopai.app.brain.morning.MorningOwner
import com.shopai.app.brain.morning.MorningScheduleStore
import com.shopai.app.brain.morning.MorningScheduler
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.receiver.MorningWorkReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's ONE morning alarm ([MorningScheduler.ALARM_ID]): arming again
 * replaces it (never two), and it carries the owner + business it is for.
 * Exact when Android allows it, otherwise inexact — and the result says so
 * (the settings screen shows it: never a silent failure).
 */
class MorningWorkAlarms(private val context: Context) : MorningAlarmPort {
    private fun pending(owner: MorningOwner?): PendingIntent = PendingIntent.getBroadcast(
        context,
        MorningScheduler.ALARM_ID,
        Intent(context, MorningWorkReceiver::class.java).apply {
            action = ACTION_FIRE
            owner?.let { putExtra(EXTRA_BUSINESS, it.businessId); putExtra(EXTRA_OWNER, it.ownerId) }
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun arm(owner: MorningOwner, atMillis: Long): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return ScheduleResult.FAILED
        return runCatching {
            manager.cancel(pending(null))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(owner))
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(owner))
            }
            status()
        }.getOrDefault(ScheduleResult.FAILED)
    }

    override fun cancel() {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(null))
        NotificationManagerCompat.from(context).cancel(MorningScheduler.ALARM_ID)
    }

    fun status(): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return ScheduleResult.FAILED
        return when {
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> ScheduleResult.NOTIFICATIONS_OFF
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms() -> ScheduleResult.APPROXIMATE
            else -> ScheduleResult.EXACT
        }
    }

    /** "Good morning Owner ☀️" — tapping it opens Morning Work. */
    fun show(title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.morning_notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            MorningScheduler.ALARM_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_MORNING_WORK, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(MorningScheduler.ALARM_ID, n) }
    }

    companion object {
        const val ACTION_FIRE = "com.shopai.app.ACTION_MORNING_WORK"
        const val EXTRA_BUSINESS = "morning_business_id"
        const val EXTRA_OWNER = "morning_owner_id"
        const val EXTRA_OPEN_MORNING_WORK = "open_morning_work"
        private const val CHANNEL_ID = "morning_work_v1"
    }
}

/** The owner's setting, on the phone, under that owner + business only. */
class PrefsMorningScheduleStore(context: Context) : MorningScheduleStore {
    private val prefs = context.applicationContext.getSharedPreferences("morning_notification", Context.MODE_PRIVATE)

    override fun setting(owner: MorningOwner): MorningNotificationSetting {
        val k = owner.key
        if (!prefs.contains("$k:enabled")) return MorningNotificationSetting()
        return runCatching {
            MorningNotificationSetting(prefs.getBoolean("$k:enabled", false), prefs.getInt("$k:hour", 8), prefs.getInt("$k:minute", 0))
        }.getOrDefault(MorningNotificationSetting())
    }

    override fun save(owner: MorningOwner, setting: MorningNotificationSetting) {
        val k = owner.key
        prefs.edit().putBoolean("$k:enabled", setting.enabled).putInt("$k:hour", setting.hour).putInt("$k:minute", setting.minute).apply()
    }

    override fun lastShownDay(owner: MorningOwner): Long? = owner.key.let { k -> if (prefs.contains("$k:shown")) prefs.getLong("$k:shown", 0) else null }

    override fun markShown(owner: MorningOwner, day: Long) {
        prefs.edit().putLong("${owner.key}:shown", day).apply()
    }
}

/** The morning notification was tapped: the app opens Morning Work (once past login). */
object MorningWorkInbox {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending

    fun open() { _pending.value = true }

    fun take(): Boolean = _pending.value.also { _pending.value = false }
}
