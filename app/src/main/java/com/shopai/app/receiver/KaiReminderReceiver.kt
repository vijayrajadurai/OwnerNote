package com.shopai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shopai.app.notifications.KaiReminderAlarms

/** A Kai reminder rang (show it, re-arm if it repeats), or Done / Snooze was tapped on it — all through the one store. */
class KaiReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(KaiReminderAlarms.EXTRA_ID) ?: return
        val reminders = KaiReminderAlarms(context.applicationContext)
        when (intent.action) {
            KaiReminderAlarms.ACTION_FIRE -> reminders.fired(id)
            KaiReminderAlarms.ACTION_DONE -> reminders.complete(id)
            KaiReminderAlarms.ACTION_SNOOZE -> reminders.snooze(id)
        }
    }
}
