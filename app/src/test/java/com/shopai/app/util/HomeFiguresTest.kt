package com.shopai.app.util

import com.shopai.app.data.model.BusinessKind
import com.shopai.app.data.model.InventoryProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 23 Oct 2026: a separate Home per business type, sales against yesterday, and a stock card that is never made up. */
class HomeFiguresTest {
    private fun product(name: String, stock: Double, min: Double) = InventoryProduct(
        id = name, businessId = "b", name = name, category = "c", unit = "pcs",
        currentStock = stock, minimumStock = min, createdAt = "", updatedAt = "",
    )

    @Test
    fun eachBusinessTypeGetsItsOwnHome() {
        assertEquals(HomeKind.SERVICE, homeKindOf(setOf(BusinessKind.SERVICE)))
        assertEquals(HomeKind.RETAIL, homeKindOf(setOf(BusinessKind.SERVICE, BusinessKind.RETAIL)))
        assertEquals(HomeKind.WHOLESALE, homeKindOf(setOf(BusinessKind.WHOLESALE)))
        assertEquals(HomeKind.WHOLESALE, homeKindOf(setOf(BusinessKind.SERVICE, BusinessKind.WHOLESALE)))
        assertEquals(HomeKind.RETAIL, homeKindOf(setOf(BusinessKind.RETAIL)))
        // Not set up yet: the retail Home, never a guess at service or wholesale.
        assertEquals(HomeKind.RETAIL, homeKindOf(emptySet()))
    }

    @Test
    fun salesAgainstYesterday() {
        assertEquals(12, salesDeltaPercent(18_240.0, 16_290.0))
        assertEquals(-50, salesDeltaPercent(500.0, 1_000.0))
        assertEquals(0, salesDeltaPercent(1_000.0, 1_000.0))
        // Nothing yesterday: no percent at all.
        assertNull(salesDeltaPercent(4_350.0, 0.0))
    }

    @Test
    fun stockCardShowsEmptiestFirstWithLevels() {
        val rows = stockWatch(
            listOf(
                product("Rice 5kg", 9.0, 5.0),
                product("Maggi", 6.0, 20.0),
                product("Sugar", 4.0, 3.0),
                product("Oil 1L", 3.0, 5.0),
                product("Soap", 50.0, 10.0),
                product("Salt", 5.0, 0.0),
            ),
        )
        assertEquals(listOf("Maggi", "Oil 1L", "Sugar", "Rice 5kg"), rows.map { it.product.name })
        assertEquals(listOf(StockLevel.LOW, StockLevel.LOW, StockLevel.NEAR, StockLevel.OK), rows.map { it.level })
        assertEquals(0.15f, rows[0].fill, 0.001f)
        // Plenty of stock, or no minimum set: not on the card.
        assertTrue(rows.none { it.product.name == "Soap" || it.product.name == "Salt" })
    }

    @Test
    fun stockCardIsEmptyWhenNothingIsShort() {
        assertTrue(stockWatch(listOf(product("Soap", 50.0, 10.0))).isEmpty())
        assertEquals(0.04f, stockWatch(listOf(product("Maggi", 0.0, 10.0))).single().fill, 0.0001f)
    }
}
