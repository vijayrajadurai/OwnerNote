package com.shopai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shopai.app.R
import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.DebitTransactionDetail
import com.shopai.app.data.model.parseMoney
import com.shopai.app.data.model.pendingAmount
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopAlertDialog
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.LedgerPending
import com.shopai.app.ui.theme.Success
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.LedgerEntryKind
import com.shopai.app.util.LedgerLine
import com.shopai.app.util.formatDisplayDate
import com.shopai.app.util.formatInr
import com.shopai.app.util.isExcessPayment
import kotlinx.coroutines.launch

@Composable
fun CreditTransactionCard(
    transaction: CreditTransactionDetail,
    onAddPayment: suspend (Double) -> Unit,
    onMarkPaid: suspend () -> Unit,
    onError: (Throwable) -> Unit,
) {
    TransactionCard(
        amount = parseMoney(transaction.amount),
        amountColor = LedgerCredit,
        status = transaction.status,
        description = transaction.description,
        dueDate = transaction.dueDate,
        pending = transaction.pendingAmount(),
        onAddPayment = onAddPayment,
        onMarkPaid = onMarkPaid,
        onError = onError,
    )
}

@Composable
fun DebitTransactionCard(
    transaction: DebitTransactionDetail,
    onAddPayment: suspend (Double) -> Unit,
    onMarkPaid: suspend () -> Unit,
    onError: (Throwable) -> Unit,
) {
    TransactionCard(
        amount = parseMoney(transaction.amount),
        amountColor = LedgerDebit,
        status = transaction.status,
        description = transaction.description,
        dueDate = transaction.dueDate,
        pending = transaction.pendingAmount(),
        onAddPayment = onAddPayment,
        onMarkPaid = onMarkPaid,
        onError = onError,
    )
}

@Composable
private fun TransactionCard(
    amount: Double,
    amountColor: Color,
    status: String,
    description: String?,
    dueDate: String?,
    pending: Double,
    onAddPayment: suspend (Double) -> Unit,
    onMarkPaid: suspend () -> Unit,
    onError: (Throwable) -> Unit,
) {
    var isPaying by remember { mutableStateOf(false) }
    var paymentAmount by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var confirmMarkPaid by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val enteredAmount = paymentAmount.toDoubleOrNull() ?: 0.0
    val excessPayment = isExcessPayment(enteredAmount, pending)
    val excessMessage = stringResource(R.string.party_payment_excess)

    ShopCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatInr(amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = amountColor,
            )
            Text(
                text = transactionStatusLabel(status),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (status == "PAID") Success else LedgerPending,
            )
        }
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (dueDate != null) {
            Text(
                text = stringResource(R.string.party_due_date, formatDisplayDate(dueDate)),
                style = MaterialTheme.typography.bodySmall,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (status != "PAID") {
            Text(
                text = stringResource(R.string.party_txn_pending, formatInr(pending)),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = amountColor,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (isPaying) {
                ShopTextField(
                    label = stringResource(R.string.party_payment_amount),
                    value = paymentAmount,
                    onValueChange = { paymentAmount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    error = if (excessPayment) excessMessage else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                PrimaryButton(
                    label = stringResource(R.string.party_confirm_payment),
                    loading = submitting,
                    enabled = enteredAmount > 0 && !excessPayment,
                    onClick = {
                        val value = paymentAmount.toDoubleOrNull() ?: return@PrimaryButton
                        if (isExcessPayment(value, pending)) return@PrimaryButton
                        scope.launch {
                            submitting = true
                            runCatching { onAddPayment(value) }
                                .onSuccess {
                                    isPaying = false
                                    paymentAmount = ""
                                }
                                .onFailure(onError)
                            submitting = false
                        }
                    },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.party_add_payment),
                        color = ShopAiThemeColors.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !submitting) { isPaying = true }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.party_mark_paid),
                        color = ShopAiThemeColors.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !submitting) { confirmMarkPaid = true }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (confirmMarkPaid) {
        ShopAlertDialog(
            onDismissRequest = { if (!submitting) confirmMarkPaid = false },
            title = { Text(stringResource(R.string.party_mark_paid_confirm_title)) },
            text = { Text(stringResource(R.string.party_mark_paid_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !submitting,
                    onClick = {
                        confirmMarkPaid = false
                        scope.launch {
                            submitting = true
                            runCatching { onMarkPaid() }.onFailure(onError)
                            submitting = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.party_mark_paid))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !submitting,
                    onClick = { confirmMarkPaid = false },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (submitting) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun LedgerHistorySection(lines: List<LedgerLine>) {
    if (lines.isEmpty()) return
    Text(
        text = stringResource(R.string.party_history_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = ShopAiThemeColors.onSurface,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
    lines.forEach { line ->
        ShopCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = line.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = ShopAiThemeColors.onSurface,
                    )
                    Text(
                        text = formatDisplayDate(line.dateIso),
                        style = MaterialTheme.typography.bodySmall,
                        color = ShopAiThemeColors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = ledgerAmountLabel(line),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = when (line.kind) {
                            LedgerEntryKind.CREDIT_GIVEN -> LedgerCredit
                            LedgerEntryKind.DEBIT_OWED -> LedgerDebit
                            LedgerEntryKind.PAYMENT_RECEIVED,
                            LedgerEntryKind.PAYMENT_MADE,
                            -> Success
                        },
                    )
                    Text(
                        text = stringResource(R.string.party_running_balance, formatInr(line.runningBalance)),
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgerPending,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ledgerAmountLabel(line: LedgerLine): String {
    val prefix = when (line.kind) {
        LedgerEntryKind.PAYMENT_RECEIVED,
        LedgerEntryKind.PAYMENT_MADE,
        -> "-"
        LedgerEntryKind.CREDIT_GIVEN,
        LedgerEntryKind.DEBIT_OWED,
        -> "+"
    }
    return "$prefix${formatInr(line.amount)}"
}

@Composable
private fun transactionStatusLabel(status: String): String = when (status) {
    "PAID" -> stringResource(R.string.party_status_paid)
    "PARTIALLY_PAID" -> stringResource(R.string.party_status_partial)
    else -> stringResource(R.string.party_status_pending)
}
