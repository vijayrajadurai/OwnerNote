package com.shopai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shopai.app.ShopAiApplication
import kotlinx.coroutines.launch

/** Re-schedules every Daily Voice Check-in alarm and the daily reminder
 * check after a reboot — exact alarms do not survive a device restart. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? ShopAiApplication ?: return
        app.container.reminderAlarms.ensureScheduled()
        app.container.kaiReminders.rearmAll()
        app.container.appScope.launch {
            val repository = app.container.voiceCheckinRepository
            repository.applySchedule(repository.getPrefs())
        }
        app.container.appScope.launch { app.container.restoreMorningNotification() }
    }
}
