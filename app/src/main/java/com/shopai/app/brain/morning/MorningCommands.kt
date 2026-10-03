package com.shopai.app.brain.morning

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What the owner asked Morning Work to do — the same for typed and spoken words. */
sealed interface MorningCommand {
    data object Open : MorningCommand
    data object Start : MorningCommand
    data object Next : MorningCommand
    data object Done : MorningCommand
    data object View : MorningCommand
    data object Summary : MorningCommand
    data object Confirm : MorningCommand
    data object Cancel : MorningCommand
    data class Skip(val reason: SkipReason?) : MorningCommand
    data class Call(val name: String?) : MorningCommand
    data class WhatsApp(val name: String?) : MorningCommand

    /** [at] null: the owner didn't say when — Kai asks, never guesses. */
    data class RemindLater(val at: LocalDateTime?) : MorningCommand

    /** Record a payment (to a supplier, or received from a customer — the party decides). */
    data class Payment(val name: String?, val amount: Double?, val amountUnclear: Boolean) : MorningCommand

    /** Just an amount — the answer to "evlo?" ([name] when a person was also heard). */
    data class Amount(val amount: Double?, val unclear: Boolean, val name: String? = null) : MorningCommand

    data class SwitchMode(val mode: ResponseMode) : MorningCommand
    data class Unknown(val text: String) : MorningCommand
}

/**
 * Kai's rule-based understanding for Morning Work: Tamil, Tanglish and
 * English, typed or from the phone's speech-to-text. No AI service.
 */
object MorningCommands {

    fun parse(text: String, now: LocalDateTime, knownNames: List<String> = emptyList()): MorningCommand {
        val raw = text.trim()
        if (raw.isEmpty()) return MorningCommand.Unknown(text)
        val unclearAudio = raw.contains("...") || raw.contains('…')
        val l = " " + normalize(raw) + " "

        fun has(vararg words: String) = words.any { w -> l.contains(" $w ") || (w.any { it.code > 0x0B80 } && l.contains(w)) }
        fun hasPart(vararg parts: String) = parts.any { l.contains(it) }

        // Response mode (explicit only).
        if (hasPart("type-la", "type la ", "text-la", "text la ", "text reply", "type reply", "type pannu", "டைப்", "எழுத்துல")) {
            return MorningCommand.SwitchMode(ResponseMode.TEXT)
        }
        if (hasPart("voice-la", "voice la ", "voice reply", "pesi sollu", "voice-la pesu", "குரல்ல", "பேசி சொல்லு")) {
            return MorningCommand.SwitchMode(ResponseMode.VOICE)
        }

        val trimmed = l.trim()
        if (trimmed in confirmWords) return MorningCommand.Confirm
        // "Confirm payment", "yes confirm pannu" — an explicit yes with no new amount in it.
        if ((trimmed.startsWith("confirm") || trimmed.startsWith("yes ")) && MorningAmounts.find(raw).isEmpty()) return MorningCommand.Confirm
        if (trimmed in cancelWords) return MorningCommand.Cancel

        // Skip (with an optional reason) — before "later", since "skip, will do later" is a skip.
        if (has("skip", "thavir", "thavirkalam", "தவிர்", "தவிர்க்கவும்") || hasPart("skip-")) {
            return MorningCommand.Skip(skipReason(l))
        }

        val mentionsMorning = hasPart("morning work", "morning task", "morning-work", "my morning", "morning start", "காலை வேலை")
        val wantsStart = has("start", "aarambi", "arambi", "ஆரம்பி", "தொடங்கு", "begin") || hasPart("let's start", "lets start", "first task", "muthal task", "முதல் வேலை")
        if (wantsStart) return MorningCommand.Start
        if (mentionsMorning || isTodayQuestion(l)) return MorningCommand.Open

        if (has("summary", "சுருக்கம்")) return MorningCommand.Summary

        val time = MorningTime.parse(l, now)
        val remindWords = has("remind", "reminder", "later", "apram", "appuram", "aprom", "kalichu", "nyabagam", "ஞாபகம்", "நினைவூட்டு", "அப்புறம்", "பிறகு") ||
            hasPart("remind ")
        if (remindWords) return MorningCommand.RemindLater(time)

        if (has("next", "adutha", "aduthu", "aduththu", "அடுத்து", "அடுத்த")) return MorningCommand.Next
        if (has("done", "mudinjiduchu", "mudinjathu", "mudinjidhu", "aachu", "achu", "finished", "completed", "ஆச்சு", "முடிஞ்சது", "முடிந்தது")) {
            return MorningCommand.Done
        }

        val name = knownName(raw, knownNames)
        if (has("call", "phone", "kal", "கால்", "போன்", "அழை")) return MorningCommand.Call(name)
        if (has("whatsapp", "whats", "watsapp", "msg", "message", "வாட்ஸ்அப்", "மெசேஜ்")) return MorningCommand.WhatsApp(name)

        val amounts = MorningAmounts.find(raw)
        val payWords = has("pay", "payment", "paid", "kudu", "kuduthen", "koduthen", "kuduthuten", "settle", "received", "collect", "collected",
            "vaangiten", "vangiten", "vaanginen", "vandhuchu", "vanthuchu", "கொடு", "கொடுத்தேன்", "செலுத்து", "வாங்கினேன்", "வந்துச்சு")
        if (payWords) {
            return MorningCommand.Payment(name, amounts.singleOrNull(), unclearAudio || amounts.size > 1)
        }

        if (has("view", "kaatu", "kattu", "show", "open", "paaru", "paru", "பார்", "காட்டு", "திற")) return MorningCommand.View

        // Only an amount ("12000", "₹12,000 rupees").
        if (amounts.isNotEmpty() && trimmed.replace(Regex("""[\d,.₹\s]|rs|rupees?|ரூபாய்|ayiram|thousand|k"""), "").isBlank()) {
            return MorningCommand.Amount(amounts.singleOrNull(), unclearAudio || amounts.size > 1)
        }
        if (unclearAudio && amounts.isNotEmpty()) return MorningCommand.Amount(null, true, name)
        // A bare time ("tomorrow morning", "Friday", "after lunch") postpones the current task.
        if (time != null) return MorningCommand.RemindLater(time)
        return MorningCommand.Unknown(text)
    }

