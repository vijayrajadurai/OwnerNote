package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BillTextParserTest {

    private val supermarketBill = """
        SRI MURUGAN STORES
        No.12, Anna Nagar Main Road
        Chennai 600040
        Ph: 98765 43210
        GSTIN: 33ABCDE1234F1Z5
        TAX INVOICE
        Bill No: 1045    Date: 21/09/2026  10:42
        Item            Qty   Rate    Amount
        Ponni Rice 5kg   1   250.00   250.00
        Sunflower Oil 1L 2    90.00   180.00
        Toor Dal 1kg     1   140.00   140.00
        Sub Total                     570.00
        CGST 2.5%                      14.25
        SGST 2.5%                      14.25
        Round Off                       0.50
        Grand Total                   599.00
        Cash                          600.00
        Change                          1.00
        Thank you! Visit again
    """.trimIndent()

    @Test
    fun readsShopNameFromTheTop() {
        assertEquals("SRI MURUGAN STORES", BillTextParser.parse(supermarketBill).merchantName)
    }

    @Test
    fun grandTotalWinsOverSubTotalAndCash() {
        val bill = BillTextParser.parse(supermarketBill)
        assertEquals(BigDecimal("599.00"), bill.total)
        assertTrue(bill.totalFromLabel)
    }

    @Test
    fun readsOnlyRealItemLines() {
        val items = BillTextParser.parse(supermarketBill).items
        assertEquals(
            listOf("Ponni Rice 5kg" to "250.00", "Sunflower Oil 1L" to "180.00", "Toor Dal 1kg" to "140.00"),
            items.map { it.description to it.amount.toPlainString() },
        )
    }

    @Test
    fun addressPincodePhoneAndDateAreNotItems() {
        val descriptions = BillTextParser.parse(supermarketBill).items.map { it.description }
        assertFalse(descriptions.any { it.contains("Anna Nagar") || it.contains("Chennai") })
        assertFalse(descriptions.any { it.contains("Ph") || it.contains("Bill No") })
    }

    @Test
    fun readsIndianCommaAmountsAndRupeeSymbol() {
        val bill = BillTextParser.parse(
            """
            KUMAR HARDWARES
            Cement 50 bags     ₹ 18,500.00
            Steel rod          ₹ 1,24,000.00
            Net Amount: Rs. 1,42,500.00
            """.trimIndent(),
        )
        assertEquals(BigDecimal("142500.00"), bill.total)
        assertEquals(listOf("18500.00", "124000.00"), bill.items.map { it.amount.toPlainString() })
    }

    @Test
    fun totalOnTheNextLineIsFound() {
        val bill = BillTextParser.parse(
            """
            ANBU MEDICALS
            Paracetamol 500    35.00
            TOTAL
            35.00
            """.trimIndent(),
        )
        assertEquals(BigDecimal("35.00"), bill.total)
    }

    @Test
    fun tamilTotalIsFound() {
        val bill = BillTextParser.parse(
            """
            லட்சுமி ஸ்டோர்ஸ்
            அரிசி 5 கிலோ   250.00
            மொத்தம்        250.00
            """.trimIndent(),
        )
        assertEquals(BigDecimal("250.00"), bill.total)
        assertTrue(bill.totalFromLabel)
        assertEquals("லட்சுமி ஸ்டோர்ஸ்", bill.merchantName)
    }

    @Test
    fun withoutATotalLabelFallsBackToLargestAmount() {
        val bill = BillTextParser.parse(
            """
            RAJ TEA STALL
            Tea x 4      40.00
            Samosa x 2   30.00
            70.00
            """.trimIndent(),
        )
        assertEquals(BigDecimal("70.00"), bill.total)
        assertFalse(bill.totalFromLabel)
    }

    @Test
    fun unreadableTextGivesEmptyResult() {
        val bill = BillTextParser.parse("~~ ## ..\n  \n")
        assertNull(bill.merchantName)
        assertNull(bill.total)
        assertTrue(bill.items.isEmpty())
    }

    @Test
    fun parseAmountRejectsZeroNegativeAndHugeNumbers() {
        assertEquals(BigDecimal("1250.50"), BillTextParser.parseAmount("1,250.5"))
        assertNull(BillTextParser.parseAmount("0"))
        assertNull(BillTextParser.parseAmount("-5"))
        assertNull(BillTextParser.parseAmount("abc"))
        assertNull(BillTextParser.parseAmount("98765432100"))
    }

    @Test
    fun formatterAddsOtherChargesSoTheTotalIsTraceable() {
        val items = BillTextParser.parse(supermarketBill).items
        val text = BillNotesFormatter.format(items, BigDecimal("599.00"), "Other charges (tax etc.)")
        assertEquals(
            """
            Ponni Rice 5kg – ₹250.00
            Sunflower Oil 1L – ₹180.00
            Toor Dal 1kg – ₹140.00
            Other charges (tax etc.) – ₹29.00
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun cashWithChangeMeansFullyPaid() {
        // Cash 600 for a 599 bill, 1 returned as change.
        assertEquals(BigDecimal("599.00"), BillTextParser.parse(supermarketBill).paid)
    }

    @Test
    fun creditBillWithBalanceDue() {
        val bill = BillTextParser.parse(
            """
            KUMAR HARDWARES
            Date: 20/09/2026
            Customer Name: Ravi Stores
            Cement 50 bags   18,500.00
            Total            18,500.00
            Advance           5,000.00
            Balance Due      13,500.00
            Due Date: 30/10/2026
            """.trimIndent(),
        )
        assertEquals("KUMAR HARDWARES", bill.merchantName)
        assertEquals("Ravi Stores", bill.customerName)
        assertEquals(BigDecimal("18500.00"), bill.total)
        assertEquals(BigDecimal("5000.00"), bill.paid)
        // The bill date, not the due date.
        assertEquals(java.time.LocalDate.of(2026, 9, 20), bill.date)
    }

    @Test
    fun balanceOnlyGivesPaidAsTotalMinusBalance() {
        val bill = BillTextParser.parse("ANBU TRADERS\nTotal 1,000.00\nBalance 400.00")
        assertEquals(BigDecimal("600.00"), bill.paid)
    }

    @Test
    fun zeroBalanceMeansFullyPaid() {
        val bill = BillTextParser.parse("ANBU TRADERS\nTotal 1,000.00\nBalance: 0.00")
        assertEquals(BigDecimal("1000.00"), bill.paid)
    }

    @Test
    fun balanceEqualToTotalMeansNothingPaid() {
        val bill = BillTextParser.parse("ANBU TRADERS\nTotal 1,000.00\nBalance Due 1,000.00")
        assertEquals(BigDecimal("0.00"), bill.paid)
    }

    @Test
    fun upiPaymentBelowTotalIsPartial() {
        val bill = BillTextParser.parse("SELVI STORES\nTotal 800.00\nUPI 300.00")
        assertEquals(BigDecimal("300.00"), bill.paid)
    }

    @Test
    fun noPaymentLinesMeansUnknown() {
        val bill = BillTextParser.parse("SELVI STORES\nRice 250.00\nTotal 250.00")
        assertNull(bill.paid)
        assertNull(bill.customerName)
    }

    @Test
    fun customerNameVariants() {
        assertEquals("Murugan", BillTextParser.parse("SHOP\nName: Murugan 9876543210\nTotal 10.00").customerName)
        assertEquals("Lakshmi Traders", BillTextParser.parse("SHOP\nBill To: Lakshmi Traders\nTotal 10.00").customerName)
        assertEquals("Anbu & Co", BillTextParser.parse("SHOP\nM/s. Anbu & Co\nTotal 10.00").customerName)
        // "Customer Mobile: ..." is not a name.
        assertNull(BillTextParser.parse("SHOP\nCustomer Mobile: 9876543210\nTotal 10.00").customerName)
    }

    @Test
    fun customerLineIsNeverTheShopName() {
        val bill = BillTextParser.parse("Name: Ravi\nTotal 10.00")
        assertNull(bill.merchantName)
        assertEquals("Ravi", bill.customerName)
    }

    @Test
    fun formatterAddsNoExtraLineWhenItemsMatchTheTotal() {
        val items = listOf(BillLineItem("Tea", BigDecimal("40.00")), BillLineItem("Samosa", BigDecimal("30.00")))
        assertEquals(
            "Tea – ₹40.00\nSamosa – ₹30.00",
            BillNotesFormatter.format(items, BigDecimal("70.00"), "Other"),
        )
    }
}
