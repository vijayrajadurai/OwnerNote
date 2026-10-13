package com.shopai.app.notifications

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
import com.shopai.app.books.integration.BooksModule
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.tools.BatchFact
import com.shopai.app.brain.tools.KaiExpiry
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The daily expiry alert (owner, 13 Oct 2026): stock that expires within [KaiExpiry.ALERT_DAYS] days, or already has, from the
 * books' batch records — run with the daily reminder check ([ReminderAlarms], 9 AM, re-armed on boot / app start). At most once a
 * day, and only when something is due (no empty notification). Tapping it opens the app.
 */
class ExpiryAlerts(private val context: Context, private val books: BooksModule) {
    private val prefs = context.getSharedPreferences("expiry_alerts", Context.MODE_PRIVATE)

    suspend fun check() {
        val today = LocalDate.now()
        if (prefs.getString(KEY_SHOWN, null) == today.toString()) return
        val s = runCatching { books.session() }.getOrNull() ?: return
        val batches = runCatching {
            s.dao.morningExpiring(s.ctx.businessId, today.plusDays(KaiExpiry.ALERT_DAYS).toEpochDay().toInt(), 100).map {
                BatchFact(it.productName, it.batchNo, LocalDate.ofEpochDay(it.expiryDay.toLong()), BigDecimal.valueOf(it.qtyMilli, 3), it.unit)
            }
        }.getOrNull() ?: return
        val lang = KaiLanguage.forAppLocale().let { if (it == KaiLang.TAMIL) it else KaiLang.TANGLISH }
        val alert = KaiExpiry.alert(batches, today, lang) ?: return
        show(alert.title, alert.body)
        prefs.edit().putString(KEY_SHOWN, today.toString()).apply()
    }

    private fun show(title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.expiry_notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }

    private companion object {
        const val CHANNEL_ID = "expiry_alerts_v1"
        const val NOTIFICATION_ID = 40_001
        const val KEY_SHOWN = "last_shown_day"
    }
}
