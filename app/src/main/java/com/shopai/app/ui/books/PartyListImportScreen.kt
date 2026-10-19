package com.shopai.app.ui.books

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.shopai.app.books.integration.BooksRejectedException
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.integration.PartyListImport
import com.shopai.app.books.integration.PartyListImportSave
import com.shopai.app.books.model.PartyKind
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * Bring the accounts from the app the owner used before (owner, 19 Oct 2026): the owner picks its Customer List / Supplier List PDF,
 * sees every person with their balance, unticks any they don't want, and Confirm writes them — the person and an opening balance
 * (the collection date as its due date). Nothing is written before Confirm; a person already in the books is left as they are.
 */
@Composable
fun PartyListImportScreen(container: AppContainer, onBack: () -> Unit) {
    val books = rememberBooks(container)
    DetailScaffold(title = stringResource(R.string.party_import_title), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> PartyListImportBody(books.session, modifier)
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun PartyListImportBody(s: BooksSession, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<PartyListImport.Sheet?>(null) }
    var kind by remember { mutableStateOf(PartyKind.CUSTOMER) }
    val picked = remember { mutableStateListOf<Boolean>() }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val notList = stringResource(R.string.party_import_not_list)
    val savedTemplate = stringResource(R.string.party_import_saved)
    val alreadyTemplate = stringResource(R.string.party_import_already)
    val failed = stringResource(R.string.party_import_failed)
    val dates = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; message = null; error = null
            val read = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { PartyListImport.read(it) }
            }
            if (read == null || read.rows.isEmpty()) { sheet = null; error = notList } else {
                sheet = read
                kind = read.kind ?: PartyKind.CUSTOMER
                picked.clear(); picked.addAll(read.rows.map { true })
            }
            busy = false
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.party_import_caption), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
        OutlinedButton(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, enabled = !busy) {
            Text(stringResource(if (sheet == null) R.string.party_import_pick else R.string.party_import_pick_again))
        }
        error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodyMedium) }
        message?.let { Text(it, color = ShopAiThemeColors.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }

        val sh = sheet
        if (sh != null) {
            ShopCard {
                Text(stringResource(R.string.party_import_found, sh.rows.size, sh.shop.orEmpty()), fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                Text(
                    stringResource(if (sh.checked) R.string.party_import_total_ok else R.string.party_import_total_check),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sh.checked) ShopAiThemeColors.primary else Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Customers or suppliers: from the report's own title; the owner can change it.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(selected = kind == PartyKind.CUSTOMER, onClick = { kind = PartyKind.CUSTOMER }, label = { Text(stringResource(R.string.party_import_customers)) })
                    FilterChip(selected = kind == PartyKind.SUPPLIER, onClick = { kind = PartyKind.SUPPLIER }, label = { Text(stringResource(R.string.party_import_suppliers)) })
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(sh.rows) { i, r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = picked.getOrElse(i) { true }, onCheckedChange = { v -> if (i < picked.size) picked[i] = v }, enabled = !busy)
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                            val sub = listOfNotNull(r.phone, r.collectionDate?.let { stringResource(R.string.party_import_due, it.format(dates)) }).joinToString("  ·  ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                        }
                        // What it means for this shop: they owe you, or you owe them.
                        val theyOwe = r.getPaise - r.givePaise
                        Text(
                            when {
                                theyOwe > 0 -> stringResource(R.string.party_import_they_owe, PartyListImportSave.rupees(theyOwe))
                                theyOwe < 0 -> stringResource(R.string.party_import_you_owe, PartyListImportSave.rupees(-theyOwe))
                                else -> "—"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (theyOwe < 0) Danger else ShopAiThemeColors.onSurface,
                        )
                    }
                }
            }
            val chosen = sh.rows.filterIndexed { i, _ -> picked.getOrElse(i) { true } }
            PrimaryButton(
                label = stringResource(R.string.party_import_confirm, chosen.size),
                enabled = chosen.isNotEmpty() && !busy,
                loading = busy,
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        val outcome = runCatching { withContext(Dispatchers.IO) { PartyListImportSave.save(s, kind, chosen) } }
                        outcome.onSuccess { o ->
                            // Said only after the books have them.
                            message = savedTemplate.format(o.added, o.withBalance) +
                                (if (o.alreadyThere.isNotEmpty()) "\n" + alreadyTemplate.format(o.alreadyThere.joinToString(", ")) else "")
                            sheet = null
                        }.onFailure { e -> error = (e as? BooksRejectedException)?.message ?: failed }
                        busy = false
                    }
                },
            )
        }
    }
}
