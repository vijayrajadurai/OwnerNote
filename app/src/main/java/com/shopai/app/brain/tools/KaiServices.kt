package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.util.Locale

/**
 * The services a shop sells by the piece, for the Daily Sales Note (owner, 14 Oct 2026): a salon's hair cutting, shaving,
 * hair dye, head massage; a beauty parlour's threading, facial, waxing; a tea shop's tea, coffee, vadai; a hotel's idli,
 * dosa, meals; a power tool rental's drill / generator by the day. "oru cutting", "cutting plus shaving", "2 tea", "drill
 * machine 2 naal vaadagai" — typed, or as the ta-IN mic writes it ("கட்டிங் பிளஸ் ஷேவிங்").
 *
 * Kai knows the words, never the price: the owner says it once ("Hair cutting evlo?" → "150"), Confirm keeps it in the
 * owner's own memory, and the next sale is drafted with it. A word Kai can't read is never turned into a service.
 */
object KaiServices {

    enum class Kind { SALON, PARLOUR, TEA, HOTEL, RENTAL, OWN }

    /**
     * One service: its name as the Daily Sales Note shows it, how the owner says it (typed and as the mic writes it),
     * and [clear] — a word only a service shop uses ("shaving", "facial", "idli"), or one a grocery sells too ("tea", "milk",
     * a hardware shop's "drill"), which is a sale only where the shop sells services.
     */
    data class Service(val name: String, val kind: Kind, val words: List<String>, val clear: Boolean = true)

