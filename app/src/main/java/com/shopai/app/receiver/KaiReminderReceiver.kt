package com.shopai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shopai.app.ShopAiApplication
import com.shopai.app.notifications.KaiReminderEngine
import kotlinx.coroutines.launch

/** A Kai reminder rang (or its snooze), or Done / Snooze was tapped on it — all through the one reminder engine. */
class KaiReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = (context.applicationContext as? ShopAiApplication)?.container?.kaiReminders ?: KaiReminderEngine(context.applicationContext)
        when (intent.action) {
            // The phone's clock, time zone or the app itself changed: alarms are armed again.
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                engine.rearmAll()
                // The morning notification follows the new clock / zone (and survives an app update).
                (context.applicationContext as? ShopAiApplication)?.container?.let { c -> c.appScope.launch { c.restoreMorningNotification() } }
            }
            else -> {
                val id = intent.getStringExtra(KaiReminderEngine.EXTRA_ID) ?: return
                when (intent.action) {
                    KaiReminderEngine.ACTION_FIRE -> engine.fired(id, snooze = false)
                    KaiReminderEngine.ACTION_FIRE_SNOOZE -> engine.fired(id, snooze = true)
                    KaiReminderEngine.ACTION_DONE -> engine.complete(id)
                    KaiReminderEngine.ACTION_SNOOZE -> engine.snooze(id, KaiReminderEngine.SNOOZE_MINUTES)
                }
            }
        }
    }
}
