package com.shopai.app.books

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.data.HsnSacEntity
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.ItemInput
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.OpeningStockInput
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.ProductInput
import com.shopai.app.books.engine.ReturnInput
import com.shopai.app.books.engine.ReturnLine
import com.shopai.app.books.engine.StockAdjustmentInput
import com.shopai.app.books.model.AdjustmentReason
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.tax.Gstin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Data validation (§27), GST/HSN (§3, §26), units, batches, business types. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BooksValidationTest {
    private lateinit var db: BooksDatabase
    private lateinit var f: BooksFixture

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BooksDatabase::class.java).build()
        f = BooksFixture(db)
    }

    @After
    fun tearDown() = db.close()

    private fun codes(r: MasterResult<*>) = (r as MasterResult.Rejected).errors.map { it.code }

    @Test
    fun duplicateInvoiceAndBillNumbers(): Unit = runBlocking {
        f.setup()
        val ramesh = f.customer("Ramesh")
        val abc = f.supplier("ABC")
        val xyz = f.supplier("XYZ")
        val item = f.product("Rice", sell = rs(100), buy = rs(80), stock = q(100))
        f.sale(ramesh, f.item(item, q(1)), number = "A-1").ok()
        assertEquals(listOf(BooksErrorCode.DUPLICATE_NUMBER), f.sale(ramesh, f.item(item, q(1)), number = "A-1").codes())
        f.purchase(abc, "77", f.item(item, q(5))).ok()
        assertEquals(listOf(BooksErrorCode.DUPLICATE_NUMBER), f.purchase(abc, "77", f.item(item, q(5))).codes())
        // Another supplier may use the same bill number.
        f.purchase(xyz, "77", f.item(item, q(5))).ok()
    }

    @Test
    fun duplicatePaymentOnTheSameDayIsCaught(): Unit = runBlocking {
        f.setup()
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(1000), stock = q(100))
        f.sale(ramesh, f.item(item, q(10))).ok()
        f.payIn(ramesh, rs(2000), reference = "UPI-1").ok()
        assertEquals(listOf(BooksErrorCode.DUPLICATE_PAYMENT), f.payIn(ramesh, rs(2000), reference = "UPI-1").codes())
        assertEquals(rs(8000), f.ledger.receivable(ramesh.id))
    }

    @Test
    fun stockCannotGoNegativeUnlessAllowed(): Unit = runBlocking {
        f.setup()
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(100))
        assertEquals(listOf(BooksErrorCode.INSUFFICIENT_STOCK), f.sale(ramesh, f.item(item, q(1))).codes())
        // Two lines of the same product are checked together.
        f.engine.postOpeningStock(OpeningStockInput(item.id, f.today, q(3), meta = f.meta())).ok()
        assertEquals(listOf(BooksErrorCode.INSUFFICIENT_STOCK), f.sale(ramesh, f.item(item, q(2)), f.item(item, q(2))).codes())
        f.masters.updateSettings(allowNegativeStock = true).ok()
        f.sale(ramesh, f.item(item, q(5))).ok()
        assertEquals(-q(2), f.ledger.stock(item.id))
    }

    @Test
    fun invalidQuantitiesPricesDatesAndAmounts(): Unit = runBlocking {
        f.setup()
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(100))
        f.engine.postOpeningStock(OpeningStockInput(item.id, f.today, q(100), meta = f.meta())).ok()
        assertEquals(listOf(BooksErrorCode.INVALID_QUANTITY), f.sale(ramesh, f.item(item, -q(1))).codes())
        assertEquals(listOf(BooksErrorCode.INVALID_QUANTITY), f.sale(ramesh, f.item(item, 0)).codes())
        assertEquals(listOf(BooksErrorCode.INVALID_PRICE), f.sale(ramesh, f.item(item, q(1), rate = -1)).codes())
        assertEquals(listOf(BooksErrorCode.FUTURE_DATE), f.sale(ramesh, f.item(item, q(1)), date = f.today.plusDays(1)).codes())
        assertEquals(listOf(BooksErrorCode.INVALID_AMOUNT), f.payIn(ramesh, 0).codes())
        assertEquals(listOf(BooksErrorCode.NO_ITEMS), f.sale(ramesh).codes())
        assertEquals(listOf(BooksErrorCode.PARTY_REQUIRED), f.sale(null, f.item(item, q(1))).codes())
        assertEquals(listOf(BooksErrorCode.OVERPAYMENT), f.sale(ramesh, f.item(item, q(1)), received = listOf(f.cashPart(rs(101)))).codes())
        // A walk-in cash sale needs no customer when it is fully paid.
        f.sale(null, f.item(item, q(1)), received = listOf(f.cashPart(rs(100)))).ok()
        f.masters.updateSettings(allowFutureDates = true).ok()
        f.sale(ramesh, f.item(item, q(1)), date = f.today.plusDays(1)).ok()
    }

    @Test
    fun gstRateMustBeOneOfTheConfiguredSlabs(): Unit = runBlocking {
        f.setup()
        val ramesh = f.customer("Ramesh")
        val svc = f.product("Repair", sell = rs(100), isService = true)
        assertEquals(listOf(BooksErrorCode.INVALID_GST), f.sale(ramesh, ItemInput(productId = svc.id, qtyMilli = q(1), gstBp = 1300)).codes())
        // Slabs are data, not code: add 13% and it is accepted.
        f.masters.updateSettings(gstRatesBp = listOf(0, 500, 1200, 1300, 1800, 2800)).ok()
        f.sale(ramesh, ItemInput(productId = svc.id, qtyMilli = q(1), gstBp = 1300)).ok()
        val exempt = f.product("Milk", sell = rs(30), taxType = TaxType.EXEMPT)
        f.engine.postOpeningStock(OpeningStockInput(exempt.id, f.today, q(10), meta = f.meta())).ok()
        assertEquals(listOf(BooksErrorCode.INVALID_GST), f.sale(ramesh, ItemInput(productId = exempt.id, qtyMilli = q(1), gstBp = 500)).codes())
    }

    @Test
    fun interStateSaleGetsIgstAndLocalSaleGetsCgstSgst(): Unit = runBlocking {
        f.setup(state = "33")
        val local = f.customer("Chennai Stores", state = "33")
        val first14 = "29ABCDE1234F1Z"
        val blr = f.customer("Bengaluru Mart", gstin = first14 + Gstin.checkChar(first14))
        val item = f.product("Saree", sell = rs(1000), gstBp = 500, isService = false)
        f.engine.postOpeningStock(OpeningStockInput(item.id, f.today, q(10), meta = f.meta())).ok()
        val a = f.sale(local, f.item(item, q(1))).ok()
        assertEquals(rs(25), a.cgstPaise)
        assertEquals(rs(25), a.sgstPaise)
        assertEquals(0L, a.igstPaise)
        val b = f.sale(blr, f.item(item, q(1))).ok()
        assertTrue(b.interState)
        assertEquals("29", b.placeOfSupply)
        assertEquals(rs(50), b.igstPaise)
        assertEquals(0L, b.cgstPaise + b.sgstPaise)
        assertEquals(rs(100), f.ledger.netTaxPayable(f.today, f.today))
    }

    @Test
    fun hsnIsNeverInventedOnlyVerifiedOrConfirmed(): Unit = runBlocking {
        f.setup()
        // Not in the master → verification required until the owner confirms.
        val notListed = f.masters.createProduct(ProductInput(name = "Rice", hsnCode = "1006", sellingPricePaise = rs(50)))
        assertEquals(listOf(BooksErrorCode.INVALID_HSN), codes(notListed))
        assertEquals("HSN verification required", (notListed as MasterResult.Rejected).errors.single().message)
        assertEquals(listOf(BooksErrorCode.INVALID_HSN), codes(f.masters.createProduct(ProductInput(name = "Dal", hsnCode = "10A"))))
        val confirmed = f.product("Rice", sell = rs(50), hsn = "1006", hsnConfirmed = true)
        assertTrue(confirmed.hsnConfirmedByUser)
        assertTrue(!confirmed.hsnVerified)

        // Once the admin master has the code, it is verified.
        f.masters.importHsnMaster(listOf(HsnSacEntity("0713", "HSN", "Dried leguminous vegetables", gstBp = 0, source = "test", updatedAt = 0)))
        val dal = f.product("Toor dal", sell = rs(120), hsn = "0713")
        assertTrue(dal.hsnVerified)
    }

    @Test
    fun hsnWiseSalesSummaryIsNetOfReturns(): Unit = runBlocking {
        f.setup()
        f.masters.importHsnMaster(
            listOf(
                HsnSacEntity("1006", "HSN", "Rice", source = "test", updatedAt = 0),
                HsnSacEntity("0713", "HSN", "Pulses", source = "test", updatedAt = 0),
            ),
        )
        val ramesh = f.customer("Ramesh")
        val rice = f.product("Rice", sell = rs(100), gstBp = 500, hsn = "1006")
        val dal = f.product("Dal", sell = rs(200), gstBp = 500, hsn = "0713")
        listOf(rice, dal).forEach { f.engine.postOpeningStock(OpeningStockInput(it.id, f.today, q(50), meta = f.meta())).ok() }
        val sale = f.sale(ramesh, f.item(rice, q(10)), f.item(dal, q(2))).ok()
        f.sale(ramesh, f.item(rice, q(5))).ok()
        f.engine.postSaleReturn(ReturnInput(sale.id, f.today, listOf(ReturnLine(1, q(1))), "torn bag", f.meta())).ok()

        val rows = f.ledger.hsnSummary(sales = true, from = f.today, to = f.today).associateBy { it.hsnCode }
        assertEquals(q(14), rows["1006"]!!.qtyMilli)
        assertEquals(rs(1400), rows["1006"]!!.taxablePaise)
        assertEquals(rs(35), rows["1006"]!!.cgstPaise)
        assertEquals(rs(35), rows["1006"]!!.sgstPaise)
        assertEquals(rs(400), rows["0713"]!!.taxablePaise)
        assertEquals(0L, rows["0713"]!!.igstPaise)
    }

    @Test
    fun unitConversionBoxesInPiecesOut(): Unit = runBlocking {
        f.setup()
        val abc = f.supplier("ABC")
        val ramesh = f.customer("Ramesh")
        val pen = f.product("Pen", sell = rs(10), buy = rs(6), unit = "PCS", secondary = "BOX", conversion = q(12))
        f.purchase(abc, "P1", f.item(pen, q(5), rate = rs(72), unit = "BOX")).ok()
        assertEquals(q(60), f.ledger.stock(pen.id))
        f.sale(ramesh, f.item(pen, q(10))).ok()
        assertEquals(q(50), f.ledger.stock(pen.id))
        // Selling a box at the list price uses 12 × the piece price.
        val box = f.sale(ramesh, f.item(pen, q(1), unit = "BOX")).ok()
        assertEquals(rs(120), box.totalPaise)
        assertEquals(q(38), f.ledger.stock(pen.id))
        assertEquals(listOf(BooksErrorCode.INVALID_UNIT), f.sale(ramesh, f.item(pen, q(1), unit = "KG")).codes())
    }

    @Test
    fun decimalQuantitiesTamilNamesAndLargeAmounts(): Unit = runBlocking {
        f.setup()
        val abc = f.supplier("அன்பு டிரேடர்ஸ்")
        val ramesh = f.customer("ரமேஷ் Ramesh")
        val rice = f.product("அரிசி Rice Ponni", sell = rs("58.50"), buy = rs("49.75"), unit = "KG")
        f.purchase(abc, "TN-1", f.item(rice, q("10.5"))).ok()
        f.sale(ramesh, f.item(rice, q("2.25"))).ok()
        assertEquals(q("8.25"), f.ledger.stock(rice.id))
        assertEquals(rs(132), f.ledger.receivable(ramesh.id)) // 2.25 × 58.50 = 131.625 → ₹131.63 → rounds to ₹132
        assertEquals(rice.id, f.dao.searchProducts(f.ctx.businessId, "அரிசி", 10, 0).single().id)
        assertEquals(rice.id, f.dao.searchProducts(f.ctx.businessId, "ponni", 10, 0).single().id)

        val gold = f.product("Gold bar", sell = rs("999999999.99"), unit = "PCS")
        f.engine.postOpeningStock(OpeningStockInput(gold.id, f.today, q(10), valuePaise = rs(5_000_000_000L), meta = f.meta())).ok()
        f.sale(ramesh, f.item(gold, q(9))).ok()
        assertEquals(rs("9000000000") + rs(132), f.ledger.receivable(ramesh.id))
    }

    @Test
    fun stockAdjustmentsNeedAReasonAndPhysicalCountPostsTheDifference(): Unit = runBlocking {
        f.setup()
        val item = f.product("Biscuits", buy = rs(10))
        f.engine.postOpeningStock(OpeningStockInput(item.id, f.today, q(40), meta = f.meta())).ok()
        f.engine.postStockAdjustment(StockAdjustmentInput(item.id, f.today, AdjustmentReason.DAMAGED, changeQtyMilli = -q(3), meta = f.meta())).ok()
        assertEquals(q(37), f.ledger.stock(item.id))
        assertEquals(
            listOf(BooksErrorCode.REASON_REQUIRED),
            f.engine.postStockAdjustment(StockAdjustmentInput(item.id, f.today, AdjustmentReason.OTHER, changeQtyMilli = -q(1), meta = f.meta())).codes(),
        )
        assertEquals(
            listOf(BooksErrorCode.INSUFFICIENT_STOCK),
            f.engine.postStockAdjustment(StockAdjustmentInput(item.id, f.today, AdjustmentReason.MISSING, changeQtyMilli = -q(100), meta = f.meta())).codes(),
        )
        val count = f.engine.postStockAdjustment(StockAdjustmentInput(item.id, f.today, AdjustmentReason.PHYSICAL_COUNT, countedQtyMilli = q(30), meta = f.meta())).ok()
        assertEquals(q(30), f.ledger.stock(item.id))
        assertEquals("ADJUSTMENT_OUT", f.dao.movements(count.id).single().type)
        assertEquals(rs(300), f.ledger.stockValue(item.id))
    }

    @Test
    fun barcodeBelongsToOneActiveProduct(): Unit = runBlocking {
        f.setup()
        val a = f.product("Soap A", barcode = "8901234567890")
        assertEquals(listOf(BooksErrorCode.DUPLICATE_BARCODE), codes(f.masters.createProduct(ProductInput(name = "Soap B", barcode = "8901234567890"))))
        f.masters.setProductArchived(a.id, archived = true).ok()
        val b = f.product("Soap B", barcode = "8901234567890")
        assertEquals(b.id, f.dao.activeProductsByBarcode(f.ctx.businessId, "8901234567890").single().id)
        // The archived one cannot come back while the barcode is taken.
        assertEquals(listOf(BooksErrorCode.DUPLICATE_BARCODE), codes(f.masters.setProductArchived(a.id, archived = false)))
    }

    @Test
    fun duplicateProductAndPartyNames(): Unit = runBlocking {
        f.setup()
        f.product("Rice Ponni")
        assertEquals(listOf(BooksErrorCode.DUPLICATE_NAME), codes(f.masters.createProduct(ProductInput(name = "  rice   PONNI "))))
        f.masters.createProduct(ProductInput(name = "rice ponni", allowSimilarName = true)).ok()
        f.customer("Ramesh")
        assertEquals(listOf(BooksErrorCode.DUPLICATE_NAME), codes(f.masters.createParty(PartyInput(PartyKind.CUSTOMER, "ramesh"))))
        // A supplier may share a customer's name.
        f.supplier("Ramesh")
        assertEquals(listOf(BooksErrorCode.INVALID_GST), codes(f.masters.createParty(PartyInput(PartyKind.CUSTOMER, "Bad GST", gstin = "33ABCDE1234F1X5"))))
    }

    @Test
    fun serviceBusinessKeepsNoStockAndServicesNeverMoveStock(): Unit = runBlocking {
        f.setup(type = BusinessType.SERVICE)
        val ramesh = f.customer("Ramesh")
        val consult = f.product("Consultation", sell = rs(500), isService = true)
        val sale = f.sale(ramesh, f.item(consult, q(2))).ok()
        assertTrue(f.dao.movements(sale.id).isEmpty())
        assertEquals(rs(1000), f.ledger.receivable(ramesh.id))
    }

    @Test
    fun productAndServiceBusinessMovesStockOnlyForProducts(): Unit = runBlocking {
        f.setup(type = BusinessType.PRODUCT_AND_SERVICE)
        val ramesh = f.customer("Ramesh")
        val fan = f.product("Fan", sell = rs(1500))
        val fitting = f.product("Fitting charge", sell = rs(200), isService = true)
        f.engine.postOpeningStock(OpeningStockInput(fan.id, f.today, q(5), meta = f.meta())).ok()
        val sale = f.sale(ramesh, f.item(fan, q(1)), f.item(fitting, q(1))).ok()
        assertEquals(1, f.dao.movements(sale.id).size)
        assertEquals(q(4), f.ledger.stock(fan.id))
    }

    @Test
    fun batchTrackingIsOptionalAndBatchStockIsKeptSeparately(): Unit = runBlocking {
        f.setup()
        val abc = f.supplier("Pharma Dist")
        val ramesh = f.customer("Ramesh")
        val tab = f.product("Paracetamol", sell = rs(20), buy = rs(12), batchTracked = true)
        // The business has batch tracking off — no batch needed.
        f.purchase(abc, "D1", f.item(tab, q(10))).ok()
        f.masters.updateSettings(batchTracking = true).ok()
        assertEquals(listOf(BooksErrorCode.BATCH_REQUIRED), f.purchase(abc, "D2", f.item(tab, q(10))).codes())
        f.purchase(
            abc, "D2",
            ItemInput(productId = tab.id, qtyMilli = q(20), batchNo = "B-1", mfgDate = f.today.minusMonths(2), expiryDate = f.today.plusMonths(10)),
        ).ok()
        assertEquals(listOf(BooksErrorCode.BATCH_REQUIRED), f.sale(ramesh, f.item(tab, q(1))).codes())
        assertEquals(listOf(BooksErrorCode.BATCH_NOT_FOUND), f.sale(ramesh, f.item(tab, q(1), batch = "NOPE")).codes())
        f.sale(ramesh, f.item(tab, q(5), batch = "B-1")).ok()
        val batch = f.dao.batchByNo(tab.id, "B-1")!!
        assertEquals(q(15), f.ledger.batchStock(tab.id, batch.id))
        assertEquals(q(25), f.ledger.stock(tab.id))
        assertEquals(listOf(BooksErrorCode.INSUFFICIENT_STOCK), f.sale(ramesh, f.item(tab, q(16), batch = "B-1")).codes())
    }

    @Test
    fun minimumSellingPriceIsEnforced(): Unit = runBlocking {
        f.setup()
        val ramesh = f.customer("Ramesh")
        val item = f.product("Phone cover", sell = rs(200), minSelling = rs(150))
        f.engine.postOpeningStock(OpeningStockInput(item.id, f.today, q(10), meta = f.meta())).ok()
        assertEquals(listOf(BooksErrorCode.BELOW_MIN_SELLING_PRICE), f.sale(ramesh, f.item(item, q(1), rate = rs(140))).codes())
        f.sale(ramesh, f.item(item, q(1), rate = rs(150))).ok()
    }

    @Test
    fun cancellingAPurchaseCannotMakeStockNegative(): Unit = runBlocking {
        f.setup()
        val abc = f.supplier("ABC")
        val ramesh = f.customer("Ramesh")
        val item = f.product("Rice", sell = rs(100), buy = rs(80))
        val bill = f.purchase(abc, "B1", f.item(item, q(10))).ok()
        f.sale(ramesh, f.item(item, q(8))).ok()
        assertEquals(listOf(BooksErrorCode.INSUFFICIENT_STOCK), f.engine.void(bill.id, "wrong bill").codes())
        assertEquals(q(2), f.ledger.stock(item.id))
    }
}
