package com.shopai.app.books.engine

import com.shopai.app.books.data.TxnEntity
import com.shopai.app.books.model.AdjustmentReason
import com.shopai.app.books.model.CashInSource
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.model.TxnSource
import java.time.LocalDate

/** Who is posting, and where. */
data class BooksContext(
    val businessId: String,
    val branchId: String,
    val warehouseId: String,
    val userId: String,
)

/**
 * Common to every post. [clientKey] is the idempotency key — retrying with
 * the same key returns the first result instead of posting again. Voice and
 * OCR posts must come from a reviewed [draftId].
 */
data class PostMeta(
    val clientKey: String,
    val source: TxnSource = TxnSource.MANUAL,
    val draftId: String? = null,
    val notes: String? = null,
)

/** One item line. Anything left null comes from the product master. */
data class ItemInput(
    val productId: String? = null,
    /** Free-text line (a service or a one-off item) when there is no product. */
    val name: String? = null,
    val qtyMilli: Long,
    val unit: String? = null,
    val ratePaise: Long? = null,
    val discountPaise: Long = 0,
    val discountBp: Int = 0,
    val gstBp: Int? = null,
    val taxType: TaxType? = null,
    val cessBp: Int? = null,
    val hsnCode: String? = null,
    /** The owner confirmed an HSN/SAC that is not in the master ("HSN verification required"). */
    val hsnConfirmed: Boolean = false,
    val priceIncludesTax: Boolean? = null,
    /** Sales: the batch to sell from. Purchases: the batch received (created if new). */
    val batchNo: String? = null,
    val mfgDate: LocalDate? = null,
    val expiryDate: LocalDate? = null,
)

/** Money taken or paid along with an invoice / bill. */
data class PaymentPart(
    val moneyAccountId: String,
    val mode: PaymentMode,
    val amountPaise: Long,
    val reference: String? = null,
)

data class SaleInput(
    val partyId: String?,
    val date: LocalDate,
    val items: List<ItemInput>,
    val received: List<PaymentPart> = emptyList(),
    val dueDate: LocalDate? = null,
    /** Leave null for the next number in the series. */
    val number: String? = null,
    /** State code; defaults to the customer's state. */
    val placeOfSupply: String? = null,
    /** Null = the business setting. Amount-only ledger entries keep the exact amount. */
    val roundOff: Boolean? = null,
    val meta: PostMeta,
)

data class PurchaseInput(
    val partyId: String,
    /** The supplier's bill number; blank only for amount-only entries (an internal number is given). */
    val billNumber: String?,
    val date: LocalDate,
    val items: List<ItemInput>,
    val paid: List<PaymentPart> = emptyList(),
    val dueDate: LocalDate? = null,
    val placeOfSupply: String? = null,
    val roundOff: Boolean? = null,
    val meta: PostMeta,
)

data class ReturnLine(val lineNo: Int, val qtyMilli: Long)

/** Sale return (credit note) or purchase return (debit note) against an original document. */
data class ReturnInput(
    val originalTxnId: String,
    val date: LocalDate,
    val lines: List<ReturnLine>,
    val reason: String,
    val meta: PostMeta,
)

/** Customer payment in (or supplier payment out). [allocations] null = oldest due first. */
data class PaymentInput(
    val partyId: String,
    val amountPaise: Long,
    val moneyAccountId: String,
    val mode: PaymentMode,
    val date: LocalDate,
    val reference: String? = null,
    val allocations: List<Pair<String, Long>>? = null,
    /** Keep any amount beyond what is due as an advance. */
    val keepExcessAsAdvance: Boolean = false,
    /** The owner confirmed an identical payment on the same day is not a duplicate. */
    val allowSameDayDuplicate: Boolean = false,
    val meta: PostMeta,
)

data class CashInInput(
    val amountPaise: Long,
    val source: CashInSource,
    val moneyAccountId: String,
    val mode: PaymentMode,
    val date: LocalDate,
    val partyId: String? = null,
    val reference: String? = null,
    val meta: PostMeta,
)

data class CashOutInput(
    val amountPaise: Long,
    /** Rent, Salary, Electricity, Transport, Office, Purchase expense, … */
    val category: String,
    val moneyAccountId: String,
    val mode: PaymentMode,
    val date: LocalDate,
    val partyId: String? = null,
    val reference: String? = null,
    val meta: PostMeta,
)

