package com.shopai.app.books.model

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/**
 * The Unit Master (owner, 20 Oct 2026: "Unit field-ல் fixed 5–10 units மட்டும் இருக்கக் கூடாது"): every unit a shop of any kind
 * sells in, searchable by its code, English name, Tamil name or a short form, with the units of the shop's kind first. A unit
 * that is not here is the shop's own custom unit — typed once, kept by the books ([com.shopai.app.books.engine.BooksMasters.ensureUnit]).
 *
 * Conversions here are only the ones true for every product (1 metre = 1,000 mm, 1 quintal = 100 kg, 1 dozen = 12 pieces).
 * A carton, box, strip, coil or ream is never given a size here — that is set per product.
 */
object UnitMaster {
    /** What a unit measures; units convert by a fixed factor only within one of these. */
    enum class Measure { COUNT, LENGTH, WEIGHT, VOLUME, AREA, SOLID_VOLUME, PACK, DOSE }

    /**
     * One unit. [perBase] = how many of the measure's base unit one of this is (METER for length, KG for weight, LITRE for
     * volume, SQFT for area, CFT for solid volume, PCS for count) — null when its size is not fixed (a box, a bag, a strip).
     */
    data class UnitDef(
        val code: String,
        val en: String,
        val ta: String,
        val measure: Measure,
        val perBase: BigDecimal? = null,
        val aliases: List<String> = emptyList(),
    )

    /** The kinds of business the unit and category suggestions follow (the shop's category picks one by default). */
    enum class Kind {
        HARDWARE, GROCERY, TEXTILE, PHARMACY, AGRICULTURE, AUTOMOBILE, FRESH,
        STATIONERY, PAINTS, CHEMICALS, COSMETICS, FOOTWEAR, FURNITURE, PET, BEVERAGES, MOBILE, RESTAURANT, OTHER,
    }

    private fun u(code: String, en: String, ta: String, m: Measure, perBase: String? = null, vararg aliases: String) =
        UnitDef(code, en, ta, m, perBase?.let(::BigDecimal), aliases.toList())

    /** Every unit, once. Codes already used by the books and Kai (PCS, PACK, METER, FEET, CFT, SQFT…) are kept as they are. */
    val all: List<UnitDef> = listOf(
        // count
        u("PCS", "Piece", "பீஸ்", Measure.COUNT, "1", "piece", "pieces", "nos", "no", "pc"),
        u("DOZEN", "Dozen", "டஜன்", Measure.COUNT, "12", "doz", "dz"),
        u("PAIR", "Pair", "ஜோடி", Measure.COUNT, null, "pairs"),
        u("SET", "Set", "செட்", Measure.COUNT, null, "sets"),
        u("UNIT", "Unit", "யூனிட்", Measure.COUNT, null, "units"),
        u("LENGTH", "Length", "லெங்த்", Measure.COUNT, null, "lengths"),
        u("BAR", "Bar", "பார்", Measure.COUNT, null, "bars"),
        u("PIPE", "Pipe", "பைப்", Measure.COUNT, null, "pipes"),
        u("SHEET", "Sheet", "ஷீட்", Measure.COUNT, null, "sheets"),
        u("BUNCH", "Bunch", "கொத்து", Measure.COUNT, null, "bunches", "kothu"),
        // length (base: metre)
        u("MM", "Millimetre", "மில்லிமீட்டர்", Measure.LENGTH, "0.001", "millimeter", "millimetre"),
        u("CM", "Centimetre", "சென்டிமீட்டர்", Measure.LENGTH, "0.01", "centimeter", "centimetre"),
        u("METER", "Metre", "மீட்டர்", Measure.LENGTH, "1", "metre", "meter", "mtr", "m"),
        u("KM", "Kilometre", "கிலோமீட்டர்", Measure.LENGTH, "1000", "kilometer", "kilometre"),
        u("INCH", "Inch", "இன்ச்", Measure.LENGTH, "0.0254", "inches", "in"),
        u("FEET", "Foot", "அடி", Measure.LENGTH, "0.3048", "foot", "ft", "adi"),
        u("YARD", "Yard", "கஜம்", Measure.LENGTH, "0.9144", "yards", "yd", "gajam"),
        // weight (base: kg)
        u("MCG", "Microgram", "மைக்ரோகிராம்", Measure.WEIGHT, "0.000000001", "microgram", "µg"),
        u("MG", "Milligram", "மில்லிகிராம்", Measure.WEIGHT, "0.000001", "milligram"),
        u("GRAM", "Gram", "கிராம்", Measure.WEIGHT, "0.001", "g", "gm", "gms", "grams"),
        u("KG", "Kilogram", "கிலோ", Measure.WEIGHT, "1", "kilo", "kgs", "kilogram"),
        u("QUINTAL", "Quintal", "குவிண்டால்", Measure.WEIGHT, "100", "qtl"),
        u("TONNE", "Tonne", "டன்", Measure.WEIGHT, "1000", "ton", "tons", "tonnes"),
        // volume (base: litre)
        u("ML", "Millilitre", "மில்லி", Measure.VOLUME, "0.001", "milliliter", "millilitre", "mili"),
        u("LITRE", "Litre", "லிட்டர்", Measure.VOLUME, "1", "liter", "ltr", "l", "lit"),
        // area (base: sq.ft)
        u("SQFT", "Square foot", "சதுர அடி", Measure.AREA, "1", "sq.ft", "sq ft", "sft"),
        u("SQM", "Square metre", "சதுர மீட்டர்", Measure.AREA, "10.7639104", "sq.m", "sq m", "sqmt"),
        u("ACRE", "Acre", "ஏக்கர்", Measure.AREA, "43560", "acres"),
        u("HECTARE", "Hectare", "ஹெக்டேர்", Measure.AREA, "107639.104", "hectares", "ha"),
        // solid volume (base: cu.ft)
        u("CFT", "Cubic foot", "கன அடி", Measure.SOLID_VOLUME, "1", "cu.ft", "cuft", "cubic feet"),
        u("CUM", "Cubic metre", "கன மீட்டர்", Measure.SOLID_VOLUME, "35.3146667", "cu.m", "cubic metre", "cubic meter"),
        // packs (the size is the product's)
        u("BOX", "Box", "பாக்ஸ்", Measure.PACK, null, "boxes", "bx", "petti"),
        u("PACK", "Packet", "பாக்கெட்", Measure.PACK, null, "packet", "packets", "pkt", "packs"),
        u("CARTON", "Carton", "கார்ட்டன்", Measure.PACK, null, "cartons", "ctn"),
        u("CASE", "Case", "கேஸ்", Measure.PACK, null, "cases"),
        u("BAG", "Bag", "பேக்", Measure.PACK, null, "bags", "bg", "moota", "mootai", "sack"),
        u("BOTTLE", "Bottle", "பாட்டில்", Measure.PACK, null, "bottles", "btl"),
        u("CAN", "Can", "கேன்", Measure.PACK, null, "cans"),
        u("JAR", "Jar", "ஜார்", Measure.PACK, null, "jars"),
        u("POUCH", "Pouch", "பவுச்", Measure.PACK, null, "pouches"),
        u("SACHET", "Sachet", "சாஷே", Measure.PACK, null, "sachets"),
        u("TIN", "Tin", "டின்", Measure.PACK, null, "tins", "dabba"),
        u("TRAY", "Tray", "ட்ரே", Measure.PACK, null, "trays"),
        u("BUNDLE", "Bundle", "கட்டு", Measure.PACK, null, "bundles", "kattu"),
        u("ROLL", "Roll", "ரோல்", Measure.PACK, null, "rolls"),
        u("COIL", "Coil", "காயில்", Measure.PACK, null, "coils"),
        u("CONE", "Cone", "கோன்", Measure.PACK, null, "cones"),
        u("SPOOL", "Spool", "ஸ்பூல்", Measure.PACK, null, "spools"),
        u("REEL", "Reel", "ரீல்", Measure.PACK, null, "reels"),
        u("BALE", "Bale", "பேல்", Measure.PACK, null, "bales"),
        u("CRATE", "Crate", "கிரேட்", Measure.PACK, null, "crates"),
        u("BASKET", "Basket", "கூடை", Measure.PACK, null, "baskets", "koodai"),
        u("DRUM", "Drum", "டிரம்", Measure.PACK, null, "drums"),
        u("BUCKET", "Bucket", "பக்கெட்", Measure.PACK, null, "buckets"),
        u("REAM", "Ream", "ரீம்", Measure.PACK, null, "reams"),
        u("PAD", "Pad", "பேட்", Measure.PACK, null, "pads"),
        u("LOAD", "Load", "லோடு", Measure.PACK, null, "loads"),
        // medicine
        u("TABLET", "Tablet", "மாத்திரை", Measure.DOSE, null, "tablets", "tab", "tabs"),
        u("CAPSULE", "Capsule", "கேப்ஸ்யூல்", Measure.DOSE, null, "capsules", "cap", "caps"),
        u("STRIP", "Strip", "ஸ்ட்ரிப்", Measure.PACK, null, "strips"),
        u("VIAL", "Vial", "வயல்", Measure.PACK, null, "vials"),
        u("AMPOULE", "Ampoule", "ஆம்பியூல்", Measure.PACK, null, "ampoules", "amp"),
        u("TUBE", "Tube", "ட்யூப்", Measure.PACK, null, "tubes"),
        u("DOSE", "Dose", "டோஸ்", Measure.DOSE, null, "doses"),
        u("KIT", "Kit", "கிட்", Measure.PACK, null, "kits"),
    )

