package com.shopai.app.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopai.app.billing.BillingConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SubscriptionPlanType { MONTHLY, YEARLY }

enum class SubscriptionStatus {
    LOADING,
    AVAILABLE,
    ACTIVE,
    EXPIRED,
    UNAVAILABLE,
    ERROR,
}

data class SubscriptionPlanUi(
    val type: SubscriptionPlanType,
    val productId: String,
    val priceLabel: String?,
    val isBestValue: Boolean,
)

data class SubscriptionUiState(
    val status: SubscriptionStatus = SubscriptionStatus.LOADING,
    val currentPlanLabel: String? = null,
    val plans: List<SubscriptionPlanUi> = emptyList(),
    val isRestoring: Boolean = false,
    val errorMessage: String? = null,
)

class SubscriptionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionUiState())
    val uiState: StateFlow<SubscriptionUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(status = SubscriptionStatus.LOADING, errorMessage = null) }
            delay(400)
            if (!BillingConfig.IS_BILLING_ENABLED) {
                _uiState.update {
                    it.copy(
                        status = SubscriptionStatus.UNAVAILABLE,
                        currentPlanLabel = null,
                        plans = placeholderPlans(),
                        errorMessage = null,
                    )
                }
                return@launch
            }
            // TODO: Query Google Play BillingClient for product details and active purchases.
            _uiState.update {
                it.copy(
                    status = SubscriptionStatus.AVAILABLE,
                    currentPlanLabel = null,
                    plans = placeholderPlans(),
                )
            }
        }
    }

    fun subscribe(plan: SubscriptionPlanType) {
        if (!BillingConfig.IS_BILLING_ENABLED) return
        // TODO: Launch billing flow; grant premium only after purchase validation.
    }

    fun restorePurchases() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, errorMessage = null) }
            delay(300)
            if (!BillingConfig.IS_BILLING_ENABLED) {
                _uiState.update {
                    it.copy(isRestoring = false, status = SubscriptionStatus.UNAVAILABLE)
                }
                return@launch
            }
            // TODO: Query existing purchases from BillingClient.
            _uiState.update { it.copy(isRestoring = false) }
        }
    }

    fun manageSubscription() {
        // TODO: Open Play Store subscription management URL for the active SKU.
    }

    private fun placeholderPlans(): List<SubscriptionPlanUi> = listOf(
        SubscriptionPlanUi(
            type = SubscriptionPlanType.MONTHLY,
            productId = BillingConfig.MONTHLY_PRODUCT_ID,
            priceLabel = null,
            isBestValue = false,
        ),
        SubscriptionPlanUi(
            type = SubscriptionPlanType.YEARLY,
            productId = BillingConfig.YEARLY_PRODUCT_ID,
            priceLabel = null,
            isBestValue = true,
        ),
    )

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SubscriptionViewModel() as T
    }
}
