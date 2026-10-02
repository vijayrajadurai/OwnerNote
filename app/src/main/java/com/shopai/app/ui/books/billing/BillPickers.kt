package com.shopai.app.ui.books.billing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.data.ProductEntity
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.nameKey
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.Qty
import com.shopai.app.ui.books.scanBarcode
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.ShopAlertDialog
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val shown = DateTimeFormatter.ofPattern("dd MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit, clearable: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open = true }) { Text(date?.format(shown) ?: "—") }
            if (clearable && date != null) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.bill_clear)) }
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.books_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** Pick a customer / supplier, a walk-in cash sale, or add a new one by name. */
@Composable
fun PartyPicker(s: BooksSession, kind: PartyKind, allowWalkIn: Boolean, onDismiss: () -> Unit, onPick: (PartyEntity?) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<PartyEntity>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) { results = s.dao.searchParties(s.ctx.businessId, kind.name, nameKey(query), 50, 0) }
    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (kind == PartyKind.CUSTOMER) R.string.bill_pick_customer else R.string.bill_pick_supplier)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShopTextField(stringResource(R.string.bill_search_name_phone), query, { query = it })
                error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
                if (allowWalkIn) TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.bill_walk_in)) }
                if (query.isNotBlank() && results.none { it.nameKey == nameKey(query) }) {
                    TextButton(onClick = {
                        scope.launch {
                            when (val r = s.masters.createParty(PartyInput(kind, query.trim()))) {
                                is MasterResult.Ok -> onPick(r.value)
                                is MasterResult.Rejected -> error = r.errors.joinToString { it.message }
                            }
                        }
                    }) { Text(stringResource(R.string.bill_add_new_party, query.trim())) }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.id }) { p ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 8.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                            listOfNotNull(p.mobile, p.gstin, p.city).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Pick a product (search or barcode) or add a one-off line (a service or item not in the master). */
@Composable
fun ProductPicker(s: BooksSession, onDismiss: () -> Unit, onPick: (ProductEntity) -> Unit, onOneOff: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Pair<ProductEntity, Long>>()) }
    var notFound by remember { mutableStateOf<String?>(null) }
    val notFoundText = stringResource(R.string.bill_barcode_not_found)
    LaunchedEffect(query) {
        val q = query.trim()
        val found = (s.dao.searchProducts(s.ctx.businessId, nameKey(q), 50, 0) + if (q.isEmpty()) emptyList() else s.dao.searchProducts(s.ctx.businessId, q, 50, 0))
            .distinctBy { it.id }.filter { it.active }
        results = found.map { it to s.ledger.stock(it.id) }
    }
    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bill_add_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShopTextField(stringResource(R.string.books_product_search), query, { query = it }, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scanBarcode(context) { code ->
                            code ?: return@scanBarcode
                            scope.launch {
                                val hit = s.dao.activeProductsByBarcode(s.ctx.businessId, code).singleOrNull()
                                if (hit != null) onPick(hit) else notFound = notFoundText.format(code)
                            }
                        }
                    }) { Text(stringResource(R.string.books_scan)) }
                }
                notFound?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
                if (query.isNotBlank()) TextButton(onClick = { onOneOff(query.trim()) }) { Text(stringResource(R.string.bill_one_off, query.trim())) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.first.id }) { (p, stock) ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                                listOfNotNull(p.hsnCode?.let { "HSN $it" }, p.sku, p.barcode).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                                }
                            }
                            if (!p.isService) Text("${Qty.toDecimal(stock).toPlainString()} ${p.primaryUnit}", style = MaterialTheme.typography.bodySmall, color = if (stock <= 0) Danger else ShopAiThemeColors.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
