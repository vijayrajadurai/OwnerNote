package com.shopai.app.books.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * OwnerNote Books — the one accounting + inventory engine.
 *
 * Money is always whole paise in a [Long] (₹1 = 100). Quantities are whole
 * thousandths in a [Long] (1 kg = 1000, 0.25 kg = 250), so every SUM in the
 * database is exact. Rates (GST, cess, discount %) are basis points
 * (18% = 1800). Doubles are never used for money.
 */
object Money {
    fun ofRupees(rupees: String): Long =
        BigDecimal(rupees.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun ofRupees(rupees: Long): Long = Math.multiplyExact(rupees, 100L)

    fun toRupees(paise: Long): BigDecimal = BigDecimal.valueOf(paise, 2)

    /** paise × (milli-quantity / 1000), rounded half-up to the paisa. */
    fun times(ratePaise: Long, qtyMilli: Long): Long =
        BigDecimal.valueOf(ratePaise).multiply(BigDecimal.valueOf(qtyMilli))
            .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact()

    /** paise × basis points / 10000, rounded half-up. */
    fun percent(paise: Long, bp: Int): Long =
        BigDecimal.valueOf(paise).multiply(BigDecimal.valueOf(bp.toLong()))
            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact()

    /** Rounds to the nearest whole rupee (half-up); returns the rounded paise. */
    fun roundToRupee(paise: Long): Long =
        BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact() * 100
}

object Qty {
    fun of(value: String): Long =
        BigDecimal(value.trim()).movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun of(whole: Long): Long = Math.multiplyExact(whole, 1000L)

    fun toDecimal(milli: Long): BigDecimal = BigDecimal.valueOf(milli, 3).stripTrailingZeros()
}

enum class BusinessType(val inventory: Boolean) {
    RETAILER(true),
    WHOLESALER(true),
    DISTRIBUTOR(true),
    SERVICE(false),
    PRODUCT_AND_SERVICE(true),
}

enum class PartyKind { CUSTOMER, SUPPLIER }

enum class CustomerType { RETAIL, BUSINESS, B2B, B2C }

enum class TaxType { GST, EXEMPT, NIL_RATED, NON_GST }

enum class CodeKind { HSN, SAC }

/** Every document the engine can post. */
enum class TxnType(val prefix: String) {
    SALE("INV"),
    PURCHASE("PUR"),
    SALE_RETURN("CN"),
    PURCHASE_RETURN("DN"),
    PAYMENT_IN("PI"),
    PAYMENT_OUT("PO"),
    CASH_IN("CI"),
    CASH_OUT("CO"),
    STOCK_ADJUSTMENT("ADJ"),
    OPENING_BALANCE("OB"),
    OPENING_STOCK("OS"),
    MONEY_TRANSFER("TR"),
}

enum class TxnStatus { CONFIRMED, VOID }

enum class TxnSource { MANUAL, VOICE, OCR, IMPORT, SYSTEM }

enum class PaymentMode { CASH, UPI, BANK_TRANSFER, CARD, CHEQUE, CREDIT, OTHER }

enum class MoneyAccountKind { CASH, BANK, UPI, CARD, OTHER }

/** Ledger heads. Party heads carry a partyId, money heads a moneyAccountId. */
enum class Account {
    RECEIVABLE,
    PAYABLE,
    SALES,
    SALES_RETURN,
    PURCHASES,
    PURCHASE_RETURN,
    OUTPUT_TAX,
    INPUT_TAX,
    MONEY,
    INCOME,
    EXPENSE,
    CAPITAL,
    OPENING_EQUITY,
    ROUND_OFF,
}

enum class MovementType(val sign: Int) {
    OPENING_STOCK(1),
    PURCHASE(1),
    SALE(-1),
    SALE_RETURN(1),
    PURCHASE_RETURN(-1),
    ADJUSTMENT_IN(1),
    ADJUSTMENT_OUT(-1),
    TRANSFER_IN(1),
    TRANSFER_OUT(-1),
}

enum class AdjustmentReason { DAMAGED, EXPIRED, MISSING, WASTAGE, PHYSICAL_COUNT, OPENING_CORRECTION, OTHER }

enum class CashInSource { OTHER_INCOME, OWNER_CAPITAL, REFUND_RECEIVED, OTHER_RECEIPT }

enum class SyncState { PENDING, SYNCED, FAILED, CONFLICT }

enum class Role { OWNER, MANAGER, STAFF, ACCOUNTANT }

enum class DraftStatus { OPEN, CONFIRMED, DISCARDED }

/** Standard units; businesses may add their own. */
val STANDARD_UNITS = listOf("PCS", "KG", "GRAM", "LITRE", "ML", "METER", "BOX", "PACK", "BAG", "BOTTLE", "DOZEN", "PAIR", "SET")

/**
 * The GST slabs the rate picker offers by default. Stored in the business
 * settings and editable there, so a change in the official slabs is a data
 * update, not an app release.
 */
val DEFAULT_GST_RATES_BP = listOf(0, 10, 25, 100, 150, 300, 500, 600, 750, 1200, 1800, 2800, 4000)
