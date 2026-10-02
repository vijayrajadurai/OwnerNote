package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * GST tax invoices print many amounts: taxable value, CGST, SGST, IGST,
 * round off, a tax summary table with its own "Total" row, the tax in words
 * and the grand total. The bill total must be the grand total.
 */
class BillGstInvoiceTest {
    private fun rupees(v: String) = BigDecimal(v).setScale(2)

    // OCR text of the Indo Burma Agencies Tally invoice (layout as Tesseract
    // reads it: right-hand header column merged into the left lines).
    private fun indoBurma(totalWords: String = "INR Fourteen Thousand One Hundred Forty Eight Only", totalLine: String = "Total ₹ 14,148.00") = """
        Tax Invoice (ORIGINAL FOR RECIPIENT)
        Indo Burma Agencies Invoice No. Dated
        No.159(105),Near LIC & SBI, 344 29-Oct-25
        Anna Salai, Delivery Note Mode/Terms of Payment
        Chennai - 600 002 575
        GSTIN/UIN: 33AAAFI0207R1ZJ Reference No. & Date. Other References
        State Name : Tamil Nadu, Code : 33
        Contact : 044 - 28600538,28606739,+91-9791040538 Buyer's Order No. Dated
        E-Mail : myiba1978@gmail.com
        Buyer (Bill to) Dispatch Doc No. Delivery Note Date
        SAMPATHI CREDITS PRIVATE LIMITED 29-Oct-25
        NO.12TH FLOOR, BASCON FUTURA SV IT PARK, Dispatched through Destination
        PARTHASARATHIPURAM, TNAGAR,
        VENKATANARAYANA ROAD, CHENNAI -600 017.
        GSTIN/UIN : 33AAACM4598E1ZI Terms of Delivery
        State Name : Tamil Nadu, Code : 33
        Sl Description of Goods HSN/SAC GST Rate Quantity Rate per Amount
        1 Odonil Room Freshner 33074900 18 % 10 NOS 130.00 NOS 1,300.00
        2 Godrej Aer Pocket 33074900 18 % 18 NOS 55.08 NOS 991.44
        3 SCENT SMALL 3303 18 % 10 BTL 42.00 BTL 420.00
        4 ODANIL AIR FRESHER 50G 3307 18 % 15 NOS 50.00 NOS 750.00
        5 Dishwash Gel 1 Kg 3401 18 % 7 PKT 135.00 PKT 945.00
        6 TOILET ROLL 4818 18 % 100 NOS 25.00 NOS 2,500.00
        7 TISSUE NAPKIN FORTUNE 48183000 18 % 60 NOS 25.00 NOS 1,500.00
        8 KITCHEN TOWEL 2 IN 1 6302 18 % 3 PKT 120.00 PKT 360.00
        9 URINERY CUBE 3307 18 % 12 NOS 50.00 NOS 600.00
        10 CHECKED CLOTH MEDIUM 5901 5 % 12 NOS 10.00 NOS 120.00
        11 PLASTO DUSTBIN EXTRA LARGE BLACK 3923 18 % 12 NOS 110.00 NOS 1,320.00
        12 RORITO WHITE BOARD MARKER HD BLUE 9608 18 % 20 NOS 20.00 NOS 400.00
        13 SCRIBLING PAD NO 3 - 40 R 4820 18 % 30 NOS 20.00 NOS 600.00
        14 WALL HOOK 250ML 3402 18 % 4 NOS 49.15 NOS 196.60
        12,003.04
        CGST 1,072.47
        SGST 1,072.47
        Round Off 0.02
        $totalLine
        Amount Chargeable (in words) E. & O.E
        $totalWords
        Taxable CGST SGST/UTGST Total
        Value Rate Amount Rate Amount Tax Amount
        11,883.04 9% 1,069.47 9% 1,069.47 2,138.94
        120.00 2.50% 3.00 2.50% 3.00 6.00
        Total: 12,003.04 1,072.47 1,072.47 2,144.94
        Tex Amount (in words) : INR Two Thousand One Hundred Forty Four and Ninety Four paise Only
        Declaration Company's Bank Details
        We declare that this invoice shows the actual price of the goods Bank Name : SBI, ANNASALAI BRANCH
        described and that all particulars are true and correct. A/c No. : 31584863314
        Customer's Seal and Signature for Indo Burma Agencies
        SUBJECT TO CHENNAI JURISDICTION
        This is a Computer Generated Invoice
    """.trimIndent()

    @Test
    fun indoBurmaInvoiceTotalIsTheGrandTotalNotTheTax() {
        val bill = BillTextParser.parse(indoBurma())
        assertEquals(rupees("14148.00"), bill.total)
        assertTrue(bill.totalFromLabel)
        assertEquals(LocalDate.of(2025, 10, 29), bill.date)
        assertEquals("SAMPATHI CREDITS PRIVATE LIMITED", bill.customerName)
        assertTrue(bill.merchantName.orEmpty(), bill.merchantName.orEmpty().startsWith("Indo Burma Agencies"))
        // No paid amount printed: none invented.
        assertNull(bill.paid)
    }

