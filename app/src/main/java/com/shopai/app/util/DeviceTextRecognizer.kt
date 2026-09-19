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
class DeviceTextRecognizer(private val context: Context) {

    private val initMutex = Mutex()
    private var tess: TessBaseAPI? = null
    private var initialized = false

    suspend fun recognizeFromUri(uri: Uri): OcrRecognitionResult = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        } ?: return@withContext OcrRecognitionResult("", success = false)

        recognizeBitmap(bitmap, uri)
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
            val text = api.utF8Text?.trim()?.replace(Regex("\\s+"), " ") ?: ""
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
                val ok = api.init(dataPath, "eng+tam", TessBaseAPI.OEM_LSTM_ONLY)
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

    fun release() {
        tess?.recycle()
        tess = null
        initialized = false
    }
}
