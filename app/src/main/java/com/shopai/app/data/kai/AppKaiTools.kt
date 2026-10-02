package com.shopai.app.data.kai

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
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.brain.tools.StockFact
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.repository.TransactionRepository
import com.shopai.app.notifications.KaiReminderAlarms
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
    private val books: BooksModule,
    private val transactions: TransactionRepository,
    private val reminders: KaiReminderAlarms,
    private val actionLog: KaiActionLog,
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
            s.dao.searchParties(biz, kind.name, key, 10, 0).map { p ->
                val customer = kind == PartyKind.CUSTOMER
                PartyMatch(p.id, p.name, customer, p.mobile?.let { "+91$it" },
                    rupees(if (customer) s.ledger.receivable(p.id) else s.ledger.payable(p.id)))
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
                    CreateCreditInput(customerId = plan.partyId, customerName = plan.partyName, amount = plan.amount.toDouble(), description = "Kai: ${plan.said}".take(120)),
                    source = TxnSource.VOICE,
                )
                val s = session()
                val txn = s?.dao?.txn(detail.id)
                ActionOutcome.Done(txn?.number ?: detail.id, txn?.partyId?.let { rupees(s.ledger.receivable(it)) })
            }
            PlanKind.DEBIT_TAKEN -> {
                val detail = transactions.createDebit(
                    CreateDebitInput(supplierId = plan.partyId, supplierName = plan.partyName, amount = plan.amount.toDouble(), description = "Kai: ${plan.said}".take(120)),
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

    override fun schedule(reminder: KaiReminder): ScheduleResult = reminders.schedule(reminder)
    override fun reminders(): List<KaiReminder> = reminders.upcoming()
    override fun cancelReminder(id: String): Boolean = reminders.cancel(id)

    override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?): String =
        actionLog.add(intent, tool, result, status, reference)
}
