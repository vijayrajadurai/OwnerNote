package com.shopai.app.billing

/**
 * Configure Google Play Billing product IDs before release.
 * Billing is not wired yet — SubscriptionViewModel reads these placeholders only.
 * Privacy and terms are shown in PrivacyPolicyActivity.
 */
object BillingConfig {
    const val MONTHLY_PRODUCT_ID = "shop_ai_premium_monthly"
    const val YEARLY_PRODUCT_ID = "shop_ai_premium_yearly"

    const val IS_BILLING_ENABLED = false
}
