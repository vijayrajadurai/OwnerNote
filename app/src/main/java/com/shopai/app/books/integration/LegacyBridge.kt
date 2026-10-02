package com.shopai.app.books.integration

import androidx.room.withTransaction
import com.shopai.app.books.data.OpenDoc
import com.shopai.app.books.data.ProductEntity
import com.shopai.app.books.data.TxnEntity
import com.shopai.app.books.engine.AdjustmentReasonText
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.ItemInput
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.OpeningStockInput
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.PaymentInput
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.ProductInput
import com.shopai.app.books.engine.PurchaseInput
import com.shopai.app.books.engine.SaleInput
import com.shopai.app.books.engine.StockAdjustmentInput
import com.shopai.app.books.engine.nameKey
import com.shopai.app.books.model.AdjustmentReason
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.model.TxnSource
import com.shopai.app.books.model.TxnType
import com.shopai.app.data.inventory.IntelligenceMovement
import com.shopai.app.data.inventory.IntelligenceProduct
import com.shopai.app.data.inventory.ProductIntelligence
import com.shopai.app.data.inventory.analyzeInventoryProduct
import com.shopai.app.data.inventory.buildInventoryIntelligenceSummary
import com.shopai.app.data.inventory.computeStockStatus
import com.shopai.app.data.inventory.STOCK_STATUS_LOW_STOCK
import com.shopai.app.data.model.CashFlowSummary
import com.shopai.app.data.model.CashFlowWindow
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.CreateInventoryProductInput
import com.shopai.app.data.model.CreatePartyInput
import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.CustomerDetail
import com.shopai.app.data.model.DebitTransactionDetail
import com.shopai.app.data.model.InventoryIntelligenceSummaryDto
import com.shopai.app.data.model.InventoryMovement
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.model.PartyRecord
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.model.PaymentRecord
import com.shopai.app.data.model.ProductIntelligenceDto
import com.shopai.app.data.model.SupplierDetail
import com.shopai.app.data.model.UpdateInventoryProductInput
import com.shopai.app.data.repository.StockChangeResult
import com.shopai.app.util.localDateToIsoInstant
import com.shopai.app.util.parseIsoToLocalDate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.UUID

/**
 * The existing customer / supplier / credit / debit / inventory screens, Kai
 * and Home keep their models and calls; after the import this bridge answers
 * them from the books. Every figure comes from the engine's ledger — nothing
 * is computed here that the books do not already hold.
 *
 * Old "credit" (customer owes) = a sale; old "debit" (owed to a supplier) =
 * a purchase; an amount-only entry is one non-GST line with no stock and no
 * round-off, so the books hold exactly the amount that was entered.
 */
class LegacyBridge(private val s: BooksSession, private val today: () -> LocalDate = LocalDate::now) {
    private val dao = s.dao
    private val biz = s.ctx.businessId
    private val zone = ZoneId.systemDefault()

    // ------------------------------------------------------------------ parties

    suspend fun customers(): List<PartySummary> = summaries(PartyKind.CUSTOMER)

    suspend fun suppliers(): List<PartySummary> = summaries(PartyKind.SUPPLIER)

    private suspend fun summaries(kind: PartyKind): List<PartySummary> {
        val open = dao.openDocsAll(biz, docTypes(kind), kind.name).groupBy { it.partyId }
        return dao.parties(biz, kind.name).map { p ->
            val docs = open[p.id].orEmpty()
            PartySummary(
                id = p.id,
                name = p.name,
                phone = p.mobile?.let { "+91$it" },
                pendingTotal = rupees(docs.sumOf { it.outstandingPaise }),
                nextDueDate = docs.minOfOrNull { it.dueDate ?: it.date }?.let { localDateToIsoInstant(LocalDate.ofEpochDay(it.toLong())) },
            )
        }
    }

    suspend fun customer(id: String): CustomerDetail {
        val p = partyFor(id, PartyKind.CUSTOMER)
        return CustomerDetail(p.id, p.name, p.mobile?.let { "+91$it" }, docs(p.id, PartyKind.CUSTOMER).map { creditDetail(it) })
    }

    suspend fun supplier(id: String): SupplierDetail {
        val p = partyFor(id, PartyKind.SUPPLIER)
        return SupplierDetail(p.id, p.name, p.mobile?.let { "+91$it" }, docs(p.id, PartyKind.SUPPLIER).map { debitDetail(it) })
    }

