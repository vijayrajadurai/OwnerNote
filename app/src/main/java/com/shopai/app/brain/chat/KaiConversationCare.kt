package com.shopai.app.brain.chat

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * How Kai takes the owner's push-back and the things it can't do (owner's request, 10 Oct 2026: "objection handling",
 * "features that aren't there", "the topic we started with"). Plain rules — no AI, nothing invented, nothing written:
 * every reply here either redrafts for Confirm, points to the screen that can do it, or says plainly that Kai can't.
 */
internal object KaiConversationCare {

    private fun plain(text: String) = " " + text.lowercase(Locale.ROOT).replace(Regex("""[?!.,;:]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "

    // ------------------------------------------------------------ push-back

    /** "illa", "thappu", "wrong", "no": the owner says what Kai has is not right. */
    private val negation = Regex("""(?i)(?<![\p{L}])(illa|illai|ille|thappu|thapu|wrong|no|nope|illaye)(?![\p{L}])|இல்ல|தப்பு""")
    fun negates(text: String) = negation.containsMatchIn(text)

    /** "adha cancel pannu", "delete pannu", "undo", "thappa pottutta": take back the entry just saved. */
    private val undo = Regex("""(?i)(?<![\p{L}])(cancel|delete|remove|undo|azhi\p{L}*|neekku\p{L}*|eduthudu|eduththudu|thirumba\s+edu)(?![\p{L}])|""" +
        """(?<![\p{L}])thappa\s*(pottu\p{L}*|potta\p{L}*|save\p{L}*|add\p{L}*|entry|eduthu\p{L}*)|wrong\s*entry""")
    /** "maathu", "change pannu", "correct pannu", "1500 illa 1000": change the entry just saved. */
    private val change = Regex("""(?i)(?<![\p{L}])(maathu|maaththu|mathu|maathidu|maathunga|change|edit|thiruthu|correct\s*pannu)(?![\p{L}])""")
    private val aboutReminder = Regex("""(?i)remind|reminder|nyabagam|alarm|ninaivu""")

    enum class Takeback { UNDO, CHANGE }

    /** The owner wants the entry Kai just saved taken back or changed (not a reminder). */
    fun takeback(text: String): Takeback? = when {
        aboutReminder.containsMatchIn(text) -> null
        undo.containsMatchIn(text) -> Takeback.UNDO
        change.containsMatchIn(text) || negates(text) && Regex("""\d""").containsMatchIn(text) -> Takeback.CHANGE
        else -> null
    }

    /** "nee sonnadhu thappu", "nee onnum purinjukala", "wrong answer": Kai got it wrong, and the owner says so. */
    private val kaiWrong = Regex(
        """(?i)(sonnadhu|sonnathu|solradhu|solrathu|solra|sonna)\s*(thappu|thapu|wrong|sari\s*illa|sariyilla)|thappa\s*solr\p{L}*|wrong\s*answer|you\s*are\s*wrong|""" +
            """puri\p{L}*kala|purinjuk\p{L}*|purinjik\p{L}*|unakku\s*(onnum\s*)?puri\p{L}*|(?<![\p{L}])(waste|nonsense|mokka)(?![\p{L}])""",
    )
    fun kaiGotItWrong(text: String) = kaiWrong.containsMatchIn(text)

    /** "puriyala", "enakku puriyala", "என்ன சொல்றீங்க": the owner didn't follow Kai's answer. */
    private val ownerLost = Regex("""(?i)^\s*(enakku\s*|ennaku\s*|enaku\s*)?(puriyala|puriyale|purila|puriyalai|puriyalaye|understand\s*panna\s*mudiyala|not\s*clear|i\s*don'?t\s*understand)\s*(owner|kai|sir|anna)?\s*[?!.]*\s*$|^\s*(enna|என்ன)\s*(solra|solreenga|sollureenga|சொல்றீங்க|சொல்ற)\s*[?!.]*\s*$""")
    fun ownerDidNotFollow(text: String) = ownerLost.containsMatchIn(text.trim())

    /** "ippo venam, aprom paakalam", "later": not now — the open draft is dropped, nothing saved. */
    private val notNow = Regex("""(?i)(?<![\p{L}])(aprom|apram|appuram|pinnadi|later|apparam)\s*(paakalam|pakkalam|parkalam|paarkalam|paapom|pannalam|sollren|solren)(?![\p{L}])|""" +
        """(?<![\p{L}])ippo\s*(venam|vendam|vendaam|venaam|venda)(?![\p{L}])|save\s*panna\s*(venam|vendam)|save\s*pannadhe|not\s*now""")
    fun notNow(text: String) = notNow.containsMatchIn(text)

    /** "tharanum / balance / baaki" — a statement about what someone owes. */
    private val owesTalk = Regex("""(?i)(?<![\p{L}])(tharanum|tharanu|kudukkanum|kudukanum|baaki|bakki|balance|pending|varanum|owes?)(?![\p{L}])""")
    fun disputesBalance(text: String) = negates(text) && owesTalk.containsMatchIn(text) && Regex("""\d""").containsMatchIn(text)

    // ------------------------------------------------------------ the people talked about

    /** "rendu perum total evlo", "both of them": the last two people Kai answered about. */
    private val both = Regex("""(?i)(?<![\p{L}])((rendu|2)\s*(perum|peroda|perukum|perukkum|பேரும்|பேரோட)|renduperum|iruvarum|both)(?![\p{L}])|இருவரும்""")
    fun asksBoth(text: String) = both.containsMatchIn(text)

    /** "first ketta aal", "mudhalla sonna aalu": the first person of this conversation; "munnadi ketta aal": the one before. */
    private val first = Regex("""(?i)(?<![\p{L}])(first|mudhal|modhal|mudhalla|modhalla|starting\s*la)\s*(-?\s*la|-?\s*a)?\s*(ketta|kettadhu|kettan|sonna|pesuna|pesina|paatha)?\s*(aal|aalu|person|per|customer|aaloda|aaludaya)(?![\p{L}])""")
    private val before = Regex("""(?i)(?<![\p{L}])(munnadi|previous|mundhi|munna)\s*(ketta|kettadhu|sonna|pesuna|pesina|paatha)\s*(aal|aalu|person|per|customer|aaloda)(?![\p{L}])""")

    /** The words with "first ketta aal" / "munnadi ketta aal" put as the name they mean, or null. */
    fun namedFromEarlier(text: String, people: List<String>): String? {
        if (people.isEmpty()) return null
        first.find(text)?.let { return text.replaceRange(it.range, people.first()) }
        if (people.size >= 2) before.find(text)?.let { return text.replaceRange(it.range, people[people.size - 2]) }
        return null
    }

    /** "ippa evlo tharanum", "ippo balance enna", "innum evlo baaki": the same person's balance, asked again with no name. */
    private val balanceAgain = Regex("""(?i)^\s*(ippa|ippo|ipo|ipa|innum|now|ippodhu|ippavum|appo)\s+(evlo|evvalavu|ethana|how\s*much|balance|baaki|bakki|pending)\s*""" +
        """(evlo|enna)?\s*(tharanum|tharanu|baaki|bakki|balance|pending|kudukkanum|varanum|irukku|iruku)?\s*(owner|kai|sir)?\s*[?!.]*\s*$""")
    private val owedWord = Regex("""(?i)(tharanum|tharanu|baaki|bakki|balance|pending|kudukkanum|varanum)""")
    fun asksBalanceAgain(text: String) = balanceAgain.containsMatchIn(text.trim()) && owedWord.containsMatchIn(text)

    /** "ippo evlo irukku", "innum evlo irukku": the same product, asked again with no name. */
    private val sameThingAgain = Regex("""(?i)^\s*(ippo|ipo|ippa|innum|now|ippodhu|ippavum|meedhi|balance)\s*(stock\s*)?(evlo|evvalavu|ethana|how\s*much|enna)\s*(irukku|iruku|irukka|left|irundhuchu|aachu)?\s*[?!.]*\s*$""")
    fun asksSameProductAgain(text: String) = sameThingAgain.containsMatchIn(text.trim())

    // ------------------------------------------------------------ what Kai can't do

    /** "GST bill podu", "invoice create pannu": a bill to make — not a bill to scan. */
    private val makeBill = Regex("""(?i)(?<![\p{L}])((gst|tax|sales|sale)\s*)?(bill|invoice)\s*(podu|potu|podunga|ready|create|generate|print|make|ezhudhu|ezhuthu|venum|anuppu|send|kudu|pannu|pannunga)(?![\p{L}])|""" +
        """(?<![\p{L}])(create|generate|make|print)\s*(a\s*)?(gst\s*)?(bill|invoice)(?![\p{L}])""")
    private val scanBill = Regex("""(?i)(scan|photo|camera|padam|edu|snap)""")
    fun makesBill(text: String) = makeBill.containsMatchIn(text) && !scanBill.containsMatchIn(text)

    /** "WhatsApp la anuppu", "SMS pannu": sending a message to someone — Kai can't send it. */
    private val sendMessage = Regex("""(?i)(?<![\p{L}])(whatsapp|whats\s*app|watsapp|sms|text\s*message)(?![\p{L}])""")
    fun sendsMessage(text: String) = sendMessage.containsMatchIn(text)
    /** The line without the "WhatsApp la" part — what Kai can still do (a reminder for the owner). */
    fun withoutMessageApp(text: String) = Regex("""(?i)(?<![\p{L}])(whatsapp|whats\s*app|watsapp|sms|text\s*message)\s*(-?\s*(la|le|il|lla|via|on|through))?(?![\p{L}])""")
        .replace(text, " ").replace(Regex("""\s+"""), " ").trim()

    enum class Beyond { WEATHER, SPORTS, NEWS, FUN, ONLINE_ORDER, LOAN, STAFF }

    private val beyond = listOf(
        Beyond.WEATHER to Regex("""(?i)(?<![\p{L}])(mazhai|mazha|weather|veyil|kulir|rain|climate|temperature|puyal)(?![\p{L}])|மழை|வெயில்|புயல்"""),
        Beyond.SPORTS to Regex("""(?i)(?<![\p{L}])(cricket|ipl|score|match|kabaddi|football|world\s*cup)(?![\p{L}])|கிரிக்கெட்|ஸ்கோர்"""),
        // "cm yaaru", "who is the pm" — never the "pm" of "5 pm".
        Beyond.NEWS to Regex("""(?i)(?<![\p{L}])(cm|pm)\s*(yaaru|yaar|who|evaru|name|per)(?![\p{L}])|(?<![\p{L}])(yaaru|who\s*is|who's)\s*(the\s*)?(cm|pm)(?![\p{L}])|""" +
            """(?<![\p{L}])(chief\s*minister|prime\s*minister|minister|president|election|news|seidhi|politics|governor)(?![\p{L}])|முதல்வர்|பிரதமர்|செய்தி"""),
        Beyond.FUN to Regex("""(?i)(?<![\p{L}])(joke|jokes|kadhai|story|paatu|paattu|song|movie|cinema|riddle|vidukadhai)(?![\p{L}])|ஜோக்|கதை|பாட்டு"""),
        Beyond.ONLINE_ORDER to Regex("""(?i)(?<![\p{L}])(amazon|flipkart|swiggy|zomato|meesho|jiomart|blinkit|zepto|online\s*(la|le)?\s*order)(?![\p{L}])"""),
        Beyond.LOAN to Regex("""(?i)(?<![\p{L}])(loan|emi\s*venum|finance\s*venum)(?![\p{L}])|லோன்"""),
        Beyond.STAFF to Regex("""(?i)(?<![\p{L}])(salary|sambalam|employee|employees|staff|worker|velai\s*aal|attendance)(?![\p{L}])|சம்பளம்"""),
    )

    /** A topic outside the shop's books — said with no amount (or one that is never business: weather, cricket, news, fun). */
    fun beyondBooks(text: String): Beyond? {
        val hit = beyond.firstOrNull { it.second.containsMatchIn(text) }?.first ?: return null
        val amount = Regex("""\d""").containsMatchIn(text)
        return hit.takeIf { !amount || it in setOf(Beyond.WEATHER, Beyond.SPORTS, Beyond.NEWS, Beyond.FUN, Beyond.ONLINE_ORDER) }
    }

    // ------------------------------------------------------------ dates

    private val dateAsk = Regex("""(?i)(enna|endha|which|what|yenna)\s*(date|thethi|thedhi|naal|day|kizhamai|kilamai)|(date|thethi|kizhamai)\s*(enna|yenna|what|sollu)|கிழமை""")
    /** "Kumar due date enna" is the books' date, not the calendar's. */
    private val booksDate = Regex("""(?i)(?<![\p{L}])(due|tharu\p{L}*|thara\p{L}*|kudu\p{L}*|payment|remind\p{L}*|bill|kattanum|vaanga\p{L}*|stock|expiry)(?![\p{L}])""")
    private val numberWords = mapOf("oru" to 1, "onnu" to 1, "one" to 1, "a" to 1, "rendu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4,
        "anju" to 5, "five" to 5, "aaru" to 6, "six" to 6, "ezhu" to 7, "seven" to 7, "ettu" to 8, "eight" to 8, "pathu" to 10, "ten" to 10)
    private val relative = Regex("""(?i)(\d+|oru|onnu|one|a|rendu|two|moonu|three|naalu|four|anju|five|aaru|six|ezhu|seven|ettu|eight|pathu|ten)\s*""" +
        """(varusham|varusam|varudam|year|years|maasam|masam|month|months|vaaram|varam|week|weeks|naal|naalu|day|days)\s*""" +
        """(kalichi|kalichu|kazhichu|after|later|aprom|apram|la|kku|ku|munnadi|before|ago)?""")

    /** "1 varusham kalichi enna date?", "naalaiku enna kizhamai?", "innaiku enna date?": the date — or null when it isn't asked. */
    fun dateAsked(text: String, today: LocalDate): LocalDate? {
        if (!dateAsk.containsMatchIn(text) || booksDate.containsMatchIn(text)) return null
        val t = plain(text)
        relative.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: numberWords[m.groupValues[1].lowercase(Locale.ROOT)] ?: return@let
            val back = m.groupValues[3].lowercase(Locale.ROOT) in setOf("munnadi", "before", "ago")
            val k = if (back) -n.toLong() else n.toLong()
            return when (m.groupValues[2].lowercase(Locale.ROOT).take(3)) {
                "var", "yea" -> if (m.groupValues[2].lowercase(Locale.ROOT).startsWith("vaaram") || m.groupValues[2].lowercase(Locale.ROOT).startsWith("varam")) today.plusWeeks(k) else today.plusYears(k)
                "maa", "mas", "mon" -> today.plusMonths(k)
                "wee", "vaa" -> today.plusWeeks(k)
                else -> today.plusDays(k)
            }
        }
        return when {
            Regex("""(?i)(naalanniku|naalanaiku|day\s*after\s*tomorrow)""").containsMatchIn(t) -> today.plusDays(2)
            Regex("""(?i)(naalai|nalai|tomorrow)""").containsMatchIn(t) -> today.plusDays(1)
            Regex("""(?i)(nethu|neththu|yesterday)""").containsMatchIn(t) -> today.minusDays(1)
            else -> today
        }
    }

    private val weekdayTa = mapOf(DayOfWeek.MONDAY to "திங்கள்", DayOfWeek.TUESDAY to "செவ்வாய்", DayOfWeek.WEDNESDAY to "புதன்", DayOfWeek.THURSDAY to "வியாழன்",
        DayOfWeek.FRIDAY to "வெள்ளி", DayOfWeek.SATURDAY to "சனி", DayOfWeek.SUNDAY to "ஞாயிறு")
    private val monthTa = listOf("ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்", "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்")

    /** "October 10, 2027 (Sunday)" / "அக்டோபர் 10, 2027 (ஞாயிறு)". */
    fun dateText(d: LocalDate, tamil: Boolean): String =
        if (tamil) "${monthTa[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year} (${weekdayTa.getValue(d.dayOfWeek)})"
        else "${d.month.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }} ${d.dayOfMonth}, ${d.year} " +
            "(${d.dayOfWeek.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }})"
}
