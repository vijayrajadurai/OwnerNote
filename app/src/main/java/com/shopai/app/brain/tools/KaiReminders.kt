package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** What the reminder is for — decides the notification wording and its action button. */
enum class ReminderAction { CALL, MESSAGE, PAYMENT, COLLECTION, STOCK, TASK }

/** Who a named person is in the books, when the owner said it ("customer Kumar", "supplier Ravi"). */
enum class PartyRole { CUSTOMER, SUPPLIER }

/**
 * ACTIVE = scheduled · RANG = triggered (Kai Urgent Action Mode is up, a retry
 * is armed) · SNOOZED = the owner asked for later · COMPLETED / CANCELLED ·
 * EXHAUSTED = rang [KaiReminderFlow.MAX_ATTEMPTS] times without an answer
 * (kept in history, never rings again).
 */
enum class ReminderStatus { ACTIVE, RANG, SNOOZED, COMPLETED, CANCELLED, EXHAUSTED }

/**
 * One reminder in OwnerNote's reminder engine (the single source of truth for
 * Kai's reminders). [triggerAt] is an exact instant (epoch ms); clock-based
 * and repeating reminders also keep the local [time] so they follow the
 * phone's time zone.
 */
data class KaiReminder(
    val id: String,
    val title: String,
    /** The owner's own words for the task ("kadaiku pogumbothu saavi eduthuka"). */
    val task: String,
    val action: ReminderAction = ReminderAction.TASK,
    val person: String? = null,
    /** Customer / supplier id in the books, or a phone-contact id. */
    val contactId: String? = null,
    val phone: String? = null,
    val triggerAt: Long,
    /** Local clock time for clock-based / repeating reminders (null for "10 minutes later"). */
    val time: LocalTime? = null,
    val zone: String,
    val recurrence: Recurrence = Recurrence.ONCE,
    val status: ReminderStatus = ReminderStatus.ACTIVE,
    val notificationMessage: String,
    val sourceText: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    /** A snooze rings once at this instant without moving a repeating reminder's schedule. */
    val snoozedUntil: Long? = null,
    /** The occurrence that last rang (guards against ringing twice after a restart). */
    val lastFiredAt: Long? = null,
    val businessId: String? = null,
    /** The signed-in owner who set it (from the session, never from a screen). */
    val ownerId: String? = null,
    /** Money in the owner's words ("Kumar-ku 5000 payment") — only when it was said. */
    val amount: java.math.BigDecimal? = null,
    /** How many times it has rung for the current occurrence (Kai Urgent Action Mode, "Reminder 2 of 5"). */
    val attemptCount: Int = 0,
    val maxAttempts: Int = KaiReminderFlow.MAX_ATTEMPTS,
    val snoozeCount: Int = 0,
    val lastTriggeredAt: Long? = null,
    /** The language the owner set it in (Kai speaks the reminder the same way). */
    val lang: KaiLang = KaiLang.TANGLISH,
) {
    val open: Boolean get() = status == ReminderStatus.ACTIVE || status == ReminderStatus.RANG || status == ReminderStatus.SNOOZED

    /** When it rings next (a snooze / retry, else its own time); null once it is finished. */
    val nextTriggerAt: Long? get() = if (!open) null else snoozedUntil ?: triggerAt.takeIf { status == ReminderStatus.ACTIVE }
}

/** A reminder Kai understood and is about to create. */
data class ReminderDraft(
    val task: String,
    val title: String,
    val action: ReminderAction,
    val person: String?,
    val role: PartyRole?,
    val time: KaiWhen?,
    val sourceText: String,
    /** False: only "remind pannu" (+ a time) was said — Kai asks what to remind about. */
    val taskSaid: Boolean = true,
    /** A payment / collection amount the owner said; null when none was said (never invented). */
    val amount: java.math.BigDecimal? = null,
)

/** Which reminder the owner means: the one just talked about / rang ("andha", "that", "Done"), or by words ("Kumar call"). */
sealed interface ReminderTarget {
    data object Last : ReminderTarget
    data class Matching(val words: String, val person: String?) : ReminderTarget
}