    @Test
    fun grandTotalWinsEvenWhenItsWordsAreGarbled() {
        // OCR mangled the total in words: only the tax in words is readable.
        val bill = BillTextParser.parse(indoBurma(totalWords = "INR Fcurteem Thausand Onc Hunderd Forty Eihgt Only"))
        assertEquals(rupees("14148.00"), bill.total)
    }

    @Test
    fun grandTotalWinsWhenItsAmountIsOnTheNextLine() {
        val bill = BillTextParser.parse(indoBurma(totalLine = "Total\n% 14,148.00"))
        assertEquals(rupees("14148.00"), bill.total)
    }

    @Test
    fun grandTotalWinsWithNoWordsAndARupeeSymbolMisread() {
        val bill = BillTextParser.parse(indoBurma(totalWords = "", totalLine = "Total = 14,148.00"))
        assertEquals(rupees("14148.00"), bill.total)
    }

    @Test
    fun taxAmountInWordsIsNeverTheTotal() {
        assertEquals(
            null,
            AmountInWords.find(listOf("Tax Amount (in words) : INR Two Thousand One Hundred Forty Four and Ninety Four paise Only")),
        )
        assertEquals(
            rupees("14148.00"),
            AmountInWords.find(
                listOf(
                    "Amount Chargeable (in words) INR Fourteen Thousand One Hundred Forty Eight Only",
                    "Tax Amount (in words) : INR Two Thousand One Hundred Forty Four and Ninety Four paise Only",
                ),
            ),
        )
        // A total "incl. GST" written in words is still the total.
        assertEquals(rupees("500.00"), AmountInWords.find(listOf("Total (incl. GST) Rupees Five Hundred Only")))
    }

    @Test
    fun igstInvoice() {
        val bill = BillTextParser.parse(
            """
            KAVERI TRADERS
            GSTIN: 29ABCDE1234F1Z5
            Invoice No: 88 Date: 12/08/2025
            Bill To: Sri Lakshmi Stores
            Rice 25kg 2 1,250.00 2,500.00
            Oil 15L 1 2,300.00 2,300.00
            Sugar 50kg 1 5,200.00 5,200.00
            Taxable Value 10,000.00
            IGST @18% 1,800.00
            Grand Total 11,800.00
            Tax Amount (in words): Rupees One Thousand Eight Hundred Only
            """.trimIndent(),
        )
        assertEquals(rupees("11800.00"), bill.total)
        assertEquals("Sri Lakshmi Stores", bill.customerName)
    }

    @Test
    fun retailGstBillWithTotalTaxLine() {
        val bill = BillTextParser.parse(
            """
            ANNAI SUPER MARKET
            Date: 05/09/2025
            Biscuits 4 25.00 100.00
            Soap 5 180.00 900.00
            Sub Total 1,000.00
            CGST 2.5% 25.00
            SGST 2.5% 25.00
            Total Tax 50.00
            Total 1,050.00
            Cash 1,100.00
            Change 50.00
            """.trimIndent(),
        )
        assertEquals(rupees("1050.00"), bill.total)
        assertEquals(rupees("1050.00"), bill.paid)
    }

    @Test
    fun multipleTotalsWithDiscountNoGstLines() {
        val bill = BillTextParser.parse(
            """
            NEW MODERN HARDWARES
            Date: 10-07-2025
            Hammer 2 300.00 600.00
            Paint 4L 1 1,800.00 1,800.00
            Total Qty 3
            Sub Total 2,400.00
            Discount 100.00
            Grand Total 2,300.00
            """.trimIndent(),
        )
        assertEquals(rupees("2300.00"), bill.total)
    }

    @Test
    fun tillTotalRowWithItemAndQtyCountsIsUnaffected() {
        val bill = BillTextParser.parse(
            """
            CHENNAI FRESH MART
            Date: 01/09/2025
            Tomato 2 40.00 80.00
            Onion 3 390.00 1,170.00
            TOTAL 5 12 1,250.00
            """.trimIndent(),
        )
        assertEquals(rupees("1250.00"), bill.total)
    }

    @Test
    fun taxSummaryTableWithoutSeparateTaxLinesStillAvoidsTheTaxTotal() {
        // No CGST/SGST lines (OCR lost them); the summary "Total:" row must be skipped.
        val bill = BillTextParser.parse(
            """
            ARUN AGENCIES
            Date: 02-Oct-25
            Item A 1 5,000.00 5,000.00
            Total ₹ 5,900.00
            Taxable CGST SGST Total
            Total: 5,000.00 450.00 450.00 900.00
            """.trimIndent(),
        )
        assertEquals(rupees("5900.00"), bill.total)
    }
}
