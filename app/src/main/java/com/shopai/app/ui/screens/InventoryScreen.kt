package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.ProductArt
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.shopai.app.data.model.PartySummary
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
import com.shopai.app.util.formatQty
import kotlinx.coroutines.launch

@Composable
fun InventoryScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenProduct: (String) -> Unit = {},
    /** Full Product Master form — used once the books are in use. */
    onNewProduct: () -> Unit = {},
    /** Stock by voice / text: Kai Chat (the one inventory logic). */
    onOpenKaiChat: () -> Unit = {},
) {
    var products by remember { mutableStateOf<List<InventoryProduct>>(emptyList()) }
    var summary by remember { mutableStateOf<InventoryIntelligenceSummaryDto?>(null) }
    var suppliers by remember { mutableStateOf<List<PartySummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var showNewProduct by remember { mutableStateOf(false) }
    var stockChangeTarget by remember { mutableStateOf<Pair<InventoryProduct, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var booksOn by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Product ids matching the search (name, SKU, barcode or HSN); null = no search.
    var matches by remember { mutableStateOf<Set<String>?>(null) }
    // The Low Stock tile tapped: only the products at or below their minimum.
    var lowOnly by remember { mutableStateOf(false) }
    // Low stock first (owner, 22 Oct 2026); "Ellaam" keeps the list's own order.
    var lowFirst by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { booksOn = container.books.session() != null }
    var archived by remember { mutableStateOf(emptyList<com.shopai.app.books.data.ProductEntity>()) }
    var showArchived by remember { mutableStateOf(false) }
    LaunchedEffect(showArchived) {
        archived = if (!showArchived) emptyList() else container.books.session()?.let { s -> s.dao.products(s.ctx.businessId, archived = true, limit = 1000, offset = 0) }.orEmpty()
    }
    LaunchedEffect(query) {
        val s = container.books.session()
        matches = if (s == null || query.isBlank()) null else {
            val q = query.trim()
            s.dao.searchProducts(s.ctx.businessId, com.shopai.app.books.engine.nameKey(q), 500, 0).map { it.id }.toSet() +
                s.dao.searchProducts(s.ctx.businessId, q, 500, 0).map { it.id } +
                s.dao.productsByCodePart(s.ctx.businessId, q, 500).map { it.id }
        }
    }
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

    LaunchedEffect(Unit) {
        runCatching { suppliers = container.partyRepository.getSuppliers() }
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
            suppliers = suppliers,
            onDismiss = { showNewProduct = false },
            onSave = { input ->
                scope.launch {
                    runCatching {
                        container.inventoryRepository.createProduct(input)
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

    DetailScaffold(
        title = stringResource(R.string.inv_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = onOpenKaiChat) {
                Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.stock_voice_entry_title))
            }
        },
    ) { contentModifier ->
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
            // Dashboard (owner, 20 Oct 2026): Search Product, Add Product, Total Products, Low Stock, then the list.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopTextField(
                    label = stringResource(R.string.books_product_search),
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.inv_search_hint),
                    modifier = Modifier.weight(1f),
                )
                if (booksOn) {
                    OutlinedButton(onClick = {
                        com.shopai.app.ui.books.scanBarcode(context) { code ->
                            code ?: return@scanBarcode
                            scope.launch {
                                // A barcode belongs to one active product: open it straight away.
                                val s = container.books.session() ?: return@launch
                                val hit = s.dao.activeProductsByBarcode(s.ctx.businessId, code).singleOrNull()
                                if (hit != null) onOpenProduct(hit.id) else query = code
                            }
                        }
                    }) { Text(stringResource(R.string.books_scan)) }
                }
            }
            PrimaryButton(label = stringResource(R.string.inv_new_product), onClick = { if (booksOn) onNewProduct() else showNewProduct = true })
            if (!loading && error == null) {
                val lowCount = products.count { computeStockStatus(it.currentStock, it.minimumStock) == STOCK_STATUS_LOW_STOCK }
                // Version 5b (owner, 22 Oct 2026): Items · Value · Kammi across the top, low stock first.
                val stockValue = products.sumOf { p -> p.currentStock.coerceAtLeast(0.0) * (p.purchasePrice ?: 0.0) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StockStat(stringResource(R.string.inv_stat_items), products.size.toString(), selected = !lowOnly, alert = false, Modifier.weight(1f)) { lowOnly = false }
                    if (stockValue > 0) StockStat(stringResource(R.string.inv_stat_value), com.shopai.app.util.shortRupees(stockValue), selected = false, alert = false, Modifier.weight(1f)) {}
                    StockStat(stringResource(R.string.inv_stat_low), lowCount.toString(), selected = lowOnly, alert = lowCount > 0, Modifier.weight(1f)) { lowOnly = !lowOnly }
                }
                StockSegments(lowFirst = lowFirst, onLowFirst = { lowFirst = it })
            }

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
                // Error / empty, version 7b (owner, 22 Oct 2026): what happened in plain words and what to do next.
                error != null -> com.shopai.app.ui.components.FriendlyState(
                    kind = com.shopai.app.ui.components.FriendlyKind.NET,
                    title = stringResource(R.string.friendly_load_title),
                    body = stringResource(R.string.friendly_load_body),
                    safe = stringResource(R.string.friendly_nothing_lost),
                    primaryLabel = stringResource(R.string.friendly_try_again),
                    onPrimary = { reload() },
                    details = error,
                )
                products.isEmpty() -> com.shopai.app.ui.components.FriendlyState(
                    kind = com.shopai.app.ui.components.FriendlyKind.EMPTY,
                    title = stringResource(R.string.friendly_no_products_title),
                    body = stringResource(R.string.friendly_no_products_body),
                    primaryLabel = stringResource(R.string.friendly_tell_kai),
                    onPrimary = onOpenKaiChat,
                    secondaryLabel = stringResource(R.string.inv_new_product),
                    onSecondary = { if (booksOn) onNewProduct() else showNewProduct = true },
                )
                else -> products.filter { p ->
                    // (without the books: the name, SKU or barcode typed, matched here)
                    val q = query.trim()
                    (matches?.contains(p.id) ?: (q.isEmpty() || listOfNotNull(p.name, p.sku, p.barcode).any { it.contains(q, ignoreCase = true) })) &&
                        (!lowOnly || computeStockStatus(p.currentStock, p.minimumStock) == STOCK_STATUS_LOW_STOCK)
                }.let { list ->
                    if (lowFirst) list.sortedByDescending { computeStockStatus(it.currentStock, it.minimumStock) == STOCK_STATUS_LOW_STOCK } else list
                }.forEach { product ->
                    // + / − open the same Stock in / Stock out box as before (quantity, reason, then save).
                    StockRow(
                        product = product,
                        onOpen = { onOpenProduct(product.id) },
                        onPlus = { stockChangeTarget = product to true },
                        onMinus = { stockChangeTarget = product to false },
                    )
                }
            }
            if (booksOn) {
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(stringResource(if (showArchived) R.string.books_hide_archived else R.string.books_show_archived))
                }
                archived.forEach { p ->
                    TextButton(onClick = { onOpenProduct(p.id) }) { Text(p.name, color = ShopAiThemeColors.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One of the counts across the top (Items / Value / Kammi); Kammi shows only the low ones. */
@Composable
private fun StockStat(label: String, value: String, selected: Boolean, alert: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .then(Modifier.background(if (alert) androidx.compose.ui.graphics.Color(0xFFFEF2F2) else androidx.compose.ui.graphics.Color.White, shape))
            .border(if (selected || alert) 1.5.dp else 1.dp, if (alert) androidx.compose.ui.graphics.Color(0xFFFECACA) else if (selected) androidx.compose.ui.graphics.Color(0xFF111827) else androidx.compose.ui.graphics.Color(0xFFECEAE5), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (alert) androidx.compose.ui.graphics.Color(0xFFB91C1C) else ShopAiThemeColors.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (alert) androidx.compose.ui.graphics.Color(0xFFB91C1C) else ShopAiThemeColors.onSurface, maxLines = 1)
    }
}

/** "Kammi mudhalla" / "Ellaam". */
@Composable
private fun StockSegments(lowFirst: Boolean, onLowFirst: (Boolean) -> Unit) {
    val outer = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(outer).background(androidx.compose.ui.graphics.Color(0xFFECEAE5), outer).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((label, on) in listOf(stringResource(R.string.inv_low_first) to true, stringResource(R.string.inv_all) to false)) {
            val picked = lowFirst == on
            val inner = androidx.compose.foundation.shape.RoundedCornerShape(9.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(inner)
                    .background(if (picked) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent, inner)
                    .clickable { onLowFirst(on) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (picked) androidx.compose.ui.graphics.Color(0xFF111827) else androidx.compose.ui.graphics.Color(0xFF6B7280))
            }
        }
    }
}

/** A product: its picture, stock against the minimum (red when low), a bar, and − / + for stock out / in. */
@Composable
private fun StockRow(product: InventoryProduct, onOpen: () -> Unit, onPlus: () -> Unit, onMinus: () -> Unit) {
    val low = computeStockStatus(product.currentStock, product.minimumStock) == STOCK_STATUS_LOW_STOCK
    val card = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(card)
            .background(androidx.compose.ui.graphics.Color.White, card)
            .border(1.dp, androidx.compose.ui.graphics.Color(0xFFECEAE5), card)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProductArt(name = product.name, category = product.category, unit = product.unit)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface, maxLines = 1)
            Text(
                text = stringResource(R.string.inv_stock_vs_min, formatQty(product.currentStock), product.unit, formatQty(product.minimumStock)),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (low) Danger else ShopAiThemeColors.onSurfaceVariant,
                maxLines = 1,
            )
            val cover = if (product.minimumStock > 0) (product.currentStock / (product.minimumStock * 3)).toFloat() else 1f
            Box(Modifier.fillMaxWidth().height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(androidx.compose.ui.graphics.Color(0xFFF3F4F6), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) {
                Box(
                    Modifier
                        .fillMaxWidth(cover.coerceIn(0.04f, 1f))
                        .height(4.dp)
                        .background(if (low) androidx.compose.ui.graphics.Color(0xFFEF4444) else androidx.compose.ui.graphics.Color(0xFF10B981), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                )
            }
        }
        val pill = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        Row(Modifier.height(44.dp).clip(pill).background(androidx.compose.ui.graphics.Color(0xFFF3F4F6), pill), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(44.dp).height(44.dp).clickable(onClick = onMinus), contentAlignment = Alignment.Center) {
                Text("−", style = MaterialTheme.typography.titleLarge, color = ShopAiThemeColors.onSurface)
            }
            Box(Modifier.width(44.dp).height(44.dp).clickable(onClick = onPlus), contentAlignment = Alignment.Center) {
                Text("+", style = MaterialTheme.typography.titleLarge, color = ShopAiThemeColors.onSurface)
            }
        }
    }
}

