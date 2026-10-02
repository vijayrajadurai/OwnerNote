package com.shopai.app.books.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * OwnerNote Books schema. Money columns are paise (Long), quantities are
 * thousandths (Long), rates are basis points (Int), dates are epoch days (Int),
 * timestamps are epoch millis (Long). Every row carries businessId (and
 * branch/warehouse where it applies) for multi-business / multi-store.
 *
 * Balances are never stored: receivable, payable, cash/bank and stock are
 * SUMs over `postings` and `stock_movements` of confirmed documents.
 */

@Entity(tableName = "businesses")
data class BusinessEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerName: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val gstin: String? = null,
    val stateCode: String? = null,
    val businessType: String,
    val batchTracking: Boolean = false,
    val allowNegativeStock: Boolean = false,
    val allowFutureDates: Boolean = false,
    val roundOff: Boolean = true,
    /** Comma-separated GST slabs in basis points, editable in settings. */
    val gstRatesBp: String,
    val logoPath: String? = null,
    val upiId: String? = null,
    val bankDetails: String? = null,
    val invoiceTerms: String? = null,
    /** When the one-time import of the backend customers / suppliers / products finished. */
    val importedAt: Long? = null,
    val importSummary: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "branches", indices = [Index("businessId")])
data class BranchEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
)

@Entity(tableName = "warehouses", indices = [Index("businessId"), Index("branchId")])
data class WarehouseEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val branchId: String,
    val name: String,
)

@Entity(tableName = "users", indices = [Index("businessId")])
data class UserEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
    val phone: String? = null,
    val role: String,
    /** Comma-separated permission keys for STAFF; empty = role defaults. */
    val permissions: String = "",
    val active: Boolean = true,
)

@Entity(
    tableName = "parties",
    indices = [Index("businessId", "kind", "nameKey"), Index("businessId", "mobile"), Index("backendId")],
)
data class PartyEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val kind: String,
    val name: String,
    /** Lower-case, single-spaced name for search and duplicate checks. */
    val nameKey: String,
    val mobile: String? = null,
    val whatsapp: String? = null,
    val address: String? = null,
    val gstin: String? = null,
    val stateCode: String? = null,
    val city: String? = null,
    val pincode: String? = null,
    val customerType: String? = null,
    val creditLimitPaise: Long? = null,
    val creditDays: Int? = null,
    val notes: String? = null,
    /** Id of the same party on the OwnerNote backend (import / sync). */
    val backendId: String? = null,
    val active: Boolean = true,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
)

@Entity(tableName = "categories", indices = [Index("businessId", "nameKey"), Index("parentId")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
    val nameKey: String,
    /** Set for a sub-category. */
    val parentId: String? = null,
)

@Entity(tableName = "brands", indices = [Index("businessId", "nameKey")])
data class BrandEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
    val nameKey: String,
)

@Entity(tableName = "units", primaryKeys = ["businessId", "code"])
data class UnitEntity(
    val businessId: String,
    val code: String,
    val name: String,
    val custom: Boolean,
)

/** Admin-managed HSN/SAC master. Ships empty; filled from the official GST list. */
@Entity(tableName = "hsn_sac", primaryKeys = ["code", "kind"], indices = [Index("description")])
data class HsnSacEntity(
    val code: String,
    val kind: String,
    val description: String,
    val gstBp: Int? = null,
    val cessBp: Int? = null,
    val effectiveFrom: Int? = null,
    val source: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "products",
    indices = [
        Index("businessId", "nameKey"),
        Index("businessId", "barcode"),
        Index("businessId", "sku"),
        Index("businessId", "hsnCode"),
        Index("businessId", "categoryId"),
        Index("supplierId"),
    ],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
    val nameKey: String,
    val isService: Boolean = false,
    val imagePath: String? = null,
    val categoryId: String? = null,
    val subCategoryId: String? = null,
    val brandId: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val hsnCode: String? = null,
    /** HSN/SAC is in the master, or the owner confirmed an unlisted code. */
    val hsnVerified: Boolean = false,
    val hsnConfirmedByUser: Boolean = false,
    val taxType: String,
    val gstBp: Int = 0,
    val cessBp: Int = 0,
    val primaryUnit: String,
    val secondaryUnit: String? = null,
    val conversionMilli: Long? = null,
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
    val description: String? = null,
    val active: Boolean = true,
    val archived: Boolean = false,
    val backendId: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
)

@Entity(tableName = "batches", indices = [Index(value = ["productId", "batchNo"], unique = true), Index("businessId", "expiryDay")])
data class BatchEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val productId: String,
    val batchNo: String,
    val mfgDay: Int? = null,
    val expiryDay: Int? = null,
    val createdAt: Long,
)

@Entity(tableName = "money_accounts", indices = [Index("businessId")])
data class MoneyAccountEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val kind: String,
    val name: String,
    val details: String? = null,
    val active: Boolean = true,
)

