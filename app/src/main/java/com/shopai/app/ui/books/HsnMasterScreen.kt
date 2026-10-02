package com.shopai.app.ui.books

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.data.HsnSacEntity
import com.shopai.app.books.data.ProductEntity
import com.shopai.app.books.engine.HsnImport
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.CodeKind
import com.shopai.app.books.model.Role
import com.shopai.app.books.tax.HsnRules
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.ShopAlertDialog
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HSN / SAC master (owner-managed). Ships empty and is filled from the official
 * GST classification by CSV import or by hand — the app never makes a code up.
 * Tap a code to see the products that use it.
 */
@Composable
fun HsnMasterScreen(container: AppContainer, onBack: () -> Unit, onOpenProduct: (String) -> Unit) {
    val books = rememberBooks(container)
    DetailScaffold(title = stringResource(R.string.books_hsn_master), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> HsnMaster(books.session, modifier, onOpenProduct)
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun HsnMaster(s: BooksSession, modifier: Modifier, onOpenProduct: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<HsnSacEntity>()) }
    var selected by remember { mutableStateOf<HsnSacEntity?>(null) }
    var products by remember { mutableStateOf(emptyList<ProductEntity>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var isOwner by remember { mutableStateOf(false) }
    val importedTemplate = stringResource(R.string.books_hsn_imported)
    val importFailed = stringResource(R.string.books_hsn_import_failed)

    LaunchedEffect(Unit) { isOwner = s.dao.user(s.ctx.userId)?.role.let { it == null || it == Role.OWNER.name } }
    LaunchedEffect(query, reload) { rows = s.dao.searchHsn(query.trim(), 200) }
    LaunchedEffect(selected) { products = selected?.let { s.dao.productsByHsn(s.ctx.businessId, it.code) }.orEmpty() }

    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.orEmpty()
                val parsed = HsnImport.parse(text, source = "CSV import", now = System.currentTimeMillis())
                val bad = s.masters.importHsnMaster(parsed.rows)
                importedTemplate.format(parsed.rows.size - bad.size, parsed.skipped + bad.size)
            }.getOrElse { importFailed }
            reload++
        }
    }

    if (adding) AddHsnDialog(s, onDismiss = { adding = false }, onAdded = { adding = false; reload++ })

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.books_hsn_master_caption), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
        if (isOwner) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel")) }) {
                    Text(stringResource(R.string.books_hsn_import))
                }
                OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.books_hsn_add)) }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.primary) }
        ShopTextField(stringResource(R.string.books_hsn_search), query, { query = it })
        selected?.let { code ->
            ShopCard {
                Text("${code.kind} ${code.code}", fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                Text(code.description, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
                Text(stringResource(R.string.books_hsn_products, products.size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                products.forEach { p -> TextButton(onClick = { onOpenProduct(p.id) }) { Text(p.name) } }
            }
        }
        if (rows.isEmpty()) {
            Text(stringResource(R.string.books_hsn_empty), color = ShopAiThemeColors.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(rows, key = { "${it.kind}:${it.code}" }) { row ->
                ShopCard(modifier = Modifier.clickable { selected = row }) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${row.code}  ·  ${row.kind}", fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                        row.gstBp?.let { Text("${bpText(it)}%", color = ShopAiThemeColors.primary) }
                    }
                    Text(row.description, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant, maxLines = 2)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AddHsnDialog(s: BooksSession, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CodeKind.HSN) }
    var description by remember { mutableStateOf("") }
    var gst by remember { mutableStateOf("") }
    val wellFormed = HsnRules.isWellFormed(code, kind)
    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.books_hsn_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChips(CodeKind.entries, kind, { it.name }) { kind = it }
                ShopTextField(
                    stringResource(if (kind == CodeKind.SAC) R.string.books_sac_code else R.string.books_hsn_code), code,
                    { code = it.filter(Char::isDigit).take(8) },
                    error = if (code.isNotEmpty() && !wellFormed) stringResource(if (kind == CodeKind.SAC) R.string.books_sac_format else R.string.books_hsn_format) else null,
                )
                ShopTextField(stringResource(R.string.books_description), description, { description = it }, singleLine = false)
                DecimalField(stringResource(R.string.books_hsn_reference_rate), gst, { gst = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = wellFormed && description.isNotBlank(),
                onClick = {
                    scope.launch {
                        s.masters.importHsnMaster(
                            listOf(HsnSacEntity(code, kind.name, description.trim(), parsePercentBp(gst).getOrNull(), null, null, "Added by owner", System.currentTimeMillis())),
                        )
                        onAdded()
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
