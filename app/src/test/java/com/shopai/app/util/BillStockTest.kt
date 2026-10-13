package com.shopai.app.util

import com.shopai.app.brain.tools.ProductRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** A scanned bill's items → the shop's products and quantities in their own units (owner, 13 Oct 2026). Nothing guessed. */
class BillStockTest {

    private fun r(s: String) = BigDecimal(s)
    private val shop = listOf(
        ProductRef("p1", "Basmati Rice", "KG", r("40")),
        ProductRef("p2", "Sugar", "KG", r("10")),
        ProductRef("p3", "Colgate", "PCS", r("240"), mapOf("BOX" to r("48"))),
        ProductRef("p4", "Oil", "BOTTLE", r("24"), mapOf("BOX" to r("12"))),
        ProductRef("p5", "Biscuit", "PCS", r("0")),
    )

    private val supplierBill = """
        SRI LAKSHMI TRADERS
        TAX INVOICE
        Invoice No: SLT-1042
        Date: 12/09/2026
        Bill To: Kumar Stores
        Description HSN Qty Rate Amount
        Basmati Rice 1006 10 kg 85.00 850.00
        Sugar 1701 5 kg 44.00 220.00
        Taxable Value 1,070.00
        CGST @ 2.5% 26.75
        SGST @ 2.5% 26.75
        Grand Total 1,123.50
    """.trimIndent()

    @Test fun purchaseBillItemsMatchTheShopsProducts() {
        val lines = BillStock.lines(BillTextParser.parse(supplierBill).items, shop)
        assertEquals(2, lines.size)
        assertEquals("p1", lines[0].product?.id)
        assertEquals(0, r("10").compareTo(lines[0].qty))
        assertEquals("p2", lines[1].product?.id)
        assertEquals(0, r("5").compareTo(lines[1].qty))
        assertTrue(lines.all { it.sure })
    }

    @Test fun aBoxIsTurnedIntoTheProductsOwnUnit() {
        val l = BillStock.line(BillLineItem("COLGATE STRONG TEETH 200G", r("2688"), quantity = r("2"), unit = "BOX", unitPrice = r("1344")), shop)
        assertEquals("p3", l.product?.id)
        assertEquals(0, r("96").compareTo(l.qty))
        assertTrue(l.sure)
    }

    @Test fun gramsAndDozensBecomeKgAndPieces() {
        assertEquals(0, r("0.5").compareTo(BillStock.inProductUnit(r("500"), "gm", shop[1])))
        assertEquals(0, r("24").compareTo(BillStock.inProductUnit(r("2"), "doz", shop[2])))
    }

    @Test fun aUnitTheProductDoesntHaveIsNeverTheDefaultUnit() {
        // "Bag" for Sugar (no bag size set): asked, not read as kg.
        val l = BillStock.line(BillLineItem("Sugar", r("2200"), quantity = r("1"), unit = "bag", unitPrice = r("2200")), shop)
        assertEquals("p2", l.product?.id)
        assertNull(l.qty)
        assertEquals(BillStock.Problem.UNKNOWN_UNIT, l.problem)
        assertFalse(l.sure)
    }

    @Test fun noUnitOnTheBillIsCheckedByTheOwner() {
        val l = BillStock.line(BillLineItem("Oil", r("1700"), quantity = r("10"), unitPrice = r("170")), shop)
        assertEquals("p4", l.product?.id)
        assertEquals(BillStock.Problem.NO_UNIT, l.problem)
        assertFalse(l.sure)
    }

    @Test fun noQuantityReadIsNotGuessed() {
        val l = BillStock.line(BillLineItem("Biscuit", r("120")), shop)
        assertEquals("p5", l.product?.id)
        assertNull(l.qty)
        assertEquals(BillStock.Problem.NO_QUANTITY, l.problem)
    }

    @Test fun anItemTheShopDoesntKeepIsNotMatchedToAnother() {
        // A product not in the list: never matched to a different product.
        val l = BillStock.line(BillLineItem("Maida 1 kg", r("45"), quantity = r("1"), unit = "kg", unitPrice = r("45")), shop)
        assertNull(l.product)
        assertEquals(BillStock.Problem.NO_PRODUCT, l.problem)
        assertFalse(l.sure)
    }

    @Test fun theWayAskedIsReadOnceAndNotForever() {
        BillStock.Request.ask(BillStock.Way.IN, now = 1_000L)
        assertEquals(BillStock.Way.IN, BillStock.Request.take(now = 2_000L))
        assertNull(BillStock.Request.take(now = 3_000L))
        // Asked long ago (over 15 minutes): not this scan's.
        BillStock.Request.ask(BillStock.Way.OUT, now = 0L)
        assertNull(BillStock.Request.take(now = 16 * 60_000L))
    }

    @Test fun summaryLinesAreNotItems() {
        assertTrue(BillStock.lines(listOf(BillLineItem("12345", r("10"))), shop).isEmpty())
    }
}
