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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
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
import com.shopai.app.ui.reminders.samplePaymentReminders
import com.shopai.app.ui.theme.ShopAiTheme
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.formatDisplayDate
import com.shopai.app.util.formatInr
import kotlin.math.absoluteValue

private val LimeBorder = Color(0xFFB8F53D)
private val CardRadius = 28.dp
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
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.home_payment_reminders_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = ShopAiThemeColors.onSurface,
        )

        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.home_payment_reminders_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
            )
            return@Column
        }

        val peekFraction = 0.18f
        val peekWidth = (LocalConfiguration.current.screenWidthDp * peekFraction).dp
        val initialPage = items.indexOfFirst { it.urgency == ReminderUrgency.DUE_TODAY }
            .takeIf { it >= 0 } ?: 0
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { items.size })
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = peekWidth),
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(312.dp),
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

        if (items.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            MaterialTheme.colorScheme.primary
                        } else {
                            ShopAiThemeColors.onSurfaceVariant.copy(alpha = 0.35f)
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
    val elevation = lerp(2f, 14f, selectedFraction)
    val borderColor = androidx.compose.ui.graphics.lerp(
        Color.White.copy(alpha = if (dark) 0.10f else 0.18f),
        LimeBorder,
        selectedFraction,
    )
    val fill = if (dark) {
        listOf(Color(0xFF163226), Color(0xFF0B0F0C))
    } else {
        listOf(Color(0xFF2FA36C), Color(0xFF1A5C40))
    }
    val amount = reminder.amount?.let { formatInr(it) } ?: "—"
    val dueLabel = formatDisplayDate(reminder.dueDateIso)
    val cardCd = stringResource(
        R.string.cd_payment_reminder_card,
        reminder.partyName,
        amount,
        stringResource(R.string.reminder_due_prefix, dueLabel),
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = cardAlpha
            },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = 10.dp)
                .clip(RoundedCornerShape(CardRadius))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x662E9F6E),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Card(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = cardCd }
                .clickable(onClick = onOpenDetails),
            shape = RoundedCornerShape(CardRadius),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation.dp),
            border = BorderStroke(1.6.dp, borderColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(fill)),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountBalanceWallet,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier
                        .size(120.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 18.dp, y = (-12).dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ReminderUrgencyBadge(urgency = reminder.urgency)

                    Text(
                        text = amount,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )

                    Text(
                        text = reminder.partyName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(
                        text = reminder.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = dueLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .weight(1f),
                        )
                        if (showArrow) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(LimeBorder)
                                    .clickable(onClick = onOpenDetails),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = stringResource(R.string.reminder_view_details),
                                    tint = Color(0xFF123224),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
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
        modifier = modifier.fillMaxWidth().height(280.dp),
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

@Preview(showBackground = true, name = "Reminder carousel light")
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
