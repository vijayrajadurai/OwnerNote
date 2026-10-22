package com.shopai.app.util

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** ₹18,240 → ₹18.2K, ₹4,82,500 → ₹4.8L, ₹1,00,00,000 → ₹1Cr — short enough for a Home tile. */
fun shortRupees(amount: Double): String {
    val a = abs(amount)
    val sign = if (amount < 0) "-" else ""
    // Thresholds sit just under the next unit so ₹99,990 reads ₹1L, never ₹100K.
    val text = when {
        a >= 99_95_000 -> trimmed(a / 1_00_00_000) + "Cr"
        a >= 99_950 -> trimmed(a / 1_00_000) + "L"
        a >= 1_000 -> trimmed(a / 1_000) + "K"
        else -> round(a).toLong().toString()
    }
    return "$sign₹$text"
}

private fun trimmed(v: Double): String {
    val one = round(v * 10) / 10
    return if (one >= 100 || one == floor(one)) one.toLong().toString() else one.toString()
}
