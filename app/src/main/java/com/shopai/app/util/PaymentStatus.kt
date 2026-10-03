package com.shopai.app.util

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Where an entry stands: Paid, Overdue, Partially paid, Upcoming (or Pending
 * when there is no due date). Worked out from the saved total, what has been
 * paid against it and its due date — never typed in.
 */
enum class PaymentState { PAID, OVERDUE, PARTIALLY_PAID, UPCOMING, PENDING }

data class PaymentStatus(
    val state: PaymentState,
    val outstanding: BigDecimal,
    /** Some was paid but not all (shown together with Overdue / Upcoming). */
    val partlyPaid: Boolean,
    val dueDate: LocalDate?,
    /** Days late (positive) or days left (negative); null without a due date. */
    val daysLate: Long?,
) {
    companion object {
        fun of(total: BigDecimal, paid: BigDecimal, dueDate: LocalDate?, today: LocalDate = LocalDate.now()): PaymentStatus {
            val outstanding = (total - paid).max(BigDecimal.ZERO)
            val partly = paid.signum() > 0 && outstanding.signum() > 0
            val late = dueDate?.let { today.toEpochDay() - it.toEpochDay() }
            val state = when {
                outstanding.signum() == 0 -> PaymentState.PAID
                late != null && late > 0 -> PaymentState.OVERDUE
                partly -> PaymentState.PARTIALLY_PAID
                dueDate != null -> PaymentState.UPCOMING
                else -> PaymentState.PENDING
            }
            return PaymentStatus(state, outstanding, partly, dueDate, late)
        }
    }
}
