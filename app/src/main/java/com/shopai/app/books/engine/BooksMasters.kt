package com.shopai.app.books.engine

import androidx.room.withTransaction
import com.shopai.app.books.data.AuditEntity
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.data.BranchEntity
import com.shopai.app.books.data.BusinessEntity
import com.shopai.app.books.data.HsnSacEntity
import com.shopai.app.books.data.MoneyAccountEntity
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.data.ProductEntity
import com.shopai.app.books.data.UnitEntity
import com.shopai.app.books.data.UserEntity
import com.shopai.app.books.data.WarehouseEntity
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.model.CodeKind
import com.shopai.app.books.model.CustomerType
import com.shopai.app.books.model.DEFAULT_GST_RATES_BP
import com.shopai.app.books.model.MoneyAccountKind
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.ProductUnits
import com.shopai.app.books.model.Role
import com.shopai.app.books.model.STANDARD_UNITS
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.tax.GstStates
import com.shopai.app.books.tax.Gstin
import com.shopai.app.books.tax.HsnRules
import java.util.Locale
import java.util.UUID

data class BusinessSetup(
    val name: String,
    val type: BusinessType,
    val ownerName: String? = null,
    val stateCode: String? = null,
    val gstin: String? = null,
    val address: String? = null,
    val phone: String? = null,
)

data class PartyInput(
    val kind: PartyKind,
    val name: String,
    val mobile: String? = null,
    val whatsapp: String? = null,
    val address: String? = null,
    val gstin: String? = null,
    val stateCode: String? = null,
    val city: String? = null,
    val pincode: String? = null,
    val customerType: CustomerType? = null,
    val creditLimitPaise: Long? = null,
    val creditDays: Int? = null,
    val notes: String? = null,
    val backendId: String? = null,
    /** Import only: the old ledger may hold two parties with the same name. */
    val allowDuplicateName: Boolean = false,
)

data class ProductInput(
    val name: String,
    val isService: Boolean = false,
    val primaryUnit: String = "PCS",
    val secondaryUnit: String? = null,
    val conversionMilli: Long? = null,
    val categoryId: String? = null,
    val subCategoryId: String? = null,
    val brandId: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val hsnCode: String? = null,
    /** Owner confirmed an HSN/SAC that is not in the master. */
    val hsnConfirmed: Boolean = false,
    val taxType: TaxType = TaxType.GST,
    val gstBp: Int = 0,
    val cessBp: Int = 0,
    val purchasePricePaise: Long? = null,
    val sellingPricePaise: Long? = null,
    val mrpPaise: Long? = null,
    val wholesalePricePaise: Long? = null,
    val retailPricePaise: Long? = null,
    val minSellingPricePaise: Long? = null,
    val priceIncludesTax: Boolean = false,
    val minStockMilli: Long? = null,
    val reorderLevelMilli: Long? = null,
    val supplierId: String? = null,
    val batchTracked: Boolean = false,
    val imagePath: String? = null,
    val description: String? = null,
    /** The owner confirmed this is a different product despite a similar name. */
    val allowSimilarName: Boolean = false,
    val backendId: String? = null,
)

sealed class MasterResult<out T> {
    data class Ok<T>(val value: T) : MasterResult<T>()
    data class Rejected(val errors: List<BooksError>) : MasterResult<Nothing>()
}

fun nameKey(name: String): String = name.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)

