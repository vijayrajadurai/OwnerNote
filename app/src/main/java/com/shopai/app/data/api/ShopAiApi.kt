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
import com.shopai.app.data.model.DailyCashOpeningRequest
import com.shopai.app.data.model.DailyCashOpeningResponse
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
import com.shopai.app.data.model.RegisterFcmTokenRequest
import com.shopai.app.data.model.UnregisterFcmTokenRequest
import com.shopai.app.data.model.DeviceTokenAck
import com.shopai.app.data.model.VerifyOtpRequest
import com.shopai.app.data.model.CreateGroupBuyingRequestInput
import com.shopai.app.data.model.GroupBuyingInboxItem
import com.shopai.app.data.model.GroupBuyingInviteRespondInput
import com.shopai.app.data.model.GroupBuyingInviteRespondResult
import com.shopai.app.data.model.GroupBuyingJoinResponse
import com.shopai.app.data.model.GroupBuyingMatchesResponse
import com.shopai.app.data.model.GroupBuyingRequest
import com.shopai.app.data.model.CreateInventoryProductInput
import com.shopai.app.data.model.UpdateInventoryProductInput
import com.shopai.app.data.model.StockChangeInput
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.model.InventoryMovement
import com.shopai.app.data.model.ProductIntelligenceDto
import com.shopai.app.data.model.InventoryIntelligenceSummaryDto
import com.shopai.app.data.model.CreateLocalOfferInput
import com.shopai.app.data.model.LocalOffer
import com.shopai.app.data.model.OfferWithDistanceDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

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

    @PUT("daily-cash/openings")
    suspend fun upsertDailyCashOpening(@Body body: DailyCashOpeningRequest): ApiEnvelope<DailyCashOpeningResponse>

    @GET("daily-cash/openings/{date}")
    suspend fun getDailyCashOpening(@Path("date") date: String): ApiEnvelope<DailyCashOpeningResponse>

    @POST("daily-cash/reports")
    suspend fun submitDailyCashReport(@Body body: SubmitDailyCashReportRequest): ApiEnvelope<DailyCashReportResponse>

    @GET("daily-cash/reports/{date}")
    suspend fun getDailyCashReport(@Path("date") date: String): ApiEnvelope<DailyCashReportResponse>

    @POST("devices/fcm")
    suspend fun registerFcmToken(@Body body: RegisterFcmTokenRequest): ApiEnvelope<DeviceTokenAck>

    @POST("devices/fcm/unregister")
    suspend fun unregisterFcmToken(@Body body: UnregisterFcmTokenRequest): ApiEnvelope<DeviceTokenAck>

    @POST("group-buying/requests")
    suspend fun createGroupBuyingRequest(@Body body: CreateGroupBuyingRequestInput): ApiEnvelope<GroupBuyingRequest>

    @GET("group-buying/requests")
    suspend fun listGroupBuyingRequests(): ApiEnvelope<List<GroupBuyingRequest>>

    @GET("group-buying/inbox")
    suspend fun listGroupBuyingInbox(): ApiEnvelope<List<GroupBuyingInboxItem>>

    @POST("group-buying/invites/{id}/respond")
    suspend fun respondGroupBuyingInvite(
        @Path("id") id: String,
        @Body body: GroupBuyingInviteRespondInput,
    ): ApiEnvelope<GroupBuyingInviteRespondResult>

    @GET("group-buying/requests/{id}/matches")
    suspend fun listGroupBuyingMatches(@Path("id") id: String): ApiEnvelope<GroupBuyingMatchesResponse>

    @POST("group-buying/requests/{id}/join")
    suspend fun joinGroupBuyingRequest(@Path("id") id: String): ApiEnvelope<GroupBuyingJoinResponse>

    @POST("group-buying/requests/{id}/cancel")
    suspend fun cancelGroupBuyingRequest(@Path("id") id: String): ApiEnvelope<GroupBuyingRequest>

    @GET("inventory/products")
    suspend fun listInventoryProducts(): ApiEnvelope<List<InventoryProduct>>

    @GET("inventory/products/low-stock")
    suspend fun listLowStockProducts(): ApiEnvelope<List<InventoryProduct>>

    @POST("inventory/products")
    suspend fun createInventoryProduct(@Body body: CreateInventoryProductInput): ApiEnvelope<InventoryProduct>

    @GET("inventory/products/{id}")
    suspend fun getInventoryProduct(@Path("id") id: String): ApiEnvelope<InventoryProduct>

    @PATCH("inventory/products/{id}")
    suspend fun updateInventoryProduct(
        @Path("id") id: String,
        @Body body: UpdateInventoryProductInput,
    ): ApiEnvelope<InventoryProduct>

    @POST("inventory/products/{id}/stock-in")
    suspend fun stockIn(@Path("id") id: String, @Body body: StockChangeInput): ApiEnvelope<InventoryProduct>

    @POST("inventory/products/{id}/stock-out")
    suspend fun stockOut(@Path("id") id: String, @Body body: StockChangeInput): ApiEnvelope<InventoryProduct>

    @GET("inventory/products/{id}/movements")
    suspend fun listInventoryMovements(@Path("id") id: String): ApiEnvelope<List<InventoryMovement>>

    @GET("inventory/products/{id}/intelligence")
    suspend fun getProductIntelligence(@Path("id") id: String): ApiEnvelope<ProductIntelligenceDto>

    @GET("inventory/intelligence/summary")
    suspend fun getInventoryIntelligenceSummary(): ApiEnvelope<InventoryIntelligenceSummaryDto>

    @GET("offers/mine")
    suspend fun listMyOffers(): ApiEnvelope<List<LocalOffer>>

    @GET("offers/nearby")
    suspend fun getNearbyOffers(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("radiusKm") radiusKm: Double,
        @Query("category") category: String? = null,
    ): ApiEnvelope<List<OfferWithDistanceDto>>

    @GET("offers/{id}")
    suspend fun getOffer(@Path("id") id: String): ApiEnvelope<LocalOffer>

    @POST("offers")
    suspend fun createOffer(@Body body: CreateLocalOfferInput): ApiEnvelope<LocalOffer>

    @POST("offers/{id}/end")
    suspend fun endOffer(@Path("id") id: String): ApiEnvelope<LocalOffer>

    @POST("offers/{id}/view")
    suspend fun recordOfferView(@Path("id") id: String): ApiEnvelope<LocalOffer>

    @POST("offers/{id}/directions")
    suspend fun recordOfferDirections(@Path("id") id: String): ApiEnvelope<LocalOffer>

    @POST("offers/{id}/call")
    suspend fun recordOfferCall(@Path("id") id: String): ApiEnvelope<LocalOffer>
}
