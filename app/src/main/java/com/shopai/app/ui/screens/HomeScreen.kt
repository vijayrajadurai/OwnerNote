package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.Business
import com.shopai.app.data.model.BusinessHealth
import com.shopai.app.data.model.CashFlowSummary
import com.shopai.app.data.model.FundingOpportunity
import com.shopai.app.data.model.PriorityItem
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.data.model.TodayCashSummary
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.components.DashboardGreetingHeader
import com.shopai.app.ui.components.HomeBackHandler
import com.shopai.app.ui.components.HomePriorityList
// import com.shopai.app.ui.components.HomeCreditDebitActions
// import com.shopai.app.ui.components.TransactionEntryChooserDialog
// import com.shopai.app.ui.components.TransactionEntryType
import com.shopai.app.ui.components.ReminderCarousel
import com.shopai.app.ui.components.ShopCard
// import com.shopai.app.ui.components.TransactionEntryChooserDialog
// import com.shopai.app.ui.components.TransactionEntryType
import com.shopai.app.ui.components.VoiceFabBottomSpacer
import com.shopai.app.ui.navigation.Routes
import com.shopai.app.ui.reminders.ReminderUrgency
import com.shopai.app.ui.reminders.samplePaymentReminders
import com.shopai.app.ui.reminders.toSortedPaymentReminders
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.ShopAiTheme
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.DailyBriefVoice
// import com.shopai.app.util.normalizeIndianPhone
// import com.shopai.app.util.rememberContactPicker
import com.shopai.app.util.formatInr
import com.shopai.app.util.localDateKey
import kotlinx.coroutines.launch

private object HomeTtsSession {
    var hasSpokenThisSession = false
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onNavigate: (String) -> Unit,
    onAddCredit: (customerId: String?, customerName: String, customerPhone: String?) -> Unit,
    onAddDebit: (supplierId: String?, supplierName: String, supplierPhone: String?) -> Unit,
) {
    var business by remember { mutableStateOf<Business?>(null) }
    var cashFlow by remember { mutableStateOf<CashFlowSummary?>(null) }
    var health by remember { mutableStateOf<BusinessHealth?>(null) }
    var priorities by remember { mutableStateOf<List<PriorityItem>>(emptyList()) }
    var reminders by remember { mutableStateOf<List<ReminderItem>>(emptyList()) }
    var funding by remember { mutableStateOf<FundingOpportunity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    // var entryChooser by remember { mutableStateOf<TransactionEntryType?>(null) }
    var cashNoteSummary by remember { mutableStateOf<TodayCashSummary?>(null) }
    var showAllPriorities by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errorLoadDashboard = stringResource(R.string.error_load_dashboard)
    // val contactPickFailed = stringResource(R.string.contacts_pick_failed)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                business = container.businessRepository.getMyBusiness()
                cashFlow = container.insightsRepository.getCashFlow()
                health = container.insightsRepository.getBusinessHealth()
                priorities = container.insightsRepository.getPriorities()
                reminders = container.reminderRepository.listReminders()
                    .filter { !it.isDone }
                    .sortedBy { it.dueDate }
                funding = container.fundingRepository.getOpportunities().firstOrNull()
                runCatching { container.dailyCashRepository.syncPendingReports() }
                cashNoteSummary = container.dailyCashRepository.getTodayCashSummary(localDateKey())
            }.onFailure {
                container.presentApiError(it, errorLoadDashboard, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(loading, health, priorities) {
        if (loading || HomeTtsSession.hasSpokenThisSession) return@LaunchedEffect
        HomeTtsSession.hasSpokenThisSession = true
        val voiceText = DailyBriefVoice.buildVoiceText(priorities, health)
        container.naturalTtsSpeaker.speakNatural(
            text = voiceText,
            languageCode = "ta-IN",
            fallbackText = voiceText,
            fallbackLanguage = "ta-IN",
        )
    }

    /*
    val pickContactForCredit = rememberContactPicker(
        onContactPicked = { contact ->
            val phone = contact.phone?.let(::normalizeIndianPhone)
            onAddCredit(null, contact.name, phone)
        },
        onPickFailed = { alertError = contactPickFailed },
    )

    val pickContactForDebit = rememberContactPicker(
        onContactPicked = { contact ->
            val phone = contact.phone?.let(::normalizeIndianPhone)
            onAddDebit(null, contact.name, phone)
        },
        onPickFailed = { alertError = contactPickFailed },
    )

    entryChooser?.let { type ->
        TransactionEntryChooserDialog(
            type = type,
            onFromContact = {
                entryChooser = null
                when (type) {
                    TransactionEntryType.CREDIT -> pickContactForCredit()
                    TransactionEntryType.DEBIT -> pickContactForDebit()
                }
            },
            onManualEntry = {
                entryChooser = null
                when (type) {
                    TransactionEntryType.CREDIT -> onAddCredit(null, "", null)
                    TransactionEntryType.DEBIT -> onAddDebit(null, "", null)
                }
            },
            onDismiss = { entryChooser = null },
        )
    }
    */

    HomeBackHandler()
    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    val carouselItems = remember(reminders, priorities) {
        reminders.toSortedPaymentReminders(priorities)
    }
    val unreadReminders = carouselItems.count {
        it.urgency == ReminderUrgency.OVERDUE || it.urgency == ReminderUrgency.DUE_TODAY
    }

    MainTabScaffold(activeTab = BottomNavTab.Home, onNavigate = onNavigate) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DashboardGreetingHeader(
                ownerName = business?.ownerName.orEmpty(),
                unreadCount = unreadReminders,
                onNotificationsClick = { onNavigate(Routes.Reminders) },
            )

            ReminderCarousel(
                items = carouselItems,
                onOpenDetails = { reminder -> onNavigate(Routes.reminderDetail(reminder.id)) },
                onMarkPaid = { reminder ->
                    if (reminder.kind.equals("CUSTOM", ignoreCase = true)) {
                        scope.launch {
                            runCatching {
                                container.reminderRepository.markDone(reminder.id)
                                reload()
                            }
                        }
                    } else {
                        onNavigate(Routes.reminderDetail(reminder.id))
                    }
                },
            )

            DailyCashHomeCard(
                summary = cashNoteSummary,
                onClick = { onNavigate(Routes.DailyCashNote) },
            )

            if (loading) {
                CircularProgressIndicator(
                    color = ShopAiThemeColors.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 24.dp),
                )
            } else {
                if (error != null) {
                    Text(error!!, color = Danger)
                }

                cashFlow?.let { cf ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DashboardStatCard(
                            label = stringResource(R.string.home_receivable),
                            value = formatInr(cf.pendingReceivables),
                            accent = LedgerCredit,
                            arrowDown = true,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.Customers) },
                        )
                        DashboardStatCard(
                            label = stringResource(R.string.home_payable),
                            value = formatInr(cf.pendingPayables),
                            accent = LedgerDebit,
                            arrowDown = false,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.Suppliers) },
                        )
                    }
                }

                if (priorities.isNotEmpty()) {
                    HomeSectionHeader(
                        title = stringResource(R.string.home_today_priorities),
                        actionLabel = when {
                            priorities.size > 3 && !showAllPriorities -> stringResource(R.string.view_all)
                            priorities.size > 3 && showAllPriorities -> stringResource(R.string.show_less)
                            else -> null
                        },
                        onActionClick = if (priorities.size > 3) {
                            { showAllPriorities = !showAllPriorities }
                        } else {
                            null
                        },
                    )
                    ShopCard {
                        HomePriorityList(
                            items = if (showAllPriorities) priorities else priorities.take(3),
                            onItemClick = { item ->
                                when (item.kind) {
                                    "REMINDER" -> onNavigate(Routes.Reminders)
                                    "PAYMENT_DUE" -> onNavigate(Routes.Suppliers)
                                    else -> onNavigate(Routes.Customers)
                                }
                            },
                        )
                    }
                }

                /*
                HomeSectionHeader(title = stringResource(R.string.home_quick_actions))
                HomeCreditDebitActions(
                    onCreditClick = { entryChooser = TransactionEntryType.CREDIT },
                    onDebitClick = { entryChooser = TransactionEntryType.DEBIT },
                )
                */

                funding?.let { opp ->
                    ShopCard {
                        Text(
                            text = stringResource(R.string.funding_opportunity),
                            style = MaterialTheme.typography.labelSmall,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                        Text(
                            text = opp.title,
                            fontWeight = FontWeight.Bold,
                            color = ShopAiThemeColors.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = opp.explanation,
                            color = ShopAiThemeColors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
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

                VoiceFabBottomSpacer()
            }
        }
    }
}

