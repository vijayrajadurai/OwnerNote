package com.shopai.app.billing

/**
 * Configure Google Play Billing product IDs and legal URLs before release.
 * Billing is not wired yet — SubscriptionViewModel reads these placeholders only.
 */
object BillingConfig {
    const val MONTHLY_PRODUCT_ID = "shop_ai_premium_monthly"
    const val YEARLY_PRODUCT_ID = "shop_ai_premium_yearly"

    // TODO: Replace with production URLs before store release.
    const val PRIVACY_POLICY_URL = "https://example.com/privacy"
    const val TERMS_URL = "https://example.com/terms"

    const val IS_BILLING_ENABLED = false
}
