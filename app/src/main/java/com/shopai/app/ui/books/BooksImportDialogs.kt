package com.shopai.app.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.shopai.app.R
import com.shopai.app.books.integration.ImportOutcome
import com.shopai.app.ui.components.ShopAlertDialog
import com.shopai.app.ui.theme.ShopAiThemeColors

/** The one-time move to OwnerNote Books: a short wait, then what was brought across. */
@Composable
fun BooksImportDialogs(importing: Boolean, imported: ImportOutcome.Imported?, onDismiss: () -> Unit) {
    if (importing) {
        ShopAlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.books_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ShopAiThemeColors.primary)
                    Text(stringResource(R.string.books_import_running))
                }
            },
        )
    }
    imported?.let { result ->
        ShopAlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.books_ok)) } },
            title = { Text(stringResource(R.string.books_import_done_title)) },
            text = { Text(stringResource(R.string.books_import_done, result.customers, result.suppliers, result.products)) },
        )
    }
}
