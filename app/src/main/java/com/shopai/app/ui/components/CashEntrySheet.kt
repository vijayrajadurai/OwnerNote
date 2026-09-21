package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.model.DailyCashEntry
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.model.DailyCashPaymentMode
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.Success
import com.shopai.app.util.isValidAmount

sealed class CashEntrySheetMode {
    data class Add(val dateKey: String, val type: DailyCashEntryType) : CashEntrySheetMode()
    data class Edit(val entry: DailyCashEntry) : CashEntrySheetMode()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashEntrySheet(
    mode: CashEntrySheetMode,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    isSaving: Boolean,
    onSave: (
        type: DailyCashEntryType,
        amount: Double,
        paymentMode: DailyCashPaymentMode,
        note: String,
    ) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val isEdit = mode is CashEntrySheetMode.Edit
    var entryType by remember(mode) {
        mutableStateOf(
            when (mode) {
                is CashEntrySheetMode.Add -> mode.type
                is CashEntrySheetMode.Edit -> mode.entry.type
            },
        )
    }
    var amount by remember(mode) {
        mutableStateOf(
            when (mode) {
                is CashEntrySheetMode.Add -> ""
                is CashEntrySheetMode.Edit -> mode.entry.amount.toString()
            },
        )
    }
    var paymentMode by remember(mode) {
        mutableStateOf(
            when (mode) {
                is CashEntrySheetMode.Add -> DailyCashPaymentMode.CASH
                is CashEntrySheetMode.Edit -> mode.entry.paymentMode
            },
        )
    }
    var note by remember(mode) {
        mutableStateOf(
            when (mode) {
                is CashEntrySheetMode.Add -> ""
                is CashEntrySheetMode.Edit -> mode.entry.note ?: ""
            },
        )
    }

    val sheetBackground = MaterialTheme.colorScheme.surface
    val sheetText = MaterialTheme.colorScheme.onSurface
    val sheetMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val sheetBorder = MaterialTheme.colorScheme.outline

    val title = if (isEdit) {
        stringResource(R.string.cash_note_edit_title)
    } else {
        stringResource(R.string.cash_note_add_title)
    }
    val canSave = isValidAmount(amount)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = sheetText,
        unfocusedTextColor = sheetText,
        focusedBorderColor = Success,
        unfocusedBorderColor = sheetBorder,
        cursorColor = sheetText,
        focusedPlaceholderColor = sheetMuted,
        unfocusedPlaceholderColor = sheetMuted,
    )

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                color = sheetText,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleMedium,
            )

            if (isEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypePill(
                        label = stringResource(R.string.cash_note_in),
                        selected = entryType == DailyCashEntryType.IN,
                        selectedColor = Success,
                        textColor = sheetText,
                        mutedColor = sheetMuted,
                        borderColor = sheetBorder,
                        onClick = { entryType = DailyCashEntryType.IN },
                        modifier = Modifier.weight(1f),
                    )
                    TypePill(
                        label = stringResource(R.string.cash_note_out),
                        selected = entryType == DailyCashEntryType.OUT,
                        selectedColor = Danger,
                        textColor = sheetText,
                        mutedColor = sheetMuted,
                        borderColor = sheetBorder,
                        onClick = { entryType = DailyCashEntryType.OUT },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            FieldLabel(stringResource(R.string.cash_note_amount), sheetMuted)
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                placeholder = { Text("0", color = sheetMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp),
            )

            FieldLabel(stringResource(R.string.cash_note_payment_mode), sheetMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(
                    label = stringResource(R.string.cash_note_cash),
                    selected = paymentMode == DailyCashPaymentMode.CASH,
                    textColor = sheetText,
                    mutedColor = sheetMuted,
                    borderColor = sheetBorder,
                    onClick = { paymentMode = DailyCashPaymentMode.CASH },
                    modifier = Modifier.weight(1f),
                )
                ModeChip(
                    label = stringResource(R.string.cash_note_upi),
                    selected = paymentMode == DailyCashPaymentMode.UPI,
                    textColor = sheetText,
                    mutedColor = sheetMuted,
                    borderColor = sheetBorder,
                    onClick = { paymentMode = DailyCashPaymentMode.UPI },
                    modifier = Modifier.weight(1f),
                )
            }

            FieldLabel(stringResource(R.string.cash_note_note), sheetMuted)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text(stringResource(R.string.cash_note_note_placeholder), color = sheetMuted) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp),
            )

            PrimaryButton(
                label = stringResource(R.string.cash_note_save),
                enabled = canSave && !isSaving,
                loading = isSaving,
                onClick = { onSave(entryType, amount.trim().toDouble(), paymentMode, note) },
            )

            if (isEdit && onDelete != null) {
                Text(
                    text = stringResource(R.string.cash_note_delete),
                    color = Danger,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(enabled = !isSaving, onClick = onDelete)
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, mutedColor: Color) {
    Text(text, color = mutedColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun TypePill(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    textColor: Color,
    mutedColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = label,
        color = if (selected) textColor else mutedColor,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(if (selected) selectedColor.copy(alpha = 0.15f) else Color.Transparent, shape)
            .border(1.dp, if (selected) selectedColor else borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    textColor: Color,
    mutedColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = label,
        color = if (selected) textColor else mutedColor,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(if (selected) Success.copy(alpha = 0.12f) else Color.Transparent, shape)
            .border(1.dp, if (selected) Success else borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        textAlign = TextAlign.Center,
    )
}
