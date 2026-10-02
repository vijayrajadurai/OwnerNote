package com.shopai.app.books

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopai.app.books.billing.AllocationDraft
import com.shopai.app.books.billing.BillDraft
import com.shopai.app.books.billing.BillDrafts
import com.shopai.app.books.billing.BillLine
import com.shopai.app.books.billing.BillPayment
import com.shopai.app.books.billing.DraftKind
import com.shopai.app.books.billing.InvoiceDocument
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.billing.MoneyAccounts
import com.shopai.app.books.billing.PaymentDraft
import com.shopai.app.books.billing.ReturnDraft
import com.shopai.app.books.billing.RupeesInWords
import com.shopai.app.books.billing.shareCaption
import com.shopai.app.books.billing.toInput
import com.shopai.app.books.billing.toPurchaseInput
import com.shopai.app.books.billing.toSaleInput
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.ReturnLine
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.MoneyAccountKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.TxnSource
import com.shopai.app.books.model.TxnType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Phase 3: bills, returns and payments through draft → quote → confirm → the one engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BooksBillingTest {
    private lateinit var db: BooksDatabase
    private lateinit var f: BooksFixture
    private lateinit var s: BooksSession
    private lateinit var drafts: BillDrafts
    private val today = LocalDate.now()

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BooksDatabase::class.java).build()
        f = BooksFixture(db).apply { this.today = this@BooksBillingTest.today }
        f.setup(state = "33")
        s = BooksSession(db, f.ctx)
        MoneyAccounts.ensureDefaults(s)
        drafts = BillDrafts(s)
    }

    @After
    fun tearDown() = db.close()

    private fun sale(partyId: String?, vararg lines: BillLine, pays: List<BillPayment> = emptyList(), due: LocalDate? = null) =
        BillDraft(DraftKind.SALE, partyId, today.toString(), due?.toString(), null, lines.toList(), pays)

    private suspend fun countTxns() = s.dao.txnsBetween(f.ctx.businessId, 0, Int.MAX_VALUE, null, 10_000, 0).size

    @Test
    fun theReviewShowsExactlyWhatGetsPostedAndWritesNothing(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh", state = "29") // other state → IGST
        val rice = f.product("Rice", sell = rs("1180"), gstBp = 1800, stock = q(100))
        val d = sale(ramesh.id, BillLine(rice.id, "Rice", q("2.5"), "PCS", null, discountBp = 500), BillLine(null, "Delivery", q(1), "NOS", rs(99), gstBp = 1800))
        val before = countTxns()

        val quote = s.engine.quoteSale(d.toSaleInput(s, "preview", TxnSource.MANUAL))
        assertTrue(quote.errors.toString(), quote.ok)
        assertEquals(before, countTxns())
        assertEquals("INV-0001", quote.number)
        assertEquals(0L, quote.totals!!.cgstPaise + quote.totals!!.sgstPaise)
        assertTrue(quote.totals!!.igstPaise > 0)
        // Peeking the number does not use it up.
        assertEquals("INV-0001", s.engine.peekNumber(TxnType.SALE))

        val id = drafts.save(null, DraftKind.SALE, d)
        val posted = s.engine.postSale(d.toSaleInput(s, id, TxnSource.MANUAL)).ok()
        assertEquals(quote.number, posted.number)
        assertEquals(quote.totals!!.grandTotalPaise, posted.totalPaise)
        assertEquals(quote.totals!!.igstPaise, posted.igstPaise)
        assertEquals(quote.totals!!.roundOffPaise, posted.roundOffPaise)
        assertEquals(quote.totals!!.taxablePaise, posted.taxablePaise)
        assertEquals(posted.totalPaise, f.ledger.receivable(ramesh.id))
        assertEquals(q("97.5"), f.ledger.stock(rice.id))
    }

    @Test
    fun theQuoteExplainsWhatIsWrongBeforeSaving(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val rice = f.product("Rice", sell = rs(50), stock = q(3))
        val short = s.engine.quoteSale(sale(ramesh.id, BillLine(rice.id, "Rice", q(5), "PCS", null)).toSaleInput(s, "p", TxnSource.MANUAL))
        assertEquals(listOf(BooksErrorCode.INSUFFICIENT_STOCK), short.errors.map { it.code })
        val walkInCredit = s.engine.quoteSale(sale(null, BillLine(rice.id, "Rice", q(1), "PCS", null)).toSaleInput(s, "p", TxnSource.MANUAL))
        assertEquals(listOf(BooksErrorCode.PARTY_REQUIRED), walkInCredit.errors.map { it.code })
        f.sale(ramesh, f.item(rice, q(1)), number = "A-7").ok()
        val dup = s.engine.quoteSale(sale(ramesh.id, BillLine(rice.id, "Rice", q(1), "PCS", null)).copy(number = "A-7").toSaleInput(s, "p", TxnSource.MANUAL))
        assertEquals(listOf(BooksErrorCode.DUPLICATE_NUMBER), dup.errors.map { it.code })
    }

    @Test
    fun aDraftIsConfirmedOnceEvenIfConfirmIsTappedTwice(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val rice = f.product("Rice", sell = rs(100), stock = q(10))
        val d = sale(ramesh.id, BillLine(rice.id, "Rice", q(2), "PCS", null))
        val id = drafts.save(null, DraftKind.SALE, d)
        assertEquals(d, drafts.bill(id))
        assertEquals(id, drafts.open().single().id)

        val first = s.engine.postSale(d.toSaleInput(s, id, TxnSource.MANUAL)) as PostResult.Posted
        val again = s.engine.postSale(d.toSaleInput(s, id, TxnSource.MANUAL)) as PostResult.Posted
        assertTrue(again.duplicate)
        assertEquals(first.txn.id, again.txn.id)
        assertEquals(rs(200), f.ledger.receivable(ramesh.id))
        assertEquals("CONFIRMED", s.dao.draft(id)!!.status)
        assertTrue(drafts.open().isEmpty())

        // A discarded draft can no longer be posted.
        val other = drafts.save(null, DraftKind.SALE, d)
        drafts.discard(other)
        assertEquals(listOf(BooksErrorCode.DRAFT_REQUIRED), s.engine.postSale(d.toSaleInput(s, other, TxnSource.MANUAL)).codes())
    }

    @Test
    fun splitPaymentModesLandInTheirOwnAccounts(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val rice = f.product("Rice", sell = rs(1000), stock = q(10))
        val d = sale(ramesh.id, BillLine(rice.id, "Rice", q(5), "PCS", null), pays = listOf(BillPayment(PaymentMode.CASH, rs(1500)), BillPayment(PaymentMode.UPI, rs(2000))))
        val id = drafts.save(null, DraftKind.SALE, d)
        val inv = s.engine.postSale(d.toSaleInput(s, id, TxnSource.MANUAL)).ok()
        val upi = MoneyAccounts.accountFor(s, PaymentMode.UPI)!!
        assertEquals(MoneyAccountKind.UPI.name, upi.kind)
        assertEquals(rs(1500), f.ledger.moneyBalance(f.cash))
        assertEquals(rs(2000), f.ledger.moneyBalance(upi.id))
        assertEquals(rs(1500), f.ledger.receivable(ramesh.id))
        assertEquals(rs(1500), f.ledger.outstanding(inv.id))
        // Cheque and bank transfer both land in the bank account.
        assertEquals(MoneyAccounts.accountFor(s, PaymentMode.CHEQUE)!!.id, MoneyAccounts.accountFor(s, PaymentMode.BANK_TRANSFER)!!.id)
    }

    @Test
    fun purchaseBillPaidByBankUpdatesStockPayableAndBank(): Unit = runBlocking {
        val abc = f.supplier("ABC")
        val oil = f.product("Oil", buy = rs(100), gstBp = 500)
        val d = BillDraft(DraftKind.PURCHASE, abc.id, today.toString(), today.plusDays(15).toString(), "ABC-55",
            listOf(BillLine(oil.id, "Oil", q(20), "PCS", null)), listOf(BillPayment(PaymentMode.BANK_TRANSFER, rs(1000), "NEFT 1")))
        val quote = s.engine.quotePurchase(d.toPurchaseInput(s, "p", TxnSource.MANUAL))
        assertEquals(rs(2100), quote.totals!!.grandTotalPaise)
        val id = drafts.save(null, DraftKind.PURCHASE, d)
        val bill = s.engine.postPurchase(d.toPurchaseInput(s, id, TxnSource.MANUAL)).ok()
        assertEquals("ABC-55", bill.number)
        assertEquals(q(20), f.ledger.stock(oil.id))
        assertEquals(rs(1100), f.ledger.payable(abc.id))
        assertEquals(-rs(1000), f.ledger.moneyBalance(MoneyAccounts.accountFor(s, PaymentMode.BANK_TRANSFER)!!.id))
        assertEquals(today.plusDays(15).toEpochDay().toInt(), bill.dueDate)
        // The same supplier bill number cannot be entered twice.
        assertEquals(listOf(BooksErrorCode.DUPLICATE_NUMBER), s.engine.quotePurchase(d.toPurchaseInput(s, "p", TxnSource.MANUAL)).errors.map { it.code })
    }

    @Test
    fun paymentPreviewAllocationAndAdvance(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val rice = f.product("Rice", sell = rs(1000), stock = q(100))
        val a = f.sale(ramesh, f.item(rice, q(3)), date = today.minusDays(9)).ok()
        val b = f.sale(ramesh, f.item(rice, q(2))).ok()

        // Oldest first.
        val auto = PaymentDraft(DraftKind.PAYMENT_IN, ramesh.id, rs(4000), PaymentMode.UPI, "UPI-9", today.toString())
        val q1 = s.engine.quotePayment(auto.toInput(s, "p", TxnSource.MANUAL), incoming = true)
        assertEquals(listOf(a.id to rs(3000), b.id to rs(1000)), q1.allocations.map { it.doc.id to it.amountPaise })
        assertEquals(0L, q1.excessPaise)

        // Chosen bills, and more than is due → must be kept as advance.
        val chosen = PaymentDraft(DraftKind.PAYMENT_IN, ramesh.id, rs(6000), PaymentMode.CASH, null, today.toString(), listOf(AllocationDraft(b.id, rs(2000))))
        assertEquals(listOf(BooksErrorCode.OVERPAYMENT), s.engine.quotePayment(chosen.toInput(s, "p", TxnSource.MANUAL), true).errors.map { it.code })
        val withAdvance = chosen.copy(keepAsAdvance = true)
        val q2 = s.engine.quotePayment(withAdvance.toInput(s, "p", TxnSource.MANUAL), true)
        assertEquals(rs(4000), q2.excessPaise)
        val id = drafts.save(null, DraftKind.PAYMENT_IN, withAdvance)
        val pay = s.engine.postPaymentIn(withAdvance.toInput(s, id, TxnSource.MANUAL)).ok()
        assertTrue(pay.isAdvance)
        assertEquals(0L, f.ledger.outstanding(b.id))
        assertEquals(rs(3000), f.ledger.outstanding(a.id))
        assertEquals(-rs(1000), f.ledger.receivable(ramesh.id)) // 5000 billed − 6000 paid

        // The advance settles the older bill.
        s.engine.applyCredit(pay.id, a.id, rs(3000)).ok()
        assertEquals(0L, f.ledger.outstanding(a.id))
        assertEquals(-rs(1000), f.ledger.receivable(ramesh.id))
        assertEquals(rs(6000), f.ledger.moneyBalance(f.cash))
    }

    @Test
    fun returnQuoteMatchesTheCreditNote(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val pen = f.product("Pen", sell = rs("12.40"), gstBp = 1200, stock = q(100))
        val inv = f.sale(ramesh, f.item(pen, q(7))).ok()
        val rd = ReturnDraft(DraftKind.SALE_RETURN, inv.id, today.toString(), listOf(ReturnLine(1, q(3))), "Ink leaking")
        val quote = s.engine.quoteReturn(rd.toInput("p", TxnSource.MANUAL), sale = true)
        assertTrue(quote.ok)
        assertEquals("CN-0001", quote.number)
        val id = drafts.save(null, DraftKind.SALE_RETURN, rd)
        val cn = s.engine.postSaleReturn(rd.toInput(id, TxnSource.MANUAL)).ok()
        assertEquals(quote.totals!!.grandTotalPaise, cn.totalPaise)
        assertEquals(q(96), f.ledger.stock(pen.id))
        assertEquals(inv.totalPaise - cn.totalPaise, f.ledger.receivable(ramesh.id))
        // Cannot return more than is left on the line.
        val tooMany = rd.copy(lines = listOf(ReturnLine(1, q(5))))
        assertEquals(listOf(BooksErrorCode.RETURN_INVALID), s.engine.quoteReturn(tooMany.toInput("p", TxnSource.MANUAL), true).errors.map { it.code })
    }

    @Test
    fun invoiceDocumentReadsEverythingFromTheBooks(): Unit = runBlocking {
        s.masters.updateInvoiceDetails("anbu@okicici", "Anbu Traders\nA/c 1234\nIFSC SBIN0001", "Goods once sold are not taken back").ok()
        val ramesh = f.customer("Ramesh")
        val rice = f.product("Rice", sell = rs(500), stock = q(10))
        val inv = f.sale(ramesh, f.item(rice, q(4)), received = listOf(f.cashPart(rs(500))), dueDate = today.plusDays(7)).ok()
        f.engine.postSaleReturn(com.shopai.app.books.engine.ReturnInput(inv.id, today, listOf(ReturnLine(1, q(1))), "torn", f.meta())).ok()

        val doc = InvoiceDocument.load(s, inv.id)!!
        assertEquals("INVOICE", doc.title) // no GSTIN set → not a tax invoice
        assertEquals(rs(500), doc.paidPaise)
        assertEquals(rs(500), doc.returnedPaise)
        assertEquals(rs(1000), doc.balancePaise)
        assertEquals(f.ledger.outstanding(inv.id), doc.balancePaise)
        val caption = shareCaption(doc)
        assertTrue(caption, caption.contains("INV-0001") && caption.contains("Balance due ₹1,000.00") && caption.contains("UPI: anbu@okicici"))

        val payment = s.dao.linkedTxns(inv.id).first { it.type == TxnType.PAYMENT_IN.name }
        assertEquals("PAYMENT RECEIPT", InvoiceDocument.load(s, payment.id)!!.title)
        assertEquals(listOf(BooksErrorCode.INVALID_FIELD), (s.masters.updateInvoiceDetails("not a upi", null, null) as com.shopai.app.books.engine.MasterResult.Rejected).errors.map { it.code })
    }

    @Test
    fun invoicePdfIsWritten(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val rice = f.product("Rice with a rather long product name to wrap", sell = rs(500), stock = q(100))
        val inv = f.sale(ramesh, *Array(40) { f.item(rice, q(1)) }).ok() // several pages
        val doc = InvoiceDocument.load(s, inv.id)!!
        // Robolectric has no native PDF engine; the PDF itself is checked on the device.
        val nativePdf = runCatching { android.graphics.pdf.PdfDocument().apply { finishPage(startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(10, 10, 1).create())); close() } }.isSuccess
        org.junit.Assume.assumeTrue("native PdfDocument not available on the JVM", nativePdf)
        val file = InvoicePdf.render(ApplicationProvider.getApplicationContext<Context>(), doc)
        assertTrue(file.exists())
        assertEquals("INV-0001.pdf", file.name)
        assertNotNull(Intent())
    }

    @Test
    fun amountsInIndianWords() {
        assertEquals("Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Seventy Eight Paise Only", RupeesInWords.of(rs("123456.78")))
        assertEquals("Rupees Two Crore Five Only", RupeesInWords.of(rs(20_000_005)))
        assertEquals("Rupees Zero and Fifty Paise Only", RupeesInWords.of(50))
        assertFalse(RupeesInWords.of(rs(1000)).contains("  "))
    }
}
