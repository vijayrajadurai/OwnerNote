package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.model.PartySummary
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.formatInr
import com.shopai.app.util.formatLocalDateForDisplay
import com.shopai.app.util.parseIsoToLocalDate
import com.shopai.app.util.partyInitialLetter
import java.time.LocalDate

@Composable
fun PartyScreenActions(
    contactLabel: String,
    addLabel: String,
    accent: Color,
    accentMuted: Color,
    onContactClick: () -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PartyActionButton(
            label = contactLabel,
            icon = Icons.Default.Contacts,
            accent = accent,
            background = accentMuted,
            onClick = onContactClick,
            modifier = Modifier.weight(1f),
        )
        PartyActionButton(
            label = addLabel,
            icon = Icons.Default.Add,
            accent = accent,
            background = accentMuted,
            onClick = onAddClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PartyActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PartyDirectoryBody(
    title: String,
    contactLabel: String,
    addLabel: String,
    emptyText: String,
    parties: List<PartySummary>,
    loading: Boolean,
    error: String?,
    avatarAccent: Color,
    avatarAccentMuted: Color,
    onContactClick: () -> Unit,
    onAddClick: () -> Unit,
    onPartyClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = ShopAiThemeColors.onSurface,
            fontWeight = FontWeight.Bold,
        )
        PartyScreenActions(
            contactLabel = contactLabel,
            addLabel = addLabel,
            accent = avatarAccent,
            accentMuted = avatarAccentMuted,
            onContactClick = onContactClick,
            onAddClick = onAddClick,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                loading -> CircularProgressIndicator(
                    color = ShopAiThemeColors.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                error != null -> Text(
                    text = error,
                    color = Danger,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                )
                parties.isEmpty() -> Text(
                    text = emptyText,
                    color = ShopAiThemeColors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                )
                else -> PartyList(
                    parties = parties,
                    avatarAccent = avatarAccent,
                    avatarAccentMuted = avatarAccentMuted,
                    onPartyClick = onPartyClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

fun groupPartiesByDueDate(parties: List<PartySummary>): List<Pair<LocalDate?, List<PartySummary>>> {
    return parties
        .groupBy { parseIsoToLocalDate(it.nextDueDate) }
        .toList()
        .sortedWith(compareBy(nullsLast()) { it.first })
        .map { (date, items) ->
            date to items.sortedBy { it.name.lowercase() }
        }
}

@Composable
fun partyDateSectionLabel(date: LocalDate?): String {
    val today = LocalDate.now()
    return when {
        date == null -> stringResource(R.string.party_section_no_due)
        date == today -> stringResource(R.string.party_section_today)
        date == today.minusDays(1) -> stringResource(R.string.party_section_yesterday)
        date == today.plusDays(1) -> stringResource(R.string.party_section_tomorrow)
        else -> formatLocalDateForDisplay(date)
    }
}

@Composable
fun PartyList(
    parties: List<PartySummary>,
    avatarAccent: Color,
    avatarAccentMuted: Color,
    onPartyClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(parties) { groupPartiesByDueDate(parties) }
    LazyColumn(modifier = modifier) {
        groups.forEach { (date, items) ->
            item(key = "header-${date ?: "none"}") {
                Text(
                    text = partyDateSectionLabel(date),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ShopAiThemeColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(items, key = { _, party -> party.id }) { index, party ->
                PartyListItem(
                    name = party.name,
                    phone = party.phone,
                    pendingTotal = party.pendingTotal,
                    dueDateIso = party.nextDueDate,
                    avatarAccent = avatarAccent,
                    avatarAccentMuted = avatarAccentMuted,
                    onClick = { onPartyClick(party.id) },
                )
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                }
            }
        }
        item {
            VoiceFabBottomSpacer()
        }
    }
}

@Composable
fun PartyListItem(
    name: String,
    phone: String?,
    pendingTotal: Double,
    avatarAccent: Color,
    avatarAccentMuted: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dueDateIso: String? = null,
) {
    val dueDate = parseIsoToLocalDate(dueDateIso)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarAccentMuted),
        ) {
            Text(
                text = partyInitialLetter(name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = avatarAccent,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = ShopAiThemeColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = phone ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = ShopAiThemeColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (dueDate != null) {
                Text(
                    text = stringResource(R.string.party_due_date, formatLocalDateForDisplay(dueDate)),
                    style = MaterialTheme.typography.bodySmall,
                    color = ShopAiThemeColors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = formatInr(pendingTotal),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (pendingTotal > 0) avatarAccent else ShopAiThemeColors.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}
