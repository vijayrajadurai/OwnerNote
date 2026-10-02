package com.shopai.app.ui.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.engine.DueSummary
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Warning
import java.time.LocalDate

/**
 * Home: every Credit (to collect) and Debit (to pay) — from bills, handwritten
 * notes, voice and manual entries alike — split into overdue, due this week
 * and later. Read from the books; nothing is added up on screen.
 */
@Composable
fun CreditDebitSummaryCard(container: AppContainer, refreshKey: Any?, onOpenCustomers: () -> Unit, onOpenSuppliers: () -> Unit) {
    var collect by remember { mutableStateOf<DueSummary?>(null) }
    var pay by remember { mutableStateOf<DueSummary?>(null) }
    LaunchedEffect(refreshKey) {
        val s = container.books.session() ?: return@LaunchedEffect
        val today = LocalDate.now()
        collect = s.ledger.receivableDue(today)
        pay = s.ledger.payableDue(today)
    }
    val c = collect ?: return
    val p = pay ?: return
    ShopCard {
        Text(stringResource(R.string.home_credit_debit_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
        Side(stringResource(R.string.home_credit_side), c, LedgerCredit, onOpenCustomers)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
        Side(stringResource(R.string.home_debit_side), p, LedgerDebit, onOpenSuppliers)
    }
}

@Composable
private fun Side(title: String, d: DueSummary, color: Color, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.SemiBold, color = color)
            Text(InvoicePdf.money(d.totalPaise), fontWeight = FontWeight.Bold, color = color)
        }
        Line(stringResource(R.string.home_due_overdue, d.overdueCount), d.overduePaise, Danger)
        Line(stringResource(R.string.home_due_soon, d.dueSoonCount), d.dueSoonPaise, Warning)
        Line(stringResource(R.string.home_due_later, d.laterCount), d.laterPaise, ShopAiThemeColors.onSurfaceVariant)
    }
}

@Composable
private fun Line(label: String, paise: Long, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        Text(InvoicePdf.money(paise), style = MaterialTheme.typography.bodySmall, color = color)
    }
}
