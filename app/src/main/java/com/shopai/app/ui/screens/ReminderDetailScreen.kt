package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PaymentReminderCard
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.reminders.PaymentReminder
import com.shopai.app.ui.reminders.isCustomerCredit
import com.shopai.app.ui.reminders.samplePaymentReminders
import com.shopai.app.ui.reminders.toPaymentReminder
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiTheme
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.buildPaymentReminderShareText
import com.shopai.app.util.createPaymentReminderCardBitmap
import com.shopai.app.util.displayIndianPhone
import com.shopai.app.util.formatInr
import com.shopai.app.util.formatPaymentReminderSender
import com.shopai.app.util.formatReminderShareDate
import com.shopai.app.util.sharePaymentReminderToWhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReminderDetailScreen(
    container: AppContainer,
    reminderId: String,
    onBack: () -> Unit,
    onOpenLedger: (kind: String) -> Unit,
) {
    var reminder by remember { mutableStateOf<PaymentReminder?>(null) }
    var loading by remember { mutableStateOf(true) }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var senderPhone by remember { mutableStateOf("") }
    var senderName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val errorLoad = stringResource(R.string.error_load_reminders)
    val shareFailed = stringResource(R.string.reminder_share_failed)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                val fromApi = container.reminderRepository.listReminders()
                    .firstOrNull { it.id == reminderId }
                reminder = fromApi?.toPaymentReminder()
                val business = container.businessRepository.getMyBusiness()
                senderName = business?.businessName?.trim().orEmpty()
                    .ifBlank { business?.ownerName?.trim().orEmpty() }
                senderPhone = displayIndianPhone(business?.phone)
                    .ifBlank { displayIndianPhone(container.authRepository.getLoginPhone()) }
            }.onFailure {
                container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    LaunchedEffect(reminderId) { reload() }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(title = stringResource(R.string.reminder_details_title), onBack = onBack) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            ReminderDetailBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                loading = loading,
                error = error,
                reminder = reminder,
                onOpenLedger = { reminder?.let { onOpenLedger(it.kind) } },
                onShareWhatsApp = {
                    val item = reminder ?: return@ReminderDetailBody
                    scope.launch {
                        processing = true
                        runCatching {
                            val amountLabel = item.amount?.let { formatInr(it) } ?: "—"
                            val dueLabel = formatReminderShareDate(item.dueDateIso)
                            val sender = formatPaymentReminderSender(senderName, senderPhone)
                            val caption = buildPaymentReminderShareText(
                                item.partyName,
                                amountLabel,
                                dueLabel,
                                sender,
                            )
                            val bitmap = withContext(Dispatchers.Default) {
                                createPaymentReminderCardBitmap(context, amountLabel, dueLabel, sender)
                            }
                            sharePaymentReminderToWhatsApp(context, bitmap, caption)
                        }.onFailure {
                            alertError = shareFailed
                        }
                        processing = false
                    }
                },
            )
            if (processing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ShopAiThemeColors.primary)
                }
            }
        }
    }
}

@Composable
private fun ReminderDetailBody(
    loading: Boolean,
    error: String?,
    reminder: PaymentReminder?,
    onOpenLedger: () -> Unit,
    onShareWhatsApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when {
            loading -> CircularProgressIndicator(
                color = ShopAiThemeColors.primary,
                modifier = Modifier.align(Alignment.Center),
            )
            error != null -> Text(error, color = Danger, modifier = Modifier.align(Alignment.TopStart))
            reminder == null -> Text(
                text = stringResource(R.string.reminder_not_found),
                color = ShopAiThemeColors.onSurfaceVariant,
            )
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PaymentReminderCard(
                        reminder = reminder,
                        onOpenDetails = {},
                        onMarkPaid = {},
                        showActions = false,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (reminder.isCustomerCredit()) {
                        PrimaryButton(
                            label = stringResource(R.string.reminder_share_whatsapp),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onShareWhatsApp,
                        )
                    }
                    if (!reminder.kind.equals("CUSTOM", ignoreCase = true)) {
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
}

@Preview(showBackground = true, name = "Reminder details")
@Composable
private fun ReminderDetailPreview() {
    ShopAiTheme {
        ReminderDetailBody(
            loading = false,
            error = null,
            reminder = samplePaymentReminders()[1],
            onOpenLedger = {},
            onShareWhatsApp = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}
