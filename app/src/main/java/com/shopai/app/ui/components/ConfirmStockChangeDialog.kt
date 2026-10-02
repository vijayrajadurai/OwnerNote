package com.shopai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.inventory.StockVoiceIntent
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.util.formatQty

/**
 * Confirmation shown after a voice/typed stock command is understood.
 * Nothing is written to the database until [onConfirm] is tapped — matches
 * the same "parse -> confirm -> mutate" flow InventoryScreen's own
 * StockChangeDialog already uses, just pre-filled from voice instead of
 * hand-typed.
 */
@Composable
fun ConfirmStockChangeDialog(
    intent: StockVoiceIntent,
    product: InventoryProduct,
    quantity: Double,
    unit: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isStockIn = intent == StockVoiceIntent.STOCK_IN
    val afterStock = if (isStockIn) product.currentStock + quantity else product.currentStock - quantity
    val amountColor = if (isStockIn) Success else Danger
    val amountText = (if (isStockIn) "+" else "-") + formatQty(quantity) + " " + unit

    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isStockIn) stringResource(R.string.inv_stock_in) else stringResource(R.string.inv_stock_out))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ShopAiThemeColors.onSurface,
                )
                Text(
                    amountText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.stock_voice_current_stock, formatQty(product.currentStock), product.unit),
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        if (isStockIn) R.string.stock_voice_after_add else R.string.stock_voice_after_remove,
                        formatQty(afterStock),
                        product.unit,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = ShopAiThemeColors.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (isStockIn) stringResource(R.string.inv_stock_in) else stringResource(R.string.inv_stock_out))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
