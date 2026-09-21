package com.shopai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.theme.ShopAiThemeColors

enum class TransactionEntryType {
    CREDIT,
    DEBIT,
}

@Composable
fun TransactionEntryChooserDialog(
    type: TransactionEntryType,
    onFromContact: () -> Unit,
    onManualEntry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (type) {
        TransactionEntryType.CREDIT -> stringResource(R.string.txn_chooser_title_credit)
        TransactionEntryType.DEBIT -> stringResource(R.string.txn_chooser_title_debit)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.txn_chooser_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onFromContact) {
                Text(
                    text = stringResource(R.string.txn_chooser_from_contact),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onManualEntry) {
                Text(
                    text = stringResource(R.string.txn_chooser_manual),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}
