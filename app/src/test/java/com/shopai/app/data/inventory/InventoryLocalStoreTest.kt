package com.shopai.app.data.inventory

import com.shopai.app.data.model.CreateInventoryProductInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryLocalStoreTest {
    private val businessId = "biz-1"

    private fun baseInput(name: String = "Cement", currentStock: Double = 0.0, minimumStock: Double = 0.0) =
        CreateInventoryProductInput(
            name = name,
            category = "Construction",
            unit = "BAG",
            currentStock = currentStock,
            minimumStock = minimumStock,
        )

    @Test
    fun createProductPersistsAllExtendedFields() {
        val store = InventoryLocalStore()
        val input = baseInput().copy(
            subCategory = "Building Material",
            brand = "UltraTech",
            sku = "CEM-001",
            barcode = "8901234567890",
            purchasePrice = 320.0,
            sellingPrice = 380.0,
            mrp = 400.0,
            gstRate = 18.0,
            imageUri = "https://example.com/cement.jpg",
            notes = "Keep dry",
        )
        val product = store.createProduct(businessId, input)
        assertEquals("Building Material", product.subCategory)
        assertEquals("UltraTech", product.brand)
        assertEquals("CEM-001", product.sku)
        assertEquals("8901234567890", product.barcode)
        assertEquals(320.0, product.purchasePrice!!, 0.001)
        assertEquals(380.0, product.sellingPrice!!, 0.001)
        assertEquals(400.0, product.mrp!!, 0.001)
        assertEquals(18.0, product.gstRate!!, 0.001)
        assertEquals("https://example.com/cement.jpg", product.imageUri)
        assertEquals("Keep dry", product.notes)
    }

    @Test
    fun runningBalanceIsCorrectAcrossOpeningStockAndSubsequentMovements() {
        val store = InventoryLocalStore()
        val product = store.createProduct(businessId, baseInput(currentStock = 50.0))

        store.addStock(businessId, product.id, 20.0, "PURCHASE")
        store.removeStock(businessId, product.id, 30.0, "SALE")

        val movements = store.listMovements(businessId, product.id)
        val opening = movements.first { it.reason == "OPENING_STOCK" }
        val stockIn = movements.first { it.reason == "PURCHASE" }
        val stockOut = movements.first { it.reason == "SALE" }
        assertEquals(50.0, opening.balanceAfter!!, 0.001)
        assertEquals(70.0, stockIn.balanceAfter!!, 0.001)
        assertEquals(40.0, stockOut.balanceAfter!!, 0.001)
    }

    @Test
    fun noOpeningStockMovementIsRecordedWhenStartingAtZero() {
        val store = InventoryLocalStore()
        val product = store.createProduct(businessId, baseInput(currentStock = 0.0))
        assertEquals(emptyList<Any>(), store.listMovements(businessId, product.id))
        assertNull(product.supplierId)
    }
}
