package com.shopai.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * 20 shop bills read by the app's own Tesseract passes (src/test/resources/bills/scanned-20.txt): GST invoices
 * (CGST/SGST, IGST, several rates, many items, discount), thermal receipts, kirana, estimate, medical, hardware,
 * restaurant, credit, fuel and Tamil bills, a tilted shadowed photo, a low-res receipt and a sideways photo.
 * Every field the bill prints must be read as printed; a total not read from a Total line is never READY.
 */
class BillScannedBillsTest {

    private data class Case(val bill: String, val pass: String, val want: Map<String, String>, val text: String)

    private fun cases(): List<Case> {
        val raw = javaClass.getResourceAsStream("/bills/scanned-20.txt")!!.bufferedReader().readText()
        return raw.split(Regex("""(?m)^=== """)).drop(1).map { block ->
            val lines = block.lines()
            val (bill, pass) = lines[0].split(" | ").let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            val want = lines[1].removePrefix("want:").split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            Case(bill, pass, want, lines.drop(2).joinToString("\n").trim())
        }
    }

    private fun money(a: BigDecimal?, b: String) = a != null && (a - BigDecimal(b)).abs() <= BigDecimal("0.01")

    @Test
    fun everyScannedBillReadsAsPrinted() {
        val failures = mutableListOf<String>()
        val all = cases()
        assertTrue("fixture has the 20 bills", all.map { it.bill }.distinct().size == 20)
        for (c in all) {
            val b = BillTextParser.parse(c.text)
            fun check(field: String, ok: Boolean, got: Any?) { if (!ok) failures += "${c.bill} (${c.pass}) $field: want ${c.want[field]} got $got" }
            for ((field, want) in c.want) when (field) {
                "total" -> check(field, money(b.total, want), b.total)
                "tax" -> check(field, money(b.tax, want), b.tax)
                "cgst" -> check(field, money(b.gst?.cgst, want), b.gst?.cgst)
                "sgst" -> check(field, money(b.gst?.sgst, want), b.gst?.sgst)
                "igst" -> check(field, money(b.gst?.igst, want), b.gst?.igst)
                "taxable" -> check(field, money(b.taxableValue, want), b.taxableValue)
                "discount" -> check(field, money(b.discount, want), b.discount)
                "roundOff" -> check(field, money(b.roundOff, want), b.roundOff)
                "date" -> check(field, b.date?.toString() == want, b.date)
                "invoice" -> check(field, b.invoiceNumber.equals(want, ignoreCase = true), b.invoiceNumber)
                "sellerGstin" -> check(field, b.sellerGstin?.value == want, b.sellerGstin)
                "buyerGstin" -> check(field, b.buyerGstin?.value == want, b.buyerGstin)
                "merchant" -> check(field, b.merchantName?.let { want.contains(it, true) || it.contains(want, true) } == true, b.merchantName)
                "customer" -> check(field, b.customerName.equals(want, ignoreCase = true), b.customerName)
                "items" -> check(field, b.items.size == want.toInt(), b.items.map { "${it.description} ${it.amount}" })
                // The amount came from no Total line: offered, flagged, never READY.
                "guessed" -> check(field, !b.totalFromLabel && b.check.status != BillStatus.READY && b.check.has(BillIssue.TOTAL_GUESSED), "${b.totalFromLabel} ${b.check}")
            }
            // A bill number / invoice number is never read as money.
            b.invoiceNumber?.filter(Char::isDigit)?.takeIf { it.length >= 3 }?.let { n ->
                check("total≠bill no", b.total?.stripTrailingZeros()?.toPlainString() != n, b.total)
                check("item≠bill no", b.items.none { it.amount.stripTrailingZeros().toPlainString() == n }, b.items.map { it.amount })
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