    /**
     * Kai Chat and Pesunga hand over to Morning Work only for words that
     * clearly ask for it ("morning work ready pannu", "innaiku enna work
     * irukku?", "do my morning work"); their other questions ("innaiku enna
     * pending?") keep their existing answers. Start → go straight to the first task.
     */
    fun morningRequest(text: String): MorningCommand? {
        val l = " " + normalize(text) + " "
        val morning = listOf("morning work", "morning task", "morning-work", "my morning", "காலை வேலை").any { l.contains(it) }
        val today = listOf(" innaiku ", " innaikku ", " inniku ", " today ", " today's ", " todays ", " இன்னைக்கு ", " இன்று ").any { l.contains(it) }
        val work = listOf(" work ", " task", " important", " vendiyathu", " vendiyadhu", " velai ", " vela ", " வேலை ").any { l.contains(it) }
        if (!morning && !(today && work)) return null
        val start = listOf(" start ", "aarambi", "ஆரம்பி", "தொடங்கு", "let's start", "lets start").any { l.contains(it) }
        return if (start) MorningCommand.Start else MorningCommand.Open
    }

    /** Owner said yes, explicitly. Silence, "hmm" or anything unclear is never a yes. */
    private val confirmWords = setOf(
        "yes", "yes confirm", "confirm", "confirm pannu", "confirm pannunga", "confirm panni", "confirm it", "yes please",
        "sari", "sari confirm", "seri", "ok", "okay", "ok confirm", "okay confirm", "aama", "ama", "aamaa", "aam", "haan",
        "yes pannu", "ஆமா", "ஆம்", "சரி", "உறுதி", "உறுதி செய்", "ஓகே",
    )

    private val cancelWords = setOf(
        "no", "cancel", "cancel pannu", "cancel it", "venam", "vendam", "vendaam", "illa", "illai", "stop", "வேண்டாம்", "இல்லை", "ரத்து",
    )

    private fun isTodayQuestion(l: String): Boolean {
        val today = listOf(" innaiku ", " innaikku ", " inniku ", " today ", " today's ", " todays ", " இன்னைக்கு ", " இன்று ")
        val ask = listOf(
            " enna ", " work ", " task", " tasks ", " important", " pending ", " vela ", " velai ", " panna vendiyathu", " panna vendiyadhu",
            " what ", " என்ன ", " வேலை ",
        )
        return (today.any { l.contains(it) } && ask.any { l.contains(it) }) || l.contains("do my morning")
    }