    val catalogue: List<Service> = listOf(
        // ---- salon
        Service("Hair cutting", Kind.SALON, listOf("hair cutting", "haircut", "hair cut", "cutting", "mudi vettu", "mudi vettal", "முடி வெட்டு", "கட்டிங்", "ஹேர் கட்டிங்", "ஹேர்கட்", "ஹேர் கட்")),
        Service("Kids cutting", Kind.SALON, listOf("kids cutting", "kids haircut", "baby cutting", "kuzhandhai cutting", "pasanga cutting", "கிட்ஸ் கட்டிங்", "பேபி கட்டிங்", "குழந்தை கட்டிங்")),
        Service("Shaving", Kind.SALON, listOf("shaving", "shave", "savaram", "savarum", "ஷேவிங்", "சேவிங்", "ஷேவ்", "சவரம்")),
        Service("Beard trim", Kind.SALON, listOf("beard trim", "beard trimming", "beard", "thaadi trim", "thadi trim", "thaadi", "trimming", "பியர்ட் ட்ரிம்", "பியர்ட்", "தாடி ட்ரிம்", "தாடி", "ட்ரிம்மிங்")),
        Service("Hair dye", Kind.SALON, listOf("hair dye", "hair colour", "hair color", "hair coloring", "dye", "ஹேர் டை", "ஹேர் கலர்", "டை")),
        Service("Head massage", Kind.SALON, listOf("head massage", "thalai massage", "massage", "ஹெட் மசாஜ்", "தலை மசாஜ்", "மசாஜ்")),
        Service("Hair wash", Kind.SALON, listOf("hair wash", "head wash", "ஹேர் வாஷ்", "ஹெட் வாஷ்")),
        Service("Hair spa", Kind.SALON, listOf("hair spa", "ஹேர் ஸ்பா")),
        Service("Hair straightening", Kind.SALON, listOf("hair straightening", "straightening", "ஹேர் ஸ்ட்ரெய்ட்னிங்", "ஸ்ட்ரெய்ட்னிங்")),
        Service("Facial", Kind.SALON, listOf("facial", "face massage", "ஃபேஷியல்", "பேஷியல்")),
        // ---- beauty parlour
        Service("Threading", Kind.PARLOUR, listOf("eyebrow threading", "threading", "eyebrow", "puruvam", "த்ரெட்டிங்", "திரெட்டிங்", "ஐப்ரோ", "புருவம்")),
        Service("Waxing", Kind.PARLOUR, listOf("waxing", "wax", "வாக்சிங்", "வேக்சிங்", "வாக்ஸ்")),
        Service("Bleach", Kind.PARLOUR, listOf("bleach", "bleaching", "ப்ளீச்", "பிளீச்")),
        Service("Manicure", Kind.PARLOUR, listOf("manicure", "மெனிக்யூர்", "மேனிக்யூர்")),
        Service("Pedicure", Kind.PARLOUR, listOf("pedicure", "பெடிக்யூர்")),
        Service("Bridal makeup", Kind.PARLOUR, listOf("bridal makeup", "bridal make up", "bridal", "kalyana makeup", "பிரைடல் மேக்கப்", "பிரைடல்")),
        Service("Makeup", Kind.PARLOUR, listOf("makeup", "make up", "மேக்கப்")),
        Service("Mehndi", Kind.PARLOUR, listOf("mehndi", "mehandi", "maruthani", "மெஹந்தி", "மருதாணி")),
        // ---- tea shop (tea, coffee, milk, bun are a grocery's goods too)
        Service("Lemon tea", Kind.TEA, listOf("lemon tea", "லெமன் டீ"), clear = false),
        Service("Ginger tea", Kind.TEA, listOf("ginger tea", "inji tea", "இஞ்சி டீ"), clear = false),
        Service("Tea", Kind.TEA, listOf("tea", "chai", "டீ"), clear = false),
        Service("Coffee", Kind.TEA, listOf("coffee", "kaapi", "kapi", "காபி", "காப்பி"), clear = false),
        Service("Milk", Kind.TEA, listOf("milk", "paal", "பால்"), clear = false),
        Service("Boost", Kind.TEA, listOf("boost", "பூஸ்ட்"), clear = false),
        Service("Horlicks", Kind.TEA, listOf("horlicks", "ஹார்லிக்ஸ்"), clear = false),
        Service("Bun", Kind.TEA, listOf("bun", "பன்"), clear = false),
        Service("Vadai", Kind.TEA, listOf("vadai", "vada", "vadai", "வடை")),
        Service("Bajji", Kind.TEA, listOf("bajji", "baji", "பஜ்ஜி")),
        Service("Samosa", Kind.TEA, listOf("samosa", "சமோசா")),
        // ---- hotel
        Service("Masala dosa", Kind.HOTEL, listOf("masala dosa", "masala dosai", "மசாலா தோசை")),
        Service("Dosa", Kind.HOTEL, listOf("dosa", "dosai", "thosai", "தோசை")),
        Service("Idli", Kind.HOTEL, listOf("idli", "idly", "இட்லி")),
        Service("Pongal", Kind.HOTEL, listOf("pongal", "பொங்கல்")),
        Service("Poori", Kind.HOTEL, listOf("poori", "puri", "பூரி")),
        Service("Kothu parotta", Kind.HOTEL, listOf("kothu parotta", "kothu porotta", "கொத்து பரோட்டா")),
        Service("Parotta", Kind.HOTEL, listOf("parotta", "porotta", "barotta", "பரோட்டா", "புரோட்டா")),
        Service("Chapathi", Kind.HOTEL, listOf("chapathi", "chapati", "chappathi", "சப்பாத்தி")),
        Service("Meals", Kind.HOTEL, listOf("meals", "saapadu", "sappadu", "lunch meals", "மீல்ஸ்", "சாப்பாடு")),
        Service("Chicken biryani", Kind.HOTEL, listOf("chicken biryani", "சிக்கன் பிரியாணி")),
        Service("Mutton biryani", Kind.HOTEL, listOf("mutton biryani", "மட்டன் பிரியாணி")),
        Service("Biryani", Kind.HOTEL, listOf("biryani", "briyani", "பிரியாணி")),
        Service("Fried rice", Kind.HOTEL, listOf("fried rice", "ஃப்ரைட் ரைஸ்", "ப்ரைட் ரைஸ்")),
        Service("Noodles", Kind.HOTEL, listOf("noodles", "நூடுல்ஸ்"), clear = false),
        Service("Omelette", Kind.HOTEL, listOf("omelette", "omelet", "omlet", "ஆம்லெட்")),
        Service("Curd rice", Kind.HOTEL, listOf("curd rice", "thayir sadam", "தயிர் சாதம்")),
        // ---- power tool rental (a hardware shop sells drills too: a rental only with "vaadagai" / days said, or in a service shop)
        Service("Drill machine rent", Kind.RENTAL, listOf("drill machine", "drilling machine", "drill", "டிரில் மெஷின்", "ட்ரில் மெஷின்", "டிரில்", "ட்ரில்"), clear = false),
        Service("Cutting machine rent", Kind.RENTAL, listOf("cutting machine", "cutter machine", "கட்டிங் மெஷின்", "கட்டர் மெஷின்"), clear = false),
        Service("Grinder machine rent", Kind.RENTAL, listOf("grinder machine", "angle grinder", "grinder", "கிரைண்டர் மெஷின்", "கிரைண்டர்"), clear = false),
        Service("Breaker rent", Kind.RENTAL, listOf("breaker machine", "breaker", "hammer machine", "jack hammer", "பிரேக்கர் மெஷின்", "பிரேக்கர்"), clear = false),
        Service("Generator rent", Kind.RENTAL, listOf("generator", "genset", "ஜெனரேட்டர்"), clear = false),
        Service("Welding machine rent", Kind.RENTAL, listOf("welding machine", "welding", "வெல்டிங் மெஷின்", "வெல்டிங்"), clear = false),
        Service("Concrete mixer rent", Kind.RENTAL, listOf("concrete mixer", "mixer machine", "கான்கிரீட் மிக்சர்", "மிக்சர் மெஷின்"), clear = false),
        Service("Pressure washer rent", Kind.RENTAL, listOf("pressure washer", "பிரஷர் வாஷர்"), clear = false),
        Service("Ladder rent", Kind.RENTAL, listOf("ladder", "eni", "ஏணி"), clear = false),
        Service("Scaffolding rent", Kind.RENTAL, listOf("scaffolding", "saaram", "ஸ்கேஃபோல்டிங்", "சாரம்"), clear = false),
    )

