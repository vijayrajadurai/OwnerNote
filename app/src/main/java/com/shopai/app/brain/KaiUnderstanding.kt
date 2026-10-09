package com.shopai.app.brain

import com.shopai.app.data.model.ParsedTransaction
import com.shopai.app.util.AmountInWords
import com.shopai.app.util.DocumentDates
import com.shopai.app.util.YearlessPolicy
import java.time.LocalDate
import java.util.Locale

/** Money the owner will receive (a customer owes) or has to pay (owes a supplier). */
enum class Direction { RECEIVABLE, PAYABLE }

/** What the owner meant. Facts are only what the words actually say — never guessed. */
sealed interface KaiIntent {
    /** Record money owed. Any null field is missing and must be asked, never assumed. */
    data class Record(
        val person: String?,
        val amount: Double?,
        val direction: Direction?,
        val dueDate: LocalDate?,
        /** Two or more different amounts were said: which one is unclear. */
        val amountAmbiguous: Boolean = false,
    ) : KaiIntent {
        val missing: List<Field>
            get() = buildList {
                if (person == null) add(Field.PERSON)
                if (amount == null) add(Field.AMOUNT)
                if (direction == null) add(Field.DIRECTION)
            }
        val complete: Boolean get() = missing.isEmpty()
    }

    enum class Field { PERSON, AMOUNT, DIRECTION }

    data class PersonBalance(val person: String) : KaiIntent
    data class PersonHistory(val person: String, val lastPaymentOnly: Boolean) : KaiIntent
    data object WhoOwesMe : KaiIntent
    data object WhomDoIOwe : KaiIntent
    data object DueToday : KaiIntent
    data object TotalPending : KaiIntent
    data object WeekCollection : KaiIntent
    data object Briefing : KaiIntent
    /** A business question Kai can't answer from its own facts: the server's Ask-my-business. */
    data class OpenQuestion(val text: String) : KaiIntent
    data object Unclear : KaiIntent
}

/**
 * Turns what the owner said or typed — Tamil, Tanglish, English or mixed —
 * into one business meaning. "Kumar kitta 3000 vanganum", "Kumar owes me
 * 3000", "Kumar 3000 credit" and "Kumar kitta moonu ayiram pending" all
 * mean the same thing. Pure Kotlin: testable without Android.
 */
object KaiUnderstanding {

    fun understand(text: String, today: LocalDate, knownPeople: List<String> = emptyList()): KaiIntent {
        val clean = text.trim().replace(Regex("""\s+"""), " ")
        if (clean.isEmpty()) return KaiIntent.Unclear
        val lower = " " + clean.lowercase(Locale.ROOT).replace(Regex("""[?!,;]"""), " ") + " "
        val person = knownPerson(clean, knownPeople)
        val amounts = amountsIn(clean, today)

        if (isQuestion(lower) && amounts.isEmpty()) {
            return question(lower, person, clean)
        }
        // No amount, nobody known, no money words: a business question for the server's Ask-my-business.
        if (amounts.isEmpty() && person == null && !hasAny(lower, receiveWords + payWords + entryWords)) {
            return question(lower, null, clean)
        }
        return KaiIntent.Record(
            person = person ?: personIn(clean),
            amount = amounts.distinct().singleOrNull(),
            direction = direction(lower),
            dueDate = dueDate(clean, lower, today),
            amountAmbiguous = amounts.distinct().size > 1,
        )
    }

    /**
     * Checks the server's reading of the same words against what was
     * actually said: a name or amount the owner didn't say is dropped (it
     * would be invented), and the owner's own words win over the server's.
     */
    fun reconcile(local: KaiIntent.Record, server: ParsedTransaction?, text: String, today: LocalDate): KaiIntent.Record {
        if (server == null) return local
        val said = text.lowercase(Locale.ROOT)
        val serverName = server.partyName?.trim()?.takeIf { it.isNotEmpty() && said.contains(it.lowercase(Locale.ROOT)) }
        val serverAmount = server.amount?.takeIf { it > 0 && it in amountsIn(text, today) }
        val serverDirection = when (server.intent) {
            "CREATE_CREDIT" -> Direction.RECEIVABLE
            "CREATE_DEBIT" -> Direction.PAYABLE
            else -> null
        }
        return local.copy(
            person = local.person ?: serverName,
            amount = local.amount ?: serverAmount.takeIf { !local.amountAmbiguous },
            direction = local.direction ?: serverDirection,
            // A due date only when the owner said one (the server may fill a default).
            dueDate = local.dueDate,
        )
    }