    private val byCode: Map<String, UnitDef> = all.associateBy { it.code }

    /** The units a kind of business sells in, in the order the owner listed them (20 Oct 2026). */
    private val byKind: Map<Kind, List<String>> = mapOf(
        Kind.HARDWARE to listOf("MM", "CM", "METER", "KM", "INCH", "FEET", "YARD", "SQFT", "SQM", "CFT", "CUM", "KG", "GRAM", "TONNE",
            "LITRE", "ML", "PCS", "LENGTH", "ROLL", "COIL", "BUNDLE", "BAR", "SHEET", "PIPE", "BOX", "BAG", "SET", "PAIR"),
        Kind.GROCERY to listOf("PCS", "PACK", "BOX", "CARTON", "CASE", "BOTTLE", "CAN", "JAR", "POUCH", "SACHET", "TIN", "BAG", "KG", "GRAM",
            "QUINTAL", "TONNE", "LITRE", "ML", "DOZEN", "TRAY", "BUNDLE"),
        Kind.TEXTILE to listOf("PCS", "PAIR", "SET", "DOZEN", "METER", "CM", "YARD", "ROLL", "BUNDLE", "PACK", "KG", "CONE", "SPOOL", "REEL", "BALE"),
        Kind.PHARMACY to listOf("TABLET", "CAPSULE", "STRIP", "BOX", "BOTTLE", "VIAL", "AMPOULE", "SACHET", "TUBE", "PACK", "PCS", "PAIR", "ML",
            "LITRE", "GRAM", "MG", "MCG", "DOSE", "KIT"),
        Kind.AGRICULTURE to listOf("KG", "GRAM", "QUINTAL", "TONNE", "BAG", "PACK", "SACHET", "LITRE", "ML", "BOTTLE", "CAN", "PCS", "BUNDLE",
            "ACRE", "HECTARE"),
        Kind.AUTOMOBILE to listOf("PCS", "SET", "PAIR", "KIT", "UNIT", "BOX", "PACK", "MM", "CM", "INCH", "METER", "KG", "GRAM", "LITRE", "CAN",
            "DRUM", "ROLL"),
        Kind.FRESH to listOf("KG", "GRAM", "QUINTAL", "TONNE", "PCS", "DOZEN", "BUNCH", "BUNDLE", "BOX", "CRATE", "BASKET", "TRAY", "PACK",
            "LITRE", "ML"),
        Kind.STATIONERY to listOf("PCS", "PACK", "BOX", "DOZEN", "SET", "REAM", "SHEET", "PAD", "ROLL", "BUNDLE", "CARTON"),
        Kind.PAINTS to listOf("LITRE", "ML", "KG", "GRAM", "TIN", "BUCKET", "CAN", "DRUM", "BOX", "PCS", "SET"),
        Kind.CHEMICALS to listOf("KG", "GRAM", "TONNE", "LITRE", "ML", "DRUM", "CAN", "BAG", "BOTTLE", "JAR", "CARTON"),
        Kind.COSMETICS to listOf("PCS", "BOTTLE", "TUBE", "JAR", "PACK", "BOX", "SET", "SACHET", "ML", "GRAM", "DOZEN"),
        Kind.FOOTWEAR to listOf("PAIR", "PCS", "BOX", "SET", "DOZEN", "CARTON"),
        Kind.FURNITURE to listOf("PCS", "SET", "UNIT", "SQFT", "SHEET", "FEET", "METER"),
        Kind.PET to listOf("KG", "GRAM", "PACK", "CAN", "POUCH", "PCS", "BAG", "BOTTLE"),
        Kind.BEVERAGES to listOf("BOTTLE", "CAN", "CASE", "CRATE", "CARTON", "LITRE", "ML", "PACK", "PCS"),
        Kind.MOBILE to listOf("PCS", "SET", "PAIR", "BOX", "PACK", "UNIT", "METER"),
        Kind.RESTAURANT to listOf("PCS", "KG", "GRAM", "LITRE", "ML", "PACK", "BOX", "TRAY", "DOZEN"),
        Kind.OTHER to listOf("PCS", "KG", "GRAM", "LITRE", "ML", "METER", "BOX", "PACK", "BAG", "BOTTLE", "DOZEN", "PAIR", "SET"),
    )

