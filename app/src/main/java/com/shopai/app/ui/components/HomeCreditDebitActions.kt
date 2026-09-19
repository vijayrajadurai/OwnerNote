package com.shopai.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.DangerMuted
import com.shopai.app.ui.theme.PrimaryMuted
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success

@Composable
fun HomeCreditDebitActions(
    onCreditClick: () -> Unit,
    onDebitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeTxnQuickActionCard(
            type = TransactionSaveType.CREDIT,
            onClick = onCreditClick,
            modifier = Modifier.weight(1f),
        )
        HomeTxnQuickActionCard(
            type = TransactionSaveType.DEBIT,
            onClick = onDebitClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeTxnQuickActionCard(
    type: TransactionSaveType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = when (type) {
        TransactionSaveType.CREDIT -> HomeTxnPalette(
            accent = Success,
            background = PrimaryMuted,
            gradient = listOf(Color(0xFF1E6B4E), Color(0xFF2E9F6E)),
            icon = "↑",
            titleRes = R.string.home_credit_action_title,
            captionRes = R.string.home_credit_action_caption,
        )
        TransactionSaveType.DEBIT -> HomeTxnPalette(
            accent = Danger,
            background = DangerMuted,
            gradient = listOf(Color(0xFFD6503C), Color(0xFFE88A5A)),
            icon = "↓",
            titleRes = R.string.home_debit_action_title,
            captionRes = R.string.home_debit_action_caption,
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "homeTxnPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, palette.accent.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            .background(palette.background)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(Brush.linearGradient(palette.gradient)),
        ) {
            Text(
                text = palette.icon,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = stringResource(palette.titleRes),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = palette.accent,
        )
        Text(
            text = stringResource(palette.captionRes),
            style = MaterialTheme.typography.bodySmall,
            color = ShopAiThemeColors.onSurfaceVariant,
        )
    }
}

private data class HomeTxnPalette(
    val accent: Color,
    val background: Color,
    val gradient: List<Color>,
    val icon: String,
    val titleRes: Int,
    val captionRes: Int,
)
