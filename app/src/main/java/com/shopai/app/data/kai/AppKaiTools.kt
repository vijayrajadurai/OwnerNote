package com.shopai.app.data.kai

import androidx.room.withTransaction
import com.shopai.app.books.billing.BillDrafts
import com.shopai.app.books.billing.DraftKind
import com.shopai.app.books.billing.MoneyAccounts
import com.shopai.app.books.billing.PaymentDraft
import com.shopai.app.books.billing.toInput
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.nameKey
import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.integration.toInput
import com.shopai.app.books.model.MoneyAccountKind
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.TxnSource
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.MoneyBalanceFact
import com.shopai.app.brain.tools.MoneyKind
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductSalesFact
import com.shopai.app.brain.tools.StockFact
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.repository.TransactionRepository
import com.shopai.app.notifications.KaiReminderEngine
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.ContactSource
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.ReminderSaved
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Kai's tools over the existing OwnerNote systems — no separate accounting:
 *  - reads: the books ledger (the single source of truth);
 *  - money actions: the engine's own payment quote → a saved draft → posted
 *    only on Confirm (payments in / out), or the existing Credit / Debit entry
 *    (through the same engine) for money given / received;
 *  - reminders: the phone's alarms; every action: the Kai action log.
 */
class AppKaiTools(
    private val context: android.content.Context,
    private val books: BooksModule,
    private val transactions: TransactionRepository,
    private val reminders: KaiReminderEngine,
    private val actionLog: KaiActionLog,
    /** Stock in / out through the existing inventory engine (the books after the import). */
    private val inventory: com.shopai.app.data.repository.InventoryRepository? = null,
    /** The signed-in business + owner (from the session — never from a screen); reminders are theirs only. */
    private val scope: () -> Pair<String?, String?> = { null to null },
) : KaiTools {

    private suspend fun session(): BooksSession? = runCatching { books.session() }.getOrNull()

    private fun rupees(paise: Long): BigDecimal = BigDecimal.valueOf(paise, 2)
    private fun qty(milli: Long): BigDecimal = BigDecimal.valueOf(milli, 3)
    private fun paise(amount: BigDecimal): Long = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    // ------------------------------------------------------------ reads

    override suspend fun sales(from: LocalDate, to: LocalDate): BigDecimal? = session()?.let { rupees(it.ledger.netSales(from, to)) }
    override suspend fun purchases(from: LocalDate, to: LocalDate): BigDecimal? = session()?.let { rupees(it.ledger.netPurchases(from, to)) }
    override suspend fun expenses(from: LocalDate, to: LocalDate): BigDecimal? = session()?.let { rupees(it.ledger.expenses(from, to)) }

    override suspend fun moneyBalances(): List<MoneyBalanceFact>? {
        val s = session() ?: return null
        return s.dao.moneyAccounts(s.ctx.businessId).map { a ->
            val kind = when (a.kind) {
                MoneyAccountKind.CASH.name -> MoneyKind.CASH
                MoneyAccountKind.BANK.name -> MoneyKind.BANK
                MoneyAccountKind.UPI.name -> MoneyKind.UPI
                else -> MoneyKind.ALL
            }
            MoneyBalanceFact(a.name, kind, rupees(s.ledger.moneyBalance(a.id)))
        }
    }

    override suspend fun stock(product: String?): List<StockFact>? {
        val s = session() ?: return null
        val biz = s.ctx.businessId
        val products = if (product == null) {
            val withStock = s.dao.allStock(biz).associateBy { it.productId }
            s.dao.products(biz, false, 500, 0).filter { !it.isService && it.id in withStock }
        } else s.dao.searchProducts(biz, nameKey(product), 10, 0).filter { !it.isService }
        return products.map { p ->
            StockFact(p.name, qty(s.ledger.stock(p.id)), p.primaryUnit, (p.reorderLevelMilli ?: p.minStockMilli)?.let(::qty))
        }
    }

    override suspend fun lowStock(): List<StockFact>? {
        val s = session() ?: return null
        val biz = s.ctx.businessId
        val moved = s.dao.allStock(biz).associateBy { it.productId }
        return s.dao.products(biz, false, 2000, 0).filter { !it.isService && it.active }.mapNotNull { p ->
            val level = p.reorderLevelMilli ?: p.minStockMilli
            val have = moved[p.id]?.qtyMilli ?: return@mapNotNull if (level != null && level > 0) StockFact(p.name, BigDecimal.ZERO, p.primaryUnit, qty(level)) else null
            when {
                level != null && have <= level -> StockFact(p.name, qty(have), p.primaryUnit, qty(level))
                have <= 0 -> StockFact(p.name, qty(have), p.primaryUnit, level?.let(::qty))
                else -> null
            }
        }.sortedBy { it.qty }
    }

    override suspend fun products(): List<com.shopai.app.brain.tools.ProductRef>? {
        val inv = inventory ?: return null
        val list = runCatching { inv.listProducts() }.getOrNull() ?: return null
        // The books keep each product's second unit and its conversion (1 BOX = 12 PCS) — Kai converts with exactly that.
        val units = session()?.let { s ->
            runCatching { s.dao.products(s.ctx.businessId, archived = false, limit = 10_000, offset = 0).associateBy { it.id } }.getOrNull()
        }.orEmpty()
        return list.map {
            val e = units[it.id]
            val secondary = e?.secondaryUnit
            val milli = e?.conversionMilli
            val conversions = if (secondary != null && milli != null && milli > 0) mapOf(secondary to BigDecimal.valueOf(milli).divide(BigDecimal(1000))) else emptyMap()
            com.shopai.app.brain.tools.ProductRef(it.id, it.name, it.unit, BigDecimal.valueOf(it.currentStock), conversions,
                purchasePrice = e?.purchasePricePaise?.let(::rupees), sellingPrice = e?.sellingPricePaise?.let(::rupees),
                minStock = e?.minStockMilli?.let(::qty), category = it.category)
        }
    }

    /**
     * The owner tapped Save on "1 box = 12 pieces": stored as the product's own second
     * unit in the signed-in business's books (the existing product units — no other store).
     * A product keeps one second unit; a different one already set is never overwritten.
     */
    override suspend fun saveUnitConversion(productId: String, unit: String, perUnit: BigDecimal): com.shopai.app.brain.tools.ConversionSave {
        val none = com.shopai.app.brain.tools.ConversionSave.UNAVAILABLE
        val s = session() ?: return none
        return runCatching {
            val p = s.dao.product(productId)?.takeIf { it.businessId == s.ctx.businessId } ?: return none
            val code = s.masters.ensureUnit(unit) ?: return none
            if (code.equals(p.primaryUnit, ignoreCase = true) || perUnit.signum() <= 0) return none
            if (p.secondaryUnit != null && !p.secondaryUnit.equals(code, ignoreCase = true)) return com.shopai.app.brain.tools.ConversionSave.OTHER_UNIT_SET
            val milli = perUnit.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact()
            when (s.masters.updateProduct(p.id, p.toInput().copy(secondaryUnit = code, conversionMilli = milli))) {
                is com.shopai.app.books.engine.MasterResult.Ok -> com.shopai.app.brain.tools.ConversionSave.SAVED
                else -> none
            }
        }.getOrDefault(none)
    }

    /** Only called after the owner confirmed: new prices / minimum level on the product (stock untouched). */
    override suspend fun updateProduct(productId: String, change: com.shopai.app.brain.tools.ProductChange): ActionOutcome {
        val s = session() ?: return ActionOutcome.Failed("books unavailable")
        return runCatching {
            val p = s.dao.product(productId)?.takeIf { it.businessId == s.ctx.businessId } ?: return ActionOutcome.Failed("product not found")
            val minMilli = change.minStock?.multiply(BigDecimal(1000))?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()
            val input = p.toInput().copy(
                purchasePricePaise = change.purchasePrice?.let(::paise) ?: p.purchasePricePaise,
                sellingPricePaise = change.sellingPrice?.let(::paise) ?: p.sellingPricePaise,
                minStockMilli = minMilli ?: p.minStockMilli,
            )
            when (val r = s.masters.updateProduct(p.id, input)) {
                is com.shopai.app.books.engine.MasterResult.Ok -> ActionOutcome.Done(p.name, null)
                is com.shopai.app.books.engine.MasterResult.Rejected -> ActionOutcome.Failed(r.errors.firstOrNull()?.message ?: "not saved")
            }
        }.getOrElse { ActionOutcome.Failed(it.message ?: "not saved") }
    }

    /**
     * Only called after the owner confirmed: a purchase from a supplier / a sale to a customer with its goods, posted by the
     * engine as ONE document (party balance + stock movement + any payment together — all or nothing).
     */
    override suspend fun stockBill(bill: com.shopai.app.brain.tools.StockBill): ActionOutcome {
        val s = session() ?: return ActionOutcome.Failed("books unavailable")
        return runCatching {
            MoneyAccounts.ensureDefaults(s)
            val p = s.dao.product(bill.product.id)?.takeIf { it.businessId == s.ctx.businessId } ?: return ActionOutcome.Failed("product not found")
            val item = com.shopai.app.books.engine.ItemInput(
                productId = p.id, qtyMilli = bill.qty.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact(),
                unit = p.primaryUnit, ratePaise = paise(bill.rate),
            )
            val meta = com.shopai.app.books.engine.PostMeta(java.util.UUID.randomUUID().toString(), TxnSource.VOICE, notes = "Kai: ${bill.said.take(80)}")
            suspend fun paidNow(grandTotal: Long?): List<com.shopai.app.books.engine.PaymentPart> {
                if (bill.credit || grandTotal == null || grandTotal <= 0) return emptyList()
                val account = MoneyAccounts.accountFor(s, bill.mode) ?: return emptyList()
                return listOf(com.shopai.app.books.engine.PaymentPart(account.id, bill.mode, grandTotal))
            }
            val result = if (bill.purchase) {
                val base = com.shopai.app.books.engine.PurchaseInput(partyId = bill.partyId, billNumber = null, date = LocalDate.now(), items = listOf(item), meta = meta)
                val quote = s.engine.quotePurchase(base)
                if (!quote.ok) return ActionOutcome.Failed(quote.errors.firstOrNull()?.message ?: "not saved")
                s.engine.postPurchase(base.copy(paid = paidNow(quote.totals?.grandTotalPaise)))
            } else {
                val base = com.shopai.app.books.engine.SaleInput(partyId = bill.partyId, date = LocalDate.now(), items = listOf(item), meta = meta)
                val quote = s.engine.quoteSale(base)
                if (!quote.ok) return ActionOutcome.Failed(quote.errors.firstOrNull()?.message ?: "not saved")
                s.engine.postSale(base.copy(received = paidNow(quote.totals?.grandTotalPaise)))
            }
            when (result) {
                is PostResult.Posted -> ActionOutcome.Done(result.txn.number, qty(s.ledger.stock(p.id)))
                is PostResult.Rejected -> ActionOutcome.Failed(result.errors.firstOrNull()?.message ?: "not saved")
            }
        }.getOrElse { ActionOutcome.Failed(it.message ?: "not saved") }
    }

    /** Only called after the owner confirmed the stock draft. */
    override suspend fun changeStock(product: com.shopai.app.brain.tools.ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
        val inv = inventory ?: return ActionOutcome.Failed("inventory unavailable")
        val reason = "Kai: ${said.take(80)}"
        return runCatching {
            if (incoming) {
                val p = inv.stockIn(product.id, qty.toDouble(), reason)
                ActionOutcome.Done(p.name, BigDecimal.valueOf(p.currentStock))
            } else {
                when (val r = inv.stockOut(product.id, qty.toDouble(), reason)) {
                    is com.shopai.app.data.repository.StockChangeResult.Ok -> ActionOutcome.Done(r.product.name, BigDecimal.valueOf(r.product.currentStock))
                    is com.shopai.app.data.repository.StockChangeResult.InsufficientStock ->
                        ActionOutcome.Failed("only ${BigDecimal.valueOf(r.available).stripTrailingZeros().toPlainString()} ${product.unit} in stock")
                }
            }
        }.getOrElse { ActionOutcome.Failed(it.message ?: "not saved") }
    }

    /** Only called after the owner checked the product details (typed / read from a photo). Stock is added after, as a normal stock in. */
    override suspend fun createProduct(product: com.shopai.app.brain.tools.NewProduct): com.shopai.app.brain.tools.ProductRef? {
        // A chat entry ("Colgate 5 box, boxku 48 pieces, purchase 28 …"): units, prices and opening stock in one write of the books.
        if (product.secondaryUnit != null || product.purchasePrice != null || product.sellingPrice != null || (product.openingQty?.signum() ?: 0) > 0) {
            // Without the books (local / server mode) the same entry goes through the inventory screen's own store below.
            session()?.let { s -> return runCatching { createWithOpening(s, product) }.getOrNull() }
        }
        val inv = inventory ?: return null
        val name = listOfNotNull(product.name.trim(), product.variant?.trim()?.takeIf { it.isNotEmpty() && !product.name.contains(it, true) })
            .joinToString(" ")
        val notes = listOfNotNull(
            product.weight?.takeIf { it.isNotBlank() }?.let { "Weight: $it" },
            product.packSize?.takeIf { it.isNotBlank() }?.let { "Pack: $it" },
            product.batchNo?.takeIf { it.isNotBlank() }?.let { "Batch: $it" },
            product.expiry?.let { "Expiry: ${com.shopai.app.brain.tools.KaiInventory.expiryShown(it)}" },
            "Added by Kai",
        ).joinToString(" · ")
        return runCatching {
            val p = inv.createProduct(
                com.shopai.app.data.model.CreateInventoryProductInput(
                    name = name,
                    category = product.category.ifBlank { "General" },
                    brand = product.brand?.takeIf { it.isNotBlank() },
                    unit = product.unit.ifBlank { "PCS" },
                    currentStock = product.openingQty?.toDouble() ?: 0.0,
                    minimumStock = 0.0,
                    purchasePrice = product.purchasePrice?.toDouble(),
                    sellingPrice = product.sellingPrice?.toDouble(),
                    imageUri = product.imageUri,
                    notes = notes,
                ),
            )
            com.shopai.app.brain.tools.ProductRef(p.id, p.name, p.unit, BigDecimal.valueOf(p.currentStock))
        }.getOrNull()
    }

    /**
     * The product (primary unit, its pack unit and conversion, purchase and selling price per primary unit) and its
     * opening stock, in ONE transaction: if any part is rejected nothing is saved — never a product without its stock
     * or stock posted twice. The engine values the opening stock at the purchase price.
     */
    private suspend fun createWithOpening(s: BooksSession, product: com.shopai.app.brain.tools.NewProduct): com.shopai.app.brain.tools.ProductRef {
        var created: com.shopai.app.books.data.ProductEntity? = null
        s.db.withTransaction {
            val unit = s.masters.ensureUnit(product.unit.ifBlank { "PCS" }) ?: error("Check the unit")
            val second = product.secondaryUnit?.let { s.masters.ensureUnit(it) ?: error("Check the unit") }?.takeIf { !it.equals(unit, ignoreCase = true) }
            val perMilli = product.perSecondary?.takeIf { second != null && it.signum() > 0 }
                ?.multiply(BigDecimal(1000))?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()
            // Batch and expiry are kept with the product. The product is not marked batch-tracked: Kai's own stock in / out
            // (and the stock screen's) do not choose a batch yet, and the books would reject them for a batch-tracked product.
            val notes = listOfNotNull(
                product.weight?.takeIf { it.isNotBlank() }?.let { "Size: $it" },
                product.packSize?.takeIf { it.isNotBlank() }?.let { "Size: $it" },
                product.batchNo?.takeIf { it.isNotBlank() }?.let { "Batch: $it" },
                product.expiry?.let { "Expiry: ${com.shopai.app.brain.tools.KaiInventory.expiryShown(it)}" },
                "Added by Kai",
            ).joinToString(" · ")
            val result = s.masters.createProduct(
                com.shopai.app.books.engine.ProductInput(
                    name = product.name.trim(), primaryUnit = unit,
                    secondaryUnit = if (perMilli != null) second else null, conversionMilli = perMilli,
                    categoryId = s.masters.categoryId(product.category.ifBlank { "General" }),
                    brandId = s.masters.brandId(product.brand?.takeIf { it.isNotBlank() }),
                    purchasePricePaise = product.purchasePrice?.let(::paise), sellingPricePaise = product.sellingPrice?.let(::paise),
                    imagePath = product.imageUri, description = notes,
                ),
            )
            val row = (result as? com.shopai.app.books.engine.MasterResult.Ok)?.value ?: error("Product not saved")
            created = row
            val opening = product.openingQty?.takeIf { it.signum() > 0 }
                ?.multiply(BigDecimal(1000))?.setScale(0, RoundingMode.HALF_UP)?.longValueExact() ?: 0L
            if (opening > 0) {
                val posted = s.engine.postOpeningStock(
                    com.shopai.app.books.engine.OpeningStockInput(
                        productId = row.id, date = LocalDate.now(), qtyMilli = opening,
                        meta = com.shopai.app.books.engine.PostMeta(java.util.UUID.randomUUID().toString(), TxnSource.VOICE, notes = "Kai: opening stock"),
                    ),
                )
                if (posted !is PostResult.Posted) error("Opening stock not saved")
            }
        }
        val p = created ?: error("Product not saved")
        val conversions = if (p.secondaryUnit != null && p.conversionMilli != null && p.conversionMilli > 0)
            mapOf(p.secondaryUnit to BigDecimal.valueOf(p.conversionMilli).divide(BigDecimal(1000))) else emptyMap()
        return com.shopai.app.brain.tools.ProductRef(p.id, p.name, p.primaryUnit, qty(s.ledger.stock(p.id)), conversions)
    }

    override suspend fun topProducts(from: LocalDate, to: LocalDate, limit: Int): List<ProductSalesFact>? {
        val s = session() ?: return null
        return s.dao.topSold(s.ctx.businessId, from.toEpochDay().toInt(), to.toEpochDay().toInt(), limit)
            .map { ProductSalesFact(it.name, qty(it.qtyMilli), rupees(it.valuePaise)) }
    }

    override suspend fun parties(name: String): List<PartyMatch>? {
        val s = session() ?: return null
        val biz = s.ctx.businessId
        val key = nameKey(name)
        return listOf(PartyKind.CUSTOMER, PartyKind.SUPPLIER).flatMap { kind ->
            // By spelling, else the same name in the other script ("Kumar" → "குமார்").
            s.dao.searchParties(biz, kind.name, key, 10, 0)
                .ifEmpty { s.dao.parties(biz, kind.name).filter { com.shopai.app.util.NameSound.same(it.name, name) } }
                .map { p ->
                val customer = kind == PartyKind.CUSTOMER
                PartyMatch(p.id, p.name, customer, p.mobile?.let { "+91$it" },
                    rupees(if (customer) s.ledger.receivable(p.id) else s.ledger.payable(p.id)),
                    city = p.city?.takeIf { it.isNotBlank() },
                    details = listOfNotNull(p.address, p.notes).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() })
            }
        }
    }

    // ------------------------------------------------------------ money actions

    override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan? {
        val s = session()
        val amountPaise = paise(amount)
        return when (kind) {
            PlanKind.PAYMENT_IN, PlanKind.PAYMENT_OUT -> {
                if (s == null || partyId == null) return null
                MoneyAccounts.ensureDefaults(s)
                val incoming = kind == PlanKind.PAYMENT_IN
                var draft = PaymentDraft(if (incoming) DraftKind.PAYMENT_IN else DraftKind.PAYMENT_OUT, partyId, amountPaise, mode, notes = "Kai: $said")
                // The engine's own preview: which bills it settles; more than the dues becomes an advance (shown to the owner).
                var quote = s.engine.quotePayment(draft.toInput(s, "kai-quote", TxnSource.VOICE), incoming)
                if (quote.errors.any { it.code == BooksErrorCode.OVERPAYMENT }) {
                    draft = draft.copy(keepAsAdvance = true)
                    quote = s.engine.quotePayment(draft.toInput(s, "kai-quote", TxnSource.VOICE), incoming)
                }
                val draftId = BillDrafts(s).save(null, draft.kind, draft, TxnSource.VOICE, raw = said)
                val before = if (incoming) s.ledger.receivable(partyId) else s.ledger.payable(partyId)
                ActionPlan(
                    key = draftId, kind = kind, partyName = partyName, partyId = partyId, amount = amount, mode = mode,
                    settles = quote.allocations.map { it.doc.number to rupees(it.amountPaise) },
                    advance = rupees(quote.excessPaise),
                    balanceBefore = rupees(before), balanceAfter = rupees(before - amountPaise),
                    problems = quote.errors.map { it.message }, draftId = draftId, said = said,
                )
            }
            PlanKind.CREDIT_GIVEN, PlanKind.DEBIT_TAKEN -> {
                val before = if (s != null && partyId != null) {
                    if (kind == PlanKind.CREDIT_GIVEN) s.ledger.receivable(partyId) else s.ledger.payable(partyId)
                } else null
                ActionPlan(
                    key = java.util.UUID.randomUUID().toString().take(8), kind = kind, partyName = partyName, partyId = partyId,
                    amount = amount, mode = mode,
                    balanceBefore = before?.let(::rupees), balanceAfter = before?.let { rupees(it + amountPaise) },
                    said = said,
                )
            }
        }
    }

    override suspend fun confirm(plan: ActionPlan): ActionOutcome = runCatching {
        when (plan.kind) {
            PlanKind.PAYMENT_IN, PlanKind.PAYMENT_OUT -> {
                val s = session() ?: return ActionOutcome.Failed("books unavailable")
                val draftId = plan.draftId ?: return ActionOutcome.Failed("draft missing")
                val draft = BillDrafts(s).payment(draftId) ?: return ActionOutcome.Failed("draft missing")
                val input = draft.toInput(s, draftId, TxnSource.VOICE)
                when (val r = if (plan.kind == PlanKind.PAYMENT_IN) s.engine.postPaymentIn(input) else s.engine.postPaymentOut(input)) {
                    is PostResult.Posted -> ActionOutcome.Done(r.txn.number, rupees(
                        if (plan.kind == PlanKind.PAYMENT_IN) s.ledger.receivable(plan.partyId!!) else s.ledger.payable(plan.partyId!!),
                    ))
                    is PostResult.Rejected -> ActionOutcome.Failed(r.errors.joinToString("; ") { it.message })
                }
            }
            PlanKind.CREDIT_GIVEN -> {
                val detail = transactions.createCredit(
                    CreateCreditInput(customerId = plan.partyId, customerName = plan.partyName, amount = plan.amount.toDouble(), description = "Kai: ${plan.said}".take(120),
                        dueDate = plan.dueDate?.toString()),
                    source = TxnSource.VOICE,
                )
                val s = session()
                val txn = s?.dao?.txn(detail.id)
                ActionOutcome.Done(txn?.number ?: detail.id, txn?.partyId?.let { rupees(s.ledger.receivable(it)) })
            }
            PlanKind.DEBIT_TAKEN -> {
                val detail = transactions.createDebit(
                    CreateDebitInput(supplierId = plan.partyId, supplierName = plan.partyName, amount = plan.amount.toDouble(), description = "Kai: ${plan.said}".take(120),
                        dueDate = plan.dueDate?.toString()),
                    source = TxnSource.VOICE,
                )
                val s = session()
                val txn = s?.dao?.txn(detail.id)
                ActionOutcome.Done(txn?.number ?: detail.id, txn?.partyId?.let { rupees(s.ledger.payable(it)) })
            }
        }
    }.getOrElse { ActionOutcome.Failed(it.message ?: "error") }

    override suspend fun discard(plan: ActionPlan) {
        val draftId = plan.draftId ?: return
        session()?.let { runCatching { it.engine.discardDraft(draftId) } }
    }

    // ------------------------------------------------------------ reminders / audit

    override fun createReminder(reminder: KaiReminder): ReminderSaved {
        val (business, owner) = scope()
        return reminders.create(reminder.copy(businessId = reminder.businessId ?: business, ownerId = reminder.ownerId ?: owner))
    }
    override fun reminderStored(id: String): Boolean = reminders.find(id) != null
    override fun updateReminder(reminder: KaiReminder): ReminderSaved = reminders.update(reminder)
    override fun cancelReminder(id: String): Boolean = reminders.cancel(id)
    override fun completeReminder(id: String): Boolean = reminders.complete(id)
    override fun snoozeReminder(id: String, minutes: Long): KaiReminder? = reminders.snooze(id, minutes)
    override fun reminders(): List<KaiReminder> = scope().let { (b, o) -> com.shopai.app.brain.tools.KaiReminderScope.visible(reminders.open(), b, o) }
    override fun lastRang(): KaiReminder? = reminders.lastRang()?.takeIf { r -> scope().let { (b, o) -> com.shopai.app.brain.tools.KaiReminderScope.visible(listOf(r), b, o).isNotEmpty() } }
    override fun fullScreenAllowed(): Boolean? = reminders.fullScreenAllowed()

    /** OwnerNote customers / suppliers (offline, the books), then the phone's contacts if the owner allowed it. */
    override suspend fun contacts(name: String, role: PartyRole?): List<ContactMatch>? {
        val key = nameKey(name)
        fun exactName(n: String) = nameKey(n) == key || com.shopai.app.util.NameSound.same(n, name)
        val inBooks = parties(name)?.filter { p -> role == null || p.customer == (role == PartyRole.CUSTOMER) }
            ?.sortedByDescending { exactName(it.name) }
            .orEmpty()
        // "Lokesh": the Lokesh records — and "Madurai Lokesh" too when a plain Lokesh exists (two people: Kai asks which).
        val exact = inBooks.filter { exactName(it.name) }
        val chosen = if (exact.isEmpty()) inBooks else exact + (com.shopai.app.brain.chat.KaiEntityResolver.sameName(name, inBooks) - exact.toSet())
        if (chosen.isNotEmpty()) return chosen.map { ContactMatch(it.id, it.name, it.phone, if (it.customer) ContactSource.CUSTOMER else ContactSource.SUPPLIER) }
        return phoneContacts(name)
    }

    private fun phoneContacts(name: String): List<ContactMatch> {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        return runCatching {
            context.contentResolver.query(uri, cols, "${cols[1]} LIKE ?", arrayOf("$name%"), "${cols[1]} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext() && size < 10) add(ContactMatch("phone:" + c.getString(0), c.getString(1), c.getString(2), ContactSource.PHONE))
                }
            }.orEmpty().distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String =
        actionLog.add(intent, tool, result, status, reference, input)
}
