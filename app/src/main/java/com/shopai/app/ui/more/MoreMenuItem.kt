package com.shopai.app.ui.more

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SupportAgent
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.shopai.app.ui.components.isRedesignLight
import com.shopai.app.ui.theme.GlassFill
import com.shopai.app.ui.theme.GlassShadow

private val CardShape = RoundedCornerShape(18.dp)

@Composable
fun MoreMenuItem(
    item: MoreMenuEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(item.labelRes)
    val iconDescription = stringResource(item.contentDescriptionRes)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "moreCardPress")
    val light = isRedesignLight()
    val cardColor = if (light) GlassFill else MaterialTheme.colorScheme.surface
    val elevation = if (light) 8.dp else 2.dp
    val shadow = if (light) GlassShadow else MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.05f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = elevation,
                shape = CardShape,
                clip = false,
                ambientColor = shadow,
                spotColor = shadow,
            )
            .clip(CardShape)
            .background(cardColor)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(item.tint.copy(alpha = if (light) 0.14f else 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon.asImageVector(),
                contentDescription = iconDescription,
                tint = item.tint,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun MoreMenuIcon.asImageVector(): ImageVector = when (this) {
    MoreMenuIcon.Profile -> Icons.Rounded.Person
    MoreMenuIcon.Categories -> Icons.Rounded.Category
    MoreMenuIcon.Transactions -> Icons.Rounded.ReceiptLong
    MoreMenuIcon.Reports -> Icons.Rounded.Insights
    MoreMenuIcon.Reminders -> Icons.Rounded.Alarm
    MoreMenuIcon.Notifications -> Icons.Rounded.Notifications
    MoreMenuIcon.Settings -> Icons.Rounded.Settings
    MoreMenuIcon.Help -> Icons.Rounded.SupportAgent
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
