package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic "photos" (ARGB ints) for the handwriting image analysis. */
class HandwritingImageAnalysisTest {
    private val paper = 0xFFF2F2F0.toInt()
    private val rule = 0xFFB4B4B4.toInt()   // grey ruled line
    private val blueInk = 0xFF2A3C9C.toInt()
    private val w = 400
    private val h = 500

    /** Ruled paper with blue "writing" blocks on the given rows. */
    private fun note(writingRows: List<IntRange>, x: IntRange = 60..300): IntArray {
        val px = IntArray(w * h) { paper }
        for (y in 20 until h step 25) for (xx in 0 until w) px[y * w + xx] = rule
        for (rows in writingRows) for (y in rows) for (xx in x) {
            // Stroke-like texture: not every pixel is ink.
            if ((xx + y) % 3 != 0) px[y * w + xx] = blueInk
        }
        return px
    }

    @Test
    fun findsTheWritingAreaAndIgnoresRuledLines() {
        val region = HandwritingImageAnalysis.inkRegion(note(listOf(100..115, 140..155, 180..195)), w, h)
        assertNotNull(region)
        region!!
        assertTrue("left ${region.left}", region.left < 60f / w && region.left > 0.05f)
        assertTrue("right ${region.right}", region.right > 300f / w && region.right < 0.95f)
        assertTrue("top ${region.top}", region.top < 100f / h && region.top > 0.1f)
        assertTrue("bottom ${region.bottom}", region.bottom > 195f / h && region.bottom < 0.6f)
    }

    @Test
    fun findsEachHandwrittenLine() {
        val rows = HandwritingImageAnalysis.inkRows(note(listOf(100..115, 140..155, 180..195, 230..245)), w, h)
        assertEquals(4, rows.size)
        rows.forEach { (top, bottom) -> assertTrue(top < bottom) }
    }

    @Test
    fun blankPageHasNoWriting() {
        val blank = note(emptyList())
        assertNull(HandwritingImageAnalysis.inkRegion(blank, w, h))
        assertTrue(HandwritingImageAnalysis.inkRows(blank, w, h).isEmpty())
        val q = HandwritingImageAnalysis.quality(blank, w, h, 3000, 4000)
        assertTrue(PhotoProblem.NO_WRITING in q.problems)
    }

    @Test
    fun goodNotePhotoPassesTheQualityCheck() {
        val q = HandwritingImageAnalysis.quality(note(listOf(100..115, 140..155, 180..195)), w, h, 3072, 4080)
        assertTrue(q.problems.toString(), q.acceptable)
    }

    @Test
    fun thumbHoldingThePageIsNotWriting() {
        val px = note(listOf(100..115, 140..155))
        // A thumb in the bottom-left corner (skin tones, solid area).
        val skin = intArrayOf(0xFFB07A5A.toInt(), 0xFF8C5A3C.toInt(), 0xFFC89070.toInt())
        for (y in 400 until 500) for (x in 0 until 120) px[y * w + x] = skin[(x + y) % 3]
        val region = HandwritingImageAnalysis.inkRegion(px, w, h)!!
        assertTrue("bottom ${region.bottom}", region.bottom < 0.5f)
        assertEquals(2, HandwritingImageAnalysis.inkRows(px, w, h).size)
    }

    @Test
    fun backPageShowThroughIsNotWriting() {
        val px = note(listOf(100..115))
        // Faint bluish strokes seen through the paper, lower down the page.
        val faint = 0xFFB9C0D2.toInt()
        for (y in 250 until 450) for (x in 40 until 360) if ((x * 3 + y) % 17 == 0) px[y * w + x] = faint
        val region = HandwritingImageAnalysis.inkRegion(px, w, h)!!
        assertTrue("bottom ${region.bottom}", region.bottom < 0.4f)
        assertEquals(1, HandwritingImageAnalysis.inkRows(px, w, h).size)
    }

    @Test
    fun darkBackgroundAroundTheNoteIsNotWriting() {
        // Black pen on paper, photographed on a dark desk (top and right edges).
        val px = IntArray(w * h) { paper }
        val black = 0xFF202020.toInt()
        for (y in 0 until 80) for (x in 0 until w) px[y * w + x] = 0xFF303234.toInt()
        for (y in 0 until h) for (x in 340 until w) px[y * w + x] = 0xFF303234.toInt()
        for (rows in listOf(150..165, 200..215)) for (y in rows) for (x in 60..280) if (x % 5 < 2) px[y * w + x] = black
        val rows = HandwritingImageAnalysis.inkRows(px, w, h)
        assertEquals(rows.toString(), 2, rows.size)
    }