    suspend fun createParty(kind: PartyKind, input: CreatePartyInput): PartyRecord {
        dao.partyByName(biz, kind.name, nameKey(input.name))?.let { return PartyRecord(it.id, it.name, it.mobile?.let { m -> "+91$m" }) }
        val mobile = input.phone?.filter(Char::isDigit)?.takeLast(10)?.takeIf { it.length == 10 && it[0] in "6789" }
        val p = s.masters.createParty(PartyInput(kind, input.name, mobile = mobile)).orThrow()
        return PartyRecord(p.id, p.name, p.mobile?.let { "+91$it" })
    }

    /** Ids from before the import (backend ids) still resolve to the same party. */
    private suspend fun partyFor(id: String, kind: PartyKind) =
        (dao.party(id) ?: dao.partyByBackendId(biz, id))?.takeIf { it.businessId == biz && it.kind == kind.name }
            ?: throw BooksRejectedException(listOf(com.shopai.app.books.engine.BooksError(BooksErrorCode.PARTY_NOT_FOUND, "Not found")))

    private suspend fun docs(partyId: String, kind: PartyKind): List<TxnEntity> =
        dao.partyTxns(biz, partyId).filter { it.type in docTypes(kind) && !it.isAdvance }

    private fun docTypes(kind: PartyKind) = if (kind == PartyKind.CUSTOMER) {
        listOf(TxnType.SALE.name, TxnType.OPENING_BALANCE.name)
    } else {
        listOf(TxnType.PURCHASE.name, TxnType.OPENING_BALANCE.name)
    }

    // ------------------------------------------------------------- credit / debit

    suspend fun createCredit(input: CreateCreditInput, source: TxnSource): CreditTransactionDetail {
        val party = resolveParty(PartyKind.CUSTOMER, input.customerId, input.customerName)
        val meta = meta(source, input)
        val txn = s.engine.postSale(
            SaleInput(
                partyId = party.id, date = today(), items = listOf(amountLine(input.amount, input.description, "Credit")),
                dueDate = parseIsoToLocalDate(input.dueDate), roundOff = false, meta = meta,
            ),
        ).orThrow()
        return creditDetail(txn)
    }

    suspend fun createDebit(input: CreateDebitInput, source: TxnSource): DebitTransactionDetail {
        val party = resolveParty(PartyKind.SUPPLIER, input.supplierId, input.supplierName)
        val meta = meta(source, input)
        val txn = s.engine.postPurchase(
            PurchaseInput(
                partyId = party.id, billNumber = null, date = today(), items = listOf(amountLine(input.amount, input.description, "Debit")),
                dueDate = parseIsoToLocalDate(input.dueDate), roundOff = false, meta = meta,
            ),
        ).orThrow()
        return debitDetail(txn)
    }

    suspend fun addCreditPayment(txnId: String, amount: Double, note: String?): CreditTransactionDetail =
        creditDetail(pay(txnId, TxnType.SALE, paise(amount), note))

    suspend fun markCreditPaid(txnId: String): CreditTransactionDetail = creditDetail(payRest(txnId, TxnType.SALE))

    suspend fun addDebitPayment(txnId: String, amount: Double, note: String?): DebitTransactionDetail =
        debitDetail(pay(txnId, TxnType.PURCHASE, paise(amount), note))

    suspend fun markDebitPaid(txnId: String): DebitTransactionDetail = debitDetail(payRest(txnId, TxnType.PURCHASE))

    private suspend fun payRest(txnId: String, docType: TxnType): TxnEntity {
        val due = s.ledger.outstanding(txnId)
        return if (due > 0) pay(txnId, docType, due, null) else doc(txnId, docType)
    }

    /** Payment against one document. The old screens do not ask how it was paid — recorded as cash. */
    private suspend fun pay(txnId: String, docType: TxnType, amountPaise: Long, note: String?): TxnEntity {
        val doc = doc(txnId, docType)
        val input = PaymentInput(
            partyId = doc.partyId!!, amountPaise = amountPaise, moneyAccountId = s.cashAccountId, mode = PaymentMode.CASH,
            date = today(), reference = note?.trim()?.takeIf { it.isNotEmpty() }, allocations = listOf(doc.id to amountPaise),
            meta = PostMeta(clientKey = UUID.randomUUID().toString()),
        )
        if (docType == TxnType.SALE) s.engine.postPaymentIn(input).orThrow() else s.engine.postPaymentOut(input).orThrow()
        return dao.txn(doc.id)!!
    }

