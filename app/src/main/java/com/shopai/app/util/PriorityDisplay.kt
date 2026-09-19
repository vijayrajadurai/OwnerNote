package com.shopai.app.util

import com.shopai.app.data.model.PriorityItem

enum class PriorityAmountTone {
    CREDIT,
    DEBIT,
    NEUTRAL,
}

data class PriorityRowDisplay(
    val name: String,
    val amount: Double?,
    val tone: PriorityAmountTone,
    val dueDateIso: String? = null,
)

fun PriorityRowDisplay.initialLetter(): String =
    name.firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString() ?: "?"

private val leadingEmojiRegex = Regex("^[🔴🟠🟡🟢]\\s*")
private val collectionNameRegex = Regex("from (.+?) —")
private val paymentNameRegex = Regex("payment to (.+?) —")

fun PriorityItem.toRowDisplay(): PriorityRowDisplay = when (kind) {
    "COLLECTION_DUE" -> PriorityRowDisplay(
        name = collectionNameRegex.find(message)?.groupValues?.get(1)?.trim() ?: stripLeadingEmoji(message),
        amount = amount,
        tone = PriorityAmountTone.CREDIT,
        dueDateIso = dueDate,
    )
    "PAYMENT_DUE" -> PriorityRowDisplay(
        name = paymentNameRegex.find(message)?.groupValues?.get(1)?.trim() ?: stripLeadingEmoji(message),
        amount = amount,
        tone = PriorityAmountTone.DEBIT,
        dueDateIso = dueDate,
    )
    "REMINDER" -> PriorityRowDisplay(
        name = stripLeadingEmoji(message.substringBefore(" —")),
        amount = amount,
        tone = PriorityAmountTone.NEUTRAL,
        dueDateIso = dueDate,
    )
    else -> PriorityRowDisplay(
        name = stripLeadingEmoji(message),
        amount = amount,
        tone = PriorityAmountTone.NEUTRAL,
        dueDateIso = dueDate,
    )
}

private fun stripLeadingEmoji(message: String): String =
    message.replace(leadingEmojiRegex, "").trim()
