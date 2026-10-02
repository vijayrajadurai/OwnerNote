package com.shopai.app.books

import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.data.ProductEntity
import com.shopai.app.books.data.TxnEntity
import com.shopai.app.books.engine.BooksContext
import com.shopai.app.books.engine.BooksEngine
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.BooksLedger
import com.shopai.app.books.engine.BooksMasters
import com.shopai.app.books.engine.BusinessSetup
import com.shopai.app.books.engine.ItemInput
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.PaymentInput
import com.shopai.app.books.engine.PaymentPart
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.ProductInput
import com.shopai.app.books.engine.PurchaseInput
import com.shopai.app.books.engine.SaleInput
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.model.TxnSource
import java.time.LocalDate

fun rs(v: String): Long = Money.ofRupees(v)
fun rs(v: Long): Long = Money.ofRupees(v)
fun q(v: Long): Long = Qty.of(v)
fun q(v: String): Long = Qty.of(v)

fun PostResult.ok(): TxnEntity = when (this) {
    is PostResult.Posted -> txn
    is PostResult.Rejected -> throw AssertionError("Rejected: $errors")
}

fun PostResult.codes(): List<BooksErrorCode> = (this as? PostResult.Rejected)?.errors?.map { it.code }
    ?: throw AssertionError("Expected a rejection but it posted")

fun <T> MasterResult<T>.ok(): T = when (this) {
    is MasterResult.Ok -> value
    is MasterResult.Rejected -> throw AssertionError("Rejected: $errors")
}

/** One business on an in-memory (or file) books database, with a fixed "today". */
class BooksFixture(val db: BooksDatabase) {
    var today: LocalDate = LocalDate.of(2026, 10, 1)
    private var clock = 1_700_000_000_000L
    private var keySeq = 0
    val ctx = BooksContext(businessId = "biz-1", branchId = "br-1", warehouseId = "wh-1", userId = "owner-1")
    val engine = BooksEngine(db, ctx, now = { clock++ }, today = { today })
    val masters = BooksMasters(db, ctx, now = { clock++ })
    val ledger = BooksLedger(db, ctx.businessId)
    val dao get() = db.dao()
    val cash get() = masters.cashAccountId()

    /** Unique per fixture, so a reopened database never sees a reused idempotency key. */
    private val keyPrefix = java.util.UUID.randomUUID().toString().take(8)

    fun meta(source: TxnSource = TxnSource.MANUAL, draftId: String? = null) = PostMeta(clientKey = "$keyPrefix-${keySeq++}", source = source, draftId = draftId)

    suspend fun setup(type: BusinessType = BusinessType.RETAILER, state: String = "33") =
        masters.setupBusiness(BusinessSetup(name = "Anbu Traders", type = type, stateCode = state)).ok()

    suspend fun customer(name: String, state: String? = null, gstin: String? = null): PartyEntity =
        masters.createParty(PartyInput(PartyKind.CUSTOMER, name, stateCode = state, gstin = gstin)).ok()

    suspend fun supplier(name: String, state: String? = null): PartyEntity =
        masters.createParty(PartyInput(PartyKind.SUPPLIER, name, stateCode = state)).ok()

    suspend fun product(
        name: String,
        sell: Long = rs(1000),
        buy: Long = rs(800),
        gstBp: Int = 0,
        unit: String = "PCS",
        secondary: String? = null,
        conversion: Long? = null,
        hsn: String? = null,
        hsnConfirmed: Boolean = false,
        isService: Boolean = false,
        taxType: TaxType = TaxType.GST,
        batchTracked: Boolean = false,
        minSelling: Long? = null,
        barcode: String? = null,
        /** Opening stock to post, for tests that sell without buying first. */
        stock: Long = 0,
    ): ProductEntity = masters.createProduct(
        ProductInput(
            name = name, sellingPricePaise = sell, purchasePricePaise = buy, gstBp = gstBp, primaryUnit = unit,
            secondaryUnit = secondary, conversionMilli = conversion, hsnCode = hsn, hsnConfirmed = hsnConfirmed,
            isService = isService, taxType = taxType, batchTracked = batchTracked, minSellingPricePaise = minSelling, barcode = barcode,
        ),
    ).ok().also {
        if (stock > 0) engine.postOpeningStock(com.shopai.app.books.engine.OpeningStockInput(it.id, today, stock, meta = meta())).ok()
    }

    suspend fun sale(
        party: PartyEntity?,
        vararg items: ItemInput,
        received: List<PaymentPart> = emptyList(),
        number: String? = null,
        date: LocalDate = today,
        dueDate: LocalDate? = null,
    ): PostResult = engine.postSale(SaleInput(party?.id, date, items.toList(), received, dueDate = dueDate, number = number, meta = meta()))

    suspend fun purchase(party: PartyEntity, billNo: String, vararg items: ItemInput, paid: List<PaymentPart> = emptyList(), date: LocalDate = today): PostResult =
        engine.postPurchase(PurchaseInput(party.id, billNo, date, items.toList(), paid, meta = meta()))

    suspend fun payIn(party: PartyEntity, amount: Long, advance: Boolean = false, reference: String? = null, allocations: List<Pair<String, Long>>? = null): PostResult =
        engine.postPaymentIn(PaymentInput(party.id, amount, cash, PaymentMode.CASH, today, reference, allocations, keepExcessAsAdvance = advance, meta = meta()))

    suspend fun payOut(party: PartyEntity, amount: Long, advance: Boolean = false): PostResult =
        engine.postPaymentOut(PaymentInput(party.id, amount, cash, PaymentMode.CASH, today, keepExcessAsAdvance = advance, meta = meta()))

    fun item(product: ProductEntity, qty: Long, rate: Long? = null, unit: String? = null, batch: String? = null) =
        ItemInput(productId = product.id, qtyMilli = qty, ratePaise = rate, unit = unit, batchNo = batch)

    fun cashPart(amount: Long) = PaymentPart(cash, PaymentMode.CASH, amount)
}
