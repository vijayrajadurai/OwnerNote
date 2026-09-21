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
data class FirebaseLoginRequest(val idToken: String)
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
    val customerId: String? = null,
    val customerName: String? = null,
    val amount: Double,
    val description: String? = null,
    val dueDate: String? = null,
)

data class CreateDebitInput(
    val supplierId: String? = null,
    val supplierName: String? = null,
    val amount: Double,
    val description: String? = null,
    val dueDate: String? = null,
)

data class CreatePartyInput(
    val name: String,
    val phone: String? = null,
)

/** Response from POST /customers or POST /suppliers (no nested transactions). */
data class PartyRecord(
    val id: String,
    val name: String,
    val phone: String?,
)

data class AddPaymentInput(
    val amount: Double,
    val note: String? = null,
)

data class PaymentRecord(
    val id: String,
    val amount: String,
    val note: String?,
    val createdAt: String,
)

data class CreditTransactionDetail(
    val id: String,
    val amount: String,
    val paidAmount: String,
    val description: String?,
    val dueDate: String?,
    val status: String,
    val createdAt: String,
    val payments: List<PaymentRecord> = emptyList(),
)

data class DebitTransactionDetail(
    val id: String,
    val amount: String,
    val paidAmount: String,
    val description: String?,
    val dueDate: String?,
    val status: String,
    val createdAt: String,
    val payments: List<PaymentRecord> = emptyList(),
)

data class CustomerDetail(
    val id: String,
    val name: String,
    val phone: String?,
    val transactions: List<CreditTransactionDetail> = emptyList(),
)

data class SupplierDetail(
    val id: String,
    val name: String,
    val phone: String?,
    val transactions: List<DebitTransactionDetail> = emptyList(),
)

fun parseMoney(value: String): Double = value.toDoubleOrNull() ?: 0.0

fun CreditTransactionDetail.pendingAmount(): Double =
    parseMoney(amount) - parseMoney(paidAmount)

fun DebitTransactionDetail.pendingAmount(): Double =
    parseMoney(amount) - parseMoney(paidAmount)

data class ParseVoiceRequest(val text: String)

data class ParseOcrRequest(val text: String)

data class AskAnswer(
    val question: String,
    val matchedIntent: String,
    val answer: String,
    val confidence: Double,
)

data class AskBusinessRequest(val question: String)

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

data class DiscoverItem(
    val id: String,
    val type: String,
    val title: String,
    val summary: String,
    val detail: String? = null,
    val priceLabel: String? = null,
    val validUntil: String? = null,
    val sortOrder: Int = 0,
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
