package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.SaveTransactionConfirmDialog
import com.shopai.app.ui.components.ScreenContainer
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.components.TransactionSaveType
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.localDateToIsoInstant
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun AddDebitScreen(
    container: AppContainer,
    onDone: () -> Unit,
) {
    var supplierName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val errorSaveTransaction = stringResource(R.string.error_save_transaction)
    val isValid = supplierName.trim().length >= 2 && (amount.toDoubleOrNull() ?: 0.0) > 0

    ScreenContainer {
        Text(
            stringResource(R.string.add_debit_title),
            style = MaterialTheme.typography.headlineMedium,
            color = ShopAiThemeColors.primary,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            stringResource(R.string.add_debit_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp, top = 4.dp),
        )

        ShopTextField(
            stringResource(R.string.supplier_name),
            supplierName,
            { supplierName = it },
        )
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

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

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
                        container.transactionRepository.createDebit(
                            CreateDebitInput(
                                supplierName = supplierName.trim(),
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
