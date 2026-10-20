package com.shopai.app.books.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A product's units: stock is always kept in the [primary] unit. An optional
 * [secondary] unit converts as `1 secondary = factor primary`
 * (e.g. 1 BOX = 12 PCS → factorMilli = 12000). [more] holds any further units the
 * owner set for the product (a purchase unit and a sales unit that both differ from the
 * stock unit: 1 CARTON = 144 PCS and 1 PACK = 6 PCS), each as thousandths of the primary unit.
 * A unit with no conversion set is never accepted — its size is never assumed.
 */
data class ProductUnits(
    val primary: String,
    val secondary: String? = null,
    val factorMilli: Long? = null,
    val more: Map<String, Long> = emptyMap(),
) {
    init {
        require(primary.isNotBlank()) { "Primary unit is required" }
        require((secondary == null) == (factorMilli == null)) { "Secondary unit needs a conversion" }
        require(factorMilli == null || factorMilli > 0) { "Conversion must be more than zero" }
        require(secondary == null || !secondary.equals(primary, ignoreCase = true)) { "Secondary unit must differ from primary" }
        more.forEach { (unit, milli) ->
            require(unit.isNotBlank()) { "Unit is required" }
            require(milli > 0) { "Conversion for $unit must be more than zero" }
            require(!unit.equals(primary, ignoreCase = true) && !unit.equals(secondary, ignoreCase = true)) { "$unit is set twice" }
        }
        require(more.keys.map { it.uppercase() }.toSet().size == more.size) { "A unit is set twice" }
    }

    /** Primary units in one [unit], in thousandths (1 BOX = 12 PCS → 12000); null when [unit] is not set up for this product. */
    fun factorFor(unit: String): Long? = when {
        unit.equals(primary, ignoreCase = true) -> 1000L
        secondary != null && unit.equals(secondary, ignoreCase = true) -> factorMilli
        else -> more.entries.firstOrNull { it.key.equals(unit, ignoreCase = true) }?.value
    }

    /** Quantity in [unit] → quantity in the primary unit (thousandths). */
    fun toPrimary(qtyMilli: Long, unit: String): Long {
        val factor = factorFor(unit) ?: throw IllegalArgumentException("Unit $unit is not set up for this product")
        if (factor == 1000L) return qtyMilli
        return BigDecimal.valueOf(qtyMilli).multiply(BigDecimal.valueOf(factor))
            .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact()
    }

    fun accepts(unit: String): Boolean = factorFor(unit) != null

    /** Every unit this product can be bought or sold in, the stock unit first. */
    val all: List<String> get() = listOfNotNull(primary, secondary) + more.keys

    companion object {
        /** "CARTON=144000;PACK=6000" (as stored on the product) → the units it holds; a broken part is skipped. */
        fun parseMore(text: String?): Map<String, Long> =
            text.orEmpty().split(';').mapNotNull { part ->
                val (unit, milli) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val u = unit.trim().uppercase()
                val m = milli.trim().toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                if (u.isEmpty()) null else u to m
            }.toMap()

        /** The stored form of [more]; null when there are none. */
        fun formatMore(more: Map<String, Long>): String? =
            more.entries.joinToString(";") { "${it.key.trim().uppercase()}=${it.value}" }.takeIf { it.isNotEmpty() }
    }
}