    /** "oru", "rendu" … "pathu" — and as the mic writes them. */
    private val numberWords = mapOf(
        "oru" to 1, "onnu" to 1, "one" to 1, "orey" to 1, "rendu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4,
        "anju" to 5, "five" to 5, "aaru" to 6, "six" to 6, "ezhu" to 7, "seven" to 7, "ettu" to 8, "eight" to 8, "ombadhu" to 9, "nine" to 9,
        "pathu" to 10, "ten" to 10,
        "ஒரு" to 1, "ஒண்ணு" to 1, "ஒன்று" to 1, "ரெண்டு" to 2, "இரண்டு" to 2, "மூணு" to 3, "மூன்று" to 3, "நாலு" to 4, "நான்கு" to 4, "அஞ்சு" to 5,
        "ஐந்து" to 5, "ஆறு" to 6, "ஏழு" to 7, "எட்டு" to 8, "ஒன்பது" to 9, "பத்து" to 10,
    )

    /** A rental's time said with it: "2 naal", "3 days", "4 manineram" — the count of days / hours rented. */
    private val duration = Regex("""^\s*(\d+|${numberWords.keys.joinToString("|")})\s*(naal|naalu|naala|days?|day|manineram|mani\s*neram|hours?|hrs?|நாள்|நாளு|மணிநேரம்|மணி\s*நேரம்)(?![\p{L}\p{M}])""")
    private val rentWord = Regex("""(?i)(?<![\p{L}\p{M}])(rent|rental|vaadagai|vadagai|vaadagaiku|vaadagaikku|vadagaiku|bhaadagai|வாடகை\p{M}*\p{L}*)""")
    private val upiWord = Regex("""(?i)(?<![\p{L}\p{M}])(gpay|g\s*pay|google\s*pay|phonepe|phone\s*pe|paytm|upi|online|qr|scan)(?![\p{L}\p{M}])|ஜிபே|ஜி பே|போன்பே|யுபிஐ|பேடிஎம்""")
    private val cashWord = Regex("""(?i)(?<![\p{L}\p{M}])(cash|kaasu|rokkam|ready\s*cash)(?![\p{L}\p{M}])|கேஷ்|ரொக்கம்|காசு""")
    private val stockWord = Regex("""(?i)(?<![\p{L}\p{M}])(vandhuchu|vanthuchu|vandhiruku|vandhirukku|stock|irakkinen|irakkunen|vaanginen|vanginen|purchase|kammi|minus|damage|udanjiduchu|kettupochu)(?![\p{L}\p{M}])|வந்துச்சு|ஸ்டாக்|வாங்கினேன்""")
    /** Words that say a sale was made — enough to read a grocery's "tea" as a tea shop's sale. */
    private val saleWord = Regex("""(?i)(?<![\p{L}\p{M}])(sale|sales|sold|vithuchu|vithen|vithom|vithutten|vitthen|serve|served|order|parcel)(?![\p{L}\p{M}])|வித்துச்சு|வித்தேன்|பார்சல்""")