    /** The shop's category (as the app stores it, e.g. "HARDWARE", "Fruits & Vegetables") → its kind; unknown → OTHER. */
    fun kindOf(category: String?): Kind {
        val c = category?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (c.isEmpty()) return Kind.OTHER
        Kind.entries.firstOrNull { it.name.equals(c, ignoreCase = true) }?.let { return it }
        return when {
            Regex("hardware|electrical|building|cement|steel|plumb|sanitary|tiles").containsMatchIn(c) -> Kind.HARDWARE
            Regex("vegetable|fruit|meat|fish|chicken|mutton|egg|flower").containsMatchIn(c) -> Kind.FRESH
            Regex("grocery|kirana|supermarket|fmcg|provision|departmental").containsMatchIn(c) -> Kind.GROCERY
            Regex("textile|garment|tailor|cloth|fabric|saree|readymade").containsMatchIn(c) -> Kind.TEXTILE
            Regex("pharma|medical|medicine|chemist|drug|clinic").containsMatchIn(c) -> Kind.PHARMACY
            Regex("agri|seed|fertili|pesticide|farm").containsMatchIn(c) -> Kind.AGRICULTURE
            Regex("auto|spare|machinery|motor|tyre|vehicle|garage").containsMatchIn(c) -> Kind.AUTOMOBILE
            Regex("stationery|book|paper|xerox").containsMatchIn(c) -> Kind.STATIONERY
            Regex("paint").containsMatchIn(c) -> Kind.PAINTS
            Regex("chemical").containsMatchIn(c) -> Kind.CHEMICALS
            Regex("cosmetic|beauty|salon|fancy").containsMatchIn(c) -> Kind.COSMETICS
            Regex("footwear|shoe|chappal|slipper").containsMatchIn(c) -> Kind.FOOTWEAR
            Regex("furniture|wood|plywood").containsMatchIn(c) -> Kind.FURNITURE
            Regex("""\bpet""").containsMatchIn(c) -> Kind.PET
            Regex("beverage|drink|juice|water|soda").containsMatchIn(c) -> Kind.BEVERAGES
            Regex("mobile|phone|electronic|computer").containsMatchIn(c) -> Kind.MOBILE
            Regex("""restaurant|hotel|food|bakery|sweet|\btea\b|cafe""").containsMatchIn(c) -> Kind.RESTAURANT
            else -> Kind.OTHER
        }
    }

