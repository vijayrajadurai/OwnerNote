package com.shopai.app.books

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.engine.ReturnInput
import com.shopai.app.books.engine.ReturnLine
import com.shopai.app.books.integration.BooksImporter
import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.integration.BooksRejectedException
import com.shopai.app.books.integration.ImportOutcome
import com.shopai.app.books.integration.LegacyBridge
import com.shopai.app.books.integration.LegacyLedgerSource
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.TxnSource
import com.shopai.app.data.model.Business
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.CreateInventoryProductInput
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.repository.StockChangeResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.util.Date

/** One-time import of the backend ledger, and the existing screens running on the books afterwards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BooksIntegrationTest {
    private lateinit var db: BooksDatabase
    private lateinit var module: BooksModule
    private val today = LocalDate.of(2026, 10, 1)

    private class FakeSource : LegacyLedgerSource {
        var failProducts = false
        val business = Business("biz-77", "user-9", "Anbu", "Anbu Traders", "GROCERY", "Chennai", null, null)
        var customers = listOf(
            PartySummary("c1", "Ramesh", "+919876543210", 7000.0, "2026-10-10T00:00:00Z"),
            PartySummary("c2", "Ramesh", null, 0.0, null), // same name, nothing due
            PartySummary("c3", "Kumar", "12345", 1234.5, null), // bad phone → kept in notes
            PartySummary("c4", "Advance Anna", null, -500.0, null), // paid in advance
        )
        var suppliers = listOf(PartySummary("s1", "ABC Traders", "9000000001", 15000.0, "2026-09-20T00:00:00Z"))
        var products = listOf(
            InventoryProduct("p1", "biz-77", "Ponni Rice", "Grocery", unit = "bag", currentStock = 12.5, minimumStock = 5.0,
                purchasePrice = 1200.0, sellingPrice = 1350.0, mrp = 1300.0, gstRate = 5.0, supplierId = "s1", barcode = "8901",
                createdAt = "", updatedAt = ""),
            InventoryProduct("p2", "biz-77", "Odd tax item", "Misc", unit = "pcs", currentStock = 0.0, minimumStock = 0.0,
                gstRate = 3.5, barcode = "8901", createdAt = "", updatedAt = ""),
        )

        override suspend fun business() = business
        override suspend fun customers() = customers
        override suspend fun suppliers() = suppliers
        override suspend fun products(): List<InventoryProduct> {
            if (failProducts) throw IOException("offline")
            return products
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("owner_books", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, BooksDatabase::class.java).build()
        module = BooksModule(context) { db }
    }

    @After
    fun tearDown() = db.close()

    private fun importer(source: FakeSource) = BooksImporter(module, source) { today }

    private suspend fun bridge() = LegacyBridge(module.session()!!) { today }

    @Test
    fun importBringsPartiesBalancesAndProductsOnce(): Unit = runBlocking {
        val source = FakeSource()
        assertNull(module.session())
        val outcome = importer(source).runIfNeeded() as ImportOutcome.Imported
        assertEquals(4, outcome.customers)
        assertEquals(1, outcome.suppliers)
        assertEquals(2, outcome.products)

        val s = module.session()!!
        val ledger = s.ledger
        val ramesh = s.dao.partyByBackendId("biz-77", "c1")!!
        assertEquals(rs(7000), ledger.receivable(ramesh.id))
        assertEquals("9876543210", ramesh.mobile)
        assertEquals(LocalDate.of(2026, 10, 10).toEpochDay().toInt(), s.dao.openDocs("biz-77", ramesh.id, listOf("OPENING_BALANCE")).single().dueDate)
        assertEquals(0L, ledger.receivable(s.dao.partyByBackendId("biz-77", "c2")!!.id))
        val kumar = s.dao.partyByBackendId("biz-77", "c3")!!
        assertEquals(rs("1234.50"), ledger.receivable(kumar.id))
        assertNull(kumar.mobile)
        assertEquals("Phone: 12345", kumar.notes)
        assertEquals(-rs(500), ledger.receivable(s.dao.partyByBackendId("biz-77", "c4")!!.id))
        val abc = s.dao.partyByBackendId("biz-77", "s1")!!
        assertEquals(rs(15_000), ledger.payable(abc.id))

        val rice = s.dao.productByBackendId("biz-77", "p1")!!
        assertEquals("BAG", rice.primaryUnit)
        assertEquals(500, rice.gstBp)
        assertEquals(abc.id, rice.supplierId)
        assertNull(rice.mrpPaise) // selling above MRP on the old record: MRP dropped, never guessed
        assertEquals(q("12.5"), ledger.stock(rice.id))
        assertEquals(rs(15_000), ledger.stockValue(rice.id))
        // The second product's barcode clashed; the odd 3.5% rate was added to the business's slabs.
        val odd = s.dao.productByBackendId("biz-77", "p2")!!
        assertNull(odd.barcode)
        assertEquals(350, odd.gstBp)

        // Second run: nothing is brought twice.
        assertEquals(ImportOutcome.AlreadyImported, importer(source).runIfNeeded())
        assertEquals(4, s.dao.parties("biz-77", "CUSTOMER").size)
        assertEquals(rs(7000), ledger.receivable(ramesh.id))
    }

    @Test
    fun anImportCutShortResumesWithoutDuplicates(): Unit = runBlocking {
        val source = FakeSource().apply { failProducts = true }
        assertTrue(importer(source).runIfNeeded() is ImportOutcome.Failed)
        // Still on the old ledger: nothing half-imported is in use.
        assertNull(module.session())

        source.failProducts = false
        importer(source).runIfNeeded() as ImportOutcome.Imported
        val s = module.session()!!
        assertEquals(4, s.dao.parties("biz-77", "CUSTOMER").size)
        assertEquals(rs(7000), s.ledger.receivable(s.dao.partyByBackendId("biz-77", "c1")!!.id))
    }

    @Test
    fun customersAndCreditEntriesRunOnTheBooks(): Unit = runBlocking {
        importer(FakeSource()).runIfNeeded()
        val b = bridge()
        val ramesh = b.customers().first { it.pendingTotal == 7000.0 }

        // Old ids from the backend still open the same customer.
        assertEquals(ramesh.id, b.customer("c1").id)

        // An amount-only credit keeps the exact amount (no round-off) and creates the customer by name.
        val credit = b.createCredit(CreateCreditInput(customerName = "Selvi", amount = 1234.5, description = "Rice + oil", dueDate = "2026-10-15T00:00:00Z"), TxnSource.MANUAL)
        assertEquals("1234.50", credit.amount)
        assertEquals("PENDING", credit.status)
        val selvi = b.customers().single { it.name == "Selvi" }
        assertEquals(1234.5, selvi.pendingTotal, 0.0)

        val part = b.addCreditPayment(credit.id, 234.5, "UPI ref 11")
        assertEquals("PARTIALLY_PAID", part.status)
        assertEquals("234.50", part.paidAmount)
        assertEquals("UPI ref 11", part.payments.single().note)

        val tooMuch = runCatching { b.addCreditPayment(credit.id, 5000.0, null) }.exceptionOrNull()
        assertTrue(tooMuch is BooksRejectedException)
        assertTrue(tooMuch!!.message!!.contains("only ₹1000.00 due"))

        val done = b.markCreditPaid(credit.id)
        assertEquals("PAID", done.status)
        assertEquals(0.0, b.customers().single { it.name == "Selvi" }.pendingTotal, 0.0)

        // Paying the imported opening balance works the same way.
        val opening = b.customer(ramesh.id).transactions.single()
        b.addCreditPayment(opening.id, 2000.0, null)
        assertEquals(5000.0, b.customers().single { it.id == ramesh.id }.pendingTotal, 0.0)
        assertEquals(rs(5000), module.session()!!.ledger.receivable(ramesh.id))
    }

    @Test
    fun voiceAndScannedEntriesAreKeptAsConfirmedDrafts(): Unit = runBlocking {
        importer(FakeSource()).runIfNeeded()
        val b = bridge()
        val debit = b.createDebit(CreateDebitInput(supplierName = "ABC Traders", amount = 500.0), TxnSource.VOICE)
        val s = module.session()!!
        val txn = s.dao.txn(debit.id)!!
        assertEquals("VOICE", txn.source)
        assertTrue(txn.number.startsWith("PUR-"))
        assertEquals(rs(15_500), s.ledger.payable(s.dao.partyByBackendId("biz-77", "s1")!!.id))
        b.markDebitPaid(debit.id)
        assertEquals(rs(15_000), s.ledger.payable(s.dao.partyByBackendId("biz-77", "s1")!!.id))
    }

    @Test
    fun cashFlowComesFromTheBooks(): Unit = runBlocking {
        importer(FakeSource()).runIfNeeded()
        val flow = bridge().cashFlow()
        assertEquals(8234.5, flow.totalReceivables, 0.0) // 7000 + 1234.50 (the advance is not "due")
        assertEquals(15000.0, flow.totalPayables, 0.0)
        // Ramesh is due 10 Oct (in 7 days? no — 9 days); Kumar has no due date (due now); ABC was due 20 Sep.
        assertEquals(1234.5, flow.next7Days.expectedCollections, 0.0)
        assertEquals(8234.5, flow.next30Days.expectedCollections, 0.0)
        assertEquals(15000.0, flow.next7Days.expectedPayments, 0.0)
    }

    @Test
    fun inventoryScreensRunOnTheBooks(): Unit = runBlocking {
        importer(FakeSource()).runIfNeeded()
        val b = bridge()
        val created = b.createProduct(
            CreateInventoryProductInput(name = "Sugar", category = "Grocery", unit = "kg", currentStock = 20.0, minimumStock = 25.0, purchasePrice = 40.0, sellingPrice = 45.0, gstRate = 5.0),
        )
        assertEquals("KG", created.unit)
        assertEquals(20.0, created.currentStock, 0.0)
        assertTrue(b.lowStockProducts().any { it.id == created.id })

        val out = b.stockChange(created.id, 30.0, "sold loose", isIn = false)
        assertEquals(StockChangeResult.InsufficientStock(20.0), out)
        b.stockChange(created.id, 3.0, "damaged bag", isIn = false)
        b.stockChange(created.id, 10.0, "count correction", isIn = true)
        assertEquals(27.0, b.product(created.id).currentStock, 0.0)

        val history = b.movements(created.id)
        assertEquals(listOf(27.0, 17.0, 20.0), history.map { it.balanceAfter })
        assertEquals("OUT", history[1].type)
        assertTrue(history[1].reason.contains("damaged bag"))
        assertEquals("DAMAGED", module.session()!!.dao.txn(history[1].id.substringBefore(':'))!!.category)

        val summary = b.intelligenceSummary(Date())
        assertEquals(3, summary.totalProducts)

        // A rejected product (duplicate barcode) leaves nothing behind — not even its opening stock.
        val dup = runCatching { b.createProduct(CreateInventoryProductInput(name = "Copy", category = "X", unit = "pcs", currentStock = 5.0, minimumStock = 0.0, barcode = "8901")) }
        assertTrue(dup.exceptionOrNull() is BooksRejectedException)
        assertEquals(3, b.products().size)
    }

    @Test
    fun returnsShowAgainstTheOriginalEntry(): Unit = runBlocking {
        importer(FakeSource()).runIfNeeded()
        val s = module.session()!!
        val b = bridge()
        val rice = s.dao.productByBackendId("biz-77", "p1")!!
        val ramesh = s.dao.partyByBackendId("biz-77", "c1")!!
        val sale = s.engine.postSale(
            com.shopai.app.books.engine.SaleInput(ramesh.id, LocalDate.now(), listOf(com.shopai.app.books.engine.ItemInput(productId = rice.id, qtyMilli = q(2))), meta = com.shopai.app.books.engine.PostMeta("k1")),
        ).ok()
        s.engine.postSaleReturn(ReturnInput(sale.id, LocalDate.now(), listOf(ReturnLine(1, q(1))), "torn", com.shopai.app.books.engine.PostMeta("k2"))).ok()
        val shown = b.customer(ramesh.id).transactions.first { it.id == sale.id }
        assertEquals("PARTIALLY_PAID", shown.status)
        assertTrue(shown.payments.single().note!!.startsWith("Return CN-"))
        assertNotNull(shown.description)
    }

    @Test
    fun signingOutHandsTheBooksBackToTheBackendForTheNextAccount(): Unit = runBlocking {
        importer(FakeSource()).runIfNeeded()
        assertNotNull(module.session())
        module.signOut()
        assertNull(module.session())
        // The same owner signing in again picks up the same books without importing again.
        assertEquals(ImportOutcome.AlreadyImported, importer(FakeSource()).runIfNeeded())
        assertNotNull(module.session())
        assertEquals(PartyKind.CUSTOMER.name, module.session()!!.dao.partyByBackendId("biz-77", "c1")!!.kind)
    }
}
