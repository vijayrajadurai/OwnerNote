package com.shopai.app.ui.notes

import com.shopai.app.util.HandwrittenTransactionParser
import com.shopai.app.util.OcrBox
import com.shopai.app.util.OcrLine
import com.shopai.app.util.OcrWord
import com.shopai.app.util.TxnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Quick correction after handwriting OCR: only what was actually read is
 * pre-filled; missing name / amount / type are never guessed.
 */
class HandwrittenQuickFixTest {
    private val today = LocalDate.of(2026, 9, 29)
    private val box = OcrBox(0f, 0.3f, 1f, 0.4f)

    private fun line(text: String, confidence: Int) =
        OcrLine(text, confidence, box, text.split(' ').filter { it.isNotBlank() }.map { OcrWord(it, confidence) })

    @Test
    fun unclearFragmentIsOnlyAHintNeverTheName() {
        // What ML Kit really returned for "Kumar 2000 Credit": a fragment.
        val (form, prefill) = prefillForm(line("uma", 35), noteDate = null, today = today)
        assertEquals("", form.personName)
        assertEquals("uma", prefill.nameHint)
        assertEquals("", form.amount)
        assertEquals(TxnDirection.UNKNOWN, form.direction)
        assertFalse(form.isComplete)
    }

    @Test
    fun confidentlyReadNameIsPrefilled() {
        val (form, prefill) = prefillForm(line("Kumar", 90), noteDate = null, today = today)
        assertEquals("Kumar", form.personName)
        assertNull(prefill.nameHint)
    }

    @Test
    fun partialLineKeepsWhatWasReadAndFlagsRepairedAmount() {
        val (form, prefill) = prefillForm(line("Lumo 2OOO", 40), noteDate = null, today = today)
        assertEquals("2000", form.amount)
        assertTrue(prefill.amountUnclear)
        assertEquals("", form.personName)
        assertEquals("Lumo", prefill.nameHint)
        assertEquals(TxnDirection.UNKNOWN, form.direction)
    }

    @Test
    fun amountAndTypeWithoutNameLeaveNameEmpty() {
        val (form, prefill) = prefillForm(line("8000 Debit", 85), noteDate = null, today = today)
        assertEquals("8000", form.amount)
        assertEquals(TxnDirection.DEBIT, form.direction)
        assertEquals("", form.personName)
        assertNull(prefill.nameHint)
    }

    @Test
    fun wordTooGarbledForATypeIsNotGuessed() {
        // ML Kit read "Debit" as "obit": too far off to call it Debit.
        val (form, _) = prefillForm(line("obit", 30), noteDate = null, today = today)
        assertEquals(TxnDirection.UNKNOWN, form.direction)
    }

    @Test
    fun twoNumbersAreAmbiguousSoNoAmount() {
        val (form, _) = prefillForm(line("Kumar 2000 3000", 90), noteDate = null, today = today)
        assertEquals("", form.amount)
    }

    @Test
    fun unreadableLineIsCompletelyEmpty() {
        val (form, prefill) = prefillForm(OcrLine("", 0, box), noteDate = null, today = today)
        assertEquals(TxnForm(date = today), form)
        assertNull(prefill.nameHint)
    }

    @Test
    fun dateComesFromTheLineThenTheNoteThenToday() {
        val noteDate = LocalDate.of(2026, 9, 28)
        assertEquals(noteDate, prefillForm(line("Kumar", 90), noteDate, today).first.date)
        assertEquals(today, prefillForm(line("Kumar", 90), null, today).first.date)
        assertEquals(LocalDate.of(2026, 9, 20), prefillForm(line("Kumar 20/09/2026", 90), noteDate, today).first.date)
    }

    @Test
    fun noteDateIsTheWrittenHeading() {
        val pages = listOf(listOf(line("28/09/26", 80), line("Kumar 2000 Credit", 80)))
        assertEquals(LocalDate.of(2026, 9, 28), HandwrittenTransactionParser.noteDate(pages, today))
        assertNull(HandwrittenTransactionParser.noteDate(listOf(listOf(line("Kumar", 80))), today))
    }

    @Test
    fun realPhotoOcrResultKeepsEveryLineForCorrection() {
        // The actual ML Kit result on the real note (date, Kumar, Pamba rows):
        // nothing on the date row, a fragment on Kumar, nothing on Pamba.
        val lines = listOf(
            OcrLine("", 0, OcrBox(0f, 0.08f, 1f, 0.2f)),
            OcrLine("uma", 35, OcrBox(0f, 0.2f, 1f, 0.29f)),
            OcrLine("", 0, OcrBox(0f, 0.32f, 1f, 0.42f)),
        )
        val result = HandwrittenTransactionParser.parse(listOf(lines), today)
        // No transaction is invented from a fragment...
        assertTrue(result.transactions.isEmpty())
        // ...and no line is dropped: all three go to the quick check.
        assertEquals(3, result.unparsedLines.size)
    }

    @Test
    fun correctedLineBecomesACompleteTransaction() {
        val (form, _) = prefillForm(line("uma", 35), noteDate = LocalDate.of(2026, 9, 28), today = today)
        val fixed = form.copy(personName = "Kumar", amount = "2000", direction = TxnDirection.CREDIT)
        assertTrue(fixed.isComplete)
        assertEquals(BigDecimal("2000.00"), fixed.parsedAmount)
        assertEquals(LocalDate.of(2026, 9, 28), fixed.date)
    }
}
