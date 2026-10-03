package com.shopai.app.ui.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.shopai.app.R
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerCreditMuted
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.LedgerDebitMuted
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Warning as WarningColor
import com.shopai.app.util.HandwrittenTransactionParser
import com.shopai.app.util.OcrBox
import com.shopai.app.util.ReviewIssue
import com.shopai.app.util.TxnDirection
import com.shopai.app.util.formatLocalDateForDisplay
import com.shopai.app.util.formatRupees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// ---------------------------------------------------------------- models

/** Values in the Edit / Add Transaction form. */
data class TxnForm(
    val personName: String = "",
    val amount: String = "",
    val date: LocalDate? = null,
    val direction: TxnDirection = TxnDirection.UNKNOWN,
    val description: String = "",
    val notes: String = "",
    /** Handwritten bill only: shows the Paid and Due date fields. */
    val isBill: Boolean = false,
    /** Already paid on the bill; blank = nothing paid. */
    val paid: String = "",
    val dueDate: LocalDate? = null,
) {
    /** Accepts "1,500", "₹1500", "1.5K". */
    val parsedAmount: BigDecimal? get() = parseMoney(amount)?.takeIf { it.signum() > 0 }

    /** Paid on the bill: zero when blank, null when unreadable or more than the bill. */
    val parsedPaid: BigDecimal?
        get() {
            if (paid.isBlank()) return BigDecimal.ZERO.setScale(2)
            val value = parseMoney(paid) ?: return null
            val total = parsedAmount
            return value.takeIf { total == null || it <= total }
        }

    /** Still to be paid on a handwritten bill. */
    val balance: BigDecimal? get() = parsedAmount?.let { total -> parsedPaid?.let { total - it } }

    val isComplete: Boolean get() = personName.isNotBlank() && parsedAmount != null && direction != TxnDirection.UNKNOWN && (!isBill || parsedPaid != null)

    private fun parseMoney(text: String): BigDecimal? {
        val cleaned = text.replace(",", "").replace("₹", "").trim()
        val k = cleaned.endsWith("k", ignoreCase = true)
        val number = (if (k) cleaned.dropLast(1) else cleaned).trim().toBigDecimalOrNull() ?: return null
        val value = if (k) number * BigDecimal(1000) else number
        return value.takeIf { it.signum() >= 0 && it < BigDecimal("1000000000") }?.setScale(2, RoundingMode.HALF_UP)
    }
}

/** Everything a transaction card shows. */
data class TxnRowUi(
    val personName: String?,
    val amount: BigDecimal?,
    val date: LocalDate?,
    val direction: TxnDirection,
    val description: String?,
    val nameConfidence: Int?,
    val amountConfidence: Int?,
    val dateConfidence: Int?,
    val issues: Set<ReviewIssue>,
    val correctedByUser: Boolean,
    val hasSource: Boolean,
    val syncLabel: String? = null,
)

// ------------------------------------------------------------- formatting

@Composable
fun directionLabel(direction: TxnDirection): String = stringResource(
    when (direction) {
        TxnDirection.CREDIT -> R.string.hw_type_credit
        TxnDirection.DEBIT -> R.string.hw_type_debit
        TxnDirection.UNKNOWN -> R.string.hw_type_unknown
    },
)

@Composable
fun directionMeaning(direction: TxnDirection): String = stringResource(
    when (direction) {
        TxnDirection.CREDIT -> R.string.hw_direction_receive
        TxnDirection.DEBIT -> R.string.hw_direction_give
        TxnDirection.UNKNOWN -> R.string.hw_direction_unknown
    },
)

