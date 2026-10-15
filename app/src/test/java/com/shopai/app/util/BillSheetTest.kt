package com.shopai.app.util

import com.shopai.app.brain.tools.ProductRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A purchase bill uploaded as a file (owner, 15 Oct 2026): the same invoice as an Excel sheet and as a CSV export reads as
 * its photo does — Kumar Traders the party, nothing paid, the 12 rows matched to the shop's stock in its own units.
 */
class BillSheetTest {
    private val rows: List<List<String>> = listOf(
        listOf("SRI LAKSHMI SUPER MART"),
        listOf("PURCHASE INVOICE"),
        listOf("Invoice No", "P/2024-25/0786"),
        listOf("Invoice Date", "15-03-2025"),
        listOf("Payment Terms", "Credit (15 Days)"),
        listOf("SUPPLIER DETAILS"),
        listOf("Kumar Traders"),
        listOf("BILL TO"),
        listOf("Sri Lakshmi Super Mart"),
        listOf("S.No", "Item Name", "Brand", "HSN Code", "Qty", "Unit", "Rate", "Amount"),
        listOf("1", "Rice Ponni", "Aachi", "100630", "10", "Bag (25 kg)", "1350", "13500"),
        listOf("2", "Toor Dal", "Aachi", "071335", "20", "Kg", "98", "1960"),
        listOf("3", "Sunflower Oil", "Fortune", "151219", "10", "Ltr", "152", "1520"),
        listOf("4", "Tea Powder", "Tata Tea", "090230", "5", "Kg", "420", "2100"),
        listOf("", "", "", "", "", "", "Subtotal", "19080"),
        listOf("", "", "", "", "", "", "CGST @ 9%", "1717.2"),
        listOf("", "", "", "", "", "", "SGST @ 9%", "1717.2"),
        listOf("", "", "", "", "", "", "Grand Total", "22514.4"),
    )
    private val products = listOf("Ponni Rice" to "KG", "Toor Dal" to "KG", "Sunflower Oil" to "LITRE", "Tea Powder" to "KG")
        .mapIndexed { i, (n, u) -> ProductRef("p$i", n, u, BigDecimal("10"), if (n == "Ponni Rice") mapOf("BAG" to BigDecimal("25")) else emptyMap()) }

    /** The rows as Excel writes them: shared strings for text, numbers as numbers (a float's noise and all). */
    private fun xlsx(rows: List<List<String>>): ByteArray {
        val strings = mutableListOf<String>()
        fun cell(col: Int, row: Int, v: String): String {
            val ref = ('A' + col).toString() + (row + 1)
            if (v.isEmpty()) return ""
            val num = v.toBigDecimalOrNull()
            return if (num != null && !v.startsWith("0")) {
                val raw = if (v == "1717.2") "1717.2000000000001" else v
                """<c r="$ref"><v>$raw</v></c>"""
            } else {
                strings += v
                """<c r="$ref" t="s"><v>${strings.size - 1}</v></c>"""
            }
        }
        val sheet = rows.mapIndexed { r, cells -> """<row r="${r + 1}">${cells.mapIndexed { c, v -> cell(c, r, v) }.joinToString("")}</row>""" }.joinToString("")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            z.write("""<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>$sheet</sheetData></worksheet>""".toByteArray())
            z.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            z.write("""<?xml version="1.0" encoding="UTF-8"?><sst>${strings.joinToString("") { "<si><t>${it.replace("&", "&amp;")}</t></si>" }}</sst>""".toByteArray())
        }
        return out.toByteArray()
    }

    private fun check(text: String) {
        val b = BillTextParser.parse(text)
        assertEquals(text, "Kumar Traders", b.merchantName)
        assertEquals(text, 0, BigDecimal("22514.40").compareTo(b.total))
        assertEquals(text, 0, b.paid!!.signum())
        assertEquals(TxnDirection.DEBIT, BillDirection.decide(text, b, MyBusiness(null, null, null)).direction)
        val lines = BillStock.lines(b.items, products)
        assertEquals(text, 4, lines.size)
        assertTrue(lines.joinToString("\n") { "${it.itemName} → ${it.product?.name} ${it.qty} ${it.problem}" }, lines.all { it.sure })
        assertEquals(0, BigDecimal("250").compareTo(lines.first { it.product?.name == "Ponni Rice" }.qty))
    }

    @Test fun anExcelBillReadsAsItsPhoto() {
        val read = BillSheet.xlsxRows(xlsx(rows))!!
        assertEquals("1717.2", read.first { it.contains("CGST @ 9%") }.last())
        check(BillSheet.text(read))
    }

    @Test fun aCsvBillReadsAsItsPhoto() {
        val csv = rows.joinToString("\r\n") { r -> r.joinToString(",") { c -> if (c.contains(',') || c.contains('(')) "\"$c\"" else c } }
        check(BillSheet.text(BillSheet.csvRows("﻿" + csv)))
    }

    @Test fun theKindOfFile() {
        assertEquals(BillSheet.Kind.PDF, BillSheet.kindOf("application/pdf", "bill.pdf"))
        assertEquals(BillSheet.Kind.XLSX, BillSheet.kindOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null))
        assertEquals(BillSheet.Kind.CSV, BillSheet.kindOf("text/csv", "bill.csv"))
        assertEquals(BillSheet.Kind.XLS, BillSheet.kindOf("application/vnd.ms-excel", "old.xls"))
        assertEquals(BillSheet.Kind.IMAGE, BillSheet.kindOf("image/jpeg", null))
        // Not a workbook (a renamed file, a DOCTYPE trick): nothing read, nothing guessed.
        assertNull(BillSheet.xlsxRows("not a zip".toByteArray()))
    }
}
