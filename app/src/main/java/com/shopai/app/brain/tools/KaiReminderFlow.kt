package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Kai's persistent reminder — plain rules the one reminder engine follows
 * (no second engine, no AI):
 *
 *   ACTIVE ──ring──▶ RANG ──Done──▶ COMPLETED
 *                     │  └─Snooze──▶ SNOOZED ──ring──▶ RANG …
 *                     └─(ignored, 5 min)──▶ RANG (attempt 2 … 5) ──▶ EXHAUSTED
 *   any open state ──Cancel──▶ CANCELLED
 *
 * Every ring is one attempt; a reminder rings at most [MAX_ATTEMPTS] times
 * per occurrence (retries and snoozes count alike), then stops.
 */
object KaiReminderFlow {
    const val MAX_ATTEMPTS = 5
    const val RETRY_MINUTES = 5L
    const val SNOOZE_MINUTES = 5L

    private const val MINUTE = 60_000L

    /** May this reminder ring now? (Finished / cancelled / exhausted reminders never ring again.) */
    fun canRing(r: KaiReminder): Boolean = r.open && (r.attemptCount < r.maxAttempts || r.recurrence.repeat != Repeat.ONCE)

    /**
     * The alarm rang at [now]. [newOccurrence]: its own time (not a retry / snooze) — a repeating
     * reminder starts counting again. The next retry (if any attempts are left) is in [RETRY_MINUTES].
     */
    fun trigger(r: KaiReminder, now: Long, newOccurrence: Boolean): KaiReminder {
        val attempt = (if (newOccurrence && r.recurrence.repeat != Repeat.ONCE) 0 else r.attemptCount) + 1
        val last = attempt >= r.maxAttempts
        val repeating = r.recurrence.repeat != Repeat.ONCE
        return r.copy(
            attemptCount = attempt,
            lastTriggeredAt = now,
            // A repeating series stays scheduled for its next time; a one-time reminder is waiting for the owner.
            status = when {
                repeating -> ReminderStatus.ACTIVE
                last -> ReminderStatus.EXHAUSTED
                else -> ReminderStatus.RANG
            },
            snoozedUntil = if (last) null else now + RETRY_MINUTES * MINUTE,
            lastFiredAt = if (newOccurrence) r.triggerAt else r.lastFiredAt,
            updatedAt = now,
        )
    }

    /** Snooze from Kai Urgent Action Mode / the notification / chat. Null: nothing left to snooze (finished or last attempt). */
    fun snooze(r: KaiReminder, now: Long, minutes: Long = SNOOZE_MINUTES): KaiReminder? {
        if (!r.open || r.attemptCount >= r.maxAttempts) return null
        return r.copy(
            status = if (r.recurrence.repeat == Repeat.ONCE) ReminderStatus.SNOOZED else ReminderStatus.ACTIVE,
            snoozeCount = r.snoozeCount + 1,
            snoozedUntil = now + minutes.coerceAtLeast(1) * MINUTE,
            updatedAt = now,
        )
    }

    /** Done: a one-time reminder is finished; a repeating one waits for its next time (attempts start again). */
    fun complete(r: KaiReminder, now: Long): KaiReminder? {
        if (!r.open && r.status != ReminderStatus.EXHAUSTED) return null
        return if (r.recurrence.repeat == Repeat.ONCE) {
            r.copy(status = ReminderStatus.COMPLETED, completedAt = now, updatedAt = now, snoozedUntil = null)
        } else {
            r.copy(status = ReminderStatus.ACTIVE, attemptCount = 0, snoozedUntil = null, updatedAt = now)
        }
    }

    fun cancel(r: KaiReminder, now: Long): KaiReminder? =
        if (!r.open) null else r.copy(status = ReminderStatus.CANCELLED, cancelledAt = now, updatedAt = now, snoozedUntil = null)

    /** Kai speaks each attempt once — however often the screen is recreated or opened again. */
    fun speechKey(r: KaiReminder): String = "${r.id}#${r.attemptCount}#${r.lastTriggeredAt ?: 0}"

