package com.shopai.app.brain.tools

import com.shopai.app.books.model.PaymentMode
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

// ------------------------------------------------------------ facts (read from the books)

data class MoneyBalanceFact(val name: String, val kind: MoneyKind, val amount: BigDecimal)

data class StockFact(
    val name: String,
    val qty: BigDecimal,
    val unit: String,
    /** The reorder / minimum level set on the product, if any. */
    val reorderAt: BigDecimal? = null,
)

data class ProductSalesFact(val name: String, val qty: BigDecimal, val value: BigDecimal)

/** A customer or supplier in the books. [balance]: what they owe me (customer) / what I owe them (supplier). */
data class PartyMatch(val id: String, val name: String, val customer: Boolean, val phone: String?, val balance: BigDecimal)

// ------------------------------------------------------------ actions

enum class PlanKind {
    /** A customer paid me — settles their dues (the engine's payment in). */
    PAYMENT_IN,
    /** I paid a supplier — settles my dues (the engine's payment out). */
    PAYMENT_OUT,
    /** I gave money to a customer / person — they owe me (a Credit entry). */
    CREDIT_GIVEN,
    /** I received money from a supplier / person — I owe them (a Debit entry). */
    DEBIT_TAKEN,
}

/**
 * A financial action Kai understood, prepared with the engine's own quote —
 * nothing is written until [KaiTools.confirm]. [problems] (from the engine)
 * block confirmation.
 */
data class ActionPlan(
    val key: String,
    val kind: PlanKind,
    val partyName: String,
    val partyId: String?,
    val amount: BigDecimal,
    val mode: PaymentMode,
    /** Documents this payment settles (number → amount), oldest due first. */
    val settles: List<Pair<String, BigDecimal>> = emptyList(),
    /** Paid beyond the dues: kept as an advance. */
    val advance: BigDecimal = BigDecimal.ZERO,
    val balanceBefore: BigDecimal? = null,
    val balanceAfter: BigDecimal? = null,
    val problems: List<String> = emptyList(),
    val draftId: String? = null,
    /** The owner's own words. */
    val said: String,
)

sealed interface ActionOutcome {
    /** Saved by the engine: [reference] is the document number. */
    data class Done(val reference: String, val balanceAfter: BigDecimal?) : ActionOutcome
    data class Failed(val reason: String) : ActionOutcome
}

/** A personal / task reminder Kai keeps on the phone (separate from the books). */
data class KaiReminder(
    val id: String,
    /** The owner's own words for the task ("kadaiku pogumbothu saavi eduthuka"). */
    val task: String,
    /** Set for "call X" reminders. */
    val callName: String? = null,
    val phone: String? = null,
    /** The next time it rings. */
    val at: LocalDateTime,
    val repeat: Repeat = Repeat.ONCE,
    val weekday: DayOfWeek? = null,
    val said: String,
    val createdAt: Long,
    /** A one-time reminder that has rung and waits for Done / Snooze (not armed, not listed). */
    val rang: Boolean = false,
) {
    /** After it rang at [at]: the next time for a repeating reminder, else null. */
    fun nextAfter(now: LocalDateTime): LocalDateTime? {
        var next = at
        when (repeat) {
            Repeat.ONCE -> return null
            Repeat.DAILY -> while (!next.isAfter(now)) next = next.plusDays(1)
            Repeat.WEEKLY -> while (!next.isAfter(now)) next = next.plusWeeks(1)
        }
        return next
    }
}

/** EXACT: rings on time. APPROXIMATE: Android may delay it (exact alarms not allowed for the app). */
enum class ScheduleResult { EXACT, APPROXIMATE, FAILED }

enum class ActionStatus { ANSWERED, DRAFT, CONFIRMED, CANCELLED, SCHEDULED, FAILED, OPENED }

/** Every Kai action is recorded: intent, tool, result, status, time and a reference id. */
data class KaiActionRecord(
    val reference: String,
    val intent: String,
    val tool: String,
    val result: String,
    val status: ActionStatus,
    val timestamp: Long,
)

/**
 * Everything Kai can look up or do — deterministic code over the existing
 * OwnerNote systems (books engine, reminders, phone). Reads return null when
 * the data can't be verified; Kai then says so instead of guessing.
 */
interface KaiTools {
    // ---- business data (the books are the truth) ----
    suspend fun sales(from: LocalDate, to: LocalDate): BigDecimal? = null
    suspend fun purchases(from: LocalDate, to: LocalDate): BigDecimal? = null
    suspend fun expenses(from: LocalDate, to: LocalDate): BigDecimal? = null
    suspend fun moneyBalances(): List<MoneyBalanceFact>? = null
    /** [product] null = every product with stock. */
    suspend fun stock(product: String?): List<StockFact>? = null
    suspend fun lowStock(): List<StockFact>? = null
    suspend fun topProducts(from: LocalDate, to: LocalDate, limit: Int): List<ProductSalesFact>? = null
    suspend fun parties(name: String): List<PartyMatch>? = null

    // ---- financial actions: prepare (no write) → confirm (engine) ----
    suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan? = null
    suspend fun confirm(plan: ActionPlan): ActionOutcome = ActionOutcome.Failed("unavailable")
    suspend fun discard(plan: ActionPlan) {}

    // ---- reminders (phone) ----
    fun schedule(reminder: KaiReminder): ScheduleResult = ScheduleResult.FAILED
    fun reminders(): List<KaiReminder> = emptyList()
    fun cancelReminder(id: String): Boolean = false

    // ---- audit ----
    fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String? = null): String = reference ?: "-"
}
