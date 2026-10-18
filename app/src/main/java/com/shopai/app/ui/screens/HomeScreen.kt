package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.shopai.app.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
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
import com.shopai.app.ui.components.HomeQuickLinks
import com.shopai.app.ui.components.shopLocationLabel
// import com.shopai.app.ui.components.HomePriorityList
// import com.shopai.app.ui.components.HomeCreditDebitActions
// import com.shopai.app.ui.components.TransactionEntryChooserDialog
// import com.shopai.app.ui.components.TransactionEntryType
import com.shopai.app.ui.components.ReminderCarousel
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.books.BooksImportDialogs
import com.shopai.app.ui.components.glassSurface
import com.shopai.app.ui.components.isRedesignLight
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
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.KaiReply
import com.shopai.app.ui.kai.KaiBriefCard
import com.shopai.app.ui.kai.KaiState
import com.shopai.app.ui.kai.state
import com.shopai.app.util.DailyBriefVoice
import com.shopai.app.util.formatInr
import com.shopai.app.util.localDateKey
import com.shopai.app.util.parseIsoToLocalDate
import kotlinx.coroutines.launch

private object HomeTtsSession {
    var hasSpokenThisSession = false
}

@Composable
private fun homeHealthStatusLabel(status: String): String = stringResource(
    when (status.uppercase()) {
        "WATCH" -> R.string.health_watch
        "PRESSURE" -> R.string.health_pressure
        "HIGH_PRESSURE" -> R.string.health_high_pressure
        else -> R.string.health_stable
    },
)

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
    // The Kadai Bold home (owner, 18 Oct 2026): each side of the books with its people, and the stock alerts — read only.
    var customers by remember { mutableStateOf<List<com.shopai.app.data.model.PartySummary>>(emptyList()) }
    var suppliers by remember { mutableStateOf<List<com.shopai.app.data.model.PartySummary>>(emptyList()) }
    var lowStock by remember { mutableStateOf<List<com.shopai.app.brain.tools.StockFact>>(emptyList()) }
    var booksReady by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Boolean?>(null) }
    var profilePhotoPath by remember { mutableStateOf<String?>(null) }
    // Kai's Morning Work: how many important tasks today (read only; null until known).
    var morningCount by remember { mutableStateOf<Int?>(null) }
    // One-time move of the ledger into OwnerNote Books (null = not running / nothing to show).
    var importing by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<com.shopai.app.books.integration.ImportOutcome.Imported?>(null) }
    // var showAllPriorities by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errorLoadDashboard = stringResource(R.string.error_load_dashboard)
    // val contactPickFailed = stringResource(R.string.contacts_pick_failed)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            if (container.books.needsImport()) {
                importing = true
                val outcome = container.booksImporter.runIfNeeded()
                importing = false
                if (outcome is com.shopai.app.books.integration.ImportOutcome.Imported) {
                    imported = outcome
                    container.kaiBrain.forget()
                }
                // Failed (offline): nothing changed — the old ledger stays in use and the import retries next time.
            }
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
            // Each read on its own: one failing leaves the others shown.
            customers = runCatching { container.partyRepository.getCustomers() }.getOrDefault(customers)
            suppliers = runCatching { container.partyRepository.getSuppliers() }.getOrDefault(suppliers)
            lowStock = runCatching { container.kaiTools.lowStock() }.getOrNull() ?: lowStock
            booksReady = runCatching { container.books.session() != null }.getOrDefault(false)
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }
    // Signed in (after login / account switch): this owner's own morning notification, nobody else's.
    LaunchedEffect(Unit) { container.restoreMorningNotification() }

    LaunchedEffect(loading) {
        if (loading) return@LaunchedEffect
        morningCount = runCatching {
            container.morningSources.snapshot()?.let { snap ->
                container.morningWork.prepare(snap, greet = false)
                container.morningWork.state.plan?.openTasks?.size
            }
        }.getOrNull()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { profilePhotoPath = container.profilePhotoStore.getPhotoPath() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // KAI greets, speaks the daily brief (lip-synced), then settles small and idle.
    val kaiMouth by container.naturalTtsSpeaker.mouthLevel.collectAsState()
    val kaiSpeaking by container.naturalTtsSpeaker.speaking.collectAsState()
    var kaiBriefDone by remember { mutableStateOf(HomeTtsSession.hasSpokenThisSession) }
    // Kai's brief, from the Business Brain over the real ledger (never sample data).
    var kaiBrief by remember { mutableStateOf<KaiReply?>(null) }

    LaunchedEffect(loading) {
        if (loading) return@LaunchedEffect
        val brief = container.kaiBrain.briefing(KaiLanguage.forAppLocale())
        kaiBrief = brief
        if (HomeTtsSession.hasSpokenThisSession) return@LaunchedEffect
        HomeTtsSession.hasSpokenThisSession = true
        if (brief != null) {
            container.kaiBrain.say(brief) { kaiBriefDone = true }
        } else {
            // Ledger unreachable: the previous short brief from priorities.
            val voiceText = DailyBriefVoice.buildVoiceText(priorities, health)
            container.naturalTtsSpeaker.speakNatural(
                text = voiceText,
                languageCode = "ta-IN",
                fallbackText = voiceText,
                fallbackLanguage = "ta-IN",
                onDone = { kaiBriefDone = true },
            )
        }
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
    BooksImportDialogs(importing = importing, imported = imported, onDismiss = { imported = null })

    val carouselItems = remember(reminders) {
        reminders.toSortedPaymentReminders()
    }
    val unreadReminders = carouselItems.count {
        it.urgency == ReminderUrgency.OVERDUE || it.urgency == ReminderUrgency.DUE_TODAY
    }

    val today = remember { java.time.LocalDate.now() }
    val toShop = remember(customers, today) { com.shopai.app.brain.home.HomeMoney.side(customers, today) }
    val toPay = remember(suppliers, today) { com.shopai.app.brain.home.HomeMoney.side(suppliers, today) }
    val context = com.shopai.app.ui.home.homeContext()
    val shopName = business?.businessName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_fallback_name)
    val dayLabel = remember(today) { today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMM", java.util.Locale.ENGLISH)) }
    fun openParty(p: com.shopai.app.brain.home.HomeParty, customer: Boolean) =
        onNavigate(if (customer) Routes.customerDetail(p.id) else Routes.supplierDetail(p.id))
    fun pay(p: com.shopai.app.brain.home.HomeParty) = onNavigate(if (booksReady) Routes.payment("OUT", p.id) else Routes.supplierDetail(p.id))
    MainTabScaffold(
        activeTab = BottomNavTab.Home,
        onNavigate = onNavigate,
        padContent = false,
        applyTopInset = false,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            com.shopai.app.ui.home.HomeBlobBackground(Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            ) {
                com.shopai.app.ui.home.HomeHeader(shopName, dayLabel, unreadReminders,
                    onBell = { onNavigate(Routes.Reminders) }, onProfile = { onNavigate(Routes.Profile) })
                // Kai standing, as in Kai chat: greets, speaks the brief (mouth moving), then rests with today's line.
                com.shopai.app.ui.home.KaiHero(
                    title = stringResource(R.string.home_vanakkam),
                    line = when {
                        kaiBrief != null -> kaiBrief!!.display
                        toShop.late + toShop.today > 0 || toPay.late + toPay.today > 0 ->
                            stringResource(R.string.home_today_line, formatInr(toShop.late + toShop.today), formatInr(toPay.late + toPay.today))
                        else -> stringResource(R.string.home_today_line_clear)
                    },
                    state = when {
                        kaiBriefDone -> KaiState.IDLE
                        kaiSpeaking -> KaiState.SPEAKING
                        else -> KaiState.GREETING
                    },
                    mouthLevel = if (kaiBriefDone) 0f else kaiMouth,
                    speakingAs = kaiBrief?.mood?.state(),
                    askLabel = stringResource(R.string.home_ask_kai),
                    onAsk = { onNavigate(Routes.KaiChat) },
                )
                if (loading && customers.isEmpty() && suppliers.isEmpty()) {
                    CircularProgressIndicator(
                        color = com.shopai.app.ui.home.HomeColors.Leaf,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp),
                    )
                }
                error?.let { Text(it, color = Danger, modifier = Modifier.padding(bottom = 8.dp)) }
                // Vaanganum (green) and Kudukkanum (red), side by side, the same height.
                Row(
                    modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    com.shopai.app.ui.home.MoneyTile(
                        stringResource(R.string.home_vaanganum), stringResource(R.string.home_customers_n, toShop.count),
                        toShop, toShop = true, onClick = { sheet = true }, modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    com.shopai.app.ui.home.MoneyTile(
                        stringResource(R.string.home_kudukkanum), stringResource(R.string.home_suppliers_n, toPay.count),
                        toPay, toShop = false, onClick = { sheet = false }, modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                com.shopai.app.ui.home.NetCard(toShop, toPay)
                Spacer(Modifier.height(10.dp))
                com.shopai.app.ui.home.MorningTile(morningCount) { onNavigate(Routes.morningWork()) }
                // Today's collections: Call / WhatsApp reminder.
                if (toShop.todayParties.isNotEmpty()) {
                    com.shopai.app.ui.home.HomeSection(stringResource(R.string.home_collect_today), stringResource(R.string.home_all)) { sheet = true }
                    com.shopai.app.ui.home.CollectTodayRow(
                        toShop.todayParties,
                        onOpen = { openParty(it, true) },
                        onCall = { com.shopai.app.ui.home.callParty(context, it) },
                        onRemind = { com.shopai.app.ui.home.remindOnWhatsApp(context, it, shopName) },
                    )
                }
                // Late customers: a reminder each.
                if (toShop.lateParties.isNotEmpty()) {
                    com.shopai.app.ui.home.HomeSection(stringResource(R.string.home_late_people), stringResource(R.string.home_all)) { sheet = true }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        toShop.lateParties.take(3).forEach { p ->
                            com.shopai.app.ui.home.ActionRow(
                                p, stringResource(R.string.home_days_late, p.lateDays) + " · " + formatInr(p.amount),
                                com.shopai.app.ui.home.HomeColors.Kum, stringResource(R.string.home_remind),
                                Icons.AutoMirrored.Filled.Chat, green = true,
                                onOpen = { openParty(p, true) }, onAction = { com.shopai.app.ui.home.remindOnWhatsApp(context, p, shopName) },
                            )
                        }
                    }
                }
                // What the shop owes today (and late): Pay.
                val payNow = toPay.lateParties + toPay.todayParties
                if (payNow.isNotEmpty()) {
                    com.shopai.app.ui.home.HomeSection(stringResource(R.string.home_pay_today), stringResource(R.string.home_all)) { sheet = false }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        payNow.take(3).forEach { p ->
                            val late = p.whenDue == com.shopai.app.brain.home.DueWhen.LATE
                            com.shopai.app.ui.home.ActionRow(
                                p,
                                (if (late) stringResource(R.string.home_days_late, p.lateDays) else stringResource(R.string.home_today)) + " · " + formatInr(p.amount),
                                if (late) com.shopai.app.ui.home.HomeColors.Kum else com.shopai.app.ui.home.HomeColors.Soft,
                                stringResource(R.string.home_pay), Icons.Filled.ArrowUpward, green = false,
                                onOpen = { openParty(p, false) }, onAction = { pay(p) },
                            )
                        }
                    }
                }
                com.shopai.app.ui.home.HomeSection(stringResource(R.string.home_quick), stringResource(R.string.home_see_all)) { onNavigate(Routes.More) }
                com.shopai.app.ui.home.QuickActionsRow(
                    onCredit = { onAddCredit(null, "", null) },
                    onPayment = { onNavigate(if (booksReady) Routes.payment("IN") else Routes.Customers) },
                    onScan = { onNavigate(Routes.VoiceEntryScan) },
                    onStock = { onNavigate(Routes.Inventory) },
                )
                Spacer(Modifier.height(12.dp))
                val lowCount = lowStock.count { it.qty.signum() > 0 }
                val outCount = lowStock.count { it.qty.signum() <= 0 }
                com.shopai.app.ui.home.StockCashTiles(
                    stockTitle = if (lowStock.isEmpty()) stringResource(R.string.home_stock_ok) else stringResource(R.string.home_stock_alerts, lowCount, outCount),
                    stockSub = if (lowStock.isEmpty()) stringResource(R.string.home_stock_none) else lowStock.take(3).joinToString(", ") { it.name },
                    cashIn = cashNoteSummary?.totalIn ?: 0.0,
                    cashOut = cashNoteSummary?.totalOut ?: 0.0,
                    onStock = { onNavigate(Routes.Inventory) },
                    onCash = { onNavigate(Routes.DailyCashNote) },
                )
                Spacer(Modifier.height(10.dp))
                com.shopai.app.ui.home.AskKaiBar(
                    stringResource(R.string.kai_chat_entry_body),
                    onAsk = { onNavigate(Routes.KaiChat) }, onMic = { onNavigate(Routes.VoiceEntry) },
                )
                Spacer(Modifier.height(12.dp))
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
    sheet?.let { customersSide ->
        com.shopai.app.ui.home.MoneySheet(
            title = stringResource(if (customersSide) R.string.home_vaanganum else R.string.home_kudukkanum),
            side = if (customersSide) toShop else toPay,
            toShop = customersSide,
            onDismiss = { sheet = null },
            onOpen = { p ->
                sheet = null
                openParty(p, customersSide)
            },
            onAction = { p ->
                if (customersSide) {
                    com.shopai.app.ui.home.remindOnWhatsApp(context, p, shopName)
                } else {
                    sheet = null
                    pay(p)
                }
            },
        )
    }
}

@Composable
private fun HomeAskKaiCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x1A1E6B4E), spotColor = Color(0x1A1E6B4E))
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.kai_chat_entry_title),
                fontWeight = FontWeight.Bold,
                color = com.shopai.app.ui.theme.TextPrimary,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.kai_chat_entry_body),
                style = MaterialTheme.typography.bodySmall,
                color = com.shopai.app.ui.theme.TextSecondary,
                maxLines = 3,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun HomePaymentReminderCard(
    items: List<com.shopai.app.ui.reminders.PaymentReminder>,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x1A1E6B4E), spotColor = Color(0x1A1E6B4E))
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.kai_joyful),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(64.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                val amount = item.amount?.let { formatInr(it) }.orEmpty()
                val date = formatHomeReminderDate(item.dueDateIso)
                Text(
                    text = if (amount.isNotBlank()) "$amount — $date" else "${item.partyName} — $date",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = com.shopai.app.ui.theme.TextPrimary,
                    maxLines = 1,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = com.shopai.app.ui.theme.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun formatHomeReminderDate(iso: String): String {
    val date = parseIsoToLocalDate(iso) ?: return iso
    val day = date.dayOfMonth
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    val month = date.month.name.lowercase().replaceFirstChar { it.titlecase() }
    return "$month ${day}$suffix"
}

@Composable
private fun HomeGradientHeader(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (view.isInEditMode) {
                    Modifier
                } else {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                    )
                },
            )
            .padding(top = 10.dp, bottom = 8.dp),
    ) {
        content()
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
            .glassSurface()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (arrowDown) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
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
    val totalIn = summary?.totalIn ?: 0.0
    val totalOut = summary?.totalOut ?: 0.0
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
                            Color(0xFF075C45),
                            Color(0xFF16845F),
                        ),
                    ),
                ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(140.dp)
                    .offset(x = 36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    Column(modifier = Modifier.weight(1f)) {
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
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CashNoteStatChip(
                        label = stringResource(R.string.cash_note_in),
                        value = formatInr(totalIn),
                        modifier = Modifier.weight(1f),
                    )
                    CashNoteStatChip(
                        label = stringResource(R.string.cash_note_out),
                        value = formatInr(totalOut),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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

@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "Owner Note dashboard")
@Composable
private fun OwnerNoteDashboardPreview() {
    ShopAiTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            HomeGradientHeader {
                DashboardGreetingHeader(
                    shopName = "Anbu Super Mart",
                    location = "Tambaram, Chennai",
                    unreadCount = 2,
                    onNotificationsClick = {},
                    onGradient = false,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                /*
                ReminderCarousel(
                    items = samplePaymentReminders(),
                    onOpenDetails = {},
                    onMarkPaid = {},
                    onHeader = true,
                )
                */
            }
        }
    }
}

@Preview(
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    name = "Owner Note dashboard dark",
)
@Composable
private fun OwnerNoteDashboardDarkPreview() {
    ShopAiTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            HomeGradientHeader {
                DashboardGreetingHeader(
                    shopName = "Anbu Super Mart",
                    location = "Tambaram, Chennai",
                    unreadCount = 2,
                    onNotificationsClick = {},
                    onGradient = false,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                /*
                ReminderCarousel(
                    items = samplePaymentReminders(),
                    onOpenDetails = {},
                    onMarkPaid = {},
                    onHeader = true,
                )
                */
            }
        }
    }
}
