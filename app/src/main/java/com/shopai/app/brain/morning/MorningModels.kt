package com.shopai.app.brain.morning

import java.time.LocalDate

/**
 * Kai — Do My Morning Work: the data it reads and the tasks it keeps.
 *
 * Everything here is plain Kotlin (no Android), so the whole Morning Work
 * engine is unit-testable. The inputs ([MorningSnapshot]) are a read-only
 * copy of figures the existing engines already calculated — party balances
 * from the ledger, stock from the stock movements, reminders from the
 * reminder engine. Morning Work never computes a balance of its own.
 */

enum class MorningPriority { CRITICAL, HIGH, MEDIUM, LOW }

enum class MorningTaskType {
    /** A customer's money is due today or overdue. */
    COLLECT_PAYMENT,

    /** A customer has paid part of a bill, or owes money with no near due date. */
    PAYMENT_FOLLOWUP,

    /** The owner has to pay a supplier (overdue, today or within a few days). */
    SUPPLIER_PAYMENT,

    /** A product is out of stock or below its minimum / reorder level. */
    LOW_STOCK,

    /** A batch has expired or expires within a week (batch tracking only). */
    EXPIRY,

    /** One of the owner's own reminders for today (or one already past). */
    REMINDER,

    /** A voice / scan / typed draft is waiting for the owner's confirmation. */
    PENDING_DRAFT,
}

enum class MorningTaskStatus { PENDING, IN_PROGRESS, COMPLETED, POSTPONED, SKIPPED, FAILED }

enum class MorningSourceType { CUSTOMER, SUPPLIER, PRODUCT, BATCH, REMINDER, DRAFT }

enum class SkipReason { ALREADY_HANDLED, NOT_NEEDED, WILL_DO_LATER, WRONG_INFORMATION, OTHER }

/**
 * Why a task has its priority — every priority is explained by one of these
 * fixed rules (no AI scoring).
 */
enum class PriorityReason(val priority: MorningPriority) {
    OVERDUE(MorningPriority.CRITICAL),
    OUT_OF_STOCK(MorningPriority.CRITICAL),
    EXPIRED(MorningPriority.CRITICAL),
    REMINDER_PAST_DUE(MorningPriority.CRITICAL),
    DUE_TODAY(MorningPriority.HIGH),
    BELOW_REORDER_LEVEL(MorningPriority.HIGH),
    BELOW_MINIMUM_STOCK(MorningPriority.HIGH),
    REMINDER_NOW(MorningPriority.HIGH),
    DUE_SOON(MorningPriority.MEDIUM),
    PARTIALLY_PAID(MorningPriority.MEDIUM),
    EXPIRING_SOON(MorningPriority.MEDIUM),
    REMINDER_LATER_TODAY(MorningPriority.MEDIUM),
    AWAITING_CONFIRMATION(MorningPriority.MEDIUM),
    PENDING_NO_DUE_DATE(MorningPriority.LOW),
}

/** How the owner is talking to Kai right now; Kai answers the same way. */
enum class ResponseMode { VOICE, TEXT }

/** Facts shown inside a task card, copied from the source of truth when the task was (re)built. */
data class TaskFacts(
    /** Pending / outstanding amount (party balance from the ledger). */
    val amount: Double? = null,
    /** Partially paid bills: their total and what was paid. */
    val billed: Double? = null,
    val paid: Double? = null,
    val openBills: Int = 0,
    val partialBills: Int = 0,
    val stock: Double? = null,
    val minimum: Double? = null,
    val reorderLevel: Double? = null,
    val unit: String? = null,
    val batchNo: String? = null,
    val expiryDay: Long? = null,
    val reminderAtMillis: Long? = null,
    val draftKind: String? = null,
)

/**
 * One morning task. Field names follow the stored record: task_id,
 * business_id, source_type, source_id, task_type, priority, title,
 * description, created_at, due_at, status, completed_at, action_taken.
 * Dates are epoch days / epoch millis so the record stores as plain JSON.
 */
data class MorningTask(
    val taskId: String,
    val businessId: String,
    val sourceType: MorningSourceType,
    val sourceId: String,
    val taskType: MorningTaskType,
    val priority: MorningPriority,
    val reason: PriorityReason,
    /** Party / product / reminder name. */
    val title: String,
    val description: String,
    val createdAt: Long,
    /** Due day (epoch day) of the money, expiry or reminder, when there is one. */
    val dueAt: Long?,
    val status: MorningTaskStatus = MorningTaskStatus.PENDING,
    val completedAt: Long? = null,
    val actionTaken: String? = null,
    /** The day this task belongs to (epoch day) — part of the de-duplication key. */
    val businessDate: Long,
    val phone: String? = null,
    val facts: TaskFacts = TaskFacts(),
    val skipReason: SkipReason? = null,
    /** Reminder created in the existing reminder engine when the task was postponed. */
    val reminderId: String? = null,
    /** Postponed while offline: the reminder still has to be created (epoch millis). */
    val pendingReminderAt: Long? = null,
    val updatedAt: Long = createdAt,
) {
    val open: Boolean get() = status == MorningTaskStatus.PENDING || status == MorningTaskStatus.IN_PROGRESS
    val dueDate: LocalDate? get() = dueAt?.let(LocalDate::ofEpochDay)

    companion object {
        /** Deterministic de-duplication key: business, task type, source and business date. */
        fun idFor(businessId: String, type: MorningTaskType, sourceId: String, businessDate: Long): String =
            "$businessId|${type.name}|$sourceId|$businessDate"
    }
}