@Composable
fun issueLabel(issue: ReviewIssue): String = stringResource(
    when (issue) {
        ReviewIssue.NAME_MISSING -> R.string.hw_issue_name_missing
        ReviewIssue.AMOUNT_MISSING -> R.string.hw_issue_amount_missing
        ReviewIssue.AMOUNT_AMBIGUOUS -> R.string.hw_issue_amount_ambiguous
        ReviewIssue.TYPE_UNKNOWN -> R.string.hw_issue_type_unknown
        ReviewIssue.NAME_UNCLEAR -> R.string.hw_issue_name_unclear
        ReviewIssue.AMOUNT_UNCLEAR -> R.string.hw_issue_amount_unclear
        ReviewIssue.DATE_UNCLEAR -> R.string.hw_issue_date_unclear
        ReviewIssue.YEAR_ASSUMED -> R.string.hw_issue_year_assumed
        ReviewIssue.DATE_MISSING -> R.string.hw_issue_date_missing
    },
)

@Composable
fun dateLabel(date: LocalDate?): String = date?.let { formatLocalDateForDisplay(it) } ?: stringResource(R.string.hw_date_not_specified)

// ------------------------------------------------------------------ card

/**
 * One transaction as a card: person, amount, date, Credit/Debit, status,
 * confidence, and Edit / Delete / View Source actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionCard(
    row: TxnRowUi,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onViewSource: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onSelectedChange: ((Boolean) -> Unit)? = null,
    onMarkChecked: (() -> Unit)? = null,
) {
    val blocking = row.issues.filter { it.blocksSave }
    val threshold = HandwrittenTransactionParser.CONFIDENCE_THRESHOLD
    ShopCard(
        modifier = modifier
            .padding(bottom = 8.dp)
            .then(if (blocking.isNotEmpty()) Modifier.border(1.5.dp, WarningColor, RoundedCornerShape(20.dp)) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null && onSelectedChange != null) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.personName ?: stringResource(R.string.hw_needs_review_value),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (row.personName == null || (row.nameConfidence ?: 100) < threshold) WarningColor else ShopAiThemeColors.onSurface,
                )
                Text(
                    dateLabel(row.date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if ((row.dateConfidence ?: 100) < threshold) WarningColor else ShopAiThemeColors.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    row.amount?.let { formatRupees(it) } ?: stringResource(R.string.hw_needs_review_value),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        row.amount == null || (row.amountConfidence ?: 100) < threshold -> WarningColor
                        row.direction == TxnDirection.CREDIT -> LedgerCredit
                        row.direction == TxnDirection.DEBIT -> LedgerDebit
                        else -> ShopAiThemeColors.onSurface
                    },
                )
                DirectionBadge(row.direction)
            }
        }
        row.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }

        // Status, confidence and sync.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (blocking.isEmpty()) {
                StatusPill(
                    stringResource(if (row.correctedByUser) R.string.hw_status_checked else R.string.hw_status_verified),
                    LedgerCredit,
                    Icons.Default.CheckCircle,
                )
            } else {
                StatusPill(stringResource(R.string.hw_status_needs_review), WarningColor, Icons.Default.Warning)
            }
            confidenceText(row)?.let { StatusPill(it, ShopAiThemeColors.onSurfaceVariant, null) }
            row.syncLabel?.let { StatusPill(it, ShopAiThemeColors.onSurfaceVariant, null) }
        }
        if (row.issues.isNotEmpty()) {
            Text(
                row.issues.map { issueLabel(it) }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (blocking.isNotEmpty()) WarningColor else ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            if (onMarkChecked != null && blocking.isNotEmpty() && row.personName != null && row.amount != null && row.direction != TxnDirection.UNKNOWN) {
                TextButton(onClick = onMarkChecked) { Text(stringResource(R.string.hw_mark_checked)) }
            }
            Box(modifier = Modifier.weight(1f))
            if (onViewSource != null && row.hasSource) {
                IconButton(onClick = onViewSource) { Icon(Icons.Default.Image, contentDescription = stringResource(R.string.hw_view_source)) }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.capture_edit)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.capture_delete), tint = Danger) }
        }
    }
}

@Composable
private fun confidenceText(row: TxnRowUi): String? {
    val parts = listOfNotNull(
        row.nameConfidence?.let { stringResource(R.string.hw_conf_name, it) },
        row.amountConfidence?.let { stringResource(R.string.hw_conf_amount, it) },
        row.dateConfidence?.let { stringResource(R.string.hw_conf_date, it) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
fun DirectionBadge(direction: TxnDirection) {
    val (fg, bg) = when (direction) {
        TxnDirection.CREDIT -> LedgerCredit to LedgerCreditMuted
        TxnDirection.DEBIT -> LedgerDebit to LedgerDebitMuted
        TxnDirection.UNKNOWN -> WarningColor to WarningColor.copy(alpha = 0.12f)
    }
    Text(
        directionLabel(direction),
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusPill(text: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(14.dp).padding(end = 2.dp)) }
        Text(text, color = color, fontSize = 11.sp)
    }
}

// ------------------------------------------------------------ edit dialog

/**
 * Edit / Add Transaction. Shows the original handwritten line and what
 * OCR read, so mistakes like "Ravl" → "Ravi" can be fixed knowingly.
 */
