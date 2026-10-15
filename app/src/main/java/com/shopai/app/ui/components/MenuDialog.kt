package com.shopai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.brain.chat.KaiMenu
import com.shopai.app.data.AppContainer
import com.shopai.app.util.formatInr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * The shop's Menu (owner, 16 Oct 2026): the owner adds each item and its price ("Cutting 100, Shaving 80, Facial 110"),
 * changes a price by adding the item again, and removes an item. The same Menu Kai Chat sells from — Kai never fills it.
 */
@Composable
fun MenuDialog(container: AppContainer, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<KaiMenu.Item>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var noBusiness by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val needName = stringResource(R.string.menu_need_name)
    val needPrice = stringResource(R.string.menu_need_price)
    val saveFailed = stringResource(R.string.menu_save_failed)

    suspend fun reload() {
        val memory = withContext(Dispatchers.IO) { runCatching { container.kaiMemoryAccess.current() }.getOrNull() }
        noBusiness = memory == null
        items = memory?.let { KaiMenu.items(it) }.orEmpty()
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.menu_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    !loaded -> Text("…")
                    noBusiness -> Text(stringResource(R.string.menu_no_business), color = MaterialTheme.colorScheme.error)
                    items.isEmpty() -> Text(stringResource(R.string.menu_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> items.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(formatInr(item.price.toDouble()), fontWeight = FontWeight.SemiBold)
                            TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    withContext(Dispatchers.IO) { runCatching { container.kaiMemoryAccess.current()?.let { KaiMenu.remove(it, item.name) } } }
                                    reload()
                                    busy = false
                                }
                            }) { Text(stringResource(R.string.menu_remove), color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                    }
                }
                if (!noBusiness) {
                    Text(stringResource(R.string.menu_add_title), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it; error = null }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.menu_item_name)) }, placeholder = { Text("Cutting") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = price, onValueChange = { v -> price = v.filter { it.isDigit() || it == '.' }; error = null }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.menu_item_price)) }, placeholder = { Text("100") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(enabled = !busy, modifier = Modifier.align(Alignment.End), onClick = {
                        val n = KaiMenu.clean(name)
                        val p = price.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                        when {
                            n == null -> error = needName
                            p == null -> error = needPrice
                            else -> scope.launch {
                                busy = true
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { container.kaiMemoryAccess.current()?.let { KaiMenu.save(it, n, p); true } ?: false }.getOrDefault(false)
                                }
                                if (ok) { name = ""; price = "" } else error = saveFailed
                                reload()
                                busy = false
                            }
                        }
                    }) { Text(stringResource(R.string.menu_add)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.menu_done)) } },
    )
}
