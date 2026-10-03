package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.util.Locale

/** A product of the business (for stock in / out). */
data class ProductRef(
    val id: String,
    val name: String,
    /** The unit stock is kept in (the product's primary / base unit). */
    val unit: String,
    val stock: BigDecimal,
    /** This product's own conversions: unit → base units in 1 (e.g. BOX → 12 for 1 box = 12 pieces). */
    val conversions: Map<String, BigDecimal> = emptyMap(),
)

/**
 * "Colgate 20 stock in pannu" → what to change. [product] null = the name was
 * not found in the products (Kai offers to create it); [qty] null = no
 * quantity was said (Kai asks, or the camera / form fills it).
 */
data class StockRequest(
    val incoming: Boolean,
    val product: ProductRef?,
    val spokenName: String,
    val qty: BigDecimal?,
    val unit: String?,
    /** Every quantity said, in order ("1 box 3 pieces" → [1 BOX, 3 PCS]); the stock flow converts them. */
    val parts: List<QtyPart> = emptyList(),
)

/**
 * Global stock in / out understanding (Tamil, Tanglish, English) — part of
 * the Kai core, the same for every owner. A shop's own words ("pottudu")
 * reach it only after the owner's private memory turned them into these
 * standard words. Nothing is written here: the agent makes a draft and the
 * owner confirms before the inventory engine is called.
 */
object KaiStock {
    /** Stock coming in: "add stock", "new stock", "stock vandhiruku", "pudhu stock", "pudhusa vandhuruku", "inventory-ku podu". */
    private val inWords = listOf(
        "stock in", "stock add", "add stock", "add pannu", "add panniten", "add pannunga", "add panni", "add", "serthu", "sethu", "serthudu",
        "vandhirukku", "vanthirukku", "vandhiruku", "vanthiruku", "vandhurukku", "vandhuruku", "vanthuruku", "vandhuchu", "vanthuchu",
        "new stock", "pudhu stock", "puthu stock", "pudhusa", "puthusa", "pudhusaa", "fresh stock", "stock vandhachu", "stock vanthachu",
        "inventory ku podu", "inventory-ku podu", "inventoryku podu", "stock la podu", "stock-la podu", "stockla podu", "inventory la podu",
        "purchase panninen", "purchase pannen", "received", "in pannu", "inward", "restock", "restocked",
        "vandhuduchu", "vanthuduchu", "vandhachu", "vanthachu", "vandhurukku", "vandhudhu", "vanthudhu", "vanthuthu", "vandhadhu", "vanthathu",
        "ஸ்டாக் உள்ளே", "சேர்", "வந்திருக்கு", "புது ஸ்டாக்",
    )
    /** Stock going out: "Colgate 2 out pannu", "2 Colgate pochu", "rendu sale aachu", "2 pieces sold", "eduthutanga". */
    private val outWords = listOf(
        "stock out", "stock remove", "remove pannu", "remove panniten", "sale panniten", "sale pannen", "sold", "out pannu", "out panniten",
        "out aachu", "out aagiduchu", "poiduchu", "poiduchchu", "pochu", "poachu", "pochi", "pochchu", "sale aachu", "sale achu", "sale aagiduchu",
        "vithuduchu", "vithachu", "vithuten", "vitthuten", "eduthutanga", "eduthuttanga", "eduthaanga", "eduthanga", "eduthuttaanga", "outward",
        "sell panniten", "sell pannen", "sell aachu", "sell achu", "sell panni", "sold out", "out",
        "ஸ்டாக் வெளியே", "விற்றுவிட்டேன்", "போயிடுச்சு", "போச்சு",
    )
    /** "2 pieces Colgate kuduthuten": stock out only when a known product is named (otherwise it is money). */
    private val gaveWords = listOf("kuduthen", "kuduthuten", "kuduthutten", "koduthen", "koduthuten", "kuduthachu", "kuduthaachu", "gave", "given")
    /** "New Colgate stock", "Colgate pudhu stock": new / fresh with a stock word is stock coming in. */
    private val newWords = Regex("""(?i)(?<![\p{L}])(new|pudhu|puthu|pudhusa|puthusa|fresh)(?![\p{L}])""")
    private val photoWords = Regex("""(?i)(?<![\p{L}])(photo|foto|camera|cam|pic|picture|scan|click)(?![\p{L}])|போட்டோ|கேமரா""")
    private val billWords = Regex("""(?i)(?<![\p{L}])(bill|bills|invoice|receipt)(?![\p{L}])|பில்""")

