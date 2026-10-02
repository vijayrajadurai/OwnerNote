package com.shopai.app.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.local.room.NoteTransactionEntity
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.STATUS_NEEDS_REVIEW
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.SYNC_FAILED
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.SYNC_PENDING
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.SYNC_SYNCED
import com.shopai.app.data.repository.NoteFilter
import com.shopai.app.data.repository.NoteLedger
import com.shopai.app.data.repository.NoteSort
import com.shopai.app.data.repository.PersonBalance
import com.shopai.app.data.repository.localDate
import com.shopai.app.data.repository.personKey
import com.shopai.app.data.repository.toLedgerLine
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Warning as WarningColor
import com.shopai.app.util.OcrBox
import com.shopai.app.util.ReviewIssue
import com.shopai.app.util.TxnDirection
import com.shopai.app.util.formatRupees
import kotlinx.coroutines.launch
import java.time.LocalDate

// ------------------------------------------------------------ row helpers

fun NoteTransactionEntity.direction(): TxnDirection = when (transactionType) {
    NoteTransactionEntity.TYPE_CREDIT -> TxnDirection.CREDIT
    NoteTransactionEntity.TYPE_DEBIT -> TxnDirection.DEBIT
    else -> TxnDirection.UNKNOWN
}

fun NoteTransactionEntity.box(): OcrBox? =
    if (boxLeft != null && boxTop != null && boxRight != null && boxBottom != null) OcrBox(boxLeft, boxTop, boxRight, boxBottom) else null

fun NoteTransactionEntity.toForm() = TxnForm(
    personName = personName,
    amount = amount.orEmpty(),
    date = localDate(),
    direction = direction(),
    description = description.orEmpty(),
    notes = notes.orEmpty(),
)

@Composable
private fun NoteTransactionEntity.toUi(): TxnRowUi = TxnRowUi(
    personName = personName,
    amount = amount?.toBigDecimalOrNull(),
    date = localDate(),
    direction = direction(),
    description = description,
    nameConfidence = nameConfidence.takeUnless { correctedByUser },
    amountConfidence = amountConfidence.takeUnless { correctedByUser },
    dateConfidence = dateConfidence.takeUnless { correctedByUser },
    issues = buildSet {
        if (reviewStatus == STATUS_NEEDS_REVIEW) {
            if (amount == null) add(ReviewIssue.AMOUNT_MISSING)
            if (direction() == TxnDirection.UNKNOWN) add(ReviewIssue.TYPE_UNKNOWN)
        }
        if (date == null) add(ReviewIssue.DATE_MISSING)
    },
    correctedByUser = correctedByUser,
    hasSource = sourceNoteId != null,
    syncLabel = when (ledgerSyncStatus) {
        SYNC_SYNCED -> stringResource(R.string.hw_sync_synced)
        SYNC_PENDING -> stringResource(R.string.hw_sync_pending)
        SYNC_FAILED -> stringResource(R.string.hw_sync_failed)
        else -> null
    },
)

/**
 * Edit / delete / view-source dialogs for saved rows, shared by the notes
 * list and the person screen.
 */
