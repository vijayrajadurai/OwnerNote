package com.shopai.app.ui.kaimemory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.memory.KaiMeaning
import com.shopai.app.brain.memory.KaiMemory
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KaiTeaching
import com.shopai.app.brain.memory.MemorySource
import com.shopai.app.brain.memory.MemoryStatus
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.launch

/**
 * MY KAI LANGUAGE — the words Kai learned for THIS shop only: custom
 * phrases, product / customer / supplier nicknames. The owner can add, edit,
 * switch off and delete them. Nothing here is shared with any other business.
 */
@Composable
fun KaiMemoryScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var memory by remember { mutableStateOf<KaiPrivateMemory?>(null) }
    var items by remember { mutableStateOf<List<KaiMemory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<KaiMemory?>(null) }
    var deleting by remember { mutableStateOf<KaiMemory?>(null) }
    var adding by remember { mutableStateOf(false) }
    val lang = KaiLanguage.forAppLocale().let { if (it == KaiLang.TAMIL) it else KaiLang.TANGLISH }

    fun reload() {
        items = memory?.list().orEmpty().sortedBy { it.triggerPhrase.lowercase() }
    }

    LaunchedEffect(Unit) {
        memory = container.kaiMemoryAccess.current()
        reload()
        loading = false
    }

    DetailScaffold(title = stringResource(R.string.kai_memory_title), onBack = onBack) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.kai_memory_intro), color = ShopAiThemeColors.onSurfaceVariant)
            }
            if (!loading && memory == null) {
                item { Text(stringResource(R.string.kai_memory_no_business), color = ShopAiThemeColors.onSurfaceVariant) }
                return@LazyColumn
            }
            item { PrimaryButton(stringResource(R.string.kai_memory_add), { adding = true }, modifier = Modifier.fillMaxWidth(), enabled = memory != null) }
            if (!loading && items.isEmpty()) {
                item { Text(stringResource(R.string.kai_memory_empty), color = ShopAiThemeColors.onSurfaceVariant) }
            }
            for ((titleRes, types) in listOf(
                R.string.kai_memory_phrases to setOf(MemoryType.ACTION_ALIAS, MemoryType.SLANG, MemoryType.ABBREVIATION, MemoryType.PREFERENCE),
                R.string.kai_memory_products to setOf(MemoryType.PRODUCT_ALIAS),
                R.string.kai_memory_customers to setOf(MemoryType.CUSTOMER_ALIAS),
                R.string.kai_memory_suppliers to setOf(MemoryType.SUPPLIER_ALIAS),
            )) {
                val group = items.filter { it.memoryType in types }
                if (group.isEmpty()) continue
                item { Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Primary) }
                items(group, key = { it.id }) { m ->
                    MemoryRow(
                        m = m,
                        meaning = m.meaning?.label(lang) ?: m.meaningValue,
                        onToggle = { on ->
                            scope.launch {
                                memory?.setStatus(m.id, if (on) MemoryStatus.ACTIVE else MemoryStatus.DISABLED)
                                reload()
                            }
                        },
                        onEdit = { editing = m },
                        onDelete = { deleting = m },
                    )
                }
            }
        }
    }

    editing?.let { m ->
        EditDialog(m, lang, onDismiss = { editing = null }) { phrase, meaning ->
            editing = null
            scope.launch {
                memory?.edit(m.id, phrase, meaning)
                reload()
            }
        }
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.kai_memory_delete_title)) },
            text = { Text("`${m.triggerPhrase}` → ${m.meaning?.label(lang) ?: m.meaningValue}") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        memory?.setStatus(m.id, MemoryStatus.DELETED)
                        reload()
                    }
                }) { Text(stringResource(R.string.kai_memory_delete), color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.kai_memory_cancel)) } },
        )
    }
    if (adding) {
        AddDialog(
            lang = lang,
            onDismiss = { adding = false },
            onAdd = { phrase, means, done ->
                scope.launch {
                    val mem = memory ?: return@launch
                    val meaning = KaiTeaching.meaningIn(means)
                    val entity = if (meaning == null) KaiTeaching.entityIn(means, container.kaiMemoryAccess.entities()) else null
                    when {
                        meaning != null -> mem.teachMeaning(phrase, meaning, MemorySource.OWNER_CREATED)
                        entity != null -> mem.teachEntity(phrase, entity, MemorySource.OWNER_CREATED)
                        else -> {
                            done(false)
                            return@launch
                        }
                    }
                    done(true)
                    adding = false
                    reload()
                }
            },
        )
    }
}

@Composable
private fun MemoryRow(m: KaiMemory, meaning: String, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val on = m.status == MemoryStatus.ACTIVE
    ShopCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("`${m.triggerPhrase}`", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = if (on) ShopAiThemeColors.onSurface else ShopAiThemeColors.onSurfaceVariant)
                Text("→ $meaning", color = if (on) Primary else ShopAiThemeColors.onSurfaceVariant)
                if (m.variants.isNotEmpty()) {
                    Text(m.variants.joinToString(", ") { "`$it`" }, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                }
                if (m.usageCount > 0) {
                    Text(stringResource(R.string.kai_memory_used, m.usageCount), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                }
            }
            Switch(checked = on, onCheckedChange = onToggle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.kai_memory_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.kai_memory_delete), color = Danger) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(m: KaiMemory, lang: KaiLang, onDismiss: () -> Unit, onSave: (String, KaiMeaning?) -> Unit) {
    var phrase by remember { mutableStateOf(m.triggerPhrase) }
    var meaning by remember { mutableStateOf(m.meaning) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kai_memory_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = phrase, onValueChange = { phrase = it }, label = { Text(stringResource(R.string.kai_memory_phrase)) }, singleLine = true)
                if (m.meaning != null) {
                    Text(stringResource(R.string.kai_memory_means), style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KaiMeaning.entries.forEach { k ->
                            FilterChip(selected = meaning == k, onClick = { meaning = k }, label = { Text(k.label(lang)) })
                        }
                    }
                } else {
                    Text("→ ${m.meaningValue}", color = Primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(phrase, meaning) }, enabled = KaiPrivateMemory.normalize(phrase).isNotEmpty()) {
                Text(stringResource(R.string.kai_memory_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.kai_memory_cancel)) } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddDialog(lang: KaiLang, onDismiss: () -> Unit, onAdd: (phrase: String, means: String, done: (Boolean) -> Unit) -> Unit) {
    var phrase by remember { mutableStateOf("") }
    var means by remember { mutableStateOf("") }
    var notUnderstood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kai_memory_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = phrase, onValueChange = { phrase = it }, label = { Text(stringResource(R.string.kai_memory_phrase)) },
                    placeholder = { Text("thooki kudu / red paste") }, singleLine = true)
                OutlinedTextField(value = means, onValueChange = { means = it; notUnderstood = false }, label = { Text(stringResource(R.string.kai_memory_means)) },
                    placeholder = { Text("Payment Out / Colgate 200g") }, singleLine = true)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KaiMeaning.entries.forEach { k -> FilterChip(selected = means == k.en, onClick = { means = k.en }, label = { Text(k.label(lang)) }) }
                }
                if (notUnderstood) Text(stringResource(R.string.kai_memory_not_understood), color = Danger, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(phrase, means) { ok -> notUnderstood = !ok } },
                enabled = KaiPrivateMemory.normalize(phrase).isNotEmpty() && means.isNotBlank(),
            ) { Text(stringResource(R.string.kai_memory_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.kai_memory_cancel)) } },
    )
}
