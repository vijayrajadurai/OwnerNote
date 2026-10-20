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
    /** The Daily Cash Note (Kai's Daily Sales Note): a sale, the opening Kallapetti, the day close — only after the owner confirmed. */
    private val dailyCash: com.shopai.app.data.repository.DailyCashRepository? = null,
    /** The business profile's category (the backend's business, read once and kept — never on every message). */
    private val category: (suspend () -> String?)? = null,
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
            StockFact(p.name, qty(s.ledger.stock(p.id)), p.primaryUnit, (p.reorderLevelMilli ?: p.minStockMilli)?.let(::qty), p.imagePath)
        }
    }

    override suspend fun lowStock(): List<StockFact>? {
        val s = session() ?: return null
        val biz = s.ctx.businessId
        val moved = s.dao.allStock(biz).associateBy { it.productId }
        return s.dao.products(biz, false, 2000, 0).filter { !it.isService && it.active }.mapNotNull { p ->
            val level = p.reorderLevelMilli ?: p.minStockMilli
            val have = moved[p.id]?.qtyMilli ?: return@mapNotNull if (level != null && level > 0) StockFact(p.name, BigDecimal.ZERO, p.primaryUnit, qty(level), p.imagePath) else null
            when {
                level != null && have <= level -> StockFact(p.name, qty(have), p.primaryUnit, qty(level), p.imagePath)
                have <= 0 -> StockFact(p.name, qty(have), p.primaryUnit, level?.let(::qty), p.imagePath)
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
            val conversions = e?.let(::conversionsOf).orEmpty()
            com.shopai.app.brain.tools.ProductRef(it.id, it.name, it.unit, BigDecimal.valueOf(it.currentStock), conversions,
                purchasePrice = e?.purchasePricePaise?.let(::rupees), sellingPrice = e?.sellingPricePaise?.let(::rupees),
                minStock = e?.minStockMilli?.let(::qty), category = it.category, batchTracked = e?.batchTracked == true)
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
            // Quotes need no draft; the post carries the owner's reviewed entry as a voice draft (the books refuse a voice post without one).
            val meta = com.shopai.app.books.engine.PostMeta(java.util.UUID.randomUUID().toString(), TxnSource.VOICE, notes = "Kai: ${bill.said.take(80)}")
            var draftId: String? = null
            suspend fun reviewed(kind: String) = meta.copy(draftId = voiceDraft(s, kind, bill.said).also { draftId = it })
            suspend fun paidNow(grandTotal: Long?): List<com.shopai.app.books.engine.PaymentPart> {
                if (bill.credit || grandTotal == null || grandTotal <= 0) return emptyList()
                val account = MoneyAccounts.accountFor(s, bill.mode) ?: return emptyList()
                return listOf(com.shopai.app.books.engine.PaymentPart(account.id, bill.mode, grandTotal))
            }
            // A batch-tracked product: the sale from the batch that expires first, the purchase into a new batch.
            val items = s.engine.fillBatches(listOf(item), forSale = !bill.purchase, LocalDate.now())
            val result = if (bill.purchase) {
                val base = com.shopai.app.books.engine.PurchaseInput(partyId = bill.partyId, billNumber = null, date = LocalDate.now(), items = items, meta = meta)
                val quote = s.engine.quotePurchase(base)
                if (!quote.ok) return ActionOutcome.Failed(quote.errors.firstOrNull()?.message ?: "not saved")
                s.engine.postPurchase(base.copy(paid = paidNow(quote.totals?.grandTotalPaise), meta = reviewed("PurchaseInput")))
            } else {
                val base = com.shopai.app.books.engine.SaleInput(partyId = bill.partyId, date = LocalDate.now(), items = items, meta = meta)
                val quote = s.engine.quoteSale(base)
                if (!quote.ok) return ActionOutcome.Failed(quote.errors.firstOrNull()?.message ?: "not saved")
                s.engine.postSale(base.copy(received = paidNow(quote.totals?.grandTotalPaise), meta = reviewed("SaleInput")))
            }
            when (result) {
                is PostResult.Posted -> ActionOutcome.Done(result.txn.number, qty(s.ledger.stock(p.id)))
                is PostResult.Rejected -> {
                    draftId?.let { runCatching { s.engine.discardDraft(it) } }
                    android.util.Log.w("Kai", "stock bill refused: ${result.errors.joinToString { it.message }}")
                    ActionOutcome.Failed(result.errors.firstOrNull()?.message ?: "not saved")
                }
            }
        }.getOrElse { ActionOutcome.Failed(it.message ?: "not saved") }
    }

    /**
     * Kai's entry was reviewed in the chat (the owner saw the summary and tapped Confirm). The books accept a voice post only
     * with its reviewed draft, so it is kept as one — the same as the stock screens' voice entries ([LegacyBridge]).
     */
    private suspend fun voiceDraft(s: BooksSession, kind: String, said: String): String =
        s.engine.saveDraft(kind, TxnSource.VOICE, "{\"by\":\"kai\"}", rawInput = said.take(200))

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

    /**
     * New stock of a batch-tracked product as the batch the owner said (Confirm on the draft): the books' own batch record —
     * a batch not yet in the books is created with the expiry said; only an expiry said → a new batch named by the date.
     */
    override suspend fun stockInBatch(product: com.shopai.app.brain.tools.ProductRef, qty: BigDecimal, said: String, batch: com.shopai.app.brain.tools.StockBatch): ActionOutcome {
        val s = session() ?: return changeStock(product, qty, true, said)
        return runCatching {
            val no = batch.batchNo?.trim()?.takeIf { it.isNotEmpty() } ?: freeBatchNo(s, product.id, batch.expiry)
            // "expiry" / "batch" in the owner's words name the batch — they are not the reason (an expired-stock write-off).
            val why = said.replace(Regex("""(?i)(?<![\p{L}])(?:exp\w*|best\s+before|batch)(?![\p{L}])"""), " ")
            val result = s.engine.postStockAdjustment(
                com.shopai.app.books.engine.StockAdjustmentInput(
                    productId = product.id, date = LocalDate.now(), reason = com.shopai.app.books.engine.AdjustmentReasonText.from(why),
                    changeQtyMilli = qty.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact(),
                    batchNo = no, expiryDate = batch.expiry, note = "Kai: ${said.take(80)}",
                    meta = com.shopai.app.books.engine.PostMeta(java.util.UUID.randomUUID().toString()),
                ),
            )
            when (result) {
                is PostResult.Posted -> ActionOutcome.Done(product.name, BigDecimal.valueOf(s.ledger.stock(product.id), 3))
                is PostResult.Rejected -> ActionOutcome.Failed(result.errors.firstOrNull()?.message ?: "not saved")
            }
        }.getOrElse { ActionOutcome.Failed(it.message ?: "not saved") }
    }

    override suspend fun keepByBatch(product: com.shopai.app.brain.tools.ProductRef): ActionOutcome {
        val s = session() ?: return ActionOutcome.Failed("Books not ready")
        return runCatching {
            when (val r = s.masters.startBatchTracking(product.id)) {
                is com.shopai.app.books.engine.MasterResult.Ok -> ActionOutcome.Done(product.name, null)
                is com.shopai.app.books.engine.MasterResult.Rejected -> ActionOutcome.Failed(r.errors.firstOrNull()?.message ?: "not saved")
            }
        }.getOrElse { ActionOutcome.Failed(it.message ?: "not saved") }
    }

    /** "B-261013" for new stock with only its expiry said; "B-261013-2" when today's batch has another expiry. */
    private suspend fun freeBatchNo(s: BooksSession, productId: String, expiry: LocalDate?): String {
        val base = com.shopai.app.books.model.BatchPick.newNo(LocalDate.now())
        for (no in listOf(base) + (2..99).map { "$base-$it" }) {
            val b = s.dao.batchByNo(productId, no) ?: return no
            if (b.expiryDay == expiry?.toEpochDay()?.toInt()) return no
        }
        return "$base-${java.util.UUID.randomUUID().toString().take(4)}"
    }

    /** Only called after the owner checked the product details (typed / read from a photo). Stock is added after, as a normal stock in. */
    override suspend fun createProduct(product: com.shopai.app.brain.tools.NewProduct): com.shopai.app.brain.tools.ProductRef? {
        // A chat entry ("Colgate 5 box, boxku 48 pieces, purchase 28 …"): units, prices and opening stock in one write of the books.
        if (product.secondaryUnit != null || product.purchasePrice != null || product.sellingPrice != null || (product.openingQty?.signum() ?: 0) > 0 ||
            !product.batchNo.isNullOrBlank() || product.expiry != null) {
            // Without the books (local / server mode) the same entry goes through the inventory screen's own store below.
            session()?.let { s ->
                return runCatching { createWithOpening(s, product) }
                    .onFailure { android.util.Log.w("Kai", "product not saved: ${product.name}: ${it.message}") }.getOrNull()
            }
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
            // A batch / expiry said (medicines, food, seeds, paint …): the product is batch-tracked and its stock is the books'
            // own batch record with that expiry — so the expiry list and the daily alert have it. Batch tracking is switched on
            // for the business; screens that don't choose a batch get one picked by the books (sale first-expiry-first).
            val batch = product.batchNo?.trim()?.takeIf { it.isNotEmpty() }
                ?: product.expiry?.let { com.shopai.app.books.model.BatchPick.newNo(LocalDate.now()) }
            if (batch != null && s.dao.business(s.ctx.businessId)?.batchTracking != true) {
                val on = s.masters.updateSettings(batchTracking = true)
                if (on is com.shopai.app.books.engine.MasterResult.Rejected) error(on.errors.firstOrNull()?.message ?: "Batch tracking not switched on")
            }
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
                    imagePath = product.imageUri, description = notes, batchTracked = batch != null,
                ),
            )
            val row = (result as? com.shopai.app.books.engine.MasterResult.Ok)?.value
                ?: error((result as? com.shopai.app.books.engine.MasterResult.Rejected)?.errors?.firstOrNull()?.message ?: "Product not saved")
            created = row
            val opening = product.openingQty?.takeIf { it.signum() > 0 }
                ?.multiply(BigDecimal(1000))?.setScale(0, RoundingMode.HALF_UP)?.longValueExact() ?: 0L
            if (opening > 0) {
                val posted = s.engine.postOpeningStock(
                    com.shopai.app.books.engine.OpeningStockInput(
                        productId = row.id, date = LocalDate.now(), qtyMilli = opening, batchNo = batch, expiryDate = product.expiry,
                        meta = com.shopai.app.books.engine.PostMeta(
                            java.util.UUID.randomUUID().toString(), TxnSource.VOICE,
                            // The owner reviewed it in the chat; without its draft the books refuse a voice post (and nothing was saved).
                            draftId = voiceDraft(s, "OpeningStockInput", "${product.name} ${product.openingQty?.toPlainString().orEmpty()} ${product.unit}"),
                            notes = "Kai: opening stock",
                        ),
                    ),
                )
                if (posted !is PostResult.Posted) error((posted as? PostResult.Rejected)?.errors?.firstOrNull()?.message ?: "Opening stock not saved")
            } else if (batch != null) {
                // No stock yet: the batch is recorded with its expiry, and the first stock in goes into it.
                s.dao.upsertBatch(com.shopai.app.books.data.BatchEntity(java.util.UUID.randomUUID().toString(), s.ctx.businessId, row.id, batch,
                    expiryDay = product.expiry?.toEpochDay()?.toInt(), createdAt = System.currentTimeMillis()))
            }
        }
        val p = created ?: error("Product not saved")
        return com.shopai.app.brain.tools.ProductRef(p.id, p.name, p.primaryUnit, qty(s.ledger.stock(p.id)), conversionsOf(p), batchTracked = p.batchTracked)
    }

    /** Every unit the product holds a conversion for (its second unit and any more), as primary units in one of it. */
    private fun conversionsOf(p: com.shopai.app.books.data.ProductEntity): Map<String, BigDecimal> {
        val second = if (p.secondaryUnit != null && p.conversionMilli != null && p.conversionMilli > 0) mapOf(p.secondaryUnit to p.conversionMilli) else emptyMap()
        return (second + com.shopai.app.books.model.ProductUnits.parseMore(p.moreUnits)).mapValues { BigDecimal.valueOf(it.value).divide(BigDecimal(1000)) }
    }

    override suspend fun expiring(until: LocalDate): List<com.shopai.app.brain.tools.BatchFact>? {
        val s = session() ?: return null
        return s.dao.morningExpiring(s.ctx.businessId, until.toEpochDay().toInt(), 200).map {
            com.shopai.app.brain.tools.BatchFact(it.productName, it.batchNo, LocalDate.ofEpochDay(it.expiryDay.toLong()), qty(it.qtyMilli), it.unit)
        }
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
                // The same payment already saved today: a second one the owner may well mean (two visits, two GPay sends) — the
                // draft says so and Confirm saves it; it is never refused (owner, 19 Oct 2026).
                val sameDay = quote.errors.any { it.code == BooksErrorCode.DUPLICATE_PAYMENT }
                suspend fun quoteOf(d: PaymentDraft) = s.engine.quotePayment(d.toInput(s, "kai-quote", TxnSource.VOICE).copy(allowSameDayDuplicate = sameDay), incoming)
                if (sameDay) quote = quoteOf(draft)
                if (quote.errors.any { it.code == BooksErrorCode.OVERPAYMENT }) {
                    draft = draft.copy(keepAsAdvance = true)
                    quote = quoteOf(draft)
                }
                val draftId = BillDrafts(s).save(null, draft.kind, draft, TxnSource.VOICE, raw = said)
                val before = if (incoming) s.ledger.receivable(partyId) else s.ledger.payable(partyId)
                ActionPlan(
                    key = draftId, kind = kind, partyName = partyName, partyId = partyId, amount = amount, mode = mode,
                    settles = quote.allocations.map { it.doc.number to rupees(it.amountPaise) },
                    advance = rupees(quote.excessPaise),
                    balanceBefore = rupees(before), balanceAfter = rupees(before - amountPaise),
                    problems = quote.errors.map { it.message }, draftId = draftId, said = said, sameDayRepeat = sameDay,
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
                // (the owner confirmed a second payment of the same amount today, told so on the draft)
                val input = draft.toInput(s, draftId, TxnSource.VOICE).let { if (plan.sameDayRepeat) it.copy(allowSameDayDuplicate = true) else it }
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

    // ------------------------------------------------------------ the Daily Sales Note (Daily Cash Note)

    override suspend fun cashOpening(day: LocalDate): BigDecimal? =
        runCatching { dailyCash?.getOpeningBalance(com.shopai.app.util.localDateKey(day))?.let { BigDecimal.valueOf(it) } }.getOrNull()

    override suspend fun setCashOpening(day: LocalDate, amount: BigDecimal): ActionOutcome {
        val cash = dailyCash ?: return ActionOutcome.Failed("daily cash note unavailable")
        return runCatching {
            val box = cash.setOpeningBalance(com.shopai.app.util.localDateKey(day), amount.toDouble())
            ActionOutcome.Done("kallapetti", BigDecimal.valueOf(box))
        }.getOrElse { ActionOutcome.Failed(it.message ?: "could not set the opening") }
    }

    override suspend fun addCashSale(day: LocalDate, amount: BigDecimal, upi: Boolean, note: String): ActionOutcome {
        val cash = dailyCash ?: return ActionOutcome.Failed("daily cash note unavailable")
        return runCatching {
            val e = cash.createDailyCashEntry(com.shopai.app.util.localDateKey(day), com.shopai.app.data.model.DailyCashEntryType.IN, amount.toDouble(),
                if (upi) com.shopai.app.data.model.DailyCashPaymentMode.UPI else com.shopai.app.data.model.DailyCashPaymentMode.CASH, note)
            // Read back from the note itself: saved only when the entry is there.
            val stored = cash.listDailyCashEntries(e.date).any { it.id == e.id }
            if (stored) ActionOutcome.Done(e.id, BigDecimal.valueOf(e.amount)) else ActionOutcome.Failed("not stored")
        }.getOrElse { ActionOutcome.Failed(it.message ?: "could not save") }
    }

    override suspend fun closeCashDay(day: LocalDate): ActionOutcome {
        val cash = dailyCash ?: return ActionOutcome.Failed("daily cash note unavailable")
        return runCatching {
            val key = com.shopai.app.util.localDateKey(day)
            val entries = cash.submitDayReport(key)
            if (cash.getDayStatus(key) == com.shopai.app.data.model.DailyCashDayStatus.SUBMITTED) ActionOutcome.Done(key, BigDecimal.valueOf(entries.size.toLong()))
            else ActionOutcome.Failed("not closed")
        }.getOrElse { ActionOutcome.Failed(it.message ?: "could not close the day") }
    }

    private var categoryRead: Pair<Long, String?>? = null

    override suspend fun businessCategory(): String? {
        categoryRead?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        val read = runCatching { category?.invoke() }.getOrNull()
        // (a failed read keeps the last one known — the profile doesn't change mid-day)
        if (read != null || categoryRead == null) categoryRead = System.currentTimeMillis() to (read ?: categoryRead?.second)
        return categoryRead?.second
    }

    override suspend fun sellsServices(): Boolean? = session()?.let { s ->
        runCatching { s.dao.business(s.ctx.businessId)?.businessType }.getOrNull()?.let { it == "SERVICE" || it == "PRODUCT_AND_SERVICE" }
    }

    override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String =
        actionLog.add(intent, tool, result, status, reference, input)
}
