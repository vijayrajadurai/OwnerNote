package com.shopai.app.data.inventory

import java.util.Calendar
import java.util.Date

/**
 * Faithful port of the Owner Note Inventory + AI Stock Intelligence (V1) pure logic.
 * Original (mobile): apps/mobile/src/storage/localAi/inventory.ts + inventoryIntelligence.ts.
 * Backend port: OwnerNote-Backend apps/backend/src/modules/inventory/inventory.logic.ts.
 *
 * currentStock/minimumStock are physical quantities only, never currency.
 * AI Stock Intelligence V2 (trend/acceleration/seasonal) is out of scope here,
 * matching the same scoping decision already made on the backend port.
 */

const val STOCK_STATUS_LOW_STOCK = "LOW_STOCK"
const val STOCK_STATUS_HEALTHY = "HEALTHY"

fun computeStockStatus(currentStock: Double, minimumStock: Double): String =
    if (currentStock <= minimumStock) STOCK_STATUS_LOW_STOCK else STOCK_STATUS_HEALTHY

data class StockRemovalDecision(val ok: Boolean, val available: Double = 0.0)

fun canRemoveStock(currentStock: Double, quantity: Double): StockRemovalDecision =
    if (quantity > currentStock) StockRemovalDecision(ok = false, available = currentStock) else StockRemovalDecision(ok = true)

private fun normalizeProductName(name: String): String = name.trim().lowercase()

interface DuplicateCheckProduct {
    val id: String
    val name: String
}

fun <T : DuplicateCheckProduct> findDuplicateProduct(name: String, existingProducts: List<T>): T? {
    val target = normalizeProductName(name)
    return existingProducts.firstOrNull { normalizeProductName(it.name) == target }
}

private const val MIN_OUT_RECORDS_FOR_HISTORY = 3
private const val USAGE_WINDOW_7_DAYS = 7
private const val USAGE_WINDOW_30_DAYS = 30
private const val HIGH_USAGE_MULTIPLIER = 1.5
private const val MAX_TOP_USAGE_PRODUCTS = 5

const val INTELLIGENCE_STATUS_OUT_OF_STOCK = "OUT_OF_STOCK"
const val INTELLIGENCE_STATUS_LOW_STOCK = "LOW_STOCK"
const val INTELLIGENCE_STATUS_HEALTHY_STOCK = "HEALTHY_STOCK"

const val INSIGHT_OUT_OF_STOCK = "OUT_OF_STOCK"
const val INSIGHT_LOW_STOCK = "LOW_STOCK"
const val INSIGHT_HEALTHY_STOCK = "HEALTHY_STOCK"
const val INSIGHT_HIGH_USAGE = "HIGH_USAGE"
const val INSIGHT_ESTIMATED_LOW_COVER = "ESTIMATED_LOW_COVER"
const val INSIGHT_INSUFFICIENT_HISTORY = "INSUFFICIENT_HISTORY"

data class IntelligenceProduct(
    val id: String,
    val name: String,
    val unit: String,
    val currentStock: Double,
    val minimumStock: Double,
)

data class IntelligenceMovement(
    val type: String,
    val quantity: Double,
    val createdAt: Date,
)

data class ProductIntelligence(
    val productId: String,
    val productName: String,
    val currentStock: Double,
    val minimumStock: Double,
    val unit: String,
    val status: String,
    val usage7Days: Double,
    val usage30Days: Double,
    val averageDailyUsage: Double?,
    val estimatedDaysRemaining: Int?,
    val hasEnoughHistory: Boolean,
    val isHighUsage: Boolean,
    val insights: List<String>,
)

private fun computeIntelligenceStatus(currentStock: Double, minimumStock: Double): String = when {
    currentStock == 0.0 -> INTELLIGENCE_STATUS_OUT_OF_STOCK
    currentStock <= minimumStock -> INTELLIGENCE_STATUS_LOW_STOCK
    else -> INTELLIGENCE_STATUS_HEALTHY_STOCK
}

private data class OutTotal(val total: Double, val count: Int)

private fun sumOutMovements(movements: List<IntelligenceMovement>, since: Date, now: Date): OutTotal {
    var total = 0.0
    var count = 0
    for (m in movements) {
        if (m.type != "OUT") continue
        val t = m.createdAt.time
        if (t > now.time) continue
        if (t < since.time) continue
        total += m.quantity
        count += 1
    }
    return OutTotal(total, count)
}

