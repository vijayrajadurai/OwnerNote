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
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.CreateCreditInput
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
import com.shopai.app.util.localDateToIsoInstant
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun AddCreditScreen(
    container: AppContainer,
    prefillCustomerId: String? = null,
    prefillCustomerName: String? = null,
    prefillCustomerPhone: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val lockedToCustomer = !prefillCustomerName.isNullOrBlank() || !prefillCustomerId.isNullOrBlank()
    var customerName by remember { mutableStateOf(prefillCustomerName ?: "") }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val errorSaveTransaction = stringResource(R.string.error_save_transaction)
    val isValid = (lockedToCustomer || customerName.trim().length >= 2) &&
        (amount.toDoubleOrNull() ?: 0.0) > 0

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(
        title = stringResource(R.string.add_credit_title),
        onBack = onBack,
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.add_credit_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            ShopTextField(
                stringResource(R.string.customer_name),
                customerName,
                { if (!lockedToCustomer) customerName = it },
            )
            if (!prefillCustomerPhone.isNullOrBlank()) {
                ShopTextField(
                    stringResource(R.string.login_phone_label),
                    prefillCustomerPhone,
                    {},
                )
            }
            ShopTextField(
                stringResource(R.string.amount_label),
                amount,
                { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
            )
            ShopTextField(
                stringResource(R.string.note_optional),
                description,
                { description = it },
            )
            FutureDatePickerField(
                label = stringResource(R.string.due_date_optional),
                selectedDate = dueDate,
                onDateSelected = { dueDate = it },
                placeholder = stringResource(R.string.due_date_placeholder),
                error = error,
                allowEmpty = true,
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
            type = TransactionSaveType.CREDIT,
            partyName = customerName.trim(),
            amount = amount.toDouble(),
            onConfirm = {
                showConfirmDialog = false
                scope.launch {
                    loading = true
                    error = null
                    runCatching {
                        val name = customerName.trim()
                        var customerId = prefillCustomerId
                        if (customerId == null && !prefillCustomerPhone.isNullOrBlank()) {
                            customerId = container.partyRepository.createCustomer(
                                CreatePartyInput(name = name, phone = prefillCustomerPhone),
                            ).id
                        }
                        container.transactionRepository.createCredit(
                            CreateCreditInput(
                                customerId = customerId,
                                customerName = if (customerId == null) name else null,
                                amount = amount.toDouble(),
                                description = description.trim().ifBlank { null },
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
