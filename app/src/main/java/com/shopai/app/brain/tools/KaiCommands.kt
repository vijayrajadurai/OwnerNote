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
    /** "Indha month highest selling product enna?" */
    data object TopProducts : KaiCommand

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

        // ---- money given / received ----
        payment(text, t, ::has, questionWords, knownPeople)?.let { return it }

        // ---- stock, money, top products (from the books) ----
        val stockWords = has("stock", "inventory", "iruppu", "இருப்பு", "ஸ்டாக்")
        if (has("reorder", "re-order", "re order", "out of stock") || (stockWords && has("low", "kammi", "kammiya", "kuraivu", "kuraivaa", "theerndhu", "theernthu", "mudinjiruchu", "finish", "kuranj", "குறைவு"))) {
            return KaiCommand.LowStock
        }
        if (has("highest selling", "top selling", "best selling", "most sold", "adhigama vithadhu", "athigama vithadhu", "adhigama vikkudhu", "top product", "best product", "adhigama vithathu")) {
            return KaiCommand.TopProducts
        }
        if (stockWords) {
            val product = Regex("""^(.*?)\s*(?:stock|inventory|iruppu|இருப்பு|ஸ்டாக்)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                ?.replace(Regex("""(?i)\b(enakku|ennaku|my|the|how much|what is|whats|what's|enna|evlo|kadaila|shop la|la|oda|ku)\b"""), " ")
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
        "கொடுத்தேன்", "கொடுத்துட்டேன்", "அனுப்பினேன்", "கொடுத்தாச்சு")
    private val inWords = listOf("vanginen", "vaanginen", "vangunen", "vaangunen", "vangiten", "vaangiten", "vanginaen", "vangitten", "received", "got",
        "vandhuchu", "vanthuchu", "vandhudhu", "thandhan", "thandhaan", "thandhaar", "thanthan", "kuduthan", "kuduthaan", "kuduthaar", "koduthan", "koduthaan", "koduthaar",
        // "Kumar 2000 kuduthutaan": he gave (and it's done) — the owner received it.
        "kuduthutaan", "kuduthuttaan", "kuduthutan", "kuduthittaan", "koduthutaan", "koduthuttaan", "kuduthutaanga", "kuduthaanga", "kuduthanga",
        "kuduthutaar", "thandhutaan", "thanthutaan", "கொடுத்துட்டான்", "கொடுத்தாங்க",
        "வாங்கினேன்", "வந்துச்சு", "தந்தான்", "தந்தார்", "கொடுத்தான்")
    private val goodsWords = Regex("""(?i)\b(kg|kgs|kilo|litre|ltr|bag|bags|pcs|pieces|packet|box|dozen|rice|arisi|sugar|oil|maavu|paal)\b""")

    private fun payment(text: String, t: String, has: (Array<out String>) -> Boolean, question: Boolean, known: List<String>): KaiCommand.Payment? {
        fun hasAny(list: List<String>) = has(list.toTypedArray())
        val out = hasAny(outWords)
        val incoming = hasAny(inWords)
        if (out == incoming) return null
        // "Kuduthana?" (did he give?), "5000 pending", "tharanum" — questions or dues, not something that happened.
        if (question || has(arrayOf("pending", "baaki", "bakki", "tharanum", "kudukkanum", "kodukkanum", "varanum", "remind", " ah ", " aa "))) return null
        // "10 kg rice vanginen" is buying goods, not a payment.
        if (goodsWords.containsMatchIn(text)) return null
        val amounts = KaiUnderstanding.amountsIn(text, java.time.LocalDate.now()).filter { it > 0 }
        val amount = amounts.singleOrNull()?.let { BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP) }
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
        val outgoing = when {
            ownerIsPayer -> true
            ownerIsRecipient && has(arrayOf("gave", "given", "paid", "received", "got", "kuduth", "koduth", "தந்த", "கொடுத்த")) -> false
            else -> out
        }
        return KaiCommand.Payment(personIn(text, known), amount, outgoing = outgoing, mode = mode, modeSaid = said)
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
                return word.replaceFirstChar { it.titlecase(Locale.ROOT) }
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
