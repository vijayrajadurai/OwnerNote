package com.shopai.app.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.shopai.app.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Warning as WarningColor
import com.shopai.app.util.HandwrittenTransactionParser
import com.shopai.app.util.LinePrefill
import com.shopai.app.util.OcrBox
import com.shopai.app.util.OcrLine
import com.shopai.app.util.TxnDirection
import java.time.LocalDate

/**
 * One handwritten line in the quick-correction list: OCR could not read it
 * completely, so the owner fills in what is missing next to its picture.
 */
@Stable
class QuickFixItem(
    val key: Long,
    val pageKey: Long?,
    val box: OcrBox?,
    /** What OCR read on the line, if anything. */
    val ocrText: String?,
    /** Letters read too unclearly to pre-fill as the name; offered as a tap-to-use hint. */
    val nameHint: String?,
    val amountUnclear: Boolean,
    /** The extracted row this completes, or null for a line that made no row. */
    val row: DraftRow?,
    /** The not-understood line this came from (removed from that list once added). */
    val source: Pair<Long, OcrLine>?,
    val initial: TxnForm,
    /** Show a taller picture (a whole page where no lines were found). */
    val wholePage: Boolean = false,
) {
    var form by mutableStateOf(initial)
    var skipped by mutableStateOf(false)
    val edited: Boolean get() = form != initial
}

/**
 * The quick-fix form for a line that made no transaction: only what OCR
 * read is filled in; the date is the line's own, else the note's, else today.
 */
fun prefillForm(line: OcrLine, noteDate: LocalDate?, today: LocalDate = LocalDate.now()): Pair<TxnForm, LinePrefill> {
    val p = HandwrittenTransactionParser.prefill(line, today)
    val form = TxnForm(
        personName = p.personName.orEmpty(),
        amount = p.amount?.stripTrailingZeros()?.toPlainString().orEmpty(),
        date = p.date ?: noteDate ?: today,
        direction = p.direction,
    )
    return form to p
}

/**
 * Fast correction for handwriting OCR could not fully read: every
 * incomplete line with its picture and inline name / amount / Credit-Debit /
 * date fields. Only what OCR actually read is pre-filled.
 */
@Composable
fun QuickFixStage(
    modifier: Modifier,
    items: List<QuickFixItem>,
    pagePath: (Long?) -> String?,
    onAddLine: () -> Unit,
    onDone: () -> Unit,
) {
    val ready = items.count { !it.skipped && it.form.isComplete }
    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        item {
            Text(stringResource(R.string.hw_quick_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.hw_quick_caption),
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            QuickFixCard(index + 1, item, pagePath(item.pageKey))
        }
        item {
            OutlinedButton(onClick = onAddLine, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.hw_quick_add_line))
            }
            PrimaryButton(
                label = stringResource(R.string.hw_quick_done, ready),
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                onClick = onDone,
            )
        }
    }
}

@Composable
private fun QuickFixCard(number: Int, item: QuickFixItem, pagePath: String?) {
    val form = item.form
    ShopCard(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hw_quick_line, number), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { item.skipped = !item.skipped }) {
                Text(stringResource(if (item.skipped) R.string.hw_quick_unskip else R.string.hw_quick_skip))
            }
        }
        // The handwriting itself, so it can be typed exactly.
        LineImage(pagePath, item.box, height = if (item.wholePage) 260.dp else 72.dp)
        Text(
            item.ocrText?.let { stringResource(R.string.hw_quick_ocr_read, it) } ?: stringResource(R.string.hw_quick_not_read),
            style = MaterialTheme.typography.bodySmall,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (item.skipped) {
            Text(stringResource(R.string.hw_quick_skipped), color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            return@ShopCard
        }
        ShopTextField(
            stringResource(R.string.hw_person_name),
            form.personName,
            { item.form = form.copy(personName = it) },
        )
        if (item.nameHint != null && form.personName.isBlank()) {
            AssistChip(
                onClick = { item.form = form.copy(personName = item.nameHint) },
                label = { Text(stringResource(R.string.hw_quick_use_hint, item.nameHint)) },
            )
        }
        ShopTextField(
            stringResource(R.string.bill_item_amount),
            form.amount,
            { item.form = form.copy(amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == 'k' || c == 'K' }) },
            placeholder = "0.00",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (item.amountUnclear && form.amount == item.initial.amount && form.amount.isNotBlank()) {
            Text(stringResource(R.string.hw_quick_check_amount), style = MaterialTheme.typography.bodySmall, color = WarningColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            FilterChip(
                selected = form.direction == TxnDirection.CREDIT,
                onClick = { item.form = form.copy(direction = TxnDirection.CREDIT) },
                label = { Text(stringResource(R.string.hw_type_credit_long)) },
            )
            FilterChip(
                selected = form.direction == TxnDirection.DEBIT,
                onClick = { item.form = form.copy(direction = TxnDirection.DEBIT) },
                label = { Text(stringResource(R.string.hw_type_debit_long)) },
            )
        }
        FutureDatePickerField(
            label = stringResource(R.string.capture_note_date),
            selectedDate = form.date,
            onDateSelected = { item.form = form.copy(date = it) },
            placeholder = stringResource(R.string.hw_date_not_specified),
            allowEmpty = true,
            anyDate = true,
        )
        val missing = buildList {
            if (form.personName.isBlank()) add(stringResource(R.string.hw_quick_field_name))
            if (form.parsedAmount == null) add(stringResource(R.string.hw_quick_field_amount))
            if (form.direction == TxnDirection.UNKNOWN) add(stringResource(R.string.hw_quick_field_type))
        }
        Text(
            if (missing.isEmpty()) "✓ " + stringResource(R.string.hw_quick_complete) else stringResource(R.string.hw_quick_missing, missing.joinToString(", ")),
            color = if (missing.isEmpty()) LedgerCredit else WarningColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
