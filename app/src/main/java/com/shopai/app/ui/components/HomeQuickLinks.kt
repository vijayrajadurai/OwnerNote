package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private val TileShape = RoundedCornerShape(18.dp)
private val IconWellShape = RoundedCornerShape(14.dp)

@Composable
fun HomeQuickLinks(
    onGroupBuyingClick: () -> Unit,
    onReportsClick: () -> Unit,
    onTodayOfferClick: () -> Unit,
    onLoansClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_quick_links),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.SemiBold,
            color = ShopAiThemeColors.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeQuickLinkTile(
                label = stringResource(R.string.more_group_buying),
                icon = Icons.Outlined.Groups,
                tint = Color(0xFF3D6BCC),
                well = Color(0xFFE8F1FC),
                onClick = onGroupBuyingClick,
                modifier = Modifier.weight(1f),
            )
            HomeQuickLinkTile(
                label = stringResource(R.string.more_reports),
                icon = Icons.Outlined.BarChart,
                tint = Color(0xFF7B5CDB),
                well = Color(0xFFF3ECFE),
                onClick = onReportsClick,
                modifier = Modifier.weight(1f),
            )
            HomeQuickLinkTile(
                label = stringResource(R.string.more_local_offers),
                icon = Icons.Outlined.LocalOffer,
                tint = Color(0xFFD9793C),
                well = Color(0xFFFFF3E6),
                onClick = onTodayOfferClick,
                modifier = Modifier.weight(1f),
            )
            HomeQuickLinkTile(
                label = stringResource(R.string.home_quick_loans),
                icon = Icons.Outlined.AccountBalance,
                tint = Color(0xFF1E8A5A),
                well = Color(0xFFE8F7EE),
                onClick = onLoansClick,
                modifier = Modifier.weight(1f),
            )
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
    modifier: Modifier = Modifier,
) {
    val light = isRedesignLight()
    Column(
        modifier = modifier
            .glassSurface(TileShape)
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
                .size(44.dp)
                .clip(IconWellShape)
                .background(if (light) well else tint.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ShopAiThemeColors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
