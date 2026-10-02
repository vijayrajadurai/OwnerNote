package com.shopai.app.brain.tools

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Repeat { ONCE, DAILY, WEEKLY }

/**
 * When the owner wants something, as understood from their words, resolved
 * against the phone's local time. Kai says back anything it had to choose.
 */
data class KaiWhen(
    /** The first time it rings. */
    val at: LocalDateTime,
    val repeat: Repeat = Repeat.ONCE,
    val weekday: DayOfWeek? = null,
    /** No clock time was said (Kai used a default, e.g. 9 AM for "naalaikku"). */
    val timeAssumed: Boolean = false,
    /** "5 maniku" without AM/PM or a part of the day: Kai took the likely one. */
    val amPmAssumed: Boolean = false,
    /** The time said for today has already passed ("inniku 9 maniku" at 11 AM). */
    val alreadyPassed: Boolean = false,
)

/**
 * Natural-language time in Tamil, Tanglish and English — plain rules, no AI:
 * "10 minutes kalichu", "1 hour later", "naalaikku kaalaila 10 maniku",
 * "tomorrow 5 PM", "tonight", "after lunch", "daily kaalaila 10 maniku",
 * "every Monday", "next week". Returns null when no time is said.
 */
object KaiTime {

    private val numberWords = mapOf(
        "oru" to 1, "ore" to 1, "onnu" to 1, "one" to 1, "a" to 1, "an" to 1,
        "rendu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4,
        "anju" to 5, "five" to 5, "aaru" to 6, "six" to 6, "ezhu" to 7, "seven" to 7,
        "ettu" to 8, "eight" to 8, "onbadhu" to 9, "nine" to 9, "pathu" to 10, "ten" to 10,
        "padhinonnu" to 11, "eleven" to 11, "pannendu" to 12, "twelve" to 12,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    )
    private val numberAlt = numberWords.keys.sortedByDescending { it.length }.joinToString("|")

    private val relative = Regex(
        """(?<![\p{L}\d])(?:after\s+|in\s+)?(\d+|$numberAlt|half(?:\s+an)?|ara|arai|are)\s*""" +
            """(minutes?|mins?|min|nimisham|nimishathula|nimidam|nimidathula|hours?|hrs?|hr|mani\s*neram(?:\s*la)?|days?|naal)""" +
            """(?:\s*(?:later|kalichu|kalichi|kazhichu|kazhithu|kalichu|apram|aprom|appuram|after|time|la|le|il))?(?![\p{L}])""",
    )
    private val tamilRelative = Regex("""(\d+)\s*(நிமிடம்|நிமிஷம்|மணி\s*நேரம்)\s*(?:கழிச்சு|கழித்து|பிறகு)?""")

    private val daily = Regex("""(?<![\p{L}])(daily|every\s*day|everyday|dhinamum|thinamum|dinamum|dhinam|ovvoru\s*naalum|daily-um)(?![\p{L}])|தினமும்|ஒவ்வொரு\s*நாளும்""")

    private val weekdays: List<Pair<DayOfWeek, List<String>>> = listOf(
        DayOfWeek.MONDAY to listOf("monday", "mon", "thingal", "thingakizhamai", "thingal kizhamai", "திங்கள்"),
        DayOfWeek.TUESDAY to listOf("tuesday", "tue", "tues", "sevvai", "sevvaai", "sevvai kizhamai", "செவ்வாய்"),
        DayOfWeek.WEDNESDAY to listOf("wednesday", "wed", "budhan", "buthan", "budhan kizhamai", "புதன்"),
        DayOfWeek.THURSDAY to listOf("thursday", "thu", "thurs", "vyazhan", "viyazhan", "viyalan", "வியாழன்"),
        DayOfWeek.FRIDAY to listOf("friday", "fri", "velli", "vellikizhamai", "velli kizhamai", "வெள்ளி"),
        DayOfWeek.SATURDAY to listOf("saturday", "sat", "sani", "sanikizhamai", "sani kizhamai", "சனி"),
        DayOfWeek.SUNDAY to listOf("sunday", "sun", "nyayiru", "gnayiru", "nyaayiru", "ஞாயிறு"),
    )

    private enum class Part(val defaultHour: Int, val pm: Boolean?) { MORNING(9, false), AFTERNOON(14, true), EVENING(18, true), NIGHT(20, true) }