/** Every confirmed document: invoice, bill, return, payment, cash in/out, adjustment, opening. */
@Entity(
    tableName = "txns",
    indices = [
        Index(value = ["clientKey"], unique = true),
        Index(value = ["businessId", "type", "numberScope", "number"], unique = true),
        Index("businessId", "date"),
        Index("businessId", "type", "date"),
        Index("partyId"),
        Index("linkedTxnId"),
    ],
)
data class TxnEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val branchId: String,
    val warehouseId: String? = null,
    val type: String,
    val number: String,
    /** Purchase bill numbers are unique per supplier; everything else per business. */
    val numberScope: String,
    val date: Int,
    val dueDate: Int? = null,
    val partyId: String? = null,
    val partyName: String? = null,
    val partyGstin: String? = null,
    val placeOfSupply: String? = null,
    val interState: Boolean = false,
    val status: String,
    val grossPaise: Long = 0,
    val discountPaise: Long = 0,
    val taxablePaise: Long = 0,
    val cgstPaise: Long = 0,
    val sgstPaise: Long = 0,
    val igstPaise: Long = 0,
    val cessPaise: Long = 0,
    val roundOffPaise: Long = 0,
    /** Invoice/bill grand total, or the amount of a payment / cash entry. */
    val totalPaise: Long,
    val moneyAccountId: String? = null,
    val paymentMode: String? = null,
    /** Expense / income category or cash-in source. */
    val category: String? = null,
    val reference: String? = null,
    val notes: String? = null,
    /** Original invoice for a return; parent invoice for a payment taken with it. */
    val linkedTxnId: String? = null,
    /** Payment kept as advance, or an opening balance in the party's favour. */
    val isAdvance: Boolean = false,
    /** Idempotency key: the same key never posts twice (retries, sync, double taps). */
    val clientKey: String,
    val source: String,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
    val voidReason: String? = null,
    val voidedBy: String? = null,
    val voidedAt: Long? = null,
)

@Entity(
    tableName = "txn_items",
    primaryKeys = ["txnId", "lineNo"],
    indices = [Index("businessId", "txnType", "date"), Index("productId"), Index("businessId", "hsnCode")],
)
data class TxnItemEntity(
    val txnId: String,
    val lineNo: Int,
    val businessId: String,
    val txnType: String,
    val date: Int,
    val active: Boolean,
    val productId: String? = null,
    val itemName: String,
    val isService: Boolean,
    val hsnCode: String? = null,
    val hsnVerified: Boolean,
    val taxType: String,
    val qtyMilli: Long,
    val unit: String,
    val baseQtyMilli: Long,
    val ratePaise: Long,
    val grossPaise: Long,
    val discountPaise: Long,
    val taxablePaise: Long,
    val gstBp: Int,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessBp: Int,
    val cessPaise: Long,
    val totalPaise: Long,
    val batchId: String? = null,
    /** On a return: the line of the original invoice / bill being returned. */
    val sourceLineNo: Int? = null,
)

/** Double-entry lines; every document's debits equal its credits. */
@Entity(
    tableName = "postings",
    indices = [
        Index("txnId"),
        Index("businessId", "account", "partyId"),
        Index("businessId", "account", "moneyAccountId"),
        Index("businessId", "account", "date"),
    ],
)
data class PostingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnId: String,
    val businessId: String,
    val branchId: String,
    val date: Int,
    val account: String,
    val partyId: String? = null,
    val moneyAccountId: String? = null,
    val category: String? = null,
    val debitPaise: Long,
    val creditPaise: Long,
    val active: Boolean,
)

@Entity(
    tableName = "stock_movements",
    indices = [
        Index(value = ["txnId", "lineNo"], unique = true),
        Index("businessId", "productId", "warehouseId"),
        Index("businessId", "productId", "batchId"),
        Index("businessId", "date"),
    ],
)
data class StockMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnId: String,
    val lineNo: Int,
    val businessId: String,
    val warehouseId: String,
    val productId: String,
    val batchId: String? = null,
    val date: Int,
    val type: String,
    /** Signed, in the product's primary unit. */
    val qtyMilli: Long,
    /** Signed stock value at cost (moving average). */
    val valuePaise: Long,
    val unit: String,
    val reason: String? = null,
    val userId: String,
    val active: Boolean,
)

/** Money (payment / credit note / advance) applied to a document (invoice / bill / opening). */
@Entity(tableName = "allocations", indices = [Index("fromTxnId"), Index("toTxnId")])
data class AllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val businessId: String,
    val fromTxnId: String,
    val toTxnId: String,
    val amountPaise: Long,
    val active: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "number_series", primaryKeys = ["businessId", "type"])
data class NumberSeriesEntity(
    val businessId: String,
    val type: String,
    val nextSeq: Long,
)

@Entity(tableName = "audit_log", indices = [Index("businessId", "at"), Index("entityId")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val businessId: String,
    val entity: String,
    val entityId: String,
    val action: String,
    val userId: String,
    val at: Long,
    val source: String,
    val reason: String? = null,
    /** Short, non-sensitive summary (type, number, amount) — no GSTIN/phone. */
    val summary: String? = null,
)

@Entity(tableName = "sync_outbox", indices = [Index("state")])
data class OutboxEntity(
    @PrimaryKey val txnId: String,
    val businessId: String,
    val state: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long,
)

/** Voice / OCR / typed input waiting for the owner's review. Never touches the books. */
@Entity(tableName = "drafts", indices = [Index("businessId", "status")])
data class DraftEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val kind: String,
    val source: String,
    val payloadJson: String,
    val rawInput: String? = null,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)
