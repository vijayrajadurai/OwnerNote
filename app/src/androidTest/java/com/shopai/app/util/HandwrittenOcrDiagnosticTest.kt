package com.shopai.app.util

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stage-by-stage trace of the handwriting pipeline on real photos.
 * Logs to `adb logcat -s HwOcrDiag`; intermediate images are written to
 * the app cache (hwdiag/) for inspection.
 */
@RunWith(AndroidJUnit4::class)
class HandwrittenOcrDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val outDir = File(context.cacheDir, "hwdiag").apply { mkdirs() }

    private fun save(name: String, bmp: Bitmap) {
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun describe(lines: List<OcrLine>) = lines.joinToString(" ‖ ") { "${it.text}(${it.confidence})" }

    private suspend fun trace(asset: String) {
        val file = File(context.cacheDir, "diag_$asset".replace('/', '_'))
        instrumentation.context.assets.open(asset).use { i -> file.outputStream().use { i.copyTo(it) } }
        val uri = Uri.fromFile(file)
        val tag = asset.substringAfterLast('/').substringBefore('.')
        Log.i(TAG, "######## $asset")

        val (w, h) = HandwritingImageProcessor.imageSize(context, uri)
        val thumb = HandwritingImageProcessor.load(context, uri, maxSide = 900)!!
        Log.i(TAG, "original ${w}x$h quality=${HandwritingImageProcessor.quality(thumb, w, h)}")

        val processed = HandwritingImageProcessor.process(context, uri, 0, CropFractions.FULL)!!
        Log.i(TAG, "page=${processed.page.width}x${processed.page.height} deskew=${processed.deskewDegrees} writingRegion=${processed.writingRegion}")
        save("$tag-1-page.jpg", processed.page)

        val recognizer = HandwritingTextRecognizer()
        Log.i(TAG, "ML Kit whole page: ${describe(recognizer.recognizeLines(processed.page))}")
        processed.writingRegion?.let { region ->
            val crop = HandwritingImageProcessor.crop(processed.page, region)
            save("$tag-2-region.jpg", crop)
            Log.i(TAG, "ML Kit region ${crop.width}x${crop.height}: ${describe(recognizer.recognizeLines(crop))}")
            val px = IntArray(crop.width * crop.height)
            crop.getPixels(px, 0, crop.width, 0, 0, crop.width, crop.height)
            val rows = HandwritingImageAnalysis.inkRows(px, crop.width, crop.height)
            Log.i(TAG, "rows found: ${rows.size} ${rows.map { "%.2f-%.2f".format(it.first, it.second) }}")
            rows.take(12).forEachIndexed { i, (top, bottom) ->
                val strip = HandwritingImageProcessor.crop(crop, CropFractions(0f, top, 1f, bottom))
                save("$tag-3-row$i.jpg", strip)
                val factor = (240f / strip.height).coerceIn(1f, 3f).coerceAtMost(3200f / strip.width).coerceAtLeast(1f)
                val big = Bitmap.createScaledBitmap(strip, (strip.width * factor).toInt(), (strip.height * factor).toInt(), true)
                for (variant in HandwritingImageProcessor.Variant.entries) {
                    val image = HandwritingImageProcessor.variant(big, variant)
                    if (variant == HandwritingImageProcessor.Variant.FOREGROUND_INK) save("$tag-4-row$i-ink.jpg", image)
                    Log.i(TAG, "  row $i $variant: ${describe(recognizer.recognizeLines(image))}")
                }
            }
        }
        val lines = recognizer.readPage(processed.page, processed.writingRegion)
        recognizer.close()
        Log.i(TAG, "readPage -> ${lines.size} lines: ${describe(lines)}")
        val parsed = HandwrittenTransactionParser.parse(listOf(lines))
        Log.i(TAG, "parser -> ${parsed.transactions.size} rows: " + parsed.transactions.joinToString(" | ") { "${it.personName}/${it.amount}/${it.date}/${it.direction}/${it.issues}" })
        Log.i(TAG, "parser -> not understood: " + parsed.unparsedLines.joinToString(" | ") { "'${it.second.text}'" })
        // What the quick check pre-fills for each not-understood line.
        val noteDate = HandwrittenTransactionParser.noteDate(listOf(lines))
        parsed.unparsedLines.forEachIndexed { i, (_, line) ->
            val (form, prefill) = com.shopai.app.ui.notes.prefillForm(line, noteDate)
            Log.i(TAG, "quick check $i: box=${line.box} read='${line.text}' -> name='${form.personName}' hint=${prefill.nameHint} amount='${form.amount}' type=${form.direction} date=${form.date}")
        }
    }

    @Test
    fun traceRealPhotos() = runBlocking {
        for (asset in listOf("notes/real_note2_kumar_pamba_a.jpg", "notes/real_note2_kumar_pamba_b.jpg")) trace(asset)
    }

    private companion object { const val TAG = "HwOcrDiag" }
}