/** A dashboard count (Total Products / Low Stock); tapping it picks which products the list shows. */
@Composable
private fun StockTile(label: String, count: Int, selected: Boolean, alert: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ShopCard(modifier = modifier.clickable(onClick = onClick)) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (alert) Danger else ShopAiThemeColors.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else ShopAiThemeColors.onSurfaceVariant,
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProductDialog(
    suppliers: List<PartySummary>,
    onDismiss: () -> Unit,
    onSave: (CreateInventoryProductInput) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var subCategory by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    var minimum by remember { mutableStateOf("") }
    var purchasePrice by remember { mutableStateOf("") }
    var sellingPrice by remember { mutableStateOf("") }
    var mrp by remember { mutableStateOf("") }
    var gstRate by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedSupplier by remember { mutableStateOf<PartySummary?>(null) }
    var supplierMenuExpanded by remember { mutableStateOf(false) }
    val decimalOnly: (String) -> String = { it.filter { ch -> ch.isDigit() || ch == '.' } }

    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inv_new_product)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                ShopTextField(label = stringResource(R.string.inv_product_name), value = name, onValueChange = { name = it })
                ShopTextField(label = stringResource(R.string.inv_category), value = category, onValueChange = { category = it })
                ShopTextField(label = stringResource(R.string.inv_sub_category), value = subCategory, onValueChange = { subCategory = it })
                ShopTextField(label = stringResource(R.string.inv_brand), value = brand, onValueChange = { brand = it })
                ShopTextField(label = stringResource(R.string.inv_sku), value = sku, onValueChange = { sku = it })
                ShopTextField(label = stringResource(R.string.inv_barcode), value = barcode, onValueChange = { barcode = it })
                ShopTextField(
                    label = stringResource(R.string.inv_unit),
                    value = unit,
                    onValueChange = { unit = it },
                    placeholder = stringResource(R.string.inv_unit_placeholder),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_opening_stock),
                    value = opening,
                    onValueChange = { opening = decimalOnly(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_minimum_stock),
                    value = minimum,
                    onValueChange = { minimum = decimalOnly(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_purchase_price),
                    value = purchasePrice,
                    onValueChange = { purchasePrice = decimalOnly(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_selling_price),
                    value = sellingPrice,
                    onValueChange = { sellingPrice = decimalOnly(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_mrp),
                    value = mrp,
                    onValueChange = { mrp = decimalOnly(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ShopTextField(
                    label = stringResource(R.string.inv_gst_rate),
                    value = gstRate,
                    onValueChange = { gstRate = decimalOnly(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ExposedDropdownMenuBox(
                    expanded = supplierMenuExpanded,
                    onExpandedChange = { supplierMenuExpanded = it },
                ) {
                    ShopTextField(
                        label = stringResource(R.string.inv_supplier),
                        value = selectedSupplier?.name ?: stringResource(R.string.inv_supplier_none),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = supplierMenuExpanded,
                        onDismissRequest = { supplierMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inv_supplier_none)) },
                            onClick = { selectedSupplier = null; supplierMenuExpanded = false },
                        )
                        suppliers.forEach { supplier ->
                            DropdownMenuItem(
                                text = { Text(supplier.name) },
                                onClick = { selectedSupplier = supplier; supplierMenuExpanded = false },
                            )
                        }
                    }
                }
                ShopTextField(label = stringResource(R.string.inv_image_uri), value = imageUri, onValueChange = { imageUri = it })
                ShopTextField(label = stringResource(R.string.inv_notes), value = notes, onValueChange = { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && category.isNotBlank() && unit.isNotBlank(),
                onClick = {
                    onSave(
                        CreateInventoryProductInput(
                            name = name.trim(),
                            category = category.trim(),
                            subCategory = subCategory.trim().ifBlank { null },
                            brand = brand.trim().ifBlank { null },
                            sku = sku.trim().ifBlank { null },
                            barcode = barcode.trim().ifBlank { null },
                            unit = unit.trim(),
                            currentStock = opening.toDoubleOrNull() ?: 0.0,
                            minimumStock = minimum.toDoubleOrNull() ?: 0.0,
                            purchasePrice = purchasePrice.toDoubleOrNull(),
                            sellingPrice = sellingPrice.toDoubleOrNull(),
                            mrp = mrp.toDoubleOrNull(),
                            gstRate = gstRate.toDoubleOrNull(),
                            supplierId = selectedSupplier?.id,
                            imageUri = imageUri.trim().ifBlank { null },
                            notes = notes.trim().ifBlank { null },
                        ),
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
                // The usual reasons as one tap (old stock, damaged, missing…); the owner's own words still go in the box.
                val reasons = if (isStockIn) {
                    listOf(R.string.inv_reason_old_stock, R.string.inv_reason_customer_return, R.string.inv_reason_count, R.string.inv_reason_other)
                } else {
                    listOf(R.string.inv_reason_damaged, R.string.inv_reason_expired, R.string.inv_reason_missing, R.string.inv_reason_wastage,
                        R.string.inv_reason_count, R.string.inv_reason_own_use)
                }.map { stringResource(it) }
                com.shopai.app.ui.books.ChoiceChips(reasons, reasons.firstOrNull { it == reason }, { it }) { reason = it }
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