sealed interface ReminderRequest {
    data class Create(val draft: ReminderDraft) : ReminderRequest
    data class ListAll(val todayOnly: Boolean) : ReminderRequest
    data class Cancel(val target: ReminderTarget) : ReminderRequest
    data class Complete(val target: ReminderTarget) : ReminderRequest
    data class Snooze(val target: ReminderTarget, val minutes: Long) : ReminderRequest
    data class Update(val target: ReminderTarget, val time: KaiWhen?) : ReminderRequest
}

/**
 * Kai's reminder understanding — Tamil, Tanglish, English and mixed, any
 * sentence order; plain rules, no AI. Turns what the owner said into a
 * structured request; scheduling is done by the reminder engine.
 */
object KaiReminderUnderstanding {

    private const val B = """(?<![\p{L}])"""
    private const val E = """(?![\p{L}])"""

    private val remindWords = Regex(
        """$B(remind|reminder|reminders|remainder|nyabagam|nyabaga|gnabagam|gnyabagam|nyaabagam|niyabagam|ninaivu|ninaivupaduthu|marakkama|marakkaama|""" +
            """marakkadha|alert|alarm)$E|நினைவூட்டு|நினைவூட்டல்|ஞாபகம்|மறக்காம|ரிமைண்டர்""",
        RegexOption.IGNORE_CASE,
    )
    private val tellMe = Regex("""$B(sollu|sollunga|tell me|solli)$E|சொல்லு""", RegexOption.IGNORE_CASE)
    private val doVerbs = Regex(
        """$B(call|phone|message|msg|whatsapp|sms|pay|payment|collect|vasool|check|eduka|edukka|eduthuka|pannanum|panna|poganum|vaanganum|""" +
            """kudukkanum|kattanum|follow\s*up|follow-up)$E|கால்|போன்|மெசேஜ்""",
        RegexOption.IGNORE_CASE,
    )
    private val cancelWords = Regex("""$B(cancel|delete|remove|vendam|venam|venaam|vendaam|stop|niruthu|eduthudu|thookidu)$E|ரத்து|வேண்டாம்""", RegexOption.IGNORE_CASE)
    private val changeWords = Regex("""$B(change|maathu|mathu|maatru|matthu|move|reschedule|postpone|shift|thalli\s*vai|thalli\s*podu|update|edit)$E|மாற்று""", RegexOption.IGNORE_CASE)
    private val thatWords = Regex("""$B(that|it|this|andha|antha|adha|atha|adhai|indha|intha|last|kadaisi)$E|அந்த|அதை""", RegexOption.IGNORE_CASE)
    private val doneWords = Regex(
        """^\s*(ok\s*)?(done|completed|complete|finished|mudinjiduchu|mudinjidhu|mudinjathu|mudichiten|mudichitten|aachu|pannitten|panniten|seithuten)\s*[.!]?\s*$|^\s*முடிஞ்சது\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val listWords = Regex("""$B(list|show|enna|ennenna|what|pending|today'?s|innaiku|innikku|inniku|sollu|iruku|irukku|irukka|my|en|ella|all)$E|என்ன""", RegexOption.IGNORE_CASE)

    /** The owner explicitly asked for a reminder ("remind pannu", "reminder", "nyabagam paduthu", Tamil script too). */
    fun mentionsReminder(raw: String): Boolean = remindWords.containsMatchIn(KaiSpokenWords.normalize(raw))

    fun understand(raw: String, now: LocalDateTime, people: List<String>): ReminderRequest? {
        // Spoken Tamil script ("2 நிமிஷத்துல … ரிமைண்டர் பண்ணு") reads the same as typed Tanglish.
        val text = KaiSpokenWords.normalize(raw.trim()).replace(Regex("""\s+"""), " ")
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)
        val time = KaiTime.parse(text, now)
        val mentionsReminder = remindWords.containsMatchIn(text)

        // "Done." / "Completed." — the reminder that just rang.
        if (doneWords.containsMatchIn(lower)) return ReminderRequest.Complete(ReminderTarget.Last)

        // "Innum 10 minutes later remind pannu", "30 minutes snooze pannu".
        val snooze = Regex("""$B(snooze)$E""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            (Regex("""$B(innum|inum|again|thirumba|marubadiyum)$E""", RegexOption.IGNORE_CASE).containsMatchIn(text) && time?.relative != null && !doVerbs.containsMatchIn(text))
        if (snooze) {
            val minutes = time?.relative?.toMinutes()?.takeIf { it > 0 } ?: 10
            return ReminderRequest.Snooze(target(text, people), minutes)
        }

        // Cancel / change an existing one.
        if (cancelWords.containsMatchIn(text) && (mentionsReminder || thatWords.containsMatchIn(text))) {
            return ReminderRequest.Cancel(target(text, people))
        }
        if (changeWords.containsMatchIn(text) && (mentionsReminder || thatWords.containsMatchIn(text))) {
            return ReminderRequest.Update(target(text, people), time)
        }
        if (Regex("""$B(complete|done|mudinjiduchu)$E""", RegexOption.IGNORE_CASE).containsMatchIn(text) && mentionsReminder) {
            return ReminderRequest.Complete(target(text, people))
        }

        // "En reminders enna?", "Today's reminders?", "Pending reminders sollu".
        val createVerb = Regex("""$B(remind\s*(pannu|pannunga|me|panni|pannidu)|reminder\s*(vai|vechudu|set|podu|poodu|pottu)|nyabagam\s*paduthu|ninaivu\s*paduthu)$E""", RegexOption.IGNORE_CASE)
        if (mentionsReminder && time?.relative == null && !createVerb.containsMatchIn(text) && !doVerbs.containsMatchIn(text) && listWords.containsMatchIn(text)) {
            return ReminderRequest.ListAll(todayOnly = Regex("""$B(today'?s?|innaiku|innikku|inniku|innaikku)$E|இன்னைக்கு""", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }

        // Create: a reminder word, or a time with something to do ("10 mins kalichu Kumar call"), or "… sollu" with a time.
        val create = mentionsReminder || (time != null && (doVerbs.containsMatchIn(text) || tellMe.containsMatchIn(text)))
        if (!create) return null
        // A question about the business that happens to have a time ("inniku evlo sales?") is not a reminder.
        if (!mentionsReminder && Regex("""$B(evlo|evvalavu|how much|enna|what)$E""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return ReminderRequest.Create(draft(text, time, people))
    }

    /** The task, the person, what kind of reminder — from the owner's words. */
    fun draft(text: String, time: KaiWhen?, people: List<String>): ReminderDraft {
        val action = when {
            Regex("""$B(whatsapp|message|msg|sms|text)$E|மெசேஜ்""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ReminderAction.MESSAGE
            Regex("""$B(call|phone|ring|kaal)$E|கால்|போன்""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ReminderAction.CALL
            Regex("""$B(collect|collection|vasool|vasul|vaanganum|vanganum)$E|வசூல்""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ReminderAction.COLLECTION
            Regex("""$B(pay|payment|kattanum|kattu|kudukkanum|kodukkanum|rent|bill\s*kattanum|emi)$E""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ReminderAction.PAYMENT
            Regex("""$B(stock|maal|inventory)$E|ஸ்டாக்""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ReminderAction.STOCK
            else -> ReminderAction.TASK
        }
        val role = when {
            Regex("""$B(customer|vaadikkaiyaalar)$E""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> PartyRole.CUSTOMER
            Regex("""$B(supplier|vendor|distributor)$E""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> PartyRole.SUPPLIER
            else -> null
        }
        val person = KaiCommands.personIn(text, people)
        val cleaned = cleanTask(KaiTime.strip(text))
        val taskSaid = cleaned.any(Char::isLetter)
        val task = cleaned.ifBlank { text }
        val title = when (action) {
            ReminderAction.CALL -> person?.let { "Call $it" }
            ReminderAction.MESSAGE -> person?.let { "Message $it" }
            else -> null
        } ?: task.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val amount = if (action == ReminderAction.PAYMENT || action == ReminderAction.COLLECTION) amountIn(KaiTime.strip(text)) else null
        return ReminderDraft(task, title, action, person, role, time, text, taskSaid || person != null || action != ReminderAction.TASK, amount)
    }

    /** "Kumar-ku ₹5,000 payment" → 5000; only a number the owner said with the money (times are stripped first). */
    fun amountIn(text: String): java.math.BigDecimal? =
        Regex("""(?i)(?:₹|rs\.?\s*|rupees?\s*)?(?<![\d.:])(\d{1,3}(?:,\d{2,3})+|\d+)(?:\.\d{1,2})?(?![\d:])(?:\s*(?:rs|rupees?|ரூபாய்))?""")
            .findAll(text).mapNotNull { it.groupValues[1].replace(",", "").toBigDecimalOrNull() }
            .firstOrNull { it.signum() > 0 }

    private fun target(text: String, people: List<String>): ReminderTarget {
        val person = KaiCommands.personIn(text, people)
        val words = cleanTask(KaiTime.strip(text))
            .replace(Regex("""(?i)$B(cancel|delete|remove|vendam|venam|venaam|stop|change|maathu|mathu|move|reschedule|postpone|update|snooze|done|completed|""" +
                """that|it|this|andha|antha|adha|atha|adhai|reminder|reminders|reminder-a|reminder-ah|ah|a|ku|kku|to|the)$E"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        return if (person == null && (words.none(Char::isLetter) || thatWords.containsMatchIn(text) && words.length < 3)) ReminderTarget.Last
        else ReminderTarget.Matching(words, person)
    }

    /** The owner's words without "remind pannu" and fillers. */
    fun cleanTask(text: String): String = (" $text ")
        .replace(Regex("""(?i)$B(remind\s*(pannu|pannunga|me\s*to|me|panni|pannidu)?|reminder\s*(vai|vechudu|set\s*pannu|set|podu|poodu|pottu)?|reminder|""" +
            """nyabagam\s*(paduthu|padutthu|paduthunga)?|gnabagam\s*(paduthu)?|niyabagam\s*(paduthu)?|ninaivu\s*(paduthu|paduthunga)?|ninaivupaduthu|""" +
            """please|pls|enakku|ennaku|kai|bro|set|pannu|pannunga|sollu|sollunga|nu|appo|later|me\s*to|la)$E"""), " ")
        .replace(Regex("""நினைவூட்டு|ஞாபகப்படுத்து|சொல்லு"""), " ")
        .replace(Regex("""\s+"""), " ").trim().trim('-', ',', '.').trim()
}

/** How Kai words reminders (confirmation, notification). */
object KaiReminderWords {

    /** "Kumar-ku call panna" / "stock check panna" — the task as a phrase. */
    fun what(r: ReminderDraft, lang: KaiLang): String = phrase(r.action, r.person, r.task, lang)

    fun phrase(action: ReminderAction, person: String?, task: String, lang: KaiLang): String = when {
        action == ReminderAction.CALL && person != null -> when (lang) { KaiLang.TAMIL -> "$person-க்கு call பண்ண"; KaiLang.TANGLISH -> "$person-ku call panna"; KaiLang.ENGLISH -> "to call $person" }
        action == ReminderAction.MESSAGE && person != null -> when (lang) { KaiLang.TAMIL -> "$person-க்கு message அனுப்ப"; KaiLang.TANGLISH -> "$person-ku message panna"; KaiLang.ENGLISH -> "to message $person" }
        else -> task
    }

    /** At the reminder time: "Owner, Kumar-ku call panna sonneenga." — a reminder, never "called". */
    fun notification(action: ReminderAction, person: String?, task: String, lang: KaiLang): String = when {
        action == ReminderAction.CALL && person != null -> when (lang) {
            KaiLang.TAMIL -> "ஓனர், $person-க்கு call பண்ண சொன்னீங்க."
            KaiLang.TANGLISH -> "Owner, $person-ku call panna sonneenga."
            KaiLang.ENGLISH -> "Owner, it's time to call $person."
        }
        action == ReminderAction.MESSAGE && person != null -> when (lang) {
            KaiLang.TAMIL -> "ஓனர், $person-க்கு message அனுப்ப சொன்னீங்க."
            KaiLang.TANGLISH -> "Owner, $person-ku message panna sonneenga."
            KaiLang.ENGLISH -> "Owner, it's time to message $person."
        }
        lang == KaiLang.TAMIL -> "ஓனர், $task — நினைவூட்டல்."
        lang == KaiLang.ENGLISH -> "Owner, reminder: $task."
        // "stock check panna" → "Owner, stock check panna vendiya time."
        task.endsWith(" panna") -> "Owner, $task vendiya time."
        task.endsWith(" pannanum") -> "Owner, ${task.removeSuffix("num")} vendiya time."
        else -> "Owner, $task — nyabagam paduthuren."
    }

    /** "10 minutes" / "1 hour 30 minutes" for a relative time. */
    fun duration(d: Duration, lang: KaiLang): String {
        val h = d.toHours()
        val m = d.toMinutes() % 60
        val s = d.seconds % 60
        val parts = buildList {
            if (h > 0) add(if (lang == KaiLang.TAMIL) "$h மணி நேரம்" else "$h hour" + if (h > 1) "s" else "")
            if (m > 0) add(if (lang == KaiLang.TAMIL) "$m நிமிடம்" else "$m minutes")
            if (s > 0 && h == 0L) add(if (lang == KaiLang.TAMIL) "$s வினாடி" else "$s seconds")
        }
        return parts.joinToString(" ")
    }
}

/** Deterministic reminder timing — no AI involved once the request is understood. */
object KaiReminderSchedule {

    /**
     * A reminder from a complete [when]: "10 minutes later" is the exact
     * instant now + 10 minutes; a clock time is resolved in the phone's [zone].
     */
    fun build(
        id: String, draft: ReminderDraft, `when`: KaiWhen, zone: java.time.ZoneId, nowMillis: Long, lang: KaiLang,
        person: String? = draft.person, phone: String? = null, contactId: String? = null,
    ): KaiReminder {
        val clockBased = `when`.relative == null
        val triggerAt = if (!clockBased) nowMillis + `when`.relative!!.toMillis()
        else `when`.at.atZone(zone).toInstant().toEpochMilli()
        return KaiReminder(
            id = id,
            title = when (draft.action) {
                ReminderAction.CALL -> person?.let { "Call $it" }
                ReminderAction.MESSAGE -> person?.let { "Message $it" }
                else -> null
            } ?: draft.title,
            task = draft.task, action = draft.action, person = person, contactId = contactId, phone = phone,
            triggerAt = triggerAt,
            time = if (clockBased) `when`.at.toLocalTime() else null,
            zone = zone.id,
            recurrence = `when`.recurrence,
            notificationMessage = KaiReminderWords.notification(draft.action, person, draft.task, lang),
            sourceText = draft.sourceText,
            createdAt = nowMillis,
            amount = draft.amount,
            lang = lang,
        )
    }

    /** The next time a repeating reminder rings after [afterMillis], in [zone]; null for one-time reminders. */
    fun next(r: KaiReminder, afterMillis: Long, zone: java.time.ZoneId): Long? {
        if (r.recurrence.repeat == Repeat.ONCE) return null
        val time = r.time ?: java.time.Instant.ofEpochMilli(r.triggerAt).atZone(zone).toLocalTime()
        val after = java.time.Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDateTime()
        return r.recurrence.nextAt(time, after)?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    /** The same reminder said twice (same task, person, schedule and time within a minute): not created again. */
    fun sameAs(a: KaiReminder, b: KaiReminder): Boolean =
        a.open && b.open && a.action == b.action &&
            a.task.trim().lowercase(Locale.ROOT) == b.task.trim().lowercase(Locale.ROOT) &&
            (a.person ?: "").lowercase(Locale.ROOT) == (b.person ?: "").lowercase(Locale.ROOT) &&
            a.recurrence == b.recurrence && kotlin.math.abs(a.triggerAt - b.triggerAt) < 60_000

    /** Reminders that fit what the owner said ("Kumar call", "rent"), best matches only. */
    fun matching(all: List<KaiReminder>, target: ReminderTarget.Matching): List<KaiReminder> {
        val open = all.filter { it.open }
        target.person?.let { p ->
            val byPerson = open.filter { it.person.equals(p, true) || it.title.contains(p, true) || it.task.contains(p, true) }
            if (byPerson.isNotEmpty()) {
                val words = target.words.lowercase(Locale.ROOT).split(' ').filter { it.length > 2 && !it.equals(p, true) }
                val narrowed = byPerson.filter { r -> words.all { w -> "${r.title} ${r.task} ${r.action}".lowercase(Locale.ROOT).contains(w) } }
                return narrowed.ifEmpty { byPerson }
            }
            return emptyList()
        }
        val words = target.words.lowercase(Locale.ROOT).split(' ').filter { it.length > 2 }
        if (words.isEmpty()) return emptyList()
        return open.filter { r -> words.all { w -> "${r.title} ${r.task}".lowercase(Locale.ROOT).contains(w) } }
            .ifEmpty { open.filter { r -> words.any { w -> "${r.title} ${r.task}".lowercase(Locale.ROOT).contains(w) } } }
    }
}
