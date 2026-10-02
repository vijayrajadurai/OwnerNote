package com.shopai.app.books.billing

import com.google.gson.Gson
import com.shopai.app.books.data.DraftEntity
import com.shopai.app.books.data.MoneyAccountEntity
import com.shopai.app.books.engine.ItemInput
import com.shopai.app.books.engine.PaymentInput
import com.shopai.app.books.engine.PaymentPart
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PurchaseInput
import com.shopai.app.books.engine.ReturnInput
import com.shopai.app.books.engine.ReturnLine
import com.shopai.app.books.engine.SaleInput
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.DraftStatus
import com.shopai.app.books.model.MoneyAccountKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.model.TxnSource
import java.time.LocalDate
import java.util.UUID

/*
 * Bills, returns and payments are first kept as a draft (so nothing is lost if
 * the app closes), reviewed with the engine's own quote, and only then posted —
 * UI → Draft → Review → Confirm → engine. The draft id is the posting's
 * idempotency key, so a double tap on Confirm can never post twice.
 */

enum class DraftKind { SALE, PURCHASE, SALE_RETURN, PURCHASE_RETURN, PAYMENT_IN, PAYMENT_OUT }

data class BillLine(
    val productId: String? = null,
    val name: String,
    val qtyMilli: Long,
    val unit: String,
    val ratePaise: Long?,
    val discountBp: Int = 0,
    val gstBp: Int? = null,
    val taxType: String? = null,
    val hsn: String? = null,
    val hsnConfirmed: Boolean = false,
    val batchNo: String? = null,
)

data class BillPayment(val mode: PaymentMode, val amountPaise: Long, val reference: String? = null)

/** A sale invoice or purchase bill being written. */
data class BillDraft(
    val kind: DraftKind,
    val partyId: String? = null,
    val date: String = LocalDate.now().toString(),
    val dueDate: String? = null,
    /** Sale: blank = next number in the series. Purchase: the supplier's bill number. */
    val number: String? = null,
    val lines: List<BillLine> = emptyList(),
    val payments: List<BillPayment> = emptyList(),
    val notes: String? = null,
)

data class ReturnDraft(
    val kind: DraftKind,
    val originalTxnId: String,
    val date: String = LocalDate.now().toString(),
    val lines: List<ReturnLine> = emptyList(),
    val reason: String = "",
)

data class AllocationDraft(val docId: String, val amountPaise: Long)

data class PaymentDraft(
    val kind: DraftKind,
    val partyId: String? = null,
    val amountPaise: Long = 0,
    val mode: PaymentMode = PaymentMode.CASH,
    val reference: String? = null,
    val date: String = LocalDate.now().toString(),
    /** Null = settle the oldest dues first. */
    val allocations: List<AllocationDraft>? = null,
    val keepAsAdvance: Boolean = false,
    val notes: String? = null,
)

/** Saved drafts, by id. */
class BillDrafts(private val s: BooksSession, private val now: () -> Long = System::currentTimeMillis) {
    private val gson = Gson()

    suspend fun save(id: String?, kind: DraftKind, payload: Any, source: TxnSource = TxnSource.MANUAL, raw: String? = null): String {
        val existing = id?.let { s.dao.draft(it) }
        val draftId = existing?.id ?: id ?: UUID.randomUUID().toString()
        val t = now()
        s.dao.upsertDraft(
            DraftEntity(
                id = draftId, businessId = s.ctx.businessId, kind = kind.name, source = existing?.source ?: source.name,
                payloadJson = gson.toJson(payload), rawInput = existing?.rawInput ?: raw, status = DraftStatus.OPEN.name,
                createdAt = existing?.createdAt ?: t, updatedAt = t,
            ),
        )
        return draftId
    }

    suspend fun bill(id: String): BillDraft? = s.dao.draft(id)?.let { gson.fromJson(it.payloadJson, BillDraft::class.java) }
    suspend fun returnDraft(id: String): ReturnDraft? = s.dao.draft(id)?.let { gson.fromJson(it.payloadJson, ReturnDraft::class.java) }
    suspend fun payment(id: String): PaymentDraft? = s.dao.draft(id)?.let { gson.fromJson(it.payloadJson, PaymentDraft::class.java) }

    suspend fun discard(id: String) = s.engine.discardDraft(id)

    suspend fun open(): List<DraftEntity> = s.dao.openDrafts(s.ctx.businessId)

