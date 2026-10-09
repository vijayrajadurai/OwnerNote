package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.KaiIntents
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiReminderSchedule
import com.shopai.app.brain.tools.KaiReminderWords
import com.shopai.app.brain.tools.KaiTime
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.KaiWhen
import com.shopai.app.brain.tools.Missing
import com.shopai.app.brain.tools.Recurrence
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderDraft
import com.shopai.app.brain.tools.ReminderRequest
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ReminderTarget
import com.shopai.app.brain.tools.Repeat
import com.shopai.app.brain.tools.ScheduleResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Kai's reminder conversation: understands → validates time / person → asks
 * only what is missing (never invents an important time or the wrong person)
 * → creates / updates / cancels / completes / snoozes through the one
 * reminder engine → confirms. Saying "reminder vachiten" is never "call
 * panniten": Kai only ever claims what the engine actually did.
 */
class KaiReminderAssistant(
    private val tools: KaiTools,
    private val books: KaiBooks? = null,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private enum class Waiting { NOTHING, TASK, TIME, DAY_OF_MONTH, NUMBER, UPDATE_TIME, CONFIRM, CONTACT }

    private data class Pending(
        val key: String,
        val draft: ReminderDraft,
        val time: KaiWhen?,
        val contacts: List<ContactMatch> = emptyList(),
        val contact: ContactMatch? = null,
        /** Changing an existing reminder (its id) instead of creating one. */
        val updateId: String? = null,
        /** The language the owner asked in (the confirmation and the reminder keep it). */
        val lang: KaiLang? = null,
    )

    private val pending = LinkedHashMap<String, Pending>()
    private var waiting = Waiting.NOTHING
    private var waitingKey: String? = null
    /** The reminder just created / changed / shown — what "andha", "that", "adha" mean. */
    private var lastTouched: String? = null

    private fun zone(): ZoneId = runCatching { ZoneId.of(tools.zone()) }.getOrDefault(ZoneId.systemDefault())
    private fun nowMillis(): Long = now().atZone(zone()).toInstant().toEpochMilli()

    // ------------------------------------------------------------ entry points

    /** "confirm" / "venam" / "maathu" right after "…reminder set pannalama?" — the reminder's answer, not a payment's. */
    fun answersConfirm(text: String): Boolean = waiting == Waiting.CONFIRM &&
        text.trim().lowercase(Locale.ROOT).trim('.', '!', ' ').let { t -> confirmYes.matches(t) || confirmNo.matches(t) || confirmEdit.containsMatchIn(t) }

    /** Kai asked which of several same-named contacts the reminder is for. */
    fun awaitingContact(): Boolean = waiting == Waiting.CONTACT

    /** A reply to what Kai just asked (a time, a date, a phone number); null when it isn't one. */
    suspend fun continueWith(text: String, lang: KaiLang): KaiTurn? {
        val key = waitingKey
        val mode = waiting
        if (mode == Waiting.NOTHING) return null
        waiting = Waiting.NOTHING
        waitingKey = null
        return when (mode) {
            // "2 minutes la remind pannu" → "Enna remind pannanum?" → "Ruthran-ku call panna": the same reminder, now with its task.
            Waiting.TASK -> {
                val p = key?.let { pending[it] } ?: return null
                val l = if (lang == KaiLang.ENGLISH) com.shopai.app.brain.KaiLanguage.forChat(p.draft.sourceText) else lang
                val said = KaiTime.parse(text, now())
                val d = com.shopai.app.brain.tools.KaiReminderUnderstanding.draft(text, said ?: p.time, emptyList())
                if (!d.taskSaid) return null
                proceed(p.copy(draft = d.copy(sourceText = "${p.draft.sourceText} · $text"), time = p.time ?: said), l)
            }
            Waiting.TIME -> {
                val p = key?.let { pending[it] } ?: return null
                // A short answer ("10 minutes la") keeps the language the owner asked in.
                val l = if (lang == KaiLang.ENGLISH) com.shopai.app.brain.KaiLanguage.forChat(p.draft.sourceText) else lang
                // "10 minutes la" / "naalaikku 10 manikku" — a full time answers the question by itself.
                val said = KaiTime.parse(text, now())
                if (said != null && (said.relative != null || (p.time == null && said.complete))) {
                    return proceed(p.copy(time = said), l)
                }
                val time = clockFrom(text, p.time?.dayPart?.let(::partWord)) ?: return null
                val base = p.time ?: KaiWhen(now(), missing = setOf(Missing.TIME))
                proceed(p.copy(time = withTime(base, time)), l)
            }
            Waiting.DAY_OF_MONTH -> {
                val p = key?.let { pending[it] } ?: return null
                val day = Regex("""(\d{1,2})""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 } ?: return null
                val base = p.time ?: return null
                val rec = base.recurrence.copy(dayOfMonth = day)
                proceed(p.copy(time = base.copy(recurrence = rec, missing = base.missing - Missing.DAY_OF_MONTH)), lang)
            }
            Waiting.NUMBER -> {
                val digits = text.filter(Char::isDigit).takeLast(10).takeIf { it.length == 10 } ?: return null
                val r = key?.let { id -> tools.reminders().firstOrNull { it.id == id } } ?: return null
                tools.updateReminder(r.copy(phone = "+91$digits"))
                tools.log("reminder number", "reminders", r.title, ActionStatus.SCHEDULED, r.id)
                say(lang, KaiMood.SUCCESS,
                    ta = "சரி ஓனர், இந்த reminder-க்கு ${r.person ?: ""} நம்பர் சேர்த்துட்டேன்.",
                    tl = "Seri Owner, indha reminder-ku ${r.person ?: ""} number add panniten.",
                    en = "Done Owner, I added ${r.person ?: "the"} number to this reminder.")
            }
            Waiting.UPDATE_TIME -> {
                val r = key?.let { id -> tools.reminders().firstOrNull { it.id == id } } ?: return null
                val time = KaiTime.parse(text, now()) ?: clockFrom(text, null)?.let { t -> KaiWhen(nextAt(t)) } ?: return null
                update(r, time, lang)
            }
            // "Seri Owner. Praba-ku 2 minutes-la call reminder set pannalama?" → "aama" / "venam" / "maathu".
            Waiting.CONFIRM -> {
                val p = key?.let { pending[it] } ?: return null
                val t = text.trim().lowercase(Locale.ROOT).trim('.', '!', ' ')
                when {
                    confirmYes.matches(t) -> confirmCreate(p, lang)
                    confirmNo.matches(t) -> act(KaiAction.CancelRequest(p.key), p.lang ?: lang)
                    confirmEdit.containsMatchIn(t) && KaiTime.parse(text, now()) == null -> editRequest(p, p.lang ?: lang)
                    else -> null
                }
            }
            // "Endha Lokesh? Lokesh / Madurai Lokesh" → "Madurai" / "rendavadhu" / "Lokesh": the same as tapping that contact.
            Waiting.CONTACT -> {
                val p = key?.let { pending[it] } ?: return null
                val asParties = p.contacts.mapIndexed { i, c -> com.shopai.app.brain.tools.PartyMatch("$i", c.name, true, c.phone, java.math.BigDecimal.ZERO) }
                val i = KaiEntityResolver.pick(com.shopai.app.brain.tools.KaiSpokenWords.normalize(text), asParties)?.id?.toIntOrNull() ?: return null
                act(KaiAction.PickContact(p.key, i), p.lang ?: lang)
            }
            Waiting.NOTHING -> null
        }
    }

    private val confirmYes = Regex("""(aama|ama|aamaa|amam|ok|okay|okk|sari|seri|sariya|confirm|yes|yeah|ya|set\s*pannu|set\s*pannunga|podu|vai|vechudu|சரி|ஆமா|ஆமாம்|ok\s*pannu)""")
    private val confirmNo = Regex("""(venam|vendam|venaam|vendaam|cancel|no|illa|vendaa|வேண்டாம்|இல்ல)""")
    private val confirmEdit = Regex("""\b(edit|maathu|mathu|change|maatru)\b|மாற்று""")

    suspend fun handle(request: ReminderRequest, lang: KaiLang): KaiTurn = when (request) {
        is ReminderRequest.Create -> proceed(Pending(newKey(), request.draft, request.draft.time), lang)
        is ReminderRequest.ListAll -> list(request.todayOnly, lang)
        is ReminderRequest.Cancel -> withTarget(request.target, Op.CANCEL, lang, 0) { r -> cancel(r, lang) }
        is ReminderRequest.Complete -> withTarget(request.target, Op.COMPLETE, lang, 0) { r -> complete(r, lang) }
        is ReminderRequest.Snooze -> withTarget(request.target, Op.SNOOZE, lang, request.minutes) { r -> snooze(r, request.minutes, lang) }
        is ReminderRequest.Update -> withTarget(request.target, Op.UPDATE, lang, 0, request.time) { r ->
            if (request.time == null) {
                waiting = Waiting.UPDATE_TIME
                waitingKey = r.id
                say(lang, KaiMood.CLARIFY, ta = "எப்போவுக்கு மாத்தணும் ஓனர்?", tl = "Eppo-ku maathanum Owner?", en = "To when, Owner?")
            } else update(r, request.time, lang)
        }
    }

    /** A reminder button. Null when the action isn't a reminder one. */
    suspend fun act(action: KaiAction, lang: KaiLang): KaiTurn? = when (action) {
        is KaiAction.ConfirmReminder -> pending[action.requestKey]?.let { confirmCreate(it, lang) }
            ?: say(lang, KaiMood.NEUTRAL, ta = "இந்த reminder ஏற்கனவே முடிவு பண்ணியாச்சு ஓனர்.", tl = "Owner, indha reminder already mudivu pannachu.", en = "That reminder was already handled, Owner.")
        is KaiAction.EditReminderRequest -> pending[action.requestKey]?.let { editRequest(it, it.lang ?: lang) }
        is KaiAction.RemindAt -> pending[action.requestKey]?.let { p ->
            val base = p.time ?: KaiWhen(action.at, missing = setOf(Missing.TIME))
            proceed(p.copy(time = withTime(base.copy(at = action.at), action.at.toLocalTime(), keepDate = true)), lang)
        }
        is KaiAction.PickContact -> pending[action.requestKey]?.let { p -> proceed(p.copy(contact = p.contacts.getOrNull(action.index), contacts = emptyList()), lang) }
        is KaiAction.CancelRequest -> pending.remove(action.requestKey)?.let {
            if (waitingKey == action.requestKey) { waiting = Waiting.NOTHING; waitingKey = null }
            say(lang, KaiMood.NEUTRAL, ta = "சரி ஓனர், reminder வைக்கல.", tl = "Seri Owner, reminder vekkala.", en = "Okay Owner, no reminder set.")
        }
        is KaiAction.CancelReminder -> byId(action.id)?.let { cancel(it, lang) }
        is KaiAction.EditReminder -> byId(action.id)?.let { r ->
            waiting = Waiting.UPDATE_TIME
            waitingKey = r.id
            say(lang, KaiMood.CLARIFY, ta = "“${r.title}” — எப்போவுக்கு மாத்தணும் ஓனர்?", tl = "“${r.title}” — eppo-ku maathanum Owner?", en = "“${r.title}” — to when, Owner?")
        }
        is KaiAction.CompleteReminder -> byId(action.id)?.let { complete(it, lang) }
        is KaiAction.SnoozeReminder -> byId(action.id)?.let { snooze(it, action.minutes, lang) }
        is KaiAction.PickReminder -> byId(action.id)?.let { r ->
            when (action.op) {
                Op.CANCEL -> cancel(r, lang)
                Op.COMPLETE -> complete(r, lang)
                Op.SNOOZE -> snooze(r, action.minutes, lang)
                Op.UPDATE -> pending.remove(action.requestKey)?.time?.let { update(r, it, lang) } ?: run {
                    waiting = Waiting.UPDATE_TIME; waitingKey = r.id
                    say(lang, KaiMood.CLARIFY, ta = "எப்போவுக்கு மாத்தணும் ஓனர்?", tl = "Eppo-ku maathanum Owner?", en = "To when, Owner?")
                }
            }
        }
        else -> null
    }

    /** The reminder rang and the owner opened it: its message with Call / Snooze / Done. */
    fun rang(id: String, lang: KaiLang): KaiTurn? {
        val r = byId(id) ?: return null
        lastTouched = r.id
        val buttons = buildList {
            if (r.action == ReminderAction.CALL && r.person != null) add(KaiButton(pick(lang, ta = "${r.person}-க்கு call", tl = "Call ${r.person}", en = "Call ${r.person}"), KaiAction.Dial(r.person, r.phone), primary = true))
            for (m in listOf(5L, 10L, 30L, 60L)) add(KaiButton(snoozeLabel(m, lang), KaiAction.SnoozeReminder(r.id, m)))
            add(KaiButton(pick(lang, ta = "முடிஞ்சது", tl = "Done", en = "Done"), KaiAction.CompleteReminder(r.id), primary = r.action != ReminderAction.CALL))
        }
        return KaiTurn(ChatReply(r.notificationMessage, KaiMood.REMINDER, ChatIntent.REMINDER_QUERY), KaiCard(emptyList(), buttons))
    }

    fun reset() {
        pending.clear()
        waiting = Waiting.NOTHING
        waitingKey = null
        lastTouched = null
    }

    // ------------------------------------------------------------ create

    /** Validate what is known; ask the next missing thing; create when complete. */
    private suspend fun proceed(p0: Pending, lang: KaiLang): KaiTurn {
        var p = p0
        pending[p.key] = p
        // 0. Only "remind pannu" (+ maybe a time): what should Kai remind about? (the time is kept, never asked again)
        if (!p.draft.taskSaid) {
            waiting = Waiting.TASK
            waitingKey = p.key
            return KaiTurn(ChatReply(pick(lang,
                ta = "சரி ஓனர். என்ன ஞாபகப்படுத்தணும்?",
                tl = "Seri Owner. Enna nyabagam paduthanum?",
                en = "Sure Owner. What should I remind you about?"), KaiMood.CLARIFY, ChatIntent.REMINDER_QUERY), KaiCard(emptyList(), listOf(cancelButton(p.key, lang))))
        }
        val time = p.time
        // 1. No time said: ask (never invented).
        if (time == null || Missing.TIME in time.missing) {
            waiting = Waiting.TIME
            waitingKey = p.key
            val part = time?.dayPart
            val choices = (part?.choices ?: listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0)))
            val buttons = choices.map { c ->
                val at = when {
                    time == null -> nextAt(c)
                    time.recurrence.repeat != Repeat.ONCE -> time.recurrence.nextAt(c, now()) ?: nextAt(c)
                    else -> time.at.toLocalDate().atTime(c)
                }
                KaiButton(clock(c), KaiAction.RemindAt(p.key, at))
            }
            val text = if (part != null) pick(lang,
                ta = "${partName(part.name, lang)} சரியான நேரம் சொல்லுங்க ஓனர்.",
                tl = "${partName(part.name, lang)}-la exact time sollunga Owner.",
                en = "What exact time in the ${partName(part.name, lang)}, Owner?")
            else pick(lang,
                ta = "சரி ஓனர். எப்போ நினைவூட்டணும்? (உதா: 10 நிமிஷத்துல, நாளைக்கு காலை 10 மணிக்கு)",
                tl = "Seri Owner. Eppa remind pannanum? (eg: 10 minutes la, naalaikku kaalaila 10 manikku)",
                en = "When should I remind you, Owner? (e.g. in 10 minutes, tomorrow 10 AM)")
            return KaiTurn(ChatReply(text, KaiMood.CLARIFY, ChatIntent.REMINDER_QUERY), KaiCard(emptyList(), buttons + cancelButton(p.key, lang)))
        }
        // 2. "Every month" without the date.
        if (Missing.DAY_OF_MONTH in time.missing) {
            waiting = Waiting.DAY_OF_MONTH
            waitingKey = p.key
            return say(lang, KaiMood.CLARIFY, ta = "மாசம் எந்த தேதி ஓனர்? (உதா: 1, 10)", tl = "Maasam endha thethi Owner? (eg: 1st, 10th)", en = "Which day of the month, Owner? (e.g. 1st, 10th)")
        }
        // 3. A time that has already gone.
        if (time.alreadyPassed) {
            val tomorrow = now().toLocalDate().plusDays(1).atTime(time.at.toLocalTime())
            return KaiTurn(
                ChatReply(pick(lang,
                    ta = "${clock(time.at.toLocalTime())} ஏற்கனவே போயிடுச்சு ஓனர். நாளைக்கு அதே நேரம் வைக்கட்டுமா?",
                    tl = "Owner, ${clock(time.at.toLocalTime())} already pochu. Naalaikku same time-ku vekkava?",
                    en = "${clock(time.at.toLocalTime())} has already passed, Owner. Set it for tomorrow at the same time?"), KaiMood.CLARIFY, ChatIntent.REMINDER_QUERY),
                KaiCard(emptyList(), listOf(KaiButton(pick(lang, ta = "நாளைக்கு", tl = "Naalaikku", en = "Tomorrow"), KaiAction.RemindAt(p.key, tomorrow), primary = true), cancelButton(p.key, lang))),
            )
        }
        // 4. The person: an OwnerNote customer / supplier or a phone contact — never guessed.
        val person = p.draft.person
        if (person != null && p.contact == null && p.contacts.isEmpty() && p.updateId == null) {
            val found = runCatchingBlocking { tools.contacts(person, p.draft.role) }.orEmpty()
            fun same(n: String) = n.equals(person, true) || com.shopai.app.util.NameSound.same(n, person)
            val exact = found.filter { same(it.name) }
            // "Lokesh" with "Lokesh" and "Madurai Lokesh" in the books: both are asked — never the plain one by itself.
            val candidates = (if (exact.isEmpty()) exact else exact + found.filter { c -> c !in exact && c.name.split(Regex("""\s+""")).any(::same) })
                .ifEmpty { found }
            when {
                candidates.size == 1 -> p = p.copy(contact = candidates.single())
                candidates.size > 1 -> {
                    p = p.copy(contacts = candidates.take(5))
                    pending[p.key] = p
                    val buttons = p.contacts.mapIndexed { i, c -> KaiButton("${c.name}${c.phone?.let { " · $it" } ?: ""} · ${sourceName(c, lang)}", KaiAction.PickContact(p.key, i)) }
                    // The names are said too (a voice owner never sees the buttons); a typed / spoken pick answers it.
                    waiting = Waiting.CONTACT
                    waitingKey = p.key
                    val names = p.contacts.map { it.name }.distinct()
                    val said = if (names.size == p.contacts.size) names else p.contacts.mapIndexed { i, c -> "${i + 1}. ${c.name}" }
                    return KaiTurn(
                        ChatReply(pick(lang,
                            ta = "$person-னு ${candidates.size} பேர் இருக்காங்க ஓனர். எந்த $person? " + said.joinToString("-ஆ, ") + "-ஆ?",
                            tl = "$person-nu ${candidates.size} contacts irukku Owner. Endha $person? " + said.joinToString("-aa, ") + "-aa?",
                            en = "There are ${candidates.size} people called $person, Owner. Which one? " + said.joinToString(" or ") + "?"), KaiMood.CLARIFY, ChatIntent.REMINDER_QUERY),
                        KaiCard(emptyList(), buttons + cancelButton(p.key, lang)),
                    )
                }
            }
        }
        // Nothing is scheduled until the owner confirms.
        return confirmAsk(p.copy(lang = p.lang ?: lang), time, p.lang ?: lang)
    }

    /** "Seri Owner. Praba-ku 2 minutes-la call reminder set pannalama?" [Confirm] [Edit] [Cancel] — nothing is set yet. */
    private fun confirmAsk(p: Pending, time: KaiWhen, lang: KaiLang): KaiTurn {
        pending[p.key] = p.copy(time = time)
        waiting = Waiting.CONFIRM
        waitingKey = p.key
        val c = p.contact
        // A preview only (the real one is built at Confirm, so "2 minutes" counts from then).
        val preview = KaiReminderSchedule.build(
            id = "preview", draft = p.draft, `when` = time, zone = zone(), nowMillis = nowMillis(), lang = lang,
            person = c?.name ?: p.draft.person, phone = c?.phone, contactId = c?.id,
        )
        val person = preview.person
        val whenWords = time.relative?.let { d -> KaiReminderWords.duration(d, lang) }
        val whenText = whenText(preview, time, lang)
        val what = when {
            preview.action == ReminderAction.CALL && person != null -> pick(lang,
                ta = "$person-க்கு ${whenWords?.let { "$it-ல" } ?: whenText} call",
                tl = "$person-ku ${whenWords?.let { "$it-la" } ?: "$whenText-ku"} call",
                en = "to call $person ${whenWords?.let { "in $it" } ?: whenText}")
            preview.action == ReminderAction.MESSAGE && person != null -> pick(lang,
                ta = "$person-க்கு ${whenWords?.let { "$it-ல" } ?: whenText} message",
                tl = "$person-ku ${whenWords?.let { "$it-la" } ?: "$whenText-ku"} message",
                en = "to message $person ${whenWords?.let { "in $it" } ?: whenText}")
            else -> {
                // "Kumar-ku reminder pannu": the task is only the name — no quoted task.
                val onlyName = person != null && preview.task.replace(person, "", ignoreCase = true).trim().trim('-').removePrefix("ku").removePrefix("kku").isBlank()
                val who = if (onlyName) pick(lang, ta = "$person-க்கு ", tl = "$person-ku ", en = "for $person ") else ""
                val task = if (onlyName) "" else " “${preview.task}”"
                pick(lang,
                    ta = "$who${whenWords?.let { "$it-ல" } ?: whenText}$task",
                    tl = "$who${whenWords?.let { "$it-la" } ?: "$whenText-ku"}$task",
                    en = (if (onlyName) who else "for${task} ") + (whenWords?.let { "in $it" } ?: whenText))
            }
        }
        val text = pick(lang,
            ta = "சரி ஓனர். $what reminder வைக்கட்டுமா?",
            tl = "Seri Owner. $what reminder set pannalama?",
            en = "Okay Owner. Set a reminder $what?")
        val lines = listOfNotNull(
            pick(lang, ta = "நினைவூட்டல்", tl = "Reminder", en = "Reminder"),
            preview.title,
            pick(lang, ta = "எப்போ: ", tl = "When: ", en = "When: ") + (whenWords?.let { d -> pick(lang, ta = "$d கழிச்சு", tl = "$d kalichi", en = "$d from now") } ?: whenText),
            preview.amount?.let { pick(lang, ta = "தொகை: ", tl = "Amount: ", en = "Amount: ") + com.shopai.app.brain.tools.KaiUrgentWords.rupees(it) },
            pick(lang, ta = "நிலை: Confirm பண்ணா தான் வைப்பேன்", tl = "Status: Confirm pannina dhaan set aagum", en = "Status: not set until you confirm"),
        )
        val buttons = listOf(
            KaiButton(pick(lang, ta = "Confirm", tl = "Confirm", en = "Confirm"), KaiAction.ConfirmReminder(p.key), primary = true),
            KaiButton(pick(lang, ta = "மாற்று", tl = "Edit", en = "Edit"), KaiAction.EditReminderRequest(p.key)),
            cancelButton(p.key, lang),
        )
        return KaiTurn(ChatReply(text, KaiMood.CLARIFY, ChatIntent.REMINDER_QUERY), KaiCard(lines, buttons))
    }

    /** Confirm: only now is the reminder saved and its alarm armed (2 minutes counts from now). */
    private fun confirmCreate(p: Pending, lang: KaiLang): KaiTurn {
        val time = p.time ?: return say(lang, KaiMood.CLARIFY, ta = "எப்போ நினைவூட்டணும் ஓனர்?", tl = "Seri Owner. Eppa remind pannanum?", en = "When should I remind you, Owner?")
        pending.remove(p.key)
        if (waitingKey == p.key) { waiting = Waiting.NOTHING; waitingKey = null }
        return create(p, time, p.lang ?: lang)
    }

    /** Edit before it is set: the time is asked again (the task and person stay). */
    private fun editRequest(p: Pending, lang: KaiLang): KaiTurn {
        pending[p.key] = p.copy(time = null)
        waiting = Waiting.TIME
        waitingKey = p.key
        return KaiTurn(
            ChatReply(pick(lang,
                ta = "சரி ஓனர். எப்போ நினைவூட்டணும்? (உதா: 10 நிமிஷத்துல, நாளைக்கு காலை 10 மணிக்கு)",
                tl = "Seri Owner. Eppa remind pannanum? (eg: 10 minutes la, naalaikku kaalaila 10 manikku)",
                en = "Sure Owner. When should I remind you? (e.g. in 10 minutes, tomorrow 10 AM)"), KaiMood.CLARIFY, ChatIntent.REMINDER_QUERY),
            KaiCard(emptyList(), listOf(cancelButton(p.key, lang))),
        )
    }

    private fun create(p: Pending, time: KaiWhen, lang: KaiLang): KaiTurn {
        val c = p.contact
        val reminder = KaiReminderSchedule.build(
            id = "R" + UUID.randomUUID().toString().replace("-", "").take(10),
            draft = p.draft, `when` = time, zone = zone(), nowMillis = nowMillis(), lang = lang,
            person = c?.name ?: p.draft.person, phone = c?.phone, contactId = c?.id,
        )
        val saved = tools.createReminder(reminder)
        if (saved.result == ScheduleResult.FAILED && !saved.duplicate) {
            tools.log("reminder", "reminder engine", reminder.title, ActionStatus.FAILED)
            return say(lang, KaiMood.ERROR, ta = "நினைவூட்டல் வைக்க முடியல ஓனர்.", tl = "Owner, reminder vekka mudiyala.", en = "I couldn't set the reminder, Owner.")
        }
        val r = saved.reminder
        // "Remind pannuren" only for a reminder the store gives back: never claimed from the engine's word alone.
        if (tools.reminderStored(r.id) == false) {
            tools.log("reminder", "read-back", "not stored ${r.id}", ActionStatus.FAILED, r.id, p.draft.sourceText)
            return say(lang, KaiMood.ERROR, ta = "நினைவூட்டல் சேமிச்சதா உறுதி பண்ண முடியல ஓனர் — Reminders screen-ல பாருங்க.",
                tl = "Owner, reminder save aanadha confirm panna mudiyala — Reminders screen-la paarunga.",
                en = "Owner, I couldn't confirm the reminder was saved — please check the Reminders screen.")
        }
        lastTouched = r.id
        tools.log(KaiIntents.CREATE_REMINDER, "reminder engine", "${r.title}: ${r.recurrence.repeat} ${Instant.ofEpochMilli(r.triggerAt)}", if (saved.duplicate) ActionStatus.ANSWERED else ActionStatus.SCHEDULED, r.id, p.draft.sourceText)
        val what = KaiReminderWords.phrase(r.action, r.person, r.task, lang)
        val whenText = whenText(r, time, lang)
        val text = when {
            saved.duplicate -> pick(lang,
                ta = "ஓனர், இந்த reminder ஏற்கனவே இருக்கு — $whenText.",
                tl = "Owner, indha reminder already irukku — $whenText.",
                en = "Owner, that reminder is already set — $whenText.")
            // "Done Owner ✅ 2 minutes kalichi Ruthran-ku call panna remind pannuren." — a reminder, never "I called".
            time.relative != null -> pick(lang,
                ta = "சரி ஓனர் ✅ ${KaiReminderWords.duration(time.relative, lang)} கழிச்சு $what நினைவூட்டுறேன்.",
                tl = "Done Owner ✅ ${KaiReminderWords.duration(time.relative, lang)} kalichi $what remind pannuren.",
                en = "Done Owner ✅ I'll remind you $what in ${KaiReminderWords.duration(time.relative, lang)} (${clock(localOf(r.triggerAt).toLocalTime())}).")
            else -> pick(lang,
                ta = "சரி ஓனர் ✅ $whenText $what நினைவூட்டுறேன்.",
                tl = "Done Owner ✅ $whenText-ku $what remind pannuren.",
                en = "Done Owner ✅ I'll remind you $what $whenText.")
        } + if (time.amPmAssumed) pick(lang, ta = " (${clock(time.at.toLocalTime())} என்று எடுத்துக்கிட்டேன்)", tl = " (${clock(time.at.toLocalTime())} nu eduthukitten)", en = " (I took it as ${clock(time.at.toLocalTime())})") else ""

        val notes = mutableListOf<String>()
        val buttons = mutableListOf(
            KaiButton(pick(lang, ta = "ரத்து", tl = "Cancel", en = "Cancel"), KaiAction.CancelReminder(r.id)),
            KaiButton(pick(lang, ta = "மாற்று", tl = "Edit", en = "Edit"), KaiAction.EditReminder(r.id)),
        )
        // The reminder card: what, when, status.
        val whenLine = time.relative?.let { d ->
            pick(lang, ta = "${KaiReminderWords.duration(d, lang)} கழிச்சு", tl = "${KaiReminderWords.duration(d, lang)} kalichi", en = "${KaiReminderWords.duration(d, lang)} from now")
        } ?: whenText
        val lines = listOf(
            pick(lang, ta = "நினைவூட்டல்", tl = "Reminder", en = "Reminder"),
            r.title,
            pick(lang, ta = "எப்போ: ", tl = "When: ", en = "When: ") + whenLine,
            pick(lang, ta = "நிலை: ", tl = "Status: ", en = "Status: ") + if (saved.duplicate) pick(lang, ta = "ஏற்கனவே இருக்கு", tl = "Already set", en = "Already set") else pick(lang, ta = "வைக்கப்பட்டது", tl = "Scheduled", en = "Scheduled"),
        )
        when (saved.result) {
            ScheduleResult.NOTIFICATIONS_OFF -> {
                notes += pick(lang, ta = "Notifications off-ஆ இருக்கு — on பண்ணா தான் நினைவூட்டல் வரும்.", tl = "Notifications off-ah irukku — on pannina dhaan reminder varum.", en = "Notifications are off — turn them on so the reminder can show.")
                buttons += KaiButton(pick(lang, ta = "Notifications on", tl = "Notifications on pannu", en = "Turn on notifications"), KaiAction.OpenNotificationSettings, primary = true)
            }
            ScheduleResult.APPROXIMATE -> {
                notes += pick(lang, ta = "Exact alarm அனுமதி இல்லாததால கொஞ்சம் தாமதமாகலாம்.", tl = "Exact alarm permission illa, so konjam late-ah varalaam.", en = "Exact alarms aren't allowed, so it may ring a little late.")
                buttons += KaiButton(pick(lang, ta = "சரியான நேரத்துக்கு அனுமதி", tl = "Exact time allow pannu", en = "Allow exact time"), KaiAction.OpenAlarmSettings)
            }
            else -> Unit
        }
        // Android 14+ can stop Kai from appearing over the lock screen: say so (the notification still comes).
        if (!saved.duplicate && tools.fullScreenAllowed() == false) {
            notes += pick(lang,
                ta = "Lock screen-ல Kai full-screen-ஆ வர “Full-screen alerts” அனுமதி இல்ல — notification-ஆ வரும்.",
                tl = "Lock screen-la Kai full-screen-ah vara “Full-screen alerts” permission illa — notification-ah varum.",
                en = "“Full-screen alerts” aren't allowed, so on the lock screen Kai will come as a notification.")
            buttons += KaiButton(pick(lang, ta = "Full-screen அனுமதி", tl = "Full-screen allow pannu", en = "Allow full screen"), KaiAction.OpenFullScreenSettings)
        }
        // A call / message reminder for someone not in OwnerNote: offer to add the number.
        if (!saved.duplicate && r.person != null && r.phone == null && (r.action == ReminderAction.CALL || r.action == ReminderAction.MESSAGE)) {
            waiting = Waiting.NUMBER
            waitingKey = r.id
            notes += pick(lang,
                ta = "${r.person} contact OwnerNote-ல இல்ல. நம்பர் சொன்னா சேர்த்துடுறேன்.",
                tl = "${r.person} contact OwnerNote-la illa. Number sollunga, add panren.",
                en = "${r.person} isn't in your OwnerNote contacts. Tell me the number and I'll add it.")
        }
        return KaiTurn(ChatReply(text, KaiMood.REMINDER, ChatIntent.REMINDER_QUERY), KaiCard(lines, buttons, warning = notes.joinToString("\n").ifBlank { null }))
    }

    // ------------------------------------------------------------ list / cancel / complete / snooze / update

    enum class Op { CANCEL, COMPLETE, SNOOZE, UPDATE }

    private fun withTarget(target: ReminderTarget, op: Op, lang: KaiLang, minutes: Long, newTime: KaiWhen? = null, block: (KaiReminder) -> KaiTurn): KaiTurn {
        val open = tools.reminders()
        val found: List<KaiReminder> = when (target) {
            ReminderTarget.Last -> listOfNotNull(
                when (op) {
                    Op.COMPLETE, Op.SNOOZE -> tools.lastRang() ?: lastTouched?.let { id -> open.firstOrNull { it.id == id } }
                    else -> lastTouched?.let { id -> open.firstOrNull { it.id == id } } ?: tools.lastRang()
                },
            )
            is ReminderTarget.Matching -> KaiReminderSchedule.matching(open, target)
        }
        return when {
            found.isEmpty() -> say(lang, KaiMood.CLARIFY,
                ta = "அப்படி ஒரு நினைவூட்டல் இல்ல ஓனர்.", tl = "Owner, appadi oru reminder illa.", en = "I couldn't find that reminder, Owner.")
            found.size == 1 -> block(found.single())
            else -> {
                // Never act on a different reminder: the owner picks.
                val who = (target as? ReminderTarget.Matching)?.person
                val verb = when (op) {
                    Op.CANCEL -> pick(lang, ta = "ரத்து", tl = "cancel", en = "cancel")
                    Op.COMPLETE -> pick(lang, ta = "முடிச்சதா", tl = "done", en = "mark done")
                    Op.SNOOZE -> pick(lang, ta = "தள்ளி வைக்க", tl = "snooze", en = "snooze")
                    Op.UPDATE -> pick(lang, ta = "மாத்த", tl = "change", en = "change")
                }
                val key = newKey()
                // A change waits here with its new time until the owner picks which reminder.
                if (op == Op.UPDATE && newTime != null) pending[key] = Pending(key, ReminderDraft("", "", ReminderAction.TASK, null, null, newTime, ""), newTime)
                val buttons = found.take(6).map { r -> KaiButton("${shortWhen(r, lang)} · ${r.title}", KaiAction.PickReminder(key, r.id, op, minutes.takeIf { it > 0 } ?: 10L)) }
                KaiTurn(
                    ChatReply(pick(lang,
                        ta = "${who?.let { "$it-க்கு " } ?: ""}${found.size} நினைவூட்டல் இருக்கு. எதை $verb பண்ணணும்?",
                        tl = "${who?.let { "$it-ku " } ?: ""}${found.size} reminders irukku. Endha reminder $verb pannanum?",
                        en = "${found.size} reminders${who?.let { " for $it" } ?: ""}. Which one should I $verb?"), KaiMood.CLARIFY, ChatIntent.REMINDER_QUERY),
                    KaiCard(emptyList(), buttons),
                )
            }
        }
    }

    private fun cancel(r: KaiReminder, lang: KaiLang): KaiTurn {
        if (!tools.cancelReminder(r.id)) return gone(lang)
        tools.log("cancel reminder", "reminder engine", r.title, ActionStatus.CANCELLED, r.id)
        if (lastTouched == r.id) lastTouched = null
        return say(lang, KaiMood.NEUTRAL, ta = "“${r.title}” நினைவூட்டல் ரத்து பண்ணிட்டேன் ஓனர்.", tl = "Owner, “${r.title}” reminder cancel pannitten.", en = "Cancelled the “${r.title}” reminder, Owner.")
    }

    private fun complete(r: KaiReminder, lang: KaiLang): KaiTurn {
        if (!tools.completeReminder(r.id)) return gone(lang)
        tools.log("complete reminder", "reminder engine", r.title, ActionStatus.CONFIRMED, r.id)
        val repeats = r.recurrence.repeat != Repeat.ONCE
        return say(lang, KaiMood.SUCCESS,
            ta = "சரி ஓனர், “${r.title}” முடிஞ்சது." + if (repeats) " அடுத்த முறை மறுபடியும் நினைவூட்டுறேன்." else "",
            tl = "Seri Owner, “${r.title}” done." + if (repeats) " Adutha time thirumba remind panren." else "",
            en = "Marked “${r.title}” done, Owner." + if (repeats) " I'll remind you again next time." else "")
    }

    private fun snooze(r: KaiReminder, minutes: Long, lang: KaiLang): KaiTurn {
        val s = tools.snoozeReminder(r.id, minutes) ?: return gone(lang)
        lastTouched = s.id
        tools.log("snooze reminder", "reminder engine", "${r.title} +${minutes}m", ActionStatus.SCHEDULED, r.id)
        val at = clock(localOf(s.snoozedUntil ?: (nowMillis() + minutes * 60_000)).toLocalTime())
        val d = KaiReminderWords.duration(Duration.ofMinutes(minutes), lang)
        return say(lang, KaiMood.REMINDER,
            ta = "சரி ஓனர், $d கழிச்சு ($at) மறுபடியும் நினைவூட்டுறேன்.",
            tl = "Seri Owner, $d kalichu ($at) thirumba remind panren.",
            en = "Okay Owner, I'll remind you again in $d ($at).")
    }

    /** "Kumar call reminder-a 30 minutes-ku change pannu", "Adha tomorrow 10 AM-ku change pannu", "weekdays mattum". */
    private fun update(r: KaiReminder, w: KaiWhen, lang: KaiLang): KaiTurn {
        val z = zone()
        val oldTime = r.time ?: localOf(r.triggerAt).toLocalTime()
        val changed: KaiReminder = when {
            w.relative != null -> r.copy(triggerAt = nowMillis() + w.relative.toMillis(), time = null, recurrence = Recurrence.ONCE)
            w.recurrence.repeat != Repeat.ONCE -> {
                val t = if (Missing.TIME in w.missing) oldTime else w.at.toLocalTime()
                val rec = if (w.recurrence.repeat == Repeat.MONTHLY && w.recurrence.dayOfMonth == null) w.recurrence.copy(dayOfMonth = localOf(r.triggerAt).dayOfMonth) else w.recurrence
                val next = rec.nextAt(t, now()) ?: return gone(lang)
                r.copy(triggerAt = next.atZone(z).toInstant().toEpochMilli(), time = t, recurrence = rec)
            }
            else -> {
                val t = if (Missing.TIME in w.missing) oldTime else w.at.toLocalTime()
                val at = w.at.toLocalDate().atTime(t)
                if (!at.isAfter(now())) return say(lang, KaiMood.CLARIFY,
                    ta = "அந்த நேரம் ஏற்கனவே போயிடுச்சு ஓனர். வேற நேரம் சொல்லுங்க.", tl = "Owner, andha time already pochu. Vera time sollunga.", en = "That time has already passed, Owner. Tell me another time.")
                r.copy(triggerAt = at.atZone(z).toInstant().toEpochMilli(), time = t, recurrence = Recurrence.ONCE)
            }
        }
        val saved: ReminderSaved = tools.updateReminder(changed.copy(zone = z.id))
        if (saved.result == ScheduleResult.FAILED) return say(lang, KaiMood.ERROR, ta = "மாத்த முடியல ஓனர்.", tl = "Owner, maatha mudiyala.", en = "I couldn't change it, Owner.")
        lastTouched = r.id
        tools.log("update reminder", "reminder engine", "${r.title} → ${Instant.ofEpochMilli(saved.reminder.triggerAt)}", ActionStatus.SCHEDULED, r.id)
        val whenText = whenText(saved.reminder, null, lang)
        // "Seri Owner, Innaikku 5:00 PM-ku maathitten" — the same reminder moved, never a second one.
        return say(lang, KaiMood.REMINDER,
            ta = "சரி ஓனர், $whenText-க்கு மாத்திட்டேன் — “${r.title}”.",
            tl = "Seri Owner, $whenText-ku maathitten — “${r.title}”.",
            en = "Updated Owner. “${r.title}” reminder is now $whenText.")
    }

    /** "En reminders enna?", "Today's reminders?" — grouped by day, times and titles only. */
    private suspend fun list(todayOnly: Boolean, lang: KaiLang): KaiTurn {
        val today = now().toLocalDate()
        val mine = tools.reminders().map { it to localOf(it.snoozedUntil ?: it.triggerAt) }
            .filter { (_, at) -> !todayOnly || at.toLocalDate() == today }
            .sortedBy { it.second }
        val payment = runCatchingBlocking { books?.snapshot()?.reminders }.orEmpty()
            .filter { !it.isDone }
            .mapNotNull { r -> com.shopai.app.util.parseIsoToLocalDate(r.dueDate)?.let { r to it } }
            .filter { (_, d) -> d == today || (!todayOnly && !d.isBefore(today)) }
        if (mine.isEmpty() && payment.isEmpty()) {
            return say(lang, KaiMood.HAPPY,
                ta = if (todayOnly) "இன்னைக்கு நினைவூட்டல் எதுவும் இல்ல ஓனர்." else "நினைவூட்டல் எதுவும் இல்ல ஓனர்.",
                tl = if (todayOnly) "Owner, innaikku reminders edhuvum illa." else "Owner, pending reminders edhuvum illa.",
                en = if (todayOnly) "No reminders today, Owner." else "You have no pending reminders, Owner.")
        }
        lastTouched = mine.singleOrNull()?.first?.id ?: lastTouched
        val lines = mutableListOf<String>()
        mine.groupBy { it.second.toLocalDate() }.forEach { (day, items) ->
            lines += KaiFormat.date(day, lang, today).replaceFirstChar { it.titlecase(Locale.ROOT) }
            items.forEach { (r, at) -> lines += "  ${clock(at.toLocalTime())} — ${r.title}" + repeatTag(r, lang) }
        }
        if (payment.isNotEmpty()) {
            lines += pick(lang, ta = "பணம் நினைவூட்டல்கள்", tl = "Payment reminders", en = "Payment reminders")
            payment.take(8).forEach { (r, d) -> lines += "  ${KaiFormat.date(d, lang, today)} — ${r.title}" + (r.amount?.let { " (${KaiFormat.rupees(it)})" } ?: "") }
        }
        tools.log("list reminders", "reminder engine", "${mine.size} + ${payment.size}", ActionStatus.ANSWERED)
        return KaiTurn(
            ChatReply(pick(lang,
                ta = if (todayOnly) "இன்னைக்கு நினைவூட்டல்கள் ஓனர்:" else "உங்க நினைவூட்டல்கள் ஓனர்:",
                tl = if (todayOnly) "Owner, innaikku reminders:" else "Owner, unga reminders:",
                en = if (todayOnly) "Today's reminders, Owner:" else "Your reminders, Owner:"), KaiMood.REMINDER, ChatIntent.REMINDER_QUERY),
            KaiCard(lines, emptyList()),
        )
    }

    // ------------------------------------------------------------ time helpers

    /** A clock time from a short reply ("10", "10:30", "6 pm", "kaalaila 10"); the day part said before decides AM / PM. */
    private fun clockFrom(text: String, partWord: String?): LocalTime? {
        val trimmed = text.trim()
        val probe = if (Regex("""^\d{1,2}([:.]\d{2})?$""").matches(trimmed)) "${partWord ?: ""} at $trimmed"
            else "${partWord ?: ""} ${com.shopai.app.brain.tools.KaiReminderUnderstanding.spokenHour(trimmed)}"
        val w = KaiTime.parse(probe, now()) ?: return null
        if (w.relative != null || Missing.TIME in w.missing) return null
        return w.at.toLocalTime()
    }

    private fun withTime(base: KaiWhen, time: LocalTime, keepDate: Boolean = false): KaiWhen {
        val missing = base.missing - Missing.TIME
        return when {
            base.recurrence.repeat != Repeat.ONCE -> base.copy(at = base.recurrence.nextAt(time, now()) ?: nextAt(time), missing = missing)
            else -> {
                val date = if (keepDate || base.dayPart != null || base.at.toLocalDate() != now().toLocalDate() || base.missing.isNotEmpty()) base.at.toLocalDate() else now().toLocalDate()
                var at = date.atTime(time)
                // No day was said and the time has gone today: the next one.
                if (!at.isAfter(now()) && date == now().toLocalDate() && !keepDate) at = at.plusDays(1)
                base.copy(at = at, missing = missing, alreadyPassed = !at.isAfter(now()))
            }
        }
    }

    private fun nextAt(t: LocalTime): LocalDateTime = now().toLocalDate().atTime(t).let { if (it.isAfter(now())) it else it.plusDays(1) }

    private fun localOf(millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDateTime()

    private fun clock(t: LocalTime) = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

    private fun whenText(r: KaiReminder, said: KaiWhen?, lang: KaiLang): String {
        val at = localOf(r.triggerAt)
        val time = clock(at.toLocalTime())
        val part = said?.dayPart?.let { " " + partName(it.name, lang) } ?: ""
        val days = r.recurrence.days
        return when (r.recurrence.repeat) {
            Repeat.DAILY -> pick(lang, ta = "தினமும்$part $time", tl = "Daily$part $time", en = "every day at $time")
            Repeat.WEEKLY -> {
                val label = if (days == Recurrence.WEEKDAYS) pick(lang, ta = "வார நாட்களில் (திங்கள்–வெள்ளி)", tl = "Weekdays (Mon–Fri)", en = "every weekday (Mon–Fri)")
                else (if (lang == KaiLang.ENGLISH) "every " else "Every ") + days.sorted().joinToString(", ") { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) }
                "$label $time"
            }
            Repeat.MONTHLY -> pick(lang, ta = "மாதம் ${r.recurrence.dayOfMonth} தேதி $time", tl = "Every month ${ordinal(r.recurrence.dayOfMonth ?: 1)} $time", en = "every month on the ${ordinal(r.recurrence.dayOfMonth ?: 1)} at $time")
            Repeat.ONCE -> KaiFormat.date(at.toLocalDate(), lang, now().toLocalDate()).replaceFirstChar { it.titlecase(Locale.ROOT) } + "$part $time"
        }
    }

    private fun shortWhen(r: KaiReminder, lang: KaiLang): String {
        val at = localOf(r.snoozedUntil ?: r.triggerAt)
        return KaiFormat.date(at.toLocalDate(), lang, now().toLocalDate()) + " " + clock(at.toLocalTime())
    }

    private fun repeatTag(r: KaiReminder, lang: KaiLang) = when (r.recurrence.repeat) {
        Repeat.ONCE -> ""
        Repeat.DAILY -> pick(lang, ta = " (தினமும்)", tl = " (daily)", en = " (daily)")
        Repeat.WEEKLY -> pick(lang, ta = " (வாரம்)", tl = " (weekly)", en = " (weekly)")
        Repeat.MONTHLY -> pick(lang, ta = " (மாதம்)", tl = " (monthly)", en = " (monthly)")
    }

    private fun ordinal(d: Int) = "$d" + when {
        d in 11..13 -> "th"; d % 10 == 1 -> "st"; d % 10 == 2 -> "nd"; d % 10 == 3 -> "rd"; else -> "th"
    }

    private fun partWord(p: com.shopai.app.brain.tools.DayPart) = when (p) {
        com.shopai.app.brain.tools.DayPart.MORNING -> "morning"
        com.shopai.app.brain.tools.DayPart.AFTERNOON -> "afternoon"
        com.shopai.app.brain.tools.DayPart.EVENING -> "evening"
        com.shopai.app.brain.tools.DayPart.NIGHT -> "night"
    }

    private fun partName(name: String, lang: KaiLang) = when (name) {
        "MORNING" -> pick(lang, ta = "காலை", tl = "Morning", en = "morning")
        "AFTERNOON" -> pick(lang, ta = "மதியம்", tl = "Mathiyam", en = "afternoon")
        "EVENING" -> pick(lang, ta = "சாயங்காலம்", tl = "Evening", en = "evening")
        else -> pick(lang, ta = "இரவு", tl = "Night", en = "night")
    }

    private fun snoozeLabel(m: Long, lang: KaiLang) = if (m >= 60) pick(lang, ta = "1 மணி நேரம்", tl = "1 hour", en = "1 hour") else pick(lang, ta = "$m நிமிடம்", tl = "$m min", en = "$m min")

    private fun sourceName(c: ContactMatch, lang: KaiLang) = when (c.source) {
        com.shopai.app.brain.tools.ContactSource.CUSTOMER -> pick(lang, ta = "வாடிக்கையாளர்", tl = "Customer", en = "Customer")
        com.shopai.app.brain.tools.ContactSource.SUPPLIER -> pick(lang, ta = "சப்ளையர்", tl = "Supplier", en = "Supplier")
        com.shopai.app.brain.tools.ContactSource.PHONE -> pick(lang, ta = "போன் contact", tl = "Phone contact", en = "Phone contact")
    }

    // ------------------------------------------------------------ wording

    private fun byId(id: String) = tools.reminders().firstOrNull { it.id == id }

    private fun gone(lang: KaiLang) = say(lang, KaiMood.CLARIFY, ta = "அந்த நினைவூட்டல் இப்போ இல்ல ஓனர்.", tl = "Owner, andha reminder ippo illa.", en = "That reminder is no longer there, Owner.")

    private fun cancelButton(key: String, lang: KaiLang) = KaiButton(pick(lang, ta = "ரத்து", tl = "Cancel", en = "Cancel"), KaiAction.CancelRequest(key))

    private fun say(lang: KaiLang, mood: KaiMood, ta: String, tl: String, en: String) =
        KaiTurn(ChatReply(pick(lang, ta, tl, en), mood, ChatIntent.REMINDER_QUERY))

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }

    private fun newKey() = UUID.randomUUID().toString().take(8)

    private suspend fun <T> runCatchingBlocking(block: suspend () -> T): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
}