@Composable
fun TransactionEditDialog(
    title: String,
    initial: TxnForm,
    originalLine: String?,
    ocrRead: String?,
    onSave: (TxnForm) -> Unit,
    onDismiss: () -> Unit,
    extraNotice: String? = null,
    /** Shown above the fields, e.g. the picture of the handwritten line. */
    header: (@Composable () -> Unit)? = null,
) {
    var form by remember { mutableStateOf(initial) }
    var tried by remember { mutableStateOf(false) }
    val required = stringResource(R.string.bill_required)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                header?.invoke()
                originalLine?.let {
                    Text(stringResource(R.string.hw_original_line), style = MaterialTheme.typography.labelMedium, color = ShopAiThemeColors.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                }
                ocrRead?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                ShopTextField(
                    stringResource(R.string.hw_person_name),
                    form.personName,
                    { form = form.copy(personName = it) },
                    error = if (tried && form.personName.isBlank()) required else null,
                )
                ShopTextField(
                    stringResource(R.string.bill_item_amount),
                    form.amount,
                    { form = form.copy(amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == 'k' || c == 'K' }) },
                    placeholder = "0.00",
                    error = if (tried && form.parsedAmount == null) stringResource(R.string.bill_amount_invalid) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                FutureDatePickerField(
                    label = stringResource(R.string.capture_note_date),
                    selectedDate = form.date,
                    onDateSelected = { form = form.copy(date = it) },
                    placeholder = stringResource(R.string.hw_date_not_specified),
                    allowEmpty = true,
                    anyDate = true,
                )
                Text(stringResource(R.string.hw_credit_or_debit), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    FilterChip(
                        selected = form.direction == TxnDirection.CREDIT,
                        onClick = { form = form.copy(direction = TxnDirection.CREDIT) },
                        label = { Text(stringResource(R.string.hw_type_credit_long)) },
                    )
                    FilterChip(
                        selected = form.direction == TxnDirection.DEBIT,
                        onClick = { form = form.copy(direction = TxnDirection.DEBIT) },
                        label = { Text(stringResource(R.string.hw_type_debit_long)) },
                    )
                }
                if (tried && form.direction == TxnDirection.UNKNOWN) {
                    Text(stringResource(R.string.hw_choose_type), color = Danger, style = MaterialTheme.typography.bodySmall)
                }
                // Handwritten bill: what was already paid and when the rest is due.
                if (form.isBill) {
                    ShopTextField(
                        stringResource(R.string.bill_paid),
                        form.paid,
                        { form = form.copy(paid = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == 'k' || c == 'K' }) },
                        placeholder = "0.00",
                        error = if (form.parsedPaid == null) stringResource(R.string.bill_paid_too_much) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    form.balance?.let {
                        Text(stringResource(R.string.hw_bill_balance, formatRupees(it)), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                    }
                    FutureDatePickerField(
                        label = stringResource(R.string.bill_due_date_optional),
                        selectedDate = form.dueDate,
                        onDateSelected = { form = form.copy(dueDate = it) },
                        placeholder = stringResource(R.string.hw_date_not_specified),
                        allowEmpty = true,
                        anyDate = true,
                    )
                }
                ShopTextField(stringResource(R.string.hw_description), form.description, { form = form.copy(description = it) }, singleLine = false)
                ShopTextField(stringResource(R.string.hw_notes), form.notes, { form = form.copy(notes = it) }, singleLine = false)
                extraNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WarningColor) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (form.isComplete) onSave(form)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun DeleteTransactionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit, notice: String? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hw_delete_title)) },
        text = notice?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.capture_delete), color = Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------ source view

