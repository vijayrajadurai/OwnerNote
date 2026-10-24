package com.shopai.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.shopai.app.ui.components.ShopAlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.brain.tools.KeptGroups
import com.shopai.app.brain.tools.KeptItem
import com.shopai.app.brain.tools.KeptPlace
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Enga Vechen?" (owner, 24 Oct 2026): everything the owner told Kai where they kept — "Aatkal kitta" first (30+ days marked), then
 * one group per top place, newest first; more than ten are shown as groups to open. Each thing: Idam maathu, Eduthuten, Photo add,
 * Delete — each asks first, and the table changes only on the owner's yes. Closed things are hidden unless "Pazhaya list" is on.
 * Adding is by Kai (typed or the mic): "Kai kitta sollunga" opens Kai Chat. The photo comes from the system Photo Picker (no
 * permission) and is copied into the app's own folder.
 */
@Composable
fun EngaVechenScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenKaiChat: () -> Unit,
    /** Opened from Kai Chat's "Photo add" on one thing: that thing, its group open, the picker ready. */
    focusId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<KeptItem>?>(null) }
    var showOld by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Set<String>>(emptySet()) }
    var moving by remember { mutableStateOf<KeptItem?>(null) }
    var closing by remember { mutableStateOf<KeptItem?>(null) }
    var deleting by remember { mutableStateOf<KeptItem?>(null) }
    var photoFor by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { items = runCatching { container.keptItems.all() }.getOrNull() ?: emptyList() }
    }

    fun save(k: KeptItem) {
        scope.launch {
            failed = !container.keptItems.save(k)
            reload()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = photoFor ?: return@rememberLauncherForActivityResult
        photoFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "kept").apply { mkdirs() }
                    val out = File(dir, "$id.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                    out.path
                }.getOrNull()
            }
            val k = items?.firstOrNull { it.id == id } ?: return@launch
            if (path != null) save(k.copy(photo = path, updatedAt = LocalDateTime.now())) else failed = true
        }
    }

    fun pickPhoto(id: String) {
        photoFor = id
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(Unit) { reload() }

    DetailScaffold(title = stringResource(R.string.kept_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryButton(label = stringResource(R.string.kept_tell_kai), onClick = onOpenKaiChat)
            Text(stringResource(R.string.kept_hint), color = ShopAiThemeColors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            FilterChip(selected = showOld, onClick = { showOld = !showOld }, label = { Text(stringResource(R.string.kept_show_old)) })
            if (failed) Text(stringResource(R.string.kept_not_saved), color = Danger)
            val all = items
            when {
                all == null -> Unit
                all.none { it.active || showOld } -> Text(stringResource(R.string.kept_empty), color = ShopAiThemeColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp))
                else -> {
                    val shown = all.filter { it.active || showOld }
                    val groups = KeptGroups.of(shown)
                    val people = groups.firstOrNull { it.withPeople }?.items?.mapNotNull { it.person?.lowercase() }?.distinct()?.size ?: 0
                    Text(
                        stringResource(R.string.kept_summary, shown.size, groups.count { !it.withPeople }, people),
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge,
                    )
                    val collapsed = shown.size > 10
                    val today = LocalDate.now()
                    for (g in groups) {
                        val key = if (g.withPeople) "\u0000people" else g.top.orEmpty()
                        val focusHere = focusId != null && g.items.any { it.id == focusId }
                        val isOpen = !collapsed || key in open || focusHere
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (g.withPeople) Color(0xFFFFF7ED) else Color(0xFFF0FDF4))
                                .clickable { open = if (key in open) open - key else open + key }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                (if (isOpen) "▾ " else "▸ ") + (if (g.withPeople) stringResource(R.string.kept_with_people) else g.top?.replaceFirstChar { it.titlecase() }
                                    ?: stringResource(R.string.kept_no_place)),
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            )
                            Text("${g.items.size}", fontWeight = FontWeight.Bold, color = ShopAiThemeColors.primary)
                        }
                        if (isOpen) g.items.forEach { k ->
                            KeptRow(
                                k = k, today = today, highlight = k.id == focusId,
                                onMove = { moving = k }, onTaken = { closing = k }, onPhoto = { pickPhoto(k.id) }, onDelete = { deleting = k },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Idam maathu: the new place, typed (outermost first); the old one is kept in the history.
    moving?.let { k ->
        var place by remember(k.id) { mutableStateOf("") }
        ShopAlertDialog(
            onDismissRequest = { moving = null },
            title = { Text(stringResource(R.string.kept_move_title, k.item)) },
            text = {
                ShopTextField(stringResource(R.string.kept_move_label), place, { place = it }, placeholder = stringResource(R.string.kept_move_placeholder))
            },
            confirmButton = {
                TextButton(enabled = place.isNotBlank(), onClick = {
                    val parts = place.split(Regex("""[,›>]+|\s+""")).map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in setOf("la", "le", "ல") }
                    save(k.copy(placeParts = parts, person = null, updatedAt = LocalDateTime.now(), history = k.history + KeptPlace(k.placeParts, k.person, LocalDateTime.now())))
                    moving = null
                }) { Text(stringResource(R.string.kept_confirm)) }
            },
            dismissButton = { TextButton(onClick = { moving = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    closing?.let { k ->
        ShopAlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.kept_close_title, k.item)) },
            confirmButton = { TextButton(onClick = { save(k.copy(status = KeptItem.Status.CLOSED, updatedAt = LocalDateTime.now())); closing = null }) { Text(stringResource(R.string.kept_confirm)) } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { k ->
        ShopAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.kept_delete_title, k.item)) },
            text = { Text(stringResource(R.string.kept_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        failed = !container.keptItems.delete(k.id)
                        k.photo?.let { runCatching { File(it).delete() } }
                        reload()
                    }
                    deleting = null
                }) { Text(stringResource(R.string.kept_delete), color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun KeptRow(k: KeptItem, today: LocalDate, highlight: Boolean, onMove: () -> Unit, onTaken: () -> Unit, onPhoto: () -> Unit, onDelete: () -> Unit) {
    val days = KeptGroups.daysWith(k, today)
    val old = k.person != null && days >= KeptGroups.OLD_DAYS
    ShopCard(modifier = if (highlight || old) Modifier.background(if (old) Color(0xFFFEF3C7) else Color(0xFFE0F2FE), RoundedCornerShape(16.dp)) else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bitmap = remember(k.photo) { k.photo?.let { com.shopai.app.ui.notes.decodeFile(it, 160)?.asImageBitmap() } }
            if (bitmap != null) {
                Image(bitmap, contentDescription = k.item, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(k.item.replaceFirstChar { it.titlecase() } + if (!k.active) " · " + stringResource(R.string.kept_closed) else "",
                    fontWeight = FontWeight.Bold, fontSize = 17.sp)
                val place = k.placeParts.joinToString(" › ").replaceFirstChar { it.titlecase() }
                if (k.person != null) Text(stringResource(R.string.kept_with_person, k.person!!, days), color = if (old) Danger else ShopAiThemeColors.onSurfaceVariant,
                    fontWeight = if (old) FontWeight.Bold else FontWeight.Normal)
                if (place.isNotEmpty()) Text(place, color = ShopAiThemeColors.onSurfaceVariant)
                Text(
                    "${k.createdAt.dayOfMonth} ${k.createdAt.month.name.take(3).lowercase().replaceFirstChar { it.titlecase() }}" +
                        (k.keptBy?.let { " · $it" } ?: "") +
                        (k.history.lastOrNull()?.let { h -> " · " + h.parts.joinToString(" ").ifEmpty { h.person.orEmpty() } } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant,
                )
            }
        }
        if (k.active) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onMove, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.kept_move), fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(onClick = onTaken, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.kept_taken), fontSize = 12.sp, maxLines = 1) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (k.active) OutlinedButton(onClick = onPhoto, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.kept_photo), fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.kept_delete), fontSize = 12.sp, maxLines = 1, color = Danger) }
        }
    }
}
