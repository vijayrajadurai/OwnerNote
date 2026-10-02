package com.shopai.app.ui.reminders

import com.shopai.app.data.model.ReminderItem
import com.shopai.app.util.parseIsoToLocalDate
import java.time.LocalDate
import java.util.Locale

enum class ReminderUrgency {
    OVERDUE,
    DUE_TODAY,
    UPCOMING,
}

data class PaymentReminder(
    val id: String,
    val kind: String,
    val amount: Double?,
    val partyName: String,
    val description: String,
    val dueDateIso: String,
    val urgency: ReminderUrgency,
)

fun PaymentReminder.isCustomerCredit(): Boolean {
    val k = kind.uppercase(Locale.ROOT)
    return k == "COLLECTION" || k == "CREDIT"
}

fun reminderUrgency(dueDateIso: String, today: LocalDate = LocalDate.now()): ReminderUrgency {
    val due = parseIsoToLocalDate(dueDateIso) ?: return ReminderUrgency.UPCOMING
    return when {
        due.isBefore(today) -> ReminderUrgency.OVERDUE
        due.isEqual(today) -> ReminderUrgency.DUE_TODAY
        else -> ReminderUrgency.UPCOMING
    }
}

fun ReminderItem.toPaymentReminder(today: LocalDate = LocalDate.now()): PaymentReminder {
    val parsed = parseReminderTitle(title, kind)
    return PaymentReminder(
        id = id,
        kind = kind,
        amount = amount,
        partyName = parsed.first,
        description = parsed.second,
        dueDateIso = dueDate,
        urgency = reminderUrgency(dueDate, today),
    )
}

fun List<ReminderItem>.toSortedPaymentReminders(
    today: LocalDate = LocalDate.now(),
): List<PaymentReminder> {
    return filter { !it.isDone }.map { it.toPaymentReminder(today) }.sortedWith(
        compareBy<PaymentReminder> {
            when (it.urgency) {
                ReminderUrgency.OVERDUE -> 0
                ReminderUrgency.DUE_TODAY -> 1
                ReminderUrgency.UPCOMING -> 2
            }
        }.thenBy { it.dueDateIso },
    )
}

fun samplePaymentReminders(today: LocalDate = LocalDate.of(2026, 9, 22)): List<PaymentReminder> = listOf(
    PaymentReminder(
        id = "sample-overdue",
        kind = "PAYMENT",
        amount = 8_200.0,
        partyName = "Suresh Traders",
        description = "Monthly stock balance",
        dueDateIso = today.minusDays(1).toString(),
        urgency = ReminderUrgency.OVERDUE,
    ),
    PaymentReminder(
        id = "sample-today",
        kind = "CUSTOM",
        amount = 12_500.0,
        partyName = "Ravi Kumar",
        description = "Shop material credit payment",
        dueDateIso = today.toString(),
        urgency = ReminderUrgency.DUE_TODAY,
    ),
    PaymentReminder(
        id = "sample-upcoming",
        kind = "COLLECTION",
        amount = 5_750.0,
        partyName = "Priya Stores",
        description = "Product payment",
        dueDateIso = today.plusDays(1).toString(),
        urgency = ReminderUrgency.UPCOMING,
    ),
)

private fun parseReminderTitle(title: String, kind: String): Pair<String, String> {
    val collect = Regex("(?i)collect from (.+?)(?:\\s*[—\\-|]|$)").find(title)
    if (collect != null) {
        return collect.groupValues[1].trim() to title
    }
    val pay = Regex("(?i)pay (.+?)(?:\\s*[—\\-|]|$)").find(title)
    if (pay != null) {
        return pay.groupValues[1].trim() to title
    }
    return when (kind.uppercase(Locale.ROOT)) {
        "COLLECTION", "PAYMENT" -> title to title
        else -> title to title
    }
}