    // ------------------------------------------------------------ questions

    private val questionWords = listOf(
        " enna ", " evlo ", " evvalavu ", " yaar ", " yaaru ", " yaarukku ", " yarukku ", " eppo ", " epdi ", " eppadi ",
        " sollu ", " sollunga ", " kaattu ", " what ", " who ", " whom ", " how ", " when ", " which ", " show ", " tell ",
        " history ", " list ", " status ", " balance ", " summary ", " important ",
        " என்ன ", " எவ்வளவு ", " யார் ", " யாரு ", " யாருக்கு ", " எப்போ ", " எப்படி ", " சொல்லு ", " சொல்லுங்க ",
    )

    private fun isQuestion(lower: String) = lower.contains('?') || questionWords.any { lower.contains(it) }

    private fun question(lower: String, person: String?, original: String): KaiIntent {
        val collect = hasAny(lower, listOf("collect", "vasool", "vanganum", "vaanganum", "vanganu", "tharanum", "owes me", "owe me", "வாங்கணும்", "தரணும்", "வசூல்"))
        val pay = hasAny(lower, listOf("kudukkanum", "kodukkanum", "kudukanum", "do i owe", "i owe", "have to pay", "i need to pay", "கொடுக்கணும்", "குடுக்கணும்"))
        return when {
            person != null && hasAny(lower, listOf("last payment", "kadaisi", "kadaisiya", "last-a", "கடைசி")) ->
                KaiIntent.PersonHistory(person, lastPaymentOnly = true)
            person != null && hasAny(lower, listOf("history", "varalaaru", "details", "transactions", "விவரம்")) ->
                KaiIntent.PersonHistory(person, lastPaymentOnly = false)
            person != null -> KaiIntent.PersonBalance(person)
            hasAny(lower, listOf(" week ", "vaaram", "வாரம்")) && (collect || hasAny(lower, listOf("collection", "varavu"))) -> KaiIntent.WeekCollection
            hasAny(lower, listOf("innaikku", "innaiku", "today", "இன்னைக்கு", "இன்று")) && hasAny(lower, listOf("due", "payment", "kattanum", "varanum")) -> KaiIntent.DueToday
            hasAny(lower, listOf("total", "motham", "மொத்தம்")) && hasAny(lower, listOf("pending", "baaki", "bakki", "பாக்கி", "outstanding")) -> KaiIntent.TotalPending
            hasAny(lower, listOf("yaar", "who", "யார்")) && collect -> KaiIntent.WhoOwesMe
            hasAny(lower, listOf("yaarukku", "yarukku", "whom", "who", "யாருக்கு")) && pay -> KaiIntent.WhomDoIOwe
            collect && !pay -> KaiIntent.WhoOwesMe
            pay && !collect -> KaiIntent.WhomDoIOwe
            hasAny(lower, listOf("important", "mukkiyam", "முக்கியம்", "brief", "today enna", "innaikku enna")) -> KaiIntent.Briefing
            else -> KaiIntent.OpenQuestion(original)
        }
    }

    // ------------------------------------------------------------ direction

    // Words of an entry even when its amount or direction is missing ("Kumar kitta pending").
    private val entryWords = listOf(" pending ", " due ", " kitta ", " baaki ", " bakki ", "-ku ", "பாக்கி", "கிட்ட")

    private val receiveWords = listOf(
        "vanganum", "vaanganum", "vanganu", "vaanganu", "vangunum", "tharanum", "tharanu", "tharuvaan", "tharuvan", "thara vendum",
        "owes me", "owe me", "receive", "receivable", "collect", "credit", "to get", "get from",
        "வாங்கணும்", "வாங்க வேண்டும்", "தரணும்", "தர வேண்டும்", "வரவு",
    )
    private val payWords = listOf(
        "kudukkanum", "kodukkanum", "kudukanum", "kudukkanu", "i owe", "pay ", "payable", "debit", "have to give", "need to give",
        "கொடுக்கணும்", "குடுக்கணும்", "கொடுக்க வேண்டும்", "செலவு",
    )