    /** One service said: how many (days / hours for a rental) and, when said with it, the amount for that line. */
    data class Item(val name: String, val qty: Int, val amount: BigDecimal?, val per: String? = null)

    /** A sale line: the services said, and the way it was paid when said (true = UPI / GPay, false = cash, null = not said). */
    data class SaleLine(val items: List<Item>, val upi: Boolean?)

    private data class Hit(val start: Int, val end: Int, val name: String, val service: Service?)

    private fun aliases(own: Collection<String>): List<Pair<String, Service?>> =
        (catalogue.flatMap { s -> s.words.map { it.lowercase(Locale.ROOT) to s } } +
            own.map { it.lowercase(Locale.ROOT) to null as Service? })
            .distinctBy { it.first }.sortedByDescending { it.first.length }

    /** Every service word in [text], longest first, never overlapping. [own] = services the owner added, by name. */
    private fun hits(text: String, own: Collection<String>): List<Hit> {
        val lower = text.lowercase(Locale.ROOT)
        val taken = BooleanArray(lower.length)
        val out = mutableListOf<Hit>()
        for ((alias, s) in aliases(own)) {
            Regex("""(?<![\p{L}\p{M}])${Regex.escape(alias)}(?![\p{L}\p{M}])""").findAll(lower).forEach { m ->
                if ((m.range.first..m.range.last).none { taken[it] }) {
                    (m.range.first..m.range.last).forEach { taken[it] = true }
                    out += Hit(m.range.first, m.range.last + 1, s?.name ?: own.first { it.equals(alias, ignoreCase = true) }, s)
                }
            }
        }
        return out.sortedBy { it.start }
    }

    private fun number(token: String): Int? = token.toIntOrNull()?.takeIf { it in 1..999 } ?: numberWords[token.lowercase(Locale.ROOT)]

    /**
     * The services said in [text] as a sale — null when none is (or the line is plainly about stock / something else).
     * [own]: the owner's own services (name → price kept). [products]: the shop's product names — a word that is the shop's
     * own product is that product, not a service, unless the owner keeps a price for it as a service.
     * [serviceShop]: the shop sells services (a word a grocery sells too, "tea", is then a sale).
     */
    fun saleIn(text: String, own: Map<String, BigDecimal>, products: List<String>, serviceShop: Boolean): SaleLine? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[,!?.]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        val found = hits(lower, own.keys)
        if (found.isEmpty()) return null
        val rent = rentWord.containsMatchIn(lower)
        val productWords = products.map { " " + it.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ") + " " }
        val known = own.keys.map { it.lowercase(Locale.ROOT) }.toSet()
        val anyKept = own.isNotEmpty()
        val items = mutableListOf<Item>()
        // (where the line was read up to: an amount said after one service is never the count of the next — "cutting 150 shaving")
        var cursor = 0
        for ((i, h) in found.withIndex()) {
            val name = h.name
            val kept = name.lowercase(Locale.ROOT) in known
            val said = lower.substring(h.start, h.end)
            val isProduct = productWords.any { it.contains(" $said ") }
            val afterEnd = found.getOrNull(i + 1)?.start ?: lower.length
            val after = lower.substring(h.end, afterEnd)
            val dur = if (h.service?.kind == Kind.RENTAL) duration.find(after) else null
            val ok = when {
                kept -> true
                isProduct -> false
                h.service == null -> true
                h.service.kind == Kind.RENTAL -> rent || dur != null || serviceShop || anyKept
                h.service.clear -> true
                else -> serviceShop || anyKept || saleWord.containsMatchIn(lower)
            }
            // How many: the number right before it ("oru cutting", "2 tea"), or a rental's days / hours after it.
            val before = lower.substring(minOf(maxOf(cursor, found.getOrNull(i - 1)?.end ?: 0), h.start), h.start).trim().split(' ').lastOrNull().orEmpty()
            cursor = h.end
            // Its amount, when said right after it ("cutting 150", "drill 2 naal 600") — not the count of the next one ("2 tea 3 coffee").
            val restAt = h.end + (dur?.range?.last?.plus(1) ?: 0)
            val rest = lower.substring(restAt, afterEnd)
            val m = Regex("""^\s*(?:ku|kku|ukku|-?ku)?\s*(?:₹|rs\.?\s*)?(\d[\d,]*(?:\.\d+)?)\s*(rs|rupees|ruba|rubai|roobai|ரூபாய்|ரூ)?(?![\p{L}\p{M}\d])""").find(rest)
            val value = m?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            val countOfNext = m != null && found.getOrNull(i + 1) != null && rest.substring(m.range.last + 1).isBlank() && m.groupValues[2].isEmpty() &&
                value != null && value <= BigDecimal.TEN
            // "head massage 1" ("ஹெட் மசாஜ் ஒண்ணு"), "tea 3": a small number after it, no rupee word, no count before — how many, not ₹1.
            val keptPrice = own.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            val countAfter = !countOfNext && value != null && m!!.groupValues[2].isEmpty() && number(before) == null && value <= BigDecimal.TEN &&
                value.stripTrailingZeros().scale() <= 0 && (if (keptPrice != null) keptPrice.compareTo(value) != 0 else value < BigDecimal(5))
            val amount = if (countOfNext || countAfter) null else value
            if (amount != null) cursor = restAt + m!!.range.last + 1
            if (!ok) continue
            val qty = dur?.let { number(it.groupValues[1]) } ?: number(before) ?: (if (countAfter) value!!.toInt() else null) ?: 1
            val per = dur?.groupValues?.get(2)?.let { if (Regex("""(?i)mani|hour|hr|மணி""").containsMatchIn(it)) "hour" else "day" }
            items += Item(name, qty, amount, per)
        }
        if (items.isEmpty()) return null
        // "cutting machine 2 vandhuchu": stock coming in — not a sale (a rental said with "vaadagai" still is).
        if (stockWord.containsMatchIn(lower) && !rent && !saleWord.containsMatchIn(lower)) return null
        val upi = if (upiWord.containsMatchIn(lower)) true else if (cashWord.containsMatchIn(lower)) false else null
        return SaleLine(items, upi)
    }