    private fun skipReason(l: String): SkipReason? = when {
        listOf("already", "handled", "mudinjiduchu", "aachu", "ஏற்கனவே").any { l.contains(it) } -> SkipReason.ALREADY_HANDLED
        listOf("not needed", "no need", "thevai illa", "theva illa", "தேவை இல்ல").any { l.contains(it) } -> SkipReason.NOT_NEEDED
        listOf("wrong", "thappu", "tappu", "தப்பு", "தவறு").any { l.contains(it) } -> SkipReason.WRONG_INFORMATION
        listOf("later", "apram", "appuram", "அப்புறம்").any { l.contains(it) } -> SkipReason.WILL_DO_LATER
        else -> null
    }

    /** The longest known customer / supplier / product name in the words ("Kumar-ku", "kumarukku" included). */
    fun knownName(text: String, known: List<String>): String? {
        val lower = text.lowercase(Locale.ROOT)
        return known.filter { it.isNotBlank() }
            .sortedByDescending { it.length }
            .firstOrNull { name -> Regex("""(?<![\p{L}])${Regex.escape(name.lowercase(Locale.ROOT))}""").containsMatchIn(lower) }
            ?: known.filter { it.isNotBlank() }.firstOrNull { name ->
                // First word of a longer name ("ABC" for "ABC Traders").
                val first = name.trim().split(' ').first().lowercase(Locale.ROOT)
                first.length >= 3 && Regex("""(?<![\p{L}])${Regex.escape(first)}""").containsMatchIn(lower)
            }
    }

    /**
     * Language of the owner's words — Kai answers in the same one. Null when
     * the words don't tell ("yes", "next", "Call Kumar"): Kai keeps the
     * language already in use instead of switching on a short word.
     */
    fun language(text: String): KaiLang? {
        if (text.any { it in '\u0B80'..'\u0BFF' }) return KaiLang.TAMIL
        if (KaiLanguage.detect(text) == KaiLang.TANGLISH) return KaiLang.TANGLISH
        val words = normalize(text).split(' ').filter { w -> w.any { it.isLetter() } }
        return when {
            words.any { it in tanglishWords || it.endsWith("-ku") || it.endsWith("-kitta") } -> KaiLang.TANGLISH
            words.size >= 3 -> KaiLang.ENGLISH
            else -> null
        }
    }

    private val tanglishWords = setOf(
        "pannu", "pannunga", "kaatu", "kattu", "innaiku", "innaikku", "inniku", "anuppu", "sollu", "vendiyathu", "vendiyadhu", "irukku",
        "iruku", "la", "panna", "pannalama", "venam", "vendam", "illa", "adutha", "aduthu", "apram", "appuram", "kalichu", "naalaikku",
        "nalaiku", "mudinjiduchu", "aachu", "kudu", "vaangiten", "vangiten", "thavir", "paaru", "sari", "aama", "ama", "velai", "vela",
    )

