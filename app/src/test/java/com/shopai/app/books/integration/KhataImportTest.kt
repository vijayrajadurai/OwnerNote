package com.shopai.app.books.integration

import com.shopai.app.books.model.PartyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Khatabook's "Customer List Report" / "Supplier List Report" PDF (owner, 19 Oct 2026: a new owner brings their khata over).
 * The samples are made the way Khatabook makes them (a browser printing the report to PDF), with made-up people: names that wrap
 * onto three lines, a person with no phone, an amount on either side, a collection date, and the Grand Total the rows must add
 * up to. The real file the owner shared was read the same way (not kept here: it has their customers' phone numbers).
 */
class KhataImportTest {

    private fun pdf(name: String) = javaClass.classLoader!!.getResourceAsStream("import/$name")!!.readBytes()

    @Test fun theCustomerListIsReadPersonByPerson() {
        val sheet = KhataImport.read(pdf("khatabook-customers-sample.pdf"))!!
        assertEquals(PartyKind.CUSTOMER, sheet.kind)
        assertEquals("Murugan Stores", sheet.shop)
        assertEquals(5, sheet.rows.size)
        val ravi = sheet.rows[0]
        // a name on three lines is one name; its phone and amount are on the middle line
        assertEquals("Ravi Kumar Tea Shop Near Bus Stand", ravi.name)
        assertEquals("9876543210", ravi.phone)
        assertEquals(1_250_000L, ravi.getPaise)
        assertEquals(0L, ravi.givePaise)
        assertEquals(LocalDate.of(2026, 11, 5), ravi.collectionDate)
        // "You'll Give": what the shop owes this customer
        val lakshmi = sheet.rows.single { it.name == "Lakshmi" }
        assertEquals(0L, lakshmi.getPaise); assertEquals(25_000L, lakshmi.givePaise)
        // no phone, nothing due — still a customer
        val anbu = sheet.rows.single { it.name == "Anbu 2" }
        assertNull(anbu.phone); assertEquals(0L, anbu.getPaise)
        assertEquals(340_050L, sheet.rows.single { it.name == "Kumar Brothers" }.getPaise)
        // the rows add up to the report's own Grand Total
        assertEquals(1_675_050L, sheet.totalGetPaise)
        assertEquals(25_000L, sheet.totalGivePaise)
        assertTrue(sheet.checked)
    }

    @Test fun theSupplierListIsSuppliers() {
        val sheet = KhataImport.read(pdf("khatabook-suppliers-sample.pdf"))!!
        assertEquals(PartyKind.SUPPLIER, sheet.kind)
        assertEquals(listOf("ABC Traders", "Selvi Agencies"), sheet.rows.map { it.name })
        val abc = sheet.rows[0]
        assertEquals(800_000L, abc.givePaise)
        assertEquals(LocalDate.of(2026, 12, 1), abc.collectionDate)
        assertEquals(50_000L, sheet.rows[1].getPaise)
        assertTrue(sheet.checked)
    }

    @Test fun aFileThatIsNotAKhatabookListIsNotRead() {
        assertNull(KhataImport.read("not a pdf".toByteArray()))
        assertNull(KhataImport.read(ByteArray(0)))
        // a PDF of something else: no Name / You'll Get / You'll Give header
        val lines = listOf(PdfText.Line(1, 10f, listOf(PdfText.Run(1, 10f, 10f, 12f, "Invoice"))), PdfText.Line(1, 30f, listOf(PdfText.Run(1, 10f, 30f, 12f, "Total 500.00"))))
        assertNull(KhataImport.parse(lines))
    }
}