    suspend fun sourceOf(id: String): TxnSource = s.dao.draft(id)?.source?.let(TxnSource::valueOf) ?: TxnSource.MANUAL
}

/** Which money account a payment mode lands in. Cash → Cash in hand; UPI → UPI; bank transfer / cheque → Bank; … */
object MoneyAccounts {
    fun kindFor(mode: PaymentMode): MoneyAccountKind = when (mode) {
        PaymentMode.CASH -> MoneyAccountKind.CASH
        PaymentMode.UPI -> MoneyAccountKind.UPI
        PaymentMode.BANK_TRANSFER, PaymentMode.CHEQUE -> MoneyAccountKind.BANK
        PaymentMode.CARD -> MoneyAccountKind.CARD
        PaymentMode.OTHER, PaymentMode.CREDIT -> MoneyAccountKind.OTHER
    }

    private val defaults = listOf(MoneyAccountKind.BANK to "Bank", MoneyAccountKind.UPI to "UPI", MoneyAccountKind.CARD to "Card", MoneyAccountKind.OTHER to "Other")

    /** Makes sure there is one account of every kind (Cash in hand exists from setup). */
    suspend fun ensureDefaults(s: BooksSession) {
        val have = s.dao.moneyAccounts(s.ctx.businessId).map { it.kind }.toSet()
        defaults.filter { it.first.name !in have }.forEach { (kind, name) -> s.masters.addMoneyAccount(kind, name) }
    }

    /** The first active account of the mode's kind (read only — call [ensureDefaults] once first). */
    suspend fun accountFor(s: BooksSession, mode: PaymentMode): MoneyAccountEntity? {
        val kind = kindFor(mode)
        if (kind == MoneyAccountKind.CASH) return s.dao.moneyAccount(s.cashAccountId)
        return s.dao.moneyAccounts(s.ctx.businessId).firstOrNull { it.kind == kind.name }
    }
}

private fun meta(draftId: String, source: TxnSource, notes: String?) =
    PostMeta(clientKey = "draft-$draftId", source = source, draftId = draftId, notes = notes?.trim()?.takeIf { it.isNotEmpty() })

private fun BillLine.toItem() = ItemInput(
    productId = productId, name = name, qtyMilli = qtyMilli, unit = unit, ratePaise = ratePaise, discountBp = discountBp,
    gstBp = gstBp, taxType = taxType?.let(TaxType::valueOf), hsnCode = hsn, hsnConfirmed = hsnConfirmed, batchNo = batchNo,
)

private suspend fun BillDraft.parts(s: BooksSession): List<PaymentPart> = payments.filter { it.amountPaise > 0 }.map {
    PaymentPart(MoneyAccounts.accountFor(s, it.mode)?.id ?: "missing-${it.mode}", it.mode, it.amountPaise, it.reference)
}

suspend fun BillDraft.toSaleInput(s: BooksSession, draftId: String, source: TxnSource): SaleInput = SaleInput(
    partyId = partyId, date = LocalDate.parse(date), items = lines.map { it.toItem() }, received = parts(s),
    dueDate = dueDate?.let(LocalDate::parse), number = number, meta = meta(draftId, source, notes),
)

suspend fun BillDraft.toPurchaseInput(s: BooksSession, draftId: String, source: TxnSource): PurchaseInput = PurchaseInput(
    partyId = partyId.orEmpty(), billNumber = number, date = LocalDate.parse(date), items = lines.map { it.toItem() }, paid = parts(s),
    dueDate = dueDate?.let(LocalDate::parse), meta = meta(draftId, source, notes),
)

fun ReturnDraft.toInput(draftId: String, source: TxnSource): ReturnInput =
    ReturnInput(originalTxnId, LocalDate.parse(date), lines.filter { it.qtyMilli > 0 }, reason, meta(draftId, source, null))

suspend fun PaymentDraft.toInput(s: BooksSession, draftId: String, source: TxnSource): PaymentInput = PaymentInput(
    partyId = partyId.orEmpty(), amountPaise = amountPaise,
    moneyAccountId = MoneyAccounts.accountFor(s, mode)?.id ?: "missing-$mode", mode = mode, date = LocalDate.parse(date),
    reference = reference, allocations = allocations?.filter { it.amountPaise > 0 }?.map { it.docId to it.amountPaise },
    keepExcessAsAdvance = keepAsAdvance, meta = meta(draftId, source, notes),
)
