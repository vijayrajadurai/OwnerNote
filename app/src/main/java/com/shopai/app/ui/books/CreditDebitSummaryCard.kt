package com.shopai.app.ui.books

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.engine.DueSummary
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.Surface
import com.shopai.app.ui.theme.TextPrimary
import com.shopai.app.ui.theme.TextSecondary
import com.shopai.app.ui.theme.Warning
import java.time.LocalDate

@Composable
fun CreditDebitSummaryCard(
    container: AppContainer,
    refreshKey: Any?,
    onOpenCustomers: () -> Unit,
    onOpenSuppliers: () -> Unit,
    pendingReceivables: Double? = null,
    pendingPayables: Double? = null,
) {
    var collect by remember { mutableStateOf<DueSummary?>(null) }
    var pay by remember { mutableStateOf<DueSummary?>(null) }
    LaunchedEffect(refreshKey) {
        val s = container.books.session() ?: return@LaunchedEffect
        val today = LocalDate.now()
        collect = s.ledger.receivableDue(today)
        pay = s.ledger.payableDue(today)
    }
    val c = collect
    val p = pay
    if (c == null || p == null) {
        val recv = pendingReceivables ?: return
        val payAmt = pendingPayables ?: return
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LedgerSideCard(
                title = stringResource(R.string.home_receivable),
                total = com.shopai.app.util.formatInr(recv),
                accent = LedgerCredit,
                tint = Color(0xFFE8F7EE),
                down = true,
                overdueLabel = stringResource(R.string.home_due_overdue, 0),
                overdueValue = com.shopai.app.util.formatInr(0.0),
                soonLabel = stringResource(R.string.home_due_soon, 0),
                soonValue = com.shopai.app.util.formatInr(0.0),
                laterLabel = stringResource(R.string.home_due_later, 0),
                laterValue = com.shopai.app.util.formatInr(recv),
                onClick = onOpenCustomers,
                modifier = Modifier.weight(1f),
            )
            LedgerSideCard(
                title = stringResource(R.string.home_payable),
                total = com.shopai.app.util.formatInr(payAmt),
                accent = LedgerDebit,
                tint = Color(0xFFFDECEA),
                down = false,
                overdueLabel = stringResource(R.string.home_due_overdue, 0),
                overdueValue = com.shopai.app.util.formatInr(0.0),
                soonLabel = stringResource(R.string.home_due_soon, 0),
                soonValue = com.shopai.app.util.formatInr(0.0),
                laterLabel = stringResource(R.string.home_due_later, 0),
                laterValue = com.shopai.app.util.formatInr(payAmt),
                onClick = onOpenSuppliers,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LedgerSideCard(
            title = stringResource(R.string.home_receivable),
            total = InvoicePdf.money(c.totalPaise),
            accent = LedgerCredit,
            tint = Color(0xFFE8F7EE),
            down = true,
            overdueLabel = stringResource(R.string.home_due_overdue, c.overdueCount),
            overdueValue = InvoicePdf.money(c.overduePaise),
            soonLabel = stringResource(R.string.home_due_soon, c.dueSoonCount),
            soonValue = InvoicePdf.money(c.dueSoonPaise),
            laterLabel = stringResource(R.string.home_due_later, c.laterCount),
            laterValue = InvoicePdf.money(c.laterPaise),
            onClick = onOpenCustomers,
            modifier = Modifier.weight(1f),
        )
        LedgerSideCard(
            title = stringResource(R.string.home_payable),
            total = InvoicePdf.money(p.totalPaise),
            accent = LedgerDebit,
            tint = Color(0xFFFDECEA),
            down = false,
            overdueLabel = stringResource(R.string.home_due_overdue, p.overdueCount),
            overdueValue = InvoicePdf.money(p.overduePaise),
            soonLabel = stringResource(R.string.home_due_soon, p.dueSoonCount),
            soonValue = InvoicePdf.money(p.dueSoonPaise),
            laterLabel = stringResource(R.string.home_due_later, p.laterCount),
            laterValue = InvoicePdf.money(p.laterPaise),
            onClick = onOpenSuppliers,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LedgerSideCard(
    title: String,
    total: String,
    accent: Color,
    tint: Color,
    down: Boolean,
    overdueLabel: String,
    overdueValue: String,
    soonLabel: String,
    soonValue: String,
    laterLabel: String,
    laterValue: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x1A1E6B4E), spotColor = Color(0x1A1E6B4E))
            .clip(RoundedCornerShape(24.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (down) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(title, style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(total, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        DueLine(Color(0xFFE24B4B), overdueLabel, overdueValue)
        DueLine(Warning, soonLabel, soonValue)
        DueLine(TextSecondary, laterLabel, laterValue)
    }
}

@Composable
private fun DueLine(dot: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
        }
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}
