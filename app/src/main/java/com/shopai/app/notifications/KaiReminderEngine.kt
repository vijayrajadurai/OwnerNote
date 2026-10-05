package com.shopai.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shopai.app.R
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiReminderFlow
import com.shopai.app.brain.tools.KaiReminderSchedule
import com.shopai.app.brain.tools.KaiUrgentPresentation
import com.shopai.app.brain.tools.KaiUrgentWords
import com.shopai.app.brain.tools.UrgentPresentation
import com.shopai.app.brain.tools.Recurrence
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.brain.tools.Repeat
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.receiver.KaiReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * OwnerNote's reminder engine for Kai — the one place reminders are created,
 * updated, cancelled, completed and snoozed. Stored on the phone (works
 * offline), each reminder has its own exact alarm; the timing is plain code,
 * never an AI. Repeating reminders are re-armed after they ring; everything is
 * re-armed on boot, app start, update and time-zone change.
 */
class KaiReminderEngine(
    private val context: Context,
    /** The phone's clock; tests on a device pass their own so a 5-minute retry needs no real 5 minutes. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0L)

    /** Ticks when reminders change (screens refresh on it). */
    val changes: StateFlow<Long> = _changes

    private fun now() = clock()
    private fun zone(): ZoneId = ZoneId.systemDefault()

    // ------------------------------------------------------------ store

    @Synchronized
    fun all(): List<KaiReminder> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> runCatching { read(a.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(list: List<KaiReminder>) {
        // Finished reminders are kept 30 days for history.
        val cutoff = now() - 30L * 86_400_000
        val kept = list.filter { it.open || (it.completedAt ?: it.cancelledAt ?: it.updatedAt) > cutoff }
        val a = JSONArray()
        kept.forEach { a.put(write(it)) }
        prefs.edit().putString(KEY, a.toString()).apply()
        _changes.value = maxOf(now(), _changes.value + 1)
    }

    private fun put(r: KaiReminder) = save(all().filter { it.id != r.id } + r)

    fun find(id: String): KaiReminder? = all().firstOrNull { it.id == id }

    /** Open reminders, soonest first. */
    fun open(): List<KaiReminder> = all().filter { it.open }.sortedBy { it.snoozedUntil ?: it.triggerAt }

    // ------------------------------------------------------------ operations

    /** Creates a reminder; the same reminder said twice is not created again. */
    fun create(r: KaiReminder): ReminderSaved {
        all().firstOrNull { KaiReminderSchedule.sameAs(it, r) }?.let { return ReminderSaved(it, duplicate = true, result = status()) }
        put(r)
        return ReminderSaved(r, duplicate = false, result = arm(r))
    }

    /** Saves changes to an existing reminder (same id) and arms it again. */
    fun update(r: KaiReminder): ReminderSaved {
        val updated = r.copy(status = ReminderStatus.ACTIVE, updatedAt = now(), snoozedUntil = null, lastFiredAt = null, attemptCount = 0, lastTriggeredAt = null)
        cancelAlarms(r.id)
        put(updated)
        return ReminderSaved(updated, duplicate = false, result = arm(updated))
    }

    fun cancel(id: String): Boolean {
        val r = find(id)?.let { KaiReminderFlow.cancel(it, now()) } ?: return false
        cancelAlarms(id)
        put(r)
        return true
    }

    /** Done: a one-time reminder is finished; a repeating one waits for its next time. */
    fun complete(id: String): Boolean {
        val r = find(id) ?: return false
        val done = KaiReminderFlow.complete(r, now()) ?: return false
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        // The retry / snooze alarm goes with it; a one-time reminder loses its own alarm too.
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(id, snooze = true))
        if (done.status == ReminderStatus.COMPLETED) cancelAlarms(id)
        put(done)
        if (prefs.getString(KEY_LAST_RANG, null) == id) prefs.edit().remove(KEY_LAST_RANG).apply()
        Log.i(TAG, "reminder $id done (${done.status})")
        return true
    }

    /** Rings again in exactly [minutes]; a repeating reminder keeps its own schedule. */
    fun snooze(id: String, minutes: Long): KaiReminder? {
        val r = find(id) ?: return null
        // Finished, or the last of its attempts: nothing to snooze (never a 6th ring).
        val updated = KaiReminderFlow.snooze(r, now(), minutes) ?: return null
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        put(updated)
        // The same snooze alarm slot as the retry: the next ring replaces it, never a second alarm.
        armAt(id, updated.snoozedUntil!!, snooze = true)
        Log.i(TAG, "reminder $id snoozed ${minutes}m (attempt ${updated.attemptCount}/${updated.maxAttempts})")
        return updated
    }

    /** The reminder that rang last and is still open (for "Done", "innum 10 minutes"). */
    fun lastRang(): KaiReminder? = prefs.getString(KEY_LAST_RANG, null)?.let(::find)?.takeIf { it.open }

    /** The alarm went off. Guards make sure an occurrence never rings twice. */
    /**
     * The alarm went off (its own time, a 5-minute retry or a snooze). Guards make sure an
     * occurrence never rings twice and a finished / cancelled / exhausted reminder never rings.
     * Every ring is one attempt of Kai Urgent Action Mode; the next retry is armed in the same
     * alarm slot (never a duplicate) until Done, Cancel or the last attempt.
     */
    @Synchronized
    fun fired(id: String, snooze: Boolean) {
        val r = find(id) ?: return
        if (!KaiReminderFlow.canRing(r)) return
        val t = now()
        val rung: KaiReminder
        if (snooze) {
            val until = r.snoozedUntil ?: return
            if (t < until - 60_000) { armAt(id, until, snooze = true); return }
            rung = KaiReminderFlow.trigger(r, t, newOccurrence = false)
        } else {
            if (r.status != ReminderStatus.ACTIVE) return
            // Too early (clock change): wait for the real time.
            if (t < r.triggerAt - 60_000) { arm(r); return }
            if (r.lastFiredAt != null && r.lastFiredAt >= r.triggerAt) return
            val first = KaiReminderFlow.trigger(r, t, newOccurrence = true)
            // A repeating reminder moves to its next time as well.
            rung = KaiReminderSchedule.next(r, maxOf(t, r.triggerAt), zone())?.let { next -> first.copy(triggerAt = next).also { arm(it) } } ?: first
        }
        put(rung)
        rung.snoozedUntil?.let { armAt(id, it, snooze = true) }
        prefs.edit().putString(KEY_LAST_RANG, id).apply()
        present(rung)
    }

    /**
     * Boot / app start / update / time change: arm every open reminder again
     * (the same alarm id replaces the old one — no duplicates). A one-time
     * reminder missed while the phone was off rings once now; a repeating one
     * moves to its next time in the current time zone.
     */
    fun rearmAll() {
        val t = now()
        val z = zone()
        // A retry / snooze that was due while the phone was off rings now (armAt never drops a missed time).
        all().filter { (it.status == ReminderStatus.RANG || it.status == ReminderStatus.SNOOZED) && it.snoozedUntil != null }
            .forEach { armAt(it.id, it.snoozedUntil!!, snooze = true) }
        all().filter { it.status == ReminderStatus.ACTIVE }.forEach { r ->
            var current = r
            if (r.recurrence.repeat != Repeat.ONCE && r.time != null) {
                // Follow the phone's time zone: the next local occurrence.
                val next = KaiReminderSchedule.next(r, t - 1, z)
                if (next != null && next != r.triggerAt && (r.triggerAt < t || ZoneId.of(r.zone) != z)) {
                    current = r.copy(triggerAt = next, zone = z.id)
                    put(current)
                }
            }
            arm(current)
            current.snoozedUntil?.let { armAt(current.id, it, snooze = true) }
        }
    }

    /** On logout: this account's reminders stop. */
    fun clear() {
        all().forEach { cancelAlarms(it.id) }
        prefs.edit().clear().apply()
        _changes.value = maxOf(now(), _changes.value + 1)
    }

    // ------------------------------------------------------------ alarms

    /** Notifications off / exact alarms not allowed: what the owner needs to know. */
    private fun status(): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return ScheduleResult.FAILED
        return when {
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> ScheduleResult.NOTIFICATIONS_OFF
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms() -> ScheduleResult.APPROXIMATE
            else -> ScheduleResult.EXACT
        }
    }

    private fun arm(r: KaiReminder): ScheduleResult = armAt(r.id, r.triggerAt, snooze = false)

    private fun armAt(id: String, at: Long, snooze: Boolean): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return ScheduleResult.FAILED
        // A missed time rings now (in 2 seconds), never silently dropped.
        val millis = at.coerceAtLeast(now() + 2_000)
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(id, snooze))
            } else {
                // Without the exact-alarm permission Android may delay it a little.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(id, snooze))
            }
            status()
        }.getOrDefault(ScheduleResult.FAILED)
    }

    private fun cancelAlarms(id: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager?.cancel(pending(id, snooze = false))
        manager?.cancel(pending(id, snooze = true))
        NotificationManagerCompat.from(context).cancel(notificationId(id))
    }

    private fun pending(id: String, snooze: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, notificationId(id) + if (snooze) 1 else 0,
        Intent(context, KaiReminderReceiver::class.java).apply {
            action = if (snooze) ACTION_FIRE_SNOOZE else ACTION_FIRE
            putExtra(EXTRA_ID, id)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------ notification

    // ------------------------------------------------------------ Kai Urgent Action Mode

    /**
     * Can Kai Urgent Action Mode appear over the lock screen? Android 14+ asks the owner
     * ("Allow full-screen alerts"); before 14 the manifest permission is enough. Never assumed.
     */
    fun fullScreenAllowed(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 34) runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false) else true
    }

    /** How the last ring reached the owner (diagnostics and the Pixel 8 test). */
    fun lastPresentation(): String? = prefs.getString(KEY_LAST_PRESENTATION, null)

    /** Kai speaks an attempt once — however often its screen is opened or recreated (survives a process restart). */
    @Synchronized
    fun claimSpeech(r: KaiReminder): Boolean {
        val key = KaiReminderFlow.speechKey(r)
        if (prefs.getString(KEY_SPOKEN, null) == key) return false
        prefs.edit().putString(KEY_SPOKEN, key).commit()
        return true
    }

    fun wasSpoken(r: KaiReminder): Boolean = prefs.getString(KEY_SPOKEN, null) == KaiReminderFlow.speechKey(r)

    private fun appInForeground(): Boolean = runCatching {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * One ring: the urgent notification (full-screen when Android allows it, else the strongest
     * notification) and, when the owner is using the app right now, Kai Urgent Action Mode directly.
     * The decision and Android's full-screen answer are logged every time.
     *  - FULL_SCREEN_INTENT: Android opens Kai over the lock screen; the banner is removed once Kai is on screen.
     *  - DIRECT_ACTIVITY (app open, unlocked): Kai opens directly; the notification is posted silently (no banner over Kai).
     *  - NOTIFICATION_ONLY (full-screen not allowed): the full alert notification stays — it IS the reminder.
     */
    private fun present(r: KaiReminder) {
        ensureChannel()
        val canFullScreen = fullScreenAllowed()
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val interactive = power?.isInteractive ?: true
        val locked = keyguard?.isKeyguardLocked ?: false
        val how = KaiUrgentPresentation.decide(canFullScreen, interactive, locked, appInForeground())
        Log.i(TAG, "ring ${r.id} attempt ${r.attemptCount}/${r.maxAttempts} canUseFullScreenIntent=$canFullScreen " +
            "(${KaiUrgentPresentation.reportLabel(canFullScreen)}) sdk=${Build.VERSION.SDK_INT} interactive=$interactive locked=$locked → $how")
        prefs.edit()
            .putString(KEY_LAST_PRESENTATION, "${r.id}|${r.attemptCount}|$how|${KaiUrgentPresentation.reportLabel(canFullScreen)}|${now()}")
            .putString(KEY_PRESENTATION_OF + r.id, how.name)
            .apply()
        post(r, fullScreen = canFullScreen && KaiUrgentPresentation.useFullScreenIntent(how), silent = KaiUrgentPresentation.silentNotification(how))
        if (how == UrgentPresentation.DIRECT_ACTIVITY) {
            runCatching { context.startActivity(urgentIntent(context, r.id)) }.onFailure { Log.w(TAG, "urgent screen not opened: ${it.message}") }
        }
    }

    /** The one reminder notification (same id for every ring of a reminder — never a second one). */
    private fun post(r: KaiReminder, fullScreen: Boolean, silent: Boolean) {
        val words = KaiUrgentWords.text(r, r.lang)
        val open = PendingIntent.getActivity(
            context, notificationId(r.id) + 2, urgentIntent(context, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, URGENT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Kai · ${words.header} · ${words.attemptLine}")
            .setContentText(words.speech)
            .setStyle(NotificationCompat.BigTextStyle().bigText(words.speech))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setContentIntent(open)
        if (silent) builder.setSilent(true) else builder.setDefaults(NotificationCompat.DEFAULT_ALL).setOnlyAlertOnce(false)
        if (fullScreen) builder.setFullScreenIntent(open, true)
        // CALL NOW (the existing dialer — Kai never calls by itself) · DONE · SNOOZE 5 MIN (while attempts remain).
        if (r.action == ReminderAction.CALL && r.person != null) {
            builder.addAction(0, words.callLabel, activity(r, 3, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (r.phone ?: "")))))
        } else if (r.action == ReminderAction.MESSAGE && r.person != null && r.phone != null) {
            builder.addAction(0, "WhatsApp", activity(r, 3, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + r.phone.filter(Char::isDigit)))))
        }
        builder.addAction(0, words.doneLabel, action(r.id, ACTION_DONE, 5))
        if (KaiReminderFlow.canSnooze(r)) builder.addAction(0, words.snoozeLabel, action(r.id, ACTION_SNOOZE, 4))
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(r.id), builder.build()) }
            .onFailure { Log.w(TAG, "notification not shown: ${it.message}") }
    }

    /** How this reminder's last ring was presented (null: it hasn't rung). */
    fun presentationOf(id: String): UrgentPresentation? =
        prefs.getString(KEY_PRESENTATION_OF + id, null)?.let { runCatching { UrgentPresentation.valueOf(it) }.getOrNull() }

    /**
     * Kai Urgent Action Mode is visible: the same ring's banner on top of it is a duplicate — removed.
     * The fallback (full-screen not allowed) keeps its notification. Alarms, retry and state are untouched.
     */
    fun urgentScreenShown(id: String): Boolean {
        val how = presentationOf(id) ?: return false
        if (!KaiUrgentPresentation.dismissNotificationWhenShown(how)) return false
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        Log.i(TAG, "urgent screen visible for $id → its notification removed (was $how)")
        return true
    }

    /**
     * The owner left Kai's screen without Done / Snooze (back, home, the dialer): the reminder is still
     * waiting, so it goes back to the shade quietly (no sound, no full-screen) until Done or the next retry.
     */
    fun urgentScreenLeft(id: String) {
        val r = find(id)?.takeIf { it.status == ReminderStatus.RANG || (it.status == ReminderStatus.ACTIVE && it.snoozedUntil != null && it.attemptCount > 0) } ?: return
        ensureChannel()
        post(r, fullScreen = false, silent = true)
        Log.i(TAG, "urgent screen left for $id → quiet notification kept")
    }

    private fun activity(r: KaiReminder, offset: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context, notificationId(r.id) + offset, intent.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(id: String, name: String, offset: Int): PendingIntent = PendingIntent.getBroadcast(
        context, notificationId(id) + offset,
        Intent(context, KaiReminderReceiver::class.java).apply { action = name; putExtra(EXTRA_ID, id) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(URGENT_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(URGENT_CHANNEL_ID, context.getString(R.string.kai_reminder_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.kai_reminder_channel_description)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
            },
        )
    }

    private fun notificationId(id: String) = NOTIFICATION_BASE + (id.hashCode() and 0xFFFFF) * 8

    // ------------------------------------------------------------ JSON

    private fun write(r: KaiReminder) = JSONObject()
        .put("id", r.id).put("title", r.title).put("task", r.task).put("action", r.action.name)
        .put("person", r.person ?: "").put("contactId", r.contactId ?: "").put("phone", r.phone ?: "")
        .put("triggerAt", r.triggerAt).put("time", r.time?.toString() ?: "").put("zone", r.zone)
        .put("repeat", r.recurrence.repeat.name).put("days", r.recurrence.days.joinToString(",") { it.name })
        .put("dayOfMonth", r.recurrence.dayOfMonth ?: 0)
        .put("status", r.status.name).put("message", r.notificationMessage).put("source", r.sourceText)
        .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
        .put("completedAt", r.completedAt ?: 0).put("cancelledAt", r.cancelledAt ?: 0)
        .put("snoozedUntil", r.snoozedUntil ?: 0).put("lastFiredAt", r.lastFiredAt ?: 0)
        .put("businessId", r.businessId ?: "").put("ownerId", r.ownerId ?: "")
        .put("amount", r.amount?.toPlainString() ?: "")
        .put("attemptCount", r.attemptCount).put("maxAttempts", r.maxAttempts).put("snoozeCount", r.snoozeCount)
        .put("lastTriggeredAt", r.lastTriggeredAt ?: 0).put("lang", r.lang.name)

    private fun read(o: JSONObject): KaiReminder {
        fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() }
        fun long(k: String) = o.optLong(k).takeIf { it > 0 }
        return KaiReminder(
            id = o.getString("id"),
            title = o.getString("title"),
            task = o.getString("task"),
            action = runCatching { ReminderAction.valueOf(o.optString("action", ReminderAction.TASK.name)) }.getOrDefault(ReminderAction.TASK),
            person = str("person"), contactId = str("contactId"), phone = str("phone"),
            triggerAt = o.getLong("triggerAt"),
            time = str("time")?.let(LocalTime::parse),
            zone = o.optString("zone", ZoneId.systemDefault().id),
            recurrence = Recurrence(
                Repeat.valueOf(o.optString("repeat", Repeat.ONCE.name)),
                str("days")?.split(',')?.map(DayOfWeek::valueOf)?.toSet().orEmpty(),
                o.optInt("dayOfMonth").takeIf { it > 0 },
            ),
            status = runCatching { ReminderStatus.valueOf(o.optString("status", ReminderStatus.ACTIVE.name)) }.getOrDefault(ReminderStatus.ACTIVE),
            notificationMessage = o.optString("message"),
            sourceText = o.optString("source"),
            createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
            completedAt = long("completedAt"), cancelledAt = long("cancelledAt"),
            snoozedUntil = long("snoozedUntil"), lastFiredAt = long("lastFiredAt"),
            businessId = str("businessId"), ownerId = str("ownerId"),
            amount = str("amount")?.toBigDecimalOrNull(),
            attemptCount = o.optInt("attemptCount", 0),
            maxAttempts = o.optInt("maxAttempts", KaiReminderFlow.MAX_ATTEMPTS).takeIf { it > 0 } ?: KaiReminderFlow.MAX_ATTEMPTS,
            snoozeCount = o.optInt("snoozeCount", 0),
            lastTriggeredAt = long("lastTriggeredAt"),
            lang = runCatching { KaiLang.valueOf(o.optString("lang", KaiLang.TANGLISH.name)) }.getOrDefault(KaiLang.TANGLISH),
        )
    }

    companion object {
        private const val PREFS = "kai_reminder_engine"
        private const val KEY = "reminders"
        private const val KEY_LAST_RANG = "last_rang"
        private const val TAG = "KaiReminder"
        private const val KEY_LAST_PRESENTATION = "last_presentation"
        private const val KEY_SPOKEN = "spoken_attempt"
        private const val KEY_PRESENTATION_OF = "presentation_of_"
        /** Kai Urgent Action Mode: high importance, lock-screen visible, sound + vibration. */
        private const val URGENT_CHANNEL_ID = "kai_reminders_urgent_v1"
        private const val NOTIFICATION_BASE = 0x4000000
        const val ACTION_FIRE = "com.shopai.app.ACTION_KAI_REMINDER"
        const val ACTION_FIRE_SNOOZE = "com.shopai.app.ACTION_KAI_REMINDER_SNOOZED"
        const val ACTION_DONE = "com.shopai.app.ACTION_KAI_REMINDER_DONE"
        const val ACTION_SNOOZE = "com.shopai.app.ACTION_KAI_REMINDER_SNOOZE"
        const val EXTRA_ID = "kai_reminder_id"
        /** MainActivity: open Kai Chat on this reminder. */
        const val EXTRA_OPEN_REMINDER = "open_kai_reminder"
        const val SNOOZE_MINUTES = KaiReminderFlow.SNOOZE_MINUTES

        /** Kai Urgent Action Mode for one reminder (the dedicated screen — never Home or Chat). */
        fun urgentIntent(context: Context, id: String): Intent =
            Intent(context, com.shopai.app.ui.reminder.KaiReminderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_ID, id)
            }

        /** Android 14+: the "Allow full-screen alerts" setting for this app (app info before 14). */
        fun fullScreenSettingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= 34) Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))
            else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
    }
}
