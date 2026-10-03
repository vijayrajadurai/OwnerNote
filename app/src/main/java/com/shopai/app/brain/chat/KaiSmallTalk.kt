package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.KaiSpokenWords
import java.util.Locale

/**
 * Kai talks like a friend too: "saaptiya?", "enna panra?", "innaiku romba
 * busy", "good morning" get a short, warm answer — no forced business
 * intent, no "konjam clear-ah sollunga". Only whole small-talk messages
 * count: anything with an amount or a business word goes to the business
 * rules instead.
 */
object KaiSmallTalk {

    enum class Kind { ATE, DOING, BUSY, TIRED, GREETING_MORNING, GREETING_AFTERNOON, GREETING_EVENING, GREETING_NIGHT, HELLO, HOW_ARE_YOU, THANKS, OK, BYE, WHO }

    private fun r(p: String) = Regex("""(?i)(?<![\p{L}])(?:$p)(?![\p{L}])""")

    private val rules: List<Pair<Kind, Regex>> = listOf(
        Kind.ATE to r("""saaptiya|saptiya|sapteya|saapteengala|sapteengala|saptingala|saaptingala|saapiteengala|saapadu\s*aachaa|saapadu\s*achaa|""" +
            """saaptacha|saaptachaa|sapta|saapta|did you eat|have you eaten|had (?:your )?(?:lunch|dinner|breakfast)|lunch\s*aachaa|tiffin\s*aachaa"""),
        Kind.HOW_ARE_YOU to r("""eppadi\s*irukk?eenga|eppadi\s*irukk?a|eppadi\s*iruka|nalla\s*irukk?eengala|nalla\s*irukk?iya|how are you|how r u|hows it going|how is it going"""),
        Kind.DOING to r("""enna\s*panra|enna\s*panre|enna\s*panreenga|enna\s*pannitu\s*irukk?a|enna\s*pannikitu\s*irukk?a|what are you doing|wat r u doing|what's up|whats up|wassup"""),
        Kind.BUSY to r("""busy|bijy|velai\s*adhigam|romba\s*velai|neraya\s*velai|time\s*illa"""),
        Kind.TIRED to r("""tired|kalaippa|kalaipa|tiredah|tired-ah|soarvaa|sorva|mudiyala|thookam\s*varudhu|thookama"""),
        Kind.GREETING_MORNING to r("""good\s*morning|gud\s*morning|gm"""),
        Kind.GREETING_AFTERNOON to r("""good\s*afternoon"""),
        Kind.GREETING_EVENING to r("""good\s*evening"""),
        Kind.GREETING_NIGHT to r("""good\s*night|gn"""),
        Kind.THANKS to r("""thanks|thank\s*you|thank u|thx|nandri|romba\s*nandri"""),
        Kind.BYE to r("""bye|bye\s*bye|poitu\s*varen|poittu\s*varen|see you|apram\s*pesalam|appuram\s*pesalam"""),
        Kind.WHO to r("""nee\s*yaaru|nee\s*yaar|neenga\s*yaaru|who are you|unga\s*peru\s*enna|un\s*peru\s*enna|your name"""),
        Kind.HELLO to r("""hi|hii|hello|helo|hey|vanakkam|vanakam|kai"""),
        Kind.OK to r("""ok|okay|okk|seri|sari|super|nice|good|semma"""),
    )

    /** Words that make a message business, not chit-chat. */
    private val business = r(
        """stock|bill|cash|panam|kaasu|sales|sale|purchase|payment|pay|balance|baaki|bakki|pending|due|remind|reminder|call|phone|customer|supplier|""" +
            """kuduthen|vanginen|kudukkanum|tharanum|evlo|evvalavu|how much|gst|profit|loss|report|summary|inventory|add|scan|collect|vasool|kanakku|morning work"""
    )

    /** Small talk → its kind; null when the message is not (only) small talk. */
    fun kindOf(raw: String): Kind? {
        val text = KaiSpokenWords.normalize(raw).lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{M}\s']"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty() || raw.any(Char::isDigit) || business.containsMatchIn(text)) return null
        if (text.split(' ').size > 8) return null
        return rules.firstOrNull { it.second.containsMatchIn(text) }?.first
    }

