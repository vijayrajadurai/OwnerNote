package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** A handwritten bill (items + total) is one entry; a notebook page stays one row per person. */
class HandwrittenBillTest {
    private val today = LocalDate.of(2026, 10, 2)
    private fun page(vararg lines: String) = listOf(lines.map { OcrLine(it, confidence = 90) })
    private fun parse(vararg lines: String) = HandwrittenTransactionParser.parse(page(*lines), today)

    @Test
    fun billWithTotalIsOneEntry() {
        val r = parse(
            "Sri Murugan Stores",
            "Name: Ramesh",
            "02/10/2026",
            "Rice 5 kg 300",
            "Dal 2 kg 240",
            "Oil 1 L 180",
            "Sugar 2 kg 90",
            "Total 810",
        )
        val t = r.transactions.single()
        assertEquals("Ramesh", t.personName)
        assertEquals(BigDecimal("810.00"), t.amount)
        assertEquals(LocalDate.of(2026, 10, 2), t.date)
        assertEquals(TxnDirection.UNKNOWN, t.direction)
        assertTrue(t.description!!, t.description!!.contains("4 items"))
        // Labelled name + written total: only Credit/Debit is left to choose.
        assertEquals(setOf(ReviewIssue.TYPE_UNKNOWN), t.issues)
        assertTrue(r.unparsedLines.isEmpty())
    }

    @Test
    fun billHeaderGivesCompanyAndCustomer() {
        val t = parse(
            "SRI MURUGAN TRADERS",
            "12, Gandhi Road, Salem",
            "Cell: 98765 43210",
            "GSTIN 33ABCDE1234F1Z5",
            "Bill No 117   Date 02/10/2026",
            "M/s.",
            "Kumar Stores",
            "Rice 25 kg 1250",
            "Dal 5 kg 600",
            "Total 1850",
        ).transactions.single()
        assertEquals("SRI MURUGAN TRADERS", t.billCompany)
        assertEquals("Kumar Stores", t.billCustomer)
        assertEquals("Kumar Stores", t.personName)
        assertEquals(BigDecimal("1850.00"), t.amount)
        assertTrue(t.description!!.contains("No. 117"))
    }

    @Test
    fun billWithoutCustomerUsesTheCompany() {
        val t = parse(
            "Balaji Hardwares",
            "Ph 9443012345",
            "Cement 10 bags 4200",
            "Paint 2 x 650 1300",
            "Grand Total 5500",
        ).transactions.single()
        assertEquals("Balaji Hardwares", t.billCompany)
        assertEquals(null, t.billCustomer)
        assertEquals("Balaji Hardwares", t.personName)
        assertTrue(ReviewIssue.NAME_UNCLEAR in t.issues)
        assertEquals(BigDecimal("5500.00"), t.amount)
    }

    @Test
    fun tamilTotalAndQtyTimesRate() {
        val t = parse(
            "கணேஷ்",
            "Bill No 45",
            "Soap 3 x 40 120",
            "Paste 2 x 55 110",
            "மொத்தம் 230",
        ).transactions.single()
        assertEquals("கணேஷ்", t.personName)
        assertEquals(BigDecimal("230.00"), t.amount)
        assertTrue(t.description!!.contains("No. 45"))
        // Top-line name (not labelled) must be confirmed — it might be the shop's own name.
        assertTrue(ReviewIssue.NAME_UNCLEAR in t.issues)
    }

    @Test
    fun noTotalLineLeavesTheTotalForTheOwner() {
        val t = parse(
            "Estimate",
            "Cement 2 bags 800",
            "Sand 3 x 500 1500",
            "Bricks 100 nos 900",
        ).transactions.single()
        // Items are never added up into a total: no written total → the owner types it.
        assertEquals(null, t.amount)
        assertTrue(ReviewIssue.AMOUNT_MISSING in t.issues)
        assertTrue(ReviewIssue.NAME_MISSING in t.issues)
    }

    @Test
    fun unclearPageIsAskedNotGuessed() {
        // Item-like lines but no Total / bill heading: could be either.
        val lines = arrayOf("Rice 5 kg 300", "Dal 2 kg 240", "Oil 1 L 180")
        val r = parse(*lines)
        assertEquals(listOf(PageKind.UNKNOWN), r.pageKinds)
        assertEquals(listOf(0), r.unknownPages)
        assertTrue(r.transactions.isEmpty())
        // The owner says "Bill": one entry, total left to the owner.
        val asBill = HandwrittenTransactionParser.parse(page(*lines), today, kinds = mapOf(0 to PageKind.BILL))
        assertEquals(1, asBill.transactions.size)
        assertTrue(asBill.unknownPages.isEmpty())
        // The owner says "Notes": read line by line, as a notes page (not one bill entry).
        val asNotes = HandwrittenTransactionParser.parse(page(*lines), today, kinds = mapOf(0 to PageKind.NOTES))
        assertTrue(asNotes.transactions.size > 1)
        assertTrue(asNotes.transactions.none { it.description?.startsWith(HANDWRITTEN_BILL_LABEL) == true })
    }

    @Test
    fun clearPagesAreClassified() {
        assertEquals(PageKind.BILL, HandwrittenTransactionParser.classify(page("Name: Ravi", "Rice 5 kg 300", "Dal 2 kg 240", "Total 540").single(), today))
        assertEquals(PageKind.NOTES, HandwrittenTransactionParser.classify(page("Ravi 500", "Kumar 300 gave").single(), today))
    }

