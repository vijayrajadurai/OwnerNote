package com.shopai.app.data.inventory

import com.shopai.app.data.model.CreateInventoryProductInput
import com.shopai.app.data.model.InventoryMovement
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.model.ProductIntelligenceDto
import com.shopai.app.data.model.InventoryIntelligenceSummaryDto
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-device local demo store, same limitation as Expo local mode —
 * mirrors GroupBuyingLocalStore's shape for this feature.
 */
class InventoryLocalStore {
    private val products = ConcurrentHashMap<String, InventoryProduct>()
    private val movements = ConcurrentHashMap<String, MutableList<InventoryMovement>>()

    fun listProducts(businessId: String): List<InventoryProduct> =
        products.values.filter { it.businessId == businessId }.sortedBy { it.name.lowercase() }

    fun getLowStockProducts(businessId: String): List<InventoryProduct> =
        listProducts(businessId).filter { computeStockStatus(it.currentStock, it.minimumStock) == STOCK_STATUS_LOW_STOCK }

    fun getProduct(businessId: String, productId: String): InventoryProduct? =
        products[productId]?.takeIf { it.businessId == businessId }

    fun createProduct(businessId: String, input: CreateInventoryProductInput): InventoryProduct {
        val existing = listProducts(businessId)
        val duplicate = findDuplicateProduct(input.name, existing.map { LocalDuplicateProduct(it.id, it.name) })
        require(duplicate == null) { "A product with this name already exists." }
        val now = Instant.now().toString()
        val product = InventoryProduct(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            name = input.name,
            category = input.category,
            subCategory = input.subCategory,
            brand = input.brand,
            sku = input.sku,
            barcode = input.barcode,
            unit = input.unit,
            currentStock = input.currentStock,
            minimumStock = input.minimumStock,
            purchasePrice = input.purchasePrice,
            sellingPrice = input.sellingPrice,
            mrp = input.mrp,
            gstRate = input.gstRate,
            // Local/offline mode has no local supplier store to resolve a name
            // from — the id is kept but never fabricated into a display name.
            supplierId = input.supplierId,
            supplierName = null,
            imageUri = input.imageUri,
            notes = input.notes,
            createdAt = now,
            updatedAt = now,
        )
        products[product.id] = product
        if (input.currentStock > 0) {
            recordMovement(product.id, "IN", input.currentStock, "OPENING_STOCK", input.currentStock)
        }
        return product
    }

    fun addStock(businessId: String, productId: String, quantity: Double, reason: String): InventoryProduct {
        val product = getProduct(businessId, productId) ?: error("Product not found")
        val newBalance = product.currentStock + quantity
        val updated = product.copy(currentStock = newBalance, updatedAt = Instant.now().toString())
        products[productId] = updated
        recordMovement(productId, "IN", quantity, reason, newBalance)
        return updated
    }

    fun removeStock(businessId: String, productId: String, quantity: Double, reason: String): InventoryStockChangeOutcome {
        val product = getProduct(businessId, productId) ?: error("Product not found")
        val decision = canRemoveStock(product.currentStock, quantity)
        if (!decision.ok) return InventoryStockChangeOutcome.InsufficientStock(decision.available)
        val newBalance = product.currentStock - quantity
        val updated = product.copy(currentStock = newBalance, updatedAt = Instant.now().toString())
        products[productId] = updated
        recordMovement(productId, "OUT", quantity, reason, newBalance)
        return InventoryStockChangeOutcome.Ok(updated)
    }

    fun listMovements(businessId: String, productId: String): List<InventoryMovement> {
        getProduct(businessId, productId) ?: error("Product not found")
        return movements[productId]?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    fun getIntelligenceSummary(businessId: String, now: Date): InventoryIntelligenceSummaryDto {
        val analyses = listProducts(businessId).map { analyzeIntelligence(it, now) }
        val summary = buildInventoryIntelligenceSummary(analyses)
        return InventoryIntelligenceSummaryDto(
            totalProducts = summary.totalProducts,
            lowStockCount = summary.lowStockCount,
            outOfStockCount = summary.outOfStockCount,
            healthyCount = summary.healthyCount,
            attentionProducts = summary.attentionProducts.map { it.toDto() },
            topUsageProducts = summary.topUsageProducts.map { it.toDto() },
            insights = summary.insights,
        )
    }

    fun getProductIntelligence(businessId: String, productId: String, now: Date): ProductIntelligenceDto {
        val product = getProduct(businessId, productId) ?: error("Product not found")
        return analyzeIntelligence(product, now).toDto()
    }

    private fun analyzeIntelligence(product: InventoryProduct, now: Date): ProductIntelligence {
        val productMovements = (movements[product.id] ?: emptyList()).map {
            IntelligenceMovement(type = it.type, quantity = it.quantity, createdAt = parseIso(it.createdAt))
        }
        return analyzeInventoryProduct(
            IntelligenceProduct(product.id, product.name, product.unit, product.currentStock, product.minimumStock),
            productMovements,
            now,
        )
    }

    private fun ProductIntelligence.toDto() = ProductIntelligenceDto(
        productId = productId,
        productName = productName,
        currentStock = currentStock,
        minimumStock = minimumStock,
        unit = unit,
        status = status,
        usage7Days = usage7Days,
        usage30Days = usage30Days,
        averageDailyUsage = averageDailyUsage,
        estimatedDaysRemaining = estimatedDaysRemaining,
        hasEnoughHistory = hasEnoughHistory,
        isHighUsage = isHighUsage,
        insights = insights,
    )

    private fun recordMovement(productId: String, type: String, quantity: Double, reason: String, balanceAfter: Double) {
        val list = movements.getOrPut(productId) { mutableListOf() }
        list.add(
            InventoryMovement(
                id = UUID.randomUUID().toString(),
                productId = productId,
                type = type,
                quantity = quantity,
                reason = reason,
                referenceType = null,
                referenceId = null,
                balanceAfter = balanceAfter,
                createdAt = Instant.now().toString(),
            ),
        )
    }

    private fun parseIso(value: String): Date = Date(Instant.parse(value).toEpochMilli())

    private data class LocalDuplicateProduct(override val id: String, override val name: String) : DuplicateCheckProduct
}

sealed class InventoryStockChangeOutcome {
    data class Ok(val product: InventoryProduct) : InventoryStockChangeOutcome()
    data class InsufficientStock(val available: Double) : InventoryStockChangeOutcome()
}