/** Loads an image file off the main thread, scaled to about [maxSide] px. */
@Composable
fun rememberFileBitmap(path: String?, maxSide: Int = 1600): Bitmap? {
    var bitmap by remember(path, maxSide) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, maxSide) {
        bitmap = if (path == null) null else withContext(Dispatchers.IO) { decodeFile(path, maxSide) }
    }
    return bitmap
}

fun decodeFile(path: String, maxSide: Int): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** The page image with the handwritten line outlined. */
@Composable
fun SourceImage(bitmap: Bitmap?, box: OcrBox?, modifier: Modifier = Modifier) {
    if (bitmap == null) {
        Text(stringResource(R.string.hw_source_missing), color = ShopAiThemeColors.onSurfaceVariant, modifier = modifier)
        return
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
        val drawnW = bitmap.width * scale
        val drawnH = bitmap.height * scale
        val offsetX = (maxW - drawnW) / 2f
        val offsetY = (maxH - drawnH) / 2f
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (box != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = 6f
                drawRect(
                    color = Color(0x33FFC107),
                    topLeft = Offset(offsetX + box.left * drawnW - pad, offsetY + box.top * drawnH - pad),
                    size = Size((box.right - box.left) * drawnW + pad * 2, (box.bottom - box.top) * drawnH + pad * 2),
                )
                drawRect(
                    color = Color(0xFFE65100),
                    topLeft = Offset(offsetX + box.left * drawnW - pad, offsetY + box.top * drawnH - pad),
                    size = Size((box.right - box.left) * drawnW + pad * 2, (box.bottom - box.top) * drawnH + pad * 2),
                    style = Stroke(width = 4f),
                )
            }
        }
    }
}

/**
 * "View Source": the original handwriting (line highlighted when OCR gave
 * its position) next to what was extracted from it.
 */
@Composable
fun ViewSourceDialog(
    imagePath: String?,
    box: OcrBox?,
    originalLine: String?,
    row: TxnRowUi,
    onDismiss: () -> Unit,
) {
    val bitmap = rememberFileBitmap(imagePath)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hw_view_source)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SourceImage(
                    bitmap = bitmap,
                    box = box,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 380.dp)
                        .size(width = 400.dp, height = 380.dp),
                )
                if (box == null) {
                    Text(stringResource(R.string.hw_source_no_position), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                }
                Text(stringResource(R.string.hw_original_line), style = MaterialTheme.typography.labelMedium, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Text(originalLine ?: "—", style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.hw_extracted), style = MaterialTheme.typography.labelMedium, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Text(
                    listOf(
                        row.personName ?: stringResource(R.string.hw_needs_review_value),
                        row.amount?.let { formatRupees(it) } ?: stringResource(R.string.hw_needs_review_value),
                        dateLabel(row.date),
                        directionLabel(row.direction),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_close)) } },
    )
}

// --------------------------------------------------------------- summary

@Composable
fun SummaryLine(label: String, value: String, color: Color = ShopAiThemeColors.onSurface) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = ShopAiThemeColors.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Runs [block] once when [key] changes; small helper for dialogs that load data. */
@Composable
fun OnChange(key: Any?, block: suspend () -> Unit) {
    LaunchedEffect(key) { block() }
}
