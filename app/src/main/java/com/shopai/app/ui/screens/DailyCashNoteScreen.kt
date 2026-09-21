package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.model.DailyCashDayStatus
import com.shopai.app.data.model.DailyCashEntry
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.model.DailyCashPaymentMode
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.CashEntrySheet
import com.shopai.app.ui.components.CashEntrySheetMode
import com.shopai.app.ui.components.CashTotalsSheet
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.theme.Danger
import com.shopai.app.data.model.DailyCashTotals
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.util.computeDailyCashTotals
import com.shopai.app.util.dateKeyToLocalDate
import com.shopai.app.util.formatInr
import com.shopai.app.util.formatLocalDateForDisplay
import com.shopai.app.util.isFutureDateKey
import com.shopai.app.util.localDateKey
import com.shopai.app.util.shiftDateKey
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyCashNoteScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var dateKey by remember { mutableStateOf(localDateKey()) }
    var entries by remember { mutableStateOf<List<DailyCashEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sheetMode by remember { mutableStateOf<CashEntrySheetMode?>(null) }
    var showTotals by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DailyCashEntry?>(null) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var submitSuccess by remember { mutableStateOf(false) }
    var dayStatus by remember { mutableStateOf(DailyCashDayStatus.OPEN) }
    val scope = rememberCoroutineScope()
    val totals = remember(entries) { computeDailyCashTotals(entries) }
    val nextDisabled = isFutureDateKey(shiftDateKey(dateKey, 1))
    val todayKey = localDateKey()
    val canEdit = dayStatus == DailyCashDayStatus.OPEN && dateKey == todayKey
    val canCloseDay = canEdit && entries.isNotEmpty()
    val submitErrorMessage = stringResource(R.string.cash_note_submit_error)

    fun reload() {
        scope.launch {
            loading = true
            runCatching { container.dailyCashRepository.syncPendingReports() }
            dayStatus = container.dailyCashRepository.getDayStatus(dateKey)
            entries = container.dailyCashRepository.listDailyCashEntries(dateKey)
            loading = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(dateKey) {
        submitSuccess = false
        reload()
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(title = stringResource(R.string.cash_note_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateNavigatorRow(
                dateLabel = formatLocalDateForDisplay(dateKeyToLocalDate(dateKey)),
                nextDisabled = nextDisabled,
                onPrevious = { dateKey = shiftDateKey(dateKey, -1) },
                onNext = { if (!nextDisabled) dateKey = shiftDateKey(dateKey, 1) },
            )

            if (dayStatus == DailyCashDayStatus.SUBMITTED || submitSuccess) {
                Text(
                    text = stringResource(
                        if (submitSuccess) R.string.cash_note_submit_success else R.string.cash_note_day_submitted,
                    ),
                    color = Success,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (canEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoneyActionButton(
                        label = stringResource(R.string.cash_note_money_in),
                        tint = Success,
                        modifier = Modifier.weight(1f),
                        onClick = { sheetMode = CashEntrySheetMode.Add(dateKey, DailyCashEntryType.IN) },
                    )
                    MoneyActionButton(
                        label = stringResource(R.string.cash_note_money_out),
                        tint = Danger,
                        modifier = Modifier.weight(1f),
                        onClick = { sheetMode = CashEntrySheetMode.Add(dateKey, DailyCashEntryType.OUT) },
                    )
                }
            }

            if (canCloseDay) {
                PrimaryButton(
                    label = stringResource(R.string.cash_note_close_day),
                    loading = isSubmitting,
                    enabled = !isSubmitting,
                    onClick = { showCloseConfirm = true },
                )
            }

            CashDaySummary(
                totals = totals,
                onShowDetails = { showTotals = true },
            )

            if (loading) {
                CircularProgressIndicator(
                    color = ShopAiThemeColors.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 24.dp),
                )
            } else if (entries.isEmpty()) {
                Text(
                    text = if (dayStatus == DailyCashDayStatus.SUBMITTED) {
                        stringResource(R.string.cash_note_day_submitted)
                    } else {
                        stringResource(R.string.cash_note_list_empty)
                    },
                    color = ShopAiThemeColors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                CashEntriesGroupedList(
                    entries = entries,
                    canEdit = canEdit,
                    onEntryClick = { entry ->
                        if (canEdit) sheetMode = CashEntrySheetMode.Edit(entry)
                    },
                )
            }
        }
    }

    sheetMode?.let { mode ->
        CashEntrySheet(
            mode = mode,
            isSaving = isSaving,
            onSave = { type, amount, paymentMode, note ->
                scope.launch {
                    isSaving = true
                    runCatching {
                        when (mode) {
                            is CashEntrySheetMode.Add -> {
                                container.dailyCashRepository.createDailyCashEntry(
                                    date = mode.dateKey,
                                    type = type,
                                    amount = amount,
                                    paymentMode = paymentMode,
                                    note = note,
                                )
                            }
                            is CashEntrySheetMode.Edit -> {
                                container.dailyCashRepository.updateDailyCashEntry(
                                    id = mode.entry.id,
                                    type = type,
                                    amount = amount,
                                    paymentMode = paymentMode,
                                    note = note,
                                )
                            }
                        }
                        sheetMode = null
                        reload()
                    }
                    isSaving = false
                }
            },
            onDelete = if (mode is CashEntrySheetMode.Edit) {
                { deleteTarget = mode.entry }
            } else {
                null
            },
            onDismiss = { if (!isSaving) sheetMode = null },
        )
    }

    if (showTotals) {
        CashTotalsSheet(
            totals = totals,
            onDismiss = { showTotals = false },
        )
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isSubmitting) showCloseConfirm = false },
            title = { Text(stringResource(R.string.cash_note_close_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.cash_note_close_confirm_body,
                        entries.size,
                        formatInr(totals.totalIn),
                        formatInr(totals.totalOut),
                        formatInr(totals.net),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isSubmitting,
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            runCatching {
                                container.dailyCashRepository.submitDayReport(dateKey)
                            }.onSuccess {
                                showCloseConfirm = false
                                submitSuccess = true
                                reload()
                            }.onFailure {
                                container.presentApiError(
                                    it,
                                    submitErrorMessage,
                                    { msg -> alertError = msg },
                                    { msg -> alertError = msg },
                                )
                            }
                            isSubmitting = false
                        }
                    },
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp,
                            color = ShopAiThemeColors.primary,
                        )
                    } else {
                        Text(stringResource(R.string.cash_note_close_day))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSubmitting,
                    onClick = { showCloseConfirm = false },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.cash_note_delete_title)) },
            text = { Text(stringResource(R.string.cash_note_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            container.dailyCashRepository.deleteDailyCashEntry(entry.id)
                            deleteTarget = null
                            sheetMode = null
                            reload()
                            isSaving = false
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DateNavigatorRow(
    dateLabel: String,
    nextDisabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateArrow(enabled = true, label = "‹", onClick = onPrevious)
        Text(dateLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DateArrow(enabled = !nextDisabled, label = "›", onClick = onNext)
    }
}

@Composable
private fun DateArrow(enabled: Boolean, label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = MaterialTheme.typography.headlineMedium.fontSize,
        color = if (enabled) ShopAiThemeColors.onSurface else ShopAiThemeColors.onSurfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun MoneyActionButton(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            color = tint,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun CashDaySummary(
    totals: DailyCashTotals,
    onShowDetails: () -> Unit,
) {
    val netColor = when {
        totals.net > 0 -> Success
        totals.net < 0 -> Danger
        else -> ShopAiThemeColors.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onShowDetails)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryColumn(stringResource(R.string.cash_note_in), totals.totalIn, Success)
            SummaryColumn(stringResource(R.string.cash_note_out), totals.totalOut, Danger)
            SummaryColumn(stringResource(R.string.cash_note_net), totals.net, netColor)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${stringResource(R.string.cash_note_cash)} · ${formatInr(totals.cashNet)}",
                style = MaterialTheme.typography.bodySmall,
                color = ShopAiThemeColors.onSurfaceVariant,
            )
            Text(
                text = "${stringResource(R.string.cash_note_upi)} · ${formatInr(totals.upiNet)}",
                style = MaterialTheme.typography.bodySmall,
                color = ShopAiThemeColors.onSurfaceVariant,
            )
        }
        Text(
            text = "${stringResource(R.string.cash_note_totals_title)} →",
            color = ShopAiThemeColors.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SummaryColumn(label: String, amount: Double, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ShopAiThemeColors.onSurfaceVariant,
        )
        Text(
            text = formatInr(amount),
            fontWeight = FontWeight.Bold,
            color = color,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun CashEntriesGroupedList(
    entries: List<DailyCashEntry>,
    canEdit: Boolean,
    onEntryClick: (DailyCashEntry) -> Unit,
) {
    val cashEntries = entries.filter { it.paymentMode == DailyCashPaymentMode.CASH }
    val upiEntries = entries.filter { it.paymentMode == DailyCashPaymentMode.UPI }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (cashEntries.isNotEmpty()) {
            CashEntrySection(
                title = stringResource(R.string.cash_note_cash),
                entries = cashEntries,
                canEdit = canEdit,
                onEntryClick = onEntryClick,
            )
        }
        if (upiEntries.isNotEmpty()) {
            CashEntrySection(
                title = stringResource(R.string.cash_note_upi),
                entries = upiEntries,
                canEdit = canEdit,
                onEntryClick = onEntryClick,
            )
        }
    }
}

@Composable
private fun CashEntrySection(
    title: String,
    entries: List<DailyCashEntry>,
    canEdit: Boolean,
    onEntryClick: (DailyCashEntry) -> Unit,
) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        entries.forEachIndexed { index, entry ->
            CashEntryRow(
                entry = entry,
                enabled = canEdit,
                onClick = { onEntryClick(entry) },
            )
            if (index < entries.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
        }
    }
}

@Composable
private fun CashEntryRow(
    entry: DailyCashEntry,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val timeLabel = remember(entry.createdAt) { formatEntryTime(entry.createdAt) }
    val amountColor = if (entry.type == DailyCashEntryType.IN) Success else Danger
    val sign = if (entry.type == DailyCashEntryType.IN) "+" else "−"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = timeLabel,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurface,
            )
            if (!entry.note.isNullOrBlank()) {
                Text(
                    text = entry.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShopAiThemeColors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "$sign${formatInr(entry.amount)}",
                fontWeight = FontWeight.Bold,
                color = amountColor,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (enabled) {
                Text("›", color = ShopAiThemeColors.onSurfaceVariant)
            }
        }
    }
}

private fun formatEntryTime(iso: String): String {
    val formatter = DateTimeFormatter.ofPattern("hh:mm a")
    return Instant.parse(iso).atZone(ZoneId.systemDefault()).format(formatter)
}
