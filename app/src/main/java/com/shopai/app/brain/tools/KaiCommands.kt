package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiUnderstanding
import com.shopai.app.books.model.PaymentMode
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Locale

/** Which money account a balance question is about. */
enum class MoneyKind { CASH, BANK, UPI, ALL }

/**
 * What the owner wants Kai to DO (or a question Kai answers from the books).
 * Anything else falls through to Kai's Business Brain answers.
 */
sealed interface KaiCommand {
    /** "10 * 3", "25000 la 18% GST evlo?" — answered at once, no confirmation. */
    data class Calculate(val answer: KaiCalculator.Answer) : KaiCommand

    /**
     * "Ramesh ku 5000 kuduthen" (money out) / "Kumar kitta 10000 vanginen" (money in).
     * Becomes a draft; nothing is saved until the owner confirms.
     */
    data class Payment(val name: String?, val amount: BigDecimal?, val outgoing: Boolean, val mode: PaymentMode, val modeSaid: Boolean) : KaiCommand

    /** Create / list / cancel / complete / snooze / change a reminder (the reminder engine). */
    data class Reminder(val request: ReminderRequest) : KaiCommand

    /** "Kumar-ku call pannu" — open the dialer for Kumar (the owner presses call). */
    data class Call(val name: String) : KaiCommand

    /** "Indha bill add pannu" / "Indha bill purchase ah?" — the bill scanner (OCR → draft → review → confirm). */
    data class ScanBill(val classifyOnly: Boolean) : KaiCommand

    /** "Rice stock evlo?" ([product] null: overall stock). */
    data class Stock(val product: String?) : KaiCommand
    /** "Which stock is low?", "Enna reorder pannanum?" */
    data object LowStock : KaiCommand
    /** "Enakku evlo cash iruku?", "Bank la evlo iruku?" */
    data class MoneyBalance(val kind: MoneyKind) : KaiCommand
    /** "Indha month highest selling product enna?", "Yentha stock fast move aguthu?" */
    data object TopProducts : KaiCommand
    /** "Yentha stock move agala?", "Dead stock enna?": products in stock that did not sell lately. */
    data object SlowStock : KaiCommand

    /** A question for the Business Brain. */
    data object Question : KaiCommand
}

/**
 * Deterministic intent detection for Kai's actions — Tamil, Tanglish and
 * English rules, no AI. Decides WHAT the owner wants; the tools decide HOW.
 */
object KaiCommands {

