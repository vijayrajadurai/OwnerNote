package com.shopai.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.shopai.app.R
import com.shopai.app.util.formatInr

enum class TransactionSaveType {
    CREDIT,
    DEBIT,
}

@Composable
fun SaveTransactionConfirmDialog(
    type: TransactionSaveType,
    partyName: String,
    amount: Double,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (type) {
        TransactionSaveType.CREDIT -> stringResource(R.string.confirm_save_credit_title)
        TransactionSaveType.DEBIT -> stringResource(R.string.confirm_save_debit_title)
    }
    val message = when (type) {
        TransactionSaveType.CREDIT -> stringResource(
            R.string.confirm_save_credit_message,
            partyName,
            formatInr(amount),
        )
        TransactionSaveType.DEBIT -> stringResource(
            R.string.confirm_save_debit_message,
            partyName,
            formatInr(amount),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
