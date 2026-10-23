package com.shopai.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shopai.app.MainActivity
import com.shopai.app.R
import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.tools.KaiUpiNotice
import com.shopai.app.brain.tools.KaiUpiSeen
import com.shopai.app.brain.tools.UpiCredit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kai வசூல் (owner, 23 Oct 2026): money that comes in on GPay / PhonePe / Paytm / BHIM → Kai says it at once.
 *
 * Play's user-data rules: only the payment apps in [KaiUpiNotice.apps] are looked at — any other app's notification is
 * returned from at its package name, unread. What is read stays on the phone (nothing is sent anywhere, nothing kept but the
 * one payment until the owner answers). The owner turns it on after Kai's explanation ([KaiUpiAccess]).
 */
class KaiUpiListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (!KaiUpiNotice.isPaymentApp(n.packageName)) return
        runCatching {
            val note = n.notification ?: return
            // The group's summary repeats its children: read the children only.
            if (note.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val extras = note.extras ?: return
            val credit = KaiUpiNotice.read(
                n.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                n.postTime,
            ) ?: return
            if (!seen.firstTime(credit)) return
            Log.i(TAG, "money in: ${credit.app} ${credit.amount.toPlainString()} (${if (credit.sender != null) "name" else "no name"})")
            KaiUpiAlert.show(applicationContext, credit)
        }.onFailure { Log.w(TAG, "payment notification not read: ${it.message}") }
    }

    companion object {
        private const val TAG = "KaiVasool"
        /** One payment seen twice (an updated notification, a second app) is one payment. */
        private val seen = KaiUpiSeen()
    }
}

/** Is Kai வசூல் allowed on this phone (Android's notification access for OwnerNote)? And where the owner turns it on. */
object KaiUpiAccess {
    fun granted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Android's notification-access page — OwnerNote's own switch where the phone has one (Android 11+). */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, KaiUpiListener::class.java).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** The detail page is missing on some phones: the list of apps with notification access instead. */
    fun listIntent(): Intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Kai's alert for one payment: a heads-up notification that opens Kai Chat on it; and Kai Chat, if it is on screen, at once. */
object KaiUpiAlert {
    private const val CHANNEL = "kai_vasool_v1"
    const val EXTRA_AMOUNT = "kai_upi_amount"
    const val EXTRA_SENDER = "kai_upi_sender"
    const val EXTRA_APP = "kai_upi_app"
    const val EXTRA_AT = "kai_upi_at"

    fun show(context: Context, c: UpiCredit) {
        KaiUpiInbox.arrived(c)
        ensureChannel(context)
        val amount = KaiFormat.rupees(c.amount.toDouble())
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AMOUNT, c.amount.toPlainString())
            putExtra(EXTRA_SENDER, c.sender)
            putExtra(EXTRA_APP, c.app)
            putExtra(EXTRA_AT, c.at)
        }
        val id = notificationId(c)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = c.sender?.let { context.getString(R.string.kai_upi_from, it) } ?: context.getString(R.string.kai_upi_no_name)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.kai_upi_title, amount, c.app))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { Log.w("KaiVasool", "alert not shown: ${it.message}") }
    }

    /** The payment a tapped alert carries (null: not one of Kai's payment alerts). */
    fun creditFrom(intent: Intent?): UpiCredit? {
        val amount = intent?.getStringExtra(EXTRA_AMOUNT)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val app = intent.getStringExtra(EXTRA_APP) ?: return null
        return UpiCredit(amount, intent.getStringExtra(EXTRA_SENDER), app, intent.getLongExtra(EXTRA_AT, 0L))
    }

    fun clearExtras(intent: Intent) {
        listOf(EXTRA_AMOUNT, EXTRA_SENDER, EXTRA_APP, EXTRA_AT).forEach(intent::removeExtra)
    }

    private fun notificationId(c: UpiCredit) = 7_300_000 + ((c.at xor c.amount.hashCode().toLong()).toInt() and 0xFFFF)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.kai_upi_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.kai_upi_channel_description)
            },
        )
    }
}

/**
 * The payment Kai Chat shows next: [pending] when an alert is tapped (the app opens Kai Chat on it), [live] while Kai Chat is
 * on screen (shown at once). Each payment is shown once.
 */
object KaiUpiInbox {
    private val _pending = MutableStateFlow<UpiCredit?>(null)
    val pending: StateFlow<UpiCredit?> = _pending
    private val _live = MutableStateFlow<UpiCredit?>(null)
    val live: StateFlow<UpiCredit?> = _live
    private val shown = LinkedHashSet<String>()
    /** A payment that came in while Kai Chat was closed is shown when it opens only if it is this fresh. */
    private const val LIVE_MS = 10 * 60_000L

    private fun key(c: UpiCredit) = "${c.app}|${c.at}|${c.amount.stripTrailingZeros().toPlainString()}|${c.sender.orEmpty()}"

    fun arrived(c: UpiCredit) { _live.value = c }
    fun open(c: UpiCredit) { _pending.value = c }

    /** The next payment to show (tapped first, then one that came in the last few minutes), once. */
    @Synchronized
    fun take(now: Long = System.currentTimeMillis()): UpiCredit? {
        val tapped = _pending.value
        val c = tapped ?: _live.value?.takeIf { now - it.at in 0..LIVE_MS } ?: run { _live.value = null; return null }
        if (tapped != null) _pending.value = null else _live.value = null
        if (!shown.add(key(c))) return null
        while (shown.size > 50) shown.remove(shown.first())
        return c
    }
}
