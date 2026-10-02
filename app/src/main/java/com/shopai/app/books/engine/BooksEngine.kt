package com.shopai.app.books.engine

import androidx.room.withTransaction
import com.shopai.app.books.data.AllocationEntity
import com.shopai.app.books.data.AuditEntity
import com.shopai.app.books.data.BatchEntity
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.data.BusinessEntity
import com.shopai.app.books.data.NumberSeriesEntity
import com.shopai.app.books.data.OutboxEntity
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.data.PostingEntity
import com.shopai.app.books.data.ProductEntity
import com.shopai.app.books.data.StockMovementEntity
import com.shopai.app.books.data.TxnEntity
import com.shopai.app.books.data.TxnItemEntity
import com.shopai.app.books.model.Account
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.model.CashInSource
import com.shopai.app.books.model.CodeKind
import com.shopai.app.books.model.DraftStatus
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.MovementType
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.ProductUnits
import com.shopai.app.books.model.SyncState
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.model.TxnSource
import com.shopai.app.books.model.TxnStatus
import com.shopai.app.books.model.TxnType
import com.shopai.app.books.tax.GstCalculator
import com.shopai.app.books.tax.GstException
import com.shopai.app.books.tax.Gstin
import com.shopai.app.books.tax.HsnRules
import com.shopai.app.books.tax.TaxLineInput
import com.shopai.app.books.tax.TaxLineResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * The one transaction engine. Sales, purchases, returns, payments, cash
 * in/out, stock adjustments and openings — typed, spoken or scanned — are
 * all posted here, each as a single database transaction that writes the
 * document, its item lines, its double-entry postings, its stock movements,
 * its allocations, an audit row and a sync-outbox row. Either everything is
 * written or nothing is.
 *
 * Nothing is ever deleted: [void] marks a document VOID (with reason, user
 * and time) and switches its postings / movements / allocations off.
 */