    @Test
    fun realNotePhotosFindOnlyTheHandwrittenLines() {
        // Two real photos of the same note: a date line and two transaction
        // lines, faint drawings showing through, a thumb and a keyboard.
        for (name in listOf("real_note2_page_a", "real_note2_page_b")) {
            val page = loadRealNotePage(name)
            val region = HandwritingImageAnalysis.inkRegion(page.pixels, page.width, page.height)
            assertNotNull(name, region)
            region!!
            // The writing is in the top half; the thumb (bottom) is excluded.
            assertTrue("$name bottom ${region.bottom}", region.bottom < 0.6f)
            assertTrue("$name top ${region.top}", region.top > 0.03f)
            val rows = HandwritingImageAnalysis.inkRows(page.pixels, page.width, page.height)
            assertEquals("$name rows $rows", 3, rows.size)
            assertTrue("$name rows $rows", rows.all { it.second < 0.6f })
        }
    }

    @Test
    fun realNoteRowsSplitIntoNameAmountAndType() {
        for (name in listOf("real_note2_page_a", "real_note2_page_b")) {
            val page = loadRealNotePage(name)
            val rows = HandwritingImageAnalysis.inkRows(page.pixels, page.width, page.height)
            val words = rows.map { (top, bottom) ->
                val y0 = (top * page.height).toInt()
                val y1 = (bottom * page.height).toInt()
                val band = page.pixels.copyOfRange(y0 * page.width, y1 * page.width)
                HandwritingImageAnalysis.inkWords(band, page.width, y1 - y0)
            }
            // Row 2 "Kumar 2000 Credit" and row 3 "Pamba 8000 Debit": three words each.
            assertEquals("$name ${words.map { it.size }}", 3, words[1].size)
            assertEquals("$name ${words.map { it.size }}", 3, words[2].size)
            // The date is one or a few pieces, never more than the three columns.
            assertTrue("$name ${words.map { it.size }}", words[0].size in 1..3)
            words.forEach { row -> row.zipWithNext().forEach { (a, b) -> assertTrue(a.second <= b.first + 0.05f) } }
        }
    }

    @Test
    fun ocrVariantsKeepPenStrokesAndDropPaleMarks() {
        val px = note(listOf(100..115))
        val faint = 0xFFB9C0D2.toInt()
        px[300 * w + 50] = faint
        val ink = HandwritingImageAnalysis.foregroundInk(px)
        // Pen strokes untouched (not thresholded or thickened)...
        assertEquals(blueInk, ink[101 * w + 62])
        assertEquals(px.count { it == blueInk }, ink.count { it == blueInk })
        // ...ruled lines and show-through become paper.
        assertEquals(0xFFFFFFFF.toInt(), ink[20 * w + 5])
        assertEquals(0xFFFFFFFF.toInt(), ink[300 * w + 50])
        // Other versions keep the image size and never add ink pixels.
        for (v in listOf(HandwritingImageAnalysis.contrast(px), HandwritingImageAnalysis.grayscale(px), HandwritingImageAnalysis.denoiseContrast(px, w, h))) {
            assertEquals(px.size, v.size)
        }
    }

    // 14. Poor quality image
    @Test
    fun poorPhotosAreFlagged() {
        // Uniform grey, nothing sharp: blurry and no writing.
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        val q1 = HandwritingImageAnalysis.quality(flat, w, h, 3000, 4000)
        assertTrue(PhotoProblem.BLURRY in q1.problems)
        // Nearly black.
        val dark = IntArray(w * h) { if (it % 7 == 0) 0xFF303030.toInt() else 0xFF101010.toInt() }
        assertTrue(PhotoProblem.TOO_DARK in HandwritingImageAnalysis.quality(dark, w, h, 3000, 4000).problems)
        // Tiny original.
        val small = HandwritingImageAnalysis.quality(note(listOf(100..115)), w, h, 300, 400)
        assertTrue(PhotoProblem.LOW_RESOLUTION in small.problems)
        assertFalse(small.acceptable)
    }
}