    private val units = mapOf(
        "pcs" to "PCS", "pc" to "PCS", "piece" to "PCS", "pieces" to "PCS", "peice" to "PCS", "peices" to "PCS", "nos" to "PCS",
        "kg" to "KG", "kgs" to "KG", "kilo" to "KG", "bag" to "BAG", "bags" to "BAG", "mootai" to "BAG", "moottai" to "BAG",
        "box" to "BOX", "boxes" to "BOX", "packet" to "PACK", "packets" to "PACK", "pack" to "PACK", "packs" to "PACK",
        "litre" to "LITRE", "litres" to "LITRE", "liter" to "LITRE", "ltr" to "LITRE", "dozen" to "DOZEN", "bottle" to "BOTTLE", "bottles" to "BOTTLE",
        "carton" to "CARTON", "cartons" to "CARTON", "case" to "CASE", "cases" to "CASE", "bundle" to "BUNDLE", "bundles" to "BUNDLE",
        "strip" to "STRIP", "strips" to "STRIP",
        "gram" to "GRAM", "grams" to "GRAM", "gm" to "GRAM", "gms" to "GRAM", "g" to "GRAM", "ml" to "ML", "dozens" to "DOZEN",
    )

    /** The unit words Kai knows ("box", "packet", …) — a shop's own word for one is taught as a unit. */
    fun unitOf(word: String): String? = units[word.trim().lowercase(Locale.ROOT)]

    /** "2 mani pochu", "10 minutes la": a time, never a stock quantity. */
    private val timeAfterNumber = Regex("""(?i)(?:\d+|oru|rendu|moonu|naalu|anju|pathu)\s*(?:mani|manikku|maniku|nimisham|nimishathula|minutes?|mins?|hours?|neram|o'?clock)(?![\p{L}])""")
    private val numberWords = mapOf(
        "oru" to 1, "onnu" to 1, "one" to 1, "rendu" to 2, "irandu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4,
        "anju" to 5, "aindhu" to 5, "five" to 5, "aaru" to 6, "six" to 6, "ezhu" to 7, "seven" to 7, "ettu" to 8, "eight" to 8,
        "onbadhu" to 9, "nine" to 9, "pathu" to 10, "padhu" to 10, "ten" to 10, "pannendu" to 12, "twelve" to 12,
        "irupathu" to 20, "twenty" to 20, "aimbadhu" to 50, "fifty" to 50, "nooru" to 100, "hundred" to 100,
    )
    private val fillers = setOf(
        "stock", "in", "out", "add", "remove", "pannu", "panniten", "pannunga", "pannen", "panninen", "panni", "purchase", "sale", "sold", "received",
        "serthu", "sethu", "serthudu", "inward", "outward", "owner", "kai", "please", "pls", "ah", "aa", "la", "ku", "kku", "na", "new", "pudhu",
        "puthu", "pudhusa", "puthusa", "pudhusaa", "fresh", "inventory", "inventoryku", "inventory-ku", "podu", "stockla", "stock-la", "aachu", "achu",
        "aagiduchu", "vandhachu", "vanthachu", "restock", "restocked", "the", "my", "of", "some", "konjam", "indha", "intha", "innaiku", "inniku", "today",
        "kadaiku", "kadaila", "shop", "irukku", "iruku", "vandhu", "vanthu", "mattum", "ellam", "items", "photo", "edu", "edunga", "camera", "open",
        "sell", "kuduthen", "kuduthuten", "kuduthutten", "koduthen", "koduthuten", "gave", "given", "vandhuduchu", "vanthuduchu",
    )
    /** Words that make it money or a question, not a stock change ("cash 500 pochu", "sale evlo aachu?"). */
    private val notStock = Regex(
        """(?i)(?<![\p{L}])(cash|panam|kaasu|rupees?|rs|money|amount|balance|salary|rent|upi|gpay|bank|payment|evlo|evvalavu|enna|how|what|which|why|""" +
            """eppo|remind|reminder|bill|loss|profit|low|reorder)(?![\p{L}])|₹|\?""",
    )
    private val stockContext = Regex("""(?i)(?<![\p{L}])(stock|inventory|iruppu|product)(?![\p{L}])""")