    fun parse(raw: String, now: LocalDateTime): KaiWhen? {
        val t = " " + raw.lowercase(Locale.ROOT).replace(Regex("""[?!,]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "

        // 1. "10 minutes kalichu", "1 hour later", "ara mani neram", "2 naal kalichu".
        relativeAmount(t)?.let { (amount, unit) ->
            val at = when {
                unit.startsWith("min") || unit.startsWith("nimi") || unit == "நிமிடம்" || unit == "நிமிஷம்" -> now.plusMinutes(amount)
                unit.startsWith("h") || unit.startsWith("mani") || unit.startsWith("மணி") -> now.plusMinutes(amount * 60)
                else -> now.plusDays(amount)
            }
            return KaiWhen(at.withSecond(0).withNano(0))
        }

        val part = partOfDay(t)
        val clock = clockTime(t)
        val repeatDaily = daily.containsMatchIn(t)
        val weekly = weeklyDay(t)
        val day = dayWord(t, now)

        // Time of day: the said clock time (AM/PM from the words), else the part of day's default.
        var amPmAssumed = false
        val time: LocalTime? = when {
            clock != null -> {
                val (h0, m, explicit) = clock
                val hour = when {
                    explicit != null -> h0 % 12 + if (explicit) 12 else 0
                    part?.pm == true -> if (h0 < 12) h0 + 12 else h0
                    part?.pm == false -> if (h0 == 12) 0 else h0
                    // No AM/PM, no part of day: shop hours — 7 to 11 morning, 12 noon, 1 to 6 evening.
                    h0 == 12 -> 12
                    h0 in 1..6 -> { amPmAssumed = true; h0 + 12 }
                    else -> { if (h0 in 7..11) amPmAssumed = false; h0 }
                }
                if (hour !in 0..23 || m !in 0..59) null else LocalTime.of(hour, m)
            }
            part != null -> LocalTime.of(part.defaultHour, 0)
            t.contains("after lunch") || t.contains("lunch kalichu") || t.contains("saapitta apram") -> LocalTime.of(14, 0)
            else -> null
        }
        val timeAssumed = clock == null

        // 2. Every day / every week.
        if (repeatDaily || weekly != null) {
            val at = time ?: LocalTime.of(9, 0)
            return if (weekly != null) {
                var date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(weekly))
                if (!date.atTime(at).isAfter(now)) date = date.plusWeeks(1)
                KaiWhen(date.atTime(at), Repeat.WEEKLY, weekly, timeAssumed = time == null || timeAssumed, amPmAssumed = amPmAssumed)
            } else {
                var date = now.toLocalDate()
                if (!date.atTime(at).isAfter(now)) date = date.plusDays(1)
                KaiWhen(date.atTime(at), Repeat.DAILY, timeAssumed = time == null || timeAssumed, amPmAssumed = amPmAssumed)
            }
        }

        // 3. A day ("naalaikku", "Friday", "next week") with or without a time.
        if (day != null) {
            val (date, explicitToday) = day
            val at = date.atTime(time ?: LocalTime.of(9, 0))
            val passed = explicitToday && !at.isAfter(now)
            return KaiWhen(at, timeAssumed = time == null || timeAssumed, amPmAssumed = amPmAssumed, alreadyPassed = passed)
        }

        // 4. Only a time: today if it is still ahead, else tomorrow. "tonight" stays today.
        if (time != null) {
            var at = now.toLocalDate().atTime(time)
            if (!at.isAfter(now)) at = at.plusDays(1)
            return KaiWhen(at, timeAssumed = timeAssumed, amPmAssumed = amPmAssumed)
        }
        return null
    }

    /** The text without its time words — what is left is the task ("Kumar-ku call pannanum"). */
    fun strip(raw: String): String {
        var s = " $raw "
        val patterns = listOf(
            relative, tamilRelative, daily,
            Regex("""(?i)(?<![\p{L}])(every|ovvoru)\s+\p{L}+(\s+kizhamai)?(?![\p{L}])"""),
            Regex("""(?i)(?<![\p{L}])(next week|adutha vaaram|next month|tonight|today|tomorrow|day after tomorrow|after lunch|morning|afternoon|evening|night|""" +
                """inniku|innaikku|innaiku|indru|naalaikku|naalaiku|nalaiku|naalai|naalanniku|nalanniku|kaalaila|kalaila|kaalaiyil|kaalai|kalai|""" +
                """madhiyam|mathiyam|madhiyanam|saayangalam|sayangalam|saayandhiram|maalai|iravu|raathiri|rathiri|nite)(?![\p{L}])"""),
            Regex("""(?i)(?<![\p{L}\d])(at\s+|@\s*)?\d{1,2}([:.]\d{2})?\s*(am|pm|a\.m\.?|p\.m\.?|mani(kku|ku|kki|ki)?|manikku|o'?\s*clock)(?![\p{L}])"""),
            Regex("""(?i)(?<![\p{L}])($numberAlt)\s+(mani(kku|ku|kki|ki)?|manikku)(?![\p{L}])"""),
            Regex("""\d{1,2}\s*மணிக்கு|இன்னைக்கு|இன்று|நாளைக்கு|நாளை|காலை|காலையில|மதியம்|சாயங்காலம்|மாலை|இரவு|ராத்திரி"""),
        )
        for (p in patterns) s = Regex(p.pattern, RegexOption.IGNORE_CASE).replace(s, " ")
        weekdays.flatMap { it.second }.forEach { w -> s = Regex("""(?i)(?<![\p{L}])${Regex.escape(w)}(?![\p{L}])""").replace(s, " ") }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    // ------------------------------------------------------------ parts

    private fun relativeAmount(t: String): Pair<Long, String>? {
        relative.find(t)?.let { m ->
            val n = m.groupValues[1].trim()
            val unit = m.groupValues[2].trim()
            val half = n.startsWith("half") || n == "ara" || n == "arai" || n == "are"
            val amount = when {
                half -> if (unit.startsWith("h") || unit.startsWith("mani")) return 30L to "min" else return null
                else -> n.toLongOrNull() ?: numberWords[n]?.toLong() ?: return null
            }
            // "1 mani" alone is a clock time ("1 maniku"); only "mani neram" is an hour count.
            if (unit.startsWith("mani") && !unit.contains("neram")) return null
            if (amount <= 0 || amount > 60 * 24 * 365) return null
            return amount to unit
        }
        tamilRelative.find(t)?.let { m -> return (m.groupValues[1].toLongOrNull() ?: return null) to m.groupValues[2] }
        return null
    }

    private fun partOfDay(t: String): Part? = when {
        Regex("""(?<![\p{L}])(tonight|night|iravu|raathiri|rathiri|nite)(?![\p{L}])|இரவு|ராத்திரி""").containsMatchIn(t) -> Part.NIGHT
        Regex("""(?<![\p{L}])(evening|saayangalam|sayangalam|saayandhiram|maalai|eve)(?![\p{L}])|சாயங்காலம்|மாலை""").containsMatchIn(t) -> Part.EVENING
        Regex("""(?<![\p{L}])(afternoon|madhiyam|mathiyam|madhiyanam|noon)(?![\p{L}])|மதியம்""").containsMatchIn(t) -> Part.AFTERNOON
        Regex("""(?<![\p{L}])(morning|kaalaila|kalaila|kaalaiyil|kaalai|kalai|kaathaala)(?![\p{L}])|காலை""").containsMatchIn(t) -> Part.MORNING
        else -> null
    }

    /** (hour, minute, pm?) — pm null when AM/PM wasn't said. */
    private fun clockTime(t: String): Triple<Int, Int, Boolean?>? {
        fun ampm(s: String?): Boolean? = when {
            s.isNullOrBlank() -> null
            s.startsWith("p") -> true
            s.startsWith("a") -> false
            else -> null
        }
        Regex("""(?<![\d.])(\d{1,2})[:.](\d{2})\s*(am|pm|a\.m\.?|p\.m\.?)?(?![\d])""").find(t)?.let { m ->
            return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), ampm(m.groupValues[3]))
        }
        Regex("""(?<![\d])(\d{1,2})\s*(am|pm|a\.m\.?|p\.m\.?)(?![\p{L}])""").find(t)?.let { m ->
            return Triple(m.groupValues[1].toInt(), 0, ampm(m.groupValues[2]))
        }
        Regex("""(?<![\d])(\d{1,2})\s*(?:mani(?:kku|ku|kki|ki)?|manikku|o'?\s*clock|மணிக்கு|மணி)(?!\s*neram)(?![\p{L}])""").find(t)?.let { m ->
            return Triple(m.groupValues[1].toInt(), 0, null)
        }
        Regex("""(?<![\p{L}])($numberAlt)\s+(?:mani(?:kku|ku|kki|ki)?|manikku)(?!\s*neram)(?![\p{L}])""").find(t)?.let { m ->
            return numberWords[m.groupValues[1]]?.takeIf { it in 1..12 }?.let { Triple(it, 0, null) }
        }
        Regex("""(?<![\p{L}])(?:at|@)\s*(\d{1,2})(?![\d:.])""").find(t)?.let { m ->
            return Triple(m.groupValues[1].toInt(), 0, null)
        }
        return null
    }

    private fun weeklyDay(t: String): DayOfWeek? {
        if (!Regex("""(?<![\p{L}])(every|ovvoru|each)(?![\p{L}])|ஒவ்வொரு""").containsMatchIn(t)) return null
        return weekdayIn(t)
    }

    private fun weekdayIn(t: String): DayOfWeek? = weekdays.firstOrNull { (_, names) ->
        names.any { Regex("""(?<![\p{L}])${Regex.escape(it)}(?![\p{L}])""").containsMatchIn(t) }
    }?.first

    /** (date, the owner explicitly said "today"). */
    private fun dayWord(t: String, now: LocalDateTime): Pair<java.time.LocalDate, Boolean>? {
        val today = now.toLocalDate()
        fun has(vararg w: String) = w.any { Regex("""(?<![\p{L}])${Regex.escape(it)}(?![\p{L}])""").containsMatchIn(t) }
        return when {
            has("day after tomorrow", "naalanniku", "nalanniku", "naalannaikku", "நாளன்னைக்கு") -> today.plusDays(2) to false
            has("tomorrow", "naalaikku", "naalaiku", "nalaiku", "nalaikku", "naalai", "நாளைக்கு", "நாளை") -> today.plusDays(1) to false
            has("today", "inniku", "innaikku", "innaiku", "inniki", "indru", "tonight", "இன்னைக்கு", "இன்று") -> today to true
            has("next week", "adutha vaaram", "aduththa vaaram", "adutha week", "அடுத்த வாரம்") -> today.plusWeeks(1) to false
            else -> weekdayIn(t)?.let { dow -> today.with(TemporalAdjusters.next(dow)) to false }
        }
    }
}