    private suspend fun doc(txnId: String, docType: TxnType): TxnEntity =
        dao.txn(txnId)?.takeIf { it.businessId == biz && (it.type == docType.name || it.type == TxnType.OPENING_BALANCE.name) }
            ?: throw BooksRejectedException(listOf(com.shopai.app.books.engine.BooksError(BooksErrorCode.NOT_FOUND, "This entry is not in the books")))

    private suspend fun resolveParty(kind: PartyKind, id: String?, name: String?) =
        id?.takeIf { it.isNotBlank() }?.let { partyFor(it, kind) }
            ?: name?.trim()?.takeIf { it.isNotEmpty() }?.let { n ->
                dao.partyByName(biz, kind.name, nameKey(n)) ?: s.masters.createParty(PartyInput(kind, n)).orThrow()
            }
            ?: throw BooksRejectedException(listOf(com.shopai.app.books.engine.BooksError(BooksErrorCode.PARTY_REQUIRED, "Enter the name")))

    private fun amountLine(amount: Double, description: String?, fallback: String) = ItemInput(
        name = description?.trim()?.takeIf { it.isNotEmpty() } ?: fallback,
        qtyMilli = Qty.of(1), unit = "NOS", ratePaise = paise(amount), gstBp = 0, taxType = TaxType.NON_GST,
    )

    /** Voice / scanned entries were reviewed on their own screens: kept as a confirmed draft for the audit trail. */
    private suspend fun meta(source: TxnSource, input: Any): PostMeta {
        val key = UUID.randomUUID().toString()
        if (source != TxnSource.VOICE && source != TxnSource.OCR) return PostMeta(clientKey = key, source = source)
        val draftId = s.engine.saveDraft(input::class.simpleName.orEmpty(), source, input.toString(), rawInput = null)
        return PostMeta(clientKey = key, source = source, draftId = draftId)
    }

    private suspend fun creditDetail(t: TxnEntity): CreditTransactionDetail {
        val applied = dao.appliedTo(t.id)
        val paid = applied.sumOf { it.amountPaise }
        return CreditTransactionDetail(
            id = t.id, amount = amount(t.totalPaise), paidAmount = amount(paid), description = describe(t),
            dueDate = t.dueDate?.let { localDateToIsoInstant(LocalDate.ofEpochDay(it.toLong())) },
            status = status(t.totalPaise, paid), createdAt = instantOf(t.date, t.createdAt),
            payments = applied.map { PaymentRecord(it.txnId, amount(it.amountPaise), noteFor(it.type, it.number, it.reference), instantOf(it.date, it.createdAt)) },
        )
    }

    private suspend fun debitDetail(t: TxnEntity): DebitTransactionDetail {
        val c = creditDetail(t)
        return DebitTransactionDetail(c.id, c.amount, c.paidAmount, c.description, c.dueDate, c.status, c.createdAt, c.payments)
    }

    private suspend fun describe(t: TxnEntity): String? = when (t.type) {
        TxnType.OPENING_BALANCE.name -> t.notes ?: "Opening balance"
        else -> dao.items(t.id).let { items ->
            if (items.size == 1 && items[0].taxType == TaxType.NON_GST.name && items[0].productId == null) items[0].itemName
            else "${t.number} · ${items.joinToString { it.itemName }}"
        }
    }

    private fun noteFor(type: String, number: String, reference: String?) = when (type) {
        TxnType.SALE_RETURN.name, TxnType.PURCHASE_RETURN.name -> "Return $number"
        else -> reference
    }

    private fun status(total: Long, paid: Long) = when {
        paid >= total -> "PAID"
        paid > 0 -> "PARTIALLY_PAID"
        else -> "PENDING"
    }

    private fun instantOf(day: Int, createdAt: Long): String {
        val date = LocalDate.ofEpochDay(day.toLong())
        val created = Instant.ofEpochMilli(createdAt)
        return if (created.atZone(zone).toLocalDate() == date) created.toString() else localDateToIsoInstant(date)
    }

