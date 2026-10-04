package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.CustomerDetail
import com.shopai.app.data.model.pendingAmount
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.buildReceivableLedger
import com.shopai.app.util.formatInr

@Composable
fun CustomerDetailScreen(
    container: AppContainer,
    customerId: String,
    onBack: () -> Unit,
    onAddCredit: (customerId: String, customerName: String) -> Unit,
    /** Customer / supplier master form (OwnerNote Books). */
    onEdit: (String) -> Unit = {},
    /** OwnerNote Books: a full invoice / bill and a payment for this party. */
    onNewBill: (String) -> Unit = {},
    onPayment: (String) -> Unit = {},
) {
    var customer by remember { mutableStateOf<CustomerDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var booksOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { booksOn = container.books.session() != null }
    // Back from editing the party: show the saved details.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        var first = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (first) first = false else reloadKey++ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val errorLoad = stringResource(R.string.customers_error)
    val paymentError = stringResource(R.string.party_payment_error)

    suspend fun reload() {
        loading = true
        error = null
        runCatching {
            customer = container.partyRepository.getCustomer(customerId)
        }.onFailure {
            container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
        }
        loading = false
    }

    LaunchedEffect(customerId, reloadKey) {
        reload()
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(
        title = customer?.name ?: stringResource(R.string.customers_title),
        onBack = onBack,
        actions = {
            if (booksOn) androidx.compose.material3.TextButton(onClick = { onEdit(customer?.id ?: customerId) }) { Text(stringResource(R.string.books_edit)) }
        },
    ) { contentModifier ->
        when {
            loading -> Box(
                modifier = contentModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ShopAiThemeColors.primary)
            }
            error != null -> Text(
                text = error!!,
                color = Danger,
                modifier = contentModifier.padding(24.dp),
            )
            customer != null -> {
                val detail = customer!!
                val totalPending = detail.transactions
                    .filter { it.status != "PAID" }
                    .sumOf { it.pendingAmount() }
                val ledger = buildReceivableLedger(detail.transactions)

                Column(
                    modifier = contentModifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (detail.phone != null) {
                        Text(
                            text = detail.phone,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                    }
                    com.shopai.app.ui.books.PartyMasterInfo(container, detail.id, reloadKey)
                    Text(
                        text = stringResource(R.string.party_total_pending, formatInr(totalPending)),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = LedgerCredit,
                    )
                    PrimaryButton(
                        label = stringResource(R.string.party_add_credit),
                        onClick = { onAddCredit(detail.id, detail.name) },
                    )
                    if (booksOn) {
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.shopai.app.ui.components.OutlinedButton(onClick = { onNewBill(detail.id) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bill_new_sale)) }
                            com.shopai.app.ui.components.OutlinedButton(onClick = { onPayment(detail.id) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bill_receive_payment)) }
                        }
                    }
                    Text(
                        text = stringResource(R.string.party_transactions_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ShopAiThemeColors.onSurface,
                    )
                    if (detail.transactions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.party_no_transactions),
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                    } else {
                        detail.transactions.forEach { txn ->
                            CreditTransactionCard(
                                transaction = txn,
                                onAddPayment = { amount ->
                                    val saved = container.transactionRepository.addCreditPayment(txn.id, amount)
                                    reloadKey++
                                    // Kai: what came in / went out and what is still pending (saved values).
                                    container.kaiBrain.forget()
                                    container.kaiBrain.announce(
                                        com.shopai.app.brain.KaiResponder.paymentRecorded(
                                            detail.name, amount,
                                            (saved.amount.toDoubleOrNull() ?: 0.0) - (saved.paidAmount.toDoubleOrNull() ?: 0.0),
                                            com.shopai.app.brain.Direction.RECEIVABLE, com.shopai.app.brain.KaiLanguage.forAppLocale(),
                                        ),
                                    )
                                },
                                onMarkPaid = {
                                    val before = (txn.amount.toDoubleOrNull() ?: 0.0) - (txn.paidAmount.toDoubleOrNull() ?: 0.0)
                                    container.transactionRepository.markCreditPaid(txn.id)
                                    reloadKey++
                                    container.kaiBrain.forget()
                                    container.kaiBrain.announce(
                                        com.shopai.app.brain.KaiResponder.paymentRecorded(
                                            detail.name, before, 0.0,
                                            com.shopai.app.brain.Direction.RECEIVABLE, com.shopai.app.brain.KaiLanguage.forAppLocale(),
                                        ),
                                    )
                                },
                                onError = { err ->
                                    container.presentApiError(
                                        err,
                                        paymentError,
                                        {},
                                        { msg -> alertError = msg },
                                    )
                                },
                            )
                        }
                    }
                    LedgerHistorySection(lines = ledger)
                }
            }
        }
    }
}
