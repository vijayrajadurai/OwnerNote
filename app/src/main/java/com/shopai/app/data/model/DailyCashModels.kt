package com.shopai.app.data.model

enum class DailyCashEntryType {
    IN,
    OUT,
}

enum class DailyCashPaymentMode {
    CASH,
    UPI,
}

data class DailyCashEntry(
    val id: String,
    val date: String,
    val type: DailyCashEntryType,
    val amount: Double,
    val paymentMode: DailyCashPaymentMode,
    val note: String?,
    val createdAt: String,
)

data class TodayCashSummary(
    val date: String,
    val totalIn: Double,
    val totalOut: Double,
    val entryCount: Int,
)

data class DailyCashTotals(
    val totalIn: Double,
    val totalOut: Double,
    val net: Double,
    val upiIn: Double,
    val upiOut: Double,
    val upiNet: Double,
    val cashIn: Double,
    val cashOut: Double,
    val cashNet: Double,
)

enum class DailyCashDayStatus {
    OPEN,
    SUBMITTED,
}

data class SubmitDailyCashReportEntry(
    val type: String,
    val amount: Double,
    val paymentMode: String,
    val note: String? = null,
    val createdAt: String,
)

data class SubmitDailyCashReportRequest(
    val date: String,
    val totalIn: Double,
    val totalOut: Double,
    val net: Double,
    val cashIn: Double,
    val cashOut: Double,
    val upiIn: Double,
    val upiOut: Double,
    val entries: List<SubmitDailyCashReportEntry>,
)

data class DailyCashReportResponse(
    val id: String,
    val date: String,
    val totalIn: Double,
    val totalOut: Double,
    val net: Double,
    val submittedAt: String,
)