    // ------------------------------------------------------------- cash flow

    /** Home / Kai cash-flow card from the books: what is due in and out, and when. */
    suspend fun cashFlow(): CashFlowSummary {
        val inDocs = dao.openDocsAll(biz, docTypes(PartyKind.CUSTOMER), PartyKind.CUSTOMER.name)
        val outDocs = dao.openDocsAll(biz, docTypes(PartyKind.SUPPLIER), PartyKind.SUPPLIER.name)
        val receivable = inDocs.sumOf { it.outstandingPaise }
        val payable = outDocs.sumOf { it.outstandingPaise }
        fun window(days: Int): CashFlowWindow {
            val limit = today().plusDays(days.toLong()).toEpochDay()
            fun due(list: List<OpenDoc>) = list.filter { (it.dueDate ?: it.date) <= limit }.sumOf { it.outstandingPaise }
            val inSoon = due(inDocs)
            val outSoon = due(outDocs)
            return CashFlowWindow(days, rupees(inSoon), rupees(outSoon), rupees((outSoon - inSoon).coerceAtLeast(0)))
        }
        return CashFlowSummary(
            totalReceivables = rupees(receivable), totalPayables = rupees(payable),
            pendingReceivables = rupees(receivable), pendingPayables = rupees(payable),
            netPosition = rupees(receivable - payable), next7Days = window(7), next30Days = window(30),
            asOf = Instant.now().toString(),
        )
    }

    // ------------------------------------------------------------- inventory

    suspend fun products(): List<InventoryProduct> {
        val stock = dao.allStock(biz).associateBy { it.productId }
        return dao.products(biz, archived = false, limit = Int.MAX_VALUE, offset = 0).map { toInventory(it, stock[it.id]?.qtyMilli ?: 0) }
    }

    suspend fun lowStockProducts(): List<InventoryProduct> =
        products().filter { computeStockStatus(it.currentStock, it.minimumStock) == STOCK_STATUS_LOW_STOCK }

    suspend fun product(id: String): InventoryProduct = toInventory(productEntity(id), s.ledger.stock(id))

    suspend fun createProduct(input: CreateInventoryProductInput): InventoryProduct {
        var created: ProductEntity? = null
        s.db.withTransaction {
            val unit = s.masters.ensureUnit(input.unit) ?: throw invalid("Check the unit")
            val categoryId = s.masters.categoryId(input.category)
            created = s.masters.createProduct(
                ProductInput(
                    name = input.name, primaryUnit = unit, categoryId = categoryId,
                    subCategoryId = s.masters.categoryId(input.subCategory, parentId = categoryId),
                    brandId = s.masters.brandId(input.brand), sku = input.sku, barcode = input.barcode,
                    gstBp = bp(input.gstRate), purchasePricePaise = input.purchasePrice?.let(::paise),
                    sellingPricePaise = input.sellingPrice?.let(::paise), mrpPaise = input.mrp?.let(::paise),
                    minStockMilli = qty(input.minimumStock), supplierId = input.supplierId?.let { localSupplierId(it) },
                    imagePath = input.imageUri, description = input.notes,
                ),
            ).orThrow()
            val opening = qty(input.currentStock)
            if (opening > 0) {
                s.engine.postOpeningStock(OpeningStockInput(created!!.id, today(), opening, meta = PostMeta(UUID.randomUUID().toString()))).orThrow()
            }
        }
        return product(created!!.id)
    }

    suspend fun updateProduct(id: String, input: UpdateInventoryProductInput): InventoryProduct {
        val p = productEntity(id)
        val categoryId = input.category?.let { s.masters.categoryId(it) } ?: p.categoryId
        s.masters.updateProduct(
            id,
            p.toInput().copy(
                name = input.name ?: p.name,
                primaryUnit = input.unit?.let { s.masters.ensureUnit(it) } ?: p.primaryUnit,
                categoryId = categoryId,
                subCategoryId = input.subCategory?.let { s.masters.categoryId(it, parentId = categoryId) } ?: p.subCategoryId,
                brandId = input.brand?.let { s.masters.brandId(it) } ?: p.brandId,
                sku = input.sku ?: p.sku, barcode = input.barcode ?: p.barcode,
                minStockMilli = input.minimumStock?.let(::qty) ?: p.minStockMilli,
                purchasePricePaise = input.purchasePrice?.let(::paise) ?: p.purchasePricePaise,
                sellingPricePaise = input.sellingPrice?.let(::paise) ?: p.sellingPricePaise,
                mrpPaise = input.mrp?.let(::paise) ?: p.mrpPaise,
                gstBp = input.gstRate?.let(::bp) ?: p.gstBp,
                supplierId = input.supplierId?.let { localSupplierId(it) } ?: p.supplierId,
                imagePath = input.imageUri ?: p.imagePath, description = input.notes ?: p.description,
            ),
        ).orThrow()
        return product(id)
    }

