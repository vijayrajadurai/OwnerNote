package com.shopai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shopai.app.ShopAiApplication
import com.shopai.app.notifications.ReminderAlarms
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** "Done" button on a reminder notification. */
class ReminderDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(ReminderAlarms.EXTRA_REMINDER_ID) ?: return
        val app = context.applicationContext as? ShopAiApplication ?: return
        app.container.reminderAlarms.dismiss(reminderId)
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                withTimeoutOrNull(8_000L) {
                    runCatching { app.container.reminderRepository.markDone(reminderId) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
