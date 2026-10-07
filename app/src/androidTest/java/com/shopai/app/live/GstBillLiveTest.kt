package com.shopai.app.live

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shopai.app.ui.screens.BillEntryState
import com.shopai.app.ui.screens.BillField
import com.shopai.app.util.BillStatus
import com.shopai.app.util.BillTextParser
import com.shopai.app.util.DeviceTextRecognizer
import com.shopai.app.util.ExtractedBill
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal

/**
 * Live GST bill OCR test on a device / emulator (Pixel 8, OnePlus …).
 *
 * The 50 sample GST invoices (assets/gst/text_invoices.json, with ground truth)
 * are printed into bill photos on the device, each with a photo problem —
 * clean, tilted 3°, sideways 90°, dark, shadow, blur, low resolution, heavy
 * JPEG — and read through the Bill Scanner's own path:
 *
 *   JPEG file → DeviceTextRecognizer(printedBills = true) → BillTextParser
 *   → BillValidation → BillEntryState (the review form's Save gate)
 *
 * Then 60 consecutive scans in one process check for crashes and memory growth.
 * Nothing is saved anywhere: no ledger, no stock.
 *
 * Results: Android/data/<app>/files/ocr-live/results.json + summary.txt + the
 * rendered images; the images are also put in the gallery (Pictures/OwnerNote-GST-Samples)
 * for a live check in the app (Bill Scanner → Gallery).
 *
 * Only SAFETY fails the test (a wrong tax shown, a wrong total marked READY,
 * a crash). Accuracy is measured and reported, never faked.
 */
