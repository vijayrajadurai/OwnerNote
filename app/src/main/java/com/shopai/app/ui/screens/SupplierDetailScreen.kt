package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.shopai.app.data.model.SupplierDetail
import com.shopai.app.data.model.pendingAmount
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.buildPayableLedger
import com.shopai.app.util.formatInr

@Composable
fun SupplierDetailScreen(
    container: AppContainer,
    supplierId: String,
    onBack: () -> Unit,
    onAddDebit: (supplierId: String, supplierName: String) -> Unit,
) {
    var supplier by remember { mutableStateOf<SupplierDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val errorLoad = stringResource(R.string.suppliers_error)
    val paymentError = stringResource(R.string.party_payment_error)

    suspend fun reload() {
        loading = true
        error = null
        runCatching {
            supplier = container.partyRepository.getSupplier(supplierId)
        }.onFailure {
            container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
        }
        loading = false
    }

    LaunchedEffect(supplierId, reloadKey) {
        reload()
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(
        title = supplier?.name ?: stringResource(R.string.suppliers_title),
        onBack = onBack,
    ) { contentModifier ->
        when {
            loading -> CircularProgressIndicator(
                color = ShopAiThemeColors.primary,
                modifier = contentModifier.padding(24.dp),
            )
            error != null -> Text(
                text = error!!,
                color = Danger,
                modifier = contentModifier.padding(24.dp),
            )
            supplier != null -> {
                val detail = supplier!!
                val totalPending = detail.transactions
                    .filter { it.status != "PAID" }
                    .sumOf { it.pendingAmount() }
                val ledger = buildPayableLedger(detail.transactions)

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
                    Text(
                        text = stringResource(R.string.party_total_pending, formatInr(totalPending)),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = LedgerDebit,
                    )
                    PrimaryButton(
                        label = stringResource(R.string.party_add_debit),
                        onClick = { onAddDebit(detail.id, detail.name) },
                    )
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
                            DebitTransactionCard(
                                transaction = txn,
                                onAddPayment = { amount ->
                                    container.transactionRepository.addDebitPayment(txn.id, amount)
                                    reloadKey++
                                },
                                onMarkPaid = {
                                    container.transactionRepository.markDebitPaid(txn.id)
                                    reloadKey++
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
