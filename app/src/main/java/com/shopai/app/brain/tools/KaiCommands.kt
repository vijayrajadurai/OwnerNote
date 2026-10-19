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
    /** "Stock-la evlo panam mudangi irukku?", "stock value": the money in stock at purchase prices. */
    data object StockValue : KaiCommand
    /** "Stock performance report", "stock report": sellers, slow and unsold stock, low stock and money in stock together. */
    data object StockReport : KaiCommand

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
        // Not "expiry 06/2027" / "batch 12/A" said with stock: a date and a batch code are never a sum.
        val batchSaid = KaiInventory.batchIn(text) != null || KaiInventory.expirySaid(text, java.time.LocalDate.now()) != null
        if (!batchSaid) KaiCalculator.solve(text)?.let { return KaiCommand.Calculate(it) }

        // ---- which stock moves, which doesn't (from the sales records) ----
        // "vikkala", "pogala", "fast-a pogudhu" alone are everyday words: they mean stock only next to a stock / item / which word.
        val aboutStock = stockThings.containsMatchIn(t)
        if (stockReport.containsMatchIn(t)) return KaiCommand.StockReport
        if (stockMoney.containsMatchIn(t)) return KaiCommand.StockValue
        if (slowMoving.containsMatchIn(t) || aboutStock && slowVerbs.containsMatchIn(t)) return KaiCommand.SlowStock
        if (fastMoving.containsMatchIn(t) || aboutStock && fastVerbs.containsMatchIn(t)) return KaiCommand.TopProducts

        // ---- money given / received ----
        payment(text, t, ::has, questionWords, knownPeople)?.let { return it }

        // ---- stock, money, top products (from the books) ----
        val stockWords = has("stock", "inventory", "iruppu", "இருப்பு", "ஸ்டாக்")
        val itemWords = stockWords || has("product", "products", "item", "items", "porul", "saamaan", "saman", "maal", "பொருள்")
        // "enna saaman theerapoguthu", "edhu theera pogudhu", "order panna vendiyadhu enna": running out (owner, 11 Oct 2026).
        if (has("reorder", "re-order", "re order", "out of stock", "order pannanum", "order panna vendiyadhu", "order podanum") || runningOut.containsMatchIn(t) ||
            (itemWords && has("low", "kammi", "kammiya", "kuraivu", "kuraivaa", "theerndhu", "theernthu", "theera", "mudinjiruchu", "finish", "kuranj", "குறைவு"))) {
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
                    """ippo|ippa|ipo|current|currently|now|total|motham|mothama|ella|ellaa|all|full|kadai|shop|yentha|endha|entha|which|ennoda|unga|namma|""" +
                    """namba|nammakitta|nambakitta|kitta|kita|ennenna|irukku|iruku|enkitta|ennkitta)\b"""), " ")
                // "இப்ப நம்ம கிட்ட என்ன என்ன ஸ்டாக்": the same words said in Tamil — not a product.
                ?.replace(Regex("""(?<![\p{L}\p{M}])(இப்ப|இப்போ|நம்ம|நம்ப|நம்மகிட்ட|கிட்ட|என்ன|எல்லா|மொத்த|இன்னைக்கு|என்கிட்ட)(?![\p{L}\p{M}])"""), " ")
                ?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.length >= 2 && it.any(Char::isLetter) }
            return KaiCommand.Stock(product)
        }
        val balanceWords = has("iruku", "irukku", "irukka", "balance", "kaila", "kaiyila", "kaiyil", "in hand", "do i have", "have", "available", "இருக்கு")
        val flowWords = has("vandh", "vanth", "pona", "ponadhu", "poch", "today", "inniku", "innaikku", "in", "out", "came", "spent", "sent")
        if (balanceWords && !flowWords) {
            when {
                // "Kumar account la evlo irukku" is Kumar's balance; "account la evlo irukku" alone is the bank's.
                has("bank", "பேங்க்") || has("account la") && personIn(raw, knownPeople) == null -> return KaiCommand.MoneyBalance(MoneyKind.BANK)
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

    /** The last word of a shop's name ("Vijayalakshmi Store", "Sri Balaji Agencies"), in English or as the mic writes it. */
    private val shopWords = setOf("store", "stores", "traders", "trader", "agencies", "agency", "mart", "enterprises", "enterprise", "distributors",
        "distributor", "agency", "supermarket", "hardwares", "hardware", "textiles", "medicals", "medical", "bakery", "sweets", "provisions", "centre",
        "center", "brothers", "bros", "and sons", "company", "co", "industries", "ஸ்டோர்", "ஸ்டோர்ஸ்", "டிரேடர்ஸ்", "ட்ரேடர்ஸ்", "ஏஜென்சீஸ்", "ஏஜென்சி", "மார்ட்",
        "என்டர்பிரைசஸ்", "டிஸ்ட்ரிபியூட்டர்ஸ்", "சூப்பர்மார்க்கெட்", "ஹார்டுவேர்ஸ்", "டெக்ஸ்டைல்ஸ்", "மெடிக்கல்ஸ்", "பேக்கரி", "ஸ்வீட்ஸ்", "பிரதர்ஸ்",
        // ("Kumar Hotel", "குமார் ஓட்டல்" — owner's phone, 15 Oct 2026)
        "hotel", "hotels", "mess", "bhavan", "restaurant", "cafe", "stall", "ஹோட்டல்", "ஓட்டல்", "மெஸ்", "பவன்", "ரெஸ்டாரண்ட்", "கஃபே", "ஸ்டால்",
        // ("Kumar Tea Shop", "குமார் டீ ஷாப்" — owner, 16 Oct 2026)
        "shop", "shoppe", "ஷாப்",
        // "Sivalingam kadai", "சிவலிங்கம் கடை": the Tamil word for shop (never a name by itself — "kadai-la evlo sale?")
        "kadai", "கடை", "மெடிக்கல்")

    /**
     * "குமார் ஓட்டல்-ku 2000 tharanum" with only Kumar in the books: the owner named Kumar's hotel — "குமார் ஓட்டல்", as said — not
     * Kumar (never merged into the person the name starts with). Null when no shop's last word follows [person] with "-ku" / "kitta",
     * or that whole name is in the books (then [KaiUnderstanding.knownPerson] already found it).
     */
    private val englishShopWord = mapOf("ஸ்டோர்" to "Store", "ஸ்டோர்ஸ்" to "Stores", "டிரேடர்ஸ்" to "Traders", "ட்ரேடர்ஸ்" to "Traders",
        "ஏஜென்சீஸ்" to "Agencies", "ஏஜென்சி" to "Agency", "மார்ட்" to "Mart", "என்டர்பிரைசஸ்" to "Enterprises", "டிஸ்ட்ரிபியூட்டர்ஸ்" to "Distributors",
        "சூப்பர்மார்க்கெட்" to "Supermarket", "ஹார்டுவேர்ஸ்" to "Hardwares", "டெக்ஸ்டைல்ஸ்" to "Textiles", "மெடிக்கல்ஸ்" to "Medicals",
        "பேக்கரி" to "Bakery", "ஸ்வீட்ஸ்" to "Sweets", "பிரதர்ஸ்" to "Brothers", "ஹோட்டல்" to "Hotel", "ஓட்டல்" to "Hotel", "மெஸ்" to "Mess",
        "பவன்" to "Bhavan", "ரெஸ்டாரண்ட்" to "Restaurant", "கஃபே" to "Cafe", "ஸ்டால்" to "Stall", "ஷாப்" to "Shop",
        "கடை" to "Kadai", "மெடிக்கல்" to "Medical")

    /** The hour word, which is a name too ("Mani") when no number comes before it. */
    private val hourNames = setOf("mani", "மணி")

    private val tamilShopWord: Map<String, String> by lazy { englishShopWord.entries.reversed().associate { (ta, en) -> en.lowercase(Locale.ROOT) to ta } }

    /** A shop name's last word ("store", "ஸ்டோர்", "Hotel"…) — part of a name, never a name or a filler by itself. */
    fun isShopWord(word: String) = shopKey(word).let { w -> w.lowercase(Locale.ROOT) in shopWords || w in shopWords }

    /** The word without "-ku" and, as the mic writes "ஷாப்புக்கு", without its doubled last consonant ("ஷாப்ப்" → "ஷாப்"). */
    private fun shopKey(word: String): String = word.substringBefore('-').replace(Regex("""(\p{L})ு$"""), "$1்").replace(Regex("""(.)்\1்$"""), "$1்")

    /** "ஸ்டோர்" → "Store" for a name kept in English letters; the word as said otherwise. */
    fun shopWordFor(person: String, word: String, tamilSaid: Boolean = false): String = shopKey(word).let { w ->
        // (and back: the mic's "ரவி டீ ஷாப்", "சிவலிங்கம் கடைக்கு" — said in Tamil letters, its shop word already read as "shop" /
        // "kadai" — stays one name in Tamil letters; a shop word the owner typed after a Tamil-letter name in the books —
        // "குமார் Hotel" — stays as typed)
        val tamil = com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(person)
        (if (!tamil) englishShopWord[w] ?: w else if (tamilSaid) tamilShopWord[w.lowercase(Locale.ROOT)] ?: w else w)
            .replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** Words said next to a name that are never who it is: time, money ways, fillers, the owner, roles ("ippo", "gpay", "baaki"). */
    val notQualifiers: Set<String> = setOf(
        "hmm", "hmmm", "umm", "ahh", "aah", "haan", "ohh", "ayyo", "ayyoo", "illa", "illai", "thappu", "wrong", "late", "lateaa", "latea", "correct", "early", "seekiram", "sikkiram", "usually", "eppavum", "eppavume",
        "inniku", "innikku", "innaiku", "innaikku", "indru", "naalaikku", "nalaikku", "naalaiku", "nalaiku", "nethu", "nethiku", "today", "tomorrow",
        "yesterday", "ippo", "ipo", "ippa", "ipa", "appo", "seri", "sari", "ok", "okay", "bro", "boss", "owner", "sir", "madam", "kanakku", "kanakkula",
        "account", "entry", "credit", "debit", "gpay", "phonepe", "paytm", "upi", "online", "bank", "card", "cheque", "mattum", "dhaan", "than",
        "thaan", "pudhu", "puthu", "new", "old", "pazhaya", "total", "full", "fulla", "motham", "amount", "rupa", "rupai", "rooba", "ruba", "roobai",
        "advance", "bill", "kadan", "baaki", "bakki", "paakki", "settle", "clear", "reminder", "phone", "message", "whatsapp", "kai", "hello", "please",
        "pls", "also", "innoru", "marupadi", "again", "ini", "inimel", "yen", "what", "who", "when", "where", "which", "endha", "entha", "andha",
        "indha", "intha", "rendu", "moonu", "customer", "supplier", "oda", "ode", "kooda", "kuda", "kooda", "kooda-ve", "kitta", "irukku", "iruku", "gave", "given", "give",
        "returned", "sent", "send", "transferred", "will", "should", "needs", "need", "must", "took", "bought", "borrowed", "lent", "received",
        "collected", "settled", "cleared", "owed", "for", "with", "by", "at", "on", "in", "of", "this", "that", "money", "loan", "eduthaan",
        "return", "refund", "ivlo", "ivvalavu", "evalo", "evvalo", "ewlo", "yevlo", "ethana", "evlavu", "full", "partial", "konjam", "mothama",
        "எனக்கு", "நான்", "கிட்ட", "இருந்து", "இன்னைக்கு", "நாளைக்கு", "ரூபாய்", "பணம்", "பாக்கி", "கணக்கு", "புது", "பழைய",
    )

    /** Tanglish verb endings ("kuduthaan", "vaanginen", "tharanum", "vandhuchu") — a verb, never a name's word (names end in
     *  "-anga", "-chi", "-ttu" too: Ganga, Kanchi, Pattu). */
    private val verbLike = Regex("""(aan|aaru|aanga|inen|inaan|anum|chu|tten|ten|ணும்|ான்|ார்|ாங்க|ேன்|ச்சு)$""")

    /**
     * A word that can be part of who the owner named — a person's or a shop's name, whatever the word ("Sivalingam", "Mandi",
     * "Agro", "மண்டி", and a shop's last word: "kadai", "Hotel") — and not grammar, money, time, the owner, a role, a verb or a
     * number (owner, 17 Oct 2026: "name-a sonnalum, shop per sonnalum, name plus shop per sonnalum" — never a list of names).
     */
    fun nameWord(raw: String): Boolean {
        val w = raw.substringBefore('-').trim('.', '\'', ',')
        if (w.isEmpty() || w.any(Char::isDigit) || w.count(Char::isLetter) < 2) return false
        if (isShopWord(w)) return true
        val k = w.lowercase(Locale.ROOT)
        if (k in notNames || k in notQualifiers || k in KaiFeed.notNames || com.shopai.app.brain.chat.KaiEntityResolver.isFunctionWord(k)) return false
        if (KaiPaymentDirection.of(w) != null || k in KaiFeed.receivedWords || k in KaiFeed.paidWords) return false
        if (KaiUnderstanding.isNumberWord(k) || verbLike.containsMatchIn(k) || timeSpan.matches(k)) return false
        return true
    }

    /** "2 nimishathula", "1 mani nerathula", "3 naal": a span of time, never a name's word. */
    private val timeSpan = Regex("""(?i)(nimisha(m|thula|ththula|tula|tthula)?|nimidam|nimidathula|neram|nerathula|nerathile|nerathla|naal(ula|la)?|naala|vaaram|vaarathula|""" +
        """maasam|maasathula|varusham|varushathula|seconds?|minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?)|(நிமிஷ|நிமிட|நேர|வார|மாச|வருஷ)[\p{L}\p{M}]*""")

    /** A word between a name and a shop's last word ("Kumar Tea Shop", "மணி டீ ஷாப்"): any word but grammar, money, time or a verb. */
    fun shopNameWord(raw: String): Boolean {
        val w = raw.substringBefore('-').trim('.', '\'', ',')
        if (w.isEmpty() || w.any(Char::isDigit) || w.none(Char::isLetter)) return false
        if (nameWord(w) || isShopWord(w)) return true
        val k = w.lowercase(Locale.ROOT)
        return k !in notQualifiers && !com.shopai.app.brain.chat.KaiEntityResolver.isFunctionWord(k) && KaiPaymentDirection.of(w) == null &&
            k !in KaiFeed.receivedWords && k !in KaiFeed.paidWords && !KaiUnderstanding.isNumberWord(k) && !verbLike.containsMatchIn(k) &&
            (k !in notNames || k in hourNames)
    }

    /**
     * The whole name said: [head] then [tail] (its last word a shop's last word when [lastShop]), in one script — the mic's
     * "Kumar டீ ஷாப்" with Kumar in the books ([headKnown]) is "Kumar Tea Shop"; "mani டீ shop" with no Mani kept is
     * "மணி டீ ஷாப்", as said; "Sivalingam மண்டி" with Sivalingam kept is "Sivalingam Mandi".
     */
    fun nameOf(head: String, tail: List<String>, lastShop: Boolean, headKnown: Boolean): String {
        if (tail.isEmpty()) return head.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val hasTamil = com.shopai.app.brain.tools.KaiSpokenWords::hasTamil
        // (a head the mic's words became — "mani", "sri", lower-case — goes back to the Tamil said; a name in English letters
        // — "Vijayalakshmi ஸ்டோர்" — keeps them: "Vijayalakshmi Store")
        if (!hasTamil(head) && !headKnown && head.first().isLowerCase() && tail.any(hasTamil)) com.shopai.app.brain.tools.KaiSpokenWords.tamilOf(head)?.let { th ->
            fun ta(w: String) = w.substringBefore('-').let { x -> if (hasTamil(x)) x else com.shopai.app.brain.tools.KaiSpokenWords.tamilOf(x) ?: x }
            val last = tail.last().substringBefore('-')
            return (listOf(th) + tail.dropLast(1).map(::ta) + (if (lastShop) shopWordFor(th, last, true) else ta(last))).joinToString(" ")
        }
        val last = tail.last().substringBefore('-')
        val words = tail.dropLast(1).map { middleWordFor(head, it) } + (if (lastShop) shopWordFor(head, last, hasTamil(head)) else middleWordFor(head, last))
        return (listOf(head) + words).joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
    }

    /** A Tamil-letter word in English letters, as owners type Tanglish ("மண்டி" → "Mandi", "கடை" → "Kadai") — for a name kept in English. */
    fun latinOf(tamil: String): String {
        val cons = mapOf('க' to "k", 'ங' to "ng", 'ச' to "s", 'ஞ' to "nj", 'ட' to "t", 'ண' to "n", 'த' to "th", 'ந' to "n", 'ப' to "p", 'ம' to "m",
            'ய' to "y", 'ர' to "r", 'ல' to "l", 'வ' to "v", 'ழ' to "zh", 'ள' to "l", 'ற' to "r", 'ன' to "n", 'ஜ' to "j", 'ஷ' to "sh", 'ஸ' to "s", 'ஹ' to "h")
        val signs = mapOf('ா' to "aa", 'ி' to "i", 'ீ' to "ee", 'ு' to "u", 'ூ' to "oo", 'ெ' to "e", 'ே' to "e", 'ை' to "ai", 'ொ' to "o", 'ோ' to "o", 'ௌ' to "au")
        val vowels = mapOf('அ' to "a", 'ஆ' to "aa", 'இ' to "i", 'ஈ' to "ee", 'உ' to "u", 'ஊ' to "oo", 'எ' to "e", 'ஏ' to "e", 'ஐ' to "ai", 'ஒ' to "o", 'ஓ' to "o", 'ஔ' to "au")
        // Voiced after its own nasal ("ண்ட" nd, "ந்த" ndh, "ம்ப" mb, "ங்க" ng, "ஞ்ச" nj) and "ட" between vowels ("கடை" kadai).
        val voiced = mapOf('க' to "g", 'ச' to "j", 'ட' to "d", 'த' to "dh", 'ப' to "b")
        val nasalOf = mapOf('க' to 'ங', 'ச' to 'ஞ', 'ட' to 'ண', 'த' to 'ந', 'ப' to 'ம')
        val out = StringBuilder()
        var i = 0
        while (i < tamil.length) {
            val c = tamil[i]
            val next = tamil.getOrNull(i + 1)
            when {
                c in vowels -> out.append(vowels.getValue(c))
                c in cons -> {
                    val afterNasal = i >= 2 && tamil[i - 1] == '்' && tamil[i - 2] == nasalOf[c]
                    val betweenVowels = c == 'ட' && i >= 1 && tamil[i - 1] != '்' && (tamil[i - 1] in signs || tamil[i - 1] in cons || tamil[i - 1] in vowels) && next != '்'
                    val doubled = i >= 2 && tamil[i - 1] == '்' && tamil[i - 2] == c
                    out.append(if (afterNasal || betweenVowels && !doubled) voiced.getValue(c) else if (c == 'ற' && doubled) "" else cons.getValue(c))
                    if (c == 'ற' && doubled) { out.setLength(out.length - 1); out.append("tr") }
                    when {
                        next == '்' -> i++
                        next != null && next in signs -> { out.append(signs.getValue(next)); i++ }
                        else -> out.append('a')
                    }
                }
                else -> if (c.isLetterOrDigit()) out.append(c)
            }
            i++
        }
        return out.toString().replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** A word between a name and its shop word, in the name's letters: the mic's "டீ" in "Kumar டீ ஷாப்" is "Tea". */
    fun middleWordFor(person: String, word: String): String {
        val w = word.substringBefore('-')
        if (com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(person) || !com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(w))
            return w.replaceFirstChar { it.titlecase(Locale.ROOT) }
        // A word Kai knows by its English name — a shop's goods or service ("டீ" = Tea, "ஜூஸ்" = Juice, "ஆணி" = Nail) — else
        // the mic's word as Kai reads it (a spelled letter — "டீ" = T — is no word), else the mic's own letters.
        val service = KaiServices.catalogue.firstOrNull { s -> w in s.words && ' ' !in s.name }?.name
        val goods = KaiFeed.goodsNames.firstOrNull { (names, _) -> w in names }?.second?.trim()?.takeIf { ' ' !in it }
        val heard = com.shopai.app.brain.tools.KaiSpokenWords.normalize(w).trim()
            .takeIf { it.length >= 2 && !com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(it) && ' ' !in it }
        return (service ?: goods ?: heard ?: latinOf(w)).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun shopNamedAfter(text: String, person: String, known: List<String>): String? {
        // "Kumar Hotel-ku", "Kumar Tea Shop ku", "குமார் டீ ஷாப்புக்கு": the shop's last word, one word between at most.
        val shop = Regex("""(?<![\p{L}\p{M}])([\p{L}][\p{L}\p{M}.']*)\s+(?:([\p{L}][\p{L}\p{M}]*)\s+)?([\p{L}][\p{L}\p{M}]*?)\s*-?\s*(?:ku|kku|ukku|kitta|kita|kitte|க்கு|கிட்ட)(?![\p{L}\p{M}])""", RegexOption.IGNORE_CASE)
        // (tried at every word, not only after the last match: "naan Kumar Hotel ku" — "naan Kumar Hotel" is no shop of Kumar's)
        val m = Regex("""(?<![\p{L}\p{M}])[\p{L}]""").findAll(text).mapNotNull { w -> shop.find(text, w.range.first)?.takeIf { it.range.first == w.range.first } }
            .firstOrNull { m ->
                isShopWord(m.groupValues[3]) && m.groupValues[2].lowercase(Locale.ROOT).let { mid -> mid.isEmpty() || mid !in notNames && !isShopWord(mid) } &&
                    (m.groupValues[1].equals(person, ignoreCase = true) || com.shopai.app.util.NameSound.same(m.groupValues[1], person))
            } ?: return null
        val mid = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { middleWordFor(person, it) }
        val name = listOfNotNull(person, mid, shopWordFor(person, m.groupValues[3], com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(m.groupValues[1] + m.groupValues[2]))).joinToString(" ")
        return name.takeIf { n -> known.none { it.equals(n, ignoreCase = true) } }
    }

    /** "theerapoguthu", "theera pogudhu", "mudiya pogudhu": stock running out ("thira" is "open" — never this). */
    private val runningOut = Regex("""(?i)(?<![\p{L}])(?:theera|theeru|mudiya)\s*-?\s*po\p{L}*|தீரப்போ\p{L}*|தீர\s+போ\p{L}*""")

    /** "Colgate nalla pogudhu" / "Colgate nikkudhu": true = selling well, false = not moving, null = no word about its movement. */
    fun movement(text: String): Boolean? {
        val t = text.lowercase(java.util.Locale.ROOT)
        return when {
            slowMoving.containsMatchIn(t) || slowVerbs.containsMatchIn(t) -> false
            fastMoving.containsMatchIn(t) || fastVerbs.containsMatchIn(t) -> true
            else -> null
        }
    }
    /** "Expiry aagura medicine list kudu", "endha batch expire aagudhu", "காலாவதி ஆகுற மருந்து": a read-only expiry list. */
    fun asksExpiry(text: String): Boolean {
        val t = text.lowercase(java.util.Locale.ROOT)
        if (!expiryWord.containsMatchIn(t)) return false
        // "expiry", "expiry?", "எக்ஸ்பைரி" alone: the expiry list (owner, 17 Oct 2026 — it was asked what "expiry" means).
        if (Regex("""^\s*(?:expiry|expiring|expired|காலாவதி|எக்ஸ்பைரி|எக்ஸ்பையரி)\s*[?.!]*\s*$""").matches(t)) return true
        // A product being added with its expiry ("Paracetamol 10 strip, batch B1, expiry 12/2026") is not the list.
        if (Regex("""\d""").containsMatchIn(t) && Regex("""(?<![\p{L}])(add|vandhuchu|vandhirukku|serthu|sethu|batch\s+no)(?![\p{L}])""").containsMatchIn(t)) return false
        return expiryAsk.containsMatchIn(t)
    }
    private val expiryWord = Regex("""(?<![\p{L}])(expiry|expire|expired|expiring|expires|exp\s*date|kaalavadhi|kalavadhi|kaalavathi|best\s*before|use\s*-?\s*by|shelf\s*life|fefo|first\s+expiry)|(endha|entha|yentha|which)\s+batch\s+(first|mudhal|modhal)|காலாவதி|எக்ஸ்பைரி|எக்ஸ்பையர்|எக்ஸ்பைர்""")
    private val expiryAsk = Regex("""(?<![\p{L}])(list|evlo|enna|edhu|ethu|yedhu|endha|entha|yentha|aagura|agura|aagudhu|aaguthu|agudhu|aagum|aaga|aagapora|agapora|aagappora|pogudhu|poguthu|pora|varudhu|varuthu|kudu|sollu|kaattu|kaatu|which|show|soon|month|maasam|week|vaaram|report|medicine|medicines|marundhu|batch|batches|items?|products?|stock|eppo|eppa|when|date|aach\w*|fefo|first|mudhal|innaikku|naalaikku)(?![\p{L}])|ஆகுற|ஆகுது|ஆகப்போற|லிஸ்ட்|மருந்து|எந்த|குடு|சொல்லு|எப்போ|ஆச்சு|ஸ்டாக்""")

    fun asksStockReport(text: String) = stockReport.containsMatchIn(text)
    fun asksStockValue(text: String) = stockMoney.containsMatchIn(text)

    /** "stock performance report", "stock report", "ஸ்டாக் அறிக்கை". */
    private val stockReport = Regex("""(?i)(?<![\p{L}])(?:stock|inventory|ஸ்டாக்)\s*(?:performance|report|analysis|analyse|arikkai)(?![\p{L}])|ஸ்டாக்\s*அறிக்கை""")
    /** "stock la evlo panam mudangi irukku", "stock value", "stock mathippu evlo": money held in stock — never a stock count. */
    private val stockMoney = Regex("""(?i)(?<![\p{L}])(?:stock|inventory|saamaan|saman|porul|maal)\p{L}{0,4}\s+(?:\p{L}+\s+){0,3}?(?:panam|paisa|kaasu|money|value|worth|mathippu|madhippu|mudang\p{L}*|blocked|locked)(?![\p{L}])""" +
        """|(?<![\p{L}])mudangi\s*(?:irukk?u|kidakku)|ஸ்டாக்\S*\s+(?:\S+\s+){0,3}?(?:பணம்|மதிப்பு|முடங்\S*)""")

    /** "move agala", "dead stock", "slow moving", "unsold": stock that is not selling. */
    private val slowMoving = Regex(
        """(?<![\p{L}])(move\s*-?\s*(?:agala|aagala|aagalai|agalai|aaga\s*maattengudhu|aagudhu\s*illa|aguthu\s*illa|aagave\s*illa|agave\s*illa)|""" +
            """not\s+moving|non\s*-?\s*moving|slow\s*-?\s*moving|dead\s*stock|unsold|not\s+selling|didn'?t\s+sell|not\s+sold|""" +
            // "vikkadha stock", "medhuva vikkura products" (owner, 13 Oct 2026)
            """vikkaa?(?:dha|tha)\p{L}*|(?:medhuva|methuva|mella|mellamaa?|slow\s*-?\s*aa?)\s*(?:vikk\p{L}*|pog\p{L}*|move\p{L}*))(?![\p{L}])|நகரல|விக்காத|மெதுவா\s*விக்""",
        RegexOption.IGNORE_CASE,
    )
    /** "vikkala", "pogala", "thengi irukku", "slow-aa": not selling — when said about stock / items. */
    private val slowVerbs = Regex(
        """(?<![\p{L}])(vikkala|vikkalai|vikkalaye|vikkave\s*illa|vikkavey\s*illa|vitkala|vikka\s*maattengudhu|pogala|pogave\s*illa|thengi|thaengi|""" +
            // "nalla pola", "pogadha item", "nikkuthu", "nikkura stock" (owner, 13 Oct 2026)
            """pola|pogaa?(?:dha|tha)|nikk(?:udhu|uthu|uthe|ura|ira|idhu|ithu|um)|ninnuduchu|ninnudichu|ninnu\s*pochu|""" +
            """thangi\s*irukku|slow\s*-?\s*(?:aa|ah|a|move\p{L}*|pogu\p{L}*|vikk\p{L}*))(?![\p{L}])|விக்கல|தேங்கி|நிக்கு|நிக்கிற|போகல|போகாத""",
        RegexOption.IGNORE_CASE,
    )
    /** "fast move aguthu", "fast-a vikkudhu", "fast moving": what sells most. */
    private val fastMoving = Regex(
        """(?<![\p{L}])(fast\s*-?\s*(?:a|ah|aa)?\s*(?:move\p{L}*|moving|vikk\p{L}*|vith\p{L}*|sell\p{L}*)|vegama\s*(?:vikk\p{L}*|vith\p{L}*|move\p{L}*)|""" +
            """seekiram\s*(?:vikk\p{L}*|vith\p{L}*|theer\p{L}*)|sikkiram\s*(?:vikk\p{L}*|vith\p{L}*)|moving\s+(?:items?|products?|stock)|""" +
            """sells?\s+(?:the\s+)?(?:most|fast)|selling\s+fast|(?:adhigama|athigama|adhigam|niraya|niraiya)\s*(?:vikk\p{L}*|vith\p{L}*))(?![\p{L}])|வேகமா\s*விக்|அதிகமா\s*விக்""",
        RegexOption.IGNORE_CASE,
    )
    /** "fast-a pogudhu", "vegama theerudhu": selling fast — when said about stock / items. */
    private val fastVerbs = Regex("""(?<![\p{L}])((?:fast|vegama|seekiram)\s*-?\s*(?:a|ah|aa)?\s*(?:pog\p{L}*|theer\p{L}*|kaali\p{L}*)|""" +
        // "nalla move aguthu", "nalla pothu", "nalla pora item", "nalla vikkudhu" — said positively (the "-ala" forms are read as slow first).
        """nalla\s*-?\s*(?:a|ah|aa)?\s*(?:move\s*(?:aa|a)?g(?:udhu|uthu|ura|ra|um)|moving|pog(?:udhu|uthu|ura|ra|um)|pothu|podhu|pora|povudhu|vikk(?:udhu|uthu|ura|ra|um)|vith\p{L}*|sell\p{L}*))(?![\p{L}])""" +
        """|நல்லா\s*(?:போகு|போற|விக்கு|மூவ்)""", RegexOption.IGNORE_CASE)
    /** Stock, items, products — or "which one" — the words that make a selling verb about the shelf. */
    private val stockThings = Regex(
        """(?<![\p{L}])(stock|stocks|item|items|product|products|maal|saamaan|saman|porul|porulgal|inventory|endha|yentha|entha|edhu|ethu|yedhu|which|what)(?![\p{L}])|ஸ்டாக்|பொருள்|எந்த|எது""",
        RegexOption.IGNORE_CASE,
    )

    /** A known party in the text, else the word before -ku / kitta ("Ramesh ku", "Kumar kitta"), or after "call / to / from". */
    fun personIn(text: String, known: List<String>): String? {
        KaiUnderstanding.knownPerson(text, known)?.let { return shopNamedAfter(text, it, known) ?: it }
        // Letters include Tamil vowel signs (\p{M}), so a Tamil-script name stays one word.
        val patterns = listOf(
            Regex("""(?<![\p{L}\p{M}])([\p{L}][\p{L}\p{M}.']{1,})\s*-?\s*(?:ku|kku|ukku|kitta|kita|kitte|kittae|kitaa|idam|oda)(?![\p{L}])""", RegexOption.IGNORE_CASE),
            // The name is only looked at (not used up), so "to call Ruthran" still finds Ruthran after "to call".
            Regex("""(?<![\p{L}])(?:call|phone|to|from)\s+(?=([\p{L}][\p{L}\p{M}.']{1,}))""", RegexOption.IGNORE_CASE),
            // (only at a word's end — "ரவிக்குமார்" is Ravikumar, never "ரவி" + "-க்கு")
            Regex("""(?<![\p{L}\p{M}])([\p{L}][\p{L}\p{M}.']{1,}?)-?(?:க்கு|கிட்ட|கிட்டே)(?![\p{L}\p{M}])"""),
            // "Ravi call remind pannu", "Ruthran phone pannanum": the name just before call / phone.
            Regex("""(?<![\p{L}\p{M}])([\p{L}][\p{L}\p{M}.']{1,})\s+(?:call|phone)(?![\p{L}])""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            for (m in p.findAll(text)) {
                val word = m.groupValues[1].trim('.', '\'')
                val key = word.lowercase(Locale.ROOT)
                // (a shop's last word is read before the words that are never a name alone — "hotel", "mess" are those too)
                val shop = p !== patterns[1] && isShopWord(word)
                if (!shop && key in notNames || key.length < 2 || key.any(Char::isDigit)) continue
                // "Sujithukku" read as "Sujithuk" + "ku": the name is "Sujith" (+ "ukku").
                val name = if (p === patterns[0] && key.endsWith("uk") && key.length >= 6) word.dropLast(2) else word
                // "Sivalingam Mandi-ku", "Sri Murugan Agro Centre-ku", "Vijayalakshmi Store kitta", "சிவலிங்கம் மண்டிக்கு": the words
                // right before it that can be a name's are the name too — whatever they are, no list of names or shops (owner,
                // 17 Oct 2026). A pattern after "call / to / from" names one word.
                val all = if (p === patterns[1]) emptyList() else text.substring(0, m.range.first).trimEnd().split(Regex("""\s+""")).filter { it.isNotEmpty() }
                // ("Mani Tea Shop": "mani" is the hour only after a number — "10 mani" — and a name before a shop's words)
                // (before a shop's last word, any word but grammar: "Kumar Tea Shop", "மணி டீ ஷாப்")
                fun nameHere(i: Int) = all[i].let { w -> if (shop) shopNameWord(w) && (w.lowercase(Locale.ROOT) !in hourNames || all.getOrNull(i - 1)?.any(Char::isDigit) != true)
                    else nameWord(w) }
                val before = all.indices.reversed().takeWhile(::nameHere).reversed().map { all[it] }.takeLast(3)
                    // (the name starts with a word that can start one — never "Tea Shop" alone)
                    .dropWhile { !nameWord(it) && !(shop && it.lowercase(Locale.ROOT) in hourNames) }
                // "kadai-ku 500", "Stores-ku 500" with no name before it names no one.
                if (shop && before.isEmpty()) continue
                if (before.isNotEmpty()) {
                    // (the mic's "ஸ்டோர்" after a name in English letters is that name's way: "Vijayalakshmi Store", as typed;
                    // "Kumar டீ ஷாப்புக்கு" → "Kumar Tea Shop"; "Sivalingam மண்டிக்கு" → "Sivalingam Mandi")
                    val head = before.first()
                    return nameOf(head, before.drop(1) + (if (shop) shopKey(name) else name), shop, known.any { it.equals(head, ignoreCase = true) })
                }
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
