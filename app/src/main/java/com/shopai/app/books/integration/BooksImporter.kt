package com.shopai.app.books.integration

import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.engine.BusinessSetup
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.OpeningStockInput
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.PartyOpeningInput
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.ProductInput
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TxnSource
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.Business
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.repository.BusinessRepository
import com.shopai.app.util.parseIsoToLocalDate
import retrofit2.HttpException
import java.math.BigDecimal
import java.time.LocalDate

/** What the one-time import reads from the OwnerNote backend. */
interface LegacyLedgerSource {
    suspend fun business(): Business?
    suspend fun customers(): List<PartySummary>
    suspend fun suppliers(): List<PartySummary>
    suspend fun products(): List<InventoryProduct>
}

class ApiLegacyLedgerSource(private val api: ShopAiApi, private val businessRepository: BusinessRepository) : LegacyLedgerSource {
    override suspend fun business(): Business? = businessRepository.getMyBusiness()
    override suspend fun customers(): List<PartySummary> = api.getCustomers().data
    override suspend fun suppliers(): List<PartySummary> = api.getSuppliers().data

    /** A backend without the inventory module (404) simply has no products to bring. */
    override suspend fun products(): List<InventoryProduct> = try {
        api.listInventoryProducts().data
    } catch (e: HttpException) {
        if (e.code() == 404) emptyList() else throw e
    }
}

sealed class ImportOutcome {
    data object AlreadyImported : ImportOutcome()
    data object NoBusiness : ImportOutcome()
    data class Imported(val customers: Int, val suppliers: Int, val products: Int) : ImportOutcome()
    data class Failed(val error: Throwable) : ImportOutcome()
}

/**
 * Moves the backend ledger into the books once:
 *  - every customer / supplier becomes a party (linked by backendId);
 *  - their current pending Credit / Debit becomes the party's opening balance
 *    (due date = the earliest pending due date);
 *  - every inventory product becomes a product, its current stock the opening stock.
 *
 * Every step is idempotent (parties/products are matched by backendId, openings
 * use fixed client keys), so an import cut short by a lost connection simply
 * resumes next time. Only when everything is in is the business marked
 * imported — from then on the backend ledger is never written again.
 */
