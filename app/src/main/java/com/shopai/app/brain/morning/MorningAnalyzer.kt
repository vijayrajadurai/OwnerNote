package com.shopai.app.brain.morning

import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns real business conditions into candidate tasks, with fixed,
 * explainable priority rules. Read only and pure: it gets a snapshot and
 * returns a list — it can't change a balance, stock, invoice or reminder.
 *
 * One primary task per source (a customer gets one collection task, never a
 * collection + a follow-up + a "pending" task for the same money); extra
 * context (partial bills, open bill count) goes inside that task.
 */
object MorningAnalyzer {
    /** Upcoming payments are shown this many days ahead. */
    const val UPCOMING_DAYS = 3L

    /** Batches expiring within this many days are flagged. */
    const val EXPIRY_DAYS = 7L

    private const val MONEY_EPSILON = 0.005

    fun candidates(
        snapshot: MorningSnapshot,
        today: LocalDate,
        nowMillis: Long,
        zone: ZoneId,
        /** Reminders Morning Work itself created (postponed tasks) — they are not new tasks. */
        ownReminderIds: Set<String> = emptySet(),
    ): List<MorningTask> {
        val day = today.toEpochDay()
        val biz = snapshot.businessId
        val out = LinkedHashMap<String, MorningTask>()

        fun add(task: MorningTask) {
            // Deterministic de-duplication: business, type, source, business date.
            out.putIfAbsent(task.taskId, task)
        }

        fun task(
            type: MorningTaskType,
            sourceType: MorningSourceType,
            sourceId: String,
            reason: PriorityReason,
            title: String,
            description: String,
            dueAt: Long?,
            phone: String? = null,
            facts: TaskFacts = TaskFacts(),
        ) = MorningTask(
            taskId = MorningTask.idFor(biz, type, sourceId, day),
            businessId = biz,
            sourceType = sourceType,
            sourceId = sourceId,
            taskType = type,
            priority = reason.priority,
            reason = reason,
            title = title,
            description = description,
            createdAt = nowMillis,
            dueAt = dueAt,
            businessDate = day,
            phone = phone,
            facts = facts,
        )

        // ---- customers and suppliers (one task per party) ----
        for (p in snapshot.parties.distinctBy { it.id }) {
            if (p.pending <= MONEY_EPSILON) continue
            val openDocs = p.docs.filter { it.outstanding > MONEY_EPSILON }
            val partial = openDocs.filter { it.paid > MONEY_EPSILON }
            // The earliest due date the records have (bill due dates, else the party's next due).
            val due = (openDocs.mapNotNull { it.dueDay } + listOfNotNull(p.nextDueDay)).minOrNull()
            val facts = TaskFacts(
                amount = p.pending,
                billed = partial.takeIf { it.isNotEmpty() }?.sumOf { it.total },
                paid = partial.takeIf { it.isNotEmpty() }?.sumOf { it.paid },
                openBills = openDocs.size,
                partialBills = partial.size,
            )
            val reason = when {
                due != null && due < day -> PriorityReason.OVERDUE
                due != null && due == day -> PriorityReason.DUE_TODAY
                p.kind == MorningPartyKind.CUSTOMER && partial.isNotEmpty() -> PriorityReason.PARTIALLY_PAID
                due != null && due - day <= UPCOMING_DAYS -> PriorityReason.DUE_SOON
                p.kind == MorningPartyKind.CUSTOMER && due == null -> PriorityReason.PENDING_NO_DUE_DATE
                else -> null
            } ?: continue
            if (p.kind == MorningPartyKind.CUSTOMER) {
                val type = if (reason == PriorityReason.OVERDUE || reason == PriorityReason.DUE_TODAY) {
                    MorningTaskType.COLLECT_PAYMENT
                } else {
                    MorningTaskType.PAYMENT_FOLLOWUP
                }
                add(task(type, MorningSourceType.CUSTOMER, p.id, reason, p.name, "collect", due, p.phone, facts))
            } else {
                add(task(MorningTaskType.SUPPLIER_PAYMENT, MorningSourceType.SUPPLIER, p.id, reason, p.name, "pay", due, p.phone, facts))
            }
        }

        // ---- stock ----
        for (p in snapshot.products.distinctBy { it.id }) {
            val reorder = p.reorderLevel?.takeIf { it > 0 }
            val minimum = p.minimum?.takeIf { it > 0 }
            if (reorder == null && minimum == null) continue // no level set: nothing to judge against
            val reason = when {
                p.stock <= 0.0 -> PriorityReason.OUT_OF_STOCK
                reorder != null && p.stock < reorder -> PriorityReason.BELOW_REORDER_LEVEL
                minimum != null && p.stock < minimum -> PriorityReason.BELOW_MINIMUM_STOCK
                else -> null
            } ?: continue
            add(
                task(
                    MorningTaskType.LOW_STOCK, MorningSourceType.PRODUCT, p.id, reason, p.name, "stock", null,
                    facts = TaskFacts(stock = p.stock, minimum = minimum, reorderLevel = reorder, unit = p.unit),
                ),
            )
        }

        // ---- expiring batches (only where batch tracking exists) ----
        for (b in snapshot.batches.distinctBy { it.id }) {
            if (b.stock <= 0.0) continue
            val reason = when {
                b.expiryDay < day -> PriorityReason.EXPIRED
                b.expiryDay - day <= EXPIRY_DAYS -> PriorityReason.EXPIRING_SOON
                else -> null
            } ?: continue
            add(
                task(
                    MorningTaskType.EXPIRY, MorningSourceType.BATCH, b.id, reason, b.productName, "expiry", b.expiryDay,
                    facts = TaskFacts(stock = b.stock, unit = b.unit, batchNo = b.batchNo, expiryDay = b.expiryDay),
                ),
            )
        }

        // ---- the owner's own reminders: today's and ones already past ----
        for (r in snapshot.reminders.distinctBy { it.id }) {
            if (r.done || !r.custom || r.id in ownReminderIds) continue
            val rDay = java.time.Instant.ofEpochMilli(r.atMillis).atZone(zone).toLocalDate().toEpochDay()
            val reason = when {
                rDay < day -> PriorityReason.REMINDER_PAST_DUE
                rDay == day && r.atMillis <= nowMillis -> PriorityReason.REMINDER_NOW
                rDay == day -> PriorityReason.REMINDER_LATER_TODAY
                else -> null
            } ?: continue
            add(
                task(
                    MorningTaskType.REMINDER, MorningSourceType.REMINDER, r.id, reason, r.title, "reminder", rDay,
                    facts = TaskFacts(reminderAtMillis = r.atMillis),
                ),
            )
        }

        // ---- drafts waiting for the owner ----
        for (d in snapshot.drafts.distinctBy { it.id }) {
            add(
                task(
                    MorningTaskType.PENDING_DRAFT, MorningSourceType.DRAFT, d.id, PriorityReason.AWAITING_CONFIRMATION,
                    d.kind, "draft", null, facts = TaskFacts(draftKind = d.kind),
                ),
            )
        }

        return sort(out.values.toList())
    }

    /**
     * Priority first; then the oldest due date, the bigger amount, and the
     * name — so the same data always gives the same order.
     */
    fun sort(tasks: List<MorningTask>): List<MorningTask> = tasks.sortedWith(
        compareBy<MorningTask> { it.priority.ordinal }
            .thenBy(nullsLast()) { it.dueAt }
            .thenByDescending { it.facts.amount ?: 0.0 }
            .thenBy { it.taskType.ordinal }
            .thenBy { it.title.lowercase() }
            .thenBy { it.taskId },
    )
}
