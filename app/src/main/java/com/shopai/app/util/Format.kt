package com.shopai.app.util

import java.text.NumberFormat
import java.util.Locale

private val inrFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
    maximumFractionDigits = 0
}

fun formatInr(amount: Double): String = inrFormat.format(amount)
