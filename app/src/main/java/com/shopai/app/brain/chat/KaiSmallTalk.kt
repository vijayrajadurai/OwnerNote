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

    enum class Kind { ATE, DOING, BUSY, TIRED, STATUS, PRAISE, GREETING_MORNING, GREETING_AFTERNOON, GREETING_EVENING, GREETING_NIGHT, HELLO, HOW_ARE_YOU, THANKS, OK, BYE, NAME, WHO }

    private fun r(p: String) = Regex("""(?i)(?<![\p{L}])(?:$p)(?![\p{L}])""")

    /** A whole Tamil-script word or phrase (Tamil vowel signs are marks, not letters). */
    private fun ta(p: String) = Regex("""(?<![\p{L}\p{M}])(?:$p)(?![\p{L}\p{M}])""")

    private fun anyOf(vararg r: Regex) = Regex(r.joinToString("|") { "(?:${it.pattern})" })

    /**
     * A small-talk kind: [said] matches the message as written; [family] matches its casual
     * spelling key ([casualKey]) — the same question however it is spelled or heard
     * ("saptiya", "saptia", "saptya", "saaptiyaa" are one word to Kai).
     */
    private class Rule(val kind: Kind, val said: Regex, val family: Regex? = null)

    private val rules: List<Rule> = listOf(
        Rule(Kind.ATE, r("""saaptiya|saptiya|sapteya|saapteengala|sapteengala|saptingala|saaptingala|saapiteengala|saapadu\s*aachaa|saapadu\s*achaa|""" +
            """saaptacha|saaptachaa|sapta|saapta|did you eat|have you eaten|had (?:your )?(?:lunch|dinner|breakfast)|lunch\s*aachaa|tiffin\s*aachaa"""),
            // "saap(pi)t" + a question ending (-aa, -iya, -ingala, -acha); "saapten" / "saapadu" are not asking.
            family = anyOf(r("""sapi?t(?:[ie]?ya|[ie]ngala|acha|a)|did u eat|have u eaten"""), ta("""சாப்[\p{L}\p{M}]*ா"""))),
        Rule(Kind.HOW_ARE_YOU, r("""(?:eppadi|epdi|eppdi|yepdi)\s*irukk?eenga|(?:epdi|eppdi|yepdi)\s*irukk?a|eppadi\s*irukk?a|eppadi\s*iruka|nalla\s*irukk?eengala|nalla\s*irukk?iya|how are you|how r u|hows it going|how is it going"""),
            family = r("""(?:epadi|epdi|yepdi|yepadi) iru(?:ka|kiya|kinga|kenga|kingala|kengala)|nala iru(?:kiya|kingala|kengala)""")),
        Rule(Kind.DOING, r("""enna\s*panra|enna\s*panre|enna\s*panreenga|enna\s*pannitu\s*irukk?a|enna\s*pannikitu\s*irukk?a|what are you doing|wat r u doing|what's up|whats up|wassup""")),
        // Asking how Kai is ("romba tired ah iruka?", "busy ah irukiya?", "are you tired?") — not the owner saying they are.
        Rule(Kind.STATUS, r("""are you (?:tired|busy|free|ok|okay)|r u (?:tired|busy|free)"""),
            family = r("""(?:tired|busy|bijy|kalaipa|sorva)(?: ah?)? iru(?:ka|kiya|kinga|kingala|kengala)""")),
        Rule(Kind.BUSY, r("""busy|bijy|velai\s*adhigam|romba\s*velai|neraya\s*velai|time\s*illa""")),
        Rule(Kind.TIRED, r("""tired|kalaippa|kalaipa|tiredah|tired-ah|soarvaa|sorva|mudiyala|thookam\s*varudhu|thookama|bore\s*adikk?u(dhu|thu|du)|boring|bore\s*ah\s*irukku""")),
        Rule(Kind.GREETING_MORNING, r("""good\s*morning|gud\s*morning|gm""")),
        Rule(Kind.GREETING_AFTERNOON, r("""good\s*afternoon""")),
        Rule(Kind.GREETING_EVENING, r("""good\s*evening""")),
        Rule(Kind.GREETING_NIGHT, r("""good\s*night|gn""")),
        Rule(Kind.THANKS, r("""thanks|thank\s*you|thank u|thx|nandri|romba\s*nandri"""), family = r("""thanks?|thank ?u|thanku|tq|tnx|tanks|nandri|nanri""")),
        Rule(Kind.BYE, r("""bye|bye\s*bye|poitu\s*varen|poittu\s*varen|see you|apram\s*pesalam|appuram\s*pesalam""")),
        // Kai's name: "un name enna?", "unga peru enna?", "what's your name?", "உன் பேர் என்ன?".
        Rule(Kind.NAME, r("""unga\s*peru\s*enna|un\s*peru\s*enna|your name"""),
            family = anyOf(
                r("""(?:un|unga|ungal|unoda|ungaloda|your|ur) (?:name|per|peru|pere|peyar|peyaru)|what is (?:your|ur) name|whats (?:your|ur) name"""),
                ta("""(?:உன்|உங்க|உங்கள்|உன்னோட|உங்களோட)\s*(?:பேர்|பேரு|பெயர்)"""),
            )),
        // Who Kai is: "nee yaaru?", "who are you?", "நீ யாரு?".
        Rule(Kind.WHO, r("""nee\s*yaaru|nee\s*yaar|neenga\s*yaaru|who are you"""),
            family = anyOf(
                r("""(?:ni|ne|nenga|ningal) yaru?|yaru? (?:ni|ne|nenga)|who (?:are|r) (?:you|u)"""),
                ta("""(?:நீ|நீங்க|நீங்கள்)\s*(?:யாரு|யார்)|(?:யாரு|யார்)\s*(?:நீ|நீங்க)"""),
            )),
        Rule(Kind.PRAISE, r("""super|semma|nice|great|awesome|good\s*job|well\s*done|sema|(i\s*)?love\s*(you|u)|luv\s*(you|u)|unna\s*pidikkum|romba\s*pidikkum""")),
        Rule(Kind.HELLO, r("""hi|hii|hello|helo|hey|vanakkam|vanakam|kai"""), family = r("""hi|helo|hey|vanakam""")),
        Rule(Kind.OK, r("""ok|okay|okk|seri|sari|good"""), family = r("""ok|okay|hm""")),
    )

    /**
     * The message's casual spelling key — used ONLY to recognise small talk, never passed on:
     * lower case, repeated letters once ("saaptiyaa" → "saptiya"), and "consonant-y-vowel" /
     * a final "-ia" spelled as "-iya" ("saptya", "saptia" → "saptiya"). Messages with names,
     * amounts or business words never get here (they are not small talk, see [kindOf]).
     */
    internal fun casualKey(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("""(\p{L})\1+"""), "$1")
        .replace(Regex("""(?<=[bcdfghjklmnpqrstvwxz])y(?=[aeiou])"""), "iy")
        .replace(Regex("""(?<=[bcdfghjklmnpqrstvwxz])ia(?![\p{L}])"""), "iya")
        .replace(Regex("""\s+"""), " ")
        .trim()

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
        val key = casualKey(text)
        return rules.firstOrNull { it.said.containsMatchIn(text) || it.family?.containsMatchIn(key) == true }?.kind
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
                "குட் மார்னிங் ஓனர் ☀️ இன்னைக்கு business-ஐ ஆரம்பிக்கலாமா?",
                "Good morning Owner ☀️ Innaiku business-a start pannalama?",
                "Good morning, Owner ☀️ Shall we start today's business?",
            )
            Kind.PRAISE -> pick("நன்றி ஓனர் 😄", "Thanks Owner 😄", "Thanks, Owner 😄")
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
            Kind.STATUS -> pick(
                "கொஞ்சம் busy தான் ஓனர் 😄 ஆனா உங்களுக்கு எப்பவும் ready. என்ன உதவி வேணும்?",
                "Konjam busy dhaan Owner 😄 Aana ungalukku eppavum ready. Enna help venum?",
                "A little busy, Owner 😄 but always ready for you. What do you need?",
            )
            Kind.NAME -> pick(
                "என் பேர் Kai ஓனர் 😊 நான் உங்க business assistant.",
                "En peru Kai Owner 😊 Naan unga business assistant.",
                "My name is Kai, Owner 😊 I'm your business assistant.",
            )
            Kind.WHO -> pick(
                "நான் Kai — உங்க கடை உதவியாளர் 😊 கணக்கு, stock, reminder, bill எல்லாம் பார்த்துக்கறேன்.",
                "Naan Kai — unga kadai assistant 😊 Kanakku, stock, reminder, bill ellam paathukaren.",
                "I'm Kai — your shop assistant 😊 Books, stock, reminders and bills.",
            )
        }
    }
}
