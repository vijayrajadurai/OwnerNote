package com.shopai.app.data.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockVoiceParserTest {

    // --- STOCK_IN: English / Tanglish / Tamil ---

    @Test
    fun englishStockIn() {
        val result = StockVoiceParser.parse("Received 50 bags cement")
        assertEquals(StockVoiceIntent.STOCK_IN, result?.intent)
        assertEquals(50.0, result!!.quantity, 0.001)
        assertEquals("bags", result.unit)
        assertEquals("cement", result.productName.lowercase())
    }

    @Test
    fun tanglishStockIn() {
        val result = StockVoiceParser.parse("Cement 50 bags vanginen")
        assertEquals(StockVoiceIntent.STOCK_IN, result?.intent)
        assertEquals(50.0, result!!.quantity, 0.001)
        assertEquals("bags", result.unit)
        assertEquals("Cement", result.productName)
    }

    @Test
    fun tanglishStockInVariants() {
        assertEquals(StockVoiceIntent.STOCK_IN, StockVoiceParser.parse("50 bags cement vandhirukku")?.intent)
        assertEquals(StockVoiceIntent.STOCK_IN, StockVoiceParser.parse("Cement 50 bags stock vandhuchu")?.intent)
        assertEquals(StockVoiceIntent.STOCK_IN, StockVoiceParser.parse("Cement 50 bags purchase panninen")?.intent)
        assertEquals(StockVoiceIntent.STOCK_IN, StockVoiceParser.parse("Cement 50 bags add pannu")?.intent)
    }

    @Test
    fun tamilStockIn() {
        val result = StockVoiceParser.parse("சிமெண்ட் 50 பை வாங்கினேன்")
        assertEquals(StockVoiceIntent.STOCK_IN, result?.intent)
        assertEquals(50.0, result!!.quantity, 0.001)
        assertEquals("bags", result.unit)
        assertEquals("சிமெண்ட்", result.productName)
    }

    @Test
    fun tamilStockInVariants() {
        assertEquals(StockVoiceIntent.STOCK_IN, StockVoiceParser.parse("சிமெண்ட் 50 பை வந்திருக்கு")?.intent)
        assertEquals(StockVoiceIntent.STOCK_IN, StockVoiceParser.parse("சிமெண்ட் 50 பை ஸ்டாக் வந்துச்சு")?.intent)
    }

    // --- STOCK_OUT: English / Tanglish / Tamil ---

    @Test
    fun englishStockOut() {
        val result = StockVoiceParser.parse("10 bags cement sold")
        assertEquals(StockVoiceIntent.STOCK_OUT, result?.intent)
        assertEquals(10.0, result!!.quantity, 0.001)
        assertEquals("bags", result.unit)
        assertEquals("cement", result.productName.lowercase())
    }

    @Test
    fun tanglishStockOut() {
        val result = StockVoiceParser.parse("Cement 10 bags sale panniten")
        assertEquals(StockVoiceIntent.STOCK_OUT, result?.intent)
        assertEquals(10.0, result!!.quantity, 0.001)
        assertEquals("bags", result.unit)
        assertEquals("Cement", result.productName)
    }

    @Test
    fun tanglishStockOutVariants() {
        assertEquals(StockVoiceIntent.STOCK_OUT, StockVoiceParser.parse("Cement 10 bags pochu")?.intent)
        assertEquals(StockVoiceIntent.STOCK_OUT, StockVoiceParser.parse("Cement 10 bags stock la irundhu remove pannu")?.intent)
        assertEquals(StockVoiceIntent.STOCK_OUT, StockVoiceParser.parse("10 bags cement poiduchu")?.intent)
    }

    @Test
    fun tamilStockOut() {
        val result = StockVoiceParser.parse("சிமெண்ட் 10 பை விற்றுவிட்டேன்")
        assertEquals(StockVoiceIntent.STOCK_OUT, result?.intent)
        assertEquals(10.0, result!!.quantity, 0.001)
        assertEquals("bags", result.unit)
        assertEquals("சிமெண்ட்", result.productName)
    }

    @Test
    fun tamilStockOutVariant() {
        assertEquals(StockVoiceIntent.STOCK_OUT, StockVoiceParser.parse("சிமெண்ட் 10 பை போயிடுச்சு")?.intent)
    }

    // --- Quantity / unit edge cases ---

    @Test
    fun missingQuantityReturnsNull() {
        assertNull(StockVoiceParser.parse("Cement bags vanginen"))
    }

    @Test
    fun missingUnitStillParsesQuantityAndProduct() {
        val result = StockVoiceParser.parse("Cement 50 add pannu")
        assertEquals(StockVoiceIntent.STOCK_IN, result?.intent)
        assertEquals(50.0, result!!.quantity, 0.001)
        assertNull(result.unit)
        assertEquals("Cement", result.productName)
    }

    @Test
    fun englishNumberWordsParsed() {
        val result = StockVoiceParser.parse("Cement fifty bags vanginen")
        assertEquals(50.0, result!!.quantity, 0.001)
    }

    @Test
    fun tamilNumberWordsParsed() {
        val result = StockVoiceParser.parse("சிமெண்ட் ஐம்பது பை வாங்கினேன்")
        assertEquals(50.0, result!!.quantity, 0.001)
    }

    @Test
    fun zeroQuantityReturnsNull() {
        assertNull(StockVoiceParser.parse("Cement 0 bags vanginen"))
    }

    @Test
    fun negativeQuantityReturnsNull() {
        assertNull(StockVoiceParser.parse("Cement -5 bags vanginen"))
    }

    @Test
    fun missingProductNameReturnsNull() {
        // Everything is consumed by quantity/unit/keyword, nothing left for a product name.
        assertNull(StockVoiceParser.parse("50 bags vanginen"))
    }

    // --- Regression: must never steal non-stock-mutation phrasing ---

    @Test
    fun stockQueryPhrasesAreNeverCaptured() {
        assertNull(StockVoiceParser.parse("Cement evlo irukku?"))
        assertNull(StockVoiceParser.parse("cement stock evlo irukku"))
        assertNull(StockVoiceParser.parse("Innaiku cement evlo use aachu?"))
    }

    @Test
    fun customerFinancialQuestionsAreNeverCaptured() {
        assertNull(StockVoiceParser.parse("Kumar-ku evlo kudukanum?"))
        assertNull(StockVoiceParser.parse("Yaaru kitta evlo vaanga irukku?"))
    }

    @Test
    fun ambiguousBothIntentsReturnsNull() {
        // Contains both an IN and an OUT keyword — never guess.
        assertNull(StockVoiceParser.parse("Cement 50 bags vanginen, 10 bags sale panniten"))
    }

    @Test
    fun futureDatedWordsDoNotBreakQuantityParsing() {
        // No date parsing exists (documented limitation) — the date-like word
        // is simply left as part of the unmatched leftover text, and the real
        // quantity is still parsed correctly.
        val result = StockVoiceParser.parse("Cement 50 bags vanginen nalaikku")
        assertEquals(50.0, result!!.quantity, 0.001)
        assertTrue(result.productName.contains("Cement"))
    }
}
