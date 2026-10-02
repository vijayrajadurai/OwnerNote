package com.shopai.app.util

import java.text.NumberFormat
import java.util.Locale

private val inrFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
    maximumFractionDigits = 0
}

fun formatInr(amount: Double): String = inrFormat.format(amount)

fun formatQty(quantity: Double): String =
    if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
