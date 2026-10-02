package com.shopai.app.data.repository

import com.shopai.app.BuildConfig
import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.integration.LegacyBridge
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.inventory.InventoryLocalStore
import com.shopai.app.data.inventory.InventoryStockChangeOutcome
import com.shopai.app.data.model.CreateInventoryProductInput
import com.shopai.app.data.model.InventoryIntelligenceSummaryDto
import com.shopai.app.data.model.InventoryMovement
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.model.ProductIntelligenceDto
import com.shopai.app.data.model.StockChangeInput
import com.shopai.app.data.model.UpdateInventoryProductInput
import java.io.IOException
import java.util.Date

sealed class StockChangeResult {
    data class Ok(val product: InventoryProduct) : StockChangeResult()
    data class InsufficientStock(val available: Double) : StockChangeResult()
}

/**
 * Network Inventory API with the same local-demo fallback shape as
 * `GroupBuyingRepository`: when no backend URL is configured, all calls
 * stay on-device. After the one-time import the products, their stock and
 * every stock movement live in the on-device books (one product master,
 * stock = the sum of its movements).
 */
class InventoryRepository(
    private val api: ShopAiApi,
    private val local: InventoryLocalStore = InventoryLocalStore(),
    private val localBusinessId: String = LOCAL_BUSINESS_ID,
    private val books: BooksModule? = null,
) {
    private suspend fun bridge(): LegacyBridge? = books?.session()?.let(::LegacyBridge)

    suspend fun listProducts(): List<InventoryProduct> {
        bridge()?.let { return it.products() }
        if (isLocalMode()) return local.listProducts(localBusinessId)
        return runCatching { api.listInventoryProducts().data }
            .recoverCatching { error -> if (error is IOException) local.listProducts(localBusinessId) else throw error }
            .getOrThrow()
    }

    suspend fun getLowStockProducts(): List<InventoryProduct> {
        bridge()?.let { return it.lowStockProducts() }
        if (isLocalMode()) return local.getLowStockProducts(localBusinessId)
        return runCatching { api.listLowStockProducts().data }
            .recoverCatching { error -> if (error is IOException) local.getLowStockProducts(localBusinessId) else throw error }
            .getOrThrow()
    }

    suspend fun createProduct(input: CreateInventoryProductInput): InventoryProduct {
        bridge()?.let { return it.createProduct(input) }
        if (isLocalMode()) {
            return local.createProduct(localBusinessId, input)
        }
        return runCatching { api.createInventoryProduct(input).data }
            .recoverCatching { error ->
                if (error is IOException) {
                    local.createProduct(localBusinessId, input)
                } else {
                    throw error
                }
            }
            .getOrThrow()
    }

    suspend fun getProduct(productId: String): InventoryProduct {
        bridge()?.let { return it.product(productId) }
        if (isLocalMode()) return local.getProduct(localBusinessId, productId) ?: error("Product not found")
        return runCatching { api.getInventoryProduct(productId).data }
            .recoverCatching { cause ->
                if (cause is IOException) local.getProduct(localBusinessId, productId) ?: error("Product not found") else throw cause
            }
            .getOrThrow()
    }

    suspend fun updateProduct(productId: String, input: UpdateInventoryProductInput): InventoryProduct {
        bridge()?.let { return it.updateProduct(productId, input) }
        if (isLocalMode()) return local.getProduct(localBusinessId, productId) ?: error("Product not found")
        return api.updateInventoryProduct(productId, input).data
    }

    suspend fun stockIn(productId: String, quantity: Double, reason: String): InventoryProduct {
        bridge()?.let { b ->
            return when (val r = b.stockChange(productId, quantity, reason, isIn = true)) {
                is StockChangeResult.Ok -> r.product
                is StockChangeResult.InsufficientStock -> error("Stock in cannot be short of stock")
            }
        }
        if (isLocalMode()) return local.addStock(localBusinessId, productId, quantity, reason)
        return runCatching { api.stockIn(productId, StockChangeInput(quantity, reason)).data }
            .recoverCatching { error -> if (error is IOException) local.addStock(localBusinessId, productId, quantity, reason) else throw error }
            .getOrThrow()
    }

    suspend fun stockOut(productId: String, quantity: Double, reason: String): StockChangeResult {
        bridge()?.let { return it.stockChange(productId, quantity, reason, isIn = false) }
        if (isLocalMode()) return local.removeStock(localBusinessId, productId, quantity, reason).toResult()
        return runCatching { StockChangeResult.Ok(api.stockOut(productId, StockChangeInput(quantity, reason)).data) }
            .recoverCatching { error ->
                if (error is IOException) local.removeStock(localBusinessId, productId, quantity, reason).toResult() else throw error
            }
            .getOrThrow()
    }

    suspend fun listMovements(productId: String): List<InventoryMovement> {
        bridge()?.let { return it.movements(productId) }
        if (isLocalMode()) return local.listMovements(localBusinessId, productId)
        return runCatching { api.listInventoryMovements(productId).data }
            .recoverCatching { error -> if (error is IOException) local.listMovements(localBusinessId, productId) else throw error }
            .getOrThrow()
    }

    suspend fun getProductIntelligence(productId: String): ProductIntelligenceDto {
        bridge()?.let { return it.productIntelligence(productId, Date()) }
        if (isLocalMode()) return local.getProductIntelligence(localBusinessId, productId, Date())
        return runCatching { api.getProductIntelligence(productId).data }
            .recoverCatching { error ->
                if (error is IOException) local.getProductIntelligence(localBusinessId, productId, Date()) else throw error
            }
            .getOrThrow()
    }

    suspend fun getIntelligenceSummary(): InventoryIntelligenceSummaryDto {
        bridge()?.let { return it.intelligenceSummary(Date()) }
        if (isLocalMode()) return local.getIntelligenceSummary(localBusinessId, Date())
        return runCatching { api.getInventoryIntelligenceSummary().data }
            .recoverCatching { error -> if (error is IOException) local.getIntelligenceSummary(localBusinessId, Date()) else throw error }
            .getOrThrow()
    }

    private fun InventoryStockChangeOutcome.toResult(): StockChangeResult = when (this) {
        is InventoryStockChangeOutcome.Ok -> StockChangeResult.Ok(product)
        is InventoryStockChangeOutcome.InsufficientStock -> StockChangeResult.InsufficientStock(available)
    }

    private fun isLocalMode(): Boolean {
        val base = BuildConfig.API_BASE_URL.trim()
        return base.isEmpty() || base == "local://"
    }

    companion object {
        const val LOCAL_BUSINESS_ID = "local-business"
    }
}
