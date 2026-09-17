package com.shopai.app.util

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val systemZone: ZoneId = ZoneId.systemDefault()

fun parseDueDateInput(text: String): String? {
    if (text.isBlank()) return null
    return parseIsoToLocalDate(text)?.let { localDateToIsoInstant(it) }
}

fun parseIsoToLocalDate(iso: String?): LocalDate? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        Instant.parse(iso).atZone(systemZone).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDate.parse(iso.trim().take(10))
    }.getOrNull()
}

fun localDateToIsoInstant(date: LocalDate): String =
    date.atStartOfDay(systemZone).toInstant().toString()

fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(systemZone).toInstant().toEpochMilli()

fun formatLocalDateForDisplay(date: LocalDate): String =
    DateTimeFormatter.ofPattern("dd MMM yyyy").format(date)

fun formatDisplayDate(iso: String): String {
    return parseIsoToLocalDate(iso)?.let { formatLocalDateForDisplay(it) }
        ?: iso.take(10)
}

/** Only today and future dates can be selected in the date picker. */
@OptIn(ExperimentalMaterial3Api::class)
class FutureSelectableDates(
    private val minDate: LocalDate = LocalDate.now(),
) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val date = Instant.ofEpochMilli(utcTimeMillis).atZone(systemZone).toLocalDate()
        return !date.isBefore(minDate)
    }

    override fun isSelectableYear(year: Int): Boolean = year >= minDate.year
}
