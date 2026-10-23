package com.shopai.app.util

import com.shopai.app.data.model.BusinessKind
import com.shopai.app.data.model.InventoryProduct
import kotlin.math.roundToInt

/** Which Home the shop sees (owner, 23 Oct 2026: retail, service and wholesale each get their own Home). */
enum class HomeKind { RETAIL, SERVICE, WHOLESALE }

/** A service-only shop gets the service Home; any wholesale gets the wholesale Home; everything else retail. */
fun homeKindOf(kinds: Set<BusinessKind>): HomeKind = when {
    BusinessKind.SERVICE in kinds && BusinessKind.RETAIL !in kinds && BusinessKind.WHOLESALE !in kinds -> HomeKind.SERVICE
    BusinessKind.WHOLESALE in kinds -> HomeKind.WHOLESALE
    else -> HomeKind.RETAIL
}

/** Today against yesterday in whole percent; null when yesterday had nothing (no made-up "100% more"). */
fun salesDeltaPercent(today: Double, yesterday: Double): Int? =
    if (yesterday <= 0.0) null else ((today / yesterday - 1.0) * 100.0).roundToInt()

enum class StockLevel { LOW, NEAR, OK }

/** One row of Home's stock card: the product, how full its bar is (0..1 of twice its minimum) and its colour level. */
data class StockWatchRow(val product: InventoryProduct, val fill: Float, val level: StockLevel)

/**
 * Products to watch on Home: those with a minimum set and stock at or under twice that minimum, emptiest first.
 * At or under the minimum is LOW (the same rule as the Stock screen), under 1.5× is NEAR, else OK.
 */
fun stockWatch(products: List<InventoryProduct>, limit: Int = 4): List<StockWatchRow> =
    products
        .filter { it.minimumStock > 0.0 && it.currentStock <= it.minimumStock * 2 }
        .sortedBy { it.currentStock / it.minimumStock }
        .take(limit)
        .map { p ->
            val level = when {
                p.currentStock <= p.minimumStock -> StockLevel.LOW
                p.currentStock <= p.minimumStock * 1.5 -> StockLevel.NEAR
                else -> StockLevel.OK
            }
            StockWatchRow(p, (p.currentStock / (p.minimumStock * 2)).toFloat().coerceIn(0.04f, 1f), level)
        }
