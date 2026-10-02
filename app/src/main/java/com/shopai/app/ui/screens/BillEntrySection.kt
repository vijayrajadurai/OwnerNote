package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.shopai.app.ui.components.TransactionSaveType
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.BillNotesFormatter
import com.shopai.app.util.BillTextParser
import com.shopai.app.util.ExtractedBill
import com.shopai.app.util.exceedsMaxLedgerAmount
import java.math.BigDecimal
import java.time.LocalDate

/** Why a field may need the owner's attention after a scan. */
enum class BillScanWarning { NO_SHOP, NO_TOTAL, TOTAL_GUESSED, NO_DATE, PAID_UNKNOWN }

/**
 * A shop bill as it becomes a ledger entry: who it's with, the bill date,
 * the total, and how much of it was already paid. Everything is editable.
 *
 * Supplier bill (I pay)      → debit entry under the shop's name.
 * Customer bill (I collect)  → credit entry under the customer's name.
 * The paid part is recorded as a payment, so only the rest stays pending.
 */
@Stable
class BillEntryState {
    var visible by mutableStateOf(false)
        private set
    var shopName by mutableStateOf("")
    var customerName by mutableStateOf("")
    var billDate by mutableStateOf<LocalDate?>(null)
    var total by mutableStateOf("")
    var paid by mutableStateOf("")
    var type by mutableStateOf(TransactionSaveType.DEBIT)
    var warnings by mutableStateOf<Set<BillScanWarning>>(emptySet())
        private set
    /** "Amount in words" on the bill when it differs from [total]. */
    var totalInWords by mutableStateOf<BigDecimal?>(null)
        private set

    val parsedTotal: BigDecimal? get() = BillTextParser.parseAmount(total)

    fun useTotalInWords() {
        totalInWords?.let { total = it.toPlainString() }
        totalInWords = null
        warnings = warnings - BillScanWarning.TOTAL_GUESSED
    }

    /** Blank means nothing paid. Null when the text isn't a valid amount. */
    val parsedPaid: BigDecimal?
        get() = when {
            paid.isBlank() -> BigDecimal.ZERO.setScale(2)
            paid.replace(",", "").toBigDecimalOrNull()?.signum() == 0 -> BigDecimal.ZERO.setScale(2)
            else -> BillTextParser.parseAmount(paid)
        }

    val pending: BigDecimal?
        get() {
            val t = parsedTotal ?: return null
            val p = parsedPaid ?: return null
            return (t - p).takeIf { it.signum() >= 0 }
        }

    /** Name the ledger entry is saved under. */
    val partyName: String
        get() = (if (type == TransactionSaveType.DEBIT) shopName else customerName).trim()

    val totalTooLarge: Boolean get() = parsedTotal?.let { exceedsMaxLedgerAmount(it.toDouble()) } == true
    val paidTooMuch: Boolean get() = parsedTotal != null && parsedPaid != null && parsedPaid!! > parsedTotal!!

    val canSave: Boolean
        get() = partyName.isNotBlank() &&
            billDate != null &&
            parsedTotal != null &&
            !totalTooLarge &&
            parsedPaid != null &&
            !paidTooMuch

    fun startManual() {
        reset()
        billDate = LocalDate.now()
        visible = true
    }

    fun reset() {
        shopName = ""
        customerName = ""
        billDate = null
        total = ""
        paid = ""
        type = TransactionSaveType.DEBIT
        warnings = emptySet()
        totalInWords = null
        visible = false
    }

    /**
     * Fills the form from a scan. [serverName]/[serverAmount] come from the
     * server's own reading of the same text and only fill what the phone
     * could not find.
     */
    fun applyScan(bill: ExtractedBill, serverName: String?, serverAmount: Double?) {
        reset()
        val shop = bill.merchantName ?: serverName?.takeIf { it.isNotBlank() }
        val labelledTotal = bill.total?.takeIf { bill.totalFromLabel }
        val serverTotal = serverAmount?.let { BillTextParser.parseAmount(it.toString()) }
        // The phone's own reading of the bill comes first; the server's guess
        // is only a last resort (it often picks an item or sub-total amount).
        val chosenTotal = labelledTotal ?: bill.total ?: serverTotal
        totalInWords = bill.amountInWords?.takeIf { chosenTotal == null || it.compareTo(chosenTotal) != 0 }

        shopName = shop.orEmpty()
        customerName = bill.customerName.orEmpty()
        billDate = bill.date?.takeIf { !it.isAfter(LocalDate.now()) }
        total = chosenTotal?.toPlainString().orEmpty()
        paid = bill.paid?.takeIf { chosenTotal == null || it <= chosenTotal }?.toPlainString().orEmpty()
        warnings = buildSet {
            if (shop == null) add(BillScanWarning.NO_SHOP)
            if (chosenTotal == null) add(BillScanWarning.NO_TOTAL)
            else if (labelledTotal == null) add(BillScanWarning.TOTAL_GUESSED)
            if (billDate == null) add(BillScanWarning.NO_DATE)
            if (bill.paid == null) add(BillScanWarning.PAID_UNKNOWN)
        }
        visible = true
    }
}

