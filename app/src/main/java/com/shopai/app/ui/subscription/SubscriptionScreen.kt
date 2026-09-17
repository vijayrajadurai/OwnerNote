package com.shopai.app.ui.subscription

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopai.app.R
import com.shopai.app.billing.BillingConfig
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard

@Composable
fun SubscriptionScreen(onBack: () -> Unit) {
    val viewModel: SubscriptionViewModel = viewModel(factory = SubscriptionViewModel.Factory())
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    DetailScaffold(title = stringResource(R.string.subscription), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.subscription_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state.status) {
                SubscriptionStatus.LOADING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(
                        stringResource(R.string.subscription_loading),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                SubscriptionStatus.ERROR -> {
                    Text(stringResource(R.string.something_went_wrong), color = MaterialTheme.colorScheme.error)
                    state.errorMessage?.let { Text(it) }
                    PrimaryButton(label = stringResource(R.string.try_again), onClick = { viewModel.load() })
                }
                SubscriptionStatus.UNAVAILABLE -> {
                    Text(stringResource(R.string.subscription_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PlanCards(state, enabled = false, onSubscribe = {})
                }
                SubscriptionStatus.ACTIVE -> {
                    StatusBadge(stringResource(R.string.status_active))
                    state.currentPlanLabel?.let {
                        Text(stringResource(R.string.current_plan) + ": $it", fontWeight = FontWeight.Bold)
                    }
                    PrimaryButton(label = stringResource(R.string.manage_subscription), onClick = { viewModel.manageSubscription() })
                }
                SubscriptionStatus.EXPIRED -> {
                    StatusBadge(stringResource(R.string.status_expired))
                    PlanCards(state, enabled = BillingConfig.IS_BILLING_ENABLED, onSubscribe = viewModel::subscribe)
                }
                SubscriptionStatus.AVAILABLE -> {
                    Text(
                        stringResource(R.string.current_plan) + ": " + stringResource(R.string.current_plan_free),
                        fontWeight = FontWeight.Bold,
                    )
                    PlanCards(state, enabled = BillingConfig.IS_BILLING_ENABLED, onSubscribe = viewModel::subscribe)
                }
            }

            OutlinedButton(
                onClick = { viewModel.restorePurchases() },
                enabled = !state.isRestoring,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isRestoring) stringResource(R.string.subscription_loading) else stringResource(R.string.restore_purchases))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.privacy_policy),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BillingConfig.PRIVACY_POLICY_URL)))
                    }
                    .padding(vertical = 4.dp),
            )
            Text(
                stringResource(R.string.terms_and_conditions),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BillingConfig.TERMS_URL)))
                    }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    ShopCard {
        Text(text, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PlanCards(
    state: SubscriptionUiState,
    enabled: Boolean,
    onSubscribe: (SubscriptionPlanType) -> Unit,
) {
    val benefits = listOf(
        stringResource(R.string.subscription_benefit_1),
        stringResource(R.string.subscription_benefit_2),
        stringResource(R.string.subscription_benefit_3),
    )
    ShopCard {
        Text(stringResource(R.string.subscription), fontWeight = FontWeight.Bold)
        benefits.forEach { benefit ->
            Text("• $benefit", modifier = Modifier.padding(top = 4.dp))
        }
    }
    state.plans.forEach { plan ->
        ShopCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when (plan.type) {
                        SubscriptionPlanType.MONTHLY -> stringResource(R.string.monthly_plan)
                        SubscriptionPlanType.YEARLY -> stringResource(R.string.yearly_plan)
                    },
                    fontWeight = FontWeight.Bold,
                )
                if (plan.isBestValue) {
                    Text(stringResource(R.string.best_value), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                plan.priceLabel ?: stringResource(R.string.price_not_configured),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            PrimaryButton(
                label = stringResource(R.string.subscribe),
                enabled = enabled,
                onClick = { onSubscribe(plan.type) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
