package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang

/**
 * One thing Kai says while a reminder rings — one **turn**, played as one continuous clip: the
 * statement ([parts] [0], its sentences synthesized together so they flow like one person talking)
 * and, after a short beat ([gapsMs]), the question.
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
 * What Kai keeps saying while a reminder rings, until the owner acts — the way a person in the shop
 * would remind, in spoken Tamil, gently but persistently:
 *
 *   "Owner... Praba-ku call panna vendiya neram aachu. Call pannidunga."  ~0.9 s  "Ippo pannalama?"
 *        … 6.5 s …  "Seekiram call pannidunga Owner."
 *        … 9 s …    "Owner, ippo pannalama?"
 *        … 11 s …   "Marakkadheenga Owner."
 *        … 13 s …   "Owner, idha ippo mudichidalaama?"
 *        … then the follow-ups again, longer apart — never the same line twice in a row.
 *
 * The statement's sentences are ONE request to the voice, so their rhythm and intonation run on
 * like speech (separately made sentences sound like three announcements). The name is said once.
 * The words follow the reminder's kind ([KaiReminderKind]) and get more direct on later attempts
 * (1 natural, 2 more direct, 3+ urgent — never angry); the attempts themselves are the reminder
 * engine's, unchanged. Pauses between turns are counted from the end of the previous turn.
 * Call / Done / Snooze end it at once with one short answer. Plain rules, no AI.
 *
 * The voice is the app's natural Sarvam voice, which reads Tamil **script**: for Tamil and
 * Tanglish owners every word is spoken in Tamil letters ([spoken]) — names too, with the spoken
 * case ending ("Kumar-ku" → "குமாருக்கு"), and hours as people say them ("நாலு மணி", "எட்டரை மணி").
 * The screen stays Tanglish. English stays English.
 */
object KaiUrgentVoiceScript {

