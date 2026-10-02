package com.shopai.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.ShopAiTheme
import com.shopai.app.ui.theme.ShopAiThemeColors
import java.time.LocalTime

private val ShopCardShape = RoundedCornerShape(22.dp)
private val BannerShape = RoundedCornerShape(16.dp)

@Composable
fun DashboardGreetingHeader(
    shopName: String,
    location: String,
    unreadCount: Int,
    onNotificationsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onGradient: Boolean = false,
    onProfileClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    photoPath: String? = null,
    ownerName: String = "",
) {
    val hour = LocalTime.now().hour
    val greetingRes = when {
        hour < 12 -> R.string.home_good_morning
        hour < 17 -> R.string.home_good_afternoon
        else -> R.string.home_good_evening
    }
    val displayShop = shopName.ifBlank { ownerName.ifBlank { stringResource(R.string.home_fallback_name) } }
    val displayLocation = location.ifBlank { stringResource(R.string.home_location_unknown) }
    val notificationsCd = if (unreadCount > 0) {
        stringResource(R.string.cd_notifications_unread, unreadCount)
    } else {
        stringResource(R.string.cd_notifications)
    }
    val settingsCd = stringResource(R.string.cd_open_settings)
    val profileCd = stringResource(R.string.cd_open_profile)
    val onHeader = if (onGradient) Color.White else ShopAiThemeColors.onSurface

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(greetingRes),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = OutfitFamily,
                fontWeight = FontWeight.ExtraBold,
                color = onHeader,
                modifier = Modifier.weight(1f),
            )
            HeaderIconButton(
                onClick = onNotificationsClick,
                contentDescription = notificationsCd,
                onGradient = onGradient,
                showBadge = unreadCount > 0,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = if (onGradient) onHeader else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(8.dp))
            HeaderIconButton(
                onClick = onSettingsClick,
                contentDescription = settingsCd,
                onGradient = onGradient,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = if (onGradient) onHeader else MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(ShopCardShape)
                .clickable(onClick = onProfileClick)
                .semantics {
                    contentDescription = "$displayShop. $displayLocation. $profileCd"
                    role = Role.Button
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShopBannerImage(photoPath = photoPath, shopName = displayShop)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayShop,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = OutfitFamily,
                    fontWeight = FontWeight.Bold,
                    color = ShopAiThemeColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = displayLocation,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShopAiThemeColors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_view_profile),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    onGradient: Boolean,
    showBadge: Boolean = false,
    icon: @Composable () -> Unit,
) {
    Box {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .then(
                    if (onGradient) {
                        Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.22f))
                    } else {
                        Modifier.glassSurface(RoundedCornerShape(16.dp))
                    },
                )
                .semantics { this.contentDescription = contentDescription },
        ) {
            icon()
        }
        if (showBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .border(1.5.dp, Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun ShopBannerImage(
    photoPath: String?,
    shopName: String,
) {
    val photoBitmap = remember(photoPath) {
        photoPath?.let { path -> runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    val bannerCd = stringResource(R.string.cd_shop_banner, shopName)
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 64.dp)
            .clip(BannerShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (photoBitmap != null) {
            Image(
                bitmap = photoBitmap,
                contentDescription = bannerCd,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Storefront,
                contentDescription = bannerCd,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

fun shopLocationLabel(areaLabel: String?, city: String): String {
    val area = areaLabel?.trim().orEmpty()
    val town = city.trim()
    return when {
        area.isNotBlank() && town.isNotBlank() &&
            !area.equals(town, ignoreCase = true) &&
            !area.contains(town, ignoreCase = true) -> "$area, $town"
        area.isNotBlank() -> area
        else -> town
    }
}

@Preview(showBackground = true, name = "Dashboard greeting")
@Composable
private fun DashboardGreetingHeaderPreview() {
    ShopAiTheme {
        DashboardGreetingHeader(
            shopName = "Anbu Super Mart",
            location = "Tambaram, Chennai",
            unreadCount = 3,
            onNotificationsClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