    /** Kai's answer in the owner's language, or null when it isn't small talk. */
    fun reply(raw: String, lang: KaiLang, hour: Int): String? {
        val kind = kindOf(raw) ?: return null
        fun pick(ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }
        return when (kind) {
            Kind.ATE -> pick(
                "சாப்டேன் ஓனர் 😄 நீங்க சாப்டீங்களா?",
                "Saapten Owner 😄 Neenga saaptingala?",
                "I'm all fuelled up, Owner 😄 Have you eaten?",
            )
            Kind.HOW_ARE_YOU -> pick(
                "நல்லா இருக்கேன் ஓனர் 😊 நீங்க எப்படி இருக்கீங்க?",
                "Nalla irukken Owner 😊 Neenga eppadi irukkeenga?",
                "I'm good, Owner 😊 How are you?",
            )
            Kind.DOING -> pick(
                "உங்க கடை கணக்கை பார்த்துட்டு இருக்கேன் ஓனர் 😄 உங்களுக்கு என்ன உதவி வேணும்?",
                "Unga kadai kanakku paathutu irukken Owner 😄 Ungalukku enna help venum?",
                "Keeping an eye on your shop's books, Owner 😄 What can I do for you?",
            )
            Kind.BUSY -> pick(
                "சரி ஓனர், busy-ஆ இருக்கீங்க 💪 Reminder, stock, bill — எதுனாலும் சொல்லுங்க, நான் பார்த்துக்கறேன்.",
                "Seri Owner, busy-ah irukkeenga 💪 Reminder, stock, bill — edhuvaanalum sollunga, naan paathukaren.",
                "Got it, Owner — a busy day 💪 Tell me any reminder, stock or bill and I'll handle it.",
            )
            Kind.TIRED -> pick(
                "கொஞ்சம் ரெஸ்ட் எடுங்க ஓனர் ☕ கடை வேலை நான் நினைவில் வெச்சுக்கறேன்.",
                "Konjam rest edunga Owner ☕ Kadai velai naan nyabagam vechukiren.",
                "Take a little rest, Owner ☕ I'll keep track of the shop work.",
            )
            Kind.GREETING_MORNING -> pick(
                "குட் மார்னிங் ஓனர் ☀️ இன்னைக்கு நல்ல வியாபாரம் ஆகட்டும்!",
                "Good morning Owner ☀️ Innaiku nalla vyabaaram aagattum!",
                "Good morning, Owner ☀️ Wishing you a great business day!",
            )
            Kind.GREETING_AFTERNOON -> pick("குட் ஆஃப்டர்நூன் ஓனர் 🙂", "Good afternoon Owner 🙂 Saaptingala?", "Good afternoon, Owner 🙂")
            Kind.GREETING_EVENING -> pick("குட் ஈவினிங் ஓனர் 🌆 இன்னைக்கு வியாபாரம் எப்படி?", "Good evening Owner 🌆 Innaiku vyabaaram eppadi?", "Good evening, Owner 🌆 How was business today?")
            Kind.GREETING_NIGHT -> pick("குட் நைட் ஓனர் 🌙 நல்லா தூங்குங்க.", "Good night Owner 🌙 Nalla thoongunga.", "Good night, Owner 🌙 Sleep well.")
            Kind.HELLO -> {
                val part = when (hour) { in 4..11 -> 0; in 12..15 -> 1; in 16..20 -> 2; else -> 3 }
                pick(
                    listOf("வணக்கம் ஓனர் ☀️", "வணக்கம் ஓனர் 🙂", "வணக்கம் ஓனர் 🌆", "வணக்கம் ஓனர் 🌙")[part] + " என்ன உதவி வேணும்?",
                    "Vanakkam Owner " + listOf("☀️", "🙂", "🌆", "🌙")[part] + " Enna help venum?",
                    "Hello, Owner " + listOf("☀️", "🙂", "🌆", "🌙")[part] + " What can I do for you?",
                )
            }
            Kind.THANKS -> pick("பரவாயில்ல ஓனர் 🙏", "Welcome Owner 🙏 Eppo venumnaalum sollunga.", "You're welcome, Owner 🙏")
            Kind.OK -> pick("சரி ஓனர் 👍", "Seri Owner 👍", "Okay, Owner 👍")
            Kind.BYE -> pick("சரி ஓனர், அப்புறம் பேசலாம் 👋", "Seri Owner, apram pesalaam 👋", "Okay Owner, talk soon 👋")
            Kind.WHO -> pick(
                "நான் Kai — உங்க கடை உதவியாளர் 😊 கணக்கு, stock, reminder, bill எல்லாம் பார்த்துக்கறேன்.",
                "Naan Kai — unga kadai assistant 😊 Kanakku, stock, reminder, bill ellam paathukaren.",
                "I'm Kai — your shop assistant 😊 Books, stock, reminders and bills.",
            )
        }
    }
}
