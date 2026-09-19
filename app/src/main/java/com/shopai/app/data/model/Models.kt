package com.shopai.app.data.model

import androidx.annotation.StringRes
import com.shopai.app.R

data class ApiEnvelope<T>(
    val data: T,
)

data class ApiErrorBody(
    val error: ApiError?,
)

data class ApiError(
    val code: String?,
    val message: String?,
)

data class SendOtpRequest(val phone: String)
data class SendOtpResponse(val phone: String, val expiresInSeconds: Int)

data class VerifyOtpRequest(val phone: String, val code: String)
data class AuthResponse(val token: String, val isNewUser: Boolean)

data class TestLoginRequest(val username: String, val password: String)

data class Business(
    val id: String,
    val ownerUserId: String,
    val ownerName: String,
    val businessName: String,
    val category: String,
    val city: String,
    val runningSinceYear: Int?,
    val monthlyVolumeApprox: String?,
)

data class BusinessInput(
    val ownerName: String,
    val businessName: String,
    val category: String,
    val city: String,
    val runningSinceYear: Int? = null,
    val monthlyVolumeApprox: Int? = null,
)

data class DashboardSnapshot(
    val businessName: String,
    val ownerName: String? = null,
    val receivableTotal: Double,
    val payableTotal: Double,
    val netPosition: Double,
    val upcoming7DayPayments: Double,
    val upcoming7DayCollections: Double,
    val insights: List<String> = emptyList(),
)

data class BusinessHealth(
    val status: String,
    val explanation: String,
)

data class CashFlowWindow(
    val windowDays: Int,
    val expectedCollections: Double,
    val expectedPayments: Double,
    val potentialGap: Double,
)

data class CashFlowSummary(
    val totalReceivables: Double,
    val totalPayables: Double,
    val pendingReceivables: Double,
    val pendingPayables: Double,
    val netPosition: Double,
    val next7Days: CashFlowWindow,
    val next30Days: CashFlowWindow,
    val asOf: String,
)

data class PartySummary(
    val id: String,
    val name: String,
    val phone: String?,
    val pendingTotal: Double,
    val nextDueDate: String?,
)

data class CreateCreditInput(
    val customerName: String,
    val amount: Double,
    val description: String? = null,
    val dueDate: String? = null,
)

data class CreateDebitInput(
    val supplierName: String,
    val amount: Double,
    val description: String? = null,
    val dueDate: String? = null,
)

data class ParseVoiceRequest(val text: String)

data class ParseOcrRequest(val text: String)

data class ParsedTransaction(
    val intent: String,
    val partyName: String?,
    val amount: Double?,
    val currency: String,
    val dueDate: String?,
    val description: String?,
    val confidence: Double,
    val rawText: String,
)

data class ReminderItem(
    val id: String,
    val kind: String,
    val title: String,
    val amount: Double? = null,
    val dueDate: String,
    val isDone: Boolean,
)

data class CreateReminderRequest(val title: String, val dueDate: String)

data class AiInsight(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val severity: String,
    val confidence: Double,
    val validUntil: String?,
    val readAt: String?,
    val dismissedAt: String?,
    val createdAt: String,
)

data class SeasonalInsightItem(
    val eventId: String,
    val eventName: String,
    val eventDate: String,
    val daysAway: Int,
    val note: String,
    val estimate: SeasonalEstimate,
)

data class SeasonalEstimate(
    val hasHistoricalBasis: Boolean,
    val message: String,
    val lastYearAmount: Double? = null,
)

data class PriorityItem(
    val kind: String,
    val severity: String,
    val message: String,
    val amount: Double? = null,
    val dueDate: String? = null,
    val refId: String? = null,
)

data class FundingOpportunity(
    val id: String,
    val type: String,
    val title: String,
    val explanation: String,
    val estimatedRequirement: String?,
    val estimatedAvailableCash: String?,
    val estimatedGap: String?,
    val urgency: String,
    val confidence: Double,
    val signalScore: Double,
    val status: String,
    val createdAt: String,
)

data class LeadQualificationInput(
    val workingCapitalRequirement: Double? = null,
    val fundingRequirementMin: Double? = null,
    val fundingRequirementMax: Double? = null,
    val preferredCallbackTime: String? = null,
    val userIntent: String,
)

data class LoanLead(
    val id: String,
    val opportunityId: String?,
    val fundingReason: String,
    val aiDetectedReason: String,
    val leadScore: Double,
    val status: String,
    val createdAt: String,
)

enum class BusinessCategory(val apiValue: String, @StringRes val labelRes: Int) {
    TEXTILE("TEXTILE", R.string.category_textile),
    GROCERY("GROCERY", R.string.category_grocery),
    HARDWARE("HARDWARE", R.string.category_hardware),
    ELECTRICAL("ELECTRICAL", R.string.category_electrical),
    MOBILE_ACCESSORIES("MOBILE_ACCESSORIES", R.string.category_mobile_accessories),
    AUTO_PARTS("AUTO_PARTS", R.string.category_auto_parts),
    FURNITURE("FURNITURE", R.string.category_furniture),
    FOOTWEAR("FOOTWEAR", R.string.category_footwear),
    PHARMACY("PHARMACY", R.string.category_pharmacy),
    STATIONERY("STATIONERY", R.string.category_stationery),
    RESTAURANT_FOOD("RESTAURANT_FOOD", R.string.category_restaurant_food),
    BEAUTY_SALON("BEAUTY_SALON", R.string.category_beauty_salon),
    OTHER("OTHER", R.string.category_other),
}