@Composable
private fun SavedRowDialogs(
    container: AppContainer,
    editing: NoteTransactionEntity?,
    deleting: NoteTransactionEntity?,
    viewing: NoteTransactionEntity?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repository = container.handwrittenNotesRepository
    editing?.let { row ->
        TransactionEditDialog(
            title = stringResource(R.string.hw_edit_title),
            initial = row.toForm(),
            originalLine = row.originalOcrText,
            ocrRead = row.originalPersonName?.let {
                stringResource(R.string.hw_ocr_read, it, row.originalAmountText ?: "—", row.originalDate ?: "—", row.originalType ?: "—")
            },
            extraNotice = if (row.ledgerSyncStatus == SYNC_SYNCED) stringResource(R.string.hw_edit_ledger_notice) else null,
            onSave = { form ->
                scope.launch {
                    repository.update(
                        row.copy(
                            personName = form.personName.trim(),
                            amount = form.parsedAmount!!.toPlainString(),
                            date = form.date?.toString(),
                            transactionType = when (form.direction) {
                                TxnDirection.CREDIT -> NoteTransactionEntity.TYPE_CREDIT
                                TxnDirection.DEBIT -> NoteTransactionEntity.TYPE_DEBIT
                                TxnDirection.UNKNOWN -> NoteTransactionEntity.TYPE_UNKNOWN
                            },
                            description = form.description.trim().ifBlank { null },
                            notes = form.notes.trim().ifBlank { null },
                            correctedByUser = true,
                            reviewStatus = NoteTransactionEntity.STATUS_USER_VERIFIED,
                            // A migrated/unknown row that now has a type can be added to the ledger.
                            ledgerSyncStatus = if (row.ledgerTransactionId == null && row.ledgerSyncStatus != NoteTransactionEntity.SYNC_NOT_SYNCED) SYNC_PENDING else row.ledgerSyncStatus,
                        ),
                    )
                    onClose()
                }
            },
            onDismiss = onClose,
        )
    }
    deleting?.let { row ->
        DeleteTransactionDialog(
            notice = stringResource(if (row.ledgerSyncStatus == SYNC_SYNCED) R.string.hw_delete_ledger_notice else R.string.hw_delete_keeps_image),
            onConfirm = {
                scope.launch {
                    repository.delete(row)
                    onClose()
                }
            },
            onDismiss = onClose,
        )
    }
    viewing?.let { row ->
        var imagePath by remember(row.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(row.id) {
            imagePath = row.sourceNoteId?.let { repository.getNote(it) }?.let { it.processedImagePath ?: it.originalImagePath }
        }
        ViewSourceDialog(imagePath = imagePath, box = row.box(), originalLine = row.originalOcrText, row = row.toUi(), onDismiss = onClose)
    }
}

private enum class NotesView { TRANSACTIONS, PEOPLE, DATES }

// ---------------------------------------------------------------- dashboard

/**
 * Handwritten Notes: Credit / Debit / Net summary, today / upcoming /
 * overdue, search, filters, sort, and person-wise / date-wise views.
 */
@Composable
fun HandwrittenNotesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onOpenPerson: (String) -> Unit,
    savedMessage: String?,
) {
    val repository = container.handwrittenNotesRepository
    val scope = rememberCoroutineScope()
    val rows by repository.observeTransactions().collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(NoteFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(NoteSort.DATE) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(NotesView.TRANSACTIONS) }
    var rangeFrom by remember { mutableStateOf<LocalDate?>(null) }
    var rangeTo by remember { mutableStateOf<LocalDate?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NoteTransactionEntity?>(null) }
    var deleting by remember { mutableStateOf<NoteTransactionEntity?>(null) }
    var viewing by remember { mutableStateOf<NoteTransactionEntity?>(null) }

    val all = rows.orEmpty()
    val summary = remember(all) { NoteLedger.summarize(all.map { it.toLedgerLine() }) }
    val range = if (rangeFrom != null && rangeTo != null && !rangeTo!!.isBefore(rangeFrom)) rangeFrom!!..rangeTo!! else null
    val shown = remember(all, query, filter, sort, descending, range) { NoteLedger.filterAndSort(all, query, filter, sort, descending, range) }
    val unsynced = all.count { it.ledgerSyncStatus == SYNC_FAILED || it.ledgerSyncStatus == SYNC_PENDING }

    DetailScaffold(title = stringResource(R.string.hw_title), onBack = onBack) { contentModifier ->
        LazyColumn(modifier = contentModifier.padding(horizontal = 16.dp)) {
            item {
                PrimaryButton(label = stringResource(R.string.hw_scan_add), onClick = onScan, modifier = Modifier.padding(bottom = 12.dp))
                savedMessage?.let { Text(it, color = ShopAiThemeColors.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)) }
                if (unsynced > 0) {
                    ShopCard(modifier = Modifier.padding(bottom = 8.dp)) {
                        Text(stringResource(R.string.hw_unsynced_banner, unsynced), color = WarningColor)
                        TextButton(onClick = {
                            scope.launch {
                                syncing = true
                                repository.syncPending()
                                syncing = false
                            }
                        }, enabled = !syncing) { Text(stringResource(R.string.hw_retry_sync)) }
                    }
                }
                SummaryCard(summary)
            }

            item {
                ShopTextField(stringResource(R.string.hw_search), query, { query = it }, placeholder = stringResource(R.string.hw_search_placeholder))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    NoteFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(filterLabel(f)) })
                    }
                }
                if (filter == NoteFilter.DATE_RANGE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FutureDatePickerField(stringResource(R.string.hw_from), rangeFrom, { rangeFrom = it }, modifier = Modifier.weight(1f), anyDate = true)
                        FutureDatePickerField(stringResource(R.string.hw_to), rangeTo, { rangeTo = it }, modifier = Modifier.weight(1f), anyDate = true)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    NotesView.entries.forEach { v ->
                        FilterChip(
                            selected = view == v,
                            onClick = { view = v },
                            label = {
                                Text(stringResource(when (v) {
                                    NotesView.TRANSACTIONS -> R.string.hw_view_transactions
                                    NotesView.PEOPLE -> R.string.hw_view_people
                                    NotesView.DATES -> R.string.hw_view_dates
                                }))
                            },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { sortMenu = true }) { Text(stringResource(R.string.hw_sort_by, sortLabel(sort))) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        NoteSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(sortLabel(s)) }, onClick = { sort = s; sortMenu = false })
                        }
                    }
                    IconButton(onClick = { descending = !descending }) {
                        Icon(if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, contentDescription = null)
                    }
                    Text(stringResource(R.string.hw_showing, shown.size, all.size), color = ShopAiThemeColors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }

            when {
                rows == null -> item { Text("…", modifier = Modifier.padding(16.dp)) }
                all.isEmpty() -> item {
                    Text(stringResource(R.string.hw_empty), color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                }
                shown.isEmpty() -> item {
                    Text(stringResource(R.string.hw_no_match), color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                }
                view == NotesView.TRANSACTIONS -> items(shown, key = { it.id }) { row ->
                    TransactionCard(
                        row = row.toUi(),
                        onEdit = { editing = row },
                        onDelete = { deleting = row },
                        onViewSource = { viewing = row },
                        modifier = Modifier.clickable { onOpenPerson(row.personName) },
                    )
                }
                view == NotesView.PEOPLE -> items(NoteLedger.byPerson(shown), key = { personKey(it.name) }) { person ->
                    PersonCard(person) { onOpenPerson(person.name) }
                }
                else -> NoteLedger.byDate(shown).forEach { (date, group) ->
                    item(key = "date-$date") {
                        Text(dateLabel(date), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                    items(group, key = { it.id }) { row ->
                        TransactionCard(row = row.toUi(), onEdit = { editing = row }, onDelete = { deleting = row }, onViewSource = { viewing = row })
                    }
                }
            }
            item { Row(modifier = Modifier.padding(bottom = 32.dp)) {} }
        }
    }

    SavedRowDialogs(container, editing, deleting, viewing) {
        editing = null
        deleting = null
        viewing = null
    }
}

@Composable
private fun SummaryCard(summary: com.shopai.app.data.repository.LedgerSummary) {
    ShopCard(modifier = Modifier.padding(bottom = 12.dp)) {
        SummaryLine(stringResource(R.string.hw_credit_to_receive), formatRupees(summary.totalCredit), LedgerCredit)
        SummaryLine(stringResource(R.string.hw_debit_to_pay), formatRupees(summary.totalDebit), LedgerDebit)
        SummaryLine(
            stringResource(R.string.hw_net_balance),
            formatRupees(summary.net),
            if (summary.net.signum() >= 0) LedgerCredit else LedgerDebit,
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            MiniStat(stringResource(R.string.hw_filter_today), summary.todayCount)
            MiniStat(stringResource(R.string.hw_filter_upcoming), summary.upcomingCount)
            MiniStat(stringResource(R.string.hw_filter_overdue), summary.overdueCount)
            MiniStat(stringResource(R.string.hw_filter_needs_review), summary.needsReview)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
    }
}

@Composable
private fun PersonCard(person: PersonBalance, onClick: () -> Unit) {
    ShopCard(modifier = Modifier.padding(bottom = 8.dp).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.hw_person_line, person.transactions.size, formatRupees(person.totalCredit), formatRupees(person.totalDebit)),
                    style = MaterialTheme.typography.bodySmall,
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
            }
            Text(formatRupees(person.net), fontWeight = FontWeight.Bold, color = if (person.net.signum() >= 0) LedgerCredit else LedgerDebit)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun filterLabel(filter: NoteFilter): String = stringResource(
    when (filter) {
        NoteFilter.ALL -> R.string.hw_filter_all
        NoteFilter.CREDIT -> R.string.hw_type_credit
        NoteFilter.DEBIT -> R.string.hw_type_debit
        NoteFilter.NEEDS_REVIEW -> R.string.hw_filter_needs_review
        NoteFilter.TODAY -> R.string.hw_filter_today
        NoteFilter.UPCOMING -> R.string.hw_filter_upcoming
        NoteFilter.OVERDUE -> R.string.hw_filter_overdue
        NoteFilter.DATE_RANGE -> R.string.hw_filter_range
    },
)

@Composable
private fun sortLabel(sort: NoteSort): String = stringResource(
    when (sort) {
        NoteSort.DATE -> R.string.hw_sort_date
        NoteSort.AMOUNT -> R.string.hw_sort_amount
        NoteSort.PERSON -> R.string.hw_sort_person
        NoteSort.TYPE -> R.string.hw_sort_type
    },
)

// ------------------------------------------------------------ person view

/** One person: Total Credit, Total Debit, Net Balance and every transaction. */
@Composable
fun HandwrittenPersonScreen(
    container: AppContainer,
    name: String,
    onBack: () -> Unit,
) {
    val rows by container.handwrittenNotesRepository.observeTransactions().collectAsState(initial = emptyList())
    val person = remember(rows, name) { NoteLedger.byPerson(rows).firstOrNull { personKey(it.name) == personKey(name) } }
    var editing by remember { mutableStateOf<NoteTransactionEntity?>(null) }
    var deleting by remember { mutableStateOf<NoteTransactionEntity?>(null) }
    var viewing by remember { mutableStateOf<NoteTransactionEntity?>(null) }

    DetailScaffold(title = person?.name ?: name, onBack = onBack) { contentModifier ->
        LazyColumn(modifier = contentModifier.padding(horizontal = 16.dp)) {
            item {
                ShopCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    SummaryLine(stringResource(R.string.hw_total_credit), formatRupees(person?.totalCredit ?: java.math.BigDecimal.ZERO), LedgerCredit)
                    SummaryLine(stringResource(R.string.hw_total_debit), formatRupees(person?.totalDebit ?: java.math.BigDecimal.ZERO), LedgerDebit)
                    val net = person?.net ?: java.math.BigDecimal.ZERO
                    SummaryLine(stringResource(R.string.hw_net_balance), formatRupees(net), if (net.signum() >= 0) LedgerCredit else LedgerDebit)
                    Text(
                        stringResource(if (net.signum() >= 0) R.string.hw_net_they_owe else R.string.hw_net_i_owe),
                        style = MaterialTheme.typography.bodySmall,
                        color = ShopAiThemeColors.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.hw_history), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                if (person == null) Text(stringResource(R.string.hw_person_empty), color = ShopAiThemeColors.onSurfaceVariant)
            }
            items(person?.transactions.orEmpty(), key = { it.id }) { row ->
                TransactionCard(row = row.toUi(), onEdit = { editing = row }, onDelete = { deleting = row }, onViewSource = { viewing = row })
            }
        }
    }

    SavedRowDialogs(container, editing, deleting, viewing) {
        editing = null
        deleting = null
        viewing = null
    }
}
