package com.shopai.app.util

import com.shopai.app.brain.tools.ProductRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The owner's purchase invoice (15 Oct 2026): the shop's own name at the top as the buyer ("Bill To"), the supplier under
 * "SUPPLIER DETAILS", "Payment Terms : Credit (15 Days)", twelve item rows. Read as the scanner reads it: Kumar Traders is the
 * party (money to pay — a debit), nothing is paid (credit terms are never a ₹15 balance), and every row goes into the shop's
 * stock in its own unit (10 bags of 25 kg = 250 kg of Ponni Rice) — ticked only when product and quantity are both sure.
 */
class BillPurchaseInvoiceTest {
    private val text = javaClass.getResource("/bills/purchase-invoice-kumar-traders.txt")!!.readText()

    @Test fun theSupplierIsThePartyAndNothingIsPaid() {
        val b = BillTextParser.parse(text)
        assertEquals("Kumar Traders", b.merchantName)
        assertEquals(BigDecimal("34744.00"), b.total)
        assertEquals(0, b.paid!!.signum())
        assertEquals(java.time.LocalDate.of(2025, 3, 15), b.date)
        assertEquals(TxnDirection.DEBIT, BillDirection.decide(text, b, MyBusiness("Sri Lakshmi Super Mart", "33ABCDE5678G1Z9", "9876543210")).direction)
        // With the owner's details not set up yet, the title still says it: a purchase.
        assertEquals(TxnDirection.DEBIT, BillDirection.decide(text, b, MyBusiness(null, null, null)).direction)
    }

    @Test fun everyRowGoesIntoStockInTheProductsUnit() {
        val b = BillTextParser.parse(text)
        assertEquals(12, b.items.size)
        val products = listOf("Ponni Rice" to "KG", "Toor Dal" to "KG", "Urad Dal" to "KG", "Sugar" to "KG", "Sunflower Oil" to "LITRE", "Groundnut Oil" to "LITRE",
            "Tea Powder" to "KG", "Coffee Powder" to "KG", "Wheat Flour" to "KG", "Maida" to "KG", "Salt" to "KG", "Detergent Powder" to "KG")
            .mapIndexed { i, (n, u) -> ProductRef("p$i", n, u, BigDecimal("10"), if (n == "Ponni Rice") mapOf("BAG" to BigDecimal("25")) else emptyMap()) }
        val lines = BillStock.lines(b.items, products)
        assertTrue(lines.joinToString("\n") { "${it.itemName} → ${it.product?.name} ${it.qty} ${it.problem}" }, lines.all { it.sure })
        assertEquals(BigDecimal("250"), lines.first { it.product?.name == "Ponni Rice" }.qty!!.stripTrailingZeros().let { BigDecimal(it.toPlainString()) })
        assertEquals(BigDecimal("10"), lines.first { it.product?.name == "Sunflower Oil" }.qty!!.stripTrailingZeros().let { BigDecimal(it.toPlainString()) })
    }

    @Test fun aSalesBillsFooterNeverRenamesItsShop() {
        // A word NOT a purchase heading's place: "Supplier" / "Seller" signed at the foot of an ordinary sales bill.
        val sale = (listOf("ANNAI STORES", "12 Main Road, Madurai", "Bill No : 451", "Date : 10-10-2026", "Rice 2 kg 120", "Sugar 1 kg 45") +
            List(16) { "Item $it 1 10" } + listOf("Total 325", "for Supplier", "Authorised Signatory", "Best seller of the week", "Thank you")).joinToString("\n")
        assertEquals("ANNAI STORES", BillTextParser.parse(sale).merchantName)
    }
}
