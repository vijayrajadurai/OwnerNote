package com.shopai.app.ui.navigation

object Routes {
    const val Splash = "splash"
    const val Login = "login"
    const val Otp = "otp"
    const val BusinessSetup = "business_setup"
    const val Home = "home"
    const val Discover = "discover"
    const val Customers = "customers"
    const val Suppliers = "suppliers"
    const val More = "more"
    const val AddCredit = "add_credit"
    const val AddDebit = "add_debit"
    const val VoiceEntry = "voice_entry"
    const val Reminders = "reminders"
    const val AiInsights = "ai_insights"
    const val Settings = "settings"
    const val Subscription = "subscription"
    const val FundingQualification = "funding_qualification/{opportunityId}"

    fun fundingQualification(opportunityId: String): String =
        "funding_qualification/$opportunityId"
}
