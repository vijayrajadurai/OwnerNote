package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A product photo's label (on-device OCR text) → the editable product form. */
class ProductLabelReaderTest {

    @Test
    fun toothpasteLabel() {
        val label = ProductLabelReader.read(
            """
            Colgate
            STRONG TEETH
            Toothpaste
            Net Wt. 200 g
            MRP Rs. 110.00 (Incl. of all taxes)
            Mfd: 08/2026
            """.trimIndent(),
        )
        assertEquals("Colgate", label.name)
        assertEquals("Colgate", label.brand)
        assertEquals("Strong Teeth", label.variant)
        assertEquals("200 g", label.weight)
        assertEquals("Personal Care", label.category)
    }

    @Test
    fun brandFromTwoWordsAndLitres() {
        val label = ProductLabelReader.read("SURF EXCEL\nEasy Wash\nDetergent Powder\n1 kg\nMRP 140")
        assertEquals("Surf Excel", label.brand)
        assertEquals("Easy Wash", label.variant)
        assertEquals("1 kg", label.weight)
        assertEquals("Home Care", label.category)

        val oil = ProductLabelReader.read("Gold Winner\nRefined Sunflower Oil\n1 L Pouch")
        assertEquals("Gold Winner", oil.brand)
        assertEquals("1 L", oil.weight)
        assertEquals("Groceries", oil.category)
    }

    @Test
    fun unknownBrandStillFillsAName() {
        val label = ProductLabelReader.read("SRI MURUGAN\nAppalam\n100 g\nPack of 10")
        assertEquals("Sri Murugan", label.name)
        assertNull(label.brand)
        assertEquals("100 g", label.weight)
        assertEquals(10, label.packCount)
    }

    @Test
    fun nothingReadableLeavesItEmpty() {
        val label = ProductLabelReader.read("12 34\n—")
        assertNull(label.name)
        assertNull(label.brand)
        assertNull(label.weight)
    }
}
