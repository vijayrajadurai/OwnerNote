package com.shopai.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.shopai.app.R
import com.shopai.app.ui.reminders.PaymentReminder
import com.shopai.app.ui.reminders.ReminderUrgency
import com.shopai.app.ui.reminders.isCustomerCredit
import com.shopai.app.ui.reminders.samplePaymentReminders
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.PrimaryDark
import com.shopai.app.ui.theme.ShopAiTheme
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.formatDisplayDate
import com.shopai.app.util.formatInr
import kotlin.math.absoluteValue

private val CardRadius = 28.dp
private val PastelCardRadius = 32.dp
private val HeaderPastels = listOf(
    Color(0xFFD4EDC9),
    Color(0xFFF2E6B8),
    Color(0xFFF6F1EA),
    Color(0xFFE9D5C8),
)
private val OverdueRed = Color(0xFFE53935)
private val DueTodayOrange = Color(0xFFFF6A3D)
private val UpcomingGreen = Color(0xFF2E9F6E)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReminderCarousel(
    items: List<PaymentReminder>,
    onOpenDetails: (PaymentReminder) -> Unit,
    onMarkPaid: (PaymentReminder) -> Unit = {},
    modifier: Modifier = Modifier,
    onHeader: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (onHeader) 0.dp else 14.dp),
    ) {
        if (!onHeader) {
            Text(
                text = stringResource(R.string.home_payment_reminders_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ShopAiThemeColors.onSurface,
            )
        }

        if (items.isEmpty()) {
            if (!onHeader) {
                Text(
                    text = stringResource(R.string.home_payment_reminders_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
            }
            return@Column
        }

        val inspectionMode = LocalInspectionMode.current
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        val peekWidth = (screenWidthDp * 0.18f).dp
        val initialPage = items.indexOfFirst { it.urgency == ReminderUrgency.DUE_TODAY }
            .takeIf { it >= 0 } ?: 0
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { items.size })
        if (onHeader) {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = peekWidth),
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            ) { page ->
                val reminder = items[page]
                HeaderPastelReminderCard(
                    reminder = reminder,
                    fillColor = HeaderPastels[page % HeaderPastels.size],
                    onOpenDetails = { onOpenDetails(reminder) },
                )
            }
        } else {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = peekWidth),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (inspectionMode) Modifier.height(340.dp) else Modifier),
            ) { page ->
                val reminder = items[page]
                val pageOffset = (
                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    ).absoluteValue
                ReminderCard(
                    reminder = reminder,
                    pageOffset = pageOffset,
                    onOpenDetails = { onOpenDetails(reminder) },
                )
            }
        }

        if (!onHeader && items.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onHeader) Modifier.padding(horizontal = 20.dp) else Modifier),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { index ->
                    val selected = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(
                        targetValue = if (selected) 18.dp else 7.dp,
                        animationSpec = tween(durationMillis = 280),
                        label = "dotWidth",
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (selected) {
                            if (onHeader) Color.White else MaterialTheme.colorScheme.primary
                        } else {
                            if (onHeader) {
                                Color.White.copy(alpha = 0.35f)
                            } else {
                                ShopAiThemeColors.onSurfaceVariant.copy(alpha = 0.35f)
                            }
                        },
                        animationSpec = tween(durationMillis = 280),
                        label = "dotColor",
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(7.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(dotColor)
                            .semantics {
                                contentDescription = "Reminder ${index + 1} of ${items.size}"
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderPastelReminderCard(
    reminder: PaymentReminder,
    fillColor: Color,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amount = reminder.amount?.let { formatInr(it) } ?: "—"
    val dueLabel = formatDisplayDate(reminder.dueDateIso)
    val cardCd = stringResource(
        R.string.cd_payment_reminder_card,
        reminder.partyName,
        amount,
        stringResource(R.string.reminder_due_prefix, dueLabel),
    )
    val isCredit = reminder.isCustomerCredit()
    val financeIcon = if (isCredit) {
        Icons.Outlined.AccountBalanceWallet
    } else {
        Icons.Outlined.Payments
    }
    val kindLabel = stringResource(if (isCredit) R.string.home_receivable else R.string.home_payable)
    val iconTint = Color(0xFF1C1C1A).copy(alpha = 0.72f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(PastelCardRadius))
            .background(fillColor)
            .clickable(onClick = onOpenDetails)
            .semantics { contentDescription = cardCd },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReminderUrgencyBadge(urgency = reminder.urgency)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onOpenDetails),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.reminder_view_details),
                        tint = Color(0xFF1C1C1A),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = financeIcon,
                        contentDescription = kindLabel,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = reminder.partyName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1C1C1A).copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = amount,
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1C1C1A),
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF1C1C1A).copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun ReminderCard(
    reminder: PaymentReminder,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
    pageOffset: Float = 0f,
    showArrow: Boolean = true,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val selectedFraction = 1f - pageOffset.coerceIn(0f, 1f)
    val scale = lerp(0.88f, 1f, selectedFraction)
    val cardAlpha = lerp(0.62f, 1f, selectedFraction)
    val pay = reminder.kind.equals("PAYMENT", ignoreCase = true) ||
        reminder.kind.equals("DEBIT", ignoreCase = true)
    val accent = if (pay) LedgerDebit else LedgerCredit
    val bodyColor = if (dark) Color.White else Color(0xFF1C1C1A)
    val muted = bodyColor.copy(alpha = 0.78f)
    val amount = reminder.amount?.let { formatInr(it) } ?: "—"
    val dueLabel = formatDisplayDate(reminder.dueDateIso)
    val cardCd = stringResource(
        R.string.cd_payment_reminder_card,
        reminder.partyName,
        amount,
        stringResource(R.string.reminder_due_prefix, dueLabel),
    )
    val innerStroke = Brush.linearGradient(
        colors = listOf(PrimaryDark, Color(0xFF1E6B4E), PrimaryDark),
    )
    val outerBorder = PrimaryDark

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = cardAlpha
            },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = cardCd }
                .clickable(onClick = onOpenDetails)
                .drawWithContent {
                    drawContent()
                    val inset = 3.5.dp.toPx()
                    val radius = (CardRadius.toPx() - inset).coerceAtLeast(0f)
                    drawRoundRect(
                        brush = innerStroke,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2f, size.height - inset * 2f),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                },
            shape = RoundedCornerShape(CardRadius),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.4.dp, outerBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = amount,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )

                Text(
                    text = reminder.partyName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bodyColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = reminder.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccessTime,
                        contentDescription = null,
                        tint = muted,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = dueLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = muted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (showArrow) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.reminder_view_details),
                            tint = accent,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(onClick = onOpenDetails),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PaymentReminderCard(
    reminder: PaymentReminder,
    onOpenDetails: () -> Unit,
    onMarkPaid: () -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
) {
    ReminderCard(
        reminder = reminder,
        onOpenDetails = onOpenDetails,
        modifier = modifier.fillMaxWidth(),
        pageOffset = 0f,
        showArrow = showActions,
    )
}

@Composable
fun ReminderUrgencyBadge(
    urgency: ReminderUrgency,
    modifier: Modifier = Modifier,
) {
    val (labelRes, background) = when (urgency) {
        ReminderUrgency.OVERDUE -> R.string.reminder_badge_overdue to OverdueRed
        ReminderUrgency.DUE_TODAY -> R.string.reminder_badge_due_today to DueTodayOrange
        ReminderUrgency.UPCOMING -> R.string.reminder_badge_upcoming to UpcomingGreen
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 520, name = "Reminder carousel light")
@Composable
private fun ReminderCarouselPreview() {
    ShopAiTheme {
        ReminderCarousel(
            items = samplePaymentReminders(),
            onOpenDetails = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    showBackground = true,
    widthDp = 390,
    heightDp = 520,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    name = "Reminder carousel dark",
)
@Composable
private fun ReminderCarouselDarkPreview() {
    ShopAiTheme {
        Box(Modifier.background(Color(0xFF121412)).padding(16.dp)) {
            ReminderCarousel(
                items = samplePaymentReminders(),
                onOpenDetails = {},
            )
        }
    }
}
