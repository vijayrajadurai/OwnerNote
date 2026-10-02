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

/** A value read from the bill that the owner must check before it can be saved. */
enum class BillField { SHOP, PERSON, TOTAL }

/** Below this Tesseract confidence, names and the total read from the photo are checked by the owner. */
private const val SURE_CONFIDENCE = 70

/** OCR noise rather than a name: too short, or mostly symbols / digits. */
internal fun looksGarbled(name: String): Boolean {
    val chars = name.filter { !it.isWhitespace() }
    if (chars.count(Char::isLetter) < 3) return true
    return chars.count(Char::isLetter).toDouble() / chars.length < 0.7
}

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
    /** When the money is due (optional): decides Upcoming / Overdue. */
    var dueDate by mutableStateOf<LocalDate?>(null)
    /** How Credit/Debit was decided from the bill (null = chosen by hand). */
    var autoDirection by mutableStateOf<com.shopai.app.util.BillDirectionGuess?>(null)
    var warnings by mutableStateOf<Set<BillScanWarning>>(emptySet())
        private set
    /** "Amount in words" on the bill when it differs from [total]. */
    var totalInWords by mutableStateOf<BigDecimal?>(null)
        private set
    /** Read from the bill but not certain: must be checked (edited or confirmed) before saving. */
    var toVerify by mutableStateOf<Set<BillField>>(emptySet())
        private set
    /** Not written as "Total" on the bill: offered, never filled in by itself. */
    var totalSuggestion by mutableStateOf<BigDecimal?>(null)
        private set
    /** A name read by the server, not on the phone: offered, never filled in by itself. */
    var shopSuggestion by mutableStateOf<String?>(null)
        private set

    /** The owner checked [field] (confirmed it, or typed it). */
    fun verified(field: BillField) {
        toVerify = toVerify - field
    }

    fun useTotalSuggestion() {
        totalSuggestion?.let { total = it.toPlainString() }
        totalSuggestion = null
        verified(BillField.TOTAL)
        warnings = warnings - BillScanWarning.TOTAL_GUESSED - BillScanWarning.NO_TOTAL
    }

    fun useShopSuggestion() {
        shopSuggestion?.let { shopName = it }
        shopSuggestion = null
        verified(BillField.SHOP)
        warnings = warnings - BillScanWarning.NO_SHOP
    }

    val parsedTotal: BigDecimal? get() = BillTextParser.parseAmount(total)

    fun useTotalInWords() {
        totalInWords?.let { total = it.toPlainString() }
        totalInWords = null
        warnings = warnings - BillScanWarning.TOTAL_GUESSED
        verified(BillField.TOTAL)
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

    /** Paid / Partially paid / Upcoming / Overdue for what is about to be saved. */
    val status: com.shopai.app.util.PaymentStatus?
        get() {
            val t = parsedTotal ?: return null
            val p = parsedPaid ?: return null
            return com.shopai.app.util.PaymentStatus.of(t, p, dueDate)
        }

    val totalTooLarge: Boolean get() = parsedTotal?.let { exceedsMaxLedgerAmount(it.toDouble()) } == true
    val paidTooMuch: Boolean get() = parsedTotal != null && parsedPaid != null && parsedPaid!! > parsedTotal!!

    val canSave: Boolean
        get() = partyName.isNotBlank() &&
            billDate != null &&
            parsedTotal != null &&
            !totalTooLarge &&
            parsedPaid != null &&
            !paidTooMuch &&
            // Nothing uncertain is saved without the owner's check.
            toVerify.isEmpty()

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
        dueDate = null
        autoDirection = null
        warnings = emptySet()
        totalInWords = null
        toVerify = emptySet()
        totalSuggestion = null
        shopSuggestion = null
        visible = false
    }

    /**
     * Fills the form from a scan — only with what is clearly on the bill:
     * shop name, purchased by, and the labelled Total. Nothing is guessed:
     * an unlabelled total or the server's reading ([serverName]/[serverAmount])
     * is only offered as a suggestion; a value the OCR was unsure of
     * ([ocrConfidence], Tesseract's 0–100, -1 unknown) must be checked by the
     * owner before saving. The due date is always chosen by the owner.
     */
    fun applyScan(
        bill: ExtractedBill,
        serverName: String?,
        serverAmount: Double?,
        direction: com.shopai.app.util.BillDirectionGuess? = null,
        ocrConfidence: Int = -1,
    ) {
        reset()
        // Credit (money to receive) or Debit (money to pay), from who issued the bill.
        direction?.let {
            autoDirection = it
            type = if (it.direction == com.shopai.app.util.TxnDirection.CREDIT) TransactionSaveType.CREDIT else TransactionSaveType.DEBIT
        }
        val unsureOcr = ocrConfidence in 0 until SURE_CONFIDENCE
        val shop = bill.merchantName?.trim()?.takeIf { it.isNotBlank() }
        val person = bill.customerName?.trim()?.takeIf { it.isNotBlank() }
        val labelledTotal = bill.total?.takeIf { bill.totalFromLabel }
        val serverTotal = serverAmount?.let { BillTextParser.parseAmount(it.toString()) }
        totalInWords = bill.amountInWords?.takeIf { labelledTotal == null || it.compareTo(labelledTotal) != 0 }

        shopName = shop.orEmpty()
        customerName = person.orEmpty()
        billDate = bill.date?.takeIf { !it.isAfter(LocalDate.now()) }
        // Only a total written as "Total / Grand total / Net amount" is filled in.
        total = labelledTotal?.toPlainString().orEmpty()
        totalSuggestion = (bill.total ?: serverTotal)?.takeIf { labelledTotal == null }
        shopSuggestion = serverName?.trim()?.takeIf { shop == null && it.isNotBlank() }
        paid = bill.paid?.takeIf { labelledTotal == null || it <= labelledTotal }?.toPlainString().orEmpty()
        toVerify = buildSet {
            if (shop != null && (unsureOcr || looksGarbled(shop))) add(BillField.SHOP)
            if (person != null && (unsureOcr || looksGarbled(person))) add(BillField.PERSON)
            // The written total disagrees with the amount in words, or the photo read poorly.
            if (labelledTotal != null && (unsureOcr || totalInWords != null)) add(BillField.TOTAL)
        }
        warnings = buildSet {
            if (shop == null) add(BillScanWarning.NO_SHOP)
            if (labelledTotal == null) add(if (totalSuggestion != null) BillScanWarning.TOTAL_GUESSED else BillScanWarning.NO_TOTAL)
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
                onClick = { state.type = TransactionSaveType.DEBIT; state.autoDirection = null },
                label = { Text(stringResource(R.string.bill_type_supplier)) },
            )
            FilterChip(
                selected = !isSupplier,
                onClick = { state.type = TransactionSaveType.CREDIT; state.autoDirection = null },
                label = { Text(stringResource(R.string.bill_type_customer)) },
            )
        }

        state.autoDirection?.let { auto ->
            Text(
                stringResource(
                    when (auto.reason) {
                        com.shopai.app.util.BillDirectionReason.MY_BILL_TO_CUSTOMER -> R.string.bill_auto_credit
                        com.shopai.app.util.BillDirectionReason.ADDRESSED_TO_ME -> R.string.bill_auto_debit_to_me
                        com.shopai.app.util.BillDirectionReason.FROM_ANOTHER_SHOP -> R.string.bill_auto_debit_other
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (auto.confident) ShopAiThemeColors.primary else ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        ShopTextField(
            stringResource(R.string.bill_shop_name),
            state.shopName,
            { state.shopName = it; state.verified(BillField.SHOP) },
            placeholder = stringResource(R.string.bill_entry_name_placeholder),
            error = if (isSupplier && state.shopName.isBlank() && state.warnings.isNotEmpty()) required else null,
        )
        VerifyNote(state, BillField.SHOP)
        state.shopSuggestion?.let { name ->
            TextButton(onClick = { state.useShopSuggestion() }) {
                Text(stringResource(R.string.bill_suggest_shop, name), fontWeight = FontWeight.SemiBold)
            }
        }
        ShopTextField(
            stringResource(if (isSupplier) R.string.bill_customer_name_optional else R.string.bill_customer_name),
            state.customerName,
            { state.customerName = it; state.verified(BillField.PERSON) },
            // Not a sample name: an empty field must not look like a name read from the bill.
            placeholder = stringResource(R.string.bill_customer_placeholder),
            error = if (!isSupplier && state.customerName.isBlank()) required else null,
        )
        VerifyNote(state, BillField.PERSON)
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
            { state.total = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }; state.verified(BillField.TOTAL) },
            placeholder = "0.00",
            error = when {
                state.totalTooLarge -> stringResource(R.string.amount_max_one_crore)
                state.total.isNotBlank() && state.parsedTotal == null -> stringResource(R.string.bill_amount_invalid)
                (BillScanWarning.NO_TOTAL in state.warnings || BillScanWarning.TOTAL_GUESSED in state.warnings) && state.total.isBlank() -> required
                else -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        VerifyNote(state, BillField.TOTAL)
        // Not written as "Total": offered only — the owner checks the bill and taps to use it.
        state.totalSuggestion?.let { amount ->
            TextButton(onClick = { state.useTotalSuggestion() }) {
                Text(stringResource(R.string.bill_suggest_total, BillNotesFormatter.rupees(amount)), fontWeight = FontWeight.SemiBold)
            }
        }
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

        FutureDatePickerField(
            label = stringResource(R.string.bill_due_date_optional),
            selectedDate = state.dueDate,
            onDateSelected = { state.dueDate = it },
            placeholder = stringResource(R.string.due_date_placeholder),
            error = null,
            allowEmpty = true,
        )
        state.status?.let { com.shopai.app.ui.components.PaymentStatusChip(it) }

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

    if (state.toVerify.isNotEmpty()) {
        Text(
            stringResource(R.string.bill_verify_before_save),
            style = MaterialTheme.typography.bodyMedium,
            color = Danger,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    PrimaryButton(
        label = stringResource(R.string.bill_save),
        loading = saving,
        enabled = state.canSave,
        modifier = Modifier.padding(top = 12.dp),
        onClick = onSave,
    )
}

/** Under a field read from the bill but not certain: check it, then confirm (or just edit it). */
@Composable
private fun VerifyNote(state: BillEntryState, field: BillField) {
    if (field !in state.toVerify) return
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            stringResource(R.string.bill_verify_field),
            style = MaterialTheme.typography.bodySmall,
            color = Danger,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { state.verified(field) }) {
            Text(stringResource(R.string.bill_verify_ok), fontWeight = FontWeight.SemiBold)
        }
    }
}
