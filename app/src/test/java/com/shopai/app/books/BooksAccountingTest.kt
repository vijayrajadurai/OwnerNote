package com.shopai.app.books

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.CashInInput
import com.shopai.app.books.engine.CashOutInput
import com.shopai.app.books.engine.MoneyOpeningInput
import com.shopai.app.books.engine.PartyOpeningInput
import com.shopai.app.books.engine.PaymentInput
import com.shopai.app.books.engine.OpeningStockInput
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.ReturnInput
import com.shopai.app.books.engine.ReturnLine
import com.shopai.app.books.model.CashInSource
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.SyncState
import com.shopai.app.books.model.TxnSource
import com.shopai.app.books.model.TxnStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The money rules (§37): receivable, payable, stock, returns, payments, cash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BooksAccountingTest {
    private lateinit var db: BooksDatabase
    private lateinit var f: BooksFixture

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BooksDatabase::class.java).build()
        f = BooksFixture(db)
        f.setup()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun saleThenPaymentsClearTheReceivable(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice bag", sell = rs(1000), stock = q(1000))
        val invoice = f.sale(ramesh, f.item(item, q(10))).ok()
        assertEquals(rs(10_000), invoice.totalPaise)
        assertEquals("INV-0001", invoice.number)
        assertEquals(rs(10_000), f.ledger.receivable(ramesh.id))

        f.payIn(ramesh, rs(3000)).ok()
        assertEquals(rs(7000), f.ledger.receivable(ramesh.id))
        assertEquals(rs(7000), f.ledger.outstanding(invoice.id))

        f.payIn(ramesh, rs(7000)).ok()
        assertEquals(0L, f.ledger.receivable(ramesh.id))
        assertEquals(0L, f.ledger.outstanding(invoice.id))
        assertEquals(rs(10_000), f.ledger.moneyBalance(f.cash))
    }

    @Test
    fun purchaseThenPaymentsClearThePayable(): Unit = runBlocking {
        val abc = f.supplier("ABC Traders")
        val item = f.product("Oil tin", buy = rs(2000))
        val bill = f.purchase(abc, "ABC/101", f.item(item, q(10))).ok()
        assertEquals(rs(20_000), f.ledger.payable(abc.id))

        f.payOut(abc, rs(5000)).ok()
        assertEquals(rs(15_000), f.ledger.payable(abc.id))
        f.payOut(abc, rs(15_000)).ok()
        assertEquals(0L, f.ledger.payable(abc.id))
        assertEquals(0L, f.ledger.outstanding(bill.id))
        assertEquals(-rs(20_000), f.ledger.moneyBalance(f.cash))
    }

    @Test
    fun stockFollowsPurchaseSaleAndReturn(): Unit = runBlocking {
        val abc = f.supplier("ABC")
        val ramesh = f.customer("Ramesh")
        val soap = f.product("Soap", sell = rs(50), buy = rs(40))
        f.purchase(abc, "B1", f.item(soap, q(50))).ok()
        val sale = f.sale(ramesh, f.item(soap, q(10))).ok()
        assertEquals(q(40), f.ledger.stock(soap.id))

        val cn = f.engine.postSaleReturn(ReturnInput(sale.id, f.today, listOf(ReturnLine(1, q(2))), "Damaged pack", f.meta())).ok()
        assertEquals("CN-0001", cn.number)
        assertEquals(q(42), f.ledger.stock(soap.id))
        // The credit note reduces what Ramesh owes and what is due on the invoice.
        assertEquals(rs(400), f.ledger.receivable(ramesh.id))
        assertEquals(rs(400), f.ledger.outstanding(sale.id))
        // Stock goes back at the cost it left with: 42 × ₹40.
        assertEquals(rs(1680), f.ledger.stockValue(soap.id))
    }

    @Test
    fun partialPaymentTakenWithTheInvoice(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(1000), stock = q(1000))
        val invoice = f.sale(ramesh, f.item(item, q(10)), received = listOf(f.cashPart(rs(4000)))).ok()
        assertEquals(rs(6000), f.ledger.receivable(ramesh.id))
        assertEquals(rs(6000), f.ledger.outstanding(invoice.id))
        assertEquals(rs(4000), f.ledger.moneyBalance(f.cash))
        val payment = f.dao.linkedTxns(invoice.id).single()
        assertEquals("PI-0001", payment.number)
    }

    @Test
    fun multiplePaymentsSettleOldestDueFirst(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(1000), stock = q(1000))
        val first = f.sale(ramesh, f.item(item, q(5)), date = f.today.minusDays(10)).ok()
        val second = f.sale(ramesh, f.item(item, q(3))).ok()
        f.payIn(ramesh, rs(6000)).ok()
        assertEquals(0L, f.ledger.outstanding(first.id))
        assertEquals(rs(2000), f.ledger.outstanding(second.id))
        assertEquals(rs(2000), f.ledger.receivable(ramesh.id))
        // Explicit allocation to a named invoice.
        f.payIn(ramesh, rs(500), allocations = listOf(second.id to rs(500))).ok()
        assertEquals(rs(1500), f.ledger.outstanding(second.id))
    }

    @Test
    fun overpaymentIsRefusedUnlessKeptAsAdvance(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(1000), stock = q(1000))
        f.sale(ramesh, f.item(item, q(7))).ok()
        assertEquals(listOf(BooksErrorCode.OVERPAYMENT), f.payIn(ramesh, rs(8000)).codes())
        assertEquals(rs(7000), f.ledger.receivable(ramesh.id))

        f.payIn(ramesh, rs(8000), advance = true).ok()
        assertEquals(-rs(1000), f.ledger.receivable(ramesh.id))
        assertEquals(rs(1000), f.ledger.receivables().advancePaise)
        assertEquals(0L, f.ledger.receivables().duePaise)

        // The advance can later be applied to a new invoice.
        val next = f.sale(ramesh, f.item(item, q(2))).ok()
        val advance = f.dao.unappliedCredits(f.ctx.businessId, ramesh.id, listOf("PAYMENT_IN", "SALE_RETURN")).single()
        f.engine.applyCredit(advance.id, next.id, rs(1000)).ok()
        assertEquals(rs(1000), f.ledger.outstanding(next.id))
        assertEquals(rs(1000), f.ledger.receivable(ramesh.id))
    }

    @Test
    fun saleReturnAfterFullPaymentLeavesCreditWithTheCustomer(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(1000), stock = q(1000))
        val sale = f.sale(ramesh, f.item(item, q(5)), received = listOf(f.cashPart(rs(5000)))).ok()
        f.engine.postSaleReturn(ReturnInput(sale.id, f.today, listOf(ReturnLine(1, q(1))), "Wrong item", f.meta())).ok()
        assertEquals(-rs(1000), f.ledger.receivable(ramesh.id))
        assertEquals(0L, f.ledger.outstanding(sale.id))
    }

    @Test
    fun returnsCannotExceedWhatWasSoldAndAddUpExactly(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val item = f.product("Pen", sell = rs("10.10"), gstBp = 1800, stock = q(1000))
        val sale = f.sale(ramesh, f.item(item, q(3))).ok()
        f.engine.postSaleReturn(ReturnInput(sale.id, f.today, listOf(ReturnLine(1, q(1))), "r1", f.meta())).ok()
        f.engine.postSaleReturn(ReturnInput(sale.id, f.today, listOf(ReturnLine(1, q(1))), "r2", f.meta())).ok()
        assertEquals(
            listOf(BooksErrorCode.RETURN_INVALID),
            f.engine.postSaleReturn(ReturnInput(sale.id, f.today, listOf(ReturnLine(1, q(2))), "r3", f.meta())).codes(),
        )
        f.engine.postSaleReturn(ReturnInput(sale.id, f.today, listOf(ReturnLine(1, q(1))), "r3", f.meta())).ok()
        // Three partial returns of the whole invoice give back exactly its tax and value.
        val returned = f.dao.returnedItems(sale.id, "SALE_RETURN")
        val original = f.dao.items(sale.id).single()
        assertEquals(original.taxablePaise, returned.sumOf { it.taxablePaise })
        assertEquals(original.cgstPaise + original.sgstPaise, returned.sumOf { it.cgstPaise + it.sgstPaise })
        assertEquals(0L, f.ledger.netTaxPayable(f.today, f.today))
        assertEquals(0L, f.ledger.receivable(ramesh.id))
    }

    @Test
    fun purchaseReturnMakesADebitNote(): Unit = runBlocking {
        val abc = f.supplier("ABC")
        val item = f.product("Oil", buy = rs(100), gstBp = 500)
        val bill = f.purchase(abc, "X-9", f.item(item, q(50))).ok()
        assertEquals(rs(5250), bill.totalPaise)
        val dn = f.engine.postPurchaseReturn(ReturnInput(bill.id, f.today, listOf(ReturnLine(1, q(5))), "Leaking", f.meta())).ok()
        assertEquals("DN-0001", dn.number)
        assertEquals(rs(525), dn.totalPaise)
        assertEquals(q(45), f.ledger.stock(item.id))
        assertEquals(rs(4725), f.ledger.payable(abc.id))
        assertEquals(rs(4725), f.ledger.outstanding(bill.id))
        assertEquals(rs(4500), f.ledger.stockValue(item.id))
    }

    @Test
    fun cashBookIsOpeningPlusInMinusOut(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val abc = f.supplier("ABC")
        f.engine.postPartyOpening(PartyOpeningInput(abc.id, rs(300), date = f.today.minusDays(5), meta = f.meta())).ok()
        f.engine.postMoneyOpening(MoneyOpeningInput(f.cash, rs(1000), f.today.minusDays(1), f.meta())).ok()
        val item = f.product("Tea", sell = rs(100), stock = q(1000))
        f.sale(ramesh, f.item(item, q(5)), received = listOf(f.cashPart(rs(500)))).ok()
        f.engine.postCashOut(CashOutInput(rs(200), "Rent", f.cash, PaymentMode.CASH, f.today, meta = f.meta())).ok()
        f.payOut(abc, rs(100)).ok()
        f.engine.postCashIn(CashInInput(rs(50), CashInSource.OTHER_INCOME, f.cash, PaymentMode.CASH, f.today, meta = f.meta())).ok()

        val book = f.ledger.cashBook(f.cash, f.today, f.today)
        assertEquals(rs(1000), book.openingPaise)
        assertEquals(rs(550), book.inPaise)
        assertEquals(rs(300), book.outPaise)
        assertEquals(rs(1250), book.closingPaise)
        assertEquals(rs(1250), f.ledger.moneyBalance(f.cash))
        assertEquals(rs(200), f.ledger.payable(abc.id))
        assertEquals(rs(200), f.ledger.expenses(f.today, f.today))
        assertEquals(rs(50), f.ledger.otherIncome(f.today, f.today))
    }

    @Test
    fun receivableAgeingBuckets(): Unit = runBlocking {
        val a = f.customer("A")
        val item = f.product("Rice", sell = rs(100), stock = q(1000))
        f.sale(a, f.item(item, q(1)), date = f.today, dueDate = f.today.plusDays(5)).ok() // current
        f.sale(a, f.item(item, q(2)), date = f.today.minusDays(20)).ok() // 20 days late
        f.sale(a, f.item(item, q(3)), date = f.today.minusDays(45)).ok()
        f.sale(a, f.item(item, q(4)), date = f.today.minusDays(75)).ok()
        f.sale(a, f.item(item, q(5)), date = f.today.minusDays(120)).ok()
        val ageing = f.ledger.receivableAgeing(f.today)
        assertEquals(rs(100), ageing.currentPaise)
        assertEquals(rs(200), ageing.days1to30Paise)
        assertEquals(rs(300), ageing.days31to60Paise)
        assertEquals(rs(400), ageing.days61to90Paise)
        assertEquals(rs(500), ageing.over90Paise)
        assertEquals(f.ledger.receivable(a.id), ageing.totalPaise)
    }

    @Test
    fun homeDueSummarySplitsOverdueSoonAndLater(): Unit = runBlocking {
        val a = f.customer("A")
        val abc = f.supplier("ABC")
        val item = f.product("Rice", sell = rs(100), buy = rs(50), stock = q(1000))
        f.sale(a, f.item(item, q(1)), date = f.today.minusDays(10), dueDate = f.today.minusDays(2)).ok() // overdue 100
        f.sale(a, f.item(item, q(2)), dueDate = f.today.plusDays(3)).ok() // soon 200
        f.sale(a, f.item(item, q(4))).ok() // no due date → later 400
        f.purchase(abc, "P1", f.item(item, q(6))).ok() // payable, no due → later 300
        val c = f.ledger.receivableDue(f.today)
        assertEquals(rs(100), c.overduePaise)
        assertEquals(rs(200), c.dueSoonPaise)
        assertEquals(rs(400), c.laterPaise)
        assertEquals(f.ledger.receivables().duePaise, c.totalPaise)
        assertEquals(rs(300), f.ledger.payableDue(f.today).laterPaise)
    }

    @Test
    fun voiceEntryNeedsAReviewedDraft(): Unit = runBlocking {
        // "Ramesh ku 5000 kuduthen" → Ramesh, payment out, ₹5,000, cash — only after confirmation.
        val ramesh = f.supplier("Ramesh")
        f.engine.postPartyOpening(PartyOpeningInput(ramesh.id, rs(5000), date = f.today, meta = f.meta())).ok()
        val unreviewed = f.engine.postPaymentOut(
            PaymentInput(ramesh.id, rs(5000), f.cash, PaymentMode.CASH, f.today, meta = f.meta(TxnSource.VOICE)),
        )
        assertEquals(listOf(BooksErrorCode.DRAFT_REQUIRED), unreviewed.codes())
        assertEquals(rs(5000), f.ledger.payable(ramesh.id))

        val draftId = f.engine.saveDraft("PAYMENT_OUT", TxnSource.VOICE, """{"party":"Ramesh","amount":500000,"mode":"CASH"}""", "Ramesh ku 5000 kuduthen")
        f.engine.postPaymentOut(PaymentInput(ramesh.id, rs(5000), f.cash, PaymentMode.CASH, f.today, meta = f.meta(TxnSource.VOICE, draftId))).ok()
        assertEquals(0L, f.ledger.payable(ramesh.id))
        assertEquals("CONFIRMED", f.dao.draft(draftId)!!.status)
        // The same draft cannot be posted twice.
        val again = f.engine.postPaymentOut(
            PaymentInput(ramesh.id, rs(5000), f.cash, PaymentMode.CASH, f.today, allowSameDayDuplicate = true, keepExcessAsAdvance = true, meta = f.meta(TxnSource.VOICE, draftId)),
        )
        assertEquals(listOf(BooksErrorCode.DRAFT_REQUIRED), again.codes())
    }

    @Test
    fun kaiChatProductAndOpeningStockNeedTheReviewedDraft(): Unit = runBlocking {
        // Owner's phone (9 Oct 2026): "Colgate 50 box …" → summary → Confirm → "Colgate save aagala". Kai posted the opening
        // stock as a voice entry with no draft, the books refused it, and the product rolled back with it.
        val colgate = f.product("Colgate", sell = rs(20), buy = rs(18), unit = "PCS")
        val noDraft = f.engine.postOpeningStock(OpeningStockInput(colgate.id, f.today, q(2500), meta = f.meta(TxnSource.VOICE)))
        assertEquals(listOf(BooksErrorCode.DRAFT_REQUIRED), noDraft.codes())
        assertEquals(0L, f.ledger.stock(colgate.id))

        // What Kai does now: product + its reviewed draft + opening stock in ONE transaction (as AppKaiTools.createWithOpening).
        var created: com.shopai.app.books.data.ProductEntity? = null
        db.withTransaction {
            val rice = f.masters.createProduct(com.shopai.app.books.engine.ProductInput(name = "Arisi", primaryUnit = "PCS", purchasePricePaise = rs(56), sellingPricePaise = rs(65))).ok()
            val draftId = f.engine.saveDraft("OpeningStockInput", TxnSource.VOICE, """{"by":"kai"}""", "Arisi 1250 PCS")
            f.engine.postOpeningStock(OpeningStockInput(rice.id, f.today, q(1250), meta = f.meta(TxnSource.VOICE, draftId))).ok()
            assertEquals("CONFIRMED", f.dao.draft(draftId)!!.status)
            created = rice
        }
        assertEquals(q(1250), f.ledger.stock(created!!.id))
    }

    @Test
    fun ocrEntryUsesTheSameEngine(): Unit = runBlocking {
        val abc = f.supplier("Indo Burma")
        val item = f.product("Rice 25kg", buy = rs(1200), gstBp = 500)
        val draftId = f.engine.saveDraft("PURCHASE", TxnSource.OCR, "{}", "scanned bill text")
        val bill = f.engine.postPurchase(
            com.shopai.app.books.engine.PurchaseInput(abc.id, "IB-77", f.today, listOf(f.item(item, q(10))), meta = f.meta(TxnSource.OCR, draftId)),
        ).ok()
        assertEquals("OCR", bill.source)
        assertEquals(rs(12_600), f.ledger.payable(abc.id))
        assertEquals(q(10), f.ledger.stock(item.id))
    }

    @Test
    fun everyDocumentBalancesAndIsAuditedAndQueuedForSync(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val abc = f.supplier("ABC")
        val item = f.product("Rice", sell = rs("101.37"), buy = rs("88.10"), gstBp = 1200)
        val bill = f.purchase(abc, "B-1", f.item(item, q(20)), paid = listOf(f.cashPart(rs(100)))).ok()
        val sale = f.sale(ramesh, f.item(item, q("3.5")), received = listOf(f.cashPart(rs(50)))).ok()
        f.engine.postSaleReturn(ReturnInput(sale.id, f.today, listOf(ReturnLine(1, q(1))), "x", f.meta())).ok()
        f.engine.postPurchaseReturn(ReturnInput(bill.id, f.today, listOf(ReturnLine(1, q(2))), "y", f.meta())).ok()

        val all = f.dao.txnsBetween(f.ctx.businessId, 0, Int.MAX_VALUE, null, 100, 0)
        for (t in all) {
            val p = f.dao.postings(t.id)
            assertEquals("${t.type} ${t.number}", p.sumOf { it.debitPaise }, p.sumOf { it.creditPaise })
            assertTrue(f.dao.auditFor(t.id).any { it.action == "CREATE" && it.userId == "owner-1" })
        }
        assertEquals(all.size, f.dao.outboxCount(f.ctx.businessId, SyncState.PENDING.name))
    }

    @Test
    fun cancellingIsASoftDeleteWithReasonAndRestoresBalances(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val abc = f.supplier("ABC")
        val item = f.product("Rice", sell = rs(100), buy = rs(80))
        f.purchase(abc, "B1", f.item(item, q(10))).ok()
        val sale = f.sale(ramesh, f.item(item, q(4))).ok()
        val payment = f.payIn(ramesh, rs(100)).ok()

        assertEquals(listOf(BooksErrorCode.REASON_REQUIRED), f.engine.void(sale.id, " ").codes())
        assertEquals(listOf(BooksErrorCode.HAS_DEPENDENTS), f.engine.void(sale.id, "Wrong customer").codes())
        f.engine.void(payment.id, "Entered twice").ok()
        val voided = f.engine.void(sale.id, "Wrong customer").ok()

        assertEquals(TxnStatus.VOID.name, voided.status)
        assertEquals("Wrong customer", voided.voidReason)
        assertEquals("owner-1", voided.voidedBy)
        assertEquals(0L, f.ledger.receivable(ramesh.id))
        assertEquals(0L, f.ledger.moneyBalance(f.cash))
        assertEquals(q(10), f.ledger.stock(item.id))
        // Still there, never deleted.
        assertEquals(sale.id, f.dao.txn(sale.id)!!.id)
        assertTrue(f.dao.auditFor(sale.id).any { it.action == "VOID" && it.reason == "Wrong customer" })
        assertEquals(listOf(BooksErrorCode.ALREADY_VOID), f.engine.void(sale.id, "again").codes())
    }

    @Test
    fun theSameClientKeyNeverPostsTwice(): Unit = runBlocking {
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(100), stock = q(1000))
        val meta = f.meta()
        val input = com.shopai.app.books.engine.SaleInput(ramesh.id, f.today, listOf(f.item(item, q(1))), meta = meta)
        val queuedBefore = f.dao.outboxCount(f.ctx.businessId, SyncState.PENDING.name)
        val first = f.engine.postSale(input) as PostResult.Posted
        val retry = f.engine.postSale(input) as PostResult.Posted
        assertTrue(retry.duplicate)
        assertEquals(first.txn.id, retry.txn.id)
        assertEquals(rs(100), f.ledger.receivable(ramesh.id))
        assertEquals(q(999), f.ledger.stock(item.id))
        // Sync queue: one row for the sale, none for the retry.
        assertEquals(queuedBefore + 1, f.dao.outboxCount(f.ctx.businessId, SyncState.PENDING.name))
    }

    @Test
    fun booksSurviveAnAppRestart(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("restart-test.db")
        val fileDb = Room.databaseBuilder(context, BooksDatabase::class.java, "restart-test.db").build()
        val first = BooksFixture(fileDb)
        first.setup()
        val ramesh = first.customer("Ramesh")
        val item = first.product("Rice", sell = rs(1000), stock = q(1000))
        first.sale(ramesh, first.item(item, q(10))).ok()
        first.payIn(ramesh, rs(3000)).ok()
        fileDb.close()

        val reopened = Room.databaseBuilder(context, BooksDatabase::class.java, "restart-test.db").build()
        val again = BooksFixture(reopened)
        assertEquals(rs(7000), again.ledger.receivable(ramesh.id))
        // Numbering continues instead of restarting.
        assertEquals("INV-0002", again.sale(ramesh, again.item(item, q(1))).ok().number)
        reopened.close()
        context.deleteDatabase("restart-test.db")
    }
}