@Composable
fun BillEntrySection(
    state: BillEntryState,
    saving: Boolean,
    onSave: () -> Unit,
) {
    val required = stringResource(R.string.bill_required)
    val isSupplier = state.type == TransactionSaveType.DEBIT

    ShopCard(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            stringResource(R.string.bill_details_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = ShopAiThemeColors.primary,
        )
        state.warnings.forEach { warning ->
            Text(
                text = stringResource(
                    when (warning) {
                        BillScanWarning.NO_SHOP -> R.string.bill_warn_no_name
                        BillScanWarning.NO_TOTAL -> R.string.bill_warn_no_total
                        BillScanWarning.TOTAL_GUESSED -> R.string.bill_warn_total_guessed
                        BillScanWarning.NO_DATE -> R.string.bill_warn_no_date
                        BillScanWarning.PAID_UNKNOWN -> R.string.bill_warn_paid_unknown
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (warning == BillScanWarning.PAID_UNKNOWN) ShopAiThemeColors.onSurfaceVariant else Danger,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Who the bill is with decides credit vs debit.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilterChip(
                selected = isSupplier,
                onClick = { state.type = TransactionSaveType.DEBIT },
                label = { Text(stringResource(R.string.bill_type_supplier)) },
            )
            FilterChip(
                selected = !isSupplier,
                onClick = { state.type = TransactionSaveType.CREDIT },
                label = { Text(stringResource(R.string.bill_type_customer)) },
            )
        }

        ShopTextField(
            stringResource(R.string.bill_shop_name),
            state.shopName,
            { state.shopName = it },
            placeholder = stringResource(R.string.bill_entry_name_placeholder),
            error = if (isSupplier && state.shopName.isBlank() && state.warnings.isNotEmpty()) required else null,
        )
        ShopTextField(
            stringResource(if (isSupplier) R.string.bill_customer_name_optional else R.string.bill_customer_name),
            state.customerName,
            { state.customerName = it },
            // Not a sample name: an empty field must not look like a name read from the bill.
            placeholder = stringResource(R.string.bill_customer_placeholder),
            error = if (!isSupplier && state.customerName.isBlank()) required else null,
        )
        FutureDatePickerField(
            label = stringResource(R.string.bill_date),
            selectedDate = state.billDate,
            onDateSelected = { state.billDate = it },
            placeholder = stringResource(R.string.bill_date_placeholder),
            error = if (state.billDate == null) required else null,
            allowEmpty = false,
            pastOnly = true,
        )
        ShopTextField(
            stringResource(R.string.bill_total),
            state.total,
            { state.total = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
            placeholder = "0.00",
            error = when {
                state.totalTooLarge -> stringResource(R.string.amount_max_one_crore)
                state.total.isNotBlank() && state.parsedTotal == null -> stringResource(R.string.bill_amount_invalid)
                BillScanWarning.NO_TOTAL in state.warnings && state.total.isBlank() -> required
                else -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        state.totalInWords?.let { words ->
            val wordsText = BillNotesFormatter.rupees(words)
            Text(
                stringResource(R.string.bill_words_differ, wordsText),
                style = MaterialTheme.typography.bodyMedium,
                color = Danger,
            )
            TextButton(onClick = { state.useTotalInWords() }) {
                Text(stringResource(R.string.bill_use_words_total, wordsText), fontWeight = FontWeight.SemiBold)
            }
        }
        ShopTextField(
            stringResource(R.string.bill_paid),
            state.paid,
            { state.paid = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
            placeholder = "0.00",
            error = when {
                state.parsedPaid == null -> stringResource(R.string.bill_amount_invalid)
                state.paidTooMuch -> stringResource(R.string.bill_paid_too_much)
                else -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        // What the entry will look like in the ledger.
        state.pending?.let { pending ->
            val total = state.parsedTotal ?: return@let
            val party = state.partyName.ifBlank { "—" }
            Text(
                stringResource(
                    if (isSupplier) R.string.bill_entry_preview_debit else R.string.bill_entry_preview_credit,
                    party,
                    BillNotesFormatter.rupees(total),
                    BillNotesFormatter.rupees(total - pending),
                    BillNotesFormatter.rupees(pending),
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = ShopAiThemeColors.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    PrimaryButton(
        label = stringResource(R.string.bill_save),
        loading = saving,
        enabled = state.canSave,
        modifier = Modifier.padding(top = 12.dp),
        onClick = onSave,
    )
}