    private fun lowerOf(text: String) = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!,;.]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "

    private fun has(lower: String, list: List<String>) = list.any { w -> Regex("""(?<![\p{L}])${Regex.escape(w)}(?![\p{L}])""").containsMatchIn(lower) }

    /** The longest real product name in the words (names may contain numbers: "Colgate 200g"). */
    private fun productIn(lower: String, products: List<ProductRef>) = products.filter { it.name.isNotBlank() }
        .sortedByDescending { it.name.length }
        .firstOrNull { p -> Regex("""(?<![\p{L}\p{N}])${Regex.escape(p.name.lowercase(Locale.ROOT))}(?![\p{L}])""").containsMatchIn(lower) }

    /** The words left once stock words, numbers and units are taken out — the product's name as said. */
    private fun spokenName(rest: String): String = rest.split(' ').filter { it.isNotEmpty() }.filter { w ->
        w !in fillers && w !in units && w !in numberWords && w.none(Char::isDigit) &&
            (inWords + outWords).none { k -> k.split(' ').contains(w) }
    }.joinToString(" ").trim().trim('-')

    private fun direction(lower: String, product: ProductRef?): Boolean? {
        val isIn = has(lower, inWords) || (newWords.containsMatchIn(lower) && stockContext.containsMatchIn(lower))
        // "out of stock" is a question about stock, not stock going out.
        val outText = lower.replace(Regex("""(?<![\p{L}])out\s+of(?![\p{L}])"""), " ")
        // "kuduthen" is money unless a known product is named and nobody is given it ("Selvam-ku … kuduthen" is a payment).
        val toSomeone = Regex("""(?<![\p{L}])[\p{L}]+\s*-?\s*(?:ku|kku|kitta)(?![\p{L}])""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        val isOut = has(outText, outWords) || (product != null && !toSomeone && has(lower, gaveWords))
        return if (isIn == isOut) null else isIn
    }

    /**
     * SCAN_STOCK: "Colgate photo edu", "stock photo edu", "new stock add pannu" (no name) → the product
     * camera, with the product's name when one was said ("" when not). Null: not a stock photo request.
     */
    fun scanRequest(text: String, products: List<ProductRef>): String? {
        val lower = lowerOf(text)
        if (billWords.containsMatchIn(lower) || notStock.containsMatchIn(lower)) return null
        val product = productIn(lower, products)
        if (photoWords.containsMatchIn(lower)) {
            if (product != null) return product.name
            if (stockContext.containsMatchIn(lower) || newWords.containsMatchIn(lower)) return spokenName(lower).takeIf { it.split(' ').size <= 3 }.orEmpty().let(::displayName)
            return null
        }
        // A stock-in sentence with no product at all ("new stock add pannu", "pudhu stock vandhiruku").
        if (product == null && direction(lower, null) == true && stockContext.containsMatchIn(lower) && spokenName(lower).isEmpty() && quantityIn(lower) == null) return ""
        return null
    }

    fun understand(text: String, products: List<ProductRef>): StockRequest? {
        val lower = lowerOf(text)
        if (notStock.containsMatchIn(lower) || timeAfterNumber.containsMatchIn(lower)) return null
        val product = productIn(lower, products)
        val isIn = direction(lower, product) ?: return null
        val rest = product?.let { lower.replace(it.name.lowercase(Locale.ROOT), " ") } ?: lower
        val words = rest.split(' ').filter { it.isNotEmpty() }
        val (qty, saidUnit) = quantityIn(rest) ?: (null to words.firstNotNullOfOrNull { units[it] })
        val spoken = product?.name ?: spokenName(rest)
        if (spoken.isEmpty()) return null
        if (product == null) {
            // A name Kai doesn't have: only when it clearly is stock ("Pepsodent stock vandhiruku", "2 Pepsodent box pochu").
            if (!stockContext.containsMatchIn(lower) && saidUnit == null && qty == null) return null
            if (spoken.split(' ').size > 4) return null
            // A big number with no unit is money, not pieces.
            if (qty != null && saidUnit == null && qty > BigDecimal(999) && !stockContext.containsMatchIn(lower)) return null
        }
        return StockRequest(incoming = isIn, product = product, spokenName = displayName(spoken), qty = qty, unit = saidUnit ?: product?.unit, parts = partsIn(rest))
    }

    /**
     * "Colgate 2 petti vandhiruku": a known product, a quantity, and right after
     * it a word Kai doesn't know as a unit or a stock word → that word (Kai asks
     * what it is — box? packet? — instead of guessing). Null when there is none.
     */
    fun unknownUnitWord(text: String, products: List<ProductRef>): String? {
        val lower = lowerOf(text)
        if (notStock.containsMatchIn(lower) || timeAfterNumber.containsMatchIn(lower)) return null
        val product = productIn(lower, products) ?: return null
        if (direction(lower, product) == null) return null
        val rest = lower.replace(product.name.lowercase(Locale.ROOT), " ")
        val m = Regex("""(?<![\p{L}\p{N}])(\d+(?:\.\d+)?|${numberWords.keys.joinToString("|")})\s+([\p{L}\p{M}]+)""").find(rest) ?: return null
        val word = m.groupValues[2]
        if (word in units || word in fillers || word in numberWords || word.length < 2) return null
        if ((inWords + outWords + gaveWords).any { k -> k.split(' ').contains(word) }) return null
        return word
    }

    /**
     * Every quantity in [text] with its unit: "1 box 3 pieces" → [1 BOX, 3 PCS], "2kg" → [2 KG],
     * "rendu box" → [2 BOX], "12" → [12 (product unit)]. Empty when there is no number.
     */
    fun partsIn(text: String): List<QtyPart> {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!,;+]"""), " ").replace(Regex("""(\d)([a-z]+)"""), "$1 $2").replace(Regex("""\s+"""), " ").trim() + " "
        val words = lower.split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<QtyPart>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val n = w.toBigDecimalOrNull()?.takeIf { w.first().isDigit() } ?: numberWords[w]?.toBigDecimal()
            if (n != null && n.signum() > 0) {
                val unit = words.getOrNull(i + 1)?.let { units[it] }
                out += QtyPart(n, unit)
                i += if (unit != null) 2 else 1
            } else {
                i++
            }
        }
        return out
    }

    /** A number said in words or digits ("12", "rendu", "12 pieces"), with its unit if said. */
    fun quantityIn(text: String): Pair<BigDecimal, String?>? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!,;]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        val words = lower.split(' ').filter { it.isNotEmpty() }
        val digits = Regex("""(?<![\p{L}\d.])(\d+(?:\.\d+)?)(?![\d])""").find(lower)?.groupValues?.get(1)?.toBigDecimalOrNull()
        val qty = (digits ?: words.firstNotNullOfOrNull { numberWords[it] }?.toBigDecimal())?.takeIf { it.signum() > 0 } ?: return null
        return qty to words.firstNotNullOfOrNull { units[it] }
    }

    /** "box" / "boxes", "piece" / "pieces" — the unit for [qty] (one or many). */
    fun unitWord(unit: String?, qty: BigDecimal): String {
        val one = qty.compareTo(BigDecimal.ONE) == 0
        return when (KaiUnits.canon(unit)) {
            null, "PCS" -> if (one) "piece" else "pieces"
            "BOX" -> if (one) "box" else "boxes"
            "PACK" -> if (one) "packet" else "packets"
            "BAG" -> if (one) "bag" else "bags"
            "BOTTLE" -> if (one) "bottle" else "bottles"
            "CARTON" -> if (one) "carton" else "cartons"
            "CASE" -> if (one) "case" else "cases"
            "BUNDLE" -> if (one) "bundle" else "bundles"
            "STRIP" -> if (one) "strip" else "strips"
            "GRAM" -> if (one) "gram" else "grams"
            "ML" -> "ml"
            else -> unitWord(unit)
        }
    }

    /** "pieces" / "kg" … as Kai says it to the owner. */
    fun unitWord(unit: String?): String = when (unit?.uppercase(Locale.ROOT)) {
        null, "", "PCS", "NOS" -> "pieces"
        "KG" -> "kg"
        "BAG" -> "bags"
        "BOX" -> "box"
        "PACK" -> "packets"
        "LITRE" -> "litre"
        "DOZEN" -> "dozen"
        "BOTTLE" -> "bottles"
        "CARTON" -> "cartons"
        "CASE" -> "cases"
        "BUNDLE" -> "bundles"
        "STRIP" -> "strips"
        "GRAM" -> "grams"
        "ML" -> "ml"
        else -> unit.lowercase(Locale.ROOT)
    }

    /** "12 pieces", "2 kg" — how Kai shows a quantity. */
    fun shown(q: BigDecimal, unit: String?): String {
        val n = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        return "$n ${unitWord(unit, q)}"
    }

    private fun displayName(s: String) = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}