// ---------------------------------------------------------------- inputs

enum class MorningPartyKind { CUSTOMER, SUPPLIER }

/** An open bill / invoice of a party with what is still left on it. */
data class MorningDoc(
    val id: String,
    val number: String,
    val total: Double,
    val paid: Double,
    val dueDay: Long?,
    val dateDay: Long,
) {
    val outstanding: Double get() = total - paid
}

data class MorningParty(
    val id: String,
    val name: String,
    val kind: MorningPartyKind,
    val phone: String?,
    /** The party's balance as the ledger has it (never recalculated here). */
    val pending: Double,
    val nextDueDay: Long?,
    /** Open documents, when the books have them (empty with the older backend ledger). */
    val docs: List<MorningDoc> = emptyList(),
)

data class MorningProduct(
    val id: String,
    val name: String,
    val unit: String,
    /** Current stock from the stock movements. */
    val stock: Double,
    val minimum: Double?,
    val reorderLevel: Double?,
)

data class MorningBatch(
    val id: String,
    val productId: String,
    val productName: String,
    val batchNo: String,
    val expiryDay: Long,
    val stock: Double,
    val unit: String,
)

data class MorningReminder(
    val id: String,
    val title: String,
    val atMillis: Long,
    val done: Boolean,
    /** Owner's own reminder (payment dues come from the parties themselves). */
    val custom: Boolean,
)

data class MorningDraft(val id: String, val kind: String, val createdAtMillis: Long)

/**
 * Everything Morning Work reads, for one business, at one moment. When the
 * phone is offline the adapter passes the last synced copy with [offline]
 * set, so Kai says it is showing last synced information.
 */
data class MorningSnapshot(
    val businessId: String,
    val parties: List<MorningParty> = emptyList(),
    val products: List<MorningProduct> = emptyList(),
    val batches: List<MorningBatch> = emptyList(),
    val reminders: List<MorningReminder> = emptyList(),
    val drafts: List<MorningDraft> = emptyList(),
    val offline: Boolean = false,
    /** When these figures were read from the source (epoch millis). */
    val syncedAtMillis: Long = 0L,
    /** The signed-in owner (from the authenticated session) this snapshot was read for. */
    val ownerId: String? = null,
    /** That owner's saved Morning Routine (section order); null = the default order. */
    val routine: List<MorningSection>? = null,
    /** Business-wide totals from aggregate queries, when the lists were read bounded. */
    val totals: MorningTotals? = null,
)

// ---------------------------------------------------------------- outputs

/** Who is using the app — Morning Work follows the existing role rules. */
data class MorningRole(val role: String? = null, val permissions: Set<String> = emptySet()) {
    private val r = role?.uppercase()

    /** Recording money needs the owner (or manager / accountant); staff only with the PAYMENTS permission. */
    val canRecordPayments: Boolean
        get() = r == null || r == "OWNER" || r == "MANAGER" || r == "ACCOUNTANT" || "PAYMENTS" in permissions.map { it.uppercase() }

    companion object {
        val OWNER = MorningRole("OWNER")
    }
}

/** Today's figures, read from the same source of truth as the tasks. */
data class MorningSummary(
    val collections: Double,
    val payments: Double,
    val lowStockProducts: Int,
    val reminders: Int,
    val followUps: Int,
    val total: Int,
    val completed: Int,
    val postponed: Int,
    val skipped: Int,
    val failed: Int,
    val pending: Int,
)

/** The day's task list, in the order Kai goes through it. */
data class MorningPlan(
    val businessId: String,
    val businessDate: Long,
    val tasks: List<MorningTask>,
    val offline: Boolean,
    val syncedAtMillis: Long,
    /** All candidates found before the list was trimmed (for "N more" in the UI). */
    val candidateCount: Int,
) {
    val openTasks: List<MorningTask> get() = tasks.filter { it.open }
    val current: MorningTask? get() = openTasks.firstOrNull()
    val allDone: Boolean get() = tasks.isNotEmpty() && openTasks.isEmpty()
    fun indexOf(task: MorningTask): Int = tasks.indexOfFirst { it.taskId == task.taskId }
}