    /** From the relationship in the sentence; mixed or no signal → null (ask the owner). */
    private fun direction(lower: String): Direction? {
        val receive = hasAny(lower, receiveWords)
        // "Kumar-ku kudukkanum" / "Kumar kitta kudukkanum": give TO Kumar → I pay.
        val pay = hasAny(lower, payWords)
        return when {
            receive && !pay -> Direction.RECEIVABLE
            pay && !receive -> Direction.PAYABLE
            receive && pay -> null
            // "Kumar 3000 due" / "Kumar kitta 3000 pending": money due from the person.
            hasAny(lower, listOf(" due ", " pending ", " baaki ", " bakki ", "பாக்கி")) -> Direction.RECEIVABLE
            else -> null
        }
    }

    // ------------------------------------------------------------ due date

    private fun dueDate(text: String, lower: String, today: LocalDate): LocalDate? {
        if (hasAny(lower, listOf(" naalaikku ", " nalaiku ", " naalaiku ", " tomorrow ", "நாளைக்கு"))) return today.plusDays(1)
        val match = DocumentDates.findMatch(text, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR) ?: return null
        // "October 10th" said in November means next year's.
        return if (match.yearAssumed && match.date.isBefore(today)) match.date.plusYears(1) else match.date
    }

    // ------------------------------------------------------------ amounts