class BooksImporter(
    private val module: BooksModule,
    private val source: LegacyLedgerSource,
    private val today: () -> LocalDate = LocalDate::now,
) {
    suspend fun runIfNeeded(): ImportOutcome = try {
        val business = source.business()
        if (business == null) {
            ImportOutcome.NoBusiness
        } else {
            val session = module.openFor(business.id, business.ownerUserId)
            val local = session.dao.business(business.id)
            if (local?.importedAt != null) {
                ImportOutcome.AlreadyImported
            } else {
                import(session, business)
            }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ImportOutcome.Failed(e)
    }

    private suspend fun import(session: BooksSession, business: Business): ImportOutcome {
        val db: BooksDatabase = session.db
        val masters = session.masters
        if (session.dao.business(business.id) == null) {
            masters.setupBusiness(
                BusinessSetup(
                    name = business.businessName.ifBlank { "My business" },
                    type = typeFor(business.category),
                    ownerName = business.ownerName,
                    phone = business.phone,
                ),
            ).orThrow()
        }
        // Read everything first so a network failure leaves nothing half-written.
        val customers = source.customers()
        val suppliers = source.suppliers()
        val products = source.products()

        val partyIds = HashMap<String, String>()
        for ((kind, list) in listOf(PartyKind.CUSTOMER to customers, PartyKind.SUPPLIER to suppliers)) {
            for (s in list) {
                val party = session.dao.partyByBackendId(business.id, s.id) ?: masters.createParty(
                    PartyInput(
                        kind = kind,
                        name = s.name.trim().ifEmpty { if (kind == PartyKind.CUSTOMER) "Customer" else "Supplier" },
                        mobile = s.phone?.let(::validMobile),
                        notes = s.phone?.takeIf { validMobile(it) == null }?.let { "Phone: $it" },
                        backendId = s.id,
                        allowDuplicateName = true,
                    ),
                ).orThrow()
                partyIds[s.id] = party.id
                val pending = paise(s.pendingTotal)
                if (pending != 0L) {
                    val posted = session.engine.postPartyOpening(
                        PartyOpeningInput(
                            partyId = party.id,
                            amountPaise = kotlin.math.abs(pending),
                            inPartysFavour = pending < 0,
                            date = today(),
                            dueDate = parseIsoToLocalDate(s.nextDueDate),
                            meta = PostMeta(clientKey = "import-ob-${business.id}-${s.id}", source = TxnSource.IMPORT, notes = "Balance brought from the OwnerNote ledger"),
                        ),
                    )
                    if (posted is PostResult.Rejected && posted.errors.none { it.code == BooksErrorCode.OPENING_EXISTS }) throw BooksRejectedException(posted.errors)
                }
            }
        }

        for (p in products) {
            if (session.dao.productByBackendId(business.id, p.id) != null) continue
            importProduct(session, business, p, partyIds)
        }

        masters.markImported("${customers.size} customers, ${suppliers.size} suppliers, ${products.size} products")
        module.invalidate()
        return ImportOutcome.Imported(customers.size, suppliers.size, products.size)
    }

    private suspend fun importProduct(session: BooksSession, business: Business, p: InventoryProduct, partyIds: Map<String, String>) {
        val masters = session.masters
        val unit = masters.ensureUnit(p.unit) ?: masters.ensureUnit("PCS")!!
        val gstBp = p.gstRate?.let { BigDecimal.valueOf(it).movePointRight(2).toInt() } ?: 0
        val slabs = session.dao.business(business.id)!!.gstRatesBp.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (gstBp !in slabs && gstBp in 0..10_000) masters.updateSettings(gstRatesBp = slabs + gstBp).orThrow()
        val categoryId = masters.categoryId(p.category)
        val selling = p.sellingPrice?.let(::paise)?.takeIf { it >= 0 }
        val mrp = p.mrp?.let(::paise)?.takeIf { it >= 0 && (selling == null || selling <= it) }
        val barcode = p.barcode?.trim()?.takeIf { it.isNotEmpty() && session.dao.activeProductsByBarcode(business.id, it).isEmpty() }
        val sku = p.sku?.trim()?.takeIf { it.isNotEmpty() && session.dao.productBySku(business.id, it) == null }
        val product = masters.createProduct(
            ProductInput(
                name = p.name,
                primaryUnit = unit,
                categoryId = categoryId,
                subCategoryId = masters.categoryId(p.subCategory, parentId = categoryId),
                brandId = masters.brandId(p.brand),
                sku = sku,
                barcode = barcode,
                gstBp = gstBp,
                purchasePricePaise = p.purchasePrice?.let(::paise)?.takeIf { it >= 0 },
                sellingPricePaise = selling,
                mrpPaise = mrp,
                minStockMilli = Qty.of(BigDecimal.valueOf(p.minimumStock).toPlainString()).takeIf { it >= 0 },
                supplierId = p.supplierId?.let(partyIds::get),
                imagePath = p.imageUri,
                description = p.notes,
                allowSimilarName = true,
                backendId = p.id,
            ),
        ).orThrow()
        val qty = Qty.of(BigDecimal.valueOf(p.currentStock).toPlainString())
        if (qty > 0) {
            val posted = session.engine.postOpeningStock(
                OpeningStockInput(
                    productId = product.id,
                    date = today(),
                    qtyMilli = qty,
                    valuePaise = Money.times(p.purchasePrice?.let(::paise)?.coerceAtLeast(0) ?: 0, qty),
                    meta = PostMeta(clientKey = "import-os-${business.id}-${p.id}", source = TxnSource.IMPORT),
                ),
            )
            if (posted is PostResult.Rejected) throw BooksRejectedException(posted.errors)
        }
    }

    private fun typeFor(category: String): BusinessType = when (category) {
        "RESTAURANT_FOOD", "BEAUTY_SALON" -> BusinessType.PRODUCT_AND_SERVICE
        else -> BusinessType.RETAILER
    }

    private fun validMobile(raw: String): String? = raw.filter(Char::isDigit).takeLast(10).takeIf { it.length == 10 && it[0] in "6789" }

    private fun paise(rupees: Double): Long = BigDecimal.valueOf(rupees).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()

    private fun <T> MasterResult<T>.orThrow(): T = when (this) {
        is MasterResult.Ok -> value
        is MasterResult.Rejected -> throw BooksRejectedException(errors)
    }
}
