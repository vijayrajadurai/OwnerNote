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
)

/**
 * On-device OCR using Tesseract (eng + tam). No network, API keys, or usage limits.
 */
class DeviceTextRecognizer(
    private val context: Context,
    /** Tesseract languages. Printed bills use the default; handwriting may use its own. */
    private val languages: String = "eng+tam",
) {

    private val initMutex = Mutex()
    private var tess: TessBaseAPI? = null
    private var initialized = false

    suspend fun recognizeFromUri(uri: Uri): OcrRecognitionResult = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(uri) ?: return@withContext OcrRecognitionResult("", success = false)
        recognizeBitmap(bitmap, uri)
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
        val api = tess ?: return OcrRecognitionResult("", success = false)
        val prepared = OcrImagePreprocessor.prepare(context, bitmap, imageUri)
        return runCatching {
            api.setImage(prepared)
            // Keep line breaks: bill/note parsing reads the text line by line.
            val text = api.utF8Text.orEmpty()
                .lines()
                .map { it.replace(Regex("[ \\t]+"), " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            api.clear()
            OcrRecognitionResult(text = text, success = text.isNotBlank())
        }.getOrElse {
            OcrRecognitionResult("", success = false)
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
        runCatching {
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
            // Printed-bill reads (recognizeBitmap) rely on the default mode.
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        }
    }

    private companion object {
        const val MAX_DECODE_SIDE_PX = 2048
    }

    fun release() {
        tess?.recycle()
        tess = null
        initialized = false
    }
}
