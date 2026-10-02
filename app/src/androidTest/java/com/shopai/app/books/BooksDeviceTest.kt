package com.shopai.app.books

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shopai.app.books.billing.BillDraft
import com.shopai.app.books.billing.BillDrafts
import com.shopai.app.books.billing.BillLine
import com.shopai.app.books.billing.BillPayment
import com.shopai.app.books.billing.DraftKind
import com.shopai.app.books.billing.InvoiceDocument
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.billing.MoneyAccounts
import com.shopai.app.books.billing.ReturnDraft
import com.shopai.app.books.billing.toInput
import com.shopai.app.books.billing.toSaleInput
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.engine.BooksContext
import com.shopai.app.books.engine.BusinessSetup
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.OpeningStockInput
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.ProductInput
import com.shopai.app.books.engine.ReturnLine
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TxnSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Phase 3 on a real phone: the engine on device SQLite and the invoice PDF
 * drawn by Android's own PDF engine (not available on the JVM).
 */
@RunWith(AndroidJUnit4::class)
class BooksDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: BooksDatabase
    private lateinit var s: BooksSession

    private fun <T> MasterResult<T>.ok(): T = (this as MasterResult.Ok).value
    private fun PostResult.ok() = (this as? PostResult.Posted)?.txn ?: throw AssertionError((this as PostResult.Rejected).errors.toString())

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, BooksDatabase::class.java).build()
        s = BooksSession(db, BooksContext("dev-biz", "dev-br", "dev-wh", "dev-owner"))
        s.masters.setupBusiness(BusinessSetup("Device Test Traders", BusinessType.RETAILER, stateCode = "33", gstin = "33ABCDE1234F1Z" + com.shopai.app.books.tax.Gstin.checkChar("33ABCDE1234F1Z"))).ok()
        s.masters.updateInvoiceDetails("devtest@okicici", "A/c 1234 · IFSC SBIN0000001", "Thank you").ok()
        MoneyAccounts.ensureDefaults(s)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun invoiceToPdfToReturnOnDevice(): Unit = runBlocking {
        val customer = s.masters.createParty(PartyInput(PartyKind.CUSTOMER, "Lokesh", mobile = "9876543210", stateCode = "33")).ok()
        val frog = s.masters.createProduct(ProductInput(name = "Frog toy", sellingPricePaise = Money.ofRupees(250), gstBp = 500)).ok()
        s.engine.postOpeningStock(OpeningStockInput(frog.id, LocalDate.now(), Qty.of(50), Money.ofRupees(7500), meta = PostMeta("os"))).ok()

        // 3 × ₹250 + 5% GST = ₹787.50 → ₹788; ₹300 cash + ₹200 UPI now, ₹288 due.
        val drafts = BillDrafts(s)
        val bill = BillDraft(
            DraftKind.SALE, customer.id, LocalDate.now().toString(), LocalDate.now().plusDays(7).toString(), null,
            listOf(BillLine(frog.id, "Frog toy", Qty.of(3), "PCS", null, gstBp = 500)) + List(30) { BillLine(null, "Gift wrap ${it + 1}", Qty.of(1), "NOS", Money.ofRupees(10), gstBp = 0) },
            listOf(BillPayment(PaymentMode.CASH, Money.ofRupees(300)), BillPayment(PaymentMode.UPI, Money.ofRupees(200))),
        )
        val id = drafts.save(null, DraftKind.SALE, bill)
        val quote = s.engine.quoteSale(bill.toSaleInput(s, id, TxnSource.MANUAL))
        val inv = s.engine.postSale(bill.toSaleInput(s, id, TxnSource.MANUAL)).ok()
        assertEquals(quote.totals!!.grandTotalPaise, inv.totalPaise)
        assertEquals(Money.ofRupees(1088), inv.totalPaise) // 788 + 30 × 10
        assertEquals(Money.ofRupees(588), s.ledger.receivable(customer.id))
        assertEquals(Money.ofRupees(300), s.ledger.moneyBalance(s.cashAccountId))
        assertEquals(Money.ofRupees(200), s.ledger.moneyBalance(MoneyAccounts.accountFor(s, PaymentMode.UPI)!!.id))
        assertEquals(Qty.of(47), s.ledger.stock(frog.id))

        // PDF: written by the platform engine, valid, more than one page for a long bill.
        val doc = InvoiceDocument.load(s, inv.id)!!
        assertEquals("TAX INVOICE", doc.title)
        val pdf = InvoicePdf.render(context, doc)
        assertTrue(pdf.length() > 1000)
        assertEquals("%PDF", pdf.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            assertTrue("pages=${r.pageCount}", r.pageCount >= 2)
            // Page images for a visual check of the layout.
            for (i in 0 until r.pageCount) r.openPage(i).use { page ->
                val bmp = android.graphics.Bitmap.createBitmap(page.width * 2, page.height * 2, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                java.io.File(pdf.parentFile, "INV-0001-p${i + 1}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }

        // Return 1 frog → credit note, stock back, receivable down.
        val rd = ReturnDraft(DraftKind.SALE_RETURN, inv.id, LocalDate.now().toString(), listOf(ReturnLine(1, Qty.of(1))), "Damaged")
        val rid = drafts.save(null, DraftKind.SALE_RETURN, rd)
        val cn = s.engine.postSaleReturn(rd.toInput(rid, TxnSource.MANUAL)).ok()
        assertEquals(Money.ofRupees(263), cn.totalPaise) // 250 + 12.50 → 262.50 → 263
        assertEquals(Qty.of(48), s.ledger.stock(frog.id))
        assertEquals(Money.ofRupees(325), s.ledger.receivable(customer.id))
        assertTrue(InvoicePdf.render(context, InvoiceDocument.load(s, cn.id)!!).exists())
    }
}
