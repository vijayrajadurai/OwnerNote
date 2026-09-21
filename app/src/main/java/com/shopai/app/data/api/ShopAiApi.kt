package com.shopai.app.data.api

import com.shopai.app.data.model.AiInsight
import com.shopai.app.data.model.AskAnswer
import com.shopai.app.data.model.AskBusinessRequest
import com.shopai.app.data.model.ApiEnvelope
import com.shopai.app.data.model.AuthResponse
import com.shopai.app.data.model.Business
import com.shopai.app.data.model.BusinessHealth
import com.shopai.app.data.model.BusinessInput
import com.shopai.app.data.model.CashFlowSummary
import com.shopai.app.data.model.AddPaymentInput
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.CreatePartyInput
import com.shopai.app.data.model.PartyRecord
import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.CustomerDetail
import com.shopai.app.data.model.DebitTransactionDetail
import com.shopai.app.data.model.DiscoverItem
import com.shopai.app.data.model.SupplierDetail
import com.shopai.app.data.model.CreateReminderRequest
import com.shopai.app.data.model.DashboardSnapshot
import com.shopai.app.data.model.DailyCashReportResponse
import com.shopai.app.data.model.SubmitDailyCashReportRequest
import com.shopai.app.data.model.FundingOpportunity
import com.shopai.app.data.model.LeadQualificationInput
import com.shopai.app.data.model.LoanLead
import com.shopai.app.data.model.ParseOcrRequest
import com.shopai.app.data.model.ParseVoiceRequest
import com.shopai.app.data.model.ParsedTransaction
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.model.PriorityItem
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.data.model.SeasonalInsightItem
import com.shopai.app.data.model.FirebaseLoginRequest
import com.shopai.app.data.model.SendOtpRequest
import com.shopai.app.data.model.SendOtpResponse
import com.shopai.app.data.model.TestLoginRequest
import com.shopai.app.data.model.VerifyOtpRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface ShopAiApi {
    @POST("auth/send-otp")
    suspend fun sendOtp(@Body body: SendOtpRequest): ApiEnvelope<SendOtpResponse>

    @POST("auth/verify-otp")
    suspend fun verifyOtp(@Body body: VerifyOtpRequest): ApiEnvelope<AuthResponse>

    @POST("auth/firebase")
    suspend fun firebaseLogin(@Body body: FirebaseLoginRequest): ApiEnvelope<AuthResponse>

    @POST("auth/test-login")
    suspend fun testLogin(@Body body: TestLoginRequest): ApiEnvelope<AuthResponse>

    @GET("business")
    suspend fun getMyBusiness(): ApiEnvelope<Business?>

    @PUT("business")
    suspend fun saveBusiness(@Body body: BusinessInput): ApiEnvelope<Business>

    @GET("dashboard")
    suspend fun getDashboard(): ApiEnvelope<DashboardSnapshot>

    @GET("cashflow")
    suspend fun getCashFlow(): ApiEnvelope<CashFlowSummary>

    @GET("business-health")
    suspend fun getBusinessHealth(): ApiEnvelope<BusinessHealth>

    @GET("priorities")
    suspend fun getPriorities(): ApiEnvelope<List<PriorityItem>>

    @GET("ai-insights")
    suspend fun getAiInsights(): ApiEnvelope<List<AiInsight>>

    @POST("ai-insights/{id}/dismiss")
    suspend fun dismissInsight(@Path("id") id: String): ApiEnvelope<AiInsight>

    @GET("seasonal-insights")
    suspend fun getSeasonalInsights(): ApiEnvelope<List<SeasonalInsightItem>>

    @GET("discover")
    suspend fun getDiscoverFeed(): ApiEnvelope<List<DiscoverItem>>

    @POST("ask-my-business")
    suspend fun askMyBusiness(@Body body: AskBusinessRequest): ApiEnvelope<AskAnswer>

    @GET("customers")
    suspend fun getCustomers(): ApiEnvelope<List<PartySummary>>

    @GET("customers/{id}")
    suspend fun getCustomer(@Path("id") id: String): ApiEnvelope<CustomerDetail>

    @POST("customers")
    suspend fun createCustomer(@Body body: CreatePartyInput): ApiEnvelope<PartyRecord>

    @GET("suppliers")
    suspend fun getSuppliers(): ApiEnvelope<List<PartySummary>>

    @GET("suppliers/{id}")
    suspend fun getSupplier(@Path("id") id: String): ApiEnvelope<SupplierDetail>

    @POST("suppliers")
    suspend fun createSupplier(@Body body: CreatePartyInput): ApiEnvelope<PartyRecord>

    @POST("transactions/credit")
    suspend fun createCredit(@Body body: CreateCreditInput): ApiEnvelope<CreditTransactionDetail>

    @POST("transactions/credit/{id}/payments")
    suspend fun addCreditPayment(
        @Path("id") id: String,
        @Body body: AddPaymentInput,
    ): ApiEnvelope<CreditTransactionDetail>

    @POST("transactions/credit/{id}/mark-paid")
    suspend fun markCreditPaid(@Path("id") id: String): ApiEnvelope<CreditTransactionDetail>

    @POST("transactions/debit")
    suspend fun createDebit(@Body body: CreateDebitInput): ApiEnvelope<DebitTransactionDetail>

    @POST("transactions/debit/{id}/payments")
    suspend fun addDebitPayment(
        @Path("id") id: String,
        @Body body: AddPaymentInput,
    ): ApiEnvelope<DebitTransactionDetail>

    @POST("transactions/debit/{id}/mark-paid")
    suspend fun markDebitPaid(@Path("id") id: String): ApiEnvelope<DebitTransactionDetail>

    @POST("voice/parse")
    suspend fun parseVoice(@Body body: ParseVoiceRequest): ApiEnvelope<ParsedTransaction>

    @POST("ocr/parse")
    suspend fun parseOcr(@Body body: ParseOcrRequest): ApiEnvelope<ParsedTransaction>

    @GET("reminders")
    suspend fun listReminders(): ApiEnvelope<List<ReminderItem>>

    @POST("reminders")
    suspend fun createReminder(@Body body: CreateReminderRequest): ApiEnvelope<ReminderItem>

    @POST("reminders/{id}/done")
    suspend fun markReminderDone(@Path("id") id: String): ApiEnvelope<ReminderItem>

    @GET("funding-opportunities")
    suspend fun getFundingOpportunities(): ApiEnvelope<List<FundingOpportunity>>

    @GET("funding-opportunities/{id}")
    suspend fun getFundingOpportunity(@Path("id") id: String): ApiEnvelope<FundingOpportunity>

    @POST("funding-opportunities/{id}/interested")
    suspend fun markFundingInterested(@Path("id") id: String): ApiEnvelope<FundingOpportunity>

    @POST("funding-opportunities/{id}/not-now")
    suspend fun markFundingNotNow(@Path("id") id: String): ApiEnvelope<FundingOpportunity>

    @POST("funding-opportunities/{id}/create-lead")
    suspend fun createLead(
        @Path("id") id: String,
        @Body body: LeadQualificationInput,
    ): ApiEnvelope<LoanLead>

    @POST("daily-cash/reports")
    suspend fun submitDailyCashReport(@Body body: SubmitDailyCashReportRequest): ApiEnvelope<DailyCashReportResponse>
}
