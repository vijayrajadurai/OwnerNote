package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.shopai.app.data.model.PriorityItem
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.DangerMuted
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.PrimaryMuted
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.util.PriorityAmountTone
import com.shopai.app.util.PriorityRowDisplay
import com.shopai.app.util.formatDisplayDate
import com.shopai.app.util.formatInr
import com.shopai.app.util.initialLetter
import com.shopai.app.util.parseIsoToLocalDate
import com.shopai.app.util.toRowDisplay
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun HomePriorityList(
    items: List<PriorityItem>,
    onItemClick: (PriorityItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val row = item.toRowDisplay()
            HomePriorityListItem(
                row = row,
                onClick = { onItemClick(item) },
            )
            if (index < items.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
fun HomePriorityListItem(
    row: PriorityRowDisplay,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val avatarColors = avatarColorsFor(row.tone)
    val amountColor = when (row.tone) {
        PriorityAmountTone.CREDIT -> Success
        PriorityAmountTone.DEBIT -> Danger
        PriorityAmountTone.NEUTRAL -> ShopAiThemeColors.onSurfaceVariant
    }

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
                .size(44.dp)
                .clip(CircleShape)
                .background(avatarColors.background),
        ) {
            Text(
                text = row.initialLetter(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = avatarColors.foreground,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = ShopAiThemeColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = priorityDueLabel(row.dueDateIso),
                style = MaterialTheme.typography.bodySmall,
                color = ShopAiThemeColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.amount != null) {
            Text(
                text = formatInr(row.amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = amountColor,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun priorityDueLabel(dueDateIso: String?): String {
    val iso = dueDateIso
    if (!iso.isNullOrBlank()) {
        val date = parseIsoToLocalDate(iso)
        if (date != null) {
            val today = LocalDate.now()
            val days = ChronoUnit.DAYS.between(today, date)
            return when {
                days < 0 -> stringResource(R.string.priority_overdue, formatDisplayDate(iso))
                days == 0L -> stringResource(R.string.priority_due_today)
                days == 1L -> stringResource(R.string.priority_due_tomorrow)
                else -> formatDisplayDate(iso)
            }
        }
        return formatDisplayDate(iso)
    }
    return stringResource(R.string.priority_follow_up)
}

private data class AvatarColors(val background: Color, val foreground: Color)

private fun avatarColorsFor(tone: PriorityAmountTone): AvatarColors = when (tone) {
    PriorityAmountTone.CREDIT -> AvatarColors(Success.copy(alpha = 0.15f), Success)
    PriorityAmountTone.DEBIT -> AvatarColors(DangerMuted, Danger)
    PriorityAmountTone.NEUTRAL -> AvatarColors(PrimaryMuted, Primary)
}
