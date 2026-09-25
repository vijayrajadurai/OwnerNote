package com.shopai.app.ui.navigation

import android.net.Uri

object Routes {
    const val Splash = "splash"
    const val Login = "login"
    const val Otp = "otp"
    const val BusinessSetup = "business_setup"
    const val Home = "home"
    const val Discover = "discover"
    const val Customers = "customers"
    const val CustomerDetail = "customer_detail/{customerId}"
    const val Suppliers = "suppliers"
    const val SupplierDetail = "supplier_detail/{supplierId}"
    const val More = "more"
    const val AddCredit =
        "add_credit?customerId={customerId}&customerName={customerName}&customerPhone={customerPhone}"
    const val AddDebit =
        "add_debit?supplierId={supplierId}&supplierName={supplierName}&supplierPhone={supplierPhone}"
    const val VoiceEntry = "voice_entry"
    const val Reminders = "reminders"
    const val ReminderDetail = "reminder_detail/{reminderId}"
    const val AiInsights = "ai_insights"
    const val DailyCashNote = "daily_cash_note"
    const val Settings = "settings"
    const val Profile = "profile"
    const val Subscription = "subscription"
    const val FundingQualification = "funding_qualification/{opportunityId}"
    const val GroupBuying = "group_buying"
    const val GroupBuyingNew = "group_buying_new"
    const val GroupBuyingResult = "group_buying_result/{requestId}"
    const val Inventory = "inventory"
    const val LocalOffers = "local_offers"

    fun fundingQualification(opportunityId: String): String =
        "funding_qualification/${Uri.encode(opportunityId)}"

    fun groupBuyingResult(requestId: String): String =
        "group_buying_result/${Uri.encode(requestId)}"

    fun reminderDetail(reminderId: String): String = "reminder_detail/${Uri.encode(reminderId)}"

    fun customerDetail(customerId: String): String = "customer_detail/$customerId"

    fun supplierDetail(supplierId: String): String = "supplier_detail/$supplierId"

    fun addCredit(
        customerId: String? = null,
        customerName: String? = null,
        customerPhone: String? = null,
    ): String {
        val id = customerId ?: ""
        val name = customerName?.let(Uri::encode) ?: ""
        val phone = customerPhone?.let(Uri::encode) ?: ""
        return "add_credit?customerId=$id&customerName=$name&customerPhone=$phone"
    }

    fun addDebit(
        supplierId: String? = null,
        supplierName: String? = null,
        supplierPhone: String? = null,
    ): String {
        val id = supplierId ?: ""
        val name = supplierName?.let(Uri::encode) ?: ""
        val phone = supplierPhone?.let(Uri::encode) ?: ""
        return "add_debit?supplierId=$id&supplierName=$name&supplierPhone=$phone"
    }
}