/** Masters: business, users, parties, products, units, money accounts, HSN/SAC. */
class BooksMasters(
    private val db: BooksDatabase,
    private val ctx: BooksContext,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.dao()
    private val biz get() = ctx.businessId

    /** Creates (or updates) the business with its main branch, warehouse, owner, units and a Cash account. */
    suspend fun setupBusiness(setup: BusinessSetup): MasterResult<BusinessEntity> = guard {
        if (setup.name.isBlank()) reject(BooksErrorCode.REQUIRED_FIELD, "Enter the business name", "name")
        val gstin = setup.gstin?.let(Gstin::normalize)?.takeIf { it.isNotEmpty() }
        if (gstin != null && !Gstin.isValid(gstin)) reject(BooksErrorCode.INVALID_GST, "GSTIN $gstin is not valid", "gstin")
        val state = setup.stateCode ?: gstin?.let(Gstin::stateCode)
        if (state != null && !GstStates.isValid(state)) reject(BooksErrorCode.INVALID_GST, "Unknown state code $state", "stateCode")
        if (gstin != null && state != null && gstin.substring(0, 2) != state) reject(BooksErrorCode.INVALID_GST, "GSTIN does not match the state", "gstin")
        val t = now()
        val existing = dao.business(biz)
        val business = (existing ?: BusinessEntity(
            id = biz, name = setup.name.trim(), businessType = setup.type.name,
            gstRatesBp = DEFAULT_GST_RATES_BP.joinToString(","), createdAt = t, updatedAt = t,
        )).copy(
            name = setup.name.trim(), ownerName = setup.ownerName?.trim(), businessType = setup.type.name,
            stateCode = state, gstin = gstin, address = setup.address?.trim(), phone = setup.phone?.trim(), updatedAt = t,
        )
        db.withTransaction {
            dao.upsertBusiness(business)
            if (existing == null) {
                dao.upsertBranch(BranchEntity(ctx.branchId, biz, "Main"))
                dao.upsertWarehouse(WarehouseEntity(ctx.warehouseId, biz, ctx.branchId, "Main"))
                dao.upsertUser(UserEntity(ctx.userId, biz, setup.ownerName?.trim() ?: "Owner", setup.phone, Role.OWNER.name))
                dao.insertUnits(STANDARD_UNITS.map { UnitEntity(biz, it, it, custom = false) })
                dao.upsertMoneyAccount(MoneyAccountEntity(cashAccountId(), biz, MoneyAccountKind.CASH.name, "Cash in hand"))
            }
            audit("BUSINESS", biz, if (existing == null) "CREATE" else "UPDATE")
        }
        business
    }

    fun cashAccountId(): String = "$biz-cash"

    /** What every invoice prints at the bottom: UPI id, bank details, terms. */
    suspend fun updateInvoiceDetails(upiId: String?, bankDetails: String?, terms: String?): MasterResult<BusinessEntity> = guard {
        val b = dao.business(biz) ?: reject(BooksErrorCode.BUSINESS_NOT_SET_UP, "Set up the business first")
        val upi = upiId.clean()
        if (upi != null && !Regex("^[A-Za-z0-9._-]{2,64}@[A-Za-z]{2,64}$").matches(upi)) reject(BooksErrorCode.INVALID_FIELD, "Check the UPI id (like name@bank)", "upiId")
        val updated = b.copy(upiId = upi, bankDetails = bankDetails.clean(), invoiceTerms = terms.clean(), updatedAt = now())
        dao.upsertBusiness(updated)
        audit("BUSINESS", biz, "INVOICE_DETAILS")
        updated
    }

    suspend fun updateSettings(
        allowNegativeStock: Boolean? = null,
        allowFutureDates: Boolean? = null,
        batchTracking: Boolean? = null,
        roundOff: Boolean? = null,
        gstRatesBp: List<Int>? = null,
    ): MasterResult<BusinessEntity> = guard {
        val b = dao.business(biz) ?: reject(BooksErrorCode.BUSINESS_NOT_SET_UP, "Set up the business first")
        gstRatesBp?.let { rates -> if (rates.isEmpty() || rates.any { it < 0 || it > 10_000 }) reject(BooksErrorCode.INVALID_GST, "Check the GST rates", "gstRatesBp") }
        val updated = b.copy(
            allowNegativeStock = allowNegativeStock ?: b.allowNegativeStock,
            allowFutureDates = allowFutureDates ?: b.allowFutureDates,
            batchTracking = batchTracking ?: b.batchTracking,
            roundOff = roundOff ?: b.roundOff,
            gstRatesBp = gstRatesBp?.distinct()?.sorted()?.joinToString(",") ?: b.gstRatesBp,
            updatedAt = now(),
        )
        dao.upsertBusiness(updated)
        audit("BUSINESS", biz, "SETTINGS")
        updated
    }

    suspend fun addMoneyAccount(kind: MoneyAccountKind, name: String, details: String? = null): MasterResult<MoneyAccountEntity> = guard {
        if (name.isBlank()) reject(BooksErrorCode.REQUIRED_FIELD, "Enter the account name", "name")
        val account = MoneyAccountEntity(newId(), biz, kind.name, name.trim(), details?.trim())
        dao.upsertMoneyAccount(account)
        audit("MONEY_ACCOUNT", account.id, "CREATE")
        account
    }

    suspend fun addCustomUnit(code: String, name: String): MasterResult<UnitEntity> = guard {
        val c = code.trim().uppercase(Locale.ROOT)
        if (!Regex("^[A-Z][A-Z0-9_]{0,11}$").matches(c)) reject(BooksErrorCode.INVALID_UNIT, "Use letters/numbers for the unit code", "code")
        val unit = UnitEntity(biz, c, name.trim().ifEmpty { c }, custom = true)
        dao.insertUnits(listOf(unit))
        unit
    }

    suspend fun createParty(input: PartyInput): MasterResult<PartyEntity> = guard {
        val party = validateParty(input, existing = null)
        dao.insertParty(party)
        audit("PARTY", party.id, "CREATE")
        party
    }

    suspend fun updateParty(partyId: String, input: PartyInput): MasterResult<PartyEntity> = guard {
        val old = dao.party(partyId)?.takeIf { it.businessId == biz } ?: reject(BooksErrorCode.PARTY_NOT_FOUND, "Party not found")
        if (old.kind != input.kind.name) reject(BooksErrorCode.WRONG_PARTY_KIND, "A customer cannot become a supplier", "kind")
        val party = validateParty(input, existing = old)
        dao.updateParty(party)
        audit("PARTY", party.id, "UPDATE")
        party
    }

    private suspend fun validateParty(input: PartyInput, existing: PartyEntity?): PartyEntity {
        val name = input.name.trim().ifEmpty { reject(BooksErrorCode.REQUIRED_FIELD, "Enter the name", "name") }
        val key = nameKey(name)
        if (!input.allowDuplicateName) {
            dao.partyByName(biz, input.kind.name, key)?.takeIf { it.id != existing?.id }?.let { reject(BooksErrorCode.DUPLICATE_NAME, "${it.name} already exists", "name") }
        }
        val gstin = input.gstin.clean()?.let(Gstin::normalize)
        if (gstin != null && !Gstin.isValid(gstin)) reject(BooksErrorCode.INVALID_GST, "GSTIN $gstin is not valid", "gstin")
        val state = input.stateCode.clean() ?: gstin?.let(Gstin::stateCode)
        if (state != null && !GstStates.isValid(state)) reject(BooksErrorCode.INVALID_GST, "Unknown state", "stateCode")
        if (gstin != null && state != null && gstin.substring(0, 2) != state) reject(BooksErrorCode.INVALID_GST, "GSTIN does not match the state", "gstin")
        input.mobile.clean()?.let { if (!isPhone(it)) reject(BooksErrorCode.INVALID_FIELD, "Check the mobile number", "mobile") }
        input.whatsapp.clean()?.let { if (!isPhone(it)) reject(BooksErrorCode.INVALID_FIELD, "Check the WhatsApp number", "whatsapp") }
        input.pincode.clean()?.let { if (!Regex("^[1-9][0-9]{5}$").matches(it)) reject(BooksErrorCode.INVALID_FIELD, "Check the pincode", "pincode") }
        if ((input.creditLimitPaise ?: 0) < 0 || (input.creditDays ?: 0) < 0) reject(BooksErrorCode.INVALID_AMOUNT, "Credit limit / days cannot be negative", "creditLimitPaise")
        val t = now()
        return PartyEntity(
            id = existing?.id ?: newId(), businessId = biz, kind = input.kind.name, name = name, nameKey = key,
            mobile = input.mobile.clean()?.let(::digits), whatsapp = input.whatsapp.clean()?.let(::digits), address = input.address.clean(),
            gstin = gstin, stateCode = state, city = input.city.clean(), pincode = input.pincode.clean(),
            customerType = (input.customerType ?: if (gstin != null) CustomerType.B2B else CustomerType.B2C).name.takeIf { input.kind == PartyKind.CUSTOMER },
            creditLimitPaise = input.creditLimitPaise, creditDays = input.creditDays, notes = input.notes.clean(),
            backendId = input.backendId ?: existing?.backendId, active = existing?.active ?: true,
            createdBy = existing?.createdBy ?: ctx.userId, createdAt = existing?.createdAt ?: t, updatedBy = ctx.userId, updatedAt = t,
        )
    }

    /** Finds or creates a category (or sub-category under [parentId]) by name. */
    suspend fun categoryId(name: String?, parentId: String? = null): String? {
        val clean = name.clean() ?: return null
        val key = nameKey(clean)
        dao.categoryByName(biz, key, parentId)?.let { return it.id }
        val row = com.shopai.app.books.data.CategoryEntity(newId(), biz, clean, key, parentId)
        dao.insertCategory(row)
        return row.id
    }

    suspend fun brandId(name: String?): String? {
        val clean = name.clean() ?: return null
        val key = nameKey(clean)
        dao.brandByName(biz, key)?.let { return it.id }
        val row = com.shopai.app.books.data.BrandEntity(newId(), biz, clean, key)
        dao.insertBrand(row)
        return row.id
    }

    /** Ensures a unit exists (custom units are added on first use). */
    suspend fun ensureUnit(code: String): String? {
        val c = code.trim().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "").take(12)
        if (c.isEmpty() || !c[0].isLetter()) return null
        if (dao.units(biz).none { it.code == c }) dao.insertUnits(listOf(UnitEntity(biz, c, c, custom = true)))
        return c
    }

    suspend fun markImported(summary: String) {
        val b = dao.business(biz) ?: return
        dao.upsertBusiness(b.copy(importedAt = now(), importSummary = summary, updatedAt = now()))
        audit("BUSINESS", biz, "IMPORTED")
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun createProduct(input: ProductInput): MasterResult<ProductEntity> = guard {
        val business = dao.business(biz) ?: reject(BooksErrorCode.BUSINESS_NOT_SET_UP, "Set up the business first")
        val row = validateProduct(input, existingId = null, business = business)
        dao.insertProduct(row)
        audit("PRODUCT", row.id, "CREATE")
        row
    }

    suspend fun updateProduct(productId: String, input: ProductInput): MasterResult<ProductEntity> = guard {
        val business = dao.business(biz) ?: reject(BooksErrorCode.BUSINESS_NOT_SET_UP, "Set up the business first")
        val old = dao.product(productId)?.takeIf { it.businessId == biz } ?: reject(BooksErrorCode.PRODUCT_NOT_FOUND, "Product not found")
        // Stock lives in the primary unit — changing it would silently rescale every movement.
        if (!old.primaryUnit.equals(input.primaryUnit, true) && dao.stock(biz, old.id, null).let { it.qtyMilli != 0L || it.valuePaise != 0L }) {
            reject(BooksErrorCode.INVALID_UNIT, "The primary unit cannot change once stock is recorded", "primaryUnit")
        }
        val row = validateProduct(input, existingId = old.id, business = business).copy(
            id = old.id, createdBy = old.createdBy, createdAt = old.createdAt, active = old.active, archived = old.archived,
        )
        dao.updateProduct(row)
        audit("PRODUCT", row.id, "UPDATE")
        row
    }

    /** Archive / restore — products are never deleted (their history stays). */
    suspend fun setProductArchived(productId: String, archived: Boolean): MasterResult<ProductEntity> = guard {
        val p = dao.product(productId)?.takeIf { it.businessId == biz } ?: reject(BooksErrorCode.PRODUCT_NOT_FOUND, "Product not found")
        if (!archived && p.barcode != null && dao.activeProductsByBarcode(biz, p.barcode).any { it.id != p.id }) {
            reject(BooksErrorCode.DUPLICATE_BARCODE, "Barcode ${p.barcode} now belongs to another product", "barcode")
        }
        val row = p.copy(archived = archived, active = !archived, updatedBy = ctx.userId, updatedAt = now())
        dao.updateProduct(row)
        audit("PRODUCT", row.id, if (archived) "ARCHIVE" else "RESTORE")
        row
    }

    /**
     * Loads / refreshes the HSN-SAC master from the official GST classification
     * (admin import). Rows that fail the format check are skipped and returned.
     */
    suspend fun importHsnMaster(rows: List<HsnSacEntity>): List<HsnSacEntity> {
        val (good, bad) = rows.partition { HsnRules.isWellFormed(it.code, CodeKind.valueOf(it.kind)) && it.description.isNotBlank() }
        if (good.isNotEmpty()) dao.upsertHsn(good.map { it.copy(code = HsnRules.normalize(it.code), updatedAt = now()) })
        audit("HSN_MASTER", "import", "IMPORT")
        return bad
    }

    private suspend fun validateProduct(input: ProductInput, existingId: String?, business: BusinessEntity): ProductEntity {
        val name = input.name.trim().ifEmpty { reject(BooksErrorCode.REQUIRED_FIELD, "Enter the product name", "name") }
        val key = nameKey(name)
        dao.productByName(biz, key)?.takeIf { it.id != existingId }?.let {
            if (!input.allowSimilarName) reject(BooksErrorCode.DUPLICATE_NAME, "${it.name} already exists", "name")
        }
        val units = runCatching { ProductUnits(input.primaryUnit.trim().uppercase(), input.secondaryUnit?.trim()?.uppercase(), input.conversionMilli) }
            .getOrElse { reject(BooksErrorCode.INVALID_UNIT, it.message.orEmpty(), "primaryUnit") }
        val knownUnits = dao.units(biz).map { it.code }.toSet()
        listOfNotNull(units.primary, units.secondary).forEach { if (it !in knownUnits) reject(BooksErrorCode.INVALID_UNIT, "Add the unit $it first", "primaryUnit") }

        val barcode = input.barcode?.trim()?.takeIf { it.isNotEmpty() }
        if (barcode != null) {
            if (!Regex("^[0-9A-Za-z-]{4,48}$").matches(barcode)) reject(BooksErrorCode.INVALID_FIELD, "Check the barcode", "barcode")
            dao.activeProductsByBarcode(biz, barcode).firstOrNull { it.id != existingId }?.let {
                reject(BooksErrorCode.DUPLICATE_BARCODE, "Barcode $barcode already belongs to ${it.name}", "barcode")
            }
        }
        val sku = input.sku?.trim()?.takeIf { it.isNotEmpty() }
        if (sku != null) dao.productBySku(biz, sku)?.takeIf { it.id != existingId }?.let { reject(BooksErrorCode.DUPLICATE_SKU, "SKU $sku already belongs to ${it.name}", "sku") }

        val slabs = business.gstRatesBp.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        if (input.taxType == TaxType.GST && input.gstBp !in slabs) reject(BooksErrorCode.INVALID_GST, "Choose a GST rate set up for this business", "gstBp")
        if (input.taxType != TaxType.GST && (input.gstBp != 0 || input.cessBp != 0)) reject(BooksErrorCode.INVALID_GST, "${input.taxType.name.lowercase()} items carry no GST", "gstBp")
        if (input.cessBp < 0 || input.cessBp > 10_000) reject(BooksErrorCode.INVALID_GST, "Check the cess rate", "cessBp")

        val hsn = input.hsnCode?.let(HsnRules::normalize)?.takeIf { it.isNotEmpty() }
        var verified = false
        if (hsn != null) {
            val kind = if (input.isService) CodeKind.SAC else CodeKind.HSN
            when (HsnRules.verdict(hsn, kind, dao.hsn(hsn, kind.name) != null)) {
                HsnRules.Verdict.INVALID_FORMAT -> reject(BooksErrorCode.INVALID_HSN, "${kind.name} $hsn is not in the right format", "hsnCode")
                HsnRules.Verdict.VERIFICATION_REQUIRED ->
                    if (!input.hsnConfirmed) reject(BooksErrorCode.INVALID_HSN, HsnRules.VERIFICATION_REQUIRED_MESSAGE, "hsnCode")
                HsnRules.Verdict.VERIFIED -> verified = true
            }
        }

        val prices = listOf(
            input.purchasePricePaise, input.sellingPricePaise, input.mrpPaise, input.wholesalePricePaise,
            input.retailPricePaise, input.minSellingPricePaise,
        )
        if (prices.any { it != null && it < 0 }) reject(BooksErrorCode.INVALID_PRICE, "Prices cannot be negative", "sellingPricePaise")
        if (input.mrpPaise != null && input.sellingPricePaise != null && input.sellingPricePaise > input.mrpPaise) {
            reject(BooksErrorCode.INVALID_PRICE, "Selling price is above MRP", "sellingPricePaise")
        }
        if (input.minSellingPricePaise != null && input.sellingPricePaise != null && input.minSellingPricePaise > input.sellingPricePaise) {
            reject(BooksErrorCode.INVALID_PRICE, "Minimum selling price is above the selling price", "minSellingPricePaise")
        }
        if ((input.minStockMilli ?: 0) < 0 || (input.reorderLevelMilli ?: 0) < 0) reject(BooksErrorCode.INVALID_QUANTITY, "Stock levels cannot be negative", "minStockMilli")
        input.supplierId?.let { id ->
            val s = dao.party(id)
            if (s == null || s.businessId != biz || s.kind != PartyKind.SUPPLIER.name) reject(BooksErrorCode.PARTY_NOT_FOUND, "Supplier not found", "supplierId")
        }

        val t = now()
        return ProductEntity(
            id = existingId ?: newId(), businessId = biz, name = name, nameKey = key, isService = input.isService,
            imagePath = input.imagePath, categoryId = input.categoryId, subCategoryId = input.subCategoryId, brandId = input.brandId,
            sku = sku, barcode = barcode, hsnCode = hsn, hsnVerified = verified, hsnConfirmedByUser = hsn != null && !verified && input.hsnConfirmed,
            taxType = input.taxType.name, gstBp = input.gstBp, cessBp = input.cessBp,
            primaryUnit = units.primary, secondaryUnit = units.secondary, conversionMilli = units.factorMilli,
            purchasePricePaise = input.purchasePricePaise, sellingPricePaise = input.sellingPricePaise, mrpPaise = input.mrpPaise,
            wholesalePricePaise = input.wholesalePricePaise, retailPricePaise = input.retailPricePaise,
            minSellingPricePaise = input.minSellingPricePaise, priceIncludesTax = input.priceIncludesTax,
            minStockMilli = input.minStockMilli, reorderLevelMilli = input.reorderLevelMilli, supplierId = input.supplierId,
            batchTracked = input.batchTracked && !input.isService, description = input.description?.trim(), backendId = input.backendId,
            createdBy = ctx.userId, createdAt = t, updatedBy = ctx.userId, updatedAt = t,
        )
    }

    private fun digits(s: String) = s.filter(Char::isDigit).takeLast(10)

    private fun isPhone(s: String) = digits(s).let { it.length == 10 && it[0] in "6789" }

    private suspend fun audit(entity: String, id: String, action: String) {
        dao.insertAudit(AuditEntity(businessId = biz, entity = entity, entityId = id, action = action, userId = ctx.userId, at = now(), source = "MANUAL"))
    }

    private suspend fun <T> guard(block: suspend () -> T): MasterResult<T> = try {
        MasterResult.Ok(block())
    } catch (e: RejectException) {
        MasterResult.Rejected(e.errors)
    }
}
