package com.shopai.app.data.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class InventoryLogicTest {
    private data class Product(override val id: String, override val name: String) : DuplicateCheckProduct

    private fun daysBefore(now: Date, days: Int): Date {
        val calendar = Calendar.getInstance()
        calendar.time = now
        calendar.add(Calendar.DATE, -days)
        return calendar.time
    }

    @Test
    fun stockStatusIsPlainThresholdComparison() {
        assertEquals(STOCK_STATUS_LOW_STOCK, computeStockStatus(5.0, 10.0))
        assertEquals(STOCK_STATUS_LOW_STOCK, computeStockStatus(10.0, 10.0))
        assertEquals(STOCK_STATUS_HEALTHY, computeStockStatus(11.0, 10.0))
    }

    @Test
    fun canRemoveStockNeverAllowsNegative() {
        assertTrue(canRemoveStock(10.0, 5.0).ok)
        val decision = canRemoveStock(10.0, 15.0)
        assertFalse(decision.ok)
        assertEquals(10.0, decision.available, 0.001)
    }

    @Test
    fun findDuplicateProductIsCaseInsensitiveAndTrimmed() {
        val existing = listOf(Product("a", "Rice Bag"))
        assertEquals("a", findDuplicateProduct("  rice bag  ", existing)?.id)
        assertNull(findDuplicateProduct("wheat bag", existing))
    }

    @Test
    fun outOfStockAlwaysFlaggedRegardlessOfHistory() {
        val now = Date()
        val product = IntelligenceProduct("p1", "Rice", "kg", currentStock = 0.0, minimumStock = 10.0)
        val result = analyzeInventoryProduct(product, emptyList(), now)
        assertEquals(INTELLIGENCE_STATUS_OUT_OF_STOCK, result.status)
        assertEquals(listOf(INSIGHT_OUT_OF_STOCK), result.insights)
    }

    @Test
    fun lowStockWithoutEnoughHistoryFlagsInsufficientHistory() {
        val now = Date()
        val product = IntelligenceProduct("p1", "Rice", "kg", currentStock = 5.0, minimumStock = 10.0)
        val movements = listOf(IntelligenceMovement("OUT", 2.0, daysBefore(now, 2)))
        val result = analyzeInventoryProduct(product, movements, now)
        assertEquals(INTELLIGENCE_STATUS_LOW_STOCK, result.status)
        assertFalse(result.hasEnoughHistory)
        assertTrue(result.insights.contains(INSIGHT_INSUFFICIENT_HISTORY))
        assertFalse(result.insights.contains(INSIGHT_ESTIMATED_LOW_COVER))
    }

    @Test
    fun lowStockWithEnoughHistoryEstimatesDaysRemaining() {
        val now = Date()
        val product = IntelligenceProduct("p1", "Rice", "kg", currentStock = 30.0, minimumStock = 40.0)
        val movements = (1..10).map { IntelligenceMovement("OUT", 3.0, daysBefore(now, it)) }
        val result = analyzeInventoryProduct(product, movements, now)
        assertEquals(INTELLIGENCE_STATUS_LOW_STOCK, result.status)
        assertTrue(result.hasEnoughHistory)
        assertEquals(1.0, result.averageDailyUsage!!, 0.001)
        assertEquals(30, result.estimatedDaysRemaining)
        assertTrue(result.insights.contains(INSIGHT_ESTIMATED_LOW_COVER))
    }

    @Test
    fun healthyStockWithNoUsageHasNoInsightsBeyondHealthy() {
        val now = Date()
        val product = IntelligenceProduct("p1", "Rice", "kg", currentStock = 100.0, minimumStock = 10.0)
        val result = analyzeInventoryProduct(product, emptyList(), now)
        assertEquals(INTELLIGENCE_STATUS_HEALTHY_STOCK, result.status)
        assertEquals(listOf(INSIGHT_HEALTHY_STOCK), result.insights)
        assertFalse(result.isHighUsage)
    }

    @Test
    fun highUsageDetectedWhenRecentWeekExceedsHistoricalAverage() {
        val now = Date()
        val product = IntelligenceProduct("p1", "Rice", "kg", currentStock = 100.0, minimumStock = 10.0)
        val olderMovements = (8..30).map { IntelligenceMovement("OUT", 1.0, daysBefore(now, it)) }
        val recentMovements = (1..7).map { IntelligenceMovement("OUT", 5.0, daysBefore(now, it)) }
        val result = analyzeInventoryProduct(product, olderMovements + recentMovements, now)
        assertTrue(result.hasEnoughHistory)
        assertTrue(result.isHighUsage)
        assertTrue(result.insights.contains(INSIGHT_HIGH_USAGE))
    }

    @Test
    fun neverCountsInOrFutureDatedMovementsAsUsage() {
        val now = Date()
        val product = IntelligenceProduct("p1", "Rice", "kg", currentStock = 10.0, minimumStock = 5.0)
        val future = Calendar.getInstance().apply { time = now; add(Calendar.DATE, 5) }.time
        val movements = listOf(
            IntelligenceMovement("IN", 50.0, daysBefore(now, 1)),
            IntelligenceMovement("OUT", 20.0, future),
            IntelligenceMovement("ADJUSTMENT", 3.0, daysBefore(now, 1)),
        )
        val result = analyzeInventoryProduct(product, movements, now)
        assertEquals(0.0, result.usage7Days, 0.001)
        assertEquals(0.0, result.usage30Days, 0.001)
    }

    @Test
    fun summaryRanksOutOfStockAboveLowStockAboveHighUsage() {
        val now = Date()
        val outOfStock = analyzeInventoryProduct(IntelligenceProduct("a", "A", "kg", 0.0, 5.0), emptyList(), now)
        val lowStock = analyzeInventoryProduct(IntelligenceProduct("b", "B", "kg", 2.0, 5.0), emptyList(), now)
        val healthy = analyzeInventoryProduct(IntelligenceProduct("c", "C", "kg", 100.0, 5.0), emptyList(), now)
        val summary = buildInventoryIntelligenceSummary(listOf(healthy, lowStock, outOfStock))
        assertEquals(listOf("a", "b"), summary.attentionProducts.map { it.productId })
        assertEquals(1, summary.lowStockCount)
        assertEquals(1, summary.outOfStockCount)
        assertEquals(1, summary.healthyCount)
    }

    @Test
    fun summaryNeverFabricatesAttentionProductsWhenAllHealthy() {
        val now = Date()
        val healthy = analyzeInventoryProduct(IntelligenceProduct("a", "A", "kg", 100.0, 5.0), emptyList(), now)
        val summary = buildInventoryIntelligenceSummary(listOf(healthy))
        assertTrue(summary.attentionProducts.isEmpty())
        assertTrue(summary.topUsageProducts.isEmpty())
        assertFalse(summary.insights.contains(INSIGHT_HEALTHY_STOCK))
    }
}
