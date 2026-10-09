package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

/** What kind of goods a product is — it decides which details Kai asks (never grams for a shirt). */
enum class ProductKind(val category: String) {
    FMCG("FMCG"), GROCERY("Grocery"), BEVERAGE("Beverages"), LIQUID("Liquids"), GARMENT("Garments"),
    FOOTWEAR("Footwear"), HARDWARE("Hardware"), ELECTRONICS("Electronics"), GENERAL("General"),
}

/** The details of a new product Kai asks for, one at a time. */
enum class ItemField { QTY, UNIT, PER_PACK, SIZE, PURCHASE, SELLING }

/**
 * A new product the owner is adding by chat, as far as it is known. [unit] is the unit the
 * quantity was said in ("5 box" → BOX); [perPack] / [inner] what one of it holds ("48 pieces");
 * prices are for one [purchasePer] / [sellingPer] unit, as the owner said them.
 */
data class ItemSpec(
    val name: String,
    val kind: ProductKind,
    val qty: BigDecimal? = null,
    val unit: String? = null,
    val perPack: BigDecimal? = null,
    val inner: String? = null,
    /** "200 g", "1 litre", "M 8, L 7, XL 5", "2 inch" — as the owner said it. */
    val size: String? = null,
    val sizeQty: BigDecimal? = null,
    val sizeUnit: String? = null,
    val purchase: BigDecimal? = null,
    val purchasePer: String? = null,
    val selling: BigDecimal? = null,
    val sellingPer: String? = null,
    val skipped: Set<ItemField> = emptySet(),
)

/** The figures of a complete entry, all from what the owner said (nothing assumed). */
data class ItemTotals(
    /** Quantity in the product's stock unit ([baseUnit]). */
    val baseQty: BigDecimal,
    val baseUnit: String,
    /** "48 kg" — the whole weight / volume when each piece's size was given. */
    val weight: String?,
    val purchaseValue: BigDecimal?,
    val sellingValue: BigDecimal?,
    /** Prices per stock unit (what the books keep). */
    val purchasePerBase: BigDecimal?,
    val sellingPerBase: BigDecimal?,
) {
    val margin: BigDecimal? get() = if (purchaseValue != null && sellingValue != null) sellingValue - purchaseValue else null
}

/**
 * Inventory by conversation: understands a stock line for a product the books don't have yet
 * ("Colgate 5 box add pannu", "Rice 10 bags, 25kg each, purchase 1400 per bag"), says which detail
 * is still missing for that kind of product, reads the owner's answer and works out the totals.
 * Pure: nothing is saved here — the agent shows the summary and the owner confirms.
 */
object KaiInventory {

    private val kindWords: List<Pair<ProductKind, List<String>>> = listOf(
        ProductKind.GROCERY to listOf("rice", "arisi", "dal", "dhal", "paruppu", "sugar", "sakkarai", "sarkarai", "wheat", "godhumai", "gothumai", "atta",
            "maida", "flour", "rava", "ravai", "sooji", "salt", "uppu", "ragi", "toor", "urad", "moong", "chana", "besan", "kadalai", "poha", "aval",
            "jaggery", "vellam", "pulses", "millet", "corn", "maavu", "அரிசி", "பருப்பு", "சர்க்கரை", "உப்பு", "மாவு", "ரவை"),
        ProductKind.BEVERAGE to listOf("coke", "coca", "cola", "pepsi", "sprite", "fanta", "7up", "maaza", "frooti", "slice", "juice", "water", "bisleri",
            "aquafina", "kinley", "soda", "thums", "mirinda", "limca", "redbull", "drink", "drinks", "beverage"),
        ProductKind.LIQUID to listOf("oil", "ennai", "ennei", "nallennai", "milk", "paal", "ghee", "nei", "phenyl", "phenol", "harpic", "lizol", "cleaner",
            "cleaning", "vinegar", "kerosene", "எண்ணெய்", "பால்", "நெய்"),
        ProductKind.GARMENT to listOf("shirt", "shirts", "pant", "pants", "saree", "sarees", "sari", "tshirt", "t-shirt", "chudi", "churidar", "nighty",
            "dhoti", "veshti", "vesti", "lungi", "jeans", "kurta", "kurti", "banian", "inner", "leggings", "frock", "blouse", "towel", "சட்டை", "புடவை", "வேட்டி"),
        ProductKind.FOOTWEAR to listOf("shoe", "shoes", "sandal", "sandals", "slipper", "slippers", "chappal", "chappals", "cheppal", "footwear", "செருப்பு"),
        ProductKind.HARDWARE to listOf("screw", "screws", "nail", "nails", "aani", "bolt", "bolts", "nut", "nuts", "washer", "washers", "pipe", "pipes",
            "wire", "switch", "switches", "plug", "hinge", "hinges", "paint", "tape", "cement", "ஆணி"),
        ProductKind.ELECTRONICS to listOf("charger", "chargers", "cable", "cables", "earphone", "earphones", "headphone", "headphones", "adapter",
            "adapters", "powerbank", "usb", "battery", "batteries", "bulb", "led", "speaker", "remote", "pendrive"),
        ProductKind.FMCG to listOf("colgate", "pepsodent", "closeup", "sensodyne", "paste", "toothpaste", "brush", "soap", "lux", "lifebuoy", "hamam",
            "dettol", "santoor", "medimix", "cinthol", "pears", "shampoo", "clinic", "biscuit", "biscuits", "parle", "britannia", "marie", "goodday",
            "detergent", "surf", "rin", "tide", "ariel", "wheel", "vim", "maggi", "noodles", "chips", "lays", "chocolate", "dairymilk", "kitkat",
            "tea", "coffee", "bru", "horlicks", "boost", "bournvita", "cream", "powder", "talc", "agarbatti", "sopu", "சோப்பு"),
    )

