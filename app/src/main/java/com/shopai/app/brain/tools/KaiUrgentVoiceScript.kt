package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang

/**
 * One thing Kai says while a reminder rings — one **turn**: its [parts] (sentences) are spoken as
 * one continuous clip with short, exact silences between them ([gapsMs]), like a person who
 * pauses for breath, not a speaker restarting for every sentence.
 */
data class VoiceLine(
    /** The whole turn as one text (logs, tests, and the fallback when the parts can't be joined). */
    val text: String,
    /** The line asks the owner to act: Kai's open hand comes out with it. */
    val gesture: Boolean,
    val parts: List<String> = listOf(text),
    /** Silence after each part but the last, ms ([parts].size - 1 values). */
    val gapsMs: List<Long> = emptyList(),
)

/**
 * What Kai keeps saying while a reminder rings, until the owner acts — like a person gently
 * but persistently reminding, never a recording on repeat. The first turn says everything at
 * once, with only a breath between the sentences:
 *
 *   "Owner... Praba-ku call panna vendiya neram aachu." ~0.35 s "Call pannunga." ~0.9 s "Call pannalama?"
 *        … 6.5 s …  "Seekiram call pannunga Owner."
 *        … 9 s …    "Owner, call pannalama?"
 *        … 11 s …   "Marakkadheenga Owner."
 *        … 13 s …   "Owner, idha ippo mudichidalaama?"
 *        … then the follow-ups again, longer apart — never the same line twice in a row.
 *
 * The name is said once (the first sentence), not in every line. The words follow the reminder's
 * kind ([KaiReminderKind]: call, payment, personal, task…) and get more direct on later attempts
 * (attempt 1 natural, 2 more direct, 3+ urgent — never angry); the attempts themselves are the
 * reminder engine's, unchanged.
 *
 * Pauses between turns are counted from the end of the previous turn. Call / Done / Snooze end it at
 * once with one short answer. Plain rules, no AI.
 *
 * The voice is the app's natural Sarvam voice, which reads Tamil **script** (like every other Kai
 * reply — see KaiResponder): for Tamil and Tanglish owners the words are spoken in Tamil script
 * ([spoken]), so the screen stays Tanglish while the voice sounds like a person, not a machine
 * reading Latin letters. English stays English.
 */
object KaiUrgentVoiceScript {

    /** The breath after the first sentence of a turn (the joined clip adds ~0.1 s of the clips' own edges). */
    const val GAP_AFTER_FIRST_MS = 300L

