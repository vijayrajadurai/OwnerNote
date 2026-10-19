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
        // (never "டை" alone: the mic writes "tie" the same — School tie, Tie rod end)
        Service("Hair dye", Kind.SALON, listOf("hair dye", "hair colour", "hair color", "hair coloring", "dye", "ஹேர் டை", "ஹேர் கலர்")),
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
        Service("Parotta", Kind.HOTEL, listOf("parotta", "porotta", "barotta", "paratha", "parota", "பரோட்டா", "புரோட்டா")),
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
        "ஒரு" to 1, "ஒண்ணு" to 1, "ஒன்னு" to 1, "ஒன்று" to 1, "ரெண்டு" to 2, "இரண்டு" to 2, "மூணு" to 3, "மூன்று" to 3, "நாலு" to 4, "நான்கு" to 4, "அஞ்சு" to 5,
        "ஐந்து" to 5, "ஆறு" to 6, "ஏழு" to 7, "எட்டு" to 8, "ஒன்பது" to 9, "பத்து" to 10,
    )

    /** A rental's time said with it: "2 naal", "3 days", "4 manineram" — the count of days / hours rented. */
    private val duration = Regex("""^\s*(\d+|${numberWords.keys.joinToString("|")})\s*(naal|naalu|naala|days?|day|manineram|mani\s*neram|hours?|hrs?|நாள்|நாளு|மணிநேரம்|மணி\s*நேரம்)(?![\p{L}\p{M}])""")
    private val rentWord = Regex("""(?i)(?<![\p{L}\p{M}])(rent|rental|vaadagai|vadagai|vaadagaiku|vaadagaikku|vadagaiku|bhaadagai|வாடகை\p{M}*\p{L}*)""")
    private val upiWord = Regex("""(?i)(?<![\p{L}\p{M}])(gpay|g\s*pay|google\s*pay|phonepe|phone\s*pe|paytm|upi|online|qr|scan)(?![\p{L}\p{M}])|ஜிபே|ஜி பே|போன்பே|யுபிஐ|பேடிஎம்""")
    private val cashWord = Regex("""(?i)(?<![\p{L}\p{M}])(cash|kaasu|rokkam|ready\s*cash)(?![\p{L}\p{M}])|கேஷ்|ரொக்கம்|காசு""")
    private val stockWord = Regex("""(?i)(?<![\p{L}\p{M}])(vandhuchu|vanthuchu|vandhadhu|vanthathu|vandhiruku|vandhirukku|stock|irakkinen|irakkunen|vaanginen|vanginen|purchase|purchased|received|recieved|arrived|delivered|came|kammi|minus|damage|udanjiduchu|kettupochu|iruppu)(?![\p{L}\p{M}])|வந்துச்சு|வந்தது|ஸ்டாக்|வாங்கினேன்|இருப்பு""")
    /** A thing named after the service, not the service: "cutting tool", "cutting wheel", "shaving cream", "massage oil", "dye bottle". */
    private val thingAfter = Regex("""^\s*(tool|tools|wheel|wheels|blade|blades|disc|disk|board|kit|cream|oil|powder|gel|brush|set|plier|pliers|scissor|scissors|machine|mat|chair|cape|cloth|spray|comb|razor|trimmer|liquid|soap|paper|bottle|bottles|pack|packet|stand|box|tray|bag|cup|cups|glass|plate|flask|maker|stove|vessel|கருவி|மெஷின்|கிரீம்|ஆயில்|கிட்|வீல்|பிளேடு)(?![\p{L}\p{M}])""")
    /** Words that say a sale was made — enough to read a grocery's "tea" as a tea shop's sale. */
    private val saleWord = Regex("""(?i)(?<![\p{L}\p{M}])(sale|sales|sold|vithuchu|vithen|vithom|vithutten|vitthen|serve|served|order|parcel)(?![\p{L}\p{M}])|வித்துச்சு|வித்தேன்|பார்சல்""")

    /** One service said: how many (days / hours for a rental) and, when said with it, the amount for that line. */
    data class Item(val name: String, val qty: Int, val amount: BigDecimal?, val per: String? = null)

    /** A sale line: the services said, and the way it was paid when said (true = UPI / GPay, false = cash, null = not said). */
    data class SaleLine(val items: List<Item>, val upi: Boolean?)

    private data class Hit(val start: Int, val end: Int, val name: String, val service: Service?)

    /** A name of two words and the same words said as one ("hair cutting" / "haircutting", "ஹேர் கட்டிங்" / "ஹேர்கட்டிங்"). */
    private fun spellings(word: String): List<String> = listOf(word, word.replace(" ", "")).distinct()

    private class Alias(val word: String, val service: Service?) {
        val pattern = Regex("""(?<![\p{L}\p{M}])${Regex.escape(word)}(?![\p{L}\p{M}])""")
    }

    /** The catalogue's words, each with its pattern built once. */
    private val catalogueAliases: List<Alias> by lazy {
        catalogue.flatMap { s -> s.words.flatMap { w -> spellings(w.lowercase(Locale.ROOT)).map { Alias(it, s) } } }.distinctBy { it.word }
    }

    private val catalogueWords: Set<String> by lazy { catalogueAliases.map { it.word }.toSet() }

    private fun aliases(own: Collection<String>): List<Alias> {
        // (a catalogue word the owner also keeps as a name stays the catalogue's service, as before)
        val words = catalogueWords
        val owned = own.flatMap { o -> spellings(o.lowercase(Locale.ROOT)) }.distinct().filter { it !in words }
        return (catalogueAliases + owned.map { Alias(it, null) }).sortedByDescending { it.word.length }
    }

    /** Every service word in [text], longest first, never overlapping. [own] = services the owner added, by name. */
    private fun hits(text: String, own: Collection<String>): List<Hit> {
        val lower = text.lowercase(Locale.ROOT)
        val taken = BooleanArray(lower.length)
        val out = mutableListOf<Hit>()
        for (a in aliases(own)) {
            val alias = a.word
            val s = a.service
            if (!lower.contains(alias)) continue
            a.pattern.findAll(lower).forEach { m ->
                if ((m.range.first..m.range.last).none { taken[it] }) {
                    (m.range.first..m.range.last).forEach { taken[it] = true }
                    out += Hit(m.range.first, m.range.last + 1, s?.name ?: own.first { alias in spellings(it.lowercase(Locale.ROOT)) }, s)
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
    fun saleIn(text: String, own: Map<String, BigDecimal>, products: List<String>, serviceShop: Boolean, kinds: Set<Kind>? = null): SaleLine? {
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
            val said = lower.substring(h.start, h.end)
            // The owner's own Menu item for what was said: "Cutting" on the Menu is the sale of "hair cutting onnu" and of the mic's
            // "கட்டிங் ஒன்னு" — the owner's name and price, never the catalogue's.
            val ownName = ownFor(h, said, own.keys)
            val name = ownName ?: h.name
            val kept = ownName != null || name.lowercase(Locale.ROOT) in known
            // (a service said in Tamil letters is the shop's product too when its English words are in a product's name: "கட்டிங் வீல்"
            // is Cutting wheel, "ஷேவிங் கிரீம்" Shaving cream, "ஹேர் கலர்" the shop's Hair colour)
            val english = if (com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(said)) h.service?.words.orEmpty().filter { w -> w.none { it in '஀'..'௿' } } else emptyList()
            val isProduct = productWords.any { p -> p.contains(" $said ") || english.any { w -> p.contains(" $w ") } } || thingAfter.containsMatchIn(lower.substring(h.end))
            val afterEnd = found.getOrNull(i + 1)?.start ?: lower.length
            val after = lower.substring(h.end, afterEnd)
            val dur = if (h.service?.kind == Kind.RENTAL) duration.find(after) else null
            val ok = when {
                kept -> true
                isProduct -> false
                // (the services of the shop's own kind only: a salon's "cutting", a hotel's "idli" — never a grocery's "tea" sale)
                h.service != null && kinds != null && h.service.kind !in kinds -> false
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
            // "hair cutting rendu", "cutting onnu": a number word after it, as typed (the mic's "ரெண்டு" is read the same) — how many,
            // unless it is said right before the next service ("cutting rendu shaving": the next one's count).
            val wordAfter = if (m == null && number(before) == null) Regex("""^\s*([\p{L}\p{M}]+)(?![\p{L}\p{M}])""").find(rest)
                ?.takeIf { w -> number(w.groupValues[1]) != null && !(found.getOrNull(i + 1) != null && rest.substring(w.range.last + 1).isBlank()) } else null
            if (wordAfter != null) cursor = restAt + wordAfter.range.last + 1
            if (!ok) continue
            val qty = dur?.let { number(it.groupValues[1]) } ?: number(before) ?: (if (countAfter) value!!.toInt() else null)
                ?: wordAfter?.let { number(it.groupValues[1]) } ?: 1
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
    fun serviceNamed(text: String, own: Collection<String>, kinds: Set<Kind>? = null): String? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[,!?.]"""), " ") + " "
        return hits(lower, own).map { h -> h to ownFor(h, lower.substring(h.start, h.end), own) }
            .filter { (h, mine) -> mine != null || h.service == null || kinds == null || h.service.kind in kinds }
            .map { (h, mine) -> mine ?: h.name }.distinct().singleOrNull()
    }

    /** The owner's Menu name for a word said ("cutting" → their "Cutting" — or their "Hair cutting"), or null. */
    /**
     * (owner, 16 Oct 2026: "facial onnu" was sold as their "Face massage" — Kai's own list calls both Facial. The owner's Menu
     * names are theirs: an item is the one said, never another of theirs that Kai's list counts as the same.) In order: the
     * name as said ("face massage", "facemassage"); said in Tamil letters, the name by sound ("கோல்ட் ஃபேஷியல்" = Gold facial);
     * the name inside what was said ("hair cutting" for their "Cutting"); their item that has Kai's own name for the service
     * ("Hair cutting" on the Menu for "mudi vettu").
     */
    private fun ownFor(h: Hit, said: String, own: Collection<String>): String? {
        val tamil = KaiSpokenWords.hasTamil(said)
        val padded = " " + said.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ") + " "
        return own.firstOrNull { o -> said in spellings(o.lowercase(Locale.ROOT)) }
            ?: own.firstOrNull { o -> tamil && !KaiSpokenWords.hasTamil(o) && KaiProductSound.sameName(said, o) }
            // (and the other way: an item the owner said into the Menu by mic, "கட்டிங்", typed later as "cutting")
            // ("hair cutting" for a "கட்டிங்" kept: the name inside what was said, by sound)
            ?: own.firstOrNull { o -> !tamil && KaiSpokenWords.hasTamil(o) && said.trim().split(Regex("""\s+""")).let { w ->
                w.indices.any { i -> (i + 1..w.size).any { j -> KaiProductSound.sameName(o, w.subList(i, j).joinToString(" ")) } }
            } }
            ?: own.firstOrNull { o -> !tamil && padded.contains(" " + o.lowercase(Locale.ROOT).trim() + " ") }
            ?: own.firstOrNull { o -> o.equals(h.name, ignoreCase = true) }
    }

    /** Words an owner puts before or after a service's name for their own kinds of it ("Gents Haircut", "Kids Haircut", "Gold Facial"). */
    private val ownKindWords = setOf("gents", "gent", "men", "mens", "male", "ladies", "lady", "women", "womens", "female", "kids", "kid", "child",
        "children", "baby", "boys", "boy", "girls", "girl", "adult", "normal", "regular", "simple", "special", "premium", "deluxe", "gold", "silver",
        "diamond", "pearl", "herbal", "fruit", "mini", "full", "half", "small", "big", "large", "basic", "classic", "royal", "quick", "express", "style",
        "styling", "fancy", "plain")

    /**
     * The owner's Menu items that may be the service [service] Kai's list read ("Hair cutting" said, "Gents Haircut" and "Kids
     * Haircut" kept): an item named by one of the service's own words, alone or with the owner's own kind words ("gents", "kids",
     * "gold"…). Never sold as one of them by itself — Kai asks (owner, 16 Oct 2026: an item is never another of theirs; owner,
     * 19 Oct 2026: "mudi vettu onnu" with "Gents Haircut" on the Menu was asked its price as a new "Hair cutting").
     */
    fun ownChoices(service: String, own: Collection<String>): List<String> {
        val s = catalogue.firstOrNull { it.name.equals(service, ignoreCase = true) } ?: return emptyList()
        val words = s.words.filterNot(KaiSpokenWords::hasTamil).map { it.lowercase(Locale.ROOT).split(' ') }
        return own.filter { o -> !KaiSpokenWords.hasTamil(o) && !o.equals(s.name, ignoreCase = true) }.filter { o ->
            val ow = o.lowercase(Locale.ROOT).trim().split(Regex("""\s+"""))
            words.any { w -> (0..ow.size - w.size).any { i -> ow.subList(i, i + w.size) == w && (ow.take(i) + ow.drop(i + w.size)).all { it in ownKindWords } } }
        }.distinct()
    }

    /**
     * The services a shop of [category] sells (the owner's pick at setup): a beauty salon's salon and parlour services, a
     * restaurant's / tea shop's food, a hardware / electrical shop's tool rental; any other shop's none (only the services the
     * owner adds). Null — no category, or "OTHER": every kind, read as before.
     */
    fun kindsFor(category: String?): Set<Kind>? = when (category?.trim()?.uppercase(Locale.ROOT)) {
        null, "", "OTHER" -> null
        "BEAUTY_SALON" -> setOf(Kind.SALON, Kind.PARLOUR)
        "RESTAURANT_FOOD" -> setOf(Kind.TEA, Kind.HOTEL)
        "HARDWARE", "ELECTRICAL" -> setOf(Kind.RENTAL)
        else -> emptySet()
    }

    /** A shop of [category] that sells services by the piece (its "tea" is a sale). */
    fun serviceCategory(category: String?) = category?.trim()?.uppercase(Locale.ROOT) in setOf("BEAUTY_SALON", "RESTAURANT_FOOD")

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