@Composable
private fun DashboardStatCard(
    label: String,
    value: String,
    accent: Color,
    arrowDown: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(
            imageVector = if (arrowDown) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun DailyCashHomeCard(
    summary: TodayCashSummary?,
    onClick: () -> Unit,
) {
    val hasEntries = summary != null && summary.entryCount > 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F3D2E),
                            Color(0xFF1E6B4E),
                            Color(0xFF2E9F6E),
                        ),
                    ),
                )
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "₹",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.cash_note_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            )
                            Text(
                                text = stringResource(R.string.cash_note_home_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.82f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }

                if (hasEntries) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CashNoteStatChip(
                            label = stringResource(R.string.cash_note_in),
                            value = formatInr(summary!!.totalIn),
                            modifier = Modifier.weight(1f),
                        )
                        CashNoteStatChip(
                            label = stringResource(R.string.cash_note_out),
                            value = formatInr(summary.totalOut),
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.cash_note_home_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.88f),
                    )
                }

//                Box(
//                    modifier = Modifier
//                        .clip(RoundedCornerShape(999.dp))
//                        .background(Color.White.copy(alpha = 0.16f))
//                        .padding(horizontal = 14.dp, vertical = 8.dp),
//                ) {
//                    Text(
//                        text = stringResource(R.string.cash_note_home_cta),
//                        style = MaterialTheme.typography.labelLarge,
//                        fontWeight = FontWeight.Bold,
//                        color = Color.White,
//                    )
//                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = ShopAiThemeColors.onSurface,
        )
        if (actionLabel != null && onActionClick != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Primary,
                modifier = Modifier.clickable(onClick = onActionClick),
            )
        }
    }
}

@Composable
private fun CashNoteStatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Preview(showBackground = true, name = "Owner Note dashboard")
@Composable
private fun OwnerNoteDashboardPreview() {
    ShopAiTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DashboardGreetingHeader(
                ownerName = "Priya",
                unreadCount = 2,
                onNotificationsClick = {},
            )
            ReminderCarousel(
                items = samplePaymentReminders(),
                onOpenDetails = {},
                onMarkPaid = {},
            )
        }
    }
}

@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    name = "Owner Note dashboard dark",
)
@Composable
private fun OwnerNoteDashboardDarkPreview() {
    ShopAiTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DashboardGreetingHeader(
                ownerName = "Priya",
                unreadCount = 2,
                onNotificationsClick = {},
            )
            ReminderCarousel(
                items = samplePaymentReminders(),
                onOpenDetails = {},
                onMarkPaid = {},
            )
        }
    }
}
