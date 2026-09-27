package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.inventory.INSIGHT_ESTIMATED_LOW_COVER
import com.shopai.app.data.inventory.INSIGHT_HIGH_USAGE
import com.shopai.app.data.inventory.INSIGHT_INSUFFICIENT_HISTORY
import com.shopai.app.data.inventory.INSIGHT_LOW_STOCK
import com.shopai.app.data.inventory.INSIGHT_OUT_OF_STOCK
import com.shopai.app.data.inventory.STOCK_STATUS_LOW_STOCK
import com.shopai.app.data.inventory.computeStockStatus
import com.shopai.app.data.model.CreateInventoryProductInput
import com.shopai.app.data.model.InventoryIntelligenceSummaryDto
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.repository.StockChangeResult
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopAlertDialog
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.ui.theme.Warning
import kotlinx.coroutines.launch

@Composable
fun InventoryScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var products by remember { mutableStateOf<List<InventoryProduct>>(emptyList()) }
    var summary by remember { mutableStateOf<InventoryIntelligenceSummaryDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var showNewProduct by remember { mutableStateOf(false) }
    var stockChangeTarget by remember { mutableStateOf<Pair<InventoryProduct, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    val errorLoad = stringResource(R.string.inv_error_load)
    val errorSave = stringResource(R.string.inv_error_save)
    val insufficientStockTemplate = stringResource(R.string.inv_insufficient_stock)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                products = container.inventoryRepository.listProducts()
                summary = container.inventoryRepository.getIntelligenceSummary()
            }.onFailure {
                container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    if (showNewProduct) {
        NewProductDialog(
            onDismiss = { showNewProduct = false },
            onSave = { name, category, unit, opening, minimum ->
                scope.launch {
                    runCatching {
                        container.inventoryRepository.createProduct(
                            CreateInventoryProductInput(name, category, unit, opening, minimum),
                        )
                    }.onSuccess {
                        showNewProduct = false
                        reload()
                    }.onFailure {
                        container.presentApiError(
                            it,
                            errorSave,
                            { msg -> alertError = msg },
                            { msg -> alertError = msg },
                        )
                    }
                }
            },
        )
    }

    stockChangeTarget?.let { (product, isStockIn) ->
        StockChangeDialog(
            product = product,
            isStockIn = isStockIn,
            onDismiss = { stockChangeTarget = null },
            onConfirm = { quantity, reason ->
                scope.launch {
                    runCatching {
                        if (isStockIn) {
                            container.inventoryRepository.stockIn(product.id, quantity, reason)
                            null
                        } else {
                            val result = container.inventoryRepository.stockOut(product.id, quantity, reason)
                            (result as? StockChangeResult.InsufficientStock)?.available
                        }
                    }.onSuccess { insufficientAvailable ->
                        if (insufficientAvailable != null) {
                            alertError = insufficientStockTemplate.format("${formatQty(insufficientAvailable)} ${product.unit}")
                        } else {
                            stockChangeTarget = null
                            reload()
                        }
                    }.onFailure {
                        container.presentApiError(it, errorLoad, { msg -> alertError = msg }, { msg -> alertError = msg })
                    }
                }
            },
        )
    }

    DetailScaffold(title = stringResource(R.string.inv_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.inv_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
            )
            PrimaryButton(label = stringResource(R.string.inv_new_product), onClick = { showNewProduct = true })

            summary?.let { s ->
                if (s.totalProducts > 0) {
                    ShopCard {
                        Text(
                            text = stringResource(R.string.inv_intelligence_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ShopAiThemeColors.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.inv_intelligence_summary_line,
                                s.totalProducts,
                                s.lowStockCount,
                                s.outOfStockCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (s.attentionProducts.isEmpty()) {
                            Text(
                                text = stringResource(R.string.inv_no_attention),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShopAiThemeColors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            s.attentionProducts.forEach { p ->
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    Text(
                                        text = p.productName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ShopAiThemeColors.onSurface,
                                    )
                                    p.insights.forEach { insight ->
                                        Text(
                                            text = insightLabel(insight, p.estimatedDaysRemaining),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = insightColor(insight),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(error!!, color = ShopAiThemeColors.onSurface)
                products.isEmpty() -> Text(
                    stringResource(R.string.inv_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
                else -> products.forEach { product ->
                    ShopCard {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = product.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = ShopAiThemeColors.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            val status = computeStockStatus(product.currentStock, product.minimumStock)
                            Text(
                                text = if (status == STOCK_STATUS_LOW_STOCK) {
                                    stringResource(R.string.inv_status_low)
                                } else {
                                    stringResource(R.string.inv_status_healthy)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (status == STOCK_STATUS_LOW_STOCK) Danger else Success,
                            )
                        }
                        Text(
                            text = product.category,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.inv_current_stock_line, formatQty(product.currentStock), product.unit),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ShopAiThemeColors.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = stringResource(R.string.inv_min_stock_line, formatQty(product.minimumStock), product.unit),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { stockChangeTarget = product to true }) {
                                Text(stringResource(R.string.inv_stock_in))
                            }
                            OutlinedButton(onClick = { stockChangeTarget = product to false }) {
                                Text(stringResource(R.string.inv_stock_out))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun insightLabel(insight: String, estimatedDaysRemaining: Int?): String = when (insight) {
    INSIGHT_OUT_OF_STOCK -> stringResource(R.string.inv_insight_out_of_stock)
    INSIGHT_LOW_STOCK -> stringResource(R.string.inv_insight_low_stock)
    INSIGHT_HIGH_USAGE -> stringResource(R.string.inv_insight_high_usage)
    INSIGHT_ESTIMATED_LOW_COVER -> stringResource(R.string.inv_insight_estimated_low_cover, estimatedDaysRemaining ?: 0)
    INSIGHT_INSUFFICIENT_HISTORY -> stringResource(R.string.inv_insight_insufficient_history)
    else -> ""
}

private fun insightColor(insight: String) = when (insight) {
    INSIGHT_OUT_OF_STOCK -> Danger
    INSIGHT_LOW_STOCK -> Warning
    INSIGHT_HIGH_USAGE -> Warning
    else -> Success
}

@Composable
private fun NewProductDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, category: String, unit: String, opening: Double, minimum: Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    var minimum by remember { mutableStateOf("") }

    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inv_new_product)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShopTextField(label = stringResource(R.string.inv_product_name), value = name, onValueChange = { name = it })
                ShopTextField(label = stringResource(R.string.inv_category), value = category, onValueChange = { category = it })
                ShopTextField(
                    label = stringResource(R.string.inv_unit),
                    value = unit,
                    onValueChange = { unit = it },
                    placeholder = stringResource(R.string.inv_unit_placeholder),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_opening_stock),
                    value = opening,
                    onValueChange = { opening = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_minimum_stock),
                    value = minimum,
                    onValueChange = { minimum = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && category.isNotBlank() && unit.isNotBlank(),
                onClick = {
                    onSave(
                        name.trim(),
                        category.trim(),
                        unit.trim(),
                        opening.toDoubleOrNull() ?: 0.0,
                        minimum.toDoubleOrNull() ?: 0.0,
                    )
                },
            ) { Text(stringResource(R.string.inv_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun StockChangeDialog(
    product: InventoryProduct,
    isStockIn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Double, reason: String) -> Unit,
) {
    var quantity by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }

    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isStockIn) stringResource(R.string.inv_stock_in) else stringResource(R.string.inv_stock_out)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShopTextField(
                    label = stringResource(R.string.inv_stock_change_qty),
                    value = quantity,
                    onValueChange = { quantity = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_stock_change_reason),
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = stringResource(R.string.inv_stock_change_reason_placeholder),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (quantity.toDoubleOrNull() ?: 0.0) > 0 && reason.isNotBlank(),
                onClick = { onConfirm(quantity.toDoubleOrNull() ?: 0.0, reason.trim()) },
            ) { Text(stringResource(R.string.inv_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun formatQty(quantity: Double): String =
    if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