    @Test
    fun pageTypeGivenReceivedMixedAndBill() {
        val given = parse("Given list", "Ravi 500", "Kumar 1200", "Selvi 300")
        assertEquals(NotePageType.MONEY_GIVEN, given.pageType)
        assertTrue(given.transactions.all { it.direction == TxnDirection.CREDIT })
        assertEquals(listOf("Ravi", "Kumar", "Selvi"), given.transactions.map { it.personName })

        val received = parse("Ravi 500 received", "Kumar 700 வாங்கினேன்")
        assertEquals(NotePageType.MONEY_RECEIVED, received.pageType)
        assertTrue(received.transactions.all { it.direction == TxnDirection.DEBIT })

        val mixed = parse("Ravi 500 gave", "Kumar 700 received", "Arun 100")
        assertEquals(NotePageType.MIXED, mixed.pageType)
        assertEquals(listOf(TxnDirection.CREDIT, TxnDirection.DEBIT, TxnDirection.UNKNOWN), mixed.transactions.map { it.direction })

        val tamilGiven = parse("கொடுத்தது", "முருகன் 400", "கணேஷ் 250")
        assertEquals(NotePageType.MONEY_GIVEN, tamilGiven.pageType)

        assertEquals(NotePageType.HANDWRITTEN_BILL, parse("Name: Ramesh", "Rice 5 kg 300", "Dal 2 kg 240", "Total 540").pageType)
        assertEquals(NotePageType.UNKNOWN, parse("Ravi 500", "Kumar 300").pageType)
        // A written opposite word on the same line is not overridden: left for review.
        assertEquals(TxnDirection.UNKNOWN, parse("Ravi 500 gave debit").transactions.single().direction)
    }

    @Test
    fun paidBalanceAndDueDateAreRead() {
        val t = parse(
            "Murugan Traders",
            "Name: Kumar",
            "Date 02/10/2026",
            "Rice 5 kg 300",
            "Dal 2 kg 240",
            "Oil 1 L 260",
            "Total 800",
            "Paid 500",
            "Balance 300",
            "Due date 15/10/2026",
        ).transactions.single()
        assertEquals(BigDecimal("800.00"), t.amount)
        assertEquals(BigDecimal("500.00"), t.billPaid)
        assertEquals(LocalDate.of(2026, 10, 15), t.billDueDate)
        // The bill date is not the due date; footer lines are not items.
        assertEquals(LocalDate.of(2026, 10, 2), t.date)
        assertTrue(t.description!!.contains("3 items"))
        assertTrue(ReviewIssue.AMOUNT_UNCLEAR !in t.issues)
    }

    @Test
    fun paidFromBalanceAdvanceAndStamp() {
        // Only a balance written: paid = total − balance.
        val fromBalance = parse("Name: Ravi", "Soap 3 x 40 120", "Paste 2 x 55 110", "Total 230", "Bal 100").transactions.single()
        assertEquals(BigDecimal("130.00"), fromBalance.billPaid)
        // Tamil advance + பாக்கி, no Total line: total = paid + balance.
        val tamil = parse("Name: கணேஷ்", "அரிசி 5 kg 300", "பருப்பு 2 kg 240", "அட்வான்ஸ் 200", "பாக்கி 340").transactions.single()
        assertEquals(BigDecimal("540.00"), tamil.amount)
        assertEquals(BigDecimal("200.00"), tamil.billPaid)
        // A "PAID" stamp: all of it.
        val stamped = parse("Name: Ravi", "Rice 5 kg 300", "Dal 2 kg 240", "Total 540", "PAID").transactions.single()
        assertEquals(BigDecimal("540.00"), stamped.billPaid)
        // Nothing written: nothing paid, no due date.
        val plain = parse("Name: Ravi", "Rice 5 kg 300", "Dal 2 kg 240", "Total 540").transactions.single()
        assertEquals(null, plain.billPaid)
        assertEquals(null, plain.billDueDate)
    }

    @Test
    fun paidThatDoesNotAddUpIsFlagged() {
        // Paid + Balance ≠ Total: the owner checks the amounts.
        val t = parse("Name: Ravi", "Rice 5 kg 300", "Dal 2 kg 240", "Total 540", "Paid 300", "Balance 300").transactions.single()
        assertTrue(ReviewIssue.AMOUNT_UNCLEAR in t.issues)
        // Paid more than the bill: not used.
        val over = parse("Name: Ravi", "Rice 5 kg 300", "Dal 2 kg 240", "Total 540", "Paid 900").transactions.single()
        assertEquals(null, over.billPaid)
        assertTrue(ReviewIssue.AMOUNT_UNCLEAR in over.issues)
    }

    @Test
    fun notebookPagesAreNotBills() {
        val notebook = parse(
            "28/09/2026",
            "Ravi 500 credit",
            "Kumar 300 Selvam 200",
            "Arun 1000 debit",
            "Total 2000",
        )
        assertEquals(listOf("Ravi", "Kumar", "Selvam", "Arun"), notebook.transactions.map { it.personName })
        // One item-ish line alone is not a bill.
        assertTrue(parse("Mani 2 kg rice 120").transactions.none { it.description?.startsWith("Handwritten bill") == true })
    }
}