    /** A product's kind from its name, else from the unit it was said in. */
    fun kindOf(name: String, unit: String?): ProductKind {
        val words = name.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{M}\p{N}-]+""")).filter { it.isNotEmpty() }
        val joined = words.joinToString("")
        kindWords.firstOrNull { (_, list) -> list.any { w -> w in words || (w.length >= 6 && joined.contains(w)) } }?.let { return it.first }
        return when (KaiUnits.canon(unit)) {
            "BAG", "KG", "GRAM" -> ProductKind.GROCERY
            "CASE" -> ProductKind.BEVERAGE
            "LITRE", "ML", "BOTTLE", "CAN" -> ProductKind.LIQUID
            "PAIR" -> ProductKind.FOOTWEAR
            else -> ProductKind.GENERAL
        }
    }

    /**
     * The picture a product gets in the inventory list: Kai's own category ("Grocery"), else its name ("Arisi", "Coke"),
     * else the owner's category word ("rice", "kids wear"), else its unit — a plain box when nothing fits.
     */
    fun artKindOf(name: String, category: String?, unit: String?): ProductKind {
        val c = category?.trim().orEmpty()
        ProductKind.values().firstOrNull { it != ProductKind.GENERAL && it.category.equals(c, ignoreCase = true) }?.let { return it }
        kindOf(name, null).takeIf { it != ProductKind.GENERAL }?.let { return it }
        if (c.isNotEmpty()) {
            val byCategory = kindOf(c, null)
            if (byCategory != ProductKind.GENERAL) return byCategory
            val lc = c.lowercase(Locale.ROOT)
            when {
                Regex("""grocer|provision|kirana|maligai|pulse|grain""").containsMatchIn(lc) -> return ProductKind.GROCERY
                Regex("""beverage|drink|juice|cool""").containsMatchIn(lc) -> return ProductKind.BEVERAGE
                Regex("""oil|liquid|dairy|milk""").containsMatchIn(lc) -> return ProductKind.LIQUID
                Regex("""garment|textile|cloth|wear|dress|kids|apparel|fashion|saree""").containsMatchIn(lc) -> return ProductKind.GARMENT
                Regex("""foot|shoe|chappal|slipper""").containsMatchIn(lc) -> return ProductKind.FOOTWEAR
                Regex("""hardware|tool|electrical|paint|plumb""").containsMatchIn(lc) -> return ProductKind.HARDWARE
                Regex("""electronic|mobile|accessor|gadget""").containsMatchIn(lc) -> return ProductKind.ELECTRONICS
                Regex("""fmcg|personal|care|cosmetic|toiletr|snack|biscuit|soap""").containsMatchIn(lc) -> return ProductKind.FMCG
            }
        }
        return kindOf(name, unit)
    }

    /** Units that hold other units ("1 box = 48 pieces") — their size is asked, never assumed. */
    private val packUnits = setOf("BOX", "BAG", "CASE", "CARTON", "CAN", "BUNDLE", "STRIP")

    /** What one [pack] of this kind holds when the owner didn't say: pieces of soap, kg of rice, bottles of Coke. */
    fun innerOf(kind: ProductKind, pack: String): String = when (kind) {
        ProductKind.GROCERY -> if (pack == "BAG") "KG" else "PCS"
        ProductKind.BEVERAGE -> "BOTTLE"
        ProductKind.LIQUID -> if (pack == "CAN") "LITRE" else "BOTTLE"
        ProductKind.FOOTWEAR -> "PAIR"
        else -> "PCS"
    }

    fun needsPerPack(spec: ItemSpec): Boolean = KaiUnits.canon(spec.unit) in packUnits

    /** The product's stock unit: pieces of a box, kg of a bag, else the unit it was said in. */
    fun baseUnit(spec: ItemSpec): String {
        val u = KaiUnits.canon(spec.unit) ?: return "PCS"
        if (u == "DOZEN") return "PCS"
        return if (u in packUnits) KaiUnits.canon(spec.inner) ?: innerOf(spec.kind, u) else u
    }

    /** Whether a size (weight / volume / clothes size / spec) is asked for this kind and stock unit. */
    private fun asksSize(spec: ItemSpec): Boolean {
        val base = baseUnit(spec)
        return when (spec.kind) {
            ProductKind.FMCG -> base in setOf("PCS", "PACK")
            ProductKind.BEVERAGE -> base in setOf("BOTTLE", "PCS", "CAN")
            ProductKind.LIQUID -> base in setOf("BOTTLE", "PCS", "PACK")
            ProductKind.GARMENT, ProductKind.FOOTWEAR, ProductKind.HARDWARE, ProductKind.ELECTRONICS -> true
            ProductKind.GROCERY, ProductKind.GENERAL -> false
        }
    }

    /** The unit a price is said for: a bag of rice is bought by the bag, sold by the kg; everything else by its stock unit. */
    fun purchaseUnit(spec: ItemSpec): String =
        if (spec.kind == ProductKind.GROCERY && KaiUnits.canon(spec.unit) == "BAG" && baseUnit(spec) == "KG") "BAG" else baseUnit(spec)
    fun sellingUnit(spec: ItemSpec): String = baseUnit(spec)

    /** The next detail to ask, or null when the entry is complete. One at a time; a skipped detail is not asked again. */
    fun next(spec: ItemSpec): ItemField? = when {
        spec.qty == null -> ItemField.QTY
        spec.unit == null -> ItemField.UNIT
        needsPerPack(spec) && spec.perPack == null -> ItemField.PER_PACK
        asksSize(spec) && spec.size == null && ItemField.SIZE !in spec.skipped -> ItemField.SIZE
        spec.purchase == null && ItemField.PURCHASE !in spec.skipped -> ItemField.PURCHASE
        spec.selling == null && ItemField.SELLING !in spec.skipped -> ItemField.SELLING
        else -> null
    }

    // ------------------------------------------------------------------ words → numbers / units

    private fun lowerOf(text: String) = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!;?]"""), " ").replace(Regex("""(\d),(\d)"""), "$1$2")
        .replace(Regex("""(\d)([a-z\p{L}]+)"""), "$1 $2").replace(Regex("""\s+"""), " ").trim() + " "

    private val extraUnits = mapOf(
        "pair" to "PAIR", "pairs" to "PAIR", "jodi" to "PAIR", "can" to "CAN", "cans" to "CAN", "tin" to "CAN", "tins" to "CAN",
        "sack" to "BAG", "sacks" to "BAG", "l" to "LITRE", "lit" to "LITRE", "mtr" to "METER", "meter" to "METER", "metre" to "METER",
        "bottil" to "BOTTLE", "kilo" to "KG", "kilos" to "KG", "piece" to "PCS",
        "கிராம்" to "GRAM", "லிட்டர்" to "LITRE", "பாட்டில்" to "BOTTLE", "மூட்டை" to "BAG", "டஜன்" to "DOZEN", "ஜோடி" to "PAIR",
    )

    /** "box" / "boxes" / "boxku" / "box-aa" / "பாக்ஸ்" → BOX; null when the word isn't a unit. */
    fun unitOf(word: String): String? {
        val w = word.lowercase(Locale.ROOT).trim('.', ',', '-', '\'')
        KaiStock.unitOf(w)?.let { return KaiUnits.canon(it) }
        extraUnits[w]?.let { return it }
        // "boxku", "box-la", "bagla", "piece-aa": the unit with a Tamil ending.
        val stem = w.replace(Regex("""(?:-)?(?:kku|ukku|ku|la|ula|le|aa|ah|a|ம்|க்கு|ல)$"""), "").trimEnd('-')
        if (stem != w && stem.length >= 1) {
            KaiStock.unitOf(stem)?.let { return KaiUnits.canon(it) }
            extraUnits[stem]?.let { return it }
        }
        return null
    }

    private val numberWord = mapOf(
        "oru" to 1, "onnu" to 1, "one" to 1, "rendu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4, "anju" to 5, "five" to 5,
        "aaru" to 6, "six" to 6, "ezhu" to 7, "seven" to 7, "ettu" to 8, "eight" to 8, "onbadhu" to 9, "nine" to 9, "pathu" to 10, "ten" to 10,
        "pannendu" to 12, "twelve" to 12, "irupathu" to 20, "twenty" to 20, "irupathanju" to 25, "muppathu" to 30, "naarpathu" to 40,
        "naarpathettu" to 48, "aimbadhu" to 50, "fifty" to 50, "nooru" to 100, "hundred" to 100,
    )

    /** The first number in [text] — digits ("28", "1,400", "1.5") or a spoken number ("rendu", "ainooru"). */
    fun numberIn(text: String, today: LocalDate = LocalDate.now()): BigDecimal? {
        val lower = lowerOf(text)
        Regex("""(?<![\p{L}\d.])(\d+(?:\.\d+)?)""").find(lower)?.groupValues?.get(1)?.toBigDecimalOrNull()?.let { return it }
        lower.split(' ').firstNotNullOfOrNull { numberWord[it] }?.let { return BigDecimal(it) }
        return runCatching { com.shopai.app.brain.KaiUnderstanding.amountsIn(text, today) }.getOrNull()?.firstOrNull()?.let { BigDecimal.valueOf(it) }
    }

    private val skipWords = Regex("""(?i)(?<![\p{L}])(skip|theriyadhu|theriyathu|theriyala|teriyadhu|apram|aprom|appuram|later|venam|vendam|vendaam|pass|no\s+idea|illa|illai|thevai\s*illa)(?![\p{L}])|தெரியாது|அப்புறம்|வேண்டாம்""")

    /** "skip", "theriyadhu", "apram" — the owner doesn't want to give this detail now. */
    fun skips(text: String): Boolean = skipWords.containsMatchIn(text) && Regex("""\d""").find(text) == null

    // ------------------------------------------------------------------ one message with everything

    private const val U = """[\p{L}\p{M}]+"""
    private val n = """(\d+(?:\.\d+)?)"""

    /** "boxku 48 pieces", "box-la 48", "1 box = 48 pieces", "per box 48", "48 pieces per box", "25kg each", "caseக்கு 24 bottles". */
    private fun perPackIn(lower: String, spec: ItemSpec): Pair<BigDecimal, String?>? {
        val pack = KaiUnits.canon(spec.unit)
        fun isPack(w: String) = unitOf(w)?.let { it == pack || (pack == null && it in packUnits) } == true
        // "<pack>ku 48 pieces" / "<pack> la 48" / "1 <pack> = 48" / "per <pack> 48"
        Regex("""(?<![\p{L}\d])(?:(?:1|oru|one|per|each|ovvoru)\s+)?($U)(?:\s*-?\s*(?:kku|ku|ukku|க்கு|la|ல|kulla|ukkulla))?\s*(?:=|:)?\s*$n\s*($U)?""").findAll(lower).forEach { m ->
            val w = m.groupValues[1]
            val marked = Regex("""(?:kku|ku|ukku|க்கு|la|ல|kulla)$""").containsMatchIn(w) || m.value.contains('=') || m.value.contains(':') ||
                Regex("""^(?:1|oru|one|per|each|ovvoru)\s""").containsMatchIn(m.value.trim()) ||
                Regex("""\s(?:kku|ku|ukku|க்கு|la|ல|kulla)\s""").containsMatchIn(" " + m.value + " ")
            if (marked && isPack(w)) {
                val inner = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::unitOf)
                return m.groupValues[2].toBigDecimal() to inner
            }
        }
        // "48 pieces per box" / "48 pieces each" / "25kg each" / "12 bottles each box"
        Regex("""$n\s*($U)\s+(?:per|each|every|ovvoru|oru)\s*($U)?""").findAll(lower).forEach { m ->
            val inner = unitOf(m.groupValues[2]) ?: return@forEach
            val after = m.groupValues[3]
            if (after.isEmpty() || isPack(after) || unitOf(after) == null) {
                if (inner != pack) return m.groupValues[1].toBigDecimal() to inner
            }
        }
        return null
    }

    private val weightUnits = setOf("GRAM", "KG", "ML", "LITRE")

    /** "piece 200 gram", "200g each", "1 litre bottle", "500 ml" — the size of one piece / bottle. */
    private fun sizeIn(lower: String, spec: ItemSpec, perPackText: String?): Triple<BigDecimal, String, String>? {
        val base = baseUnit(spec)
        if (base in weightUnits) return null
        Regex("""$n\s*($U)""").findAll(lower).forEach { m ->
            val u = unitOf(m.groupValues[2]) ?: return@forEach
            if (u !in weightUnits) return@forEach
            if (perPackText != null && m.value.trim() == perPackText.trim()) return@forEach
            // The main quantity ("Rice 10 kg") is not a size.
            if (spec.qty != null && m.groupValues[1].toBigDecimal().compareTo(spec.qty) == 0 && KaiUnits.canon(spec.unit) == u) return@forEach
            val q = m.groupValues[1].toBigDecimal()
            return Triple(q, u, "${plain(q)} ${unitShort(u)}")
        }
        return null
    }

    private val purchaseWord = """(?:purchase|purchasing|buying|buy|cost|vaangu(?:na|n|m|ra)?|vaangina|kolmudhal|p\.?\s*rate|பர்சேஸ்|வாங்கு\S*)"""
    private val sellingWord = """(?:selling|sell|sale\s+(?:price|rate)|sales\s+(?:price|rate)|vikkura|vikka|virpanai|vithu|mrp|s\.?\s*rate|செல்லிங்|விற்பனை)"""
    private val priceTail = """\s*(?:rate|price|vilai|vela|விலை)?\s*(?:₹|rs\.?|rupees?)?\s*$n\s*(?:rs|rupees?|rupa|rooba|ரூபாய்)?\s*(?:(?:per|/|oru|ovvoru)\s*($U))?"""

    private fun priceIn(lower: String, word: String): Pair<BigDecimal, String?>? =
        Regex("""(?<![\p{L}])$word$priceTail""").find(lower)?.let { m -> m.groupValues[1].toBigDecimal() to m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::unitOf) }

    /** Sizes said for clothes / shoes: "M 8 L 7 XL 5", "size 7 8 9". */
    private fun clothesSizesIn(text: String): String? {
        val pairs = Regex("""(?i)(?<![\p{L}])(xxxl|xxl|xl|xs|s|m|l|\d{1,2})\s*[-:]?\s*(\d{1,3})(?![\d\p{L}])""").findAll(text)
            .filter { it.groupValues[1].any(Char::isLetter) }.toList()
        if (pairs.size >= 2) return pairs.joinToString(", ") { "${it.groupValues[1].uppercase(Locale.ROOT)} ${it.groupValues[2]}" }
        return Regex("""(?i)(?<![\p{L}])size\s+([\w\s,/-]{1,30})""").find(text)?.groupValues?.get(1)?.trim()?.trimEnd(',')
    }

    /** Every detail said in one message, added to [spec] (what was already known stays). */
    fun withDetails(spec: ItemSpec, text: String): ItemSpec {
        val lower = lowerOf(text)
        var s = spec
        perPackIn(lower, s)?.let { (q, inner) -> if (s.perPack == null && q.signum() > 0) s = s.copy(perPack = q, inner = inner ?: s.inner) }
        val perPackText = Regex("""$n\s*($U)\s+(?:per|each|every|ovvoru)""").find(lower)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        if (s.size == null) when (s.kind) {
            ProductKind.GARMENT, ProductKind.FOOTWEAR -> clothesSizesIn(text)?.let { s = s.copy(size = it) }
            ProductKind.HARDWARE, ProductKind.ELECTRONICS -> Regex("""(?i)(?<![\p{L}])(?:size|spec|model)\s+([\w\s."/-]{1,30})""").find(text)
                ?.groupValues?.get(1)?.trim()?.trimEnd(',')?.let { s = s.copy(size = it) }
            else -> sizeIn(lower, s, perPackText)?.let { (q, u, shown) -> s = s.copy(size = shown, sizeQty = q, sizeUnit = u) }
        }
        priceIn(lower, purchaseWord)?.let { (p, per) -> if (s.purchase == null) s = s.copy(purchase = p, purchasePer = per) }
        priceIn(lower, sellingWord)?.let { (p, per) -> if (s.selling == null) s = s.copy(selling = p, sellingPer = per) }
        return s
    }

    /** True when [text] carries product details beyond the quantity (a pack size, a size, a price). */
    fun hasDetails(text: String, spec: ItemSpec): Boolean {
        val d = withDetails(spec.copy(perPack = null, size = null, purchase = null, selling = null), text)
        return d.perPack != null || d.size != null || d.purchase != null || d.selling != null
    }

    /**
     * "Colgate 5 box, boxக்கு 48 pieces, piece 200 gram, purchase 28, selling 35" — a stock line with its
     * details and no stock verb: (name, quantity, unit). Null when it doesn't start with a name and a quantity.
     */
    fun stockLine(text: String): Triple<String, BigDecimal, String?>? {
        val lower = lowerOf(text).trim()
        val m = Regex("""^([\p{L}\p{M}][\p{L}\p{M}\s.'-]{1,40}?)\s+(\d+(?:\.\d+)?|${numberWord.keys.joinToString("|")})\s*($U)?(?=[\s,]|$)""").find(lower) ?: return null
        val name = m.groupValues[1].trim()
        if (name.split(' ').size > 4) return null
        val q = m.groupValues[2].toBigDecimalOrNull() ?: numberWord[m.groupValues[2]]?.toBigDecimal() ?: return null
        val unit = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::unitOf)
        return Triple(display(name), q, unit)
    }

    // ------------------------------------------------------------------ answers to one question

    /** The owner's answer to [field]; null when it doesn't answer it (Kai asks again or the message goes elsewhere). */
    fun answer(spec: ItemSpec, field: ItemField, text: String, today: LocalDate = LocalDate.now()): ItemSpec? {
        val lower = lowerOf(text)
        if (field in setOf(ItemField.SIZE, ItemField.PURCHASE, ItemField.SELLING) && skips(text)) return spec.copy(skipped = spec.skipped + field)
        return when (field) {
            ItemField.QTY -> {
                val parts = KaiStock.partsIn(lower).ifEmpty { numberIn(text, today)?.let { listOf(QtyPart(it, null)) }.orEmpty() }
                val p = parts.firstOrNull()?.takeIf { it.qty.signum() > 0 } ?: return null
                val unit = KaiUnits.canon(p.unit) ?: lower.split(' ').firstNotNullOfOrNull(::unitOf)
                withDetails(spec.copy(qty = p.qty, unit = unit ?: spec.unit), text)
            }
            ItemField.UNIT -> {
                val unit = lower.split(' ').firstNotNullOfOrNull(::unitOf) ?: return null
                val q = numberIn(text, today)?.takeIf { it.signum() > 0 }
                withDetails(spec.copy(unit = unit, qty = q ?: spec.qty), text)
            }
            ItemField.PER_PACK -> {
                if (lower.contains('=')) perPackIn(lower, spec)?.let { (q, inner) -> return spec.copy(perPack = q, inner = inner ?: spec.inner) }
                val q = numberIn(text, today)?.takeIf { it.signum() > 0 } ?: return null
                val inner = lower.split(' ').firstNotNullOfOrNull(::unitOf)?.takeIf { it != KaiUnits.canon(spec.unit) }
                spec.copy(perPack = q, inner = inner ?: spec.inner)
            }
            ItemField.SIZE -> when (spec.kind) {
                ProductKind.GARMENT, ProductKind.FOOTWEAR, ProductKind.HARDWARE, ProductKind.ELECTRONICS -> {
                    val said = (clothesSizesIn(text) ?: text.trim().trim('.', ',')).takeIf { it.isNotBlank() && it.length <= 40 } ?: return null
                    spec.copy(size = said)
                }
                else -> {
                    val q = numberIn(text, today)?.takeIf { it.signum() > 0 } ?: return null
                    val u = lower.split(' ').firstNotNullOfOrNull(::unitOf)?.takeIf { it in weightUnits } ?: defaultSizeUnit(spec, q)
                    spec.copy(size = "${plain(q)} ${unitShort(u)}", sizeQty = q, sizeUnit = u)
                }
            }
            ItemField.PURCHASE -> {
                val p = numberIn(text, today)?.takeIf { it.signum() >= 0 } ?: return null
                val per = Regex("""(?:per|/|oru|ovvoru)\s*($U)""").find(lower)?.groupValues?.get(1)?.let(::unitOf)
                spec.copy(purchase = p, purchasePer = per)
            }
            ItemField.SELLING -> {
                val p = numberIn(text, today)?.takeIf { it.signum() >= 0 } ?: return null
                val per = Regex("""(?:per|/|oru|ovvoru)\s*($U)""").find(lower)?.groupValues?.get(1)?.let(::unitOf)
                spec.copy(selling = p, sellingPer = per)
            }
        }
    }

    private fun defaultSizeUnit(spec: ItemSpec, q: BigDecimal): String = when (spec.kind) {
        ProductKind.BEVERAGE -> if (q <= BigDecimal(5)) "LITRE" else "ML"
        ProductKind.LIQUID -> if (q <= BigDecimal(5)) "LITRE" else "ML"
        else -> "GRAM"
    }

    // ------------------------------------------------------------------ totals

    /** Stock units in one [unit] of this entry (box → 48 pieces, bag → 25 kg, kg → 1000 g …); null when unknown. */
    private fun factor(spec: ItemSpec, unit: String?): BigDecimal? {
        val u = KaiUnits.canon(unit) ?: return BigDecimal.ONE
        val base = baseUnit(spec)
        if (u == base) return BigDecimal.ONE
        if (u == KaiUnits.canon(spec.unit) && spec.perPack != null) return spec.perPack
        if (u == "DOZEN" && base == "PCS") return BigDecimal(12)
        return when (u to base) {
            "KG" to "GRAM" -> BigDecimal(1000); "GRAM" to "KG" -> BigDecimal("0.001")
            "LITRE" to "ML" -> BigDecimal(1000); "ML" to "LITRE" -> BigDecimal("0.001")
            else -> null
        }
    }

    fun totals(spec: ItemSpec): ItemTotals? {
        val qty = spec.qty ?: return null
        if (spec.unit == null) return null
        val f = factor(spec, spec.unit) ?: return null
        val baseQty = KaiUnits.clean(qty.multiply(f))
        val base = baseUnit(spec)
        val weight = if (spec.sizeQty != null && spec.sizeUnit != null && base !in weightUnits) {
            val total = baseQty.multiply(spec.sizeQty)
            when (spec.sizeUnit) {
                "GRAM" -> if (total >= BigDecimal(1000)) "${plain(total.divide(BigDecimal(1000)))} kg" else "${plain(total)} g"
                "ML" -> if (total >= BigDecimal(1000)) "${plain(total.divide(BigDecimal(1000)))} litre" else "${plain(total)} ml"
                "KG" -> "${plain(total)} kg"
                else -> "${plain(total)} litre"
            }
        } else null
        fun perBase(price: BigDecimal?, per: String?): BigDecimal? {
            price ?: return null
            val pf = factor(spec, per) ?: return null
            return price.divide(pf, 4, RoundingMode.HALF_UP).stripTrailingZeros()
        }
        val pBase = perBase(spec.purchase, spec.purchasePer ?: purchaseUnit(spec))
        val sBase = perBase(spec.selling, spec.sellingPer ?: sellingUnit(spec))
        fun value(p: BigDecimal?) = p?.multiply(baseQty)?.setScale(2, RoundingMode.HALF_UP)
        return ItemTotals(baseQty, base, weight, value(pBase), value(sBase), pBase, sBase)
    }

    // ------------------------------------------------------------------ what Kai says

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }

    fun one(unit: String?) = KaiStock.unitWord(unit, BigDecimal.ONE)
    fun many(unit: String?) = KaiStock.unitWord(unit, BigDecimal.TEN)
    fun shown(q: BigDecimal, unit: String?) = KaiStock.shown(q, unit)
    fun rupees(a: BigDecimal) = KaiFormat.rupees(a.toDouble())

    /** The one question for [field], in the owner's language. */
    fun question(spec: ItemSpec, field: ItemField, lang: KaiLang): String {
        val name = spec.name
        return when (field) {
            ItemField.QTY -> pick(lang,
                ta = "சரி Owner 👍 $name எவ்வளவு வந்திருக்கு? (உதா: 5 box)",
                tl = "Seri Owner 👍 $name evlo vandhirukku? (eg: 5 box)",
                en = "Sure Owner 👍 How much $name came in? (e.g. 5 boxes)")
            ItemField.UNIT -> {
                val q = plain(spec.qty ?: BigDecimal.ONE)
                val (a, b) = when (spec.kind) {
                    ProductKind.GROCERY -> "BAG" to "KG"
                    ProductKind.BEVERAGE -> "BOTTLE" to "CASE"
                    ProductKind.LIQUID -> "BOTTLE" to "LITRE"
                    ProductKind.FOOTWEAR -> "PAIR" to "BOX"
                    else -> "PCS" to "BOX"
                }
                val qa = KaiStock.shown(spec.qty ?: BigDecimal.ONE, a)
                val qb = KaiStock.shown(spec.qty ?: BigDecimal.ONE, b)
                pick(lang, ta = "$name $q — $qa-ஆ, $qb-ஆ Owner?", tl = "$name $q — $qa-aa, $qb-aa Owner?", en = "$name $q — $qa or $qb, Owner?")
            }
            ItemField.PER_PACK -> {
                val pack = one(spec.unit)
                val inner = innerOf(spec.kind, KaiUnits.canon(spec.unit) ?: "BOX")
                if (inner == "KG" || inner == "LITRE") pick(lang,
                    ta = "சரி Owner 👍 1 $pack எத்தனை ${many(inner)}?", tl = "Seri Owner 👍 1 $pack evlo ${many(inner)}?", en = "Sure Owner 👍 How many ${many(inner)} in 1 $pack?")
                else pick(lang,
                    ta = "சரி Owner 👍 1 $pack-ல எத்தனை ${many(inner)} இருக்கு?", tl = "Seri Owner 👍 1 $pack-la evlo ${many(inner)} irukku?",
                    en = "Sure Owner 👍 How many ${many(inner)} are in 1 $pack?")
            }
            ItemField.SIZE -> {
                val piece = one(baseUnit(spec))
                when (spec.kind) {
                    ProductKind.GARMENT -> pick(lang,
                        ta = "Size என்ன Owner? (உதா: M 8, L 7, XL 5) — தெரியலனா 'skip'", tl = "Size enna Owner? (eg: M 8, L 7, XL 5) — theriyalana 'skip'",
                        en = "Which sizes, Owner? (e.g. M 8, L 7, XL 5) — or 'skip'")
                    ProductKind.FOOTWEAR -> pick(lang,
                        ta = "Size என்ன Owner? (உதா: 7, 8, 9) — தெரியலனா 'skip'", tl = "Size enna Owner? (eg: 7, 8, 9) — theriyalana 'skip'",
                        en = "Which sizes, Owner? (e.g. 7, 8, 9) — or 'skip'")
                    ProductKind.HARDWARE -> pick(lang,
                        ta = "Size / spec என்ன Owner? (உதா: 2 inch) — தெரியலனா 'skip'", tl = "Size / spec enna Owner? (eg: 2 inch) — theriyalana 'skip'",
                        en = "Size / spec, Owner? (e.g. 2 inch) — or 'skip'")
                    ProductKind.ELECTRONICS -> pick(lang,
                        ta = "Model / spec என்ன Owner? — தெரியலனா 'skip'", tl = "Model / spec enna Owner? — theriyalana 'skip'", en = "Model / spec, Owner? — or 'skip'")
                    ProductKind.BEVERAGE -> pick(lang,
                        ta = "1 $piece எத்தனை ml? — தெரியலனா 'skip'", tl = "1 $piece evlo ml? — theriyalana 'skip'", en = "How many ml in 1 $piece? — or 'skip'")
                    ProductKind.LIQUID -> pick(lang,
                        ta = "1 $piece எத்தனை litre / ml? — தெரியலனா 'skip'", tl = "1 $piece evlo litre / ml? — theriyalana 'skip'",
                        en = "How many litres / ml in 1 $piece? — or 'skip'")
                    else -> pick(lang,
                        ta = "ஒரு $piece எத்தனை gram? — தெரியலனா 'skip'", tl = "Oru $piece evlo gram? — theriyalana 'skip'", en = "How many grams is 1 $piece? — or 'skip'")
                }
            }
            ItemField.PURCHASE -> {
                val per = one(purchaseUnit(spec))
                pick(lang, ta = "ஒரு $per purchase rate எவ்வளவு?", tl = "Oru $per purchase rate evlo?", en = "Purchase rate for 1 $per?")
            }
            ItemField.SELLING -> {
                val per = one(sellingUnit(spec))
                pick(lang, ta = "ஒரு $per selling rate எவ்வளவு?", tl = "Oru $per selling rate evlo?", en = "Selling rate for 1 $per?")
            }
        }
    }

    /** The compact summary shown before saving: quantities, size, prices and totals — every figure from the owner. */
    fun summaryLines(spec: ItemSpec, t: ItemTotals, lang: KaiLang): List<String> {
        val lines = mutableListOf<String>()
        val said = shown(spec.qty!!, spec.unit)
        lines += if (KaiUnits.canon(spec.unit) == t.baseUnit) said else "$said = ${shown(t.baseQty, t.baseUnit)}"
        spec.size?.let { s -> lines += if (spec.sizeQty != null) pick(lang, ta = "ஒவ்வொன்றும் $s", tl = "Ovvonnum $s", en = "$s each") + (t.weight?.let { " ($it)" } ?: "") else "Size: $s" }
        spec.purchase?.let { p ->
            lines += "Purchase ${rupees(p)}/${one(spec.purchasePer ?: purchaseUnit(spec))}" + (t.purchaseValue?.let { " → ${rupees(it)}" } ?: "")
        }
        spec.selling?.let { p ->
            lines += "Selling ${rupees(p)}/${one(spec.sellingPer ?: sellingUnit(spec))}" + (t.sellingValue?.let { " → ${rupees(it)}" } ?: "")
        }
        t.margin?.let { lines += pick(lang, ta = "லாபம் ${rupees(it)}", tl = "Margin ${rupees(it)}", en = "Margin ${rupees(it)}") }
        return lines
    }

    /** "5 boxes = 240 pieces. Purchase ₹6,720. Selling value ₹8,400. Stock add pannattuma?" */
    fun summaryText(spec: ItemSpec, t: ItemTotals, lang: KaiLang): String {
        val said = shown(spec.qty!!, spec.unit)
        val qty = if (KaiUnits.canon(spec.unit) == t.baseUnit) said else "$said = ${shown(t.baseQty, t.baseUnit)}"
        val money = listOfNotNull(
            t.purchaseValue?.let { "Purchase ${rupees(it)}" },
            t.sellingValue?.let { pick(lang, ta = "Selling value ${rupees(it)}", tl = "Selling value ${rupees(it)}", en = "Selling value ${rupees(it)}") },
        ).joinToString(". ")
        val body = "${spec.name} — $qty." + (if (money.isNotEmpty()) " $money." else "")
        return pick(lang,
            ta = "சரி Owner. $body Stock add பண்ணட்டுமா?",
            tl = "Seri Owner. $body Stock add pannattuma?",
            en = "Okay Owner. $body Shall I add it to stock?")
    }

    fun plain(q: BigDecimal): String = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    private fun unitShort(u: String) = when (u) { "GRAM" -> "g"; "KG" -> "kg"; "ML" -> "ml"; "LITRE" -> "litre"; else -> u.lowercase(Locale.ROOT) }

    fun display(s: String) = s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    // ------------------------------------------------------------------ product edits, details, summary

    /** What the owner asks about one product the books have. */
    sealed interface ProductAsk {
        /** "Colgate selling price 38 aakku", "Rice purchase rate 1400 per bag". */
        data class Price(val selling: Boolean, val price: BigDecimal, val per: String?) : ProductAsk
        /** "Rice minimum stock 5 bags aakku", "Colgate 2 box-ku keela pona remind pannu". */
        data class Minimum(val qty: BigDecimal, val unit: String?) : ProductAsk
        /** "Colgate details kaattu". */
        data object Details : ProductAsk
    }

    private val sellingPrice = Regex("""(?i)(?<![\p{L}])(?:selling|sell|sale|sales|vikkura|vikka|virpanai|mrp|செல்லிங்|விற்பனை)\s*(?:price|rate|vilai|vela|விலை)?\s*(?:-?\s*(?:a|ah|aa|ai|ஐ|அ))?\s*(?:=|:)?\s*(?:₹|rs\.?)?\s*(\d+(?:\.\d+)?)\s*(?:rs|rupees?|ரூபாய்)?\s*(?:(?:per|/|oru)\s*([\p{L}]+))?""")
    private val purchasePrice = Regex("""(?i)(?<![\p{L}])(?:purchase|buying|buy|cost|vaangura|vaangum|kolmudhal|பர்சேஸ்)\s*(?:price|rate|vilai|vela|விலை)?\s*(?:-?\s*(?:a|ah|aa|ai))?\s*(?:=|:)?\s*(?:₹|rs\.?)?\s*(\d+(?:\.\d+)?)\s*(?:rs|rupees?|ரூபாய்)?\s*(?:(?:per|/|oru)\s*([\p{L}]+))?""")
    private val minimumA = Regex("""(?i)(?<![\p{L}])(?:minimum|min|reorder|low\s*stock)\s*(?:stock|level|alert|alavu)?\s*(?:=|:)?\s*(\d+(?:\.\d+)?)\s*([\p{L}]+)?""")
    private val minimumB = Regex("""(?i)(\d+(?:\.\d+)?)\s*([\p{L}]+)?\s*(?:-?\s*(?:ku|kku|க்கு))?\s*(?:keela|keezha|keezhe|kizhe|kila|kela|below|கீழ)""")
    private val detailsWord = Regex("""(?i)(?<![\p{L}])(?:details|detail|vivaram|vivarangal|full\s+info|info)(?![\p{L}])|விவரம்""")

    fun productAsk(text: String): ProductAsk? {
        val lower = lowerOf(text)
        (minimumA.find(lower) ?: minimumB.find(lower))?.let { m ->
            val unit = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::unitOf)
            return ProductAsk.Minimum(m.groupValues[1].toBigDecimal(), unit)
        }
        sellingPrice.find(lower)?.let { m -> return ProductAsk.Price(true, m.groupValues[1].toBigDecimal(), m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::unitOf)) }
        purchasePrice.find(lower)?.let { m -> return ProductAsk.Price(false, m.groupValues[1].toBigDecimal(), m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::unitOf)) }
        if (detailsWord.containsMatchIn(lower)) return ProductAsk.Details
        return null
    }

    private val summaryWords = Regex("""(?i)(?<![\p{L}])(?:inventory|stock)\s*(?:summary|value|report|motham|total)(?![\p{L}])|(?<![\p{L}])(?:summary|value)\s+(?:of\s+)?(?:inventory|stock)(?![\p{L}])""")
    private val listWords = Regex("""(?i)(?<![\p{L}])(?:ella|ellaa|ellam|ellaam|all|full)\s+(?:inventory|stock|products?)\s*(?:items?|list)?(?![\p{L}])|(?<![\p{L}])inventory\s+(?:items|list)(?![\p{L}])""")

    /** "en inventory summary kaattu", "stock value evlo?". */
    fun asksSummary(text: String): Boolean = summaryWords.containsMatchIn(text)
    /** "ella inventory items kaattu", "inventory list". */
    fun asksList(text: String): Boolean = listWords.containsMatchIn(text)

    /** "5 boxes" / "5 boxes + 3 pieces" for a quantity in [p]'s stock unit, using its own pack size; null when it has none. */
    fun packView(p: ProductRef, qty: BigDecimal): String? {
        val (unit, f) = p.conversions.entries.filter { it.value > BigDecimal.ONE }.maxByOrNull { it.value }?.toPair() ?: return null
        if (qty < f) return null
        val packs = qty.divideToIntegralValue(f)
        val rest = KaiUnits.clean(qty - packs.multiply(f))
        val head = KaiStock.shown(packs, unit)
        return if (rest.signum() == 0) head else "$head + ${KaiStock.shown(rest, p.unit)}"
    }

    /** Stock value at the purchase price (null when the product has no purchase price). */
    fun stockValue(p: ProductRef): BigDecimal? = p.purchasePrice?.multiply(p.stock)?.setScale(2, RoundingMode.HALF_UP)

    // ------------------------------------------------------------------ bills with goods

    private val buyWords = Regex("""(?i)(?<![\p{L}])(?:vaanginen|vaangunen|vanginen|vangunen|vaangitten|vaangiten|vaangi|purchase\s*(?:panninen|pannen|pannitten|panniten)?|bought|eduthen|வாங்கினேன்|வாங்கிட்டேன்)(?![\p{L}])""")
    private val sellWords = Regex("""(?i)(?<![\p{L}])(?:credit\s+sale|sale|sold|vithen|vitthen|vithuten|kuduthen|kuduthuten|koduthen|koduthuten|கொடுத்தேன்|விற்றேன்)(?![\p{L}])""")
    private val creditWords = Regex("""(?i)(?<![\p{L}])(?:credit|kadan|kadanaa|udhaar|udhari|baaki|bakki|later|apram\s+(?:tharen|tharuven|kudukkaren|tharuvaanga))(?![\p{L}])|கடன்""")
    private val cashWords = Regex("""(?i)(?<![\p{L}])(?:cash|rokkam|kaasu|panam\s+kuduthutaanga|paid|upi|gpay|g\s*pay|phonepe|paytm|bank|card)(?![\p{L}])""")
    private val freeWords = Regex("""(?i)(?<![\p{L}])(?:free|freeya|freeyaa|summa|ilavasam|sample)(?![\p{L}])|இலவசம்""")
    private val moneyInText = Regex("""(?i)(?:₹|rs\.?)\s*(\d[\d,]*(?:\.\d+)?)|(?<![\p{L}\d])(\d[\d,]*(?:\.\d+)?)\s*(?:rs|rupees?|rupa|ரூபாய்)(?![\p{L}])""")

    fun buys(text: String) = buyWords.containsMatchIn(text)
    fun sells(text: String) = sellWords.containsMatchIn(text)
    /** true = on credit, false = paid now (cash / UPI …), null = not said. */
    fun creditOf(text: String): Boolean? = when {
        creditWords.containsMatchIn(text) -> true
        cashWords.containsMatchIn(text) -> false
        else -> null
    }
    fun isFree(text: String) = freeWords.containsMatchIn(text)
    fun modeOf(text: String): com.shopai.app.books.model.PaymentMode {
        val t = text.lowercase(Locale.ROOT)
        return when {
            Regex("""upi|gpay|g\s*pay|phonepe|paytm""").containsMatchIn(t) -> com.shopai.app.books.model.PaymentMode.UPI
            Regex("""bank|neft|imps""").containsMatchIn(t) -> com.shopai.app.books.model.PaymentMode.BANK_TRANSFER
            Regex("""card""").containsMatchIn(t) -> com.shopai.app.books.model.PaymentMode.CARD
            else -> com.shopai.app.books.model.PaymentMode.CASH
        }
    }
    /** "₹6,000" / "6000 rs" said with the goods: the bill's total (not a quantity). */
    fun moneyIn(text: String): BigDecimal? = moneyInText.find(text)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(",", "").toBigDecimalOrNull() }
    /** The words without the money, so "₹6,000" is never read as 6000 pieces. */
    fun withoutMoney(text: String): String = moneyInText.replace(text, " ")
}