    private val tamilUnits = mapOf(
        "oru" to 1, "onnu" to 1, "ondru" to 1, "rendu" to 2, "irandu" to 2, "moonu" to 3, "munu" to 3, "moondru" to 3,
        "naalu" to 4, "nalu" to 4, "naangu" to 4, "anju" to 5, "ainthu" to 5, "aaru" to 6, "ezhu" to 7, "ettu" to 8,
        "onbadhu" to 9, "ombodhu" to 9, "pathu" to 10, "iruvathu" to 20, "muppathu" to 30, "naapathu" to 40,
        "aimbathu" to 50, "arubathu" to 60, "ezhupathu" to 70, "embathu" to 80, "thonnooru" to 90,
        "ஒரு" to 1, "ஒன்று" to 1, "ரெண்டு" to 2, "இரண்டு" to 2, "மூணு" to 3, "மூன்று" to 3, "நாலு" to 4, "நான்கு" to 4,
        "அஞ்சு" to 5, "ஐந்து" to 5, "ஆறு" to 6, "ஏழு" to 7, "எட்டு" to 8, "ஒன்பது" to 9, "பத்து" to 10,
    )
    private val tamilHundreds = mapOf(
        "nooru" to 100, "nuru" to 100, "irunooru" to 200, "munnooru" to 300, "naanooru" to 400, "ainooru" to 500,
        "anjooru" to 500, "arunooru" to 600, "ezhunooru" to 700, "ennooru" to 800, "tholayiram" to 900,
        // Speech-to-text spellings of the same numbers ("ainnooru", "anjuru", "irunuru").
        "ainnooru" to 500, "ainnuru" to 500, "ainuru" to 500, "anjuru" to 500, "irunuru" to 200, "munnuru" to 300, "naanuru" to 400,
        "arunuru" to 600, "ezhunuru" to 700, "ennuru" to 800, "ஐந்நூறு" to 500,
        "நூறு" to 100, "இருநூறு" to 200, "முந்நூறு" to 300, "நானூறு" to 400, "ஐநூறு" to 500, "அஞ்சூறு" to 500,
    )
    private val englishNumberWords = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "fifteen", "twenty", "thirty",
        "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "lakh", "lakhs",
    )

    /** "five", "ainnooru", "aayiram", "ஆயிரம்": a word that is (part of) a spoken amount. */
    fun isNumberWord(word: String): Boolean = word.lowercase(java.util.Locale.ROOT).let {
        it in tamilUnits || it in tamilHundreds || it in tamilScales || it in englishNumberWords
    }

    private val tamilScales = mapOf(
        "ayiram" to 1_000, "aayiram" to 1_000, "ayirathu" to 1_000, "aayirathu" to 1_000, "ayram" to 1_000, "thousand" to 1_000,
        "latcham" to 100_000, "laksham" to 100_000, "lakh" to 100_000, "lakhs" to 100_000, "latsam" to 100_000,
        "ஆயிரம்" to 1_000, "ஆயிரத்து" to 1_000, "லட்சம்" to 100_000,
    )

    // "3000", "3,000", "₹3000", "3k", "3.5k", "3000rs", "3000/-". Not phone numbers.
    private val digitAmount = Regex("""(?<![\d.,/])(?:₹|rs\.?\s*)?(\d{1,3}(?:,\d{2,3})+|\d+(?:\.\d{1,2})?)\s*(k\b)?""", RegexOption.IGNORE_CASE)

    /** Every distinct money amount the words contain (dates excluded). */
    fun amountsIn(text: String, today: LocalDate): List<Double> {
        var masked = text
        DocumentDates.findMatch(text, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)?.let { masked = masked.replace(it.text, " ") }
        // Ordinals of a date ("10th") are never money.
        masked = masked.replace(Regex("""\b\d{1,2}(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE), " ")
        val found = mutableListOf<Double>()
        digitAmount.findAll(masked).forEach { m ->
            val digits = m.groupValues[1].replace(",", "")
            if (digits.length >= 9) return@forEach // phone / account numbers
            val value = digits.toDoubleOrNull() ?: return@forEach
            val amount = if (m.groupValues[2].isNotEmpty()) value * 1000 else value
            if (amount > 0) found += amount
        }
        wordAmount(masked)?.let { found += it }
        return found.distinct()
    }

    // "moonu ayiram", "rendu ayirathu anjooru", "three thousand five hundred".
    private fun wordAmount(text: String): Double? {
        val tokens = text.lowercase(Locale.ROOT).split(Regex("""[\s,\-]+""")).filter { it.isNotEmpty() }
        var total = 0L
        var current = 0L
        var saw = false
        for (t in tokens) {
            when {
                t in tamilUnits -> { current += tamilUnits.getValue(t); saw = true }
                t in tamilHundreds -> { current += tamilHundreds.getValue(t); saw = true }
                t in tamilScales -> {
                    total += (if (current == 0L) 1 else current) * tamilScales.getValue(t)
                    current = 0
                    saw = true
                }
                else -> if (saw) break
            }
        }
        val tamil = (total + current).takeIf { saw && it >= 10 }?.toDouble()
        // "ten thousand": the English run has a number before its scale word — that is the amount, not "thousand" alone.
        val englishRun = Regex("""(?i)\b(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\s+(?:hundred|thousand|lakh)\b""")
        if (tamil != null && !englishRun.containsMatchIn(text)) return tamil
        // English words ("three thousand"): only a run that parses as a number.
        val english = Regex("""(?i)\b((?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|lakh|and)(?:\s+(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|lakh|and))*)\b""")
            .find(text)?.value ?: return null
        return AmountInWords.parse(english)?.toDouble()?.takeIf { it >= 10 }
    }

    // ------------------------------------------------------------ people

    /** A known customer/supplier named in the words ("Kumar-ku", "kumarukku" included). */
    fun knownPerson(text: String, known: List<String>): String? {
        val lower = text.lowercase(Locale.ROOT)
        val names = known.filter { it.isNotBlank() }.sortedByDescending { it.length } // "Ravi Kumar" before "Ravi"
        // The whole name, or the name with a case ending ("Kumar-ku", "Kumarukku", "Kumarkitta") — never the start of
        // a longer name ("Kumaran" is not "Kumar").
        names.firstOrNull { name ->
            Regex("""(?<![\p{L}])${Regex.escape(name.lowercase(Locale.ROOT))}(?:u?k?ku|kitta|kita|kitte|kittae|oda|odu|idam|ai|um|ukkum|a|aa|ah|u|க்கு|கிட்ட|கிட்டே|ஓட)?(?![\p{L}\p{M}])""").containsMatchIn(lower)
        }?.let { return it }
        // The same name in the other script: "Kumar-ku" for "குமார்", "குமார்க்கு" for "Kumar".
        val words = Regex("""[\p{L}\p{M}]+""").findAll(text).map { w ->
            w.value.replace(Regex("""(?i)(kitta|kita|kitte|ukku|kku|ku|oda|idam)$"""), "").replace(Regex("""(க்கு|கிட்ட|கிட்டே|உக்கு)$"""), "")
        }.filter { it.length >= 2 }.toList()
        return names.firstOrNull { name -> !name.contains(' ') && words.any { com.shopai.app.util.NameSound.same(it, name) } }
    }

    private val notNames = setOf(
        "kitta", "kita", "kitte", "ku", "kku", "ukku", "vanganum", "vaanganum", "vanganu", "vaanganu", "kudukkanum", "kodukkanum",
        "kudukanum", "tharanum", "tharanu", "thara", "pending", "due", "rs", "rupees", "rupee", "rubai", "ruba", "credit", "debit",
        "receive", "received", "collect", "panniten", "pannunga", "pannanum", "irukku", "iruku", "owes", "owe", "me", "i", "to",
        "from", "payment", "paid", "pay", "on", "by", "before", "date", "the", "a", "an", "of", "and", "is", "has", "will", "give",
        "get", "baaki", "bakki", "innaikku", "naalaikku", "today", "tomorrow", "month", "week", "entry", "add", "owner", "sir",
        "naan", "na", "naa", "nan", "enakku", "ennaku", "enaku", "yenakku", "yenaku", "yennaku", "yenak", "avan", "aval", "avar", "kaasu", "panam", "amount", "vandhu", "vandhuchu", "koduthen", "kuduthen",
        "kuduthuten", "vaangi", "vangi", "k", "th", "st", "nd", "rd",
        // Common English and question words.
        "have", "need", "must", "should", "got", "gave", "lent", "lend", "borrowed", "money", "cash", "for", "with", "want",
        "wants", "my", "you", "your", "he", "she", "they", "them", "him", "her", "it", "this", "that", "please", "note", "save",
        "record", "yaar", "yaaru", "yaarukku", "yar", "yaru", "yarukku", "yaarum", "yarum", "enna", "evlo", "eppo", "epdi", "sollu", "history", "last", "next", "total",
        "who", "what", "how", "when", "show", "tell", "vaaram", "motham", "collection", "moola", "romba", "konjam",
        // When / how / who words and the days of the week ("Yeppa tharanum?", "Innaiku yaruku…", "next friday tharuvaan") — never names.
        "eppa", "yeppa", "yeppo", "yepo", "epo", "eppadi", "yeppadi", "yepdi", "innaiku", "innai", "inniku", "inni", "naalaiku", "nalaiku", "naalai",
        "yaruku", "yaaruku", "yarukita", "yarukitta", "yaarukitta", "yaarukita", "yarkitta", "yaarkitta", "yarlam", "yaarlam", "yaarellam", "yarellam", "yaarlaam", "general", "generala", "usually", "correct", "late", "time",
        "avanga", "ellarum", "ellaarum", "nethu", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "thingal", "sevvai", "budhan", "vyazhan", "viyazhan", "velli", "sani", "nyayiru", "gnayiru", "kizhamai",
    ) + com.shopai.app.brain.tools.KaiFeed.notNames

    /** The person named in a new entry: the first word that is not a keyword, number or date word. */
    fun personIn(text: String): String? {
        val words = Regex("""[\p{L}][\p{L}\p{M}'.]*""").findAll(text).map { it.value.trim('.', '\'') }.toList()
        val numberWords = tamilUnits.keys + tamilHundreds.keys + tamilScales.keys
        val months = setOf("jan", "january", "feb", "february", "mar", "march", "apr", "april", "may", "jun", "june", "jul", "july",
            "aug", "august", "sep", "sept", "september", "oct", "october", "nov", "november", "dec", "december")
        val word = words.firstOrNull { w ->
            val l = w.lowercase(Locale.ROOT)
            val base = stripSuffix(l)
            w.count { it.isLetter() } >= 2 && base !in notNames && l !in notNames && base !in numberWords && base !in months &&
                !l.all { it in "k" } && base.length >= 2
        } ?: return null
        val base = stripSuffix(word.lowercase(Locale.ROOT))
        return word.take(base.length).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /**
     * A new person's full name when the owner typed two capitalised name words ("Madurai Ravi", "Chennai Lokesh"):
     * both words, never just the place. Lower-case speech keeps the first word (Kai's draft says "puthu customer").
     */
    fun personPhraseIn(text: String): String? {
        val first = personIn(text) ?: return null
        val m = Regex("""(?<![\p{L}])${Regex.escape(first)}\s+(\p{Lu}[\p{L}\p{M}]+)""").find(text) ?: return first
        val second = m.groupValues[1]
        val base = stripSuffix(second.lowercase(Locale.ROOT))
        if (base in notNames || base.length < 2 || second.lowercase(Locale.ROOT) in notNames) return first
        return "$first ${second.take(base.length)}"
    }

    // "Kumar-ku", "Kumarukku", "Ravikitta", "Ravi-kitta" → the name.
    private fun stripSuffix(word: String): String {
        for (s in listOf("-kitta", "kitta", "-ukku", "ukku", "-kku", "-ku", "க்கு", "கிட்ட")) {
            if (word.endsWith(s) && word.length - s.length >= 2) return word.removeSuffix(s).trimEnd('-')
        }
        if (word.endsWith("ku") && word.length >= 5) return word.removeSuffix("ku")
        return word
    }

    private fun hasAny(lower: String, words: List<String>) = words.any { lower.contains(it) }
}
