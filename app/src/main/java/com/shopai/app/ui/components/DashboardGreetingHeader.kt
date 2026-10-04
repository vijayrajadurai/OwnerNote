package com.shopai.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
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

private val BannerShape = RoundedCornerShape(16.dp)

@Suppress("UNUSED_PARAMETER")
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
    val displayShop = shopName.ifBlank { ownerName.ifBlank { stringResource(R.string.home_fallback_name) } }
    val displayLocation = location.ifBlank { stringResource(R.string.home_location_unknown) }
    val profileCd = stringResource(R.string.cd_open_profile)
    val onHeader = onGradient
    val titleColor = if (onHeader) androidx.compose.ui.graphics.Color.White else ShopAiThemeColors.onSurface
    val subColor = if (onHeader) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.82f) else ShopAiThemeColors.onSurfaceVariant
    val iconTint = if (onHeader) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onProfileClick)
            .semantics {
                contentDescription = "$displayShop. $displayLocation. $profileCd"
                role = Role.Button
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            ShopBannerImage(photoPath = photoPath, shopName = displayShop, onHeader = onHeader)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayShop,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = OutfitFamily,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
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
                        tint = iconTint,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = displayLocation,
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (onHeader) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f)
                        else androidx.compose.ui.graphics.Color(0xFFE5F6EE),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_view_profile),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (onHeader) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (onHeader) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
    }
}

@Composable
private fun ShopBannerImage(
    photoPath: String?,
    shopName: String,
    onHeader: Boolean = false,
) {
    val photoBitmap: ImageBitmap? = remember(photoPath) {
        if (photoPath.isNullOrBlank()) {
            null
        } else {
            runCatching { BitmapFactory.decodeFile(photoPath)?.asImageBitmap() }.getOrNull()
        }
    }
    val bannerCd = stringResource(R.string.cd_shop_banner, shopName)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 52.dp)
            .clip(BannerShape)
            .background(
                if (onHeader) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f)
                else androidx.compose.ui.graphics.Color(0xFFDFF6EA),
            ),
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
                tint = if (onHeader) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.primary,
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