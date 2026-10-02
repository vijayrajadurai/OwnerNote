package com.shopai.app.books

import com.shopai.app.books.engine.AdjustmentReasonText
import com.shopai.app.books.engine.HsnImport
import com.shopai.app.books.model.AdjustmentReason
import com.shopai.app.util.Csv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BooksImportFormatsTest {
    @Test
    fun csvHandlesQuotesCommasAndLineEndings() {
        val rows = Csv.parse("﻿code,desc\r\n1006,\"Rice, broken\"\n0713,\"Say \"\"dal\"\"\"\r\n\r\n")
        assertEquals(listOf(listOf("code", "desc"), listOf("1006", "Rice, broken"), listOf("0713", "Say \"dal\"")), rows)
    }

    @Test
    fun hsnListImportSkipsHeaderAndBadRowsAndNeverInventsRates() {
        val csv = """
            HSN Code,Type,Description,GST %,Cess
            1006,HSN,Rice,5,
            998314,,IT design services,18%,
            12,HSN,Too short,5,
            1006,HSN,,5,
            0713,,Pulses,,
        """.trimIndent()
        val parsed = HsnImport.parse(csv, "test", now = 1)
        assertEquals(listOf("1006", "998314", "0713"), parsed.rows.map { it.code })
        assertEquals(listOf("HSN", "SAC", "HSN"), parsed.rows.map { it.kind })
        assertEquals(500, parsed.rows[0].gstBp)
        assertEquals(1800, parsed.rows[1].gstBp)
        assertNull(parsed.rows[2].gstBp)
        assertEquals(2, parsed.skipped)
    }

    @Test
    fun stockReasonsInEnglishAndTamil() {
        assertEquals(AdjustmentReason.DAMAGED, AdjustmentReasonText.from("Bag damaged in rain"))
        assertEquals(AdjustmentReason.EXPIRED, AdjustmentReasonText.from("expiry"))
        assertEquals(AdjustmentReason.MISSING, AdjustmentReasonText.from("காணவில்லை"))
        assertEquals(AdjustmentReason.PHYSICAL_COUNT, AdjustmentReasonText.from("stock count correction"))
        assertEquals(AdjustmentReason.OTHER, AdjustmentReasonText.from("gave to temple"))
    }
}
