package com.shopai.app.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Which reader suits a photo: the printed-bill reader (Tesseract) or the
 * handwriting reader (ML Kit). Decided from what was read, so a photo taken
 * under the wrong button is handed to the right one.
 */
object DocumentKind {
    private val gstin = Regex("""(?i)\b\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\b""")
    private val markers = listOf(
        Regex("""(?i)\btax\s*invoice\b"""),
        Regex("""(?i)\binvoice\s*(no|number|#)"""),
        Regex("""(?i)\b(hsn|sac)\b"""),
        Regex("""(?i)\bcgst\b"""),
        Regex("""(?i)\bsgst\b"""),
        Regex("""(?i)\bigst\b"""),
        Regex("""(?i)\btaxable\b"""),
        Regex("""(?i)\b(amount\s*in\s*words|rupees\b.*\bonly)\b"""),
        Regex("""(?i)\be\s*\.?\s*&\s*o\s*\.?\s*e\b"""),
    )

    /**
     * How strongly the text looks machine-printed (GST invoice markers). A
     * handwritten bill in a printed bill book may carry a printed GSTIN and
     * "Invoice" header (score 2) — only 3 or more counts as a printed bill.
     */
    fun printedScore(text: String): Int = (if (gstin.containsMatchIn(text)) 1 else 0) + markers.count { it.containsMatchIn(text) }

    fun isClearlyPrinted(text: String): Boolean = printedScore(text) >= 3

    /**
     * The printed-bill reader could not make sense of the photo — most likely
     * handwriting: Tesseract itself is unsure, there is no printed-invoice
     * evidence, and no "Total" line could be found.
     */
    fun looksHandwritten(text: String, tesseractConfidence: Int, foundLabelledTotal: Boolean): Boolean =
        !foundLabelledTotal && printedScore(text) < 2 && (tesseractConfidence in 0 until 55 || text.count(Char::isLetter) < 12)

    /** A private copy of the photo for the other reader (the source may be deleted or temporary). */
    fun handoffCopy(context: Context, source: Uri): Uri? = runCatching {
        val dir = File(context.cacheDir, "share/handoff").apply { mkdirs() }
        val file = File(dir, "scan-${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(source)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        Uri.fromFile(file)
    }.getOrNull()
}

/**
 * A photo passed from one reader to the other. Each is taken once; a photo
 * that arrived by handoff is never sent back (no ping-pong).
 */
object ScanHandoff {
    private val toShopBill = kotlinx.coroutines.flow.MutableStateFlow<Uri?>(null)
    private var toHandwritten: Uri? = null

    /** Observed by the Shop bill reader, which may already be open underneath. */
    val pendingShopBill: kotlinx.coroutines.flow.StateFlow<Uri?> = toShopBill

    fun sendToShopBill(uri: Uri) { toShopBill.value = uri }
    fun takeShopBill(): Uri? = toShopBill.value.also { toShopBill.value = null }

    fun sendToHandwritten(uri: Uri) { toHandwritten = uri }
    fun takeHandwritten(): Uri? = toHandwritten.also { toHandwritten = null }
}
