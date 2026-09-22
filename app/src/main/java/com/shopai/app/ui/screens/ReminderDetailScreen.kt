package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PaymentReminderCard
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.reminders.PaymentReminder
import com.shopai.app.ui.reminders.samplePaymentReminders
import com.shopai.app.ui.reminders.toPaymentReminder
import com.shopai.app.ui.reminders.toSortedPaymentReminders
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiTheme
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.launch

@Composable
fun ReminderDetailScreen(
    container: AppContainer,
    reminderId: String,
    onBack: () -> Unit,
    onOpenLedger: (kind: String) -> Unit,
) {
    var reminder by remember { mutableStateOf<PaymentReminder?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val errorLoad = stringResource(R.string.error_load_reminders)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                val fromApi = container.reminderRepository.listReminders()
                    .firstOrNull { it.id == reminderId }
                reminder = fromApi?.toPaymentReminder()
                    ?: emptyList<ReminderItem>()
                        .toSortedPaymentReminders(container.insightsRepository.getPriorities())
                        .firstOrNull { it.id == reminderId }
            }.onFailure {
                container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    LaunchedEffect(reminderId) { reload() }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(title = stringResource(R.string.reminder_details_title), onBack = onBack) { contentModifier ->
        ReminderDetailBody(
            modifier = contentModifier.padding(horizontal = 20.dp, vertical = 12.dp),
            loading = loading,
            error = error,
            reminder = reminder,
            onMarkPaid = {
                val item = reminder ?: return@ReminderDetailBody
                scope.launch {
                    runCatching {
                        container.reminderRepository.markDone(item.id)
                        onBack()
                    }.onFailure { err ->
                        container.presentApiError(
                            err,
                            errorLoad,
                            { msg -> error = msg },
                            { msg -> alertError = msg },
                        )
                    }
                }
            },
            onOpenLedger = { reminder?.let { onOpenLedger(it.kind) } },
        )
    }
}

@Composable
private fun ReminderDetailBody(
    loading: Boolean,
    error: String?,
    reminder: PaymentReminder?,
    onMarkPaid: () -> Unit,
    onOpenLedger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            loading -> CircularProgressIndicator(
                color = ShopAiThemeColors.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            error != null -> Text(error, color = Danger)
            reminder == null -> Text(
                text = stringResource(R.string.reminder_not_found),
                color = ShopAiThemeColors.onSurfaceVariant,
            )
            else -> {
                    PaymentReminderCard(
                        reminder = reminder,
                        onOpenDetails = {},
                        onMarkPaid = onMarkPaid,
                        showActions = false,
                    )
                Spacer(Modifier.height(4.dp))
                if (reminder.kind.equals("CUSTOM", ignoreCase = true)) {
                    PrimaryButton(
                        label = stringResource(R.string.reminder_mark_paid),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onMarkPaid,
                    )
                } else {
                    OutlinedButton(
                        onClick = onOpenLedger,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.reminder_open_ledger))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Reminder details")
@Composable
private fun ReminderDetailPreview() {
    ShopAiTheme {
        ReminderDetailBody(
            loading = false,
            error = null,
            reminder = samplePaymentReminders()[1],
            onMarkPaid = {},
            onOpenLedger = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}