    fun route(raw: String, now: LocalDateTime, knownPeople: List<String>): KaiCommand {
        val text = raw.trim().replace(Regex("""\s+"""), " ")
        if (text.isEmpty()) return KaiCommand.Question
        val t = " " + text.lowercase(Locale.ROOT).replace(Regex("""[?!.,;]"""), " ").replace(Regex("""\s+"""), " ") + " "
        fun has(vararg w: String) = w.any { word -> if (word.first().isLetter() && word.last().isLetter()) Regex("""(?<![\p{L}])${Regex.escape(word)}(?![\p{L}])""").containsMatchIn(t) else t.contains(word) }

        val remindWords = has("remind", "reminder", "reminders", "nyabagam", "nyabaga", "gnabagam", "gnyabagam", "nyaabagam", "ninaivu", "ninaivupaduthu",
            "ninaivu paduthu", "marakkama", "marakkaama", "marakkadha", "alert", "alarm", "நினைவூட்டு", "ஞாபகம்", "மறக்காம", "ரிமைண்டர்")
        val callWords = has("call", "phone", "kaal", "கால்", "போன்")
        val time = KaiTime.parse(text, now)
        val questionWords = has("evlo", "evvalavu", "enna", "eppo", "yaar", "yaaru", "how", "what", "when", "who", "which", "எவ்வளவு", "என்ன", "எப்போ") || raw.contains('?')

        // ---- documents ----
        val billWords = has("bill", "invoice", "receipt", "பில்", "ரசீது")
        if (billWords && has("purchase ah", "purchase-ah", "sale ah", "sales ah", "sale-ah", "enna bill", "which type", "what type", "what kind", "purchase or sale", "classify")) {
            return KaiCommand.ScanBill(classifyOnly = true)
        }
        if (KaiIntents.isBillScan(text)) return KaiCommand.ScanBill(classifyOnly = false)

        // ---- reminders (any phrasing) ----
        KaiReminderUnderstanding.understand(text, now, knownPeople)?.let { return KaiCommand.Reminder(it) }
        // ---- call now ----
        if (callWords && time == null && has("pannu", "pannunga", "call pannu", "call", "podu", "poodu") && !questionWords) {
            personIn(text, knownPeople)?.let { return KaiCommand.Call(it) }
        }

        // ---- calculation ----
        KaiCalculator.solve(text)?.let { return KaiCommand.Calculate(it) }

        // ---- which stock moves, which doesn't (from the sales records) ----
        // "vikkala", "pogala", "fast-a pogudhu" alone are everyday words: they mean stock only next to a stock / item / which word.
        val aboutStock = stockThings.containsMatchIn(t)
        if (slowMoving.containsMatchIn(t) || aboutStock && slowVerbs.containsMatchIn(t)) return KaiCommand.SlowStock
        if (fastMoving.containsMatchIn(t) || aboutStock && fastVerbs.containsMatchIn(t)) return KaiCommand.TopProducts

        // ---- money given / received ----
        payment(text, t, ::has, questionWords, knownPeople)?.let { return it }

        // ---- stock, money, top products (from the books) ----
        val stockWords = has("stock", "inventory", "iruppu", "இருப்பு", "ஸ்டாக்")
        val itemWords = stockWords || has("product", "products", "item", "items", "porul", "saamaan", "saman", "maal", "பொருள்")
        if (has("reorder", "re-order", "re order", "out of stock") || (itemWords && has("low", "kammi", "kammiya", "kuraivu", "kuraivaa", "theerndhu", "theernthu", "mudinjiruchu", "finish", "kuranj", "குறைவு"))) {
            return KaiCommand.LowStock
        }
        if (has("highest selling", "top selling", "best selling", "most sold", "adhigama vithadhu", "athigama vithadhu", "adhigama vikkudhu", "top product", "best product", "adhigama vithathu",
                "adhigama vithuchu", "athigama vithuchu", "adhigam vithuchu", "adhigama vikkuthu", "athigama vikkuthu")) {
            return KaiCommand.TopProducts
        }
        if (stockWords) {
            // "Today stock evlo iruku", "ippo motham stock": every product — a time or "all" word is not a product name.
            val product = Regex("""^(.*?)\s*(?:stock|inventory|iruppu|இருப்பு|ஸ்டாக்)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                ?.replace(Regex("""(?i)\b(enakku|ennaku|my|the|how much|what is|whats|what's|enna|evlo|kadaila|shop la|la|oda|ku|today|todays|today's|innaiku|innaikku|inniku|innikku|""" +
                    """ippo|ippa|ipo|current|currently|now|total|motham|mothama|ella|ellaa|all|full|kadai|shop|yentha|endha|entha|which|ennoda|unga|namma)\b"""), " ")
                ?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.length >= 2 && it.any(Char::isLetter) }
            return KaiCommand.Stock(product)
        }
        val balanceWords = has("iruku", "irukku", "irukka", "balance", "kaila", "kaiyila", "kaiyil", "in hand", "do i have", "have", "available", "இருக்கு")
        val flowWords = has("vandh", "vanth", "pona", "ponadhu", "poch", "today", "inniku", "innaikku", "in", "out", "came", "spent", "sent")
        if (balanceWords && !flowWords) {
            when {
                has("bank", "account la", "பேங்க்") -> return KaiCommand.MoneyBalance(MoneyKind.BANK)
                has("upi", "gpay", "phonepe", "paytm") -> return KaiCommand.MoneyBalance(MoneyKind.UPI)
                has("cash", "kaasu", "panam", "rokkam", "ரொக்கம்", "பணம்") -> return KaiCommand.MoneyBalance(MoneyKind.CASH)
            }
        }
        return KaiCommand.Question
    }

    // ------------------------------------------------------------ payments

    private val outWords = listOf("kuduthen", "koduthen", "kuduthuten", "kuduthutten", "koduthuten", "kuduthaachu", "kuduthachu", "kuduthiten", "kudutha",
        "gave", "given", "paid", "pay panniten", "payment panniten", "pay pannen", "anuppinen", "anupinen", "anuppiten", "anupiten", "sent",
        // The owner paid by UPI / GPay ("Ramesh-ku 400 GPay pannen", "pay pannitten").
        "pay pannitten", "payment pannitten", "gpay pannen", "gpay panniten", "gpay pannitten", "upi pannen", "upi pannitten", "transfer pannen", "transfer pannitten",
        "கொடுத்தேன்", "கொடுத்துட்டேன்", "அனுப்பினேன்", "கொடுத்தாச்சு", "குடுத்தேன்", "குடுத்துட்டேன்") + KaiFeed.paidWords
    private val inWords = listOf("vanginen", "vaanginen", "vangunen", "vaangunen", "vangiten", "vaangiten", "vanginaen", "vangitten", "received", "got",
        "vandhuchu", "vanthuchu", "vandhudhu", "thandhan", "thandhaan", "thandhaar", "thanthan", "kuduthan", "kuduthaan", "kuduthaar", "koduthan", "koduthaan", "koduthaar",
        // "Kumar 2000 kuduthutaan": he gave (and it's done) — the owner received it.
        "kuduthutaan", "kuduthuttaan", "kuduthutan", "kuduthittaan", "koduthutaan", "koduthuttaan", "kuduthutaanga", "kuduthaanga", "kuduthanga",
        "kuduthutaar", "thandhutaan", "thanthutaan", "கொடுத்துட்டான்", "கொடுத்தாங்க",
        // The person sent / paid / returned it ("Kumar 500 GPay pannitaan", "anuppitaan"), or the owner collected it.
        "kuduthutaru", "kuduthuttaru", "kuduthuttan", "kuduthuttaanga", "kuduthutaanga", "anuppitaan", "anupitaan", "anuppinaan", "anupinaan", "anuppunaan",
        "anuppinaar", "anuppitaar", "anuppitaanga", "anuppinaanga", "pay pannitaan", "pay pannitaar", "pay pannitaanga", "pay pannaan", "payment pannitaan",
        "gpay pannitaan", "gpay pannaan", "gpay pannitaar", "upi pannitaan", "upi pannaan", "transfer pannitaan", "return pannitaan", "return pannaan",
        "return pannitaar", "collect pannitten", "collect panniten", "collect pannen", "vasool pannitten", "vasool panniten",
        "குடுத்தான்", "குடுத்தாங்க", "குடுத்தார்", "குடுத்துட்டான்", "கொடுத்தார்", "கொடுத்துட்டாங்க", "அனுப்பினான்", "அனுப்பிட்டான்",
        "வாங்கினேன்", "வந்துச்சு", "தந்தான்", "தந்தார்", "கொடுத்தான்") + KaiFeed.receivedWords
    /** "Suresh gpay la 5000 pay pannan", "5000 transfer pannittaan": the person paid (any spelling of the 3rd-person past). */
    private val personPaidVerb = Regex("""(?i)(?<![\p{L}])(pay|gpay|g\s*pay|upi|phonepe|paytm|transfer|payment|return|settle|send|online)\s*(?:-?\s*la\s+)?(pannan|pannaan|pannaar|pannar|pannaru|pannaaru|pannanga|pannaanga|pannitan|pannitaan|pannittan|pannittaan|pannitar|pannitaar|pannitaru|pannitaaru|pannittaru|pannittaaru|pannitanga|pannitaanga|pannunan|pannunaan|pannunaar|pannunaru|pannirukkaan|pannirukkan|pannirukaan|pannirukkaar|pannirukkaru|pannirukkaanga)(?![\p{L}])""")
    /** "Ramesh-ku 400 GPay pannen", "transfer pannitten": the owner paid. */
    private val ownerPaidVerb = Regex("""(?i)(?<![\p{L}])(pay|gpay|g\s*pay|upi|phonepe|paytm|transfer|payment|send|online)\s*(?:-?\s*la\s+)?(pannen|panninen|pannunen|panniten|pannitten|pannittaen|pannirukken|pannirken)(?![\p{L}])""")
    /** "anuppinan", "anuppichan", "anupitaanga": they sent it (any spelling of the 3rd-person past of anuppu). */
    private val personSent = Regex("""(?i)(?<![\p{L}])anupp?[iu](?:n|tt?|ch{1,2}|cch)?(?:aan|an|aar|ar|aru|aaru|aanga|anga)(?![\p{L}])""")
    /** "kuduthaaru", "kuduthar", "koduthutaanga", "thandhaaru": they gave it (any spelling of the 3rd-person past of kudu / thaa). */
    private val personGave = Regex("""(?i)(?<![\p{L}])(?:kudh?u?th|kodh?u?th|thandh|thanth)(?:u?tt?|it?t?)?(?:aan|an|aar|ar|aaru|aru|aanga|anga)(?![\p{L}])""")
    /** "anuppinen", "anuppichen", "anupiten": the owner sent it. */
    private val ownerSent = Regex("""(?i)(?<![\p{L}])anupp?[iu](?:n|tt?|ch{1,2}|cch)?(?:en|een|aen)(?![\p{L}])""")
    private val goodsWords = Regex("""(?i)\b(kg|kgs|kilo|litre|ltr|bag|bags|pcs|pieces|packet|box|dozen|rice|arisi|sugar|oil|maavu|paal)\b""")

    private fun payment(text: String, t: String, has: (Array<out String>) -> Boolean, question: Boolean, known: List<String>): KaiCommand.Payment? {
        fun hasAny(list: List<String>) = has(list.toTypedArray())
        val out = hasAny(outWords) || ownerPaidVerb.containsMatchIn(t) || ownerSent.containsMatchIn(t)
        val incoming = hasAny(inWords) || personPaidVerb.containsMatchIn(t) || personSent.containsMatchIn(t) || personGave.containsMatchIn(t)
        if (out == incoming) return null
        // "Kuduthana?" (did he give?), "5000 pending", "tharanum" — questions or dues, not something that happened.
        // "full ah pay pannitaru", "cash ah kuduthaan": that "ah" says how it was paid — not a question.
        val howPaid = Regex("""(?<![\p{L}])(full|motham|mothama|muzhusa|cash|gpay|upi|online|seekiram)\s+(?:ah|aa)(?![\p{L}])""").replace(t, "$1")
        if (question || has(arrayOf("pending", "baaki", "bakki", "tharanum", "kudukkanum", "kodukkanum", "varanum", "remind", "பாக்கி", "தரணும்", "குடுக்கணும்", "கொடுக்கணும்")) ||
            Regex("""\s(?:ah|aa)\s""").containsMatchIn(" $howPaid ")) return null
        // "10 kg rice vanginen" is buying goods, not a payment.
        if (goodsWords.containsMatchIn(text)) return null
        val amounts = KaiUnderstanding.amountsIn(text, java.time.LocalDate.now()).filter { it > 0 }
        // "Kumar -500 kuduthaan": a minus amount is not a payment amount — Kai asks how much (never drops the sign).
        val negative = Regex("""(?<![\p{L}\p{N}])-\s*(?:₹|rs\.?\s*)?\d""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val amount = amounts.singleOrNull()?.takeUnless { negative }?.let { BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP) }
        val (mode, said) = when {
            has(arrayOf("upi", "gpay", "g pay", "google pay", "phonepe", "phone pe", "paytm")) -> PaymentMode.UPI to true
            // "account-la podalama" is a request to record the transaction, not a bank transfer.
            has(arrayOf("bank", "neft", "imps", "rtgs", "transfer")) -> PaymentMode.BANK_TRANSFER to true
            has(arrayOf("cheque", "check")) -> PaymentMode.CHEQUE to true
            has(arrayOf("card")) -> PaymentMode.CARD to true
            has(arrayOf("cash", "rokkam", "kaasa")) -> PaymentMode.CASH to true
            has(arrayOf("account la", "account-la")) -> PaymentMode.BANK_TRANSFER to true
            else -> PaymentMode.CASH to false
        }
        // Direction follows who received the money. "Kumar gave me 5k" is incoming;
        // a bare "Kumar gave 5k" stays ambiguous and is not guessed as a payment.
        val ownerIsRecipient = has(arrayOf("me", "to me", "for me", "enakku", "enaku", "எனக்கு"))
        val ownerIsPayer = has(arrayOf("i gave", "i paid", "naan kuduth", "naan koduth", "நான் கொடுத்த"))
        // "Kumar paid 500" / "Kumar 500 rs paid today": the person is the one who paid (English names the payer first);
        // "I paid Kumar", "paid 500 to Ramesh", "Kumar-ku 500 sent" name the owner as the payer.
        val person = personIn(text, known)
        val personPaid = person != null && Regex("""(?<![\p{L}])(paid|gave|sent)(?![\p{L}])""").find(t)?.range?.first?.let { verb ->
            val at = t.indexOf(person.lowercase(java.util.Locale.ROOT))
            at in 0 until verb && !Regex("""(?<![\p{L}])${Regex.escape(person.lowercase(java.util.Locale.ROOT))}\s*-?\s*(?:ku|kku|ukku)(?![\p{L}])""").containsMatchIn(t) && !has(arrayOf("to"))
        } == true
        val outgoing = when {
            ownerIsPayer -> true
            personPaid -> false
            ownerIsRecipient && has(arrayOf("gave", "given", "paid", "received", "got", "kuduth", "koduth", "தந்த", "கொடுத்த")) -> false
            else -> out
        }
        return KaiCommand.Payment(person, amount, outgoing = outgoing, mode = mode, modeSaid = said)
    }

    // ------------------------------------------------------------ people / words

    private val notNames = setOf(
        "naan", "nan", "enakku", "ennaku", "avan", "aval", "avar", "avanga", "owner", "sir", "please", "pls", "call", "phone", "remind", "reminder",
        "daily", "every", "today", "tomorrow", "naalaikku", "naalaiku", "nalaiku", "inniku", "innaikku", "cash", "upi", "gpay", "bank", "money", "panam",
        "kaasu", "rupees", "rs", "minutes", "minute", "hour", "hours", "kalichu", "kalichi", "later", "after", "the", "to", "from", "for", "i", "me",
        "my", "kaalaila", "morning", "evening", "night", "indha", "intha", "supplier", "customer", "payment", "advance", "total", "bill", "kadai",
        "kadaiku", "shop", "saavi", "key", "keys", "ok", "okay", "hi", "hello", "kai", "bro", "anna", "akka", "thambi",
        // Stems of time words ("maniku", "naalaikku", "inniku") — never names.
        "mani", "naalai", "nalai", "inni", "innai", "kadai", "veetu", "veedu", "kaalai", "office", "school", "hospital", "bank", "week", "month",
        "am", "pm", "reminder", "time", "adha", "andha", "idha", "indha", "daily", "weekly", "monthly", "thethi",
        // What is left of a time word once "-ku" is read as the dative ("naalaik|ku", "manik|ku", "innaik|ku").
        "naalaik", "nalaik", "naalaikk", "manik", "manikk", "innaik", "innik", "innaikk", "kadaik", "veetuk", "officek", "evening", "stock",
        "kalichi", "apram", "aprom", "appuram", "nimisham", "minutes", "la", "le",
        // Verbs after "call" ("call panna", "call pannu") — never names.
        "panna", "pannu", "pannunga", "pannanum", "panni", "pannidu", "pannalama", "pannava", "podu", "sollu", "message", "whatsapp", "back",
        "vandhiru", "vanthiru", "vandhuru", "iru", "irukku",
        // Places, things and errands in personal reminders ("car service-ku", "gym-ku", "school-ku") — never names.
        "service", "servicing", "gym", "car", "bike", "vandi", "medicine", "marunthu", "maathirai", "tablet", "current", "eb", "gas", "cylinder",
        "documents", "document", "walking", "walk", "college", "tuition", "class", "temple", "kovil", "church", "market", "hotel", "station",
        "airport", "bus", "train", "court", "function", "marriage", "kalyanam", "party", "meeting", "pickup", "drop", "gate", "rent", "emi", "loan",
        "home", "house", "veettu", "ration", "iruk", "irukk", "iruku",
        // "enaku" / "enakku" / "yenakku" read as "ena" + "-ku" — the owner, never a name.
        "ena", "enak", "enakk", "enna", "yena", "yenak", "yenakk", "yenna", "una", "unak", "yaar", "yaaru", "yar", "yaru",
        // When / how words and the days of the week ("Yeppa-ku", "Friday kitta") — never names.
        "eppa", "yeppa", "yeppo", "yepo", "epo", "eppadi", "yeppadi", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "velli", "sani", "nyayiru", "thingal", "sevvai", "budhan", "vyazhan",
        // A clock / day word right before "call" ("10 maniku call remind pannu") — never names.
        "maniku", "manikku", "naalaiku", "naalaikku", "nalaiku", "innaiku", "innaikku", "inniku", "innikku", "saayangalam", "raathiri", "madhiyam",
    ) + KaiFeed.notNames

    /** "move agala", "dead stock", "slow moving", "unsold": stock that is not selling. */
    private val slowMoving = Regex(
        """(?<![\p{L}])(move\s*-?\s*(?:agala|aagala|aagalai|agalai|aaga\s*maattengudhu|aagudhu\s*illa|aguthu\s*illa|aagave\s*illa|agave\s*illa)|""" +
            """not\s+moving|non\s*-?\s*moving|slow\s*-?\s*moving|dead\s*stock|unsold|not\s+selling|didn'?t\s+sell|not\s+sold)(?![\p{L}])|நகரல""",
        RegexOption.IGNORE_CASE,
    )
    /** "vikkala", "pogala", "thengi irukku", "slow-aa": not selling — when said about stock / items. */
    private val slowVerbs = Regex(
        """(?<![\p{L}])(vikkala|vikkalai|vikkalaye|vikkave\s*illa|vikkavey\s*illa|vitkala|vikka\s*maattengudhu|pogala|pogave\s*illa|thengi|thaengi|""" +
            """thangi\s*irukku|slow\s*-?\s*(?:aa|ah|a|move\p{L}*|pogu\p{L}*|vikk\p{L}*))(?![\p{L}])|விக்கல|தேங்கி""",
        RegexOption.IGNORE_CASE,
    )
    /** "fast move aguthu", "fast-a vikkudhu", "fast moving": what sells most. */
    private val fastMoving = Regex(
        """(?<![\p{L}])(fast\s*-?\s*(?:a|ah|aa)?\s*(?:move\p{L}*|moving|vikk\p{L}*|vith\p{L}*|sell\p{L}*)|vegama\s*(?:vikk\p{L}*|vith\p{L}*|move\p{L}*)|""" +
            """seekiram\s*(?:vikk\p{L}*|vith\p{L}*|theer\p{L}*)|sikkiram\s*(?:vikk\p{L}*|vith\p{L}*)|moving\s+(?:items?|products?|stock)|""" +
            """sells?\s+(?:the\s+)?(?:most|fast)|selling\s+fast)(?![\p{L}])|வேகமா\s*விக்""",
        RegexOption.IGNORE_CASE,
    )
    /** "fast-a pogudhu", "vegama theerudhu": selling fast — when said about stock / items. */
    private val fastVerbs = Regex("""(?<![\p{L}])((?:fast|vegama|seekiram)\s*-?\s*(?:a|ah|aa)?\s*(?:pog\p{L}*|theer\p{L}*|kaali\p{L}*))(?![\p{L}])""", RegexOption.IGNORE_CASE)
    /** Stock, items, products — or "which one" — the words that make a selling verb about the shelf. */
    private val stockThings = Regex(
        """(?<![\p{L}])(stock|stocks|item|items|product|products|maal|saamaan|saman|porul|porulgal|inventory|endha|yentha|entha|which|what)(?![\p{L}])|ஸ்டாக்|பொருள்|எந்த""",
        RegexOption.IGNORE_CASE,
    )

    /** A known party in the text, else the word before -ku / kitta ("Ramesh ku", "Kumar kitta"), or after "call / to / from". */
    fun personIn(text: String, known: List<String>): String? {
        KaiUnderstanding.knownPerson(text, known)?.let { return it }
        // Letters include Tamil vowel signs (\p{M}), so a Tamil-script name stays one word.
        val patterns = listOf(
            Regex("""(?<![\p{L}\p{M}])([\p{L}][\p{L}\p{M}.']{1,})\s*-?\s*(?:ku|kku|ukku|kitta|kita|kitte|kittae|kitaa|idam|oda)(?![\p{L}])""", RegexOption.IGNORE_CASE),
            // The name is only looked at (not used up), so "to call Ruthran" still finds Ruthran after "to call".
            Regex("""(?<![\p{L}])(?:call|phone|to|from)\s+(?=([\p{L}][\p{L}\p{M}.']{1,}))""", RegexOption.IGNORE_CASE),
            Regex("""([\p{L}][\p{L}\p{M}.']{1,})-?(?:க்கு|கிட்ட|கிட்டே)"""),
            // "Ravi call remind pannu", "Ruthran phone pannanum": the name just before call / phone.
            Regex("""(?<![\p{L}\p{M}])([\p{L}][\p{L}\p{M}.']{1,})\s+(?:call|phone)(?![\p{L}])""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            for (m in p.findAll(text)) {
                val word = m.groupValues[1].trim('.', '\'')
                val key = word.lowercase(Locale.ROOT)
                if (key in notNames || key.length < 2 || key.any(Char::isDigit)) continue
                // "Sujithukku" read as "Sujithuk" + "ku": the name is "Sujith" (+ "ukku").
                val name = if (p === patterns[0] && key.endsWith("uk") && key.length >= 6) word.dropLast(2) else word
                return name.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
        return null
    }

    /** The owner's own words for the task, without "remind pannu" and fillers. */
    fun cleanTask(text: String): String = (" $text ")
        .replace(Regex("""(?i)(?<![\p{L}])(remind\s*(pannu|pannunga|me|panni|pannidu)?|reminder\s*(vai|vechudu|set\s*pannu|podu)?|nyabagam\s*(paduthu|padutthu|paduthunga)?|""" +
            """gnabagam\s*(paduthu)?|ninaivu\s*(paduthu|paduthunga)?|ninaivupaduthu|please|pls|enakku|ennaku|kai|bro|set|pannu|pannunga|sollu|sollunga|nu|appo)(?![\p{L}])"""), " ")
        .replace(Regex("""நினைவூட்டு|ஞாபகப்படுத்து|சொல்லு"""), " ")
        .replace(Regex("""\s+"""), " ").trim().trim('-', ',', '.')
}