    /** The Inventory screen's Stock in / Stock out: a stock adjustment with the owner's reason. */
    suspend fun stockChange(id: String, quantity: Double, reason: String, isIn: Boolean): StockChangeResult {
        val change = qty(quantity).let { if (isIn) it else -it }
        val result = s.engine.postStockAdjustment(
            StockAdjustmentInput(
                productId = id, date = today(), reason = AdjustmentReasonText.from(reason), changeQtyMilli = change,
                note = reason.trim(), meta = PostMeta(UUID.randomUUID().toString()),
            ),
        )
        if (result is PostResult.Rejected && result.errors.any { it.code == BooksErrorCode.INSUFFICIENT_STOCK }) {
            return StockChangeResult.InsufficientStock(Qty.toDecimal(s.ledger.stock(id).coerceAtLeast(0)).toDouble())
        }
        result.orThrow()
        return StockChangeResult.Ok(product(id))
    }

    suspend fun movements(id: String): List<InventoryMovement> {
        var balance = 0L
        return dao.productMovements(biz, id).map { m ->
            balance += m.qtyMilli
            InventoryMovement(
                id = "${m.txnId}:${m.lineNo}", productId = m.productId,
                type = if (m.qtyMilli >= 0) "IN" else "OUT",
                quantity = Qty.toDecimal(kotlin.math.abs(m.qtyMilli)).toDouble(),
                reason = listOfNotNull(movementLabel(m.txnType), m.number, m.reason?.takeIf { it.isNotBlank() }).joinToString(" · "),
                referenceType = m.txnType, referenceId = m.number,
                balanceAfter = Qty.toDecimal(balance).toDouble(),
                createdAt = instantOf(m.date, m.createdAt),
            )
        }.reversed()
    }

    suspend fun productIntelligence(id: String, now: Date): ProductIntelligenceDto = analyze(productEntity(id), now).toDto()

    suspend fun intelligenceSummary(now: Date): InventoryIntelligenceSummaryDto {
        val analyses = dao.products(biz, archived = false, limit = Int.MAX_VALUE, offset = 0).filter { !it.isService }.map { analyze(it, now) }
        val summary = buildInventoryIntelligenceSummary(analyses)
        return InventoryIntelligenceSummaryDto(
            summary.totalProducts, summary.lowStockCount, summary.outOfStockCount, summary.healthyCount,
            summary.attentionProducts.map { it.toDto() }, summary.topUsageProducts.map { it.toDto() }, summary.insights,
        )
    }

    private suspend fun analyze(p: ProductEntity, now: Date): ProductIntelligence {
        val moves = dao.productMovements(biz, p.id).map {
            IntelligenceMovement(if (it.qtyMilli >= 0) "IN" else "OUT", Qty.toDecimal(kotlin.math.abs(it.qtyMilli)).toDouble(), Date.from(Instant.ofEpochMilli(it.createdAt)))
        }
        return analyzeInventoryProduct(
            IntelligenceProduct(p.id, p.name, p.primaryUnit, Qty.toDecimal(s.ledger.stock(p.id)).toDouble(), Qty.toDecimal(p.minStockMilli ?: 0).toDouble()),
            moves, now,
        )
    }

    private fun ProductIntelligence.toDto() = ProductIntelligenceDto(
        productId, productName, currentStock, minimumStock, unit, status, usage7Days, usage30Days,
        averageDailyUsage, estimatedDaysRemaining, hasEnoughHistory, isHighUsage, insights,
    )

