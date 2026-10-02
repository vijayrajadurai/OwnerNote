package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.ui.theme.Warning
import com.shopai.app.util.BillNotesFormatter
import com.shopai.app.util.PaymentState
import com.shopai.app.util.PaymentStatus

@Composable
fun paymentStateLabel(state: PaymentState): String = stringResource(
    when (state) {
        PaymentState.PAID -> R.string.status_paid
        PaymentState.OVERDUE -> R.string.status_overdue
        PaymentState.PARTIALLY_PAID -> R.string.status_partial
        PaymentState.UPCOMING -> R.string.status_upcoming
        PaymentState.PENDING -> R.string.status_pending
    },
)

fun paymentStateColor(state: PaymentState): Color = when (state) {
    PaymentState.PAID -> Success
    PaymentState.OVERDUE -> Danger
    PaymentState.PARTIALLY_PAID -> Warning
    PaymentState.UPCOMING -> Color(0xFF2E7DD7)
    PaymentState.PENDING -> Color(0xFF6B7F76)
}

/** "Overdue · Partly paid · ₹2,000 outstanding · 5 days late". */
@Composable
fun PaymentStatusChip(status: PaymentStatus, modifier: Modifier = Modifier) {
    val color = paymentStateColor(status.state)
    Row(modifier = modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            paymentStateLabel(status.state),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        val detail = buildList {
            if (status.partlyPaid && status.state != PaymentState.PARTIALLY_PAID) add(stringResource(R.string.status_partly_paid_short))
            if (status.state != PaymentState.PAID) add(stringResource(R.string.status_outstanding, BillNotesFormatter.rupees(status.outstanding)))
            status.daysLate?.takeIf { status.state != PaymentState.PAID }?.let { late ->
                add(
                    when {
                        late > 0 -> stringResource(R.string.status_days_late, late.toInt())
                        late == 0L -> stringResource(R.string.status_due_today)
                        else -> stringResource(R.string.status_due_in, (-late).toInt())
                    },
                )
            }
        }.joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
    }
}
