package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.InventoryMovement
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.LedgerPending
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.util.formatDisplayDate
import com.shopai.app.util.formatInr

@Composable
fun ProductDetailScreen(
    container: AppContainer,
    productId: String,
    onBack: () -> Unit,
    /** Product Master form — offered once the books are in use. */
    onEdit: (String) -> Unit = {},
) {
    var product by remember { mutableStateOf<InventoryProduct?>(null) }
    var movements by remember { mutableStateOf<List<InventoryMovement>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val errorLoad = stringResource(R.string.inv_error_load)
    var booksOn by remember { mutableStateOf(false) }
    var refresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(Unit) { booksOn = container.books.session() != null }
    // Back from editing: show the saved product.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        var first = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (first) first = false else refresh++ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(productId, refresh) {
        loading = true
        error = null
        runCatching {
            product = container.inventoryRepository.getProduct(productId)
            movements = container.inventoryRepository.listMovements(productId)
        }.onFailure {
            container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
        }
        loading = false
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(
        title = product?.name ?: stringResource(R.string.inv_title),
        onBack = onBack,
        actions = {
            if (booksOn && product != null) androidx.compose.material3.TextButton(onClick = { onEdit(product!!.id) }) { Text(stringResource(R.string.books_edit)) }
        },
    ) { contentModifier ->
        when {
            loading -> Box(modifier = contentModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ShopAiThemeColors.primary)
            }
            error != null -> Text(
                text = error!!,
                color = Danger,
                modifier = contentModifier.padding(24.dp),
            )
            product != null -> {
                val p = product!!
                Column(
                    modifier = contentModifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShopCard {
                        DetailRow(stringResource(R.string.inv_category), p.category)
                        DetailRow(stringResource(R.string.inv_sub_category), p.subCategory)
                        DetailRow(stringResource(R.string.inv_brand), p.brand)
                        DetailRow(stringResource(R.string.inv_sku), p.sku)
                        DetailRow(stringResource(R.string.inv_barcode), p.barcode)
                        DetailRow(
                            stringResource(R.string.inv_current_stock_label),
                            "${formatQty(p.currentStock)} ${p.unit}",
                        )
                        DetailRow(
                            stringResource(R.string.inv_minimum_stock_label),
                            "${formatQty(p.minimumStock)} ${p.unit}",
                        )
                        DetailRow(stringResource(R.string.inv_purchase_price), p.purchasePrice?.let { formatInr(it) })
                        DetailRow(stringResource(R.string.inv_selling_price), p.sellingPrice?.let { formatInr(it) })
                        DetailRow(stringResource(R.string.inv_mrp), p.mrp?.let { formatInr(it) })
                        DetailRow(stringResource(R.string.inv_gst_rate), p.gstRate?.let { "$it%" })
                        DetailRow(stringResource(R.string.inv_supplier), p.supplierName)
                        DetailRow(stringResource(R.string.inv_image_uri), p.imageUri)
                        DetailRow(stringResource(R.string.inv_notes), p.notes)
                    }
                    if (booksOn) com.shopai.app.ui.books.ProductMasterInfo(container, p.id, refresh)

                    Text(
                        text = stringResource(R.string.inv_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ShopAiThemeColors.onSurface,
                    )
                    if (movements.isEmpty()) {
                        Text(
                            text = stringResource(R.string.inv_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                    } else {
                        movements.forEach { movement ->
                            MovementRow(movement, p.unit)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = ShopAiThemeColors.onSurface,
        )
    }
}

@Composable
private fun MovementRow(movement: InventoryMovement, unit: String) {
    ShopCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = movementTypeLabel(movement.type),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = ShopAiThemeColors.onSurface,
                )
                Text(
                    text = formatDisplayDate(movement.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = ShopAiThemeColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = movement.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShopAiThemeColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = movementQuantityLabel(movement, unit),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (movement.type == "OUT") Danger else Success,
                )
                Text(
                    text = movement.balanceAfter?.let {
                        stringResource(R.string.inv_history_balance, "${formatQty(it)} $unit")
                    } ?: stringResource(R.string.inv_history_balance_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = LedgerPending,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun movementTypeLabel(type: String): String = when (type) {
    "IN" -> stringResource(R.string.inv_stock_in)
    "OUT" -> stringResource(R.string.inv_stock_out)
    else -> type
}

@Composable
private fun movementQuantityLabel(movement: InventoryMovement, unit: String): String {
    val prefix = if (movement.type == "OUT") "-" else "+"
    return "$prefix${formatQty(movement.quantity)} $unit"
}

private fun formatQty(quantity: Double): String =
    if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
