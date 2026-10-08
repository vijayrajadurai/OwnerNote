package com.shopai.app.brain.tools

import com.shopai.app.util.DocumentDates
import com.shopai.app.util.YearlessPolicy
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Repeat { ONCE, DAILY, WEEKLY, MONTHLY }

/** How a reminder repeats. WEEKLY uses [days] (Mon–Fri for "weekdays"); MONTHLY uses [dayOfMonth]. */
data class Recurrence(val repeat: Repeat = Repeat.ONCE, val days: Set<DayOfWeek> = emptySet(), val dayOfMonth: Int? = null) {
    /** The first time on or after [after] (exclusive) at [time] that fits this schedule; null for ONCE. */
    fun nextAt(time: LocalTime, after: LocalDateTime): LocalDateTime? {
        var date = after.toLocalDate()
        repeat(800) {
            val candidate = date.atTime(time)
            if (candidate.isAfter(after) && fits(date)) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    fun fits(date: LocalDate): Boolean = when (repeat) {
        Repeat.ONCE -> true
        Repeat.DAILY -> true
        Repeat.WEEKLY -> date.dayOfWeek in days
        // "31st" in a 30-day month: the last day of that month.
        Repeat.MONTHLY -> dayOfMonth != null && date.dayOfMonth == minOf(dayOfMonth, date.lengthOfMonth())
    }

    companion object {
        val ONCE = Recurrence()
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}

/** A part of the day said without a clock time — Kai asks for the exact time, offering these. */
enum class DayPart(val choices: List<LocalTime>) {
    MORNING(listOf(LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(10, 0))),
    AFTERNOON(listOf(LocalTime.of(13, 0), LocalTime.of(14, 0), LocalTime.of(15, 0))),
    EVENING(listOf(LocalTime.of(17, 0), LocalTime.of(18, 0), LocalTime.of(19, 0))),
    NIGHT(listOf(LocalTime.of(20, 0), LocalTime.of(21, 0), LocalTime.of(22, 0))),
}

/** What the owner didn't say and Kai must ask (never invented). */
enum class Missing { TIME, DAY_OF_MONTH }

/**
 * When the owner wants something, resolved against the phone's local date
 * and time. [at] is the first time it rings; when [missing] is not empty it
 * is only a placeholder on the right day and Kai asks before scheduling.
 */
data class KaiWhen(
    val at: LocalDateTime,
    val recurrence: Recurrence = Recurrence.ONCE,
    /** "10 minutes later": exactly now + this. */
    val relative: Duration? = null,
    val dayPart: DayPart? = null,
    val missing: Set<Missing> = emptySet(),
    /** "5 maniku" without AM/PM or a part of the day: Kai took the likely one (and says so). */
    val amPmAssumed: Boolean = false,
    /** The day / time said has already passed ("inniku 9 maniku" at 11 AM, "10/09/2026"). */
    val alreadyPassed: Boolean = false,
    /** True when parsing found a calendar day, rather than only a clock time or duration. */
    val daySpecified: Boolean = false,
) {
    val repeat: Repeat get() = recurrence.repeat
    val needsTime: Boolean get() = Missing.TIME in missing
    val complete: Boolean get() = missing.isEmpty()
}

/**
 * Natural-language time in Tamil, Tanglish and English — plain rules, no AI.
 *
 *   relative:  "10 minutes later", "10 mins la", "innum 10 nimishathula", "30 seconds", "1 hour 30 minutes"
 *   absolute:  "tomorrow 10 AM", "naalaikku kaalaila 10 manikku", "tomorrow evening 6", "இன்று இரவு 8 மணிக்கு"
 *   dates:     today, tomorrow, day after tomorrow, next / this Monday, next week, next month 10th, 10th,
 *              "10 thethi", "10 October", "10/10/2026"
 *   repeats:   daily, every Monday, weekdays, every Monday and Thursday, every month 1st
 *
 * Returns null when no time / day / repeat is said at all.
 */
object KaiTime {

    private val numberWords = mapOf(
        "oru" to 1, "ore" to 1, "onnu" to 1, "one" to 1, "a" to 1, "an" to 1,
        "rendu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4,
        "anju" to 5, "five" to 5, "aaru" to 6, "six" to 6, "ezhu" to 7, "seven" to 7,
        "ettu" to 8, "eight" to 8, "onbadhu" to 9, "nine" to 9, "pathu" to 10, "ten" to 10,
        "padhinonnu" to 11, "eleven" to 11, "pannendu" to 12, "twelve" to 12,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50,
    )
    private val numberAlt = numberWords.keys.sortedByDescending { it.length }.joinToString("|")

    private const val B = """(?<![\p{L}\d])"""
    private const val E = """(?![\p{L}])"""

    // "nimisham", "nimishathula", "nimisathil", "nimidam"…; "mani neram", "mani nerathula".
    private val relativeUnit = """(seconds?|secs?|sec|minutes?|mins?|min|minits?|mints?|nimi(?:sh|s|d)\p{L}*|""" +
        """hours?|hrs?|hr|mani\s*nera\p{L}*|days?|naal|naatkal)"""
    private val relative = Regex(
        """$B(?:innum\s+|inum\s+|in\s+|after\s+)?(\d+(?:\.\d+)?|$numberAlt|half(?:\s+an)?|ara|arai)\s*$relativeUnit""" +
            """(?:\s*(?:later|kalichu|kalichi|kazhichu|kazhithu|apram|aprom|appuram|piragu|piragau|after|time|la|le|il))?$E""",
        RegexOption.IGNORE_CASE,
    )
    private val tamilRelative = Regex("""(\d+)\s*(வினாடி|நிமிடம்|நிமிஷம்|மணி\s*நேரம்)\s*(?:கழிச்சு|கழித்து|பிறகு|ல)?""")

    // "Every morning 8 manikku medicine", "ovvoru kaalaiyum": every day, at that part of the day.
    private val dailyWords = Regex("""$B(daily|every\s*day|everyday|dhinamum|thinamum|dinamum|dhinam|daily-um|ovvoru\s*naalum|naal\s*thorum|""" +
        """every\s*(?:morning|afternoon|evening|night)|ovvoru\s*(?:kaalaiyum|kalaiyum|saayangalamum|iravum|raathiriyum))$E|தினமும்|ஒவ்வொரு\s*நாளும்""", RegexOption.IGNORE_CASE)
    private val weekdaysWords = Regex("""$B(weekdays?|week\s*days|monday\s*(?:to|-|muthal)\s*friday|mon\s*(?:to|-)\s*fri)$E""", RegexOption.IGNORE_CASE)
    private val everyWords = Regex("""$B(every|ovvoru|each|ella)$E|ஒவ்வொரு""", RegexOption.IGNORE_CASE)
    private val monthlyWords = Regex("""$B(monthly|every\s*month|ovvoru\s*maasamum|ovvoru\s*masamum|maasa\s*maasam|masa\s*masam|month\s*thorum)$E|ஒவ்வொரு\s*மாதமும்|மாசா\s*மாசம்""", RegexOption.IGNORE_CASE)

    private val weekdays: List<Pair<DayOfWeek, List<String>>> = listOf(
        DayOfWeek.MONDAY to listOf("monday", "mon", "thingal", "thingakizhamai", "thingal kizhamai", "திங்கள்"),
        DayOfWeek.TUESDAY to listOf("tuesday", "tue", "tues", "sevvai", "sevvaai", "sevvai kizhamai", "செவ்வாய்"),
        DayOfWeek.WEDNESDAY to listOf("wednesday", "wed", "budhan", "buthan", "budhan kizhamai", "புதன்"),
        DayOfWeek.THURSDAY to listOf("thursday", "thu", "thurs", "vyazhan", "viyazhan", "viyalan", "வியாழன்"),
        DayOfWeek.FRIDAY to listOf("friday", "fri", "velli", "vellikizhamai", "velli kizhamai", "வெள்ளி"),
        DayOfWeek.SATURDAY to listOf("saturday", "sat", "sani", "sanikizhamai", "sani kizhamai", "சனி"),
        DayOfWeek.SUNDAY to listOf("sunday", "sun", "nyayiru", "gnayiru", "nyaayiru", "ஞாயிறு"),
    )

    private val partWords: List<Pair<DayPart, Regex>> = listOf(
        DayPart.NIGHT to Regex("""$B(tonight|night|iravu|raathiri|rathiri|raatri|nite)$E|இரவு|ராத்திரி""", RegexOption.IGNORE_CASE),
        DayPart.EVENING to Regex("""$B(evening|saayangalam|sayangalam|saayangaalam|saayandhiram|maalai|maalaila|malaila|eve)$E|சாயங்காலம்|மாலை""", RegexOption.IGNORE_CASE),
        DayPart.AFTERNOON to Regex("""$B(afternoon|madhiyam|mathiyam|madhiyanam|after\s*lunch|lunch\s*kalichu)$E|மதியம்""", RegexOption.IGNORE_CASE),
        DayPart.MORNING to Regex("""$B(morning|kaalaila|kalaila|kaalaiyil|kaalai|kalai|kaathaala)$E|காலை""", RegexOption.IGNORE_CASE),
    )

    private val ordinalDay = """(\d{1,2})\s*(?:st|nd|rd|th|thethi|thedhi|tharikku|தேதி)"""

    fun parse(raw: String, now: LocalDateTime): KaiWhen? {
        val t = " " + KaiSpokenWords.normalize(raw).lowercase(Locale.ROOT).replace(Regex("""[?!,]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        val today = now.toLocalDate()

        // 1. Relative: exactly now + the said duration ("1 hour 30 minutes" adds up).
        relativeDuration(t)?.let { d -> return KaiWhen(now.plus(d).withNano(0), relative = d) }

        val part = partWords.firstOrNull { it.second.containsMatchIn(t) }?.first
        val clock = clockTime(t, part)
        val amPmAssumed = clock?.second == true
        val time: LocalTime? = clock?.first

        // 2. Repeats.
        val recurrence: Recurrence? = when {
            monthlyWords.containsMatchIn(t) || (everyWords.containsMatchIn(t) && Regex("""$B(month|maasam|masam)$E""").containsMatchIn(t)) -> {
                val day = Regex(ordinalDay).find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 }
                Recurrence(Repeat.MONTHLY, dayOfMonth = day)
            }
            weekdaysWords.containsMatchIn(t) -> Recurrence(Repeat.WEEKLY, Recurrence.WEEKDAYS)
            everyWords.containsMatchIn(t) && weekdaysIn(t).isNotEmpty() -> Recurrence(Repeat.WEEKLY, weekdaysIn(t))
            dailyWords.containsMatchIn(t) -> Recurrence(Repeat.DAILY)
            else -> null
        }
        if (recurrence != null) {
            val missing = buildSet {
                if (time == null) add(Missing.TIME)
                if (recurrence.repeat == Repeat.MONTHLY && recurrence.dayOfMonth == null) add(Missing.DAY_OF_MONTH)
            }
            val at = recurrence.takeIf { Missing.DAY_OF_MONTH !in missing }
                ?.nextAt(time ?: part?.choices?.first() ?: LocalTime.of(9, 0), now)
                ?: today.plusDays(1).atTime(time ?: LocalTime.of(9, 0))
            return KaiWhen(at, recurrence, dayPart = part, missing = missing, amPmAssumed = amPmAssumed)
        }

        // 3. A day, with or without a time.
        val day = dayIn(t, today)
        if (day != null) {
            var date = day.first
            val fromWeekday = day.second == DaySource.WEEKDAY
            // "Friday 9 AM" said on Friday at 11 AM: next Friday.
            if (fromWeekday && time != null && date == today && !date.atTime(time).isAfter(now)) date = date.plusWeeks(1)
            val at = date.atTime(time ?: part?.choices?.first() ?: LocalTime.of(9, 0))
            val passed = date.isBefore(today) || (date == today && time != null && !at.isAfter(now))
            return KaiWhen(at, dayPart = part, missing = if (time == null) setOf(Missing.TIME) else emptySet(), amPmAssumed = amPmAssumed, alreadyPassed = passed, daySpecified = true)
        }

        // 4. Only a time: today if it is still ahead, else tomorrow.
        if (time != null) {
            var at = today.atTime(time)
            if (!at.isAfter(now)) at = at.plusDays(1)
            return KaiWhen(at, dayPart = part, amPmAssumed = amPmAssumed)
        }
        // 5. Only a part of the day ("evening remind pannu"): today's, the exact time is asked.
        if (part != null) {
            val first = part.choices.firstOrNull { today.atTime(it).isAfter(now) }
            val date = if (first != null) today else today.plusDays(1)
            return KaiWhen(date.atTime(first ?: part.choices.first()), dayPart = part, missing = setOf(Missing.TIME))
        }
        return null
    }

    /** The text without its time words — what is left is the task ("Kumar-ku call pannanum"). */
    fun strip(raw: String): String {
        var s = " $raw "
        val patterns = listOf(
            relative, tamilRelative, dailyWords, weekdaysWords, monthlyWords,
            Regex("""(?i)$B(every|ovvoru|each)\s+\p{L}+(\s+(and|&)\s+\p{L}+)*(\s+kizhamai)?$E"""),
            Regex("""(?i)$B(next|this|indha|intha|adutha|aduththa|varra|vara)\s+(week|month|maasam|vaaram)$E"""),
            Regex("""(?i)$B(next|this|indha|intha|adutha|aduththa|on)$E"""),
            Regex("""(?i)$B(tonight|today|tomorrow|day after tomorrow|after lunch|lunch kalichu|morning|afternoon|evening|night|""" +
                """inniku|innaikku|innaiku|innikku|indru|naalaikku|naalaiku|nalaiku|naalai|naalanniku|nalanniku|marunaal|""" +
                """kaalaila|kalaila|kaalaiyil|kaalai|kalai|madhiyam|mathiyam|madhiyanam|saayangalam|sayangalam|saayangaalam|saayandhiram|maalaila|maalai|""" +
                """iravu|raathiri|rathiri|raatri|nite)$E"""),
            Regex("""(?i)$B(at\s+|@\s*)?\d{1,2}([:.]\d{2})?\s*(am|pm|a\.m\.?|p\.m\.?|mani(kku|ku|kki|ki)?|manikku|o'?\s*clock)(\s*-\s*(ku|kku))?$E"""),
            // "Weekdays 8:30-ku office" — a clock time with minutes, with or without "-ku".
            Regex("""(?i)$B(at\s+|@\s*)?\d{1,2}:\d{2}(\s*-?\s*(ku|kku))?(?![\d\p{L}])"""),
            Regex("""(?i)$B($numberAlt)\s+(mani(kku|ku|kki|ki)?|manikku)$E"""),
            Regex("""(?i)$B$ordinalDay$E"""),
            Regex("""(?i)$B(at\s+)\d{1,2}$E"""),
            Regex("""\d{1,2}\s*மணிக்கு|இன்னைக்கு|இன்று|நாளைக்கு|நாளை|காலை|காலையில|மதியம்|சாயங்காலம்|மாலை|இரவு|ராத்திரி|தேதி"""),
        )
        for (p in patterns) s = Regex(p.pattern, RegexOption.IGNORE_CASE).replace(s, " ")
        DocumentDates.findMatch(s, LocalDate.now(), allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)
            ?.let { s = s.replace(it.text, " ") }
        weekdays.flatMap { it.second }.forEach { w -> s = Regex("""(?i)$B${Regex.escape(w)}$E""").replace(s, " ") }
        // A number left right after a day part ("evening 6") was its hour.
        s = Regex("""(?i)^\s*\d{1,2}(\s|$)""").replace(s, " ")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    // ------------------------------------------------------------ parts

    private fun relativeDuration(t: String): Duration? {
        var total = Duration.ZERO
        var found = false
        for (m in relative.findAll(t)) {
            val n = m.groupValues[1].trim()
            val unit = m.groupValues[2].trim()
            // "1 mani" alone is a clock time; only "mani neram" counts hours.
            val half = n.startsWith("half") || n == "ara" || n == "arai"
            val amount: Double = when {
                half -> 0.5
                else -> n.toDoubleOrNull() ?: numberWords[n]?.toDouble() ?: continue
            }
            if (amount <= 0) continue
            val seconds = when {
                unit.startsWith("s") -> amount
                unit.startsWith("min") || unit.startsWith("nimi") -> amount * 60
                unit.startsWith("h") || unit.startsWith("mani") -> amount * 3600
                else -> amount * 86400
            }
            if (half && seconds < 60) continue
            total = total.plusSeconds(seconds.toLong())
            found = true
        }
        tamilRelative.find(t)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@let
            val unit = m.groupValues[2]
            total = total.plusSeconds(when { unit.startsWith("வினா") -> n; unit.startsWith("மணி") -> n * 3600; else -> n * 60 })
            found = true
        }
        return total.takeIf { found && !it.isZero && it.toDays() <= 366 }
    }

    /** (time, AM/PM was assumed). AM/PM: said → part of day → shop hours (7–11 morning, 12 noon, 1–6 evening). */
    private fun clockTime(t: String, part: DayPart?): Pair<LocalTime, Boolean>? {
        var hour: Int
        var minute = 0
        var pm: Boolean? = null
        fun ampm(s: String?): Boolean? = when {
            s.isNullOrBlank() -> null
            s.startsWith("p") -> true
            s.startsWith("a") -> false
            else -> null
        }
        val partAlt = """morning|kaalaila|kalaila|kaalai|kalai|afternoon|madhiyam|mathiyam|evening|saayangalam|sayangalam|maalaila|maalai|night|tonight|iravu|raathiri|rathiri"""
        val m1 = Regex("""(?<![\d.])(\d{1,2})[:.](\d{2})\s*(am|pm|a\.m\.?|p\.m\.?)?(?![\d])""").find(t)
        val m2 = Regex("""(?<![\d.])(\d{1,2})\s*(am|pm|a\.m\.?|p\.m\.?)$E""").find(t)
        val m3 = Regex("""(?<![\d.])(\d{1,2})\s*(?:mani(?:kku|ku|kki|ki)?|manikku|o'?\s*clock|மணிக்கு|மணி)(?!\s*nera)$E""").find(t)
        val m4 = Regex("""$B($numberAlt)\s+(?:mani(?:kku|ku|kki|ki)?|manikku)(?!\s*nera)$E""").find(t)
        val m5 = Regex("""$B(?:$partAlt)\s+(\d{1,2})(?:[:.](\d{2}))?$E(?!\s*(?:minutes?|mins?|hours?|days?|naal|nimi|seconds?|%|rs|rupees))""").find(t)
        val m6 = Regex("""$B(?:at|@)\s*(\d{1,2})(?![\d:.])""").find(t)
        when {
            m1 != null -> { hour = m1.groupValues[1].toInt(); minute = m1.groupValues[2].toInt(); pm = ampm(m1.groupValues[3]) }
            m2 != null -> { hour = m2.groupValues[1].toInt(); pm = ampm(m2.groupValues[2]) }
            m3 != null -> hour = m3.groupValues[1].toInt()
            m4 != null -> hour = numberWords[m4.groupValues[1]]?.takeIf { it in 1..12 } ?: return null
            m5 != null -> { hour = m5.groupValues[1].toInt(); minute = m5.groupValues[2].toIntOrNull() ?: 0 }
            m6 != null -> hour = m6.groupValues[1].toInt()
            else -> return null
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        var assumed = false
        val h24 = when {
            pm != null -> if (hour > 12) hour else hour % 12 + if (pm) 12 else 0
            hour > 12 || hour == 0 -> hour
            part == DayPart.MORNING -> if (hour == 12) 0 else hour
            part == DayPart.AFTERNOON || part == DayPart.EVENING -> if (hour < 12) hour + 12 else hour
            part == DayPart.NIGHT -> when { hour == 12 -> 0; hour < 5 -> hour; else -> hour + 12 }
            hour == 12 -> 12
            hour in 1..6 -> { assumed = true; hour + 12 }
            else -> hour
        }
        return LocalTime.of(h24 % 24, minute) to assumed
    }

    private fun weekdaysIn(t: String): Set<DayOfWeek> = weekdays.filter { (_, names) ->
        names.any { Regex("""$B${Regex.escape(it)}$E""").containsMatchIn(t) }
    }.map { it.first }.toSet()

    private enum class DaySource { WORD, WEEKDAY, DATE }

    /** The day said, and how it was said. */
    private fun dayIn(t: String, today: LocalDate): Pair<LocalDate, DaySource>? {
        fun has(vararg w: String) = w.any { Regex("""$B${Regex.escape(it)}$E""").containsMatchIn(t) }
        val nextWord = has("next", "adutha", "aduththa", "varra", "vara")

        // "next month 10th" / "adutha maasam 10 thethi".
        if (has("next month", "adutha maasam", "aduththa maasam", "adutha month", "next maasam", "அடுத்த மாதம்", "அடுத்த மாசம்")) {
            val first = today.withDayOfMonth(1).plusMonths(1)
            val day = Regex(ordinalDay).find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d{1,2})$""").find(t.trim())?.groupValues?.get(1)?.toIntOrNull()
            return (day?.let { runCatching { first.withDayOfMonth(minOf(it, first.lengthOfMonth())) }.getOrNull() } ?: first) to DaySource.DATE
        }
        when {
            has("day after tomorrow", "naalanniku", "nalanniku", "naalannaikku", "naalai marunaal", "nalai marunaal", "marunaal", "நாளன்னைக்கு", "நாளை மறுநாள்") -> return today.plusDays(2) to DaySource.WORD
            has("tomorrow", "naalaikku", "naalaiku", "nalaiku", "nalaikku", "naalai", "நாளைக்கு", "நாளை") -> return today.plusDays(1) to DaySource.WORD
            has("today", "inniku", "innaikku", "innaiku", "innikku", "inniki", "indru", "tonight", "இன்னைக்கு", "இன்று") -> return today to DaySource.WORD
        }
        weekdaysIn(t).singleOrNull()?.let { dow ->
            // "this Friday" / "Friday" = the coming one (today counts); "next Monday" = the coming one after today.
            val date = if (nextWord) today.with(TemporalAdjusters.next(dow)) else today.with(TemporalAdjusters.nextOrSame(dow))
            return date to DaySource.WEEKDAY
        }
        if (has("next week", "adutha vaaram", "aduththa vaaram", "adutha week", "அடுத்த வாரம்")) return today.plusWeeks(1) to DaySource.WORD
        // "10/10/2026", "10-10-26", "10/10": day / month (/ year), read directly.
        Regex("""(?<![\d:.])(\d{1,2})\s*[/\-]\s*(\d{1,2})(?:\s*[/\-]\s*(\d{2,4}))?(?![\d])""").find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            runCatching { LocalDate.of(y ?: today.year, mo, d) }.getOrNull()?.let { date ->
                return (if (y == null && date.isBefore(today)) date.plusYears(1) else date) to DaySource.DATE
            }
        }
        // A written date with a month name: "10 October", "October 10" ("11 am" is a time, not the year).
        val withoutClock = t.replace(Regex("""(?<![\d.])\d{1,2}([:.]\d{2})?\s*(am|pm|a\.m\.?|p\.m\.?|mani\w*|manikku|o'?\s*clock|மணி\S*)$E"""), " ")
        DocumentDates.findMatch(withoutClock, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)
            ?.let { m -> return (if (m.yearAssumed && m.date.isBefore(today)) m.date.plusYears(1) else m.date) to DaySource.DATE }
        // "10th" / "10 thethi": this month's, or next month's if it has passed.
        Regex(ordinalDay).find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 }?.let { d ->
            val thisMonth = runCatching { today.withDayOfMonth(d) }.getOrNull()
            val date = if (thisMonth != null && !thisMonth.isBefore(today)) thisMonth else today.withDayOfMonth(1).plusMonths(1).let { it.withDayOfMonth(minOf(d, it.lengthOfMonth())) }
            return date to DaySource.DATE
        }
        return null
    }

}
