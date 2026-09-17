package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.model.Business
import com.shopai.app.data.model.BusinessHealth
import com.shopai.app.data.model.CashFlowSummary
import com.shopai.app.data.model.FundingOpportunity
import com.shopai.app.data.model.PriorityItem
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.components.EyebrowLabel
import com.shopai.app.ui.components.HomeBackHandler
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.navigation.Routes
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.Pressure
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.ui.theme.Warning
import com.shopai.app.util.DailyBriefVoice
import com.shopai.app.util.PriorityAmountTone
import com.shopai.app.util.formatInr
import com.shopai.app.util.toRowDisplay
import kotlinx.coroutines.launch

private object HomeTtsSession {
    var hasSpokenThisSession = false
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onNavigate: (String) -> Unit,
) {
    var business by remember { mutableStateOf<Business?>(null) }
    var cashFlow by remember { mutableStateOf<CashFlowSummary?>(null) }
    var health by remember { mutableStateOf<BusinessHealth?>(null) }
    var priorities by remember { mutableStateOf<List<PriorityItem>>(emptyList()) }
    var funding by remember { mutableStateOf<FundingOpportunity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val errorLoadDashboard = stringResource(R.string.error_load_dashboard)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                business = container.businessRepository.getMyBusiness()
                cashFlow = container.insightsRepository.getCashFlow()
                health = container.insightsRepository.getBusinessHealth()
                priorities = container.insightsRepository.getPriorities()
                funding = container.fundingRepository.getOpportunities().firstOrNull()
            }.onFailure {
                container.presentApiError(it, errorLoadDashboard, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    LaunchedEffect(loading, health, priorities) {
        if (loading || HomeTtsSession.hasSpokenThisSession) return@LaunchedEffect
        // Speak once per session after the first dashboard load finishes.
        HomeTtsSession.hasSpokenThisSession = true
        val voiceText = DailyBriefVoice.buildVoiceText(priorities, health)
        container.naturalTtsSpeaker.speakNatural(
            text = voiceText,
            languageCode = "ta-IN",
            fallbackText = voiceText,
            fallbackLanguage = "ta-IN",
        )
    }

    HomeBackHandler()

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    MainTabScaffold(activeTab = BottomNavTab.Home, onNavigate = onNavigate) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.labelSmall, color = ShopAiThemeColors.primary)
                    Text(
                        business?.businessName ?: stringResource(R.string.home_fallback_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = ShopAiThemeColors.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "🔔",
                    modifier = Modifier.clickable { onNavigate(Routes.Reminders) },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            Text(
                stringResource(R.string.home_greeting, business?.ownerName ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                color = ShopAiThemeColors.onSurfaceVariant,
            )

            if (loading) {
                CircularProgressIndicator(color = ShopAiThemeColors.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                if (error != null) Text(error!!, color = Danger)

                cashFlow?.let { cf ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(stringResource(R.string.home_receivable), formatInr(cf.pendingReceivables), Success, Modifier.weight(1f)) {
                            onNavigate(Routes.Customers)
                        }
                        StatTile(stringResource(R.string.home_payable), formatInr(cf.pendingPayables), Danger, Modifier.weight(1f)) {
                            onNavigate(Routes.Suppliers)
                        }
                    }
                }

                health?.let { h ->
                    ShopCard {
                        EyebrowLabel(stringResource(R.string.home_business_health))
                        Text(
                            healthLabel(h.status),
                            style = MaterialTheme.typography.titleMedium,
                            color = healthColor(h.status),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(h.explanation, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                if (priorities.isNotEmpty()) {
                    Text(stringResource(R.string.home_today_priorities), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                    priorities.take(3).forEach { item ->
                        val row = item.toRowDisplay()
                        ShopCard(modifier = Modifier.clickable {
                            when (item.kind) {
                                "REMINDER" -> onNavigate(Routes.Reminders)
                                "PAYMENT_DUE" -> onNavigate(Routes.Suppliers)
                                else -> onNavigate(Routes.Customers)
                            }
                        }) {
                            PriorityListRow(
                                name = row.name,
                                amount = row.amount,
                                tone = row.tone,
                            )
                        }
                    }
                }

                Text(stringResource(R.string.home_quick_actions), style = MaterialTheme.typography.labelSmall, color = ShopAiThemeColors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction("↑", stringResource(R.string.home_add_credit), Modifier.weight(1f)) { onNavigate(Routes.AddCredit) }
                    QuickAction("↓", stringResource(R.string.home_add_debit), Modifier.weight(1f)) { onNavigate(Routes.AddDebit) }
                }

                funding?.let { opp ->
                    ShopCard {
                        EyebrowLabel(stringResource(R.string.funding_opportunity))
                        Text(opp.title, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface, modifier = Modifier.padding(top = 4.dp))
                        Text(opp.explanation, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            container.fundingRepository.markInterested(opp.id)
                                            onNavigate(Routes.fundingQualification(opp.id))
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.funding_learn_more)) }
                            OutlinedButton(
                                onClick = {
                                    funding = null
                                    scope.launch { runCatching { container.fundingRepository.markNotNow(opp.id) } }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.funding_not_now)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorityListRow(
    name: String,
    amount: Double?,
    tone: PriorityAmountTone,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = ShopAiThemeColors.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (amount != null) {
            Text(
                text = formatInr(amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = when (tone) {
                    PriorityAmountTone.CREDIT -> Success
                    PriorityAmountTone.DEBIT -> Danger
                    PriorityAmountTone.NEUTRAL -> ShopAiThemeColors.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
        Text(label, color = ShopAiThemeColors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun QuickAction(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ShopAiThemeColors.primaryMutedBackground)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun healthColor(status: String) = when (status) {
    "STABLE" -> Success
    "WATCH" -> Warning
    "PRESSURE" -> Pressure
    "HIGH_PRESSURE" -> Danger
    else -> ShopAiThemeColors.onSurface
}

@Composable
private fun healthLabel(status: String): String = when (status) {
    "STABLE" -> stringResource(R.string.health_stable)
    "WATCH" -> stringResource(R.string.health_watch)
    "PRESSURE" -> stringResource(R.string.health_pressure)
    "HIGH_PRESSURE" -> stringResource(R.string.health_high_pressure)
    else -> status
}
