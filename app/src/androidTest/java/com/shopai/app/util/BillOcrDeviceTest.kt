package com.shopai.app.util

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal

/**
 * Runs real bill photos through the same path as the camera button:
 * sampled decode → on-device Tesseract → BillTextParser.
 */
@RunWith(AndroidJUnit4::class)
class BillOcrDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext
    private val recognizer = DeviceTextRecognizer(appContext)

    @After
    fun tearDown() = recognizer.release()

    private fun read(assetName: String): Pair<String, ExtractedBill> = runBlocking {
        val file = File(appContext.cacheDir, "ocr_test_$assetName".replace('/', '_'))
        instrumentation.context.assets.open(assetName).use { input -> file.outputStream().use { input.copyTo(it) } }
        val result = recognizer.recognizeFromUri(Uri.fromFile(file))
        file.delete()
        Log.i(TAG, "---- OCR text for $assetName ----\n${result.text}\n---- end ----")
        val bill = BillTextParser.parse(result.text)
        Log.i(TAG, "parsed: shop=${bill.merchantName} total=${bill.total} fromLabel=${bill.totalFromLabel} date=${bill.date} customer=${bill.customerName} paid=${bill.paid}")
        result.text to bill
    }

    @Test
    fun sriBalajiHardwaresGrandTotal() {
        val (_, bill) = read("bills/sri_balaji_hardwares.jpg")
        assertEquals(BigDecimal("31930.00"), bill.total)
    }

    private companion object {
        const val TAG = "BillOcrTest"
    }
}
