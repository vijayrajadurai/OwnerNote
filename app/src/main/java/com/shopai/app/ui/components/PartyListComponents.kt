package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.model.PartySummary
import com.shopai.app.ui.kai.KaiInk
import com.shopai.app.ui.kai.kaiGreenBackdrop
import com.shopai.app.ui.theme.Danger
import com.shopai.app.util.PartyBucket
import com.shopai.app.util.PartyRow
import com.shopai.app.util.formatInr
import com.shopai.app.util.groupPartyRows
import com.shopai.app.util.partyInitialLetter
import com.shopai.app.util.partyMatches
import com.shopai.app.util.partyTotals
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class PartyFilter { ALL, LATE, WEEK, CLEARED }

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * Customers / Suppliers (owner, 23 Oct 2026: "user friendly-ah"): the mixed-green background, the total still open on top
 * with how much of it is late, a search, filter chips, and the list grouped late first — each row says how many days late
 * or left. A late customer row has a WhatsApp button (the owner sends). One big add button and Contact at the bottom.
 * Read only: adding still goes through the same Add Credit / Add Debit screens.
 */
@Composable
fun PartyDirectoryBody(
    title: String,
    /** Customers (money to come in, green) or suppliers (money to go out, red). */
    incoming: Boolean,
    addLabel: String,
    contactLabel: String,
    searchHint: String,
    emptyText: String,
    parties: List<PartySummary>,
    loading: Boolean,
    error: String?,
    onContactClick: () -> Unit,
    onAddClick: () -> Unit,
    onPartyClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Full customer / supplier master form (OwnerNote Books). */
    newPartyLabel: String? = null,
    onNewParty: (() -> Unit)? = null,
    /** A small "Import" beside the title: bring the list from the app the owner used before (owner, 20 Oct 2026). */
    importLabel: String? = null,
    onImport: (() -> Unit)? = null,
    /** WhatsApp reminder on late rows (customers only). */
    onWhatsApp: ((PartySummary) -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PartyFilter.ALL) }
    val today = remember { LocalDate.now() }
    val accent = if (incoming) Color(0xFF047857) else Color(0xFFB91C1C)
    Box(modifier.fillMaxSize().kaiGreenBackdrop()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // One line, never broken letter by letter ("வாடிக்கையாளர்" is long); "+ New customer" is an icon so the title has room.
                Text(title, color = KaiInk.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (newPartyLabel != null && onNewParty != null) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, KaiInk.Line, RoundedCornerShape(12.dp))
                            .clickable(onClick = onNewParty),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = newPartyLabel, tint = KaiInk.Ink, modifier = Modifier.size(19.dp))
                    }
                }
                if (importLabel != null && onImport != null) HeaderPill(importLabel, Icons.Outlined.UploadFile, onImport)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> CircularProgressIndicator(color = accent, modifier = Modifier.align(Alignment.Center))
                    error != null -> Text(error, color = Danger, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    parties.isEmpty() -> Text(emptyText, color = KaiInk.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    else -> {
                        val totals = remember(parties, today) { partyTotals(parties, today) }
                        val all = remember(parties, today) { groupPartyRows(parties, today) }
                        val count = { b: PartyBucket -> all.firstOrNull { it.first == b }?.second?.size ?: 0 }
                        val shown = remember(parties, today, query, filter) {
                            groupPartyRows(parties.filter { partyMatches(it, query) }, today).filter { (b, _) ->
                                when (filter) {
                                    PartyFilter.ALL -> true
                                    PartyFilter.LATE -> b == PartyBucket.LATE
                                    PartyFilter.WEEK -> b == PartyBucket.THIS_WEEK
                                    PartyFilter.CLEARED -> b == PartyBucket.CLEARED
                                }
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 180.dp)) {
                            item(key = "total") { PartyTotalCard(incoming, totals.pending, totals.people, totals.late, totals.lateCount, totals.onTime) }
                            item(key = "search") {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    placeholder = { Text(searchHint, color = Color(0xFFA8A29E)) },
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Color(0xFFA8A29E)) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color.White,
                                        focusedBorderColor = accent,
                                        unfocusedBorderColor = KaiInk.Line,
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                )
                            }
                            item(key = "chips") {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilterChipPill(stringResource(R.string.party_chip_all, parties.size), filter == PartyFilter.ALL) { filter = PartyFilter.ALL }
                                    FilterChipPill(stringResource(R.string.party_chip_late, count(PartyBucket.LATE)), filter == PartyFilter.LATE) { filter = PartyFilter.LATE }
                                    FilterChipPill(stringResource(R.string.party_chip_week, count(PartyBucket.THIS_WEEK)), filter == PartyFilter.WEEK) { filter = PartyFilter.WEEK }
                                    FilterChipPill(stringResource(R.string.party_chip_cleared, count(PartyBucket.CLEARED)), filter == PartyFilter.CLEARED) { filter = PartyFilter.CLEARED }
                                }
                            }
                            if (shown.isEmpty()) {
                                item(key = "none") {
                                    Text(stringResource(R.string.party_list_none), color = KaiInk.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
                                }
                            }
                            shown.forEach { (bucket, rows) ->
                                item(key = "head-$bucket") { PartyGroupHeader(bucket, rows) }
                                itemsIndexed(rows, key = { _, r -> r.party.id }) { i, r ->
                                    val last = i == rows.lastIndex
                                    PartyRowItem(
                                        row = r,
                                        incoming = incoming,
                                        shape = if (last) RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp) else RectangleShape,
                                        onClick = { onPartyClick(r.party.id) },
                                        onWhatsApp = onWhatsApp?.takeIf { incoming && r.bucket == PartyBucket.LATE }?.let { wa -> { wa(r.party) } },
                                    )
                                    if (last) Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 92.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(if (incoming) listOf(Color(0xFF059669), Color(0xFF047857)) else listOf(Color(0xFFDC2626), Color(0xFF991B1B))))
                    .clickable(onClick = onAddClick),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(6.dp))
                Text(addLabel, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .border(1.dp, KaiInk.Line, RoundedCornerShape(18.dp))
                    .clickable(onClick = onContactClick)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Contacts, contentDescription = null, tint = KaiInk.Ink, modifier = Modifier.size(18.dp))
                Text(contactLabel, color = KaiInk.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeaderPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, KaiInk.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = KaiInk.Ink, modifier = Modifier.size(16.dp))
        Text(label, color = KaiInk.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PartyTotalCard(incoming: Boolean, pending: Double, people: Int, late: Double, lateCount: Int, onTime: Double) {
    val soft = if (incoming) KaiInk.Mint else Color(0xFFFECACA)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(if (incoming) listOf(Color(0xFF047857), Color(0xFF064E3B)) else listOf(Color(0xFFB91C1C), Color(0xFF7F1D1D))))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(if (incoming) R.string.party_total_get else R.string.party_total_give), color = soft, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatInr(pending), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(stringResource(R.string.party_people, people), color = soft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f))) {
            if (pending > 0 && late > 0) Box(Modifier.fillMaxWidth((late / pending).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(Color(0xFFFCA5A5)))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.party_late_sum, lateCount, formatInr(late)),
                color = Color(0xFFFECACA),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.party_on_time, formatInr(onTime)), color = soft, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) KaiInk.Ink else Color.White)
            .border(1.dp, if (selected) KaiInk.Ink else KaiInk.Line, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else Color(0xFF44403C), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PartyGroupHeader(bucket: PartyBucket, rows: List<PartyRow>) {
    val (label, dot) = when (bucket) {
        PartyBucket.LATE -> R.string.party_group_late to Color(0xFFEF4444)
        PartyBucket.THIS_WEEK -> R.string.party_group_week to Color(0xFFF59E0B)
        PartyBucket.LATER -> R.string.party_group_later to Color(0xFF10B981)
        PartyBucket.CLEARED -> R.string.party_group_cleared to Color(0xFF94A3B8)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Color.White)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(stringResource(label), color = KaiInk.Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp, modifier = Modifier.weight(1f))
        Text(
            if (bucket == PartyBucket.CLEARED) stringResource(R.string.party_people, rows.size)
            else stringResource(R.string.party_group_note, rows.size, formatInr(rows.sumOf { it.party.pendingTotal })),
            color = KaiInk.InkSoft,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PartyRowItem(row: PartyRow, incoming: Boolean, shape: Shape, onClick: () -> Unit, onWhatsApp: (() -> Unit)?) {
    val due = com.shopai.app.util.parseIsoToLocalDate(row.party.nextDueDate)
    val (tone, ink) = when (row.bucket) {
        PartyBucket.LATE -> Color(0xFFFEE2E2) to Color(0xFFB91C1C)
        PartyBucket.THIS_WEEK -> Color(0xFFFEF3C7) to Color(0xFF92400E)
        PartyBucket.LATER -> Color(0xFFDCFCE7) to Color(0xFF15803D)
        PartyBucket.CLEARED -> Color(0xFFF1F5F9) to Color(0xFF64748B)
    }
    val sub = when (row.bucket) {
        PartyBucket.LATE -> stringResource(R.string.party_row_late, row.days ?: 0)
        PartyBucket.THIS_WEEK -> if (row.days == 0) stringResource(R.string.party_row_today) else stringResource(R.string.party_row_soon, row.days ?: 0, due?.format(dayMonth).orEmpty())
        PartyBucket.LATER -> due?.let { stringResource(R.string.party_row_later, it.format(dayMonth)) } ?: stringResource(R.string.party_row_no_due)
        PartyBucket.CLEARED -> stringResource(R.string.party_row_cleared)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF1F1EF)))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(tone), contentAlignment = Alignment.Center) {
                Text(partyInitialLetter(row.party.name), color = ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.party.name, color = KaiInk.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = if (row.bucket == PartyBucket.CLEARED) KaiInk.InkSoft else ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(
                formatInr(row.party.pendingTotal),
                color = when (row.bucket) {
                    PartyBucket.LATE -> Color(0xFFB91C1C)
                    PartyBucket.CLEARED -> KaiInk.InkSoft
                    else -> if (incoming) KaiInk.Ink else Color(0xFFB91C1C)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
            onWhatsApp?.let { wa ->
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFDCFCE7)).clickable(onClick = wa),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = stringResource(R.string.party_remind_wa), tint = Color(0xFF15803D), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
