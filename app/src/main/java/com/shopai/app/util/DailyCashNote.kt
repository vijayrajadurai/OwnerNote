package com.shopai.app.util

import com.shopai.app.data.model.DailyCashEntry
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.model.DailyCashPaymentMode
import com.shopai.app.data.model.DailyCashTotals

fun computeDailyCashTotals(entries: List<DailyCashEntry>): DailyCashTotals {
    var totalIn = 0.0
    var totalOut = 0.0
    var upiIn = 0.0
    var upiOut = 0.0
    var cashIn = 0.0
    var cashOut = 0.0

    for (entry in entries) {
        if (entry.type == DailyCashEntryType.IN) {
            totalIn += entry.amount
            if (entry.paymentMode == DailyCashPaymentMode.UPI) upiIn += entry.amount else cashIn += entry.amount
        } else {
            totalOut += entry.amount
            if (entry.paymentMode == DailyCashPaymentMode.UPI) upiOut += entry.amount else cashOut += entry.amount
        }
    }

    return DailyCashTotals(
        totalIn = totalIn,
        totalOut = totalOut,
        net = totalIn - totalOut,
        upiIn = upiIn,
        upiOut = upiOut,
        upiNet = upiIn - upiOut,
        cashIn = cashIn,
        cashOut = cashOut,
        cashNet = cashIn - cashOut,
    )
}

fun sortEntriesNewestFirst(entries: List<DailyCashEntry>): List<DailyCashEntry> =
    entries.sortedByDescending { it.createdAt }

fun isValidAmount(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return false
    val value = trimmed.toDoubleOrNull() ?: return false
    return value.isFinite() && value > 0
}