class BooksEngine(
    private val db: BooksDatabase,
    private val ctx: BooksContext,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.dao()
    private val biz get() = ctx.businessId

    // =====================================================================
    // Sales and purchases
    // =====================================================================

    /** Everything a sale will be, by the posting rules, without writing anything. */
    private class DocPlan(
        val business: BusinessEntity,
        val party: PartyEntity?,
        val lines: List<Line>,
        val placeOfSupply: String?,
        val interState: Boolean,
        val totals: com.shopai.app.books.tax.InvoiceTotals,
    )

    private suspend fun planSale(input: SaleInput): DocPlan {
        val business = business()
        checkDate(input.date, business)
        input.dueDate?.let { if (it.isBefore(input.date)) reject(BooksErrorCode.INVALID_DATE, "Due date is before the invoice date", "dueDate") }
        val party = input.partyId?.let { partyOf(it, PartyKind.CUSTOMER) }
        val lines = resolveItems(input.items, business, forSale = true)
        val pos = input.placeOfSupply ?: party?.let(::stateOf)
        val interState = GstCalculator.isInterState(business.stateCode, pos)
        val totals = invoiceTotals(lines, interState, input.roundOff ?: business.roundOff)
        lines.forEachIndexed { i, l -> checkMinPrice(l, totals.lines[i], i) }

        val receivedTotal = checkParts(input.received)
        input.received.forEachIndexed { i, p -> if (moneyAccountOrNull(p.moneyAccountId) == null) reject(BooksErrorCode.MONEY_ACCOUNT_NOT_FOUND, "Choose a cash / bank account", "received[$i]") }
        if (receivedTotal > totals.grandTotalPaise) {
            reject(BooksErrorCode.OVERPAYMENT, "Received ₹${Money.toRupees(receivedTotal)} is more than the bill ₹${Money.toRupees(totals.grandTotalPaise)}", "received")
        }
        if (party == null && receivedTotal != totals.grandTotalPaise) {
            reject(BooksErrorCode.PARTY_REQUIRED, "Select the customer for a credit or part-paid sale", "partyId")
        }
        input.number?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (dao.txnByNumber(biz, TxnType.SALE.name, "", it) != null) reject(BooksErrorCode.DUPLICATE_NUMBER, "Invoice number $it is already used", "number")
        }
        // Stock is checked here too, so the review shows a shortage before anything is saved.
        val requested = HashMap<String, Long>()
        lines.forEach { l ->
            if (!tracksStock(l, business)) return@forEach
            val product = l.product!!
            val batchId = l.batchNo?.let { batchOf(product, it).id }
            val key = "${product.id}|${batchId.orEmpty()}"
            requested[key] = (requested[key] ?: 0) + l.baseQtyMilli
            checkAvailable(product, batchId, requested[key]!!, business, "items[${l.index}]")
        }
        return DocPlan(business, party, lines, pos, interState, totals)
    }

    /** The invoice as it will be posted (totals, tax, number) — or why it cannot be. Writes nothing. */
    suspend fun quoteSale(input: SaleInput): Quote = quote(input.number?.trim()?.takeIf { it.isNotEmpty() } ?: peekNumber(TxnType.SALE)) { planSale(input) }

    suspend fun postSale(input: SaleInput): PostResult = post(input.meta) {
        val plan = planSale(input)
        val business = plan.business
        val party = plan.party
        val lines = plan.lines
        val pos = plan.placeOfSupply
        val interState = plan.interState
        val totals = plan.totals
        val number = input.number?.trim()?.takeIf { it.isNotEmpty() } ?: nextNumber(TxnType.SALE)

        val txn = newTxn(
            type = TxnType.SALE, number = number, date = input.date, meta = input.meta,
            party = party, placeOfSupply = pos, interState = interState, dueDate = input.dueDate,
        ).withTotals(totals)
        dao.insertTxn(txn)
        dao.insertItems(itemRows(txn, lines, totals.lines))

        val ledger = Ledger(txn)
        ledger.dr(Account.RECEIVABLE, totals.grandTotalPaise, partyId = party?.id)
        ledger.cr(Account.SALES, totals.taxablePaise)
        taxPostings(ledger, totals.cgstPaise, totals.sgstPaise, totals.igstPaise, totals.cessPaise, Account.OUTPUT_TAX, credit = true)
        ledger.cr(Account.ROUND_OFF, totals.roundOffPaise)
        ledger.write()

        val requested = HashMap<String, Long>()
        val movements = lines.mapNotNull { l ->
            if (!tracksStock(l, business)) return@mapNotNull null
            val product = l.product!!
            val batchId = l.batchNo?.let { batchOf(product, it).id }
            val key = "${product.id}|${batchId.orEmpty()}"
            requested[key] = (requested[key] ?: 0) + l.baseQtyMilli
            checkAvailable(product, batchId, requested[key]!!, business, "items[${l.index}]")
            movement(txn, l, MovementType.SALE, -l.baseQtyMilli, -costOut(product, l.baseQtyMilli), batchId, reason = null)
        }
        if (movements.isNotEmpty()) dao.insertMovements(movements)

        input.received.forEachIndexed { i, part -> childPayment(txn, part, i, TxnType.PAYMENT_IN, party) }
        finish(txn, "CREATE")
    }

    private suspend fun planPurchase(input: PurchaseInput): DocPlan {
        val business = business()
        checkDate(input.date, business)
        input.dueDate?.let { if (it.isBefore(input.date)) reject(BooksErrorCode.INVALID_DATE, "Due date is before the bill date", "dueDate") }
        val party = partyOf(input.partyId, PartyKind.SUPPLIER)
        val supplierBillNo = input.billNumber?.trim().orEmpty()
        if (supplierBillNo.isNotEmpty() && dao.txnByNumber(biz, TxnType.PURCHASE.name, party.id, supplierBillNo) != null) {
            reject(BooksErrorCode.DUPLICATE_NUMBER, "Bill $supplierBillNo from ${party.name} is already entered", "billNumber")
        }
        val lines = resolveItems(input.items, business, forSale = false)
        val pos = input.placeOfSupply ?: stateOf(party)
        val interState = GstCalculator.isInterState(business.stateCode, pos)
        val totals = invoiceTotals(lines, interState, input.roundOff ?: business.roundOff)
        val paidTotal = checkParts(input.paid)
        input.paid.forEachIndexed { i, p -> if (moneyAccountOrNull(p.moneyAccountId) == null) reject(BooksErrorCode.MONEY_ACCOUNT_NOT_FOUND, "Choose a cash / bank account", "paid[$i]") }
        if (paidTotal > totals.grandTotalPaise) {
            reject(BooksErrorCode.OVERPAYMENT, "Paid ₹${Money.toRupees(paidTotal)} is more than the bill ₹${Money.toRupees(totals.grandTotalPaise)}", "paid")
        }
        return DocPlan(business, party, lines, pos, interState, totals)
    }

    suspend fun quotePurchase(input: PurchaseInput): Quote =
        quote(input.billNumber?.trim()?.takeIf { it.isNotEmpty() } ?: peekNumber(TxnType.PURCHASE)) { planPurchase(input) }

    suspend fun postPurchase(input: PurchaseInput): PostResult = post(input.meta) {
        val plan = planPurchase(input)
        val business = plan.business
        val party = plan.party!!
        val lines = plan.lines
        val pos = plan.placeOfSupply
        val interState = plan.interState
        val totals = plan.totals
        // No supplier bill number (amount-only entry): an internal number in the business series.
        val supplierBillNo = input.billNumber?.trim().orEmpty()
        val (billNo, billScope) = if (supplierBillNo.isNotEmpty()) supplierBillNo to party.id else nextNumber(TxnType.PURCHASE) to ""

        val txn = newTxn(
            type = TxnType.PURCHASE, number = billNo, numberScope = billScope, date = input.date, meta = input.meta,
            party = party, placeOfSupply = pos, interState = interState, dueDate = input.dueDate,
        ).withTotals(totals)
        dao.insertTxn(txn)
        dao.insertItems(itemRows(txn, lines, totals.lines))

        val ledger = Ledger(txn)
        ledger.dr(Account.PURCHASES, totals.taxablePaise)
        taxPostings(ledger, totals.cgstPaise, totals.sgstPaise, totals.igstPaise, totals.cessPaise, Account.INPUT_TAX, credit = false)
        ledger.dr(Account.ROUND_OFF, totals.roundOffPaise)
        ledger.cr(Account.PAYABLE, totals.grandTotalPaise, partyId = party.id)
        ledger.write()

        val movements = lines.mapIndexedNotNull { i, l ->
            if (!tracksStock(l, business)) return@mapIndexedNotNull null
            val batchId = l.batchNo?.let { receiveBatch(l.product!!, it, l.mfg, l.expiry).id }
            movement(txn, l, MovementType.PURCHASE, l.baseQtyMilli, totals.lines[i].taxablePaise, batchId, reason = null)
        }
        if (movements.isNotEmpty()) dao.insertMovements(movements)

        input.paid.forEachIndexed { i, part -> childPayment(txn, part, i, TxnType.PAYMENT_OUT, party) }
        finish(txn, "CREATE")
    }

    // =====================================================================
    // Returns (credit note / debit note)
    // =====================================================================

    suspend fun postSaleReturn(input: ReturnInput): PostResult = post(input.meta) { postReturn(input, TxnType.SALE, TxnType.SALE_RETURN) }

    suspend fun postPurchaseReturn(input: ReturnInput): PostResult = post(input.meta) { postReturn(input, TxnType.PURCHASE, TxnType.PURCHASE_RETURN) }

    private class ReturnPlan(
        val business: BusinessEntity,
        val original: TxnEntity,
        val party: PartyEntity?,
        val rows: List<TxnItemEntity>,
        val raw: Long,
        val grand: Long,
    ) {
        val taxable get() = rows.sumOf { it.taxablePaise }
        val cgst get() = rows.sumOf { it.cgstPaise }
        val sgst get() = rows.sumOf { it.sgstPaise }
        val igst get() = rows.sumOf { it.igstPaise }
        val cess get() = rows.sumOf { it.cessPaise }
    }

    /** The credit / debit note as it will be posted, without writing anything. */
    suspend fun quoteReturn(input: ReturnInput, sale: Boolean): Quote {
        val returnType = if (sale) TxnType.SALE_RETURN else TxnType.PURCHASE_RETURN
        return try {
            val p = planReturn(input, if (sale) TxnType.SALE else TxnType.PURCHASE, returnType)
            val results = p.rows.map { TaxLineResult(it.grossPaise, it.discountPaise, it.taxablePaise, it.cgstPaise, it.sgstPaise, it.igstPaise, it.cessPaise) }
            Quote(
                totals = com.shopai.app.books.tax.InvoiceTotals(
                    results, p.rows.sumOf { it.grossPaise }, p.rows.sumOf { it.discountPaise }, p.taxable,
                    p.cgst, p.sgst, p.igst, p.cess, p.grand - p.raw, p.grand,
                ),
                lines = p.rows.mapIndexed { i, r -> QuoteLine(r.itemName, r.hsnCode, r.qtyMilli, r.unit, r.ratePaise, r.gstBp, r.taxType, results[i]) },
                interState = p.original.interState, placeOfSupply = p.original.placeOfSupply,
                number = peekNumber(returnType), errors = emptyList(),
            )
        } catch (e: RejectException) {
            Quote(null, emptyList(), false, null, null, e.errors)
        }
    }

    private suspend fun planReturn(input: ReturnInput, originalType: TxnType, returnType: TxnType): ReturnPlan {
        val business = business()
        checkDate(input.date, business)
        if (input.reason.isBlank()) reject(BooksErrorCode.REASON_REQUIRED, "Enter the reason for the return", "reason")
        val original = dao.txn(input.originalTxnId)?.takeIf { it.businessId == biz && it.type == originalType.name }
            ?: reject(BooksErrorCode.NOT_FOUND, "Original ${if (originalType == TxnType.SALE) "invoice" else "bill"} not found", "originalTxnId")
        if (original.status != TxnStatus.CONFIRMED.name) reject(BooksErrorCode.ALREADY_VOID, "${original.number} is void")
        if (input.date.toEpochDay() < original.date) reject(BooksErrorCode.INVALID_DATE, "Return date is before ${original.number}", "date")
        if (input.lines.isEmpty()) reject(BooksErrorCode.NO_ITEMS, "Select the items being returned", "lines")
        if (input.lines.map { it.lineNo }.toSet().size != input.lines.size) reject(BooksErrorCode.RETURN_INVALID, "A line is listed twice", "lines")

        val originalItems = dao.items(original.id).associateBy { it.lineNo }
        val returned = dao.returnedItems(original.id, returnType.name).groupBy { it.sourceLineNo }
        val party = original.partyId?.let { dao.party(it) }

        val rows = input.lines.mapIndexed { i, rl ->
            val src = originalItems[rl.lineNo] ?: reject(BooksErrorCode.RETURN_INVALID, "Line ${rl.lineNo} is not on ${original.number}", "lines[$i]")
            if (rl.qtyMilli <= 0) reject(BooksErrorCode.INVALID_QUANTITY, "Return quantity must be more than zero", "lines[$i]")
            val before = returned[rl.lineNo].orEmpty()
            val remaining = src.qtyMilli - before.sumOf { it.qtyMilli }
            if (rl.qtyMilli > remaining) {
                reject(BooksErrorCode.RETURN_INVALID, "Only ${com.shopai.app.books.model.Qty.toDecimal(remaining).toPlainString()} ${src.unit} of ${src.itemName} can be returned", "lines[$i]")
            }
            // The last of a line returns exactly what is left, so partial returns never drift by a paisa.
            fun part(total: Long, already: Long): Long =
                if (rl.qtyMilli == remaining) total - already else prorate(total, rl.qtyMilli, src.qtyMilli)
            src.copy(
                txnId = "", lineNo = i + 1, txnType = returnType.name, date = input.date.toEpochDay().toInt(), active = true,
                qtyMilli = rl.qtyMilli,
                baseQtyMilli = part(src.baseQtyMilli, before.sumOf { it.baseQtyMilli }),
                grossPaise = part(src.grossPaise, before.sumOf { it.grossPaise }),
                discountPaise = part(src.discountPaise, before.sumOf { it.discountPaise }),
                taxablePaise = part(src.taxablePaise, before.sumOf { it.taxablePaise }),
                cgstPaise = part(src.cgstPaise, before.sumOf { it.cgstPaise }),
                sgstPaise = part(src.sgstPaise, before.sumOf { it.sgstPaise }),
                igstPaise = part(src.igstPaise, before.sumOf { it.igstPaise }),
                cessPaise = part(src.cessPaise, before.sumOf { it.cessPaise }),
                totalPaise = part(src.totalPaise, before.sumOf { it.totalPaise }),
                sourceLineNo = rl.lineNo,
            )
        }

        val raw = rows.sumOf { it.taxablePaise + it.cgstPaise + it.sgstPaise + it.igstPaise + it.cessPaise }
        val grand = if (business.roundOff) Money.roundToRupee(raw) else raw
        // Goods going back to a supplier must be in stock.
        if (returnType == TxnType.PURCHASE_RETURN) {
            val requested = HashMap<String, Long>()
            rows.forEach { row ->
                val productId = row.productId ?: return@forEach
                val src = dao.movement(original.id, row.sourceLineNo!!) ?: return@forEach
                val product = dao.product(productId) ?: return@forEach
                val key = "${product.id}|${src.batchId.orEmpty()}"
                requested[key] = (requested[key] ?: 0) + row.baseQtyMilli
                checkAvailable(product, src.batchId, requested[key]!!, business, "lines[${row.lineNo - 1}]")
            }
        }
        return ReturnPlan(business, original, party, rows, raw, grand)
    }

    private suspend fun postReturn(input: ReturnInput, originalType: TxnType, returnType: TxnType): TxnEntity {
        val plan = planReturn(input, originalType, returnType)
        val business = plan.business
        val original = plan.original
        val party = plan.party
        val rows = plan.rows
        val taxable = plan.taxable
        val cgst = plan.cgst
        val sgst = plan.sgst
        val igst = plan.igst
        val cess = plan.cess
        val raw = plan.raw
        val grand = plan.grand

        val txn = newTxn(
            type = returnType, number = nextNumber(returnType), date = input.date,
            meta = input.meta.copy(notes = listOfNotNull(input.reason.trim(), input.meta.notes).joinToString(" — ")),
            party = party, placeOfSupply = original.placeOfSupply, interState = original.interState, linkedTxnId = original.id,
        ).copy(
            grossPaise = rows.sumOf { it.grossPaise }, discountPaise = rows.sumOf { it.discountPaise }, taxablePaise = taxable,
            cgstPaise = cgst, sgstPaise = sgst, igstPaise = igst, cessPaise = cess, roundOffPaise = grand - raw, totalPaise = grand,
        )
        dao.insertTxn(txn)
        dao.insertItems(rows.map { it.copy(txnId = txn.id) })

        val ledger = Ledger(txn)
        if (returnType == TxnType.SALE_RETURN) {
            ledger.dr(Account.SALES_RETURN, taxable)
            taxPostings(ledger, cgst, sgst, igst, cess, Account.OUTPUT_TAX, credit = false)
            ledger.dr(Account.ROUND_OFF, grand - raw)
            ledger.cr(Account.RECEIVABLE, grand, partyId = original.partyId)
        } else {
            ledger.dr(Account.PAYABLE, grand, partyId = original.partyId)
            ledger.cr(Account.PURCHASE_RETURN, taxable)
            taxPostings(ledger, cgst, sgst, igst, cess, Account.INPUT_TAX, credit = true)
            ledger.cr(Account.ROUND_OFF, grand - raw)
        }
        ledger.write()

        val requested = HashMap<String, Long>()
        val movements = rows.mapNotNull { row ->
            val productId = row.productId ?: return@mapNotNull null
            val src = dao.movement(original.id, row.sourceLineNo!!) ?: return@mapNotNull null
            val product = dao.product(productId) ?: return@mapNotNull null
            // Goes back at the cost it left with (sale) / came in at (purchase), opposite sign.
            val value = -prorate(src.valuePaise, row.baseQtyMilli, kotlin.math.abs(src.qtyMilli))
            if (returnType == TxnType.SALE_RETURN) {
                stockRow(txn, row.lineNo, product, MovementType.SALE_RETURN, row.baseQtyMilli, value, src.batchId, input.reason)
            } else {
                val key = "${product.id}|${src.batchId.orEmpty()}"
                requested[key] = (requested[key] ?: 0) + row.baseQtyMilli
                checkAvailable(product, src.batchId, requested[key]!!, business, "lines[${row.lineNo - 1}]")
                stockRow(txn, row.lineNo, product, MovementType.PURCHASE_RETURN, -row.baseQtyMilli, value, src.batchId, input.reason)
            }
        }
        if (movements.isNotEmpty()) dao.insertMovements(movements)

        // The note first settles what is still due on the original document.
        val due = original.totalPaise - dao.allocatedTo(original.id)
        val apply = minOf(due, grand)
        if (apply > 0) dao.insertAllocations(listOf(AllocationEntity(businessId = biz, fromTxnId = txn.id, toTxnId = original.id, amountPaise = apply, active = true, createdAt = now())))
        return finish(txn, "CREATE")
    }

    // =====================================================================
    // Payments
    // =====================================================================

    suspend fun postPaymentIn(input: PaymentInput): PostResult = post(input.meta) { postPayment(input, TxnType.PAYMENT_IN) }

    suspend fun postPaymentOut(input: PaymentInput): PostResult = post(input.meta) { postPayment(input, TxnType.PAYMENT_OUT) }

    /** Where a payment will go — which documents it settles and what is left as advance. Writes nothing. */
    suspend fun quotePayment(input: PaymentInput, incoming: Boolean): PaymentQuote = try {
        val p = planPayment(input, if (incoming) TxnType.PAYMENT_IN else TxnType.PAYMENT_OUT)
        PaymentQuote(p.allocations, p.excess, emptyList())
    } catch (e: RejectException) {
        PaymentQuote(emptyList(), 0, e.errors)
    }

    private class PaymentPlan(
        val party: PartyEntity,
        val account: com.shopai.app.books.data.MoneyAccountEntity,
        val allocations: List<AllocationPreview>,
        val excess: Long,
    )

    private suspend fun planPayment(input: PaymentInput, type: TxnType): PaymentPlan {
        val business = business()
        checkDate(input.date, business)
        val incoming = type == TxnType.PAYMENT_IN
        val party = partyOf(input.partyId, if (incoming) PartyKind.CUSTOMER else PartyKind.SUPPLIER)
        if (input.amountPaise <= 0) reject(BooksErrorCode.INVALID_AMOUNT, "Amount must be more than zero", "amountPaise")
        if (input.mode == PaymentMode.CREDIT) reject(BooksErrorCode.INVALID_AMOUNT, "Choose how the money was ${if (incoming) "received" else "paid"}", "mode")
        val account = moneyAccountOf(input.moneyAccountId)
        if (!input.allowSameDayDuplicate &&
            dao.samePayments(biz, party.id, type.name, day(input.date), input.amountPaise, input.mode.name, input.reference?.trim().orEmpty()) > 0
        ) {
            reject(BooksErrorCode.DUPLICATE_PAYMENT, "The same ₹${Money.toRupees(input.amountPaise)} payment for ${party.name} is already saved today", "amountPaise")
        }

        val docTypes = if (incoming) listOf(TxnType.SALE.name, TxnType.OPENING_BALANCE.name) else listOf(TxnType.PURCHASE.name, TxnType.OPENING_BALANCE.name)
        val open = dao.openDocs(biz, party.id, docTypes)
        if (input.allocations != null && input.allocations.map { it.first }.toSet().size != input.allocations.size) {
            reject(BooksErrorCode.ALLOCATION_INVALID, "A document is listed twice", "allocations")
        }
        val previews: List<AllocationPreview> = if (input.allocations != null) {
            val byId = open.associateBy { it.id }
            input.allocations.mapIndexed { i, (docId, amount) ->
                val doc = byId[docId] ?: reject(BooksErrorCode.ALLOCATION_INVALID, "That document has nothing due for ${party.name}", "allocations[$i]")
                if (amount <= 0 || amount > doc.outstandingPaise) {
                    reject(BooksErrorCode.ALLOCATION_INVALID, "${doc.number} has only ₹${Money.toRupees(doc.outstandingPaise)} due", "allocations[$i]")
                }
                AllocationPreview(doc, amount)
            }
        } else {
            var left = input.amountPaise
            open.mapNotNull { doc ->
                if (left == 0L) return@mapNotNull null
                val take = minOf(left, doc.outstandingPaise)
                left -= take
                AllocationPreview(doc, take)
            }
        }
        val allocated = previews.sumOf { it.amountPaise }
        if (allocated > input.amountPaise) reject(BooksErrorCode.ALLOCATION_INVALID, "Allocated more than the payment", "allocations")
        val excess = input.amountPaise - allocated
        if (excess > 0 && !input.keepExcessAsAdvance) {
            reject(
                BooksErrorCode.OVERPAYMENT,
                "₹${Money.toRupees(excess)} is more than what ${party.name} ${if (incoming) "owes" else "is owed"}. Save it as an advance?",
                "amountPaise",
            )
        }
        return PaymentPlan(party, account, previews, excess)
    }

    private suspend fun postPayment(input: PaymentInput, type: TxnType): TxnEntity {
        val p = planPayment(input, type)
        val incoming = type == TxnType.PAYMENT_IN
        val party = p.party
        val account = p.account
        val excess = p.excess
        val plan = p.allocations.map { it.doc.id to it.amountPaise }

        val txn = newTxn(type = type, number = nextNumber(type), date = input.date, meta = input.meta, party = party)
            .copy(totalPaise = input.amountPaise, moneyAccountId = account.id, paymentMode = input.mode.name, reference = input.reference?.trim(), isAdvance = excess > 0)
        dao.insertTxn(txn)
        val ledger = Ledger(txn)
        if (incoming) {
            ledger.dr(Account.MONEY, input.amountPaise, moneyAccountId = account.id)
            ledger.cr(Account.RECEIVABLE, input.amountPaise, partyId = party.id)
        } else {
            ledger.dr(Account.PAYABLE, input.amountPaise, partyId = party.id)
            ledger.cr(Account.MONEY, input.amountPaise, moneyAccountId = account.id)
        }
        ledger.write()
        if (plan.isNotEmpty()) {
            dao.insertAllocations(plan.map { (docId, amount) -> AllocationEntity(businessId = biz, fromTxnId = txn.id, toTxnId = docId, amountPaise = amount, active = true, createdAt = now()) })
        }
        return finish(txn, "CREATE")
    }

    /** Applies an advance / unused credit note / advance opening to a document. */
    suspend fun applyCredit(fromTxnId: String, toTxnId: String, amountPaise: Long): PostResult = try {
        db.withTransaction {
            val from = dao.txn(fromTxnId)?.takeIf { it.businessId == biz && it.status == TxnStatus.CONFIRMED.name }
                ?: reject(BooksErrorCode.NOT_FOUND, "Credit not found")
            val to = dao.txn(toTxnId)?.takeIf { it.businessId == biz && it.status == TxnStatus.CONFIRMED.name }
                ?: reject(BooksErrorCode.NOT_FOUND, "Document not found")
            if (from.partyId == null || from.partyId != to.partyId) reject(BooksErrorCode.ALLOCATION_INVALID, "Both must be for the same party")
            val customerSide = setOf(TxnType.PAYMENT_IN.name, TxnType.SALE_RETURN.name)
            val supplierSide = setOf(TxnType.PAYMENT_OUT.name, TxnType.PURCHASE_RETURN.name)
            val kind = dao.party(from.partyId)!!.kind
            val fromOk = from.type in (if (kind == PartyKind.CUSTOMER.name) customerSide else supplierSide) ||
                (from.type == TxnType.OPENING_BALANCE.name && from.isAdvance)
            val toOk = to.type == (if (kind == PartyKind.CUSTOMER.name) TxnType.SALE.name else TxnType.PURCHASE.name) ||
                (to.type == TxnType.OPENING_BALANCE.name && !to.isAdvance)
            if (!fromOk || !toOk) reject(BooksErrorCode.ALLOCATION_INVALID, "This credit cannot be applied to that document")
            val unapplied = from.totalPaise - dao.allocatedFrom(from.id)
            val due = to.totalPaise - dao.allocatedTo(to.id)
            if (amountPaise <= 0 || amountPaise > unapplied || amountPaise > due) {
                reject(BooksErrorCode.ALLOCATION_INVALID, "Only ₹${Money.toRupees(minOf(unapplied, due))} can be applied")
            }
            dao.insertAllocations(listOf(AllocationEntity(businessId = biz, fromTxnId = from.id, toTxnId = to.id, amountPaise = amountPaise, active = true, createdAt = now())))
            audit(from, "APPLY", reason = null, summary = "${from.number} → ${to.number} ₹${Money.toRupees(amountPaise)}")
            outbox(from.id)
            PostResult.Posted(from)
        }
    } catch (e: RejectException) {
        PostResult.Rejected(e.errors)
    }

    // =====================================================================
    // Cash in / out, transfers, openings
    // =====================================================================

    suspend fun postCashIn(input: CashInInput): PostResult = post(input.meta) {
        val business = business()
        checkDate(input.date, business)
        if (input.amountPaise <= 0) reject(BooksErrorCode.INVALID_AMOUNT, "Amount must be more than zero", "amountPaise")
        val account = moneyAccountOf(input.moneyAccountId)
        val party = input.partyId?.let { dao.party(it)?.takeIf { p -> p.businessId == biz } ?: reject(BooksErrorCode.PARTY_NOT_FOUND, "Party not found", "partyId") }
        val txn = newTxn(type = TxnType.CASH_IN, number = nextNumber(TxnType.CASH_IN), date = input.date, meta = input.meta, party = party)
            .copy(totalPaise = input.amountPaise, moneyAccountId = account.id, paymentMode = input.mode.name, category = input.source.name, reference = input.reference?.trim())
        dao.insertTxn(txn)
        val ledger = Ledger(txn)
        ledger.dr(Account.MONEY, input.amountPaise, moneyAccountId = account.id)
        if (input.source == CashInSource.OWNER_CAPITAL) ledger.cr(Account.CAPITAL, input.amountPaise)
        else ledger.cr(Account.INCOME, input.amountPaise, category = input.source.name)
        ledger.write()
        finish(txn, "CREATE")
    }

    suspend fun postCashOut(input: CashOutInput): PostResult = post(input.meta) {
        val business = business()
        checkDate(input.date, business)
        if (input.amountPaise <= 0) reject(BooksErrorCode.INVALID_AMOUNT, "Amount must be more than zero", "amountPaise")
        val category = input.category.trim().ifEmpty { reject(BooksErrorCode.REASON_REQUIRED, "Choose an expense category", "category") }
        val account = moneyAccountOf(input.moneyAccountId)
        val party = input.partyId?.let { dao.party(it)?.takeIf { p -> p.businessId == biz } ?: reject(BooksErrorCode.PARTY_NOT_FOUND, "Party not found", "partyId") }
        val txn = newTxn(type = TxnType.CASH_OUT, number = nextNumber(TxnType.CASH_OUT), date = input.date, meta = input.meta, party = party)
            .copy(totalPaise = input.amountPaise, moneyAccountId = account.id, paymentMode = input.mode.name, category = category, reference = input.reference?.trim())
        dao.insertTxn(txn)
        val ledger = Ledger(txn)
        ledger.dr(Account.EXPENSE, input.amountPaise, category = category)
        ledger.cr(Account.MONEY, input.amountPaise, moneyAccountId = account.id)
        ledger.write()
        finish(txn, "CREATE")
    }

    suspend fun postTransfer(input: TransferInput): PostResult = post(input.meta) {
        val business = business()
        checkDate(input.date, business)
        if (input.amountPaise <= 0) reject(BooksErrorCode.INVALID_AMOUNT, "Amount must be more than zero", "amountPaise")
        if (input.fromAccountId == input.toAccountId) reject(BooksErrorCode.INVALID_AMOUNT, "Choose two different accounts", "toAccountId")
        val from = moneyAccountOf(input.fromAccountId)
        val to = moneyAccountOf(input.toAccountId)
        val txn = newTxn(type = TxnType.MONEY_TRANSFER, number = nextNumber(TxnType.MONEY_TRANSFER), date = input.date, meta = input.meta)
            .copy(totalPaise = input.amountPaise, moneyAccountId = from.id, category = to.id, reference = input.reference?.trim())
        dao.insertTxn(txn)
        val ledger = Ledger(txn)
        ledger.dr(Account.MONEY, input.amountPaise, moneyAccountId = to.id)
        ledger.cr(Account.MONEY, input.amountPaise, moneyAccountId = from.id)
        ledger.write()
        finish(txn, "CREATE")
    }

    suspend fun postPartyOpening(input: PartyOpeningInput): PostResult = post(input.meta) {
        business()
        val party = dao.party(input.partyId)?.takeIf { it.businessId == biz } ?: reject(BooksErrorCode.PARTY_NOT_FOUND, "Party not found", "partyId")
        if (input.amountPaise <= 0) reject(BooksErrorCode.INVALID_AMOUNT, "Opening balance must be more than zero", "amountPaise")
        if (dao.partyOpenings(biz, party.id) > 0) reject(BooksErrorCode.OPENING_EXISTS, "${party.name} already has an opening balance", "partyId")
        val txn = newTxn(type = TxnType.OPENING_BALANCE, number = nextNumber(TxnType.OPENING_BALANCE), date = input.date, meta = input.meta, party = party, dueDate = input.dueDate)
            .copy(totalPaise = input.amountPaise, isAdvance = input.inPartysFavour)
        dao.insertTxn(txn)
        val ledger = Ledger(txn)
        val customer = party.kind == PartyKind.CUSTOMER.name
        val partyAccount = if (customer) Account.RECEIVABLE else Account.PAYABLE
        // Customer owes us / we hold a supplier's advance → debit; the other way round → credit.
        val debitParty = customer != input.inPartysFavour
        if (debitParty) {
            ledger.dr(partyAccount, input.amountPaise, partyId = party.id)
            ledger.cr(Account.OPENING_EQUITY, input.amountPaise)
        } else {
            ledger.dr(Account.OPENING_EQUITY, input.amountPaise)
            ledger.cr(partyAccount, input.amountPaise, partyId = party.id)
        }
        ledger.write()
        finish(txn, "CREATE")
    }

    suspend fun postMoneyOpening(input: MoneyOpeningInput): PostResult = post(input.meta) {
        business()
        val account = moneyAccountOf(input.moneyAccountId)
        if (input.amountPaise == 0L) reject(BooksErrorCode.INVALID_AMOUNT, "Enter the opening balance", "amountPaise")
        val txn = newTxn(type = TxnType.OPENING_BALANCE, number = nextNumber(TxnType.OPENING_BALANCE), date = input.date, meta = input.meta)
            .copy(totalPaise = input.amountPaise, moneyAccountId = account.id)
        dao.insertTxn(txn)
        val ledger = Ledger(txn)
        ledger.dr(Account.MONEY, input.amountPaise, moneyAccountId = account.id)
        ledger.cr(Account.OPENING_EQUITY, input.amountPaise)
        ledger.write()
        finish(txn, "CREATE")
    }

    // =====================================================================
    // Stock
    // =====================================================================

    suspend fun postOpeningStock(input: OpeningStockInput): PostResult = post(input.meta) {
        val business = business()
        val product = stockProductOf(input.productId, business)
        if (input.qtyMilli <= 0) reject(BooksErrorCode.INVALID_QUANTITY, "Opening stock must be more than zero", "qtyMilli")
        val value = input.valuePaise ?: Money.times(product.purchasePricePaise ?: 0, input.qtyMilli)
        if (value < 0) reject(BooksErrorCode.INVALID_PRICE, "Opening stock value cannot be negative", "valuePaise")
        val batchId = batchFor(product, business, input.batchNo, "batchNo")?.let { receiveBatch(product, it, input.mfgDate, input.expiryDate).id }
        val txn = newTxn(type = TxnType.OPENING_STOCK, number = nextNumber(TxnType.OPENING_STOCK), date = input.date, meta = input.meta)
            .copy(totalPaise = value)
        dao.insertTxn(txn)
        dao.insertMovements(listOf(stockRow(txn, 1, product, MovementType.OPENING_STOCK, input.qtyMilli, value, batchId, "Opening stock")))
        finish(txn, "CREATE")
    }

    suspend fun postStockAdjustment(input: StockAdjustmentInput): PostResult = post(input.meta) {
        val business = business()
        checkDate(input.date, business)
        val product = stockProductOf(input.productId, business)
        if (input.reason == com.shopai.app.books.model.AdjustmentReason.OTHER && input.note.isNullOrBlank()) {
            reject(BooksErrorCode.REASON_REQUIRED, "Write why the stock is being changed", "note")
        }
        val batchId = batchFor(product, business, input.batchNo, "batchNo")?.let { batchOf(product, it).id }
        val change = if (input.countedQtyMilli != null) {
            if (input.countedQtyMilli < 0) reject(BooksErrorCode.INVALID_QUANTITY, "Counted stock cannot be negative", "countedQtyMilli")
            val books = if (batchId != null) dao.batchStock(biz, product.id, batchId).qtyMilli else dao.stock(biz, product.id, null).qtyMilli
            input.countedQtyMilli - books
        } else {
            input.changeQtyMilli
        }
        if (change == 0L) reject(BooksErrorCode.INVALID_QUANTITY, "Nothing to adjust — the stock already matches", "changeQtyMilli")
        val value = if (change > 0) costIn(product, change) else -costOut(product, -change)
        if (change < 0) checkAvailable(product, batchId, -change, business, "changeQtyMilli")
        val txn = newTxn(type = TxnType.STOCK_ADJUSTMENT, number = nextNumber(TxnType.STOCK_ADJUSTMENT), date = input.date, meta = input.meta)
            .copy(totalPaise = kotlin.math.abs(value), category = input.reason.name, notes = input.note?.trim())
        dao.insertTxn(txn)
        val type = if (change > 0) MovementType.ADJUSTMENT_IN else MovementType.ADJUSTMENT_OUT
        dao.insertMovements(listOf(stockRow(txn, 1, product, type, change, value, batchId, listOfNotNull(input.reason.name, input.note?.trim()).joinToString(": "))))
        finish(txn, "CREATE")
    }

    // =====================================================================
    // Void (soft delete with audit)
    // =====================================================================

    suspend fun void(txnId: String, reason: String): PostResult = try {
        db.withTransaction {
            val business = business()
            if (reason.isBlank()) reject(BooksErrorCode.REASON_REQUIRED, "Enter why this is being cancelled", "reason")
            val txn = dao.txn(txnId)?.takeIf { it.businessId == biz } ?: reject(BooksErrorCode.NOT_FOUND, "Not found")
            if (txn.status == TxnStatus.VOID.name) reject(BooksErrorCode.ALREADY_VOID, "${txn.number} is already cancelled")

            val children = dao.linkedTxns(txn.id)
            val ownPayments = children.filter { it.type == TxnType.PAYMENT_IN.name || it.type == TxnType.PAYMENT_OUT.name }
            val returns = children.filter { it.type == TxnType.SALE_RETURN.name || it.type == TxnType.PURCHASE_RETURN.name }
            if (returns.isNotEmpty()) reject(BooksErrorCode.HAS_DEPENDENTS, "Cancel the return ${returns.first().number} first")
            val ownIds = ownPayments.map { it.id }.toSet()
            val otherAllocations = dao.allocationsTo(txn.id).filter { it.fromTxnId !in ownIds }
            if (otherAllocations.isNotEmpty()) {
                val by = dao.txn(otherAllocations.first().fromTxnId)?.number.orEmpty()
                reject(BooksErrorCode.HAS_DEPENDENTS, "${txn.number} has a payment/credit applied ($by). Cancel or move it first")
            }
            // Taking stock back out must not push it below zero.
            if (!business.allowNegativeStock) {
                dao.movements(txn.id).filter { it.active && it.qtyMilli > 0 }.groupBy { it.productId to it.batchId }.forEach { (key, rows) ->
                    val (productId, batchId) = key
                    val onHand = if (batchId != null) dao.batchStock(biz, productId, batchId).qtyMilli else dao.stock(biz, productId, null).qtyMilli
                    if (onHand - rows.sumOf { it.qtyMilli } < 0) {
                        val name = dao.product(productId)?.name.orEmpty()
                        reject(BooksErrorCode.INSUFFICIENT_STOCK, "Cancelling would make $name stock negative")
                    }
                }
            }
            ownPayments.forEach { markVoid(it, "With ${txn.number}: $reason") }
            val voided = markVoid(txn, reason)
            PostResult.Posted(voided)
        }
    } catch (e: RejectException) {
        PostResult.Rejected(e.errors)
    }

    private suspend fun markVoid(txn: TxnEntity, reason: String): TxnEntity {
        val t = now()
        val voided = txn.copy(status = TxnStatus.VOID.name, voidReason = reason.trim(), voidedBy = ctx.userId, voidedAt = t, updatedBy = ctx.userId, updatedAt = t)
        dao.updateTxn(voided)
        dao.deactivatePostings(txn.id)
        dao.deactivateMovements(txn.id)
        dao.deactivateItems(txn.id)
        dao.deactivateAllocationsFrom(txn.id)
        audit(voided, "VOID", reason.trim(), summary(voided))
        outbox(txn.id)
        return voided
    }

    // =====================================================================
    // Drafts (voice / OCR / typed) — reviewed before anything is posted
    // =====================================================================

    suspend fun saveDraft(kind: String, source: TxnSource, payloadJson: String, rawInput: String?): String {
        val id = newId()
        val t = now()
        dao.upsertDraft(com.shopai.app.books.data.DraftEntity(id, biz, kind, source.name, payloadJson, rawInput, DraftStatus.OPEN.name, t, t))
        return id
    }

    suspend fun discardDraft(draftId: String) {
        val d = dao.draft(draftId) ?: return
        if (d.status == DraftStatus.OPEN.name) dao.upsertDraft(d.copy(status = DraftStatus.DISCARDED.name, updatedAt = now()))
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private suspend fun post(meta: PostMeta, block: suspend () -> TxnEntity): PostResult = try {
        db.withTransaction {
            val existing = dao.txnByClientKey(meta.clientKey)
            if (existing != null) return@withTransaction PostResult.Posted(existing, duplicate = true)
            // Voice / scanned entries must come from a reviewed draft; any post naming a draft
            // (bills too) must find it still open, and confirming it closes it.
            val draftRequired = meta.source == TxnSource.VOICE || meta.source == TxnSource.OCR
            val draft = when {
                meta.draftId != null -> dao.draft(meta.draftId)?.takeIf { it.businessId == biz && it.status == DraftStatus.OPEN.name }
                    ?: reject(BooksErrorCode.DRAFT_REQUIRED, "This entry was already saved or discarded")
                draftRequired -> reject(BooksErrorCode.DRAFT_REQUIRED, "Voice and scanned entries must be reviewed before saving")
                else -> null
            }
            val txn = block()
            draft?.let { dao.upsertDraft(it.copy(status = DraftStatus.CONFIRMED.name, updatedAt = now())) }
            PostResult.Posted(txn)
        }
    } catch (e: RejectException) {
        PostResult.Rejected(e.errors)
    } catch (e: GstException) {
        PostResult.Rejected(listOf(BooksError(BooksErrorCode.INVALID_PRICE, e.message.orEmpty())))
    }

    private suspend fun business(): BusinessEntity =
        dao.business(biz) ?: reject(BooksErrorCode.BUSINESS_NOT_SET_UP, "Set up the business first")

    private fun checkDate(date: LocalDate, business: BusinessEntity) {
        if (date.isAfter(today()) && !business.allowFutureDates) reject(BooksErrorCode.FUTURE_DATE, "Date is in the future", "date")
        if (date.year < 2000) reject(BooksErrorCode.INVALID_DATE, "Check the date", "date")
    }

    private suspend fun partyOf(id: String, kind: PartyKind): PartyEntity {
        val party = dao.party(id)?.takeIf { it.businessId == biz } ?: reject(BooksErrorCode.PARTY_NOT_FOUND, "Party not found", "partyId")
        if (party.kind != kind.name) reject(BooksErrorCode.WRONG_PARTY_KIND, "${party.name} is not a ${kind.name.lowercase()}", "partyId")
        if (!party.active) reject(BooksErrorCode.PARTY_NOT_FOUND, "${party.name} is archived", "partyId")
        return party
    }

    private fun stateOf(party: PartyEntity): String? = party.stateCode ?: party.gstin?.let(Gstin::stateCode)

    private suspend fun moneyAccountOf(id: String) =
        dao.moneyAccount(id)?.takeIf { it.businessId == biz && it.active } ?: reject(BooksErrorCode.MONEY_ACCOUNT_NOT_FOUND, "Choose a cash / bank account", "moneyAccountId")

    private suspend fun stockProductOf(id: String, business: BusinessEntity): ProductEntity {
        val product = dao.product(id)?.takeIf { it.businessId == biz } ?: reject(BooksErrorCode.PRODUCT_NOT_FOUND, "Product not found", "productId")
        if (product.isService || !BusinessType.valueOf(business.businessType).inventory) {
            reject(BooksErrorCode.PRODUCT_INACTIVE, "${product.name} does not keep stock", "productId")
        }
        return product
    }

    private fun checkParts(parts: List<PaymentPart>): Long {
        parts.forEachIndexed { i, p ->
            if (p.amountPaise <= 0) reject(BooksErrorCode.INVALID_AMOUNT, "Payment amount must be more than zero", "payments[$i]")
            if (p.mode == PaymentMode.CREDIT) reject(BooksErrorCode.INVALID_AMOUNT, "Credit is not a payment — leave it as balance due", "payments[$i]")
        }
        return parts.sumOf { it.amountPaise }
    }

    private class Line(
        val index: Int,
        val product: ProductEntity?,
        val name: String,
        val isService: Boolean,
        val unit: String,
        val qtyMilli: Long,
        val baseQtyMilli: Long,
        val tax: TaxLineInput,
        val hsn: String?,
        val hsnVerified: Boolean,
        val batchNo: String?,
        val mfg: LocalDate?,
        val expiry: LocalDate?,
    )

    private suspend fun resolveItems(items: List<ItemInput>, business: BusinessEntity, forSale: Boolean): List<Line> {
        if (items.isEmpty()) reject(BooksErrorCode.NO_ITEMS, "Add at least one item", "items")
        val slabs = business.gstRatesBp.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        return items.mapIndexed { i, item ->
            val field = "items[$i]"
            val product = item.productId?.let { id ->
                dao.product(id)?.takeIf { it.businessId == biz } ?: reject(BooksErrorCode.PRODUCT_NOT_FOUND, "Product not found", field)
            }
            if (product != null && (!product.active || product.archived)) reject(BooksErrorCode.PRODUCT_INACTIVE, "${product.name} is inactive", field)
            val name = product?.name ?: item.name?.trim()?.takeIf { it.isNotEmpty() } ?: reject(BooksErrorCode.NO_ITEMS, "Enter the item name", field)
            if (item.qtyMilli <= 0) reject(BooksErrorCode.INVALID_QUANTITY, "Quantity of $name must be more than zero", field)

            val units = product?.let { ProductUnits(it.primaryUnit, it.secondaryUnit, it.conversionMilli) }
            val unit = (item.unit ?: units?.primary ?: "PCS").trim().uppercase()
            if (units != null && !units.accepts(unit)) reject(BooksErrorCode.INVALID_UNIT, "$name is not sold in $unit", field)
            val baseQty = units?.toPrimary(item.qtyMilli, unit) ?: item.qtyMilli

            val listPrice = product?.let { if (forSale) it.sellingPricePaise else it.purchasePricePaise }
            val rate = item.ratePaise ?: listPrice?.let { p ->
                if (units?.secondary != null && unit.equals(units.secondary, true)) Money.times(p, units.factorMilli!!) else p
            } ?: reject(BooksErrorCode.INVALID_PRICE, "Enter the rate for $name", field)
            if (rate < 0) reject(BooksErrorCode.INVALID_PRICE, "Rate of $name cannot be negative", field)

            val taxType = item.taxType ?: product?.taxType?.let(TaxType::valueOf) ?: TaxType.GST
            val gstBp = if (taxType == TaxType.GST) item.gstBp ?: product?.gstBp ?: 0 else {
                if ((item.gstBp ?: 0) != 0) reject(BooksErrorCode.INVALID_GST, "$name is ${taxType.name.lowercase()} — no GST applies", field)
                0
            }
            if (taxType == TaxType.GST && gstBp !in slabs) reject(BooksErrorCode.INVALID_GST, "${BigDecimal.valueOf(gstBp.toLong(), 2).stripTrailingZeros().toPlainString()}% is not a GST rate set up for this business", field)
            val cessBp = if (taxType == TaxType.GST) item.cessBp ?: product?.cessBp ?: 0 else 0

            val isService = product?.isService ?: true
            val hsn = (item.hsnCode ?: product?.hsnCode)?.let(HsnRules::normalize)?.takeIf { it.isNotEmpty() }
            val hsnVerified = if (hsn == null) false else {
                val kind = if (isService) CodeKind.SAC else CodeKind.HSN
                if (!HsnRules.isWellFormed(hsn, kind)) reject(BooksErrorCode.INVALID_HSN, "${kind.name} $hsn is not in the right format", field)
                val confirmedOnProduct = product != null && product.hsnCode == hsn && (product.hsnVerified || product.hsnConfirmedByUser)
                val inMaster = dao.hsn(hsn, kind.name) != null
                if (!confirmedOnProduct && !inMaster && !item.hsnConfirmed) reject(BooksErrorCode.INVALID_HSN, HsnRules.VERIFICATION_REQUIRED_MESSAGE, field)
                inMaster || (product != null && product.hsnVerified && product.hsnCode == hsn)
            }

            val batchNo = batchFor(product, business, item.batchNo, field)
            Line(
                index = i, product = product, name = name, isService = isService, unit = unit,
                qtyMilli = item.qtyMilli, baseQtyMilli = baseQty,
                tax = TaxLineInput(
                    qtyMilli = item.qtyMilli, ratePaise = rate, gstBp = gstBp, taxType = taxType,
                    discountPaise = item.discountPaise, discountBp = item.discountBp, cessBp = cessBp,
                    priceIncludesTax = item.priceIncludesTax ?: product?.priceIncludesTax ?: false,
                ),
                hsn = hsn, hsnVerified = hsnVerified, batchNo = batchNo, mfg = item.mfgDate, expiry = item.expiryDate,
            )
        }
    }

    /** The batch number to use, required only when this product and business track batches. */
    private fun batchFor(product: ProductEntity?, business: BusinessEntity, batchNo: String?, field: String): String? {
        val tracked = product != null && product.batchTracked && business.batchTracking && !product.isService
        if (!tracked) return null
        return batchNo?.trim()?.takeIf { it.isNotEmpty() } ?: reject(BooksErrorCode.BATCH_REQUIRED, "Choose the batch of ${product!!.name}", field)
    }

    private suspend fun batchOf(product: ProductEntity, batchNo: String): BatchEntity =
        dao.batchByNo(product.id, batchNo) ?: reject(BooksErrorCode.BATCH_NOT_FOUND, "Batch $batchNo of ${product.name} not found")

    private suspend fun receiveBatch(product: ProductEntity, batchNo: String, mfg: LocalDate?, expiry: LocalDate?): BatchEntity {
        dao.batchByNo(product.id, batchNo)?.let { return it }
        if (mfg != null && expiry != null && expiry.isBefore(mfg)) reject(BooksErrorCode.INVALID_DATE, "Expiry is before the manufacturing date", "expiryDate")
        val batch = BatchEntity(newId(), biz, product.id, batchNo, mfg?.let(::day), expiry?.let(::day), now())
        dao.upsertBatch(batch)
        return batch
    }

    private fun invoiceTotals(lines: List<Line>, interState: Boolean, roundOff: Boolean) = try {
        GstCalculator.invoice(lines.map { it.tax }, interState, roundOff)
    } catch (e: GstException) {
        reject(BooksErrorCode.INVALID_PRICE, e.message.orEmpty(), "items")
    }

    private fun checkMinPrice(line: Line, result: TaxLineResult, i: Int) {
        val min = line.product?.minSellingPricePaise ?: return
        // Compare like with like: the net price per primary unit after discount.
        val net = result.grossPaise - result.discountPaise
        val perUnit = BigDecimal.valueOf(net).multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(line.baseQtyMilli), 0, RoundingMode.HALF_UP).toLong()
        if (perUnit < min) reject(BooksErrorCode.BELOW_MIN_SELLING_PRICE, "${line.name} is below its minimum selling price ₹${Money.toRupees(min)}", "items[$i]")
    }

    private fun tracksStock(line: Line, business: BusinessEntity): Boolean =
        line.product != null && !line.isService && BusinessType.valueOf(business.businessType).inventory

    private suspend fun checkAvailable(product: ProductEntity, batchId: String?, needMilli: Long, business: BusinessEntity, field: String) {
        if (business.allowNegativeStock) return
        val onHand = if (batchId != null) dao.batchStock(biz, product.id, batchId).qtyMilli else dao.stock(biz, product.id, null).qtyMilli
        if (needMilli > onHand) {
            reject(
                BooksErrorCode.INSUFFICIENT_STOCK,
                "Only ${com.shopai.app.books.model.Qty.toDecimal(onHand.coerceAtLeast(0)).toPlainString()} ${product.primaryUnit} of ${product.name} in stock",
                field,
            )
        }
    }

    /** Moving-average cost of taking [qtyMilli] out now. */
    private suspend fun costOut(product: ProductEntity, qtyMilli: Long): Long {
        val s = dao.stock(biz, product.id, null)
        return if (s.qtyMilli > 0 && s.valuePaise > 0) prorate(s.valuePaise, qtyMilli, s.qtyMilli)
        else Money.times(product.purchasePricePaise ?: 0, qtyMilli)
    }

    private suspend fun costIn(product: ProductEntity, qtyMilli: Long): Long = costOut(product, qtyMilli)

    private fun prorate(total: Long, part: Long, whole: Long): Long =
        if (whole == 0L) 0 else BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part)).divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValueExact()

    /** Runs a plan read-only and reports it (or why it cannot post). */
    private suspend fun quote(number: String?, plan: suspend () -> DocPlan): Quote = try {
        val p = plan()
        Quote(
            totals = p.totals,
            lines = p.lines.mapIndexed { i, l -> QuoteLine(l.name, l.hsn, l.qtyMilli, l.unit, l.tax.ratePaise, l.tax.gstBp, l.tax.taxType.name, p.totals.lines[i]) },
            interState = p.interState, placeOfSupply = p.placeOfSupply, number = number, errors = emptyList(),
        )
    } catch (e: RejectException) {
        Quote(null, emptyList(), false, null, number, e.errors)
    } catch (e: GstException) {
        Quote(null, emptyList(), false, null, number, listOf(BooksError(BooksErrorCode.INVALID_PRICE, e.message.orEmpty())))
    }

    /** The number the next document of [type] will get — shown on the preview, not used up. */
    suspend fun peekNumber(type: TxnType): String {
        var seq = dao.series(biz, type.name)?.nextSeq ?: 1L
        while (true) {
            val number = "${type.prefix}-${seq.toString().padStart(4, '0')}"
            if (dao.txnByNumber(biz, type.name, "", number) == null) return number
            seq++
        }
    }

    private suspend fun moneyAccountOrNull(id: String) = dao.moneyAccount(id)?.takeIf { it.businessId == biz && it.active }

    private suspend fun nextNumber(type: TxnType): String {
        var seq = dao.series(biz, type.name)?.nextSeq ?: 1L
        while (true) {
            val number = "${type.prefix}-${seq.toString().padStart(4, '0')}"
            if (dao.txnByNumber(biz, type.name, "", number) == null) {
                dao.upsertSeries(NumberSeriesEntity(biz, type.name, seq + 1))
                return number
            }
            seq++
        }
    }

    private fun day(d: LocalDate): Int = d.toEpochDay().toInt()

    private fun newTxn(
        type: TxnType,
        number: String,
        date: LocalDate,
        meta: PostMeta,
        party: PartyEntity? = null,
        numberScope: String = "",
        placeOfSupply: String? = null,
        interState: Boolean = false,
        dueDate: LocalDate? = null,
        linkedTxnId: String? = null,
        clientKey: String = meta.clientKey,
    ): TxnEntity {
        val t = now()
        return TxnEntity(
            id = newId(), businessId = biz, branchId = ctx.branchId, warehouseId = ctx.warehouseId,
            type = type.name, number = number, numberScope = numberScope, date = day(date), dueDate = dueDate?.let(::day),
            partyId = party?.id, partyName = party?.name, partyGstin = party?.gstin,
            placeOfSupply = placeOfSupply, interState = interState, status = TxnStatus.CONFIRMED.name, totalPaise = 0,
            notes = meta.notes?.trim()?.takeIf { it.isNotEmpty() }, linkedTxnId = linkedTxnId, clientKey = clientKey,
            source = meta.source.name, createdBy = ctx.userId, createdAt = t, updatedBy = ctx.userId, updatedAt = t,
        )
    }

    private fun TxnEntity.withTotals(t: com.shopai.app.books.tax.InvoiceTotals) = copy(
        grossPaise = t.grossPaise, discountPaise = t.discountPaise, taxablePaise = t.taxablePaise,
        cgstPaise = t.cgstPaise, sgstPaise = t.sgstPaise, igstPaise = t.igstPaise, cessPaise = t.cessPaise,
        roundOffPaise = t.roundOffPaise, totalPaise = t.grandTotalPaise,
    )

    private fun itemRows(txn: TxnEntity, lines: List<Line>, results: List<TaxLineResult>) = lines.mapIndexed { i, l ->
        val r = results[i]
        TxnItemEntity(
            txnId = txn.id, lineNo = i + 1, businessId = biz, txnType = txn.type, date = txn.date, active = true,
            productId = l.product?.id, itemName = l.name, isService = l.isService, hsnCode = l.hsn, hsnVerified = l.hsnVerified,
            taxType = l.tax.taxType.name, qtyMilli = l.qtyMilli, unit = l.unit, baseQtyMilli = l.baseQtyMilli,
            ratePaise = l.tax.ratePaise, grossPaise = r.grossPaise, discountPaise = r.discountPaise, taxablePaise = r.taxablePaise,
            gstBp = l.tax.gstBp, cgstPaise = r.cgstPaise, sgstPaise = r.sgstPaise, igstPaise = r.igstPaise,
            cessBp = l.tax.cessBp, cessPaise = r.cessPaise, totalPaise = r.totalPaise,
        )
    }

    private fun movement(txn: TxnEntity, l: Line, type: MovementType, qty: Long, value: Long, batchId: String?, reason: String?) =
        stockRow(txn, l.index + 1, l.product!!, type, qty, value, batchId, reason)

    private fun stockRow(txn: TxnEntity, lineNo: Int, product: ProductEntity, type: MovementType, qty: Long, value: Long, batchId: String?, reason: String?) =
        StockMovementEntity(
            txnId = txn.id, lineNo = lineNo, businessId = biz, warehouseId = ctx.warehouseId, productId = product.id,
            batchId = batchId, date = txn.date, type = type.name, qtyMilli = qty, valuePaise = value,
            unit = product.primaryUnit, reason = reason, userId = ctx.userId, active = true,
        )

    private fun taxPostings(ledger: Ledger, cgst: Long, sgst: Long, igst: Long, cess: Long, account: Account, credit: Boolean) {
        listOf("CGST" to cgst, "SGST" to sgst, "IGST" to igst, "CESS" to cess).forEach { (component, amount) ->
            if (credit) ledger.cr(account, amount, category = component) else ledger.dr(account, amount, category = component)
        }
    }

    /** A payment taken / made together with an invoice / bill, applied to it. */
    private suspend fun childPayment(parent: TxnEntity, part: PaymentPart, index: Int, type: TxnType, party: PartyEntity?) {
        val account = moneyAccountOf(part.moneyAccountId)
        val pay = newTxn(
            type = type, number = nextNumber(type), date = LocalDate.ofEpochDay(parent.date.toLong()),
            meta = PostMeta(clientKey = "${parent.clientKey}#pay$index", source = TxnSource.valueOf(parent.source)),
            party = party, linkedTxnId = parent.id,
        ).copy(totalPaise = part.amountPaise, moneyAccountId = account.id, paymentMode = part.mode.name, reference = part.reference?.trim())
        dao.insertTxn(pay)
        val ledger = Ledger(pay)
        if (type == TxnType.PAYMENT_IN) {
            ledger.dr(Account.MONEY, part.amountPaise, moneyAccountId = account.id)
            ledger.cr(Account.RECEIVABLE, part.amountPaise, partyId = party?.id)
        } else {
            ledger.dr(Account.PAYABLE, part.amountPaise, partyId = party?.id)
            ledger.cr(Account.MONEY, part.amountPaise, moneyAccountId = account.id)
        }
        ledger.write()
        dao.insertAllocations(listOf(AllocationEntity(businessId = biz, fromTxnId = pay.id, toTxnId = parent.id, amountPaise = part.amountPaise, active = true, createdAt = now())))
        finish(pay, "CREATE")
    }

    private suspend fun finish(txn: TxnEntity, action: String): TxnEntity {
        audit(txn, action, reason = null, summary = summary(txn))
        outbox(txn.id)
        return txn
    }

    private fun summary(txn: TxnEntity) = "${txn.type} ${txn.number} ₹${Money.toRupees(txn.totalPaise)}"

    private suspend fun audit(txn: TxnEntity, action: String, reason: String?, summary: String) {
        dao.insertAudit(AuditEntity(businessId = biz, entity = "TXN", entityId = txn.id, action = action, userId = ctx.userId, at = now(), source = txn.source, reason = reason, summary = summary))
    }

    private suspend fun outbox(txnId: String) {
        dao.upsertOutbox(OutboxEntity(txnId = txnId, businessId = biz, state = SyncState.PENDING.name, updatedAt = now()))
    }

    /** Builds one document's double-entry lines and refuses to write them unless debits = credits. */
    private inner class Ledger(private val txn: TxnEntity) {
        private val rows = mutableListOf<PostingEntity>()

        fun dr(account: Account, amount: Long, partyId: String? = null, moneyAccountId: String? = null, category: String? = null) =
            add(account, amount, partyId, moneyAccountId, category, debit = true)

        fun cr(account: Account, amount: Long, partyId: String? = null, moneyAccountId: String? = null, category: String? = null) =
            add(account, amount, partyId, moneyAccountId, category, debit = false)

        private fun add(account: Account, amount: Long, partyId: String?, moneyAccountId: String?, category: String?, debit: Boolean) {
            if (amount == 0L) return
            // A negative debit is a credit (round-off, overdraft openings).
            val isDebit = if (amount > 0) debit else !debit
            val value = kotlin.math.abs(amount)
            rows += PostingEntity(
                txnId = txn.id, businessId = biz, branchId = ctx.branchId, date = txn.date, account = account.name,
                partyId = partyId, moneyAccountId = moneyAccountId, category = category,
                debitPaise = if (isDebit) value else 0, creditPaise = if (isDebit) 0 else value, active = true,
            )
        }

        suspend fun write() {
            check(rows.sumOf { it.debitPaise } == rows.sumOf { it.creditPaise }) { "Unbalanced postings for ${txn.type} ${txn.number}" }
            if (rows.isNotEmpty()) dao.insertPostings(rows)
        }
    }
}
