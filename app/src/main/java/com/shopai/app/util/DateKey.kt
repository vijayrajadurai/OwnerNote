package com.shopai.app.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

/** Local calendar date as YYYY-MM-DD — never use Instant truncation. */
fun localDateKey(date: LocalDate = LocalDate.now()): String = date.format(DATE_KEY_FORMAT)

fun dateKeyToLocalDate(key: String): LocalDate = LocalDate.parse(key, DATE_KEY_FORMAT)

fun shiftDateKey(key: String, deltaDays: Long): String =
    dateKeyToLocalDate(key).plusDays(deltaDays).format(DATE_KEY_FORMAT)

fun isFutureDateKey(key: String, today: LocalDate = LocalDate.now()): Boolean =
    dateKeyToLocalDate(key).isAfter(today)
