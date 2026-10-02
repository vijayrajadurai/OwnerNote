package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DocumentParsingTest {
    private val today = LocalDate.of(2026, 9, 27)

    @Test
    fun readsCommonIndianDateFormats() {
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find("Date: 21/09/2026", today))
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find("21-9-26", today))
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find("Dt 21.09.2026", today))
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find("2026-09-21", today))
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find("21 Sep 2026", today))
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find("21st September, 2026", today))
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find("Sep 21, 2026", today))
    }

    @Test
    fun dayComesBeforeMonth() {
        assertEquals(LocalDate.of(2026, 2, 3), DocumentDates.find("03/02/2026", today))
    }

    @Test
    fun prefersTheDateLabelledLine() {
        val text = "Valid till 30/12/2026\nBill Date: 21/09/2026"
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find(text, today))
    }

    @Test
    fun rejectsImpossibleDates() {
        assertNull(DocumentDates.find("31/02/2026", today))
        assertNull(DocumentDates.find("12/13/2026", today))
        assertNull(DocumentDates.find("01/01/1990", today))
    }

    @Test
    fun yearlessDateOnlyWhenAllowedAndNeverInTheFuture() {
        assertNull(DocumentDates.find("21/9", today))
        assertEquals(LocalDate.of(2026, 9, 21), DocumentDates.find("21/9", today, allowYearless = true))
        // 25 Dec has not come yet this year, so it must be last year.
        assertEquals(LocalDate.of(2025, 12, 25), DocumentDates.find("25/12", today, allowYearless = true))
    }

    @Test
    fun billDateIsExtracted() {
        val bill = BillTextParser.parse("SRI MURUGAN STORES\nBill No: 1045 Date: 21/09/2026 10:42\nTotal 250.00")
        assertEquals(LocalDate.of(2026, 9, 21), bill.date)
    }

    @Test
    fun readsASimpleHandwrittenNote() {
        val note = HandwrittenNoteParser.parse("Ravi Kumar\nRs 500\n21/9", today)
        assertEquals("Ravi Kumar", note.name)
        assertEquals(BigDecimal("500.00"), note.amount)
        assertEquals(LocalDate.of(2026, 9, 21), note.date)
    }

    @Test
    fun noteOnOneLineWithSlashDashAmount() {
        val note = HandwrittenNoteParser.parse("Murugan 1,250/- 20-09-2026", today)
        assertEquals("Murugan", note.name)
        assertEquals(BigDecimal("1250.00"), note.amount)
        assertEquals(LocalDate.of(2026, 9, 20), note.date)
    }

    @Test
    fun dateDigitsAreNeverTakenAsTheAmount() {
        val note = HandwrittenNoteParser.parse("Selvi 300 on 25/09/2026", today)
        assertEquals(BigDecimal("300.00"), note.amount)
    }

    @Test
    fun labelsAreStrippedFromTheName() {
        val note = HandwrittenNoteParser.parse("Name: Anbu\nAmount: 750\nDate: 22/9", today)
        assertEquals("Anbu", note.name)
        assertEquals(BigDecimal("750.00"), note.amount)
        assertEquals(LocalDate.of(2026, 9, 22), note.date)
    }

    @Test
    fun unreadableNoteGivesNothing() {
        val note = HandwrittenNoteParser.parse("~ ~ .. //", today)
        assertNull(note.name)
        assertNull(note.amount)
        assertNull(note.date)
    }
}
