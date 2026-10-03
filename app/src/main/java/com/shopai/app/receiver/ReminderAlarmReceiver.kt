package com.shopai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shopai.app.ShopAiApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Daily reminder check (see ReminderAlarms). Fetches the latest reminders,
 * falling back to the saved copy when offline, rings for the ones due
 * today, then arms tomorrow's check.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ShopAiApplication ?: return
        // An exact-time reminder (set from Morning Work): ring it now.
        if (intent.action == com.shopai.app.notifications.ReminderAlarms.ACTION_TIMED) {
            app.container.reminderAlarms.notifyTimed(intent)
            return
        }
        val pending = goAsync()
        app.container.appScope.launch {
            val alarms = app.container.reminderAlarms
            try {
                if (app.container.authRepository.isLoggedIn()) {
                    // A broadcast only gets ~10s, so don't wait long for the network.
                    val fresh = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                        runCatching { app.container.reminderRepository.listReminders() }.getOrNull()
                    }
                    alarms.notifyDueToday(fresh ?: alarms.cached())
                }
                alarms.markCheckedToday()
                alarms.ensureScheduled()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val NETWORK_TIMEOUT_MS = 7_000L
    }
}
