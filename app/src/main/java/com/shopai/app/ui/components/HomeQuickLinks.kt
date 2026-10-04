package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Surface
import com.shopai.app.ui.theme.TextPrimary

@Composable
fun HomeQuickLinks(
    onSpeakClick: () -> Unit,
    onGroupBuyingClick: () -> Unit,
    onReportsClick: () -> Unit,
    onTodayOfferClick: () -> Unit,
    onLoansClick: () -> Unit,
    onInventoryClick: () -> Unit = {},
    onSeeAllClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_quick_links),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = OutfitFamily,
                fontWeight = FontWeight.SemiBold,
                color = ShopAiThemeColors.onSurface,
            )
            Row(
                modifier = Modifier.clickable(onClick = onSeeAllClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_see_all),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ShopAiThemeColors.primary,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ShopAiThemeColors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            item {
                HomeQuickLinkTile(stringResource(R.string.more_voice), Icons.Outlined.Mic, Color(0xFF0F7A5A), Color(0xFFDDF6EA), onSpeakClick)
            }
            item {
                HomeQuickLinkTile(stringResource(R.string.more_group_buying), Icons.Outlined.Groups, Color(0xFF3D6BCC), Color(0xFFE8F1FC), onGroupBuyingClick)
            }
            item {
                HomeQuickLinkTile(stringResource(R.string.more_reports), Icons.Outlined.BarChart, Color(0xFF7B5CDB), Color(0xFFF3ECFE), onReportsClick)
            }
            item {
                HomeQuickLinkTile(stringResource(R.string.more_local_offers), Icons.Outlined.LocalOffer, Color(0xFFD9793C), Color(0xFFFFF3E6), onTodayOfferClick)
            }
            item {
                HomeQuickLinkTile(stringResource(R.string.home_quick_loans), Icons.Outlined.AccountBalance, Color(0xFF1E8A5A), Color(0xFFE8F7EE), onLoansClick)
            }
            item {
                HomeQuickLinkTile(stringResource(R.string.home_quick_inventory), Icons.Outlined.Inventory2, Color(0xFF2A7A8C), Color(0xFFE4F4F6), onInventoryClick)
            }
        }
    }
}

@Composable
private fun HomeQuickLinkTile(
    label: String,
    icon: ImageVector,
    tint: Color,
    well: Color,
    onClick: () -> Unit,
) {
    val light = isRedesignLight()
    Column(
        modifier = Modifier
            .width(76.dp)
            .shadow(6.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x1A1E6B4E), spotColor = Color(0x1A1E6B4E))
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (light) well else tint.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