    /** The beat before the question at the end of a turn (the joined clip adds ~0.1 s of the clips' own edges). */
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
     * "Paiyana school-la irundhu kootitu vara"), as a Tamil speaker says them. Words, never whole phrases.
     */
    private val spokenWords = listOf(
        "call screen" to "கால் ஸ்க்ரீன்", "call" to "கால்", "message" to "மெசேஜ்", "reminder" to "ரிமைண்டர்",
        "pending" to "பெண்டிங்", "payment" to "பேமெண்ட்", "collection" to "கலெக்ஷன்", "collect" to "கலெக்ட்", "done" to "டன்",
        "stock" to "ஸ்டாக்", "order" to "ஆர்டர்", "whatsapp" to "வாட்ஸ்அப்", "open" to "ஓபன்", "check" to "செக்",
        "pannanum" to "பண்ணணும்", "panna" to "பண்ண", "pannu" to "பண்ணு", "pannunga" to "பண்ணுங்க",
        "vaanganum" to "வாங்கணும்", "vaanga" to "வாங்க", "vanganum" to "வாங்கணும்", "kudukkanum" to "குடுக்கணும்", "kudukka" to "குடுக்க",
        "kudu" to "குடு", "anuppanum" to "அனுப்பணும்", "anuppa" to "அனுப்ப", "kitta" to "கிட்ட", "kaasu" to "காசு", "panam" to "பணம்",
        "mudikkanum" to "முடிக்கணும்", "paakkanum" to "பாக்கணும்", "vaikkanum" to "வைக்கணும்",
        // The owner's own life: family, school, health, home, errands.
        "paiyana" to "பையனை", "paiyan" to "பையன்", "ponna" to "பொண்ணை", "ponnu" to "பொண்ணு", "pasanga" to "பசங்க",
        "amma" to "அம்மா", "appa" to "அப்பா", "wife" to "வைஃப்", "son" to "பையன்", "daughter" to "பொண்ணு",
        "school" to "ஸ்கூல்", "college" to "காலேஜ்", "tuition" to "ட்யூஷன்", "office" to "ஆபீஸ்",
        "documents" to "டாக்குமெண்ட்ஸ்", "document" to "டாக்குமெண்ட்", "medicine" to "மெடிசின்", "tablet" to "டேப்லெட்",
        "maathirai" to "மாத்திரை", "doctor" to "டாக்டர்", "hospital" to "ஹாஸ்பிடல்", "gym" to "ஜிம்", "walking" to "வாக்கிங்",
        "gate" to "கேட்", "lock" to "லாக்", "car" to "கார்", "bike" to "பைக்", "service" to "சர்வீஸ்", "clean" to "க்ளீன்",
        "current" to "கரண்ட்", "bill" to "பில்", "pay" to "பே", "rent" to "ரென்ட்", "pickup" to "பிக்கப்", "drop" to "டிராப்",
        "irundhu" to "இருந்து", "kootitu" to "கூட்டிட்டு", "kooptu" to "கூப்டு", "eduthutu" to "எடுத்துட்டு", "kondu" to "கொண்டு",
        "vara" to "வர", "varanum" to "வரணும்", "poga" to "போக", "poganum" to "போகணும்", "kelamba" to "கிளம்ப", "kelambanum" to "கிளம்பணும்",
        "edukka" to "எடுக்க", "edukkanum" to "எடுக்கணும்", "katta" to "கட்ட", "kattanum" to "கட்டணும்", "time" to "டைம்",
    ).map { (en, ta) -> Regex("(?<![A-Za-z])" + Regex.escape(en) + "(?![A-Za-z])", RegexOption.IGNORE_CASE) to ta }

    private const val TA = "\\u0B80-\\u0BFF"

    /**
     * [text] ready for the Tamil voice: English / Tanglish words in Tamil letters, the [names] in Tamil
     * letters with the spoken case ending ("Kumar-ku" → "குமாருக்கு", "office-ku" → "ஆபீஸுக்கு").
     */
    fun spoken(text: String, lang: KaiLang, names: List<String> = emptyList()): String {
        if (lang == KaiLang.ENGLISH) return text
        var out = text.replace(Regex("(?<=[A-Za-z])-ku(?![A-Za-z])"), "-க்கு")
            .replace(Regex("(?<=[A-Za-z])-la(?![A-Za-z])"), "-ல")
            .replace(Regex("(?<=[A-Za-z])-nu(?![A-Za-z])"), "-னு")
            .replace(Regex("(?<=[A-Za-z])-a(?![A-Za-z])"), "-ஐ")
        for ((word, ta) in spokenWords) out = out.replace(word, ta)
        // A person's name in Tamil letters (a name the voice would otherwise read as English).
        for (word in names.flatMap { it.split(' ') }.filter { it.isNotBlank() }.distinct()) {
            KaiTamilVoice.tamil(word)?.let { ta -> out = out.replace(Regex("(?<![A-Za-z])" + Regex.escape(word) + "(?![A-Za-z])", RegexOption.IGNORE_CASE), ta) }
        }
        // The spoken dative on a Tamil word: "குமார்-க்கு" → "குமாருக்கு", "அம்மா-க்கு" → "அம்மாக்கு".
        out = Regex("([$TA]+)-க்கு").replace(out) { KaiTamilVoice.dative(it.groupValues[1]) }
        // The spoken object ending: "பையன்-ஐ" ("son-a") → "பையனை".
        out = Regex("([$TA]+)-ஐ").replace(out) { KaiTamilVoice.accusative(it.groupValues[1]) }
        // "ஸ்கூல்-ல" → "ஸ்கூல்ல": one word once both halves are Tamil.
        return out.replace(Regex("(?<=[$TA])-(?=[$TA])"), "")
    }

    /** The lines in order, as the voice says them: [0] is the opening turn, then the follow-ups. */
    fun lines(r: KaiReminder, lang: KaiLang = r.lang): List<VoiceLine> {
        val v = voiceLang(lang)
        val names = listOfNotNull(r.person)
        return written(r, v).map { l -> l.copy(text = spoken(l.text, v, names), parts = l.parts.map { spoken(it, v, names) }) }
    }

    /** One turn: the [statement] sentences spoken together, then a beat, then the [question]. */
    private fun turn(statement: List<String>, question: String, gesture: Boolean = true): VoiceLine {
        val said = statement.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return VoiceLine("$said $question", gesture, listOf(said, question), listOf(GAP_BEFORE_QUESTION_MS))
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

    /** "4 mani aachu" (Tamil: "நாலு மணி ஆச்சு", "எட்டரை மணி ஆச்சு") for a clock-time reminder; null for "in 10 minutes". */
    private fun clockWords(r: KaiReminder, lang: KaiLang): String? {
        val t = r.time ?: return null
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        val hm = if (t.minute == 0) null else "$h:" + t.minute.toString().padStart(2, '0')
        return when (lang) {
            KaiLang.TAMIL -> KaiTamilVoice.clock(t) + " ஆச்சு."
            KaiLang.TANGLISH -> (hm ?: "$h mani") + " aachu."
            KaiLang.ENGLISH -> "it's " + (hm ?: "$h o'clock") + "."
        }
    }

    /** The question that ends the first turn: what the owner would do next. */
    private fun question(r: KaiReminder, kind: ReminderKind, lang: KaiLang, urgent: Boolean): String {
        val task = r.task
        return when {
            r.action == ReminderAction.CALL && r.person != null ->
                if (urgent) pick(lang, ta = "இப்போவே பண்ணலாமா?", tl = "Ippove pannalama?", en = "Shall we call right now?")
                else pick(lang, ta = "இப்போ பண்ணலாமா?", tl = "Ippo pannalama?", en = "Shall we call now?")
            r.action == ReminderAction.MESSAGE && r.person != null -> pick(lang, ta = "இப்போ அனுப்பலாமா?", tl = "Ippo anuppalama?", en = "Shall we send it now?")
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
        val q = question(r, kind, lang, urgent = level >= 3)
        val call = r.action == ReminderAction.CALL && p != null
        val message = r.action == ReminderAction.MESSAGE && p != null
        val owner = pick(lang, ta = "ஓனர்", tl = "Owner", en = "Owner")
        val oo = pick(lang, ta = "ஓனர்... ", tl = "Owner... ", en = "Owner... ")

        val opening: VoiceLine = when {
            call -> when (level) {
                1 -> turn(listOf(
                    pick(lang, ta = "ஓனர்... $p-க்கு call பண்ண வேண்டிய நேரம் ஆச்சு.", tl = "Owner... $p-ku call panna vendiya neram aachu.", en = "Owner... it's time to call $p."),
                    pick(lang, ta = "Call பண்ணிடுங்க.", tl = "Call pannidunga.", en = "Please call.")), q)
                2 -> turn(listOf(
                    pick(lang, ta = "ஓனர், $p-க்கு இன்னும் call பண்ணலையே.", tl = "Owner, $p-ku innum call pannalaye.", en = "Owner, you still haven't called $p."),
                    pick(lang, ta = "இப்போ பண்ணிடுங்க.", tl = "Ippo pannidunga.", en = "Please call now.")), q)
                else -> turn(listOf(
                    pick(lang, ta = "ஓனர், $p-க்கு இன்னும் call பண்ணவே இல்லையே.", tl = "Owner, $p-ku innum call pannave illaye.", en = "Owner, the call to $p is still waiting."),
                    pick(lang, ta = "ரொம்ப நேரம் ஆச்சு, உடனே பண்ணிடுங்க.", tl = "Romba neram aachu, udane pannidunga.", en = "It's been a while, please call right away.")), q)
            }
            message -> when (level) {
                1 -> turn(listOf(
                    pick(lang, ta = "ஓனர்... $p-க்கு message அனுப்ப வேண்டிய நேரம் ஆச்சு.", tl = "Owner... $p-ku message anuppa vendiya neram aachu.", en = "Owner... it's time to message $p."),
                    pick(lang, ta = "Message அனுப்பிடுங்க.", tl = "Message anuppidunga.", en = "Please send it.")), q)
                2 -> turn(listOf(
                    pick(lang, ta = "ஓனர், $p-க்கு இன்னும் message அனுப்பலையே.", tl = "Owner, $p-ku innum message anuppalaye.", en = "Owner, you still haven't messaged $p."),
                    pick(lang, ta = "இப்போ அனுப்பிடுங்க.", tl = "Ippo anuppidunga.", en = "Please send it now.")), q)
                else -> turn(listOf(
                    pick(lang, ta = "ஓனர், $p-க்கு இன்னும் message அனுப்பவே இல்லையே.", tl = "Owner, $p-ku innum message anuppave illaye.", en = "Owner, the message to $p is still waiting."),
                    pick(lang, ta = "உடனே அனுப்பிடுங்க.", tl = "Udane anuppidunga.", en = "Please send it right away.")), q)
            }
            else -> {
                val amount = r.amount?.let { KaiUrgentWords.rupees(it) + " " } ?: ""
                // "Kumar-ku payment panna", "current bill pay panna", "paiyana school-la irundhu kootitu vara".
                val what: String? = when {
                    r.action == ReminderAction.PAYMENT && p != null -> pick(lang, ta = "$p-க்கு ${amount}payment பண்ண", tl = "$p-ku ${amount}payment panna", en = "pay $p $amount".trim())
                    r.action == ReminderAction.COLLECTION && p != null -> pick(lang, ta = "$p கிட்ட ${amount}collect பண்ண", tl = "$p kitta ${amount}collect panna", en = "collect ${amount}from $p")
                    else -> infinitive(task)?.takeIf { lang != KaiLang.ENGLISH }
                }
                // What it is called while it is still waiting ("Kumar payment", "gym poganum").
                val label = when {
                    r.action == ReminderAction.PAYMENT && p != null -> pick(lang, ta = "$p payment", tl = "$p payment", en = "the $p payment")
                    r.action == ReminderAction.COLLECTION && p != null -> pick(lang, ta = "$p collection", tl = "$p collection", en = "the $p collection")
                    lang == KaiLang.ENGLISH -> "“$task”"
                    else -> task
                }
                when (level) {
                    1 -> if (kind == ReminderKind.PERSONAL) {
                        // "Owner... nalu mani aachu. Paiyana school-la irundhu kootitu varanum." … "Kelambalama?"
                        val first = oo + (clockWords(r, lang) ?: pick(lang, ta = "நேரம் ஆச்சு.", tl = "neram aachu.", en = "it's time."))
                        val second = when {
                            lang == KaiLang.ENGLISH -> "Time for “$task”."
                            anumEnding.containsMatchIn(task) -> "${cap(task)}."
                            what != null -> "${cap(what)}num."
                            else -> pick(lang, ta = "${cap(task)} நேரம்.", tl = "${cap(task)} time.", en = "")
                        }
                        turn(listOf(first, second), q)
                    } else {
                        // "Owner... Kumar-ku payment panna vendiya time aachu. Oru thadava check pannidunga." … "Open pannalama?"
                        val first = when {
                            what != null -> pick(lang, ta = "ஓனர்... $what வேண்டிய நேரம் ஆச்சு.", tl = "Owner... $what vendiya time aachu.", en = "Owner... time to $what.")
                            lang == KaiLang.ENGLISH && r.action == ReminderAction.PAYMENT && p != null -> "Owner... time to pay $p."
                            else -> pick(lang, ta = "ஓனர்... ${low(task)} பத்தி ஞாபகப்படுத்த சொன்னீங்க.", tl = "Owner... ${low(task)} pathi nyabagapaduththa sonneenga.", en = "Owner... you asked me to remind you: “$task”.")
                        }
                        val doIt = if (kind == ReminderKind.PAYMENT && p != null) pick(lang, ta = "ஒரு தடவை check பண்ணிடுங்க.", tl = "Oru thadava check pannidunga.", en = "Please check it.")
                            else pick(lang, ta = "இப்போ பண்ணிடுங்க.", tl = "Ippo pannidunga.", en = "Please do it now.")
                        turn(listOf(first, doIt), q)
                    }
                    2 -> turn(listOf(
                        pick(lang, ta = "ஓனர், $label இன்னும் pending-ல இருக்கு.", tl = "Owner, $label innum pending-la irukku.", en = "Owner, $label is still pending."),
                        pick(lang, ta = "இப்போ பண்ணிடுங்க.", tl = "Ippo pannidunga.", en = "Please do it now.")), q)
                    else -> turn(listOf(
                        pick(lang, ta = "ஓனர், $label ரொம்ப நேரமா pending-ல இருக்கு.", tl = "Owner, $label romba neram-a pending-la irukku.", en = "Owner, $label has been waiting a while."),
                        pick(lang, ta = "உடனே பண்ணிடுங்க.", tl = "Udane pannidunga.", en = "Please do it right away.")), q)
                }
            }
        }

        // The follow-ups: short, no name again, more direct as the attempts go on.
        val v = when {
            call -> pick(lang, ta = "call பண்ணிடுங்க", tl = "call pannidunga", en = "call")
            message -> pick(lang, ta = "message அனுப்பிடுங்க", tl = "message anuppidunga", en = "send the message")
            else -> pick(lang, ta = "பண்ணிடுங்க", tl = "pannidunga", en = "do it")
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
     * Only between turns: inside a turn the question follows the statement after [GAP_BEFORE_QUESTION_MS].
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
    fun callAck(r: KaiReminder, lang: KaiLang = r.lang): String = voiceLang(lang).let { v -> spoken(callAckWritten(r, v), v, listOfNotNull(r.person)) }

    internal fun callAckWritten(r: KaiReminder, lang: KaiLang): String {
        val p = r.person?.takeIf { it.isNotBlank() }
        return if (p != null) pick(lang, ta = "சரி ஓனர், $p-க்கு call screen திறக்குறேன்.", tl = "Seri Owner, $p-ku call screen open pannuren.", en = "Okay Owner, opening the call screen for $p.")
        else pick(lang, ta = "சரி ஓனர், call screen திறக்குறேன்.", tl = "Seri Owner, call screen open pannuren.", en = "Okay Owner, opening the call screen.")
    }

    fun doneAck(lang: KaiLang) = pick(voiceLang(lang), ta = "சரி ஓனர்.", tl = "Seri Owner.", en = "Okay Owner.")

    fun snoozeAck(minutes: Long, lang: KaiLang) =
        pick(voiceLang(lang), ta = "சரி ஓனர், ${KaiTamilVoice.count(minutes)} நிமிஷம் கழிச்சு மறுபடியும் சொல்றேன்.", tl = "Seri Owner, $minutes minutes kalichu thirumba solren.", en = "Okay Owner, I'll remind you in $minutes minutes.")

    // ------------------------------------------------------------------ the screen's compact controls

    data class Controls(val call: String, val done: String, val snooze: String)

    fun controls(minutes: Long, lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> Controls(call = "இப்போ Call", done = "முடிஞ்சது", snooze = "$minutes நிமி கழிச்சு")
        KaiLang.TANGLISH, KaiLang.ENGLISH -> Controls(call = "Call now", done = "Done", snooze = "Snooze $minutes min")
    }
}
