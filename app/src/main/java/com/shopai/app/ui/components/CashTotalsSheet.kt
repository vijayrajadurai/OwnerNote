package com.shopai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.model.DailyCashTotals
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.Success
import com.shopai.app.util.formatInr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashTotalsSheet(
    totals: DailyCashTotals,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    onDismiss: () -> Unit,
) {
    val sheetBackground = MaterialTheme.colorScheme.surface
    val sheetText = MaterialTheme.colorScheme.onSurface
    val sheetMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val sheetBorder = MaterialTheme.colorScheme.outline

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.cash_note_totals_title),
                color = sheetText,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            TotalsSection(
                title = stringResource(R.string.cash_note_section_total),
                inAmount = totals.totalIn,
                outAmount = totals.totalOut,
                netAmount = totals.net,
                sheetMuted = sheetMuted,
                sheetBorder = sheetBorder,
            )
            TotalsSection(
                title = stringResource(R.string.cash_note_upi),
                inAmount = totals.upiIn,
                outAmount = totals.upiOut,
                netAmount = totals.upiNet,
                sheetMuted = sheetMuted,
                sheetBorder = sheetBorder,
            )
            TotalsSection(
                title = stringResource(R.string.cash_note_cash),
                inAmount = totals.cashIn,
                outAmount = totals.cashOut,
                netAmount = totals.cashNet,
                sheetMuted = sheetMuted,
                sheetBorder = sheetBorder,
            )
        }
    }
}

@Composable
private fun TotalsSection(
    title: String,
    inAmount: Double,
    outAmount: Double,
    netAmount: Double,
    sheetMuted: Color,
    sheetBorder: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider(color = sheetBorder)
        Text(
            text = title.uppercase(),
            color = sheetMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        TotalsRow(stringResource(R.string.cash_note_in), inAmount, Success, sheetMuted)
        TotalsRow(stringResource(R.string.cash_note_out), outAmount, Danger, sheetMuted)
        TotalsRow(
            stringResource(R.string.cash_note_net),
            netAmount,
            when {
                netAmount > 0 -> Success
                netAmount < 0 -> Danger
                else -> sheetMuted
            },
            sheetMuted,
        )
    }
}

@Composable
private fun TotalsRow(label: String, amount: Double, color: Color, mutedColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = mutedColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(formatInr(amount), color = color, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    }
}
