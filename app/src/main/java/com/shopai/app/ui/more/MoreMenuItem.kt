package com.shopai.app.ui.more

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopai.app.ui.components.isRedesignLight
import com.shopai.app.ui.theme.GlassShadow
import com.shopai.app.ui.theme.OutfitFamily

private val CardShape = RoundedCornerShape(22.dp)
private val IconWellShape = RoundedCornerShape(16.dp)

@Composable
fun MoreMenuItem(
    item: MoreMenuEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(item.labelRes)
    val caption = stringResource(item.captionRes)
    val iconDescription = stringResource(item.contentDescriptionRes)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "moreCardPress")
    val light = isRedesignLight()
    val cardColor = if (light) item.card else MaterialTheme.colorScheme.surface
    val chevronTint = item.tint.copy(alpha = if (light) 0.55f else 0.7f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (light) 10.dp else 2.dp,
                shape = CardShape,
                clip = false,
                ambientColor = if (light) GlassShadow else MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f),
                spotColor = if (light) GlassShadow else MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f),
            )
            .clip(CardShape)
            .background(cardColor)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label. $caption"
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(IconWellShape)
                    .background(if (light) Color.White.copy(alpha = 0.78f) else item.tint.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = item.icon.asImageVector(),
                    contentDescription = iconDescription,
                    tint = item.tint,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = chevronTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
    }
}

private fun MoreMenuIcon.asImageVector(): ImageVector = when (this) {
    MoreMenuIcon.Profile -> Icons.Rounded.Person
    MoreMenuIcon.Categories -> Icons.Rounded.Category
    MoreMenuIcon.Transactions -> Icons.Rounded.Description
    MoreMenuIcon.Reports -> Icons.Rounded.BarChart
    MoreMenuIcon.Reminders -> Icons.Rounded.Alarm
    MoreMenuIcon.Notifications -> Icons.Rounded.Notifications
    MoreMenuIcon.Settings -> Icons.Rounded.Settings
    MoreMenuIcon.Help -> Icons.Rounded.HeadsetMic
    MoreMenuIcon.About -> Icons.Rounded.Info
    MoreMenuIcon.Privacy -> Icons.Rounded.PrivacyTip
    MoreMenuIcon.AskBusiness -> Icons.Rounded.Chat
    MoreMenuIcon.GroupBuying -> Icons.Rounded.Groups
    MoreMenuIcon.Handwritten -> Icons.Rounded.Draw
    MoreMenuIcon.Voice -> Icons.Rounded.Mic
    MoreMenuIcon.Subscription -> Icons.Rounded.WorkspacePremium
    MoreMenuIcon.Hsn -> Icons.Rounded.QrCode2
    MoreMenuIcon.BooksSettings -> Icons.Rounded.MenuBook
    MoreMenuIcon.Offers -> Icons.Rounded.LocalOffer
}
