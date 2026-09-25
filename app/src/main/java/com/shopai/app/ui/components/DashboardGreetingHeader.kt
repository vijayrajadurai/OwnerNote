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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.theme.ShopAiTheme
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.partyInitialLetter
import java.time.LocalTime

@Composable
fun DashboardGreetingHeader(
    ownerName: String,
    unreadCount: Int,
    onNotificationsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onGradient: Boolean = false,
    onProfileClick: () -> Unit = {},
) {
    val hour = LocalTime.now().hour
    val greetingRes = when {
        hour < 12 -> R.string.home_good_morning
        hour < 17 -> R.string.home_good_afternoon
        else -> R.string.home_good_evening
    }
    val displayName = ownerName.ifBlank { stringResource(R.string.home_fallback_name) }
    val notificationsCd = if (unreadCount > 0) {
        stringResource(R.string.cd_notifications_unread, unreadCount)
    } else {
        stringResource(R.string.cd_notifications)
    }

    val profileCd = stringResource(R.string.cd_open_profile)
    val onHeader = if (onGradient) Color.White else ShopAiThemeColors.onSurface
    val onHeaderMuted = if (onGradient) Color.White.copy(alpha = 0.82f) else ShopAiThemeColors.onSurfaceVariant
    val avatarBg = if (onGradient) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val avatarFg = if (onGradient) Color.White else MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(avatarBg)
                .clickable(onClick = onProfileClick)
                .semantics { contentDescription = profileCd },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = partyInitialLetter(displayName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = avatarFg,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(greetingRes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = onHeader,
            )
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = onHeaderMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(
                onClick = onNotificationsClick,
                modifier = Modifier
                    .then(
                        if (onGradient) {
                            Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f))
                        } else {
                            Modifier
                        },
                    )
                    .semantics { contentDescription = notificationsCd },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = onHeader,
                )
            }
            if (unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Dashboard greeting")
@Composable
private fun DashboardGreetingHeaderPreview() {
    ShopAiTheme {
        DashboardGreetingHeader(
            ownerName = "Priya",
            unreadCount = 3,
            onNotificationsClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