    fun unit(code: String?): UnitDef? = code?.let { byCode[canon(it)] }

    /** One code for a unit however it is typed ("kgs", "Kilo", "கிலோ" → KG); an unknown unit stays as typed (upper case) — a custom unit. */
    fun canon(typed: String): String {
        val t = typed.trim()
        if (t.isEmpty()) return t
        val low = t.lowercase(Locale.ROOT)
        return all.firstOrNull { d -> d.code.equals(t, true) || d.en.equals(t, true) || d.ta == t || d.aliases.any { it.equals(low, true) } }?.code
            ?: t.uppercase(Locale.ROOT)
    }

    /**
     * The code a unit typed by the owner is kept under: a known unit's own code ("கிலோ" → KG), else the letters and digits
     * the books keep for a custom unit ("Gunny" → GUNNY, 12 at most). Null when nothing of it can be kept (only Tamil letters
     * of a unit not in the master, or a leading digit) — the owner is asked to type it in English letters.
     */
    fun customCode(typed: String): String? {
        val c = canon(typed).uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "").take(12)
        return c.takeIf { it.isNotEmpty() && it[0].isLetter() }
    }

    /**
     * The units of a product that need a conversion to its stock unit: the purchase unit, the sales unit and any other unit
     * it has, each once and never the stock unit itself (1 CARTON = ? PCS, 1 PACK = ? PCS).
     */
    fun unitsNeedingConversion(primary: String, purchase: String?, sales: String?, others: Collection<String>): List<String> =
        (listOfNotNull(purchase, sales) + others).map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && !it.equals(primary.trim(), ignoreCase = true) }.distinct()

    /** The units suggested for a kind of business, in order. */
    fun suggestions(kind: Kind): List<UnitDef> = byKind.getValue(kind).mapNotNull(byCode::get)

    /**
     * Units matching what is typed (code, English, Tamil or short form; starts-with first), the kind's own units before the rest.
     * [custom] = the shop's own unit codes, searched too. Empty query → the kind's suggestions, then the shop's custom units.
     */
    fun search(query: String, kind: Kind, custom: List<String> = emptyList()): List<String> {
        val q = query.trim().lowercase(Locale.ROOT)
        val mine = byKind.getValue(kind)
        val customOnly = custom.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() && it !in byCode }.distinct()
        if (q.isEmpty()) return (mine + customOnly).distinct()
        fun score(d: UnitDef): Int? {
            val names = listOf(d.code.lowercase(Locale.ROOT), d.en.lowercase(Locale.ROOT), d.ta) + d.aliases.map { it.lowercase(Locale.ROOT) }
            return when {
                names.any { it == q } -> 0
                names.any { it.startsWith(q) } -> 1
                names.any { it.contains(q) } -> 2
                else -> null
            }
        }
        val known = all.mapNotNull { d -> score(d)?.let { s -> Triple(d.code, s, if (d.code in mine) 0 else 1) } }
            .sortedWith(compareBy({ it.second }, { it.third }, { mine.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i } }))
            .map { it.first }
        val own = customOnly.filter { it.lowercase(Locale.ROOT).contains(q) }
        return (known + own).distinct()
    }

    /** "KG · கிலோ" for a chip; a custom unit shows as typed. */
    fun label(code: String, tamil: Boolean): String = unit(code)?.let { "${it.code} · ${if (tamil) it.ta else it.en}" } ?: code

    /**
     * How many [to] are in one [from], when that is true for every product (same measure, both of fixed size): METER → MM = 1000,
     * QUINTAL → KG = 100, DOZEN → PCS = 12. Null otherwise — a box, bag, strip or coil is set per product, never assumed.
     */
    fun fixedFactor(from: String, to: String): BigDecimal? {
        val a = unit(from) ?: return null
        val b = unit(to) ?: return null
        if (a.code == b.code) return BigDecimal.ONE
        if (a.measure != b.measure) return null
        val pa = a.perBase ?: return null
        val pb = b.perBase ?: return null
        return pa.divide(pb, MathContext(12)).stripTrailingZeros()
    }
}
