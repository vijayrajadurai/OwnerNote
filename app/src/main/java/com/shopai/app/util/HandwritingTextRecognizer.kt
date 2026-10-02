package com.shopai.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.shopai.app.BuildConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Handwritten-note OCR (free, on-device Google ML Kit, bundled model).
 *
 * Tesseract — used for printed bills — returns nothing for cursive
 * handwriting, so notes use this instead. Printed bills are untouched.
 */
class HandwritingTextRecognizer {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Lines of the page, top to bottom, with confidence (0–100) and position. */
    suspend fun recognizeLines(page: Bitmap): List<OcrLine> {
        val result = client.process(InputImage.fromBitmap(page, 0)).await()
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        fun box(r: Rect) = OcrBox(r.left / w, r.top / h, r.right / w, r.bottom / h)
        val pieces = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val rect = line.boundingBox ?: return@mapNotNull null
            Piece(line, rect)
        }
        return mergeRows(pieces).map { row ->
            val union = Rect(row.minOf { it.rect.left }, row.minOf { it.rect.top }, row.maxOf { it.rect.right }, row.maxOf { it.rect.bottom })
            OcrLine(
                text = row.joinToString(" ") { it.line.text.trim() },
                confidence = row.minOf { percent(it.line.confidence) },
                box = box(union),
                words = row.flatMap { piece ->
                    piece.line.elements.map { e -> OcrWord(e.text, percent(e.confidence), e.boundingBox?.let(::box)) }
                },
            )
        }
    }

    /**
     * Reads a page several ways — the whole page, just the writing, and each
     * handwritten row on its own in several image versions — and keeps the
     * reading with the strongest transaction structure (not simply the most
     * lines or characters). Boxes always refer to [page].
     */
    suspend fun readPage(page: Bitmap, writingRegion: CropFractions?): List<OcrLine> {
        val whole = recognizeLines(page)
        if (writingRegion == null) return whole
        val cropped = HandwritingImageProcessor.crop(page, writingRegion)
        val fromCrop = recognizeLines(cropped).map { line -> line.mapBoxes(writingRegion) }
        val fromRows = readRowByRow(cropped).map { line -> line.mapBoxes(writingRegion) }
        // Row-by-row first so it wins a tie (it keeps each line's position).
        return listOf(fromRows, fromCrop, whole).maxBy { HandwrittenTransactionParser.readingScore(it) }
    }

    /**
     * Cuts out each handwritten line, enlarges it and reads it on its own in
     * every image version, keeping the version whose text looks most like a
     * transaction (name + amount + Credit/Debit, or a date).
     */
    private suspend fun readRowByRow(region: Bitmap): List<OcrLine> {
        val px = IntArray(region.width * region.height)
        region.getPixels(px, 0, region.width, 0, 0, region.width, region.height)
        val rows = HandwritingImageAnalysis.inkRows(px, region.width, region.height)
        if (rows.isEmpty() || rows.size > 60) return emptyList()
        return rows.mapIndexed { index, (top, bottom) ->
            val box = OcrBox(0f, top, 1f, bottom)
            val strip = HandwritingImageProcessor.crop(region, CropFractions(0f, top, 1f, bottom))
            // Aim for text about 120 px tall; never shrink, cap the width for memory.
            val factor = (240f / strip.height).coerceIn(1f, 3f).coerceAtMost(3200f / strip.width).coerceAtLeast(1f)
            val big = if (factor > 1.05f) Bitmap.createScaledBitmap(strip, (strip.width * factor).roundToInt(), (strip.height * factor).roundToInt(), true) else strip
            val candidates = mutableListOf<OcrLine>()
            // 1. The whole row, in each image version.
            for (variant in HandwritingImageProcessor.Variant.entries) {
                val image = runCatching { HandwritingImageProcessor.variant(big, variant) }.getOrNull() ?: continue
                val line = runCatching { recognizeLines(image) }.getOrDefault(emptyList()).asOneLine(box) ?: continue
                log("row $index $variant: '${line.text}' (${line.confidence})")
                candidates += line
                // A complete row (name + amount + Credit/Debit) needs no more passes.
                if (HandwrittenTransactionParser.readingScore(listOf(line)) >= COMPLETE_ROW) break
            }
            // 2. Word by word: widely spaced columns ("Kumar   2000   Credit")
            // are often dropped when the row is read at once.
            if (candidates.none { HandwrittenTransactionParser.readingScore(listOf(it)) >= COMPLETE_ROW }) {
                readWordByWord(big, box, index)?.let { candidates += it }
            }
            // Unreadable line: still returned (empty text + position) so the
            // review screen can show its picture instead of losing it.
            val best = candidates.maxByOrNull { HandwrittenTransactionParser.readingScore(listOf(it)) } ?: OcrLine(text = "", confidence = 0, box = box)
            log("row $index chosen: '${best.text}'")
            best
        }
    }

    /**
     * Reads each word of a row on its own (with a white margin, in every
     * image version) and keeps the combination of actual readings with the
     * strongest transaction structure. Nothing is made up: every word is
     * one of the texts OCR returned for it.
     */
    private suspend fun readWordByWord(row: Bitmap, box: OcrBox, rowIndex: Int): OcrLine? {
        val px = IntArray(row.width * row.height)
        row.getPixels(px, 0, row.width, 0, 0, row.width, row.height)
        val words = HandwritingImageAnalysis.inkWords(px, row.width, row.height)
        if (words.size < 2 || words.size > 8) return null
        val readings = words.mapIndexed { w, (left, right) ->
            val crop = withMargin(HandwritingImageProcessor.crop(row, CropFractions(left, 0f, right, 1f)))
            val seen = LinkedHashMap<String, OcrWord>()
            for (variant in HandwritingImageProcessor.Variant.entries) {
                val image = runCatching { HandwritingImageProcessor.variant(crop, variant) }.getOrNull() ?: continue
                val line = runCatching { recognizeLines(image) }.getOrDefault(emptyList()).asOneLine(box) ?: continue
                val text = line.text.trim()
                if (text.isNotEmpty() && text !in seen) seen[text] = OcrWord(text, line.confidence)
            }
            log("row $rowIndex word $w: ${seen.values.joinToString { "'${it.text}'(${it.confidence})" }}")
            seen.values.toList()
        }.filter { it.isNotEmpty() }
        if (readings.isEmpty()) return null
        // Every combination when small (typically 3 words × ≤5 readings); else the most confident.
        val combinations = readings.fold(1L) { acc, r -> acc * r.size }
        val chosen: List<OcrWord> = if (combinations <= 500) {
            var best: List<OcrWord> = emptyList()
            var bestScore = -1
            fun search(i: Int, picked: List<OcrWord>) {
                if (i == readings.size) {
                    val score = HandwrittenTransactionParser.readingScore(listOf(OcrLine(picked.joinToString(" ") { it.text }, 80, box)))
                    // Ties: the more confident reading.
                    if (score > bestScore || (score == bestScore && picked.sumOf { it.confidence } > best.sumOf { it.confidence })) {
                        best = picked; bestScore = score
                    }
                    return
                }
                for (r in readings[i]) search(i + 1, picked + r)
            }
            search(0, emptyList())
            best
        } else {
            readings.map { r -> r.maxBy { it.confidence } }
        }
        return OcrLine(
            text = chosen.joinToString(" ") { it.text },
            confidence = chosen.minOf { it.confidence },
            box = box,
            words = chosen,
        )
    }

    /** White border around a word: OCR reads a word badly when ink touches the edge. */
    private fun withMargin(word: Bitmap): Bitmap {
        val m = (word.height * 0.3f).roundToInt().coerceAtLeast(8)
        val out = Bitmap.createBitmap(word.width + m * 2, word.height + m * 2, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(word, m.toFloat(), m.toFloat(), null)
        }
        return out
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    /** One visual row → one line, pieces left to right. */
    private fun List<OcrLine>.asOneLine(box: OcrBox): OcrLine? {
        if (isEmpty()) return null
        return OcrLine(
            text = joinToString(" ") { it.text },
            confidence = minOf { it.confidence },
            box = box,
            words = flatMap { it.words }.map { it.copy(box = null) },
        )
    }

    fun close() = client.close()

    private companion object {
        // HandwrittenTransactionParser.readingScore of one complete row.
        const val COMPLETE_ROW = 9000
        const val TAG = "HwOcr"
    }

    /** Crop-relative box → page-relative box. */
    private fun OcrLine.mapBoxes(region: CropFractions): OcrLine {
        fun map(b: OcrBox) = OcrBox(
            region.left + b.left * (region.right - region.left),
            region.top + b.top * (region.bottom - region.top),
            region.left + b.right * (region.right - region.left),
            region.top + b.bottom * (region.bottom - region.top),
        )
        return copy(box = box?.let(::map), words = words.map { it.copy(box = it.box?.let(::map)) })
    }

    private data class Piece(val line: Text.Line, val rect: Rect)

    private fun percent(confidence: Float): Int = (confidence * 100).roundToInt().coerceIn(0, 100)

    /**
     * Pieces that share a visual row ("Kumar" and "- 2000 Debit" read as
     * separate blocks) are joined left to right, so a person is never split
     * from their amount.
     */
    private fun mergeRows(pieces: List<Piece>): List<List<Piece>> {
        val rows = mutableListOf<MutableList<Piece>>()
        for (piece in pieces.sortedBy { it.rect.centerY() }) {
            val row = rows.lastOrNull { existing -> existing.any { overlapsVertically(it.rect, piece.rect) } }
            if (row != null) row += piece else rows += mutableListOf(piece)
        }
        return rows.map { row -> row.sortedBy { it.rect.left } }.sortedBy { row -> row.minOf { it.rect.top } }
    }

    private fun overlapsVertically(a: Rect, b: Rect): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        val smaller = min(a.height(), b.height()).coerceAtLeast(1)
        return overlap > smaller * 0.5
    }
}
