package com.shopai.app.brain.tools

import java.util.Locale

/**
 * What the owner MEANT, however they said it (owner, 19 Oct 2026: "words maaralaam — shop owner intent purinjikkanum, stock-la
 * nariya problem varudhu"). Owners don't use Kai's words: stock comes "irakkumadhi aachu", "kadaikku serndhuchu", "rack-la
 * adukkinen"; it goes "theernjuduchu", "eli kadichiduchu", "oluguchu", "veetukku eduthutten"; a reminder is "ezhuppi vidu",
 * "thatti vidu", "maranthudaama sollu"; a credit is "pakki vechutu ponaan", "eluthi vei", "account-la potukko".
 *
 * Each rule here is a FAMILY of words (a stem with its endings, typed and as the mic's Tamil reads after [KaiSpokenWords]),
 * said again in the one way Kai's rules already read — so a new way of saying an intent is a stem here, never a new code path,
 * and Kai's rules stay the single place that decides. Nothing here writes anything: the line still becomes a draft that only
 * the owner's Confirm saves.
 *
 * Guards, so a rewrite never changes the meaning:
 *  - stock words are read as stock only on a line with a quantity (a digit or a number word);
 *  - reminder words are a reminder only with a time or a day said, and never in a question;
 *  - "eluthi vei" is a reminder with a time, an entry on the person's record without one.
 */
