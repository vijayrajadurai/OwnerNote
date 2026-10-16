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

    /**
     * The owner's purchase bill (16 Oct 2026): every item was "Not in your inventory" and could not be ticked; the shop's
     * address was read as an item; row numbers stayed on names. The address is no item, the names are clean, and an item not
     * in the inventory keeps its quantity, unit and rate — to be added as a new product.
     */
    @Test fun aNewShopsBillItemsAreCleanAndCanBeAdded() {
        val bill = """
            KUMAR TRADERS
            123, Main Road, Gandhi Nagar, Coimbatore - 641 012, Tamil Nadu | Supplier Ref ST
            TAX INVOICE
            Invoice No: P/2024-25/0786
            Date: 15/03/2025
            Bill To: SRI LAKSHMI SUPER MART
            S.No Description Qty Rate Amount
            1 Rice Ponni Aachi 25 kg 52.00 1300.00
            6 _| Groundnut Oil idhayam 10 ltr 180.00 1800.00
            Tea Powder _ Tata Tea 5 pkt 120.00 600.00
            10 | Maida Pillsbury 10 kg 40.00 400.00
            Grand Total 4,100.00
        """.trimIndent()
        // (a new shop: none of these in its inventory yet, as on the owner's phone)
        val lines = BillStock.lines(BillTextParser.parse(bill).items, emptyList())
        val names = lines.map { it.itemName }
        assertTrue(names.toString(), names.none { it.contains("Main Road") || it.contains("641") })
        assertTrue(names.toString(), "Groundnut Oil idhayam" in names && "Maida Pillsbury" in names && "Tea Powder Tata Tea" in names)
        val oil = lines.first { it.itemName == "Groundnut Oil idhayam" }
        assertEquals(BillStock.Problem.NO_PRODUCT, oil.problem)
        assertEquals(0, r("10").compareTo(oil.billQty))
        assertEquals("LITRE", BillStock.productUnit(oil.billUnit))
        assertEquals(0, r("180").compareTo(oil.rate))
        assertEquals("PACKET", BillStock.productUnit("pkt"))
        assertEquals("PCS", BillStock.productUnit(null))
    }

    @Test fun anAddressIsNoItemButAPackSizeIs() {
        assertTrue(BillStock.addressLine("123, Main Road, Gandhi Nagar, Coimbatore - 641 012, Tamil Nadu"))
        assertTrue(BillStock.addressLine("GSTIN 33AAAAA0000A1Z5 Phone 98765 43210"))
        assertFalse(BillStock.addressLine("Detergent Powder Surf Excel"))
        assertFalse(BillStock.addressLine("Basmati Rice 1006"))
        assertFalse(BillStock.addressLine("Atta 500 g"))
        assertEquals("Groundnut Oil idhayam", BillStock.cleanName("6 _| Groundnut Oil idhayam"))
        assertEquals("Maida Pillsbury", BillStock.cleanName("10 | Maida Pillsbury"))
        assertEquals("Rice Ponni Aachi", BillStock.cleanName("Rice Ponni Aachi"))
        assertEquals("Colgate 200g", BillStock.cleanName("Colgate 200g"))
    }
}
