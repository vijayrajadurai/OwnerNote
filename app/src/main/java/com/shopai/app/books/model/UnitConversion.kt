package com.shopai.app.books.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A product's units: stock is always kept in the [primary] unit. An optional
 * [secondary] unit converts as `1 secondary = factor primary`
 * (e.g. 1 BOX = 12 PCS → factorMilli = 12000).
 */
data class ProductUnits(
    val primary: String,
    val secondary: String? = null,
    val factorMilli: Long? = null,
) {
    init {
        require(primary.isNotBlank()) { "Primary unit is required" }
        require((secondary == null) == (factorMilli == null)) { "Secondary unit needs a conversion" }
        require(factorMilli == null || factorMilli > 0) { "Conversion must be more than zero" }
        require(secondary == null || !secondary.equals(primary, ignoreCase = true)) { "Secondary unit must differ from primary" }
    }

    /** Quantity in [unit] → quantity in the primary unit (thousandths). */
    fun toPrimary(qtyMilli: Long, unit: String): Long = when {
        unit.equals(primary, ignoreCase = true) -> qtyMilli
        secondary != null && unit.equals(secondary, ignoreCase = true) ->
            BigDecimal.valueOf(qtyMilli).multiply(BigDecimal.valueOf(factorMilli!!))
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact()
        else -> throw IllegalArgumentException("Unit $unit is not set up for this product")
    }

    fun accepts(unit: String): Boolean =
        unit.equals(primary, ignoreCase = true) || (secondary != null && unit.equals(secondary, ignoreCase = true))
}
