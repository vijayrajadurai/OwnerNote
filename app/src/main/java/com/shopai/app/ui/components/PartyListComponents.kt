package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopai.app.data.model.PartySummary
import com.shopai.app.ui.theme.LedgerPending
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.formatInr
import com.shopai.app.util.partyInitialLetter

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
fun PartyList(
    parties: List<PartySummary>,
    avatarAccent: Color,
    avatarAccentMuted: Color,
    onPartyClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ShopCard(modifier = modifier) {
        Column {
            parties.forEachIndexed { index, party ->
                PartyListItem(
                    name = party.name,
                    phone = party.phone,
                    pendingTotal = party.pendingTotal,
                    avatarAccent = avatarAccent,
                    avatarAccentMuted = avatarAccentMuted,
                    onClick = { onPartyClick(party.id) },
                )
                if (index < parties.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                }
            }
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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
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
        }

        Text(
            text = formatInr(pendingTotal),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (pendingTotal > 0) LedgerPending else ShopAiThemeColors.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}
