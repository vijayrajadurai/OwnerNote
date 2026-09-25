package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.CreatePartyInput
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.SaveTransactionConfirmDialog
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.components.TransactionSaveType
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.exceedsMaxLedgerAmount
import com.shopai.app.util.localDateToIsoInstant
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun AddDebitScreen(
    container: AppContainer,
    prefillSupplierId: String? = null,
    prefillSupplierName: String? = null,
    prefillSupplierPhone: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val lockedToSupplier = !prefillSupplierName.isNullOrBlank() || !prefillSupplierId.isNullOrBlank()
    var supplierName by remember { mutableStateOf(prefillSupplierName ?: "") }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val errorSaveTransaction = stringResource(R.string.error_save_transaction)
    val enteredAmount = amount.toDoubleOrNull() ?: 0.0
    val amountTooLarge = exceedsMaxLedgerAmount(enteredAmount)
    val amountError = if (amountTooLarge) stringResource(R.string.amount_max_one_crore) else null
    val isValid = (lockedToSupplier || supplierName.trim().length >= 2) &&
        enteredAmount > 0 &&
        !amountTooLarge &&
        description.trim().isNotEmpty() &&
        dueDate != null

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(
        title = stringResource(R.string.add_debit_title),
        onBack = onBack,
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.add_debit_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            ShopTextField(
                stringResource(R.string.supplier_name),
                supplierName,
                { if (!lockedToSupplier) supplierName = it },
            )
            if (!prefillSupplierPhone.isNullOrBlank()) {
                ShopTextField(
                    stringResource(R.string.login_phone_label),
                    prefillSupplierPhone,
                    {},
                )
            }
            ShopTextField(
                stringResource(R.string.amount_label),
                amount,
                { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                error = amountError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            ShopTextField(
                stringResource(R.string.note_label),
                description,
                { description = it },
            )
            FutureDatePickerField(
                label = stringResource(R.string.due_date_label),
                selectedDate = dueDate,
                onDateSelected = { dueDate = it },
                placeholder = stringResource(R.string.due_date_placeholder),
                error = error,
                allowEmpty = false,
            )

            PrimaryButton(
                label = stringResource(R.string.save),
                loading = loading,
                enabled = isValid,
                onClick = { showConfirmDialog = true },
            )
        }
    }

    if (showConfirmDialog) {
        SaveTransactionConfirmDialog(
            type = TransactionSaveType.DEBIT,
            partyName = supplierName.trim(),
            amount = amount.toDouble(),
            onConfirm = {
                showConfirmDialog = false
                scope.launch {
                    loading = true
                    error = null
                    runCatching {
                        val name = supplierName.trim()
                        var supplierId = prefillSupplierId
                        if (supplierId == null && !prefillSupplierPhone.isNullOrBlank()) {
                            supplierId = container.partyRepository.createSupplier(
                                CreatePartyInput(name = name, phone = prefillSupplierPhone),
                            ).id
                        }
                        container.transactionRepository.createDebit(
                            CreateDebitInput(
                                supplierId = supplierId,
                                supplierName = if (supplierId == null) name else null,
                                amount = amount.toDouble(),
                                description = description.trim(),
                                dueDate = dueDate?.let { localDateToIsoInstant(it) },
                            ),
                        )
                        onDone()
                    }.onFailure {
                        container.presentApiError(it, errorSaveTransaction, { msg -> error = msg }, { msg -> alertError = msg })
                    }
                    loading = false
                }
            },
            onDismiss = { showConfirmDialog = false },
        )
    }
}
