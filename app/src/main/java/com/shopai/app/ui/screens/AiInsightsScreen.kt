package com.shopai.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.model.AiInsight
import com.shopai.app.data.model.BusinessHealth
import com.shopai.app.data.model.CashFlowSummary
import com.shopai.app.data.model.SeasonalInsightItem
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.Pressure
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Warning
import com.shopai.app.util.formatInr
import kotlinx.coroutines.launch

private data class InsightCategory(val key: String, @StringRes val labelRes: Int)

private val categories = listOf(
    InsightCategory("ALL", R.string.filter_all),
    InsightCategory("CASH_FLOW", R.string.filter_cash_flow),
    InsightCategory("BUSINESS_HEALTH", R.string.filter_business_health),
    InsightCategory("CUSTOMERS", R.string.filter_customers),
    InsightCategory("SUPPLIERS", R.string.filter_suppliers),
    InsightCategory("SEASONAL", R.string.filter_seasonal),
)

private fun categoryOf(type: String): String = when (type) {
    "CASH_PRESSURE", "HISTORICAL_PATTERN", "PURCHASE_PATTERN" -> "CASH_FLOW"
    "COLLECTION_DUE" -> "CUSTOMERS"
    "PAYMENT_DUE" -> "SUPPLIERS"
    "SEASONAL_PREPARATION", "UPCOMING_NEED" -> "SEASONAL"
    else -> "BUSINESS_HEALTH"
}

@Composable
fun AiInsightsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var health by remember { mutableStateOf<BusinessHealth?>(null) }
    var cashFlow by remember { mutableStateOf<CashFlowSummary?>(null) }
    var insights by remember { mutableStateOf<List<AiInsight>>(emptyList()) }
    var seasonal by remember { mutableStateOf<List<SeasonalInsightItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf("ALL") }
    val scope = rememberCoroutineScope()
    val errorLoadInsights = stringResource(R.string.error_load_insights)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                health = container.insightsRepository.getBusinessHealth()
                cashFlow = container.insightsRepository.getCashFlow()
                insights = container.insightsRepository.getAiInsights()
                seasonal = container.insightsRepository.getSeasonalInsights()
            }.onFailure {
                container.presentApiError(it, errorLoadInsights, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val filtered = insights.filter { category == "ALL" || categoryOf(it.type) == category }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(title = stringResource(R.string.ai_insights_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = category == cat.key,
                        onClick = { category = cat.key },
                        label = { Text(stringResource(cat.labelRes)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                loading -> CircularProgressIndicator(color = ShopAiThemeColors.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
                error != null -> Text(error!!, color = Danger)
                else -> {
                    health?.let { h ->
                        ShopCard(modifier = Modifier.padding(bottom = 12.dp)) {
                            Text(
                                stringResource(R.string.home_business_health),
                                fontWeight = FontWeight.Bold,
                                color = ShopAiThemeColors.primary,
                            )
                            Text(h.explanation, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    cashFlow?.let { cf ->
                        ShopCard(modifier = Modifier.padding(bottom = 12.dp)) {
                            Text(stringResource(R.string.cash_flow), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.primary)
                            Text(
                                stringResource(R.string.net_position, formatInr(cf.netPosition)),
                                color = ShopAiThemeColors.onSurface,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                stringResource(R.string.collections_7d, formatInr(cf.next7Days.expectedCollections)),
                                color = ShopAiThemeColors.onSurfaceVariant,
                            )
                        }
                    }
                    filtered.forEach { insight ->
                        ShopCard(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(insight.title, fontWeight = FontWeight.Bold, color = severityColor(insight.severity))
                            Text(insight.description, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        insights = insights.filter { it.id != insight.id }
                                        runCatching { container.insightsRepository.dismissInsight(insight.id) }
                                    }
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text(stringResource(R.string.dismiss)) }
                        }
                    }
                    if (seasonal.isNotEmpty()) {
                        Text(
                            stringResource(R.string.seasonal),
                            fontWeight = FontWeight.Bold,
                            color = ShopAiThemeColors.primary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        seasonal.forEach { item ->
                            ShopCard(modifier = Modifier.padding(bottom = 8.dp)) {
                                Text(item.eventName, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                                Text(item.note, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                Text(
                                    stringResource(R.string.days_away, item.daysAway),
                                    color = ShopAiThemeColors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun severityColor(severity: String) = when (severity) {
    "HIGH_PRESSURE" -> Danger
    "PRESSURE" -> Pressure
    "WATCH" -> Warning
    else -> ShopAiThemeColors.onSurface
}
