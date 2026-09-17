package com.shopai.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.shopai.app.R

@Composable
fun ApiErrorAlertDialog(
    message: String?,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_alert_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.try_again))
            }
        },
    )
}