    private fun movementLabel(txnType: String) = when (txnType) {
        TxnType.SALE.name -> "Sale"
        TxnType.PURCHASE.name -> "Purchase"
        TxnType.SALE_RETURN.name -> "Sales return"
        TxnType.PURCHASE_RETURN.name -> "Purchase return"
        TxnType.OPENING_STOCK.name -> "Opening stock"
        TxnType.STOCK_ADJUSTMENT.name -> null
        else -> null
    }

    private suspend fun productEntity(id: String) =
        (dao.product(id) ?: dao.productByBackendId(biz, id))?.takeIf { it.businessId == biz } ?: throw invalid("Product not found")

    private suspend fun toInventory(p: ProductEntity, stockMilli: Long) = InventoryProduct(
        id = p.id, businessId = biz, name = p.name,
        category = p.categoryId?.let { dao.category(it)?.name }.orEmpty(),
        subCategory = p.subCategoryId?.let { dao.category(it)?.name },
        brand = p.brandId?.let { dao.brand(it)?.name },
        sku = p.sku, barcode = p.barcode, unit = p.primaryUnit,
        currentStock = Qty.toDecimal(stockMilli).toDouble(), minimumStock = Qty.toDecimal(p.minStockMilli ?: 0).toDouble(),
        purchasePrice = p.purchasePricePaise?.let(::rupees), sellingPrice = p.sellingPricePaise?.let(::rupees), mrp = p.mrpPaise?.let(::rupees),
        gstRate = BigDecimal.valueOf(p.gstBp.toLong(), 2).toDouble(),
        supplierId = p.supplierId, supplierName = p.supplierId?.let { dao.party(it)?.name },
        imageUri = p.imagePath, notes = p.description,
        createdAt = Instant.ofEpochMilli(p.createdAt).toString(), updatedAt = Instant.ofEpochMilli(p.updatedAt).toString(),
    )

    private suspend fun localSupplierId(id: String) = (dao.party(id) ?: dao.partyByBackendId(biz, id))?.id

    // ------------------------------------------------------------- helpers

    private fun paise(rupees: Double): Long {
        if (!rupees.isFinite()) throw invalid("Check the amount")
        return BigDecimal.valueOf(rupees).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    }

    private fun qty(value: Double): Long = Qty.of(BigDecimal.valueOf(value).toPlainString())

    private fun bp(rate: Double?): Int = rate?.let { BigDecimal.valueOf(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt() } ?: 0

    private fun rupees(paise: Long): Double = Money.toRupees(paise).toDouble()

    private fun amount(paise: Long): String = Money.toRupees(paise).toPlainString()

    private fun invalid(message: String) =
        BooksRejectedException(listOf(com.shopai.app.books.engine.BooksError(BooksErrorCode.INVALID_FIELD, message)))

    private fun PostResult.orThrow(): TxnEntity = when (this) {
        is PostResult.Posted -> txn
        is PostResult.Rejected -> throw BooksRejectedException(errors)
    }

    private fun <T> MasterResult<T>.orThrow(): T = when (this) {
        is MasterResult.Ok -> value
        is MasterResult.Rejected -> throw BooksRejectedException(errors)
    }
}

/** Builds a product master input from a stored product (for partial edits). */
fun ProductEntity.toInput() = ProductInput(
    name = name, isService = isService, primaryUnit = primaryUnit, secondaryUnit = secondaryUnit, conversionMilli = conversionMilli,
    categoryId = categoryId, subCategoryId = subCategoryId, brandId = brandId, sku = sku, barcode = barcode,
    hsnCode = hsnCode, hsnConfirmed = hsnConfirmedByUser || hsnVerified, taxType = TaxType.valueOf(taxType), gstBp = gstBp, cessBp = cessBp,
    purchasePricePaise = purchasePricePaise, sellingPricePaise = sellingPricePaise, mrpPaise = mrpPaise,
    wholesalePricePaise = wholesalePricePaise, retailPricePaise = retailPricePaise, minSellingPricePaise = minSellingPricePaise,
    priceIncludesTax = priceIncludesTax, minStockMilli = minStockMilli, reorderLevelMilli = reorderLevelMilli,
    supplierId = supplierId, batchTracked = batchTracked, imagePath = imagePath, description = description,
    allowSimilarName = true, backendId = backendId,
)