data class StockAdjustmentInput(
    val productId: String,
    val date: LocalDate,
    val reason: AdjustmentReason,
    /** Positive adds, negative removes. Ignored when [countedQtyMilli] is set. */
    val changeQtyMilli: Long = 0,
    /** Physical count: the engine posts the difference to what the books say. */
    val countedQtyMilli: Long? = null,
    val batchNo: String? = null,
    val note: String? = null,
    val meta: PostMeta,
)

data class OpeningStockInput(
    val productId: String,
    val date: LocalDate,
    val qtyMilli: Long,
    /** Value of the whole opening quantity; null = quantity × purchase price. */
    val valuePaise: Long? = null,
    val batchNo: String? = null,
    val mfgDate: LocalDate? = null,
    val expiryDate: LocalDate? = null,
    val meta: PostMeta,
)

data class PartyOpeningInput(
    val partyId: String,
    val amountPaise: Long,
    /** True when the business owes a customer / a supplier owes the business (advance). */
    val inPartysFavour: Boolean = false,
    val date: LocalDate,
    val dueDate: LocalDate? = null,
    val meta: PostMeta,
)

data class MoneyOpeningInput(
    val moneyAccountId: String,
    /** May be negative (overdraft). */
    val amountPaise: Long,
    val date: LocalDate,
    val meta: PostMeta,
)

data class TransferInput(
    val fromAccountId: String,
    val toAccountId: String,
    val amountPaise: Long,
    val date: LocalDate,
    val reference: String? = null,
    val meta: PostMeta,
)

enum class BooksErrorCode {
    BUSINESS_NOT_SET_UP,
    INVALID_DATE,
    NUMBER_REQUIRED,
    FUTURE_DATE,
    PARTY_REQUIRED,
    PARTY_NOT_FOUND,
    WRONG_PARTY_KIND,
    NO_ITEMS,
    PRODUCT_NOT_FOUND,
    PRODUCT_INACTIVE,
    INVALID_QUANTITY,
    INVALID_UNIT,
    INVALID_PRICE,
    BELOW_MIN_SELLING_PRICE,
    INVALID_GST,
    INVALID_HSN,
    BATCH_REQUIRED,
    BATCH_NOT_FOUND,
    INSUFFICIENT_STOCK,
    INVALID_AMOUNT,
    OVERPAYMENT,
    DUPLICATE_NUMBER,
    DUPLICATE_PAYMENT,
    MONEY_ACCOUNT_NOT_FOUND,
    ALLOCATION_INVALID,
    RETURN_INVALID,
    REASON_REQUIRED,
    ALREADY_VOID,
    HAS_DEPENDENTS,
    DRAFT_REQUIRED,
    NOT_FOUND,
    OPENING_EXISTS,
    REQUIRED_FIELD,
    INVALID_FIELD,
    DUPLICATE_NAME,
    DUPLICATE_BARCODE,
    DUPLICATE_SKU,
}

data class BooksError(val code: BooksErrorCode, val message: String, val field: String? = null)

sealed class PostResult {
    /** [duplicate] = this client key was already posted; nothing new was written. */
    data class Posted(val txn: TxnEntity, val duplicate: Boolean = false) : PostResult()
    data class Rejected(val errors: List<BooksError>) : PostResult()
}

/** One line of a document preview, as the engine will post it. */
data class QuoteLine(
    val name: String,
    val hsn: String?,
    val qtyMilli: Long,
    val unit: String,
    val ratePaise: Long,
    val gstBp: Int,
    val taxType: String,
    val result: com.shopai.app.books.tax.TaxLineResult,
)

/**
 * A document worked out by the same plan that posts it — totals, tax, round-off
 * and the number it will get — or the reasons it cannot be posted. Nothing is written.
 */
data class Quote(
    val totals: com.shopai.app.books.tax.InvoiceTotals?,
    val lines: List<QuoteLine>,
    val interState: Boolean,
    val placeOfSupply: String?,
    val number: String?,
    val errors: List<BooksError>,
) {
    val ok: Boolean get() = errors.isEmpty() && totals != null
}

data class AllocationPreview(val doc: com.shopai.app.books.data.OpenDoc, val amountPaise: Long)

/** Which documents a payment settles and what is left over as advance. */
data class PaymentQuote(val allocations: List<AllocationPreview>, val excessPaise: Long, val errors: List<BooksError>)

internal class RejectException(val errors: List<BooksError>) : RuntimeException(errors.joinToString { it.message })

internal fun reject(code: BooksErrorCode, message: String, field: String? = null): Nothing =
    throw RejectException(listOf(BooksError(code, message, field)))
