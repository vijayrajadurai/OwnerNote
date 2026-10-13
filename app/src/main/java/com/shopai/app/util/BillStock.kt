package com.shopai.app.util

import com.shopai.app.brain.tools.KaiStock
import com.shopai.app.brain.tools.ProductRef
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * The stock a scanned bill moves (owner, 13 Oct 2026: "purchase bill / sales bill photo → stock increase / less").
 *
 * Each item line read from the bill ([BillLineItem]) is matched to one of the shop's products by name (the same matcher Kai
 * uses for spoken / typed product names), and its quantity is turned into the product's own unit (1 BOX = 12 PCS from the
 * product's conversion, 500 G = 0.5 KG). Nothing is guessed: a line with no product, no quantity, or a unit that can't be
 * turned into the product's unit is shown for the owner to fill in and is not ticked. Nothing moves before the bill is saved.
 */
object BillStock {

    enum class Problem {
        /** No product of the shop has this name — added to stock only once the owner picks / creates it in Inventory. */
        NO_PRODUCT,
        /** The bill row has no quantity Kai could read (qty × rate didn't match the amount). */
        NO_QUANTITY,
        /** The bill row has no unit: the quantity is shown in the product's unit for the owner to check. */
        NO_UNIT,
        /** A unit the product doesn't have ("BOX" for a product with no box size). */
        UNKNOWN_UNIT,
    }

    data class Line(
        /** The item as printed on the bill. */
        val itemName: String,
        val billQty: BigDecimal?,
        val billUnit: String?,
        val product: ProductRef?,
        /** The quantity in [product]'s own unit; null when it can't be worked out. */
        val qty: BigDecimal?,
        val problem: Problem?,
    ) {
        /** Ticked by itself only when product and quantity are both sure. */
        val sure: Boolean get() = product != null && qty != null && qty.signum() > 0 && problem == null
    }

    /** Bill units, written many ways, as one key. */
    private val unitKeys = mapOf(
        "nos" to "pcs", "no" to "pcs", "pcs" to "pcs", "pc" to "pcs", "piece" to "pcs", "pieces" to "pcs", "unit" to "pcs", "units" to "pcs",
        "each" to "pcs", "ea" to "pcs", "set" to "set",
        "kg" to "kg", "kgs" to "kg", "kilo" to "kg", "g" to "g", "gm" to "g", "gms" to "g", "gram" to "g", "grams" to "g",
        "l" to "ltr", "ltr" to "ltr", "litre" to "ltr", "liter" to "ltr", "ml" to "ml",
        "btl" to "bottle", "bottle" to "bottle", "bottles" to "bottle", "pkt" to "packet", "packet" to "packet", "packets" to "packet",
        "box" to "box", "boxes" to "box", "bag" to "bag", "bags" to "bag", "dozen" to "dozen", "doz" to "dozen", "mtr" to "mtr", "m" to "mtr",
    )

    private fun key(unit: String?): String? = unit?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { unitKeys[it] ?: it }

    /** Every bill line, matched to a product where one has its name. */
    fun lines(items: List<BillLineItem>, products: List<ProductRef>): List<Line> =
        items.filter { it.description.any(Char::isLetter) }.map { line(it, products) }

    fun line(item: BillLineItem, products: List<ProductRef>): Line {
        val product = match(item.description, products)
        val qty = item.quantity?.takeIf { it.signum() > 0 }
        if (product == null) return Line(item.description, qty, item.unit, null, null, Problem.NO_PRODUCT)
        if (qty == null) return Line(item.description, null, item.unit, product, null, Problem.NO_QUANTITY)
        val unit = key(item.unit) ?: return Line(item.description, qty, null, product, qty, Problem.NO_UNIT)
        val inUnit = inProductUnit(qty, unit, product)
        return Line(item.description, qty, item.unit, product, inUnit, if (inUnit == null) Problem.UNKNOWN_UNIT else null)
    }

    /** The shop's product this bill line names, or null. */
    fun match(description: String, products: List<ProductRef>): ProductRef? {
        // Pack sizes and codes ("200G", "1L", "x12") are not part of the name.
        val name = description.replace(Regex("""(?i)\b\d+(?:\.\d+)?\s*(?:g|gm|gms|kg|ml|l|ltr|pcs|x)\b|\bx\s*\d+\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (name.none(Char::isLetter)) return null
        return KaiStock.productNamedIn(name, products)
    }

    /** [qty] [unit] in [product]'s own unit, from its conversion or the standard weights and measures; null when it can't be. */
    fun inProductUnit(qty: BigDecimal, unit: String, product: ProductRef): BigDecimal? {
        val base = key(product.unit)
        val u = key(unit) ?: return null
        if (u == base) return qty
        // The product's own second unit ("1 BOX = 12 PCS").
        product.conversions.entries.firstOrNull { key(it.key) == u }?.let { return qty.multiply(it.value) }
        return when {
            u == "g" && base == "kg" -> qty.divide(BigDecimal(1000), 3, RoundingMode.HALF_UP).stripTrailingZeros()
            u == "kg" && base == "g" -> qty.multiply(BigDecimal(1000))
            u == "ml" && base == "ltr" -> qty.divide(BigDecimal(1000), 3, RoundingMode.HALF_UP).stripTrailingZeros()
            u == "ltr" && base == "ml" -> qty.multiply(BigDecimal(1000))
            u == "dozen" && base == "pcs" -> qty.multiply(BigDecimal(12))
            else -> null
        }
    }
}
