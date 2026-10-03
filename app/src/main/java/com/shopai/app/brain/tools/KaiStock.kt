package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.util.Locale

/** A product of the business (for stock in / out). */
data class ProductRef(val id: String, val name: String, val unit: String, val stock: BigDecimal)

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
        "ஸ்டாக் உள்ளே", "சேர்", "வந்திருக்கு", "புது ஸ்டாக்",
    )
    /** Stock going out: "Colgate 2 out pannu", "2 Colgate pochu", "rendu sale aachu", "2 pieces sold", "eduthutanga". */
    private val outWords = listOf(
        "stock out", "stock remove", "remove pannu", "remove panniten", "sale panniten", "sale pannen", "sold", "out pannu", "out panniten",
        "out aachu", "out aagiduchu", "poiduchu", "poiduchchu", "pochu", "poachu", "pochi", "pochchu", "sale aachu", "sale achu", "sale aagiduchu",
        "vithuduchu", "vithachu", "vithuten", "vitthuten", "eduthutanga", "eduthuttanga", "eduthaanga", "eduthanga", "eduthuttaanga", "outward",
        "ஸ்டாக் வெளியே", "விற்றுவிட்டேன்", "போயிடுச்சு", "போச்சு",
    )
    private val units = mapOf(
        "pcs" to "PCS", "pc" to "PCS", "piece" to "PCS", "pieces" to "PCS", "peice" to "PCS", "peices" to "PCS", "nos" to "PCS",
        "kg" to "KG", "kgs" to "KG", "kilo" to "KG", "bag" to "BAG", "bags" to "BAG", "mootai" to "BAG", "moottai" to "BAG",
        "box" to "BOX", "boxes" to "BOX", "packet" to "PACK", "packets" to "PACK", "pack" to "PACK", "packs" to "PACK",
        "litre" to "LITRE", "litres" to "LITRE", "liter" to "LITRE", "ltr" to "LITRE", "dozen" to "DOZEN", "bottle" to "BOTTLE", "bottles" to "BOTTLE",
    )
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
        "kadaiku", "kadaila", "shop", "irukku", "iruku", "vandhu", "vanthu", "mattum", "ellam", "items",
    )
    /** Words that make it money or a question, not a stock change ("cash 500 pochu", "sale evlo aachu?"). */
    private val notStock = Regex(
        """(?i)(?<![\p{L}])(cash|panam|kaasu|rupees?|rs|money|amount|balance|salary|rent|upi|gpay|bank|payment|evlo|evvalavu|enna|how|what|which|why|""" +
            """eppo|remind|reminder|bill|loss|profit|low|reorder)(?![\p{L}])|₹|\?""",
    )
    private val stockContext = Regex("""(?i)(?<![\p{L}])(stock|inventory|iruppu|product)(?![\p{L}])""")

    fun understand(text: String, products: List<ProductRef>): StockRequest? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!,;.]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        fun has(list: List<String>) = list.any { w -> Regex("""(?<![\p{L}])${Regex.escape(w)}(?![\p{L}])""").containsMatchIn(lower) }
        val isIn = has(inWords)
        val isOut = has(outWords)
        if (isIn == isOut) return null
        if (notStock.containsMatchIn(lower)) return null
        // The product: the longest real product name in the words (names may contain numbers: "Colgate 200g").
        val product = products.filter { it.name.isNotBlank() }
            .sortedByDescending { it.name.length }
            .firstOrNull { p -> Regex("""(?<![\p{L}\p{N}])${Regex.escape(p.name.lowercase(Locale.ROOT))}(?![\p{L}])""").containsMatchIn(lower) }
        val rest = product?.let { lower.replace(it.name.lowercase(Locale.ROOT), " ") } ?: lower
        val words = rest.split(' ').filter { it.isNotEmpty() }
        val (qty, saidUnit) = quantityIn(rest) ?: (null to words.firstNotNullOfOrNull { units[it] })
        val spoken = product?.name ?: words.filter { w ->
            w !in fillers && w !in units && w !in numberWords && w.none(Char::isDigit) &&
                (inWords + outWords).none { k -> k.split(' ').contains(w) }
        }.joinToString(" ").trim().trim('-')
        if (spoken.isEmpty()) return null
        if (product == null) {
            // A name Kai doesn't have: only when it clearly is stock ("Pepsodent stock vandhiruku", "2 Pepsodent box pochu").
            if (!stockContext.containsMatchIn(lower) && saidUnit == null && qty == null) return null
            if (spoken.split(' ').size > 4) return null
            // A big number with no unit is money, not pieces.
            if (qty != null && saidUnit == null && qty > BigDecimal(999) && !stockContext.containsMatchIn(lower)) return null
        }
        return StockRequest(incoming = isIn, product = product, spokenName = displayName(spoken), qty = qty, unit = saidUnit ?: product?.unit)
    }

    /** A number said in words or digits ("12", "rendu", "12 pieces"), with its unit if said. */
    fun quantityIn(text: String): Pair<BigDecimal, String?>? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!,;]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        val words = lower.split(' ').filter { it.isNotEmpty() }
        val digits = Regex("""(?<![\p{L}\d.])(\d+(?:\.\d+)?)(?![\d])""").find(lower)?.groupValues?.get(1)?.toBigDecimalOrNull()
        val qty = (digits ?: words.firstNotNullOfOrNull { numberWords[it] }?.toBigDecimal())?.takeIf { it.signum() > 0 } ?: return null
        return qty to words.firstNotNullOfOrNull { units[it] }
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
        else -> unit.lowercase(Locale.ROOT)
    }

    private fun displayName(s: String) = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}