    /** Lower case, "Kai," removed, punctuation spaced out; Tamil letters kept. */
    internal fun normalize(text: String): String {
        var t = text.lowercase(Locale.ROOT).replace('’', '\'')
        t = t.replace(Regex("""[?!,;:"()]"""), " ").replace(Regex("""\.{2,}|…"""), " ").replace(Regex("""(?<!\d)\.(?!\d)"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        t = t.removePrefix("hey ").removePrefix("hi ")
        for (p in listOf("kai ", "கை ", "kaai ")) if (t.startsWith(p)) t = t.removePrefix(p)
        return t.trim()
    }
}

/** Money amounts in what the owner said: "20000", "20,000", "₹20000", "20k", "20 ayiram", "1.5 lakh". */
object MorningAmounts {
    private val number = Regex("""(?<![\d.,/])(?:₹|rs\.?\s*)?(\d{1,3}(?:,\d{2,3})+|\d+(?:\.\d{1,2})?)\s*(k\b|ayiram\b|aayiram\b|thousand\b|ஆயிரம்|lakh\b|lakhs\b|latcham\b|லட்சம்)?""", RegexOption.IGNORE_CASE)

    fun find(text: String): List<Double> {
        val found = mutableListOf<Double>()
        // "30 minutes", "2 hours", "10 mani" are times, not money.
        val masked = text.replace(Regex("""\d+\s*(min|mins|minute|minutes|nimisham|nimidam|hour|hours|hr|hrs|mani|மணி|நிமிஷம்|நிமிடம்)""", RegexOption.IGNORE_CASE), " ")
        number.findAll(masked).forEach { m ->
            val digits = m.groupValues[1].replace(",", "")
            if (digits.length >= 9) return@forEach // phone / account numbers
            val v = digits.toDoubleOrNull() ?: return@forEach
            val scale = when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "k", "ayiram", "aayiram", "thousand", "ஆயிரம்" -> 1_000.0
                "lakh", "lakhs", "latcham", "லட்சம்" -> 1_00_000.0
                else -> 1.0
            }
            val amount = v * scale
            if (amount > 0) found += amount
        }
        return found.distinct()
    }
}

/**
 * "after 30 minutes", "1 hour later", "after lunch", "evening", "tomorrow
 * morning", "Friday" → an exact time, always from the real current time.
 * Nothing said about time → null (Kai asks).
 */
object MorningTime {
    val MORNING: LocalTime = LocalTime.of(9, 0)
    val AFTER_LUNCH: LocalTime = LocalTime.of(14, 0)
    val EVENING: LocalTime = LocalTime.of(18, 0)
    val WEEKDAY_TIME: LocalTime = LocalTime.of(10, 0)

    private val weekdays = mapOf(
        DayOfWeek.MONDAY to listOf("monday", "thingal", "திங்கள்"),
        DayOfWeek.TUESDAY to listOf("tuesday", "sevvai", "செவ்வாய்"),
        DayOfWeek.WEDNESDAY to listOf("wednesday", "budhan", "puthan", "புதன்"),
        DayOfWeek.THURSDAY to listOf("thursday", "vyazhan", "viyazhan", "வியாழன்"),
        DayOfWeek.FRIDAY to listOf("friday", "velli", "வெள்ளி"),
        DayOfWeek.SATURDAY to listOf("saturday", "sani", "சனி"),
        DayOfWeek.SUNDAY to listOf("sunday", "nyayiru", "gnayiru", "ஞாயிறு"),
    )

    private val smallNumbers = mapOf(
        "oru" to 1, "one" to 1, "an" to 1, "a" to 1, "rendu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4,
        "anju" to 5, "five" to 5, "pathu" to 10, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "ஒரு" to 1, "ரெண்டு" to 2, "மூணு" to 3,
    )

    fun parse(textLower: String, now: LocalDateTime): LocalDateTime? {
        val l = " ${textLower.lowercase(Locale.ROOT)} "
        // Half an hour.
        if (Regex("""half an hour|half hour|ara mani|arai mani|அரை மணி""").containsMatchIn(l)) return now.plusMinutes(30)
        Regex("""(?<![\p{L}\d])(\d+|[\p{L}]+)\s*(min|mins|minute|minutes|nimisham|nimidam|நிமிஷம்|நிமிடம்)(?![a-z])""").findAll(l).forEach { m ->
            amountOf(m.groupValues[1])?.let { return now.plusMinutes(it.toLong()) }
        }
        Regex("""(?<![\p{L}\d])(\d+|[\p{L}]+)\s*(hour|hours|hr|hrs|mani neram|மணி நேரம்)(?![a-z])""").findAll(l).forEach { m ->
            amountOf(m.groupValues[1])?.let { return now.plusHours(it.toLong()) }
        }
        val tomorrow = listOf(" tomorrow", "naalaikku", "nalaiku", "naalaiku", "நாளைக்கு", "நாளை").any { l.contains(it) }
        if (tomorrow) {
            val time = when {
                listOf("evening", "saayankaalam", "maalai", "மாலை").any { l.contains(it) } -> EVENING
                listOf("lunch", "afternoon", "madhiyam", "மதியம்").any { l.contains(it) } -> AFTER_LUNCH
                else -> MORNING
            }
            return now.toLocalDate().plusDays(1).atTime(time)
        }
        if (listOf("after lunch", "lunch apram", "lunch appuram", "lunch-ku apram", "lunch ku apram", "afternoon", "madhiyam", "மதியம்").any { l.contains(it) }) {
            return todayOrTomorrow(now, AFTER_LUNCH)
        }
        if (listOf("evening", "saayankaalam", "sayankalam", "maalai", "மாலை", "சாயங்காலம்").any { l.contains(it) }) {
            return todayOrTomorrow(now, EVENING)
        }
        for ((dow, names) in weekdays) {
            if (names.any { Regex("""(?<![\p{L}])${Regex.escape(it)}(?![a-z])""").containsMatchIn(l) }) {
                var d = now.toLocalDate().plusDays(1)
                while (d.dayOfWeek != dow) d = d.plusDays(1)
                return d.atTime(WEEKDAY_TIME)
            }
        }
        return null
    }

    private fun todayOrTomorrow(now: LocalDateTime, time: LocalTime): LocalDateTime {
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now.truncatedTo(ChronoUnit.MINUTES))) today else today.plusDays(1)
    }

    private fun amountOf(token: String): Int? = token.toIntOrNull()?.takeIf { it in 1..1440 } ?: smallNumbers[token]
}
