package com.shopai.app.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OcrRecognitionResult(
    val text: String,
    val success: Boolean,
    /** Tesseract's own mean word confidence (0–100); -1 when unknown. */
    val meanConfidence: Int = -1,
)

/**
 * On-device OCR using Tesseract. No network, API keys, or usage limits.
 *
 * Printed bills ([printedBills], with the default [languages] "eng+tam") are read English-first:
 * the eng+tam model tends to slip Tamil letters into English words on printed
 * GST invoices. When the English read is not clearly good (no labelled total,
 * or the bill's own arithmetic fails — see [OcrResultChooser.goodEnough]),
 * eng+tam is tried, then a cleaned-up image (deskew + adaptive threshold),
 * then a quarter turn each way for a sideways photo; the best text by
 * [OcrResultChooser.score] wins. Tesseract's own confidence never decides.
 *
 * One TessBaseAPI is native and not thread-safe: every use goes through
 * [apiMutex], so two scans can never touch it at once. Every bitmap made here
 * is recycled when its pass is done.
 */
class DeviceTextRecognizer(
    private val context: Context,
    /** Tesseract languages. Printed bills use the default; handwriting may use its own. */
    private val languages: String = "eng+tam",
    /**
     * Bill scanner only: several passes, the best by what the bill says (see above).
     * Other photos (product labels) keep the single eng+tam read.
     */
    private val printedBills: Boolean = false,
) {

    private val initMutex = Mutex()
    private val apiMutex = Mutex()
    private var tess: TessBaseAPI? = null
    private var initialized = false
    /** English-only engine for the first pass over printed bills (made only when needed). */
    private var tessEnglish: TessBaseAPI? = null
    /** [release] was called: no further reads touch the (recycled) engines. */
    @Volatile private var closed = false
    /** [release] came while a read held the engine: the read frees them when it is done. */
    @Volatile private var releasePending = false

    private val englishFirst: Boolean get() = printedBills && languages == "eng+tam"

    suspend fun recognizeFromUri(uri: Uri): OcrRecognitionResult = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(uri) ?: return@withContext OcrRecognitionResult("", success = false)
        // The decoded photo is ours: freed as soon as the read is done, not left for the GC.
        try {
            recognizeBitmap(bitmap, uri)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Full camera photos (e.g. 50 MP ≈ 200 MB decoded) would run out of
     * memory, so decode at a reduced size. OCR downscales to 2048 px anyway.
     */
    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= MAX_DECODE_SIDE_PX) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
    }

    suspend fun recognizeFromBitmap(bitmap: Bitmap): OcrRecognitionResult =
        withContext(Dispatchers.IO) {
            recognizeBitmap(bitmap, imageUri = null)
        }

    private suspend fun recognizeBitmap(bitmap: Bitmap, imageUri: Uri?): OcrRecognitionResult {
        if (!ensureInitialized()) {
            return OcrRecognitionResult("", success = false)
        }
        val mixed = tess ?: return OcrRecognitionResult("", success = false)
        val prepared = OcrImagePreprocessor.prepare(context, bitmap, imageUri)
        try {
            if (!englishFirst) return read(mixed, prepared)
            // Each pass: the engine used and what it read.
            val passes = mutableListOf<Pair<TessBaseAPI, OcrRecognitionResult>>()
            fun done() = passes.lastOrNull()?.second?.let { it.success && OcrResultChooser.goodEnough(it.text) } == true
            fun bestEngine() = passes[OcrResultChooser.best(passes.map { it.second.text })].first
            englishApi()?.let { passes += it to read(it, prepared) }
            if (!done()) passes += mixed to read(mixed, prepared)
            if (!done()) {
                // The cleaned image (deskew + adaptive threshold) with whichever engine read better so far.
                val clean = OcrImagePreprocessor.cleanVariant(prepared)
                try {
                    val engine = bestEngine()
                    passes += engine to read(engine, clean)
                } finally {
                    clean.recycle()
                }
            }
            // No pass found a labelled total: perhaps the bill was photographed sideways (either way).
            for (degrees in listOf(90, 270)) {
                if (passes.any { BillTextParser.parse(it.second.text).totalFromLabel }) break
                val turned = OcrImagePreprocessor.rotated(prepared, degrees)
                try {
                    val engine = bestEngine()
                    passes += engine to read(engine, turned)
                } finally {
                    turned.recycle()
                }
            }
            val chosen = OcrResultChooser.best(passes.map { it.second.text })
            return passes.getOrNull(chosen)?.second ?: OcrRecognitionResult("", success = false)
        } finally {
            prepared.recycle()
        }
    }

    /** One Tesseract read of [image]; the engine is always cleared afterwards, even on failure. */
    private suspend fun read(api: TessBaseAPI, image: Bitmap): OcrRecognitionResult = apiMutex.withLock {
        if (closed) return@withLock OcrRecognitionResult("", success = false)
        try {
            api.setImage(image)
            // Keep line breaks: bill/note parsing reads the text line by line
            // (the header line and the "Total" line must stay separate).
            val text = api.utF8Text.orEmpty()
                .lines()
                .map { it.replace(Regex("[ \\t]+"), " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            val confidence = runCatching { api.meanConfidence() }.getOrDefault(-1)
            OcrRecognitionResult(text = text, success = text.isNotBlank(), meanConfidence = confidence)
        } catch (e: Exception) {
            android.util.Log.w("DeviceTextRecognizer", "OCR pass failed", e)
            OcrRecognitionResult("", success = false)
        } finally {
            runCatching { api.clear() }
            if (releasePending) recycleEngines()
        }
    }

    /** The English-only engine, made on first use (null if it can't be set up: eng+tam is used alone). */
    private suspend fun englishApi(): TessBaseAPI? = initMutex.withLock {
        if (closed) return null
        tessEnglish?.let { return it }
        withContext(Dispatchers.IO) {
            runCatching {
                val api = TessBaseAPI()
                if (!api.init(prepareTessData(), "eng", TessBaseAPI.OEM_LSTM_ONLY)) {
                    api.recycle()
                    null
                } else {
                    api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                    api.also { tessEnglish = it }
                }
            }.getOrNull()
        }
    }

    private suspend fun ensureInitialized(): Boolean = initMutex.withLock {
        if (initialized && tess != null) return true
        withContext(Dispatchers.IO) {
            runCatching {
                val dataPath = prepareTessData()
                val api = TessBaseAPI()
                val ok = api.init(dataPath, languages, TessBaseAPI.OEM_LSTM_ONLY)
                if (!ok) {
                    api.recycle()
                    return@runCatching false
                }
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                tess = api
                initialized = true
                true
            }.getOrDefault(false)
        }
    }

    private fun prepareTessData(): String {
        val root = File(context.filesDir, "tesseract")
        val tessDataDir = File(root, "tessdata")
        if (!tessDataDir.exists()) tessDataDir.mkdirs()

        listOf("eng.traineddata", "tam.traineddata").forEach { fileName ->
            val outFile = File(tessDataDir, fileName)
            if (!outFile.exists()) {
                context.assets.open("tessdata/$fileName").use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
        }
        return root.absolutePath
    }

    /**
     * Reads a prepared page line by line: each line's text, confidence
     * (0–100) and position (fractions of [image]), plus its words with their
     * own confidence. Used for handwritten notes.
     */
    suspend fun recognizeLines(
        image: Bitmap,
        pageSegMode: Int = TessBaseAPI.PageSegMode.PSM_AUTO,
    ): List<OcrLine> = withContext(Dispatchers.IO) {
        if (!ensureInitialized()) return@withContext emptyList()
        val api = tess ?: return@withContext emptyList()
        apiMutex.withLock { if (closed) return@withLock emptyList(); runCatching {
            api.setPageSegMode(pageSegMode)
            api.setImage(image)
            api.utF8Text // runs recognition; the iterator reads its results
            val w = image.width.toFloat()
            val h = image.height.toFloat()
            fun box(rect: android.graphics.Rect) = OcrBox(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h)

            data class RawLine(val text: String, val confidence: Int, val rect: android.graphics.Rect)
            val lines = mutableListOf<RawLine>()
            val words = mutableListOf<Pair<OcrWord, android.graphics.Rect>>()
            api.resultIterator?.let { it ->
                it.begin()
                do {
                    val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        lines += RawLine(text, it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE).toInt(), it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                    }
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                it.begin()
                do {
                    val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        words += OcrWord(text, it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD).toInt(), box(rect)) to rect
                    }
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                it.delete()
            }
            api.clear()
            // Each word belongs to the line its centre falls in.
            lines.map { line ->
                OcrLine(
                    text = line.text,
                    confidence = line.confidence.coerceIn(0, 100),
                    box = box(line.rect),
                    words = words.filter { (_, r) -> line.rect.contains(r.centerX(), r.centerY()) }.map { it.first.copy(confidence = it.first.confidence.coerceIn(0, 100)) },
                )
            }
        }.getOrElse {
            android.util.Log.w("DeviceTextRecognizer", "recognizeLines failed", it)
            emptyList()
        }.also {
            runCatching { api.clear() }
            // Printed-bill reads (recognizeBitmap) rely on the default mode.
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            if (releasePending) recycleEngines()
        } }
    }

    private companion object {
        const val MAX_DECODE_SIDE_PX = 2048
    }

    /**
     * Frees the native engines (the screen is gone). Never while a read is using
     * them: if one is running, it frees them as soon as it finishes.
     */
    fun release() {
        closed = true
        if (apiMutex.tryLock()) {
            try {
                recycleEngines()
            } finally {
                apiMutex.unlock()
            }
        } else {
            releasePending = true
        }
    }

    /** Called with [apiMutex] held. */
    private fun recycleEngines() {
        releasePending = false
        tess?.recycle()
        tess = null
        tessEnglish?.recycle()
        tessEnglish = null
        initialized = false
    }
}
