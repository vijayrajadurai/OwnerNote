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
)

private val leadingEmojiRegex = Regex("^[🔴🟠🟡🟢]\\s*")
private val collectionNameRegex = Regex("from (.+?) —")
private val paymentNameRegex = Regex("payment to (.+?) —")

fun PriorityItem.toRowDisplay(): PriorityRowDisplay = when (kind) {
    "COLLECTION_DUE" -> PriorityRowDisplay(
        name = collectionNameRegex.find(message)?.groupValues?.get(1)?.trim() ?: stripLeadingEmoji(message),
        amount = amount,
        tone = PriorityAmountTone.CREDIT,
    )
    "PAYMENT_DUE" -> PriorityRowDisplay(
        name = paymentNameRegex.find(message)?.groupValues?.get(1)?.trim() ?: stripLeadingEmoji(message),
        amount = amount,
        tone = PriorityAmountTone.DEBIT,
    )
    "REMINDER" -> PriorityRowDisplay(
        name = stripLeadingEmoji(message.substringBefore(" —")),
        amount = amount,
        tone = PriorityAmountTone.NEUTRAL,
    )
    else -> PriorityRowDisplay(
        name = stripLeadingEmoji(message),
        amount = amount,
        tone = PriorityAmountTone.NEUTRAL,
    )
}

private fun stripLeadingEmoji(message: String): String =
    message.replace(leadingEmojiRegex, "").trim()