    /** Snooze is offered only while another ring is still possible. */
    fun canSnooze(r: KaiReminder): Boolean = r.open && r.attemptCount < r.maxAttempts
}

/** Who may see a reminder: the signed-in owner of the signed-in business (older reminders without ids stay visible). */
object KaiReminderScope {
    fun visible(all: List<KaiReminder>, businessId: String?, ownerId: String?): List<KaiReminder> =
        all.filter { r -> (r.businessId == null || r.businessId == businessId) && (r.ownerId == null || r.ownerId == ownerId) }
}

/** How Kai Urgent Action Mode reaches the owner on this phone right now. */
enum class UrgentPresentation {
    /** The app is open and the phone unlocked: Kai Urgent Action Mode opens directly. */
    DIRECT_ACTIVITY,
    /** Locked / screen off / app in the background, full-screen allowed: Android shows Kai over the lock screen. */
    FULL_SCREEN_INTENT,
    /** Full-screen not allowed: the strongest notification (high priority, lock-screen visible, sound, actions); tap opens Kai. */
    NOTIFICATION_ONLY,
}

object KaiUrgentPresentation {
    /**
     * [canUseFullScreenIntent]: NotificationManager.canUseFullScreenIntent() on Android 14+
     * (before 14 the manifest permission is enough). Never claims full screen when Android refuses it.
     */
    fun decide(canUseFullScreenIntent: Boolean, interactive: Boolean, locked: Boolean, appInForeground: Boolean): UrgentPresentation = when {
        appInForeground && interactive && !locked -> UrgentPresentation.DIRECT_ACTIVITY
        canUseFullScreenIntent -> UrgentPresentation.FULL_SCREEN_INTENT
        else -> UrgentPresentation.NOTIFICATION_ONLY
    }

    /**
     * Kai Urgent Action Mode is on screen: the same ring as a banner on top of it is a duplicate,
     * so it is removed. Only the fallback (no full-screen allowed) keeps its notification.
     */
    fun dismissNotificationWhenShown(how: UrgentPresentation): Boolean = how != UrgentPresentation.NOTIFICATION_ONLY

    /** The app is open and unlocked: Kai's screen opens itself, so the notification is posted without a heads-up banner. */
    fun silentNotification(how: UrgentPresentation): Boolean = how == UrgentPresentation.DIRECT_ACTIVITY

    /** Only a locked / background ring uses the full-screen intent (on an unlocked phone Android would show it as a banner). */
    fun useFullScreenIntent(how: UrgentPresentation): Boolean = how == UrgentPresentation.FULL_SCREEN_INTENT

    /** The Pixel 8 report line. */
    fun reportLabel(canUseFullScreenIntent: Boolean): String =
        if (canUseFullScreenIntent) "FULL_SCREEN_PERMITTED" else "FULL_SCREEN_NOT_PERMITTED"
}

/** What Kai Urgent Action Mode shows and says for one ring. */
data class UrgentText(
    /** Big line: "Praba-ku call panna vendiya neram aachu". */
    val headline: String,
    /** "Ippo call pannalama?" */
    val question: String,
    /** Kai's voice — the same words as on screen. */
    val speech: String,
    val callLabel: String,
    val doneLabel: String,
    val snoozeLabel: String,
    val attemptLine: String,
    val header: String,
)