@RunWith(AndroidJUnit4::class)
class GstBillLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val outDir = File(app.getExternalFilesDir(null), "ocr-live").apply { deleteRecursively(); mkdirs() }

    private enum class Photo { CLEAN, TILT_3, SIDEWAYS_90, DARK, SHADOW, BLUR, LOW_RES, JPEG_30 }

    private data class Truth(
        val total: String, val tax: String, val taxReadable: Boolean, val totalReadable: Boolean,
        val buyer: String?, val sellerGstin: String, val items: List<Pair<String, String>>,
    )

    private fun dataset(): List<Triple<String, String, Truth>> {
        val json = instrumentation.context.assets.open("gst/text_invoices.json").bufferedReader().readText()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.getJSONObject("truth")
            val items = t.getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONArray(it).getString(0) to a.getJSONArray(it).getString(1) } }
            Triple(
                o.getString("id"), o.getString("text"),
                Truth(
                    t.getString("total"), t.getString("tax"), t.getBoolean("taxReadable"), t.getBoolean("totalReadable"),
                    t.optString("buyer").takeIf { !t.isNull("buyer") && it.isNotEmpty() }, t.getString("sellerGstin"), items,
                ),
            )
        }
    }

    // ------------------------------------------------------------------ rendering a bill photo

    /** The invoice text printed on a white A5-ish page, the way a shop's printer would. */
    private fun render(text: String): Bitmap {
        val lines = text.lines()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val width = 1500
        val lineH = 44
        val height = maxOf(900, 120 + lines.size * lineH)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        lines.forEachIndexed { i, line -> c.drawText(line, 60f, 90f + i * lineH, paint) }
        return bmp
    }

    private fun distort(src: Bitmap, photo: Photo): Bitmap = when (photo) {
        Photo.CLEAN -> src.copy(Bitmap.Config.ARGB_8888, false)
        Photo.TILT_3 -> rotate(src, 3f)
        Photo.SIDEWAYS_90 -> rotate(src, 90f)
        Photo.DARK -> filter(src, 0.55f, 0f)
        Photo.SHADOW -> shadow(src)
        Photo.BLUR -> Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, src.width / 3, src.height / 3, true), src.width, src.height, true)
        Photo.LOW_RES -> Bitmap.createScaledBitmap(src, src.width / 2, src.height / 2, true)
        Photo.JPEG_30 -> src.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun rotate(src: Bitmap, deg: Float): Bitmap {
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)
        // Rotation leaves transparent corners: put the page on a grey table.
        val bg = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        Canvas(bg).apply { drawColor(Color.rgb(170, 170, 170)); drawBitmap(out, 0f, 0f, null) }
        out.recycle()
        return bg
    }

    private fun filter(src: Bitmap, scale: Float, add: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val m = ColorMatrix(floatArrayOf(scale, 0f, 0f, 0f, add, 0f, scale, 0f, 0f, add, 0f, 0f, scale, 0f, add, 0f, 0f, 0f, 1f, 0f))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    private fun shadow(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, out.width.toFloat(), out.height.toFloat(),
                Color.argb(170, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        Canvas(out).drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), p)
        return out
    }

    private fun saveJpeg(bmp: Bitmap, name: String, quality: Int): File {
        val f = File(outDir, "$name.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return f
    }

    /** A copy in the phone's gallery, for picking in the app's Bill Scanner by hand. */
    private fun toGallery(file: File) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OwnerNote-GST-Samples")
            }
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            app.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    // ------------------------------------------------------------------ the test

    @Test
    fun gstBillsLive() = runBlocking {
        val data = dataset()
        val recognizer = DeviceTextRecognizer(app, printedBills = true)
        val photos = Photo.values()
        val results = JSONArray()
        var totalOk = 0; var taxOk = 0; var buyerOk = 0; var itemsOk = 0; var gstinOk = 0
        var wrongTaxShown = 0; var readyButWrongTotal = 0; var savableWithoutCheckWhenWrong = 0
        val perPhoto = photos.associateWith { intArrayOf(0, 0) } // [total ok, count]
        val timesMs = mutableListOf<Long>()
        val nativeKb = mutableListOf<Long>()
        val safety = mutableListOf<String>()

        try {
            data.forEachIndexed { i, (id, text, truth) ->
                val photo = photos[i % photos.size]
                val page = render(text)
                val img = distort(page, photo)
                page.recycle()
                val file = saveJpeg(img, "${id}_${photo.name.lowercase()}", if (photo == Photo.JPEG_30) 30 else 92)
                img.recycle()
                if (i < 16) toGallery(file)

                val t0 = SystemClock.elapsedRealtime()
                val ocr = recognizer.recognizeFromUri(Uri.fromFile(file))
                val ms = SystemClock.elapsedRealtime() - t0
                timesMs += ms
                val bill = BillTextParser.parse(ocr.text)
                nativeKb += Debug.getNativeHeapAllocatedSize() / 1024

                // The review form exactly as the Bill Scanner fills it.
                val form = BillEntryState().apply { applyScan(bill, serverName = null, serverAmount = null, ocrConfidence = ocr.meanConfidence) }

                val tOk = if (truth.totalReadable) bill.totalFromLabel && bill.total?.toPlainString() == truth.total else !bill.totalFromLabel
                val taxShown = if (form.taxNeedsCheck) null else bill.tax?.toPlainString()
                val xOk = if (truth.taxReadable) taxShown == truth.tax else taxShown == null
                val bOk = bill.customerName == truth.buyer
                val iOk = bill.items.map { it.description to it.amount.toPlainString() } == truth.items
                val gOk = bill.sellerGstin?.value == truth.sellerGstin || !truth.totalReadable
                if (tOk) totalOk++; if (xOk) taxOk++; if (bOk) buyerOk++; if (iOk) itemsOk++; if (gOk) gstinOk++
                perPhoto.getValue(photo).let { it[0] += if (tOk) 1 else 0; it[1]++ }

                // Safety: a wrong value must never reach the owner as "read correctly".
                if (taxShown != null && taxShown != truth.tax) { wrongTaxShown++; safety += "$id ${photo}: tax shown $taxShown, truth ${truth.tax}" }
                val wrongTotalFilled = form.total.isNotBlank() && BillTextParser.parseAmount(form.total)?.toPlainString() != truth.total
                if (wrongTotalFilled && bill.check.status == BillStatus.READY) { readyButWrongTotal++; safety += "$id ${photo}: wrong total ${form.total} marked READY" }
                if (wrongTotalFilled && BillField.TOTAL !in form.toVerify) savableWithoutCheckWhenWrong++

                val strict = tOk && xOk && bOk && iOk && gOk
                results.put(JSONObject().apply {
                    put("id", id); put("photo", photo.name); put("ms", ms); put("ocrConfidence", ocr.meanConfidence)
                    put("result", if (strict) "PASS" else "FAIL")
                    put("total", if (tOk) "ok" else "got=${bill.total} fromLabel=${bill.totalFromLabel} truth=${truth.total}")
                    put("tax", if (xOk) "ok" else "shown=$taxShown truth=${truth.tax} readable=${truth.taxReadable}")
                    put("buyer", if (bOk) "ok" else "got=${bill.customerName} truth=${truth.buyer}")
                    put("items", if (iOk) "ok" else "got=${bill.items.map { it.description + "=" + it.amount }}")
                    put("gstin", if (gOk) "ok" else "got=${bill.sellerGstin?.raw}")
                    put("status", bill.check.status.name); put("issues", JSONArray(bill.check.issues.map { it.name }))
                    put("totalHeldForCheck", BillField.TOTAL in form.toVerify)
                    put("ocrText", ocr.text)
                })
                Log.i(TAG, "$id ${photo.name} ${if (strict) "PASS" else "FAIL"} ${ms}ms status=${bill.check.status} total=${bill.total} tax=$taxShown")
            }

            // 60 consecutive scans in one process: no crash, no stale data, memory must not keep climbing.
            val repeatFile = File(outDir, "repeat.jpg")
            val repeatBills = data.take(6)
            val repeatNative = mutableListOf<Long>()
            for (k in 0 until 60) {
                val (rid, rtext, rtruth) = repeatBills[k % repeatBills.size]
                val page = render(rtext)
                repeatFile.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                page.recycle()
                val bill = BillTextParser.parse(recognizer.recognizeFromUri(Uri.fromFile(repeatFile)).text)
                if (bill.total?.toPlainString() != rtruth.total) safety += "repeat #$k ($rid): total ${bill.total} (stale or misread)"
                System.gc()
                repeatNative += Debug.getNativeHeapAllocatedSize() / 1024
            }
            val firstTen = repeatNative.take(10).average()
            val lastTen = repeatNative.takeLast(10).average()

            val n = data.size
            fun pct(x: Int) = "%.1f %%".format(100.0 * x / n)
            val summary = buildString {
                appendLine("GST BILL LIVE OCR TEST — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Bills: $n  (photo kinds: ${photos.joinToString { it.name }})")
                appendLine("Strict PASS: ${(0 until results.length()).count { results.getJSONObject(it).getString("result") == "PASS" }} / $n")
                appendLine("Grand total: $totalOk/$n (${pct(totalOk)})")
                appendLine("GST tax (correct, or withheld when unreadable): $taxOk/$n (${pct(taxOk)})")
                appendLine("Buyer: $buyerOk/$n (${pct(buyerOk)})   Items exact: $itemsOk/$n (${pct(itemsOk)})   Seller GSTIN: $gstinOk/$n (${pct(gstinOk)})")
                appendLine("Grand total by photo: " + perPhoto.entries.joinToString { "${it.key}=${it.value[0]}/${it.value[1]}" })
                appendLine("SAFETY: wrong tax shown=$wrongTaxShown  wrong total marked READY=$readyButWrongTotal  wrong total filled without a check=$savableWithoutCheckWhenWrong")
                appendLine("Time per bill: median ${timesMs.sorted()[timesMs.size / 2]} ms, max ${timesMs.maxOrNull()} ms")
                appendLine("60 consecutive scans: done, native heap first10≈${firstTen.toLong()} KB last10≈${lastTen.toLong()} KB")
                if (safety.isNotEmpty()) appendLine("SAFETY PROBLEMS:\n  " + safety.joinToString("\n  "))
                appendLine("FINAL: " + if (safety.isEmpty() && wrongTaxShown == 0 && readyButWrongTotal == 0) "SAFE" else "SAFETY_FAIL")
            }
            File(outDir, "results.json").writeText(results.toString(1))
            File(outDir, "summary.txt").writeText(summary)
            summary.lines().forEach { Log.i(TAG, it) }

            assertTrue("Safety problems:\n" + safety.joinToString("\n"), safety.isEmpty())
            assertTrue("Wrong tax shown: $wrongTaxShown", wrongTaxShown == 0)
            assertTrue("Wrong total marked READY: $readyButWrongTotal", readyButWrongTotal == 0)
            // Memory must not keep growing scan after scan (allow 40 MB of noise).
            assertTrue("Native heap kept growing: $firstTen KB → $lastTen KB", lastTen - firstTen < 40 * 1024)
        } finally {
            recognizer.release()
        }
    }

    private companion object {
        const val TAG = "GstLive"
    }
}