private fun daysAgo(now: Date, days: Int): Date {
    val calendar = Calendar.getInstance()
    calendar.time = now
    calendar.add(Calendar.DATE, -days)
    return calendar.time
}

fun analyzeInventoryProduct(product: IntelligenceProduct, movements: List<IntelligenceMovement>, now: Date): ProductIntelligence {
    val out7 = sumOutMovements(movements, daysAgo(now, USAGE_WINDOW_7_DAYS), now)
    val out30 = sumOutMovements(movements, daysAgo(now, USAGE_WINDOW_30_DAYS), now)

    val status = computeIntelligenceStatus(product.currentStock, product.minimumStock)
    val hasEnoughHistory = out30.count >= MIN_OUT_RECORDS_FOR_HISTORY
    val averageDailyUsage = if (hasEnoughHistory) out30.total / USAGE_WINDOW_30_DAYS else null
    val estimatedDaysRemaining =
        if (hasEnoughHistory && averageDailyUsage != null && averageDailyUsage > 0 && product.currentStock > 0) {
            Math.round(product.currentStock / averageDailyUsage).toInt()
        } else {
            null
        }

    val historicalWeeklyAverage = if (hasEnoughHistory && averageDailyUsage != null) averageDailyUsage * 7 else null
    val isHighUsage = hasEnoughHistory && historicalWeeklyAverage != null && historicalWeeklyAverage > 0 &&
        out7.total > historicalWeeklyAverage * HIGH_USAGE_MULTIPLIER

    val insights = mutableListOf<String>()
    when (status) {
        INTELLIGENCE_STATUS_OUT_OF_STOCK -> insights.add(INSIGHT_OUT_OF_STOCK)
        INTELLIGENCE_STATUS_LOW_STOCK -> {
            insights.add(INSIGHT_LOW_STOCK)
            if (hasEnoughHistory && estimatedDaysRemaining != null) {
                insights.add(INSIGHT_ESTIMATED_LOW_COVER)
            } else if (!hasEnoughHistory) {
                insights.add(INSIGHT_INSUFFICIENT_HISTORY)
            }
        }
        else -> insights.add(INSIGHT_HEALTHY_STOCK)
    }
    if (isHighUsage) insights.add(INSIGHT_HIGH_USAGE)

    return ProductIntelligence(
        productId = product.id,
        productName = product.name,
        currentStock = product.currentStock,
        minimumStock = product.minimumStock,
        unit = product.unit,
        status = status,
        usage7Days = out7.total,
        usage30Days = out30.total,
        averageDailyUsage = averageDailyUsage,
        estimatedDaysRemaining = estimatedDaysRemaining,
        hasEnoughHistory = hasEnoughHistory,
        isHighUsage = isHighUsage,
        insights = insights,
    )
}

data class InventoryIntelligenceSummary(
    val totalProducts: Int,
    val lowStockCount: Int,
    val outOfStockCount: Int,
    val healthyCount: Int,
    val attentionProducts: List<ProductIntelligence>,
    val topUsageProducts: List<ProductIntelligence>,
    val insights: List<String>,
)

private fun attentionRank(p: ProductIntelligence): Int = when {
    p.status == INTELLIGENCE_STATUS_OUT_OF_STOCK -> 0
    p.status == INTELLIGENCE_STATUS_LOW_STOCK -> 1
    p.isHighUsage -> 2
    else -> 99
}

fun buildInventoryIntelligenceSummary(analyses: List<ProductIntelligence>): InventoryIntelligenceSummary {
    val outOfStockCount = analyses.count { it.status == INTELLIGENCE_STATUS_OUT_OF_STOCK }
    val lowStockCount = analyses.count { it.status == INTELLIGENCE_STATUS_LOW_STOCK }
    val healthyCount = analyses.count { it.status == INTELLIGENCE_STATUS_HEALTHY_STOCK }

    val attentionProducts = analyses.filter { attentionRank(it) < 99 }.sortedBy { attentionRank(it) }

    val topUsageProducts = analyses
        .filter { it.isHighUsage }
        .sortedByDescending { it.usage7Days }
        .take(MAX_TOP_USAGE_PRODUCTS)

    val insights = analyses.flatMap { it.insights }.distinct().filter { it != INSIGHT_HEALTHY_STOCK }

    return InventoryIntelligenceSummary(
        totalProducts = analyses.size,
        lowStockCount = lowStockCount,
        outOfStockCount = outOfStockCount,
        healthyCount = healthyCount,
        attentionProducts = attentionProducts,
        topUsageProducts = topUsageProducts,
        insights = insights,
    )
}