    /** The beat before the question at the end of a turn. */
    const val GAP_BEFORE_QUESTION_MS = 850L

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) {
        KaiLang.TAMIL -> ta
        KaiLang.TANGLISH -> tl
        KaiLang.ENGLISH -> en
    }

    fun languageCode(lang: KaiLang) = if (lang == KaiLang.ENGLISH) "en-IN" else "ta-IN"

    /** The language the voice speaks: Tanglish is spoken as Tamil. */
    private fun voiceLang(lang: KaiLang) = if (lang == KaiLang.ENGLISH) KaiLang.ENGLISH else KaiLang.TAMIL

    /**
     * English and Tanglish words inside Tamil lines (also the owner's own task words, e.g. "Praba-ku call panna",
     * "Paiyana school-la irundhu kootitu vara"), as a Tamil speaker says them. Names and anything unknown stay
     * as they are. Words, never whole phrases.
     */
    private val spokenWords = listOf(
        "call screen" to "கால் ஸ்க்ரீன்", "call" to "கால்", "message" to "மெசேஜ்", "reminder" to "ரிமைண்டர்",
        "pending" to "பெண்டிங்", "payment" to "பேமெண்ட்", "collection" to "கலெக்ஷன்", "collect" to "கலெக்ட்", "done" to "டன்",
        "stock" to "ஸ்டாக்", "order" to "ஆர்டர்", "whatsapp" to "வாட்ஸ்அப்", "open" to "ஓபன்", "check" to "செக்",
        "pannanum" to "பண்ணணும்", "panna" to "பண்ண", "pannu" to "பண்ணு", "pannunga" to "பண்ணுங்க",
        "vaanganum" to "வாங்கணும்", "vaanga" to "வாங்க", "vanganum" to "வாங்கணும்", "kudukkanum" to "குடுக்கணும்",
        "kudu" to "குடு", "anuppanum" to "அனுப்பணும்", "kitta" to "கிட்ட", "kaasu" to "காசு", "panam" to "பணம்",
        // The owner's own life: family, school, health, home, errands.
        "paiyana" to "பையனை", "paiyan" to "பையன்", "ponna" to "பொண்ணை", "ponnu" to "பொண்ணு", "pasanga" to "பசங்க",
        "amma" to "அம்மா", "appa" to "அப்பா", "wife" to "வைஃப்", "son" to "சன்",
        "school" to "ஸ்கூல்", "college" to "காலேஜ்", "tuition" to "ட்யூஷன்", "office" to "ஆபீஸ்",
        "documents" to "டாக்குமெண்ட்ஸ்", "document" to "டாக்குமெண்ட்", "medicine" to "மெடிசின்", "tablet" to "டேப்லெட்",
        "maathirai" to "மாத்திரை", "doctor" to "டாக்டர்", "hospital" to "ஹாஸ்பிடல்", "gym" to "ஜிம்", "walking" to "வாக்கிங்",
        "gate" to "கேட்", "lock" to "லாக்", "car" to "கார்", "bike" to "பைக்", "service" to "சர்வீஸ்", "clean" to "க்ளீன்",
        "current" to "கரண்ட்", "bill" to "பில்", "pay" to "பே", "rent" to "ரென்ட்", "pickup" to "பிக்கப்", "drop" to "டிராப்",
        "irundhu" to "இருந்து", "kootitu" to "கூட்டிட்டு", "kooptu" to "கூப்டு", "eduthutu" to "எடுத்துட்டு", "kondu" to "கொண்டு",
        "vara" to "வர", "varanum" to "வரணும்", "poga" to "போக", "poganum" to "போகணும்", "kelamba" to "கிளம்ப", "kelambanum" to "கிளம்பணும்",
        "edukka" to "எடுக்க", "edukkanum" to "எடுக்கணும்", "katta" to "கட்ட", "kattanum" to "கட்டணும்", "vaanga" to "வாங்க",
    ).map { (en, ta) -> Regex("(?<![A-Za-z])" + Regex.escape(en) + "(?![A-Za-z])", RegexOption.IGNORE_CASE) to ta }

    /** [text] ready for the Tamil voice: the English words in it written in Tamil script. */
    fun spoken(text: String, lang: KaiLang): String {
        if (lang == KaiLang.ENGLISH) return text
        // "Praba-ku" → "Praba-க்கு", "school-la" → "school-ல" (the word stays, the case ending is Tamil).
        var out = text.replace(Regex("(?<=[A-Za-z])-ku(?![A-Za-z])"), "-க்கு")
            .replace(Regex("(?<=[A-Za-z])-la(?![A-Za-z])"), "-ல")
            .replace(Regex("(?<=[A-Za-z])-nu(?![A-Za-z])"), "-னு")
            .replace(Regex("(?<=[A-Za-z])-a(?![A-Za-z])"), "-ஐ")
        for ((word, ta) in spokenWords) out = out.replace(word, ta)
        // "school-ல" → "ஸ்கூல்-ல" reads as one word once both halves are Tamil.
        return out.replace(Regex("(?<=[\u0B80-\u0BFF])-(?=[\u0B80-\u0BFF])"), "")
    }

    /** The lines in order, as the voice says them: [0] is the opening turn, then the follow-ups. */
    fun lines(r: KaiReminder, lang: KaiLang = r.lang): List<VoiceLine> {
        val v = voiceLang(lang)
        return written(r, v).map { l -> l.copy(text = spoken(l.text, v), parts = l.parts.map { spoken(it, v) }) }
    }

    /** One turn of [parts] with the natural gaps: a breath after the first, a beat before the last (the question). */
    private fun turn(parts: List<String>, gesture: Boolean): VoiceLine {
        val p = parts.map { it.trim() }.filter { it.isNotEmpty() }
        val gaps = p.indices.drop(1).map { i -> if (i == p.lastIndex) GAP_BEFORE_QUESTION_MS else GAP_AFTER_FIRST_MS }
        return VoiceLine(p.joinToString(" "), gesture, p, gaps)
    }

    private fun one(text: String, gesture: Boolean) = VoiceLine(text, gesture)

    /** 1 natural, 2 more direct, 3 (and later) urgent. */
    private fun level(r: KaiReminder) = r.attemptCount.coerceAtLeast(1).coerceAtMost(3)

    private val anumEnding = Regex("""(?i)anum$""")
    private val infinitiveEnding = Regex("""(?i)(?<![\p{L}])(panna|vara|poga|edukka|katta|vaanga|kudukka|anuppa|mudikka|kelamba|paakka|paarkka|vaikka|kooppida|thara|seiya)$""")
    private val exerciseWords = Regex("""(?i)(?<![\p{L}])(gym|walking|walk|yoga|exercise|jogging|running)(?![\p{L}])""")
    private val goWords = Regex("""(?i)(?<![\p{L}])(vara|varanum|poga|poganum|kelamba|kelambanum|pickup|drop|kootitu|kooptu|kondu|pick\s*up)(?![\p{L}])""")
    private val takeWords = Regex("""(?i)(?<![\p{L}])(medicine|tablet|tablets|maathirai|marunthu|edukka|edukkanum)(?![\p{L}])""")

    /** "gym poganum" → "gym poga", "car clean pannanum" → "car clean panna"; already "… vara" stays; else null. */
    private fun infinitive(task: String): String? = when {
        anumEnding.containsMatchIn(task) -> task.dropLast(3)
        infinitiveEnding.containsMatchIn(task) -> task
        else -> null
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    private fun low(s: String) = s.replaceFirstChar { it.lowercase() }

    /** "4 mani aachu" / "8:30 aachu" for a clock-time reminder; null for "in 10 minutes". */
    private fun clockWords(r: KaiReminder, lang: KaiLang): String? {
        val t = r.time ?: return null
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        val hm = if (t.minute == 0) null else "$h:" + t.minute.toString().padStart(2, '0')
        return when (lang) {
            KaiLang.TAMIL -> (hm ?: "$h மணி") + " ஆச்சு."
            KaiLang.TANGLISH -> (hm ?: "$h mani") + " aachu."
            KaiLang.ENGLISH -> "it's " + (hm ?: "$h o'clock") + "."
        }
    }

    /** The question that ends the first turn: what the owner would do next. */
    private fun question(r: KaiReminder, kind: ReminderKind, lang: KaiLang): String {
        val task = r.task
        return when {
            r.action == ReminderAction.CALL && r.person != null -> pick(lang, ta = "Call பண்ணலாமா?", tl = "Call pannalama?", en = "Shall we call?")
            r.action == ReminderAction.MESSAGE && r.person != null -> pick(lang, ta = "Message அனுப்பலாமா?", tl = "Message anuppalama?", en = "Shall we send it?")
            kind == ReminderKind.PAYMENT -> pick(lang, ta = "Open பண்ணலாமா?", tl = "Open pannalama?", en = "Shall I open it?")
            exerciseWords.containsMatchIn(task) -> pick(lang, ta = "ரெடியா?", tl = "Ready-a?", en = "Ready?")
            goWords.containsMatchIn(task) -> pick(lang, ta = "கிளம்பலாமா?", tl = "Kelambalama?", en = "Shall we leave?")
            takeWords.containsMatchIn(task) -> pick(lang, ta = "எடுத்துக்கலாமா?", tl = "Eduthukkalama?", en = "Time to take it?")
            else -> pick(lang, ta = "இப்போ பண்ணலாமா?", tl = "Ippo pannalama?", en = "Shall we do it now?")
        }
    }

    /** The lines as written in [lang], before [spoken]. */
    internal fun written(r: KaiReminder, lang: KaiLang): List<VoiceLine> {
        val kind = KaiReminderKind.of(r)
        val level = level(r)
        val p = r.person?.takeIf { it.isNotBlank() }
        val task = r.task.trim().trimEnd('.', ' ')
        val q = question(r, kind, lang)
        val call = r.action == ReminderAction.CALL && p != null
        val message = r.action == ReminderAction.MESSAGE && p != null
        val owner = pick(lang, ta = "ஓனர்", tl = "Owner", en = "Owner")

        val opening: VoiceLine = when {
            call -> when (level) {
                1 -> turn(listOf(
                    pick(lang, ta = "ஓனர்... $p-க்கு call பண்ண வேண்டிய நேரம் ஆச்சு.", tl = "Owner... $p-ku call panna vendiya neram aachu.", en = "Owner... it's time to call $p."),
                    pick(lang, ta = "Call பண்ணுங்க.", tl = "Call pannunga.", en = "Please call."), q), true)
                2 -> turn(listOf(
                    pick(lang, ta = "ஓனர், $p-க்கு இன்னும் call பண்ணல.", tl = "Owner, $p-ku innum call pannala.", en = "Owner, you still haven't called $p."),
                    pick(lang, ta = "இப்போ call பண்ணுங்க.", tl = "Ippo call pannunga.", en = "Please call now."), q), true)
                else -> turn(listOf(
                    pick(lang, ta = "ஓனர், $p call ரொம்ப நேரமா pending-ல இருக்கு.", tl = "Owner, $p call romba neram-a pending-la irukku.", en = "Owner, the call to $p has been waiting a while."),
                    pick(lang, ta = "உடனே call பண்ணுங்க.", tl = "Udane call pannunga.", en = "Please call right away."),
                    pick(lang, ta = "இப்போவே call பண்ணலாமா?", tl = "Ippove call pannalama?", en = "Shall we call now?")), true)
            }
            message -> when (level) {
                1 -> turn(listOf(
                    pick(lang, ta = "ஓனர்... $p-க்கு message அனுப்ப வேண்டிய நேரம் ஆச்சு.", tl = "Owner... $p-ku message panna vendiya neram aachu.", en = "Owner... it's time to message $p."),
                    pick(lang, ta = "Message அனுப்புங்க.", tl = "Message anuppunga.", en = "Please send it."), q), true)
                2 -> turn(listOf(
                    pick(lang, ta = "ஓனர், $p-க்கு இன்னும் message அனுப்பல.", tl = "Owner, $p-ku innum message anuppala.", en = "Owner, you still haven't messaged $p."),
                    pick(lang, ta = "இப்போ அனுப்புங்க.", tl = "Ippo anuppunga.", en = "Please send it now."), q), true)
                else -> turn(listOf(
                    pick(lang, ta = "ஓனர், $p message ரொம்ப நேரமா pending-ல இருக்கு.", tl = "Owner, $p message romba neram-a pending-la irukku.", en = "Owner, the message to $p has been waiting a while."),
                    pick(lang, ta = "உடனே அனுப்புங்க.", tl = "Udane anuppunga.", en = "Please send it right away."), q), true)
            }
            else -> {
                // "Kumar-ku payment panna", "current bill pay panna", "paiyana school-la irundhu kootitu vara".
                val amount = r.amount?.let { KaiUrgentWords.rupees(it) + " " } ?: ""
                val what: String? = when {
                    r.action == ReminderAction.PAYMENT && p != null -> pick(lang, ta = "$p-க்கு ${amount}payment பண்ண", tl = "$p-ku ${amount}payment panna", en = "pay $p $amount".trim())
                    r.action == ReminderAction.COLLECTION && p != null -> pick(lang, ta = "$p கிட்ட ${amount}collect பண்ண", tl = "$p kitta ${amount}collect panna", en = "collect ${amount}from $p")
                    else -> infinitive(task)?.let { if (lang == KaiLang.ENGLISH) null else it }
                }
                // What it is called when it is still waiting ("Kumar payment", "gym poganum").
                val label = when {
                    r.action == ReminderAction.PAYMENT && p != null -> pick(lang, ta = "$p payment", tl = "$p payment", en = "the $p payment")
                    r.action == ReminderAction.COLLECTION && p != null -> pick(lang, ta = "$p collection", tl = "$p collection", en = "the $p collection")
                    lang == KaiLang.ENGLISH -> "“$task”"
                    else -> task
                }
                val doIt = if (kind == ReminderKind.PAYMENT && p != null) pick(lang, ta = "இதை check பண்ணுங்க.", tl = "Idha check pannunga.", en = "Please check it.")
                    else pick(lang, ta = "இதை இப்போ பண்ணுங்க.", tl = "Idha ippo pannunga.", en = "Please do it now.")
                when (level) {
                    1 -> {
                        val clock = clockWords(r, lang)
                        if (kind == ReminderKind.PERSONAL) {
                            // "Owner... 4 mani aachu." "Paiyana school-la irundhu kootitu vara vendiya neram." "Kelambalama?"
                            val first = pick(lang, ta = "ஓனர்... ", tl = "Owner... ", en = "Owner... ") +
                                (clock ?: pick(lang, ta = "நேரம் ஆச்சு.", tl = "neram aachu.", en = "it's time."))
                            val second = when {
                                lang == KaiLang.ENGLISH -> "Time for “$task”."
                                anumEnding.containsMatchIn(task) -> pick(lang, ta = "${cap(task)}-னு நினைவூட்டல்.", tl = "${cap(task)}-nu reminder.", en = "")
                                what != null -> pick(lang, ta = "${cap(what)} வேண்டிய நேரம்.", tl = "${cap(what)} vendiya neram.", en = "")
                                else -> pick(lang, ta = "${cap(task)} நேரம்.", tl = "${cap(task)} neram.", en = "")
                            }
                            turn(listOf(first, second, q), true)
                        } else {
                            // "Owner... Kumar-ku payment panna vendiya time aachu." "Idha check pannunga." "Open pannalama?"
                            val first = when {
                                what != null -> pick(lang, ta = "ஓனர்... $what வேண்டிய நேரம் ஆச்சு.", tl = "Owner... $what vendiya time aachu.", en = "Owner... time to $what.")
                                lang == KaiLang.ENGLISH && r.action == ReminderAction.PAYMENT && p != null -> "Owner... time to pay $p."
                                else -> pick(lang, ta = "ஓனர்... ${low(task)} — நீங்க நினைவூட்ட சொன்னது.", tl = "Owner... ${low(task)}-nu remind panna sonneenga.", en = "Owner... you asked me to remind you: “$task”.")
                            }
                            turn(listOf(first, doIt, q), true)
                        }
                    }
                    2 -> turn(listOf(
                        pick(lang, ta = "ஓனர், $label இன்னும் pending-ல இருக்கு.", tl = "Owner, $label innum pending-la irukku.", en = "Owner, $label is still pending."),
                        pick(lang, ta = "இப்போ பண்ணுங்க.", tl = "Ippo pannunga.", en = "Please do it now."), q), true)
                    else -> turn(listOf(
                        pick(lang, ta = "ஓனர், $label ரொம்ப நேரமா pending-ல இருக்கு.", tl = "Owner, $label romba neram-a pending-la irukku.", en = "Owner, $label has been waiting a while."),
                        pick(lang, ta = "உடனே பண்ணுங்க.", tl = "Udane pannunga.", en = "Please do it right away."), q), true)
                }
            }
        }

        // The follow-ups: short, no name again, more direct as the attempts go on.
        val v = when {
            call -> pick(lang, ta = "call பண்ணுங்க", tl = "call pannunga", en = "call")
            message -> pick(lang, ta = "message அனுப்புங்க", tl = "message anuppunga", en = "send the message")
            else -> pick(lang, ta = "பண்ணுங்க", tl = "pannunga", en = "do it")
        }
        val ask = "$owner, ${low(q)}"
        val followUps = when (level) {
            1 -> listOf(
                one(pick(lang, ta = "சீக்கிரம் $v ஓனர்.", tl = "Seekiram $v Owner.", en = "Let's $v soon, Owner."), false),
                one(ask, true),
                one(pick(lang, ta = "மறக்காதீங்க ஓனர்.", tl = "Marakkadheenga Owner.", en = "Don't forget, Owner."), false),
                one(pick(lang, ta = "ஓனர், இதை இப்போ முடிச்சிடலாமா?", tl = "Owner, idha ippo mudichidalaama?", en = "Owner, shall we finish this now?"), true),
            )
            2 -> listOf(
                one(pick(lang, ta = "ஓனர், இப்போ $v.", tl = "Owner, ippo $v.", en = "Owner, please $v now."), true),
                one(pick(lang, ta = "கொஞ்சம் சீக்கிரம் ஓனர்.", tl = "Konjam seekiram Owner.", en = "A little quicker, Owner."), false),
                one(ask, true),
                one(pick(lang, ta = "மறக்காதீங்க ஓனர்.", tl = "Marakkadheenga Owner.", en = "Don't forget, Owner."), false),
            )
            else -> listOf(
                one(pick(lang, ta = "ஓனர், உடனே $v.", tl = "Owner, udane $v.", en = "Owner, please $v right away."), true),
                one(pick(lang, ta = "ரொம்ப லேட் ஆகுது ஓனர்.", tl = "Romba late aagudhu Owner.", en = "It's getting late, Owner."), false),
                one(ask, true),
                one(pick(lang, ta = "ஓனர், இதை இப்போவே முடிச்சிடுங்க.", tl = "Owner, idha ippove mudichidunga.", en = "Owner, please finish this now."), true),
            )
        }
        return listOf(opening) + followUps
    }

    /**
     * Everything Kai may say for this ring and the next, to fetch ahead of time: this attempt's sentences,
     * the next attempt's opening sentences, and the three short answers.
     */
    fun prefetchTexts(r: KaiReminder, lang: KaiLang = r.lang): List<String> {
        val attempt = r.attemptCount.coerceAtLeast(1)
        val next = r.copy(attemptCount = minOf(attempt + 1, r.maxAttempts))
        // Each sentence of a turn is kept on its own (the turn is joined from them when it plays).
        return (lines(r.copy(attemptCount = attempt), lang).flatMap { it.parts } + lines(next, lang).first().parts +
            callAck(r, lang) + doneAck(lang) + snoozeAck(KaiReminderFlow.SNOOZE_MINUTES, lang)).distinct()
    }

    /** Which line the [n]th turn is: the opening once, then the follow-ups in a cycle. */
    fun lineAt(n: Int, size: Int): Int = if (n < size) n else 1 + (n - 1) % (size - 1)

    /**
     * The pause before the [n]th turn (after the previous one ended), ms — longer as it goes on, capped.
     * Only between turns: the sentences inside a turn are [GAP_AFTER_FIRST_MS] / [GAP_BEFORE_QUESTION_MS] apart.
     */
    fun pauseBefore(n: Int): Long = when (n) {
        0 -> 0L
        1 -> 6_500L
        2 -> 9_000L
        3 -> 11_000L
        4 -> 13_000L
        5 -> 15_000L
        else -> 18_000L
    }

    // ------------------------------------------------------------------ the owner acted: one short answer

    /** Call Now — before the dialer opens. Never "called": only that the call screen is opening. */
    fun callAck(r: KaiReminder, lang: KaiLang = r.lang): String = voiceLang(lang).let { v -> spoken(callAckWritten(r, v), v) }

    internal fun callAckWritten(r: KaiReminder, lang: KaiLang): String {
        val p = r.person?.takeIf { it.isNotBlank() }
        return if (p != null) pick(lang, ta = "சரி ஓனர், $p-க்கு call screen திறக்குறேன்.", tl = "Seri Owner, $p-ku call screen open pannuren.", en = "Okay Owner, opening the call screen for $p.")
        else pick(lang, ta = "சரி ஓனர், call screen திறக்குறேன்.", tl = "Seri Owner, call screen open pannuren.", en = "Okay Owner, opening the call screen.")
    }

    fun doneAck(lang: KaiLang) = pick(voiceLang(lang), ta = "சரி ஓனர்.", tl = "Seri Owner.", en = "Okay Owner.")

    fun snoozeAck(minutes: Long, lang: KaiLang) =
        pick(voiceLang(lang), ta = "சரி ஓனர், $minutes நிமிஷம் கழிச்சு நினைவூட்டுறேன்.", tl = "Seri Owner, $minutes minutes-ku remind pannuren.", en = "Okay Owner, I'll remind you in $minutes minutes.")

    // ------------------------------------------------------------------ the screen's compact controls

    data class Controls(val call: String, val done: String, val snooze: String)

    fun controls(minutes: Long, lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> Controls(call = "இப்போ Call", done = "முடிஞ்சது", snooze = "$minutes நிமி கழிச்சு")
        KaiLang.TANGLISH, KaiLang.ENGLISH -> Controls(call = "Call now", done = "Done", snooze = "Snooze $minutes min")
    }
}