    /** The service named in [text] (one only), for "cutting evlo?" / "cutting rate 200" — null when none or several are. */
    fun serviceNamed(text: String, own: Collection<String>): String? =
        hits(" " + text.lowercase(Locale.ROOT).replace(Regex("""[,!?.]"""), " ") + " ", own).map { it.name }.distinct().singleOrNull()

    /** A service's name as the owner says it when Kai has no word for it ("hair botox"), cut from "pudhu service hair botox 1500". */
    fun newServiceName(text: String): String? {
        val m = Regex("""(?i)(?:pudhu|puthu|new|புது)\s+(?:service|servis|சர்வீஸ்)\s+([\p{L}\p{M}][\p{L}\p{M}\s]{1,40}?)\s*(?:-|:)?\s*(?:₹|rs\.?\s*)?\d""").find(text)
            ?: Regex("""(?i)([\p{L}\p{M}][\p{L}\p{M}\s]{1,40}?)\s+(?:service|servis|சர்வீஸ்)\s*(?:-|:)?\s*(?:₹|rs\.?\s*)?\d""").find(text)
            ?: return null
        val name = m.groupValues[1].trim().split(Regex("""\s+""")).filter { it.lowercase(Locale.ROOT) !in setOf("oru", "add", "pannu", "pudhu", "puthu", "new", "service") }
            .joinToString(" ").lowercase(Locale.ROOT)
        return name.takeIf { it.length >= 2 }?.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    // ------------------------------------------------------------ the Daily Sales Note line

    private fun plain(a: BigDecimal) = a.stripTrailingZeros().toPlainString()

    /** How a sale is written in the Daily Cash Note's note: "Hair cutting ×1 ₹150 · Shaving ×1 ₹80" — what the screen and PDF show. */
    fun note(items: List<Pair<Item, BigDecimal>>): String = items.joinToString(" · ") { (i, amt) ->
        "${i.name} ×${i.qty}${i.per?.let { if (it == "hour") " hr" else " day" }.orEmpty()} ₹${plain(amt)}"
    }

    /** The services read back from a Daily Cash Note entry's note (empty for an entry written by hand). */
    fun fromNote(note: String?): List<Triple<String, Int, BigDecimal>> =
        Regex("""([^·]+?)\s×(\d+)(?:\s(?:day|hr))?\s₹(\d+(?:\.\d+)?)""").findAll(note.orEmpty()).mapNotNull { m ->
            val amt = m.groupValues[3].toBigDecimalOrNull() ?: return@mapNotNull null
            Triple(m.groupValues[1].trim().removePrefix("Kai:").trim(), m.groupValues[2].toInt(), amt)
        }.toList()
}
