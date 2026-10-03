package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.util.Locale

/** A product of the business (for stock in / out). */
data class ProductRef(val id: String, val name: String, val unit: String, val stock: BigDecimal)

/** "Colgate 20 stock in pannu" → what to change; [product] null = the name was not found in the products. */
data class StockRequest(
    val incoming: Boolean,
    val product: ProductRef?,
    val spokenName: String,
    val qty: BigDecimal,
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
    private val inWords = listOf(
        "stock in", "stock add", "add pannu", "add panniten", "add pannunga", "serthu", "sethu", "vandhirukku", "vanthirukku",
        "purchase panninen", "purchase pannen", "received", "in pannu", "inward", "ஸ்டாக் உள்ளே", "சேர்",
    )
    private val outWords = listOf(
        "stock out", "stock remove", "remove pannu", "remove panniten", "sale panniten", "sale pannen", "sold", "out pannu",
        "poiduchu", "poiduchchu", "outward", "ஸ்டாக் வெளியே", "விற்றுவிட்டேன்", "போயிடுச்சு",
    )
    private val units = mapOf(
        "pcs" to "PCS", "pc" to "PCS", "piece" to "PCS", "pieces" to "PCS", "kg" to "KG", "kgs" to "KG", "kilo" to "KG", "bag" to "BAG", "bags" to "BAG",
        "box" to "BOX", "boxes" to "BOX", "packet" to "PACK", "packets" to "PACK", "pack" to "PACK", "litre" to "LITRE", "litres" to "LITRE", "ltr" to "LITRE",
        "dozen" to "DOZEN", "bottle" to "BOTTLE", "bottles" to "BOTTLE",
    )
    private val fillers = setOf(
        "stock", "in", "out", "add", "remove", "pannu", "panniten", "pannunga", "pannen", "panninen", "purchase", "sale", "sold", "received", "serthu",
        "sethu", "vandhirukku", "vanthirukku", "poiduchu", "poiduchchu", "inward", "outward", "owner", "kai", "please", "ah", "aa", "la", "ku", "kku",
    )

    fun understand(text: String, products: List<ProductRef>): StockRequest? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[?!,;]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        fun has(list: List<String>) = list.any { w -> Regex("""(?<![\p{L}])${Regex.escape(w)}(?![\p{L}])""").containsMatchIn(lower) }
        val isIn = has(inWords)
        val isOut = has(outWords)
        if (isIn == isOut) return null
        // The product: the longest real product name in the words (names may contain numbers: "Colgate 200g").
        val product = products.filter { it.name.isNotBlank() }
            .sortedByDescending { it.name.length }
            .firstOrNull { p -> Regex("""(?<![\p{L}\p{N}])${Regex.escape(p.name.lowercase(Locale.ROOT))}(?![\p{L}])""").containsMatchIn(lower) }
        val rest = product?.let { lower.replace(it.name.lowercase(Locale.ROOT), " ") } ?: lower
        val qtyMatch = Regex("""(?<![\p{L}\d.])(\d+(?:\.\d+)?)(?![\d])""").find(rest) ?: return null
        val qty = qtyMatch.groupValues[1].toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val words = rest.split(' ').filter { it.isNotEmpty() }
        val unit = words.firstNotNullOfOrNull { units[it] }
        val spoken = product?.name ?: words.filter { w ->
            w !in fillers && w !in units && w != qtyMatch.groupValues[1] && w.none(Char::isDigit) &&
                (inWords + outWords).none { k -> k.split(' ').contains(w) }
        }.joinToString(" ").trim()
        if (spoken.isEmpty()) return null
        return StockRequest(incoming = isIn, product = product, spokenName = spoken, qty = qty, unit = unit ?: product?.unit)
    }
}
