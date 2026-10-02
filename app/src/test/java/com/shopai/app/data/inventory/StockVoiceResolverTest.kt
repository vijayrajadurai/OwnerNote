package com.shopai.app.data.inventory

import com.shopai.app.data.model.InventoryProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockVoiceResolverTest {

    private fun product(
        name: String,
        unit: String = "bags",
        currentStock: Double = 100.0,
        minimumStock: Double = 10.0,
    ) = InventoryProduct(
        id = name.lowercase(),
        businessId = "biz-1",
        name = name,
        category = "General",
        unit = unit,
        currentStock = currentStock,
        minimumStock = minimumStock,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun findsProductByExactCaseInsensitiveName() {
        val products = listOf(product("Cement"))
        val found = StockVoiceResolver.findProductBySpokenName("cement", products)
        assertEquals("Cement", found?.name)
    }

    @Test
    fun findsProductByLeftoverSubstringAfterParsing() {
        // Parser leaves "Cement stock la irundhu" when the matched keyword
        // is only "remove pannu" — resolver must still find "Cement".
        val products = listOf(product("Cement"))
        val found = StockVoiceResolver.findProductBySpokenName("Cement stock la irundhu", products)
        assertEquals("Cement", found?.name)
    }

    @Test
    fun missingProductNeverAutoCreates() {
        val products = listOf(product("Cement"))
        val resolution = StockVoiceResolver.resolve("Rice 50 bags vanginen", products)
        assertTrue(resolution is StockVoiceResolution.ProductNotFound)
        assertEquals("Rice", (resolution as StockVoiceResolution.ProductNotFound).spokenName)
    }

    @Test
    fun missingSpokenUnitFallsBackToProductsOwnUnit() {
        val products = listOf(product("Cement", unit = "bags"))
        val resolution = StockVoiceResolver.resolve("Cement 50 add pannu", products)
        assertTrue(resolution is StockVoiceResolution.ReadyToConfirm)
        val ready = resolution as StockVoiceResolution.ReadyToConfirm
        assertEquals("bags", ready.unit)
        assertEquals(StockVoiceIntent.STOCK_IN, ready.intent)
    }

    @Test
    fun stockInNeverBlockedByInsufficientStockCheck() {
        val products = listOf(product("Cement", currentStock = 5.0))
        val resolution = StockVoiceResolver.resolve("Cement 50 bags vanginen", products)
        assertTrue(resolution is StockVoiceResolution.ReadyToConfirm)
    }

    @Test
    fun stockOutOverAvailableStockIsCaughtBeforeConfirmation() {
        val products = listOf(product("Cement", currentStock = 82.0, unit = "bags"))
        val resolution = StockVoiceResolver.resolve("Cement 100 bags sale panniten", products)
        assertTrue(resolution is StockVoiceResolution.InsufficientStock)
        val insufficient = resolution as StockVoiceResolution.InsufficientStock
        assertEquals(82.0, insufficient.availableQuantity, 0.001)
        assertEquals(100.0, insufficient.requestedQuantity, 0.001)
    }

    @Test
    fun stockOutWithinAvailableStockIsReadyToConfirm() {
        val products = listOf(product("Cement", currentStock = 82.0))
        val resolution = StockVoiceResolver.resolve("Cement 10 bags sale panniten", products)
        assertTrue(resolution is StockVoiceResolution.ReadyToConfirm)
    }

    @Test
    fun notUnderstoodTextNeverResolvesAProduct() {
        val products = listOf(product("Cement"))
        val resolution = StockVoiceResolver.resolve("Cement evlo irukku?", products)
        assertEquals(StockVoiceResolution.NotUnderstood, resolution)
    }

    @Test
    fun typedAndVoiceInputProduceTheSameResolution() {
        val products = listOf(product("Cement", currentStock = 82.0))
        val typed = StockVoiceResolver.resolve("Cement 50 bags vanginen", products)
        val fromVoiceTranscript = StockVoiceResolver.resolve("Cement 50 bags vanginen", products)
        assertEquals(typed, fromVoiceTranscript)
    }
}
