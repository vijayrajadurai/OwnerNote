package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.math.RoundingMode

/** One quantity the owner said: "5 box", "3 pieces", "12" ([unit] null = the product's own unit). */
data class QtyPart(val qty: BigDecimal, val unit: String?)

/** How the owner's quantity turns into the product's stock unit. */
sealed interface UnitResolution {
    /**
     * [baseQty] in the product's stock unit [baseUnit]; [input] as the owner said it;
     * [rules] the conversions used (unit → product units per 1), empty when none was needed.
     */
    data class Ok(val baseQty: BigDecimal, val baseUnit: String, val input: List<QtyPart>, val rules: Map<String, BigDecimal>) : UnitResolution {
        val converted: Boolean get() = rules.isNotEmpty()
    }

    /** The owner said [unit] and Kai doesn't know how many product units it holds — Kai must ask, never assume. */
    data class Missing(val unit: String, val input: List<QtyPart>) : UnitResolution
}

/**
 * Units for stock in / out. Stock is always kept in the product's own (base)
 * unit — the books' `ProductUnits` (primary unit + one secondary unit with its
 * conversion, set per product). A quantity in another unit is converted only
 * when that product's conversion is known (its secondary unit, one the owner
 * gave in this conversation, or a fixed one: kg↔gram, litre↔ml, dozen = 12 pieces).
 * Otherwise Kai asks — 2 boxes are never taken as 2 pieces.
 */
object KaiUnits {
    /** One name per unit ("pieces" / "nos" → PCS, "packet" → PACK, "g" → GRAM). */
    fun canon(unit: String?): String? = unit?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
        when (it) {
            "NOS", "NO", "PC", "PIECE", "PIECES", "PCS" -> "PCS"
            "PACKET", "PACKETS", "PACKS", "PACK", "PKT" -> "PACK"
            "KGS", "KILO", "KG" -> "KG"
            "G", "GM", "GMS", "GRAMS", "GRAM" -> "GRAM"
            "L", "LTR", "LITER", "LITRES", "LITRE" -> "LITRE"
            "BOXES", "BOX" -> "BOX"
            "TRAYS", "TRAY" -> "TRAY"
            else -> it
        }
    }

    /** Fixed conversions that are true for every product (never a guess). */
    private fun standard(from: String, to: String): BigDecimal? = when (from to to) {
        "KG" to "GRAM" -> BigDecimal(1000)
        "GRAM" to "KG" -> BigDecimal("0.001")
        "LITRE" to "ML" -> BigDecimal(1000)
        "ML" to "LITRE" -> BigDecimal("0.001")
        "DOZEN" to "PCS" -> BigDecimal(12)
        else -> null
    }

    /**
     * Product units in one [unit] of [product], or null when unknown.
     * [extra] = conversions the owner gave in this conversation for this product.
     */
    fun factor(product: ProductRef, unit: String?, extra: Map<String, BigDecimal> = emptyMap()): BigDecimal? {
        val u = canon(unit) ?: return BigDecimal.ONE
        val base = canon(product.unit) ?: "PCS"
        if (u == base) return BigDecimal.ONE
        product.conversions.entries.firstOrNull { canon(it.key) == u }?.let { return it.value }
        extra.entries.firstOrNull { canon(it.key) == u }?.let { return it.value }
        return standard(u, base)
    }

    fun resolve(product: ProductRef, parts: List<QtyPart>, extra: Map<String, BigDecimal> = emptyMap()): UnitResolution {
        val base = canon(product.unit) ?: "PCS"
        var total = BigDecimal.ZERO
        val rules = LinkedHashMap<String, BigDecimal>()
        for (p in parts) {
            val u = canon(p.unit)
            val f = factor(product, u, extra) ?: return UnitResolution.Missing(u!!, parts)
            if (u != null && u != base) rules[u] = f
            total += p.qty.multiply(f)
        }
        return UnitResolution.Ok(clean(total), product.unit, parts, rules)
    }

    /** "1 box + 3 pieces" — the owner's quantity as they said it. */
    fun describe(parts: List<QtyPart>, productUnit: String): String =
        parts.joinToString(" + ") { KaiStock.shown(it.qty, it.unit ?: productUnit) }

    /** "1 box = 12 pieces". */
    fun rule(unit: String, factor: BigDecimal, productUnit: String): String =
        "1 ${KaiStock.unitWord(unit, BigDecimal.ONE)} = ${KaiStock.shown(factor, productUnit)}"

    fun clean(q: BigDecimal): BigDecimal = q.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
}