object KaiIntentWords {
    private const val B = """(?<![\p{L}\p{M}])"""
    private const val E = """(?![\p{L}\p{M}])"""
    private fun r(s: String) = Regex(B + "(?:" + s + ")" + E, RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ cues

    /** A count said: a digit, or a number word ("rendu", "aimbadhu"). */
    private val quantity = Regex("""\d|""" + B + """(?:oru|onnu|rendu|moonu|naalu|anju|aaru|ezhu|ettu|pathu|pannendu|irupadhu|iruvadhu|aimbadhu|ambadhu|aimbathu|nooru|\p{L}*nooru|\p{L}*nuru|aa?yira\p{L}*|thousand|hundred)""" + E, RegexOption.IGNORE_CASE)

    /** A time or a day: "10 manikku", "naalaiku", "saayangaalam", "Friday", "adutha Monday", "ovvoru Sani", "2 mani neram kalichu". */
    private val timeCue = Regex(
        """(?i)\d+\s*(?:manikku|maniku|mani|o'?clock|a\.?m\.?|p\.?m\.?|nimisham\p{L}*|minutes?|mins?|hours?|hrs?)""" + E + "|" + B +
            """(?:naalai\p{L}*|nalai\p{L}*|naalanniku|innaikku|innaiku|inniku|innikku|indru|kaalai\p{L}*|kalai\p{L}*|madhiyam|mathiyam|""" +
            """saayang\p{L}*|sayang\p{L}*|maalai\p{L}*|iravu|raathiri|rathiri|ratri|morning|evening|night|tomorrow|today|tonight|""" +
            """monday|tuesday|wednesday|thursday|friday|saturday|sunday|sani|velli|sevvai|pudhan|viyazhan|thingal|nyayiru|gnayiru|""" +
            """adutha|next|every|ovvoru|dhinamum|dinamum|thinamum|daily|weekly|monthly|kalichu|kalichi|kazhichu|apram)""" + E,
    )

    /** A question: never turned into a reminder or an entry ("naalaiku yaar tharanum sollu"). */
    private val question = Regex("""(?i)\?|""" + B + """(?:evlo|evvalavu|ewlo|enna|ennenna|yaar\p{L}*|yar\p{L}*|eppo|eppa|edhu|ethu|endha|entha|yentha|ethana|list|how|what|who|when|which)""" + E)

    // ------------------------------------------------------------------ numbers said as one word

    /** "rendaayiram", "moonaayirathu ainooru": the thousands said as one word, read as the parts Kai's amounts already add up. */
    private val thousands = listOf(
        "rend|irand" to "rendu", "moon|moov|moonr" to "moonu", "naal|naan" to "naalu", "anj|ainth|ainj" to "anju", "aar" to "aaru",
        "ezh|ell" to "ezhu", "ett" to "ettu", "ombadh|onbadh|ombath|onbath" to "onbadhu", "path" to "pathu",
    ).map { (stem, word) -> r("(?:$stem)aa?yira(m|thu)") to word }

    /** Tens with "dh" as typed ("aimbadhu") — Kai's amounts know the "th" spelling. */
    private val tens = listOf(
        r("irupadhu|iruvadhu|irubadhu") to "iruvathu", r("muppadhu|muppadu") to "muppathu", r("naappadhu|naapadhu|narpadhu") to "naapathu",
        r("aimbadhu|ambadhu|aimpadhu") to "aimbathu", r("arupadhu|aruvadhu|arubadhu") to "arubathu", r("ezhupadhu|ezhuvadhu") to "ezhupathu",
        r("enbadhu|embadhu|enpadhu") to "embathu",
    )

    // ------------------------------------------------------------------ stock

    /** Goods CAME IN, said any way → "vandhuchu" (stock in). */
    private val stockIn = listOf(
        r("""irakkuma[dt]h?i(?:\s+(?:aachu|achu|aagiduchu|aayiduchu|ayiduchu|pannen|panninen|panniten|pannitten))?"""),
        r("""(?:vandhu\s+|vanthu\s+)?irang(?:iduchu|idichu|ichu|uchu|iruchu|irukku|iyachu|iyaachu|inadhu)"""),
        r("""s[ea]rn?[dt]h(?:uchu|uduchu|iduchu|achu|aachu|irukku|uruku|urukku)"""),
        r("""delivery\s+(?:aachu|achu|aagiduchu|aayiduchu|vandhuchu|vanthuchu|kuduthaanga|kuduthanga|kuduthaan|kuduthan|pannaanga|pannanga|panninaanga|panninanga)|delivered"""),
        r("""unload(?:ed)?(?:\s+(?:pannom|pannen|panninom|panniten|pannitten|pannittom|aachu|achu|pannaanga|pannanga))?"""),
        r("""adukk(?:inen|inom|itten|iten|ittom|i\s+vechen|i\s+vachen|i\s+vechom|i\s+vachom|i\s+vachitten)"""),
        r("""(?:stock\s+)?y?ea?t?thi(?:tten|ten|nen|nom|ttom|tom|\s+vechen|\s+vachen)"""),
        r("""kidaich(?:uchu|iduchu|achu|aachu|irukku)"""),
        r("""refill(?:ed)?(?:\s+(?:pannen|panniten|pannitten|aachu|achu))?"""),
    )

    private val shelfPlace = r("""(?:rack|racks|shelf|shelves|selfu|godown|kodown|store\s*room|almirah|almari)\s*-?\s*(?:la|le|il|ula|ulla|kku|ku)""")

    /** Goods WENT OUT, said any way → the out word Kai reads, keeping why (the reason the stock move records). */
    private val stockOut = listOf(
        // sold / used up / ran out
        r("""th(?:ee|i)r[nr]?[dtj]h?\p{L}*|kaali\s+(?:aayiduchu|aagiduchu|ayiduchu|aachu|achu|aayidichu|aagidichu)|mudinj(?:iduchu|uduchu|uchu|ichu|iruchu)""") to "vithuchu",
        // taken home / own use
        r("""veett?(?:u|ukku|uku|ukk)\s+(?:eduth\p{L}*|kondu\s+pon\p{L}*|kondupon\p{L}*)|(?:own|sondha|sontha|self)\s+use(?:\s*-?\s*(?:ku|kku))?(?:\s+(?:eduthen|eduthutten|pannen|panniten))?|use\s+(?:pannitten|panniten|pannen|pannom|aagiduchu|aachu)""") to "veetukku pochu",
        // sent back to the supplier
        r("""thirupp?i\s+anupp\p{L}*|thirupp?i\s+(?:kuduthutten|kuduthuten|kuduthen)\s+supplier\p{L}*""") to "return anuppitten",
        // rats, leaks, gone old, stolen
        r("""eli\s+(?:kadi|thinn|thin)\p{L}*|rats?\s+(?:ate|eaten|bit|kadi\p{L}*)""") to "wastage aachu",
        r("""(?:olug|ozhug|oluk)\p{L}*|leak(?:ed|age)?(?:\s+(?:aachu|aagiduchu|aayiduchu))?""") to "damage aachu",
        r("""pazhas\p{L}*|pazhaya(?:dhaa|tha)?\s+(?:aagiduchu|aayiduchu|aachu)""") to "kettupochu",
        r("""thirud\p{L}*|thiruttu\s+poch\p{L}*|kaanaama\s+poch\p{L}*|stolen""") to "missing aachu",
    )

    // ------------------------------------------------------------------ reminders

    /** "ezhuppi vidu", "thatti vidu", "maranthudaama sollu / paathuko": remind me — with a time said. */
    private val remindVerb = r(
        """ezhupp?(?:i|u)\s*(?:vidu|vidunga|vudu|udu|viduga)|ezhuppu|thatti\s*(?:vidu|vidunga|udu|kudu|sollu)|""" +
            """ma?ra[nr]?[dt]h?u?d(?:aa|a)ma\s*(?:sollu|sollunga|paathuko|paathukko|paarthuko|paathukonga|nyabagapaduthu|remind\s+pannu)?|""" +
            """marakkaama\s*(?:sollu|sollunga|paathuko|paathukko)|(?:nyabagam|gnabagam)\s+(?:paduthu|padutthu|paduthunga)""",
    )
    /** "eluthi vei": with a time, a reminder ("10 manikku GST kattanum eluthi vei"); with a person and amount, an entry. */
    private val writeDown = r("""(?:eluthi|ezhudhi|ezhuthi|eludhi|ezhuthu|eluthu)\s*(?:vei|vai|vechuko|vachuko|vechukko|vachukko|vachiko|vechiko|vechikko|ko|koo|kko)""")
    /**
     * "<to do> sollu" at the end with a time: "current bill katta sollu", "Selvam-kitta kekka sollu", "stock ennanum nu sollu" —
     * remind me to. Only after a verb ("katta", "kekka", "pannanum", "… nu"), never "innaiku sales sollu" (tell me the sales).
     */
    private val tellMeTo = Regex("""(?i)(?<![\p{L}\p{M}-])([\p{L}]+(?:a|um)|[\p{L}]*nu)\s+(?:sollu|sollunga|sollidu|paathuko|paathukko|paarthuko)\s*[.!]*$""")

    // ------------------------------------------------------------------ money

    private val moneyRules = listOf(
        // "pakki" is how many owners type "baaki"
        r("pakki|bakki") to "baaki",
        // "Kumar 500 baaki vechutu ponaan", "kadan sollitu poyitaan": left it owing — a credit
        r("""(?:baaki|kadan|credit|udhaar)\s+(?:vechutu|vechittu|vachuttu|vachitu|vachittu|sollitu|sollittu|solli)\s+(?:ponaan|ponan|poyitaan|poitaan|poyittaan|poyitan|ponaanga|ponanga|ponaru|ponaaru|poyittaanga)""") to "kadan",
        // "account-la potukko", "patru": put it on their record
        r("""account\s*-?\s*(?:la|le|il)\s+(?:potukko|pottuko|pottukko|potuko|podu|podunga|eluthu|ezhudhu|vei|vai|serthuko|sethuko)|patru""") to "note pannu",
        // "Lakshmi 1000 varavu vechaanga": they paid in
        r("""(?:varavu|வரவு)\s+(?:vech|vach|pott)(?:aanga|anga|aan|an|aaru|aar|aru|ar|ittaan|itaan|ittaanga)""") to "kuduthaanga",
        // "adaichuttaan", "katti mudichaan": paid it off
        r("""adai?ch(?:u|i)tt?(?:aan|an|aanga|anga|aaru|ar)|katti\s*mudich(?:aan|an|aanga|anga|aaru)""") to "kuduthaan",
        // "saraku kadanukku eduthen": bought on credit from a supplier
        r("""(?:saraku|maal|saamaan|saaman)?\s*kadanukk?u\s+(?:eduthen|eduthom|eduthutten|vaanginen|vanginen|vaangitten)""") to "credit la maal vaanginen",
        // "paadhi kaasu 3000": part of it — the amount is the amount (never a name "Paadhi")
        r("""(?:paadhi|paathi|paati|half)\s+(?:kaasu|panam|amount|payment)""") to "",
        // "pudhu customer Dinesh 600 kadan", "new supplier Vasanth 5000": the person is new — the line is about them (Kai's draft
        // says a new customer / supplier is added; owner, 19 Oct 2026)
        Regex("""(?i)^\s*(?:pudhu|puthu|new|oru\s+pudhu|oru\s+puthu)\s+(?:customer|customeru|supplier|aalu|aal|party|per|aaal)\s+(?=[\p{L}])""") to "",
    )
    /** "Murugan Stores-la 6000-ku … credit la maal vaanginen": from them, for that much. */
    private val boughtFrom = Regex("""(?i)^(.+?)\s*-?\s*(?:la|le|il)\s+(\d[\d,]*)\s*-?\s*(?:ku|kku)\s+credit la maal vaanginen""")

    // ------------------------------------------------------------------ days

    private val dayRules = listOf(
        // "naalanniki", "naalanaikku": the day after tomorrow
        r("""naa?lann?(?:i|a|ai)k+(?:i|u)""") to "naalanniku",
        // "ovvoru Saniyum", "Velliyum": that day, every week
        r("""(sani|velli|sevvai|pudhan|viyazhan|thingal|nyayiru|gnayiru|monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:kizhamai)?[yv]?um""") to "$1",
    )

    // ------------------------------------------------------------------ expiry

    private val expiryRules = listOf(
        r("""(?:date|thethi|thedhi|tedhi)\s+(?:mudiy|mudi)(?:ura|ra|apogura|a\s+pogura|pogudhu|udhu|uthu|um)|expiry\s+nerung\p{L}*|seekiram\s+kett?u\s*(?:pog|pōg)(?:um|ura|ra)""") to "expiry aagura",
        r("""best\s+before\s+(?:thaand|mudinj)\p{L}*|(?:date|thethi|thedhi)\s+(?:thaand|mudinj)\p{L}*""") to "expire aachu",
    )

    /** Money talk: a due date, what someone owes — "date thaandiduchu" there is a payment overdue, not goods expiring. */
    private val moneyCue = Regex("""(?i)""" + B + """(?:due|tharanum|tharavendiyadhu|kudukka\p{L}*|kudukanum|vaanganum|vanganum|payment|baaki|bakki|kadan|panam|kaasu|""" +
        """collection|vasool|pending|owe\p{L}*|customer\p{L}*|supplier\p{L}*)""" + E)

    /** The line as Kai's rules read it, or the line itself when nothing here applies. */
    fun canonical(text: String): String {
        var t = text
        for ((re, word) in thousands) t = re.replace(t) { m -> word + " " + if (m.groupValues[1] == "thu") "aayirathu" else "aayiram" }
        for ((re, word) in tens) t = re.replace(t, word)
        // ("due date thaandiduchu" is money overdue, never expiry)
        if (!moneyCue.containsMatchIn(t)) for ((re, to) in expiryRules) t = re.replace(t, to)
        for ((re, to) in dayRules) t = re.replace(t, to)
        val asks = question.containsMatchIn(t)
        val timed = timeCue.containsMatchIn(t)
        // Reminders first: "naalaiku 9 manikku … ezhuppi vidu" is a reminder whatever else the line holds.
        if (timed && !asks) {
            t = remindVerb.replace(t, "remind pannu")
            t = writeDown.replace(t, "remind pannu")
            if (!Regex("""(?i)remind""").containsMatchIn(t)) t = tellMeTo.replace(t) { m -> "${m.groupValues[1]} remind pannu" }
        } else if (quantity.containsMatchIn(t)) t = writeDown.replace(t, "note pannu")
        // "pakki" is "baaki" in a question too; the entry words only on a line with an amount that asks nothing.
        t = moneyRules.first().first.replace(t, moneyRules.first().second)
        if (quantity.containsMatchIn(t) && !asks) for ((re, to) in moneyRules.drop(1)) t = re.replace(t, to)
        boughtFrom.find(t.trim())?.let { m -> t = "${m.groupValues[1].trim()} kitta ${m.groupValues[2]} ku credit la maal vaanginen" }
        // Stock words only on a line with a count — "Colgate 2 box irakkumadhi aachu", never a sentence about something else.
        if (quantity.containsMatchIn(t) && !Regex("""(?i)remind""").containsMatchIn(t)) {
            // "rack-la", "godown-la": where it was put — the place, never a unit or part of the name.
            t = shelfPlace.replace(t, " ")
            for (re in stockIn) t = re.replace(t, "vandhuchu")
            for ((re, to) in stockOut) t = re.replace(t, to)
        }
        t = t.replace(Regex("""\s+"""), " ").trim()
        return if (t.equals(text.replace(Regex("""\s+"""), " ").trim(), ignoreCase = false)) text else t
    }

    /** Stock taken for the shop's own use ("veetukku pochu"). */
    fun ownUse(text: String): Boolean = Regex("""(?i)""" + B + """(?:veett?ukk?u|own\s+use|sondha\s+use|self\s+use)""" + E).containsMatchIn(text.lowercase(Locale.ROOT))
}