/** Kai's urgent reminder words — only what the reminder stores (person, amount, task); nothing invented. */
object KaiUrgentWords {

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) {
        KaiLang.TAMIL -> ta
        KaiLang.TANGLISH -> tl
        KaiLang.ENGLISH -> en
    }

    fun rupees(amount: BigDecimal): String {
        val f = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }
        return "₹" + f.format(amount)
    }

    fun text(r: KaiReminder, lang: KaiLang = r.lang): UrgentText {
        val attempt = r.attemptCount.coerceAtLeast(1)
        val p = r.person?.takeIf { it.isNotBlank() }
        val amt = r.amount?.let(::rupees)
        val task = r.task.trim().trimEnd('.')
        val headline: String
        val question: String
        when {
            r.action == ReminderAction.CALL && p != null -> {
                headline = when {
                    attempt == 1 -> pick(lang, ta = "$p-க்கு call பண்ண வேண்டிய நேரம் ஆச்சு", tl = "$p-ku call panna vendiya neram aachu", en = "Time to call $p")
                    attempt == 2 -> pick(lang, ta = "இன்னும் $p-க்கு call பண்ணல", tl = "Innum $p-ku call pannala", en = "You still haven't called $p")
                    else -> pick(lang, ta = "$p call reminder இன்னும் pending-ல இருக்கு", tl = "$p call reminder innum pending-la irukku", en = "The $p call reminder is still pending")
                }
                question = pick(lang, ta = "இப்போ call பண்ணலாமா?", tl = "Ippo call pannalama?", en = "Call now?")
            }
            r.action == ReminderAction.MESSAGE && p != null -> {
                headline = if (attempt == 1) pick(lang, ta = "$p-க்கு message அனுப்ப வேண்டிய நேரம் ஆச்சு", tl = "$p-ku message panna vendiya neram aachu", en = "Time to message $p")
                else pick(lang, ta = "$p message reminder இன்னும் pending-ல இருக்கு", tl = "$p message reminder innum pending-la irukku", en = "The $p message reminder is still pending")
                question = pick(lang, ta = "இப்போ message பண்ணலாமா?", tl = "Ippo message pannalama?", en = "Message now?")
            }
            r.action == ReminderAction.PAYMENT -> {
                val who = p?.let { pick(lang, ta = "$it-க்கு ", tl = "$it-ku ", en = "") } ?: ""
                val money = amt?.let { "$it " } ?: ""
                headline = pending(attempt, lang,
                    first = pick(lang, ta = "$who${money}payment பண்ண வேண்டிய reminder", tl = "$who${money}payment panna vendiya reminder",
                        en = "Reminder to pay ${amt?.let { "$it " } ?: ""}${p?.let { "to $it" } ?: ""}".trim()))
                question = pick(lang, ta = "முடிஞ்சதும் Done அழுத்துங்க.", tl = "Mudinjadhum Done press pannunga.", en = "Tap Done when it's paid.")
            }
            r.action == ReminderAction.COLLECTION -> {
                val from = p?.let { pick(lang, ta = "$it கிட்ட ", tl = "$it kitta ", en = "") } ?: ""
                val money = amt?.let { "$it " } ?: ""
                headline = pending(attempt, lang,
                    first = pick(lang, ta = "$from${money}collect பண்ண வேண்டிய reminder", tl = "$from${money}collect panna vendiya reminder",
                        en = "Reminder to collect ${amt?.let { "$it " } ?: ""}${p?.let { "from $it" } ?: ""}".trim()))
                question = pick(lang, ta = "முடிஞ்சதும் Done அழுத்துங்க.", tl = "Mudinjadhum Done press pannunga.", en = "Tap Done when it's collected.")
            }
            r.action == ReminderAction.STOCK && task.isNotEmpty() -> {
                val words = if (task.endsWith(" panna", true)) task else "$task panna"
                headline = pending(attempt, lang, first = pick(lang, ta = "$task — நினைவூட்டல்", tl = "$words vendiya reminder", en = "Reminder: $task"))
                question = pick(lang, ta = "முடிஞ்சதும் Done அழுத்துங்க.", tl = "Mudinjadhum Done press pannunga.", en = "Tap Done when it's done.")
            }
            else -> {
                headline = if (attempt == 1) pick(lang, ta = "நீங்க நினைவூட்ட சொன்ன வேலை pending-ல இருக்கு", tl = "Neenga remind panna sonna task pending-la irukku", en = "The task you asked me to remind you about is pending")
                else pick(lang, ta = "அந்த வேலை இன்னும் pending-ல இருக்கு", tl = "Andha task innum pending-la irukku", en = "That task is still pending")
                question = if (task.isNotEmpty()) "“$task”" else pick(lang, ta = "முடிஞ்சதும் Done அழுத்துங்க.", tl = "Mudinjadhum Done press pannunga.", en = "Tap Done when it's done.")
            }
        }
        val owner = pick(lang, ta = "ஓனர், ", tl = "Owner, ", en = "Owner, ")
        // "Owner, innum Praba-ku call pannala." — a name keeps its capital, a word doesn't.
        val body = if (headline.substringBefore(' ') in sentenceWords) headline.replaceFirstChar { it.lowercase() } else headline
        val speech = "$owner$body. $question"
        return UrgentText(
            headline = headline,
            question = question,
            speech = speech.replace("..", "."),
            callLabel = pick(lang, ta = "📞 இப்போ CALL", tl = "📞 CALL NOW", en = "📞 CALL NOW"),
            doneLabel = pick(lang, ta = "✓ முடிஞ்சது", tl = "✓ DONE", en = "✓ DONE"),
            snoozeLabel = pick(lang, ta = "⏰ 5 நிமிடம் கழிச்சு", tl = "⏰ SNOOZE 5 MIN", en = "⏰ SNOOZE 5 MIN"),
            attemptLine = pick(lang, ta = "நினைவூட்டல் $attempt / ${r.maxAttempts}", tl = "Reminder $attempt of ${r.maxAttempts}", en = "Reminder $attempt of ${r.maxAttempts}"),
            header = pick(lang, ta = "நினைவூட்டல்", tl = "REMINDER", en = "REMINDER"),
        )
    }

    /** Words that only start a sentence (a product or person name keeps its capital). */
    private val sentenceWords = setOf("Innum", "Neenga", "Andha", "Time", "Reminder", "Still", "The", "That", "You")

    private fun pending(attempt: Int, lang: KaiLang, first: String): String =
        if (attempt == 1) first else pick(lang, ta = "இன்னும் pending: $first", tl = "Innum pending: $first", en = "Still pending: $first")

    /** "Set 2 minutes ago". */
    fun setAgo(createdAt: Long, now: Long, lang: KaiLang): String {
        val minutes = ((now - createdAt) / 60_000).coerceAtLeast(0)
        val span = when {
            minutes < 1 -> return pick(lang, ta = "இப்போ தான் வைச்சது", tl = "Ippo dhaan set pannadhu", en = "Set just now")
            minutes < 60 -> "$minutes " + pick(lang, ta = "நிமிடம்", tl = if (minutes == 1L) "minute" else "minutes", en = if (minutes == 1L) "minute" else "minutes")
            minutes < 24 * 60 -> "${minutes / 60} " + pick(lang, ta = "மணி நேரம்", tl = if (minutes / 60 == 1L) "hour" else "hours", en = if (minutes / 60 == 1L) "hour" else "hours")
            else -> "${minutes / (24 * 60)} " + pick(lang, ta = "நாள்", tl = if (minutes / (24 * 60) == 1L) "day" else "days", en = if (minutes / (24 * 60) == 1L) "day" else "days")
        }
        return pick(lang, ta = "$span முன்னாடி வைச்சது", tl = "Set $span ago", en = "Set $span ago")
    }

    fun done(lang: KaiLang) = pick(lang, ta = "சரி ஓனர். Reminder முடிஞ்சது.", tl = "Seri Owner. Reminder complete.", en = "Okay Owner. Reminder complete.")
    fun snoozed(minutes: Long, lang: KaiLang) = pick(lang, ta = "சரி ஓனர். $minutes நிமிடம் கழிச்சு மறுபடியும் சொல்றேன்.", tl = "Seri Owner. $minutes minutes-ku snooze pannitten.", en = "Okay Owner. Snoozed for $minutes minutes.")
    /** The dialer opened — never "called", never "answered". */
    fun callOpened(lang: KaiLang) = pick(lang, ta = "Call screen திறந்துட்டேன் ஓனர்.", tl = "Call screen open pannitten Owner.", en = "I opened the call screen, Owner.")
    fun noNumber(person: String?, lang: KaiLang) = pick(lang,
        ta = "${person ?: ""} நம்பர் இல்ல ஓனர் — dialer திறக்குறேன்.", tl = "${person ?: ""} number illa Owner — dialer open panren.", en = "I don't have ${person ?: "the"} number, Owner — opening the dialer.")
}
