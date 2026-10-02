package com.shopai.app.ui.books.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.billing.BillDraft
import com.shopai.app.books.billing.BillDrafts
import com.shopai.app.books.billing.BillLine
import com.shopai.app.books.billing.BillPayment
import com.shopai.app.books.billing.DraftKind
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.billing.MoneyAccounts
import com.shopai.app.books.billing.toPurchaseInput
import com.shopai.app.books.billing.toSaleInput
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.data.ProductEntity
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.Quote
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.model.TxnSource
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.books.BooksErrors
import com.shopai.app.ui.books.BooksNotReady
import com.shopai.app.ui.books.BooksState
import com.shopai.app.ui.books.ChoiceChips
import com.shopai.app.ui.books.DecimalField
import com.shopai.app.ui.books.SectionCard
import com.shopai.app.ui.books.bpText
import com.shopai.app.ui.books.paiseText
import com.shopai.app.ui.books.parsePercentBp
import com.shopai.app.ui.books.parseQty
import com.shopai.app.ui.books.parseRupees
import com.shopai.app.ui.books.qtyText
import com.shopai.app.ui.books.rememberBooks
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Sale invoice or purchase bill: write (saved as a draft) → review the engine's quote → confirm (posted by the engine). */
@Composable
fun BillEditorScreen(
    container: AppContainer,
    kind: DraftKind,
    partyId: String?,
    draftId: String?,
    onBack: () -> Unit,
    onPosted: (String) -> Unit,
) {
    val books = rememberBooks(container)
    DetailScaffold(title = stringResource(if (kind == DraftKind.SALE) R.string.bill_new_sale else R.string.bill_new_purchase), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> BillEditor(books.session, kind, partyId, draftId, modifier, onBack) { id ->
                container.kaiBrain.forget()
                onPosted(id)
            }
            else -> BooksNotReady(books, modifier)
        }
    }
}

private class EditLine(
    val product: ProductEntity?,
    val name: String,
    val qty: String,
    val unit: String,
    val rate: String,
    val discount: String,
    val gstBp: Int?,
    val taxType: TaxType,
) {
    fun copy(qty: String = this.qty, unit: String = this.unit, rate: String = this.rate, discount: String = this.discount, gstBp: Int? = this.gstBp) =
        EditLine(product, name, qty, unit, rate, discount, gstBp, taxType)
}

private class EditPay(val mode: PaymentMode, val amount: String)

@Composable
private fun BillEditor(
    s: BooksSession,
    kind: DraftKind,
    initialPartyId: String?,
    initialDraftId: String?,
    modifier: Modifier,
    onBack: () -> Unit,
    onPosted: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drafts = remember { BillDrafts(s) }
    val sale = kind == DraftKind.SALE
    var ready by remember { mutableStateOf(false) }
    var slabs by remember { mutableStateOf(emptyList<Int>()) }
    var businessName by remember { mutableStateOf("") }
    var businessGstin by remember { mutableStateOf<String?>(null) }

    var draftId by remember { mutableStateOf(initialDraftId) }
    var party by remember { mutableStateOf<PartyEntity?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var number by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(emptyList<EditLine>()) }
    var pays by remember { mutableStateOf(listOf(EditPay(PaymentMode.CASH, ""))) }
    var notes by remember { mutableStateOf("") }

    var picking by remember { mutableStateOf<String?>(null) } // "party" | "item"
    var quote by remember { mutableStateOf<Quote?>(null) }
    var parseErrors by remember { mutableStateOf(emptyList<BooksError>()) }
    var reviewing by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var postErrors by remember { mutableStateOf(emptyList<BooksError>()) }

    LaunchedEffect(Unit) {
        MoneyAccounts.ensureDefaults(s)
        val business = s.dao.business(s.ctx.businessId)
        slabs = business?.gstRatesBp?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        businessName = business?.name.orEmpty()
        businessGstin = business?.gstin
        val draft = initialDraftId?.let { drafts.bill(it) }
        if (draft != null) {
            party = draft.partyId?.let { s.dao.party(it) }
            date = LocalDate.parse(draft.date)
            dueDate = draft.dueDate?.let(LocalDate::parse)
            number = draft.number.orEmpty()
            notes = draft.notes.orEmpty()
            lines = draft.lines.map { l ->
                EditLine(l.productId?.let { s.dao.product(it) }, l.name, qtyText(l.qtyMilli), l.unit, paiseText(l.ratePaise),
                    if (l.discountBp == 0) "" else bpText(l.discountBp), l.gstBp, l.taxType?.let(TaxType::valueOf) ?: TaxType.GST)
            }
            pays = draft.payments.map { EditPay(it.mode, paiseText(it.amountPaise)) }.ifEmpty { listOf(EditPay(PaymentMode.CASH, "")) }
        } else {
            party = initialPartyId?.let { s.dao.party(it) }
            dueDate = party?.creditDays?.let { date.plusDays(it.toLong()) }
        }
        ready = true
    }

    /** The screen's state as a draft, or the fields that do not parse. */
    fun toDraft(): Pair<BillDraft?, List<BooksError>> {
        val errs = mutableListOf<BooksError>()
        val billLines = lines.mapIndexed { i, l ->
            val qty = parseQty(l.qty).getOrNull()
            val rate = parseRupees(l.rate).getOrNull()
            val disc = parsePercentBp(l.discount).getOrNull()
            if (qty == null) errs += BooksError(BooksErrorCode.INVALID_QUANTITY, "Enter the quantity of ${l.name}", "items[$i]")
            if (parseRupees(l.rate).isFailure || (l.product == null && rate == null)) errs += BooksError(BooksErrorCode.INVALID_PRICE, "Enter the rate of ${l.name}", "items[$i]")
            if (parsePercentBp(l.discount).isFailure) errs += BooksError(BooksErrorCode.INVALID_PRICE, "Check the discount of ${l.name}", "items[$i]")
            BillLine(l.product?.id, l.name, qty ?: 0, l.unit, rate, disc ?: 0, l.gstBp, l.taxType.name)
        }
        val billPays = pays.mapNotNull { p ->
            val amount = parseRupees(p.amount).getOrElse { errs += BooksError(BooksErrorCode.INVALID_AMOUNT, "Check the amount paid", "payments"); null }
            amount?.takeIf { it > 0 }?.let { BillPayment(p.mode, it) }
        }
        if (errs.isNotEmpty()) return null to errs
        return BillDraft(kind, party?.id, date.toString(), dueDate?.toString(), number.trim().ifEmpty { null }, billLines, billPays, notes) to emptyList()
    }

    suspend fun quoteOf(d: BillDraft): Quote =
        if (sale) s.engine.quoteSale(d.toSaleInput(s, "preview", TxnSource.MANUAL)) else s.engine.quotePurchase(d.toPurchaseInput(s, "preview", TxnSource.MANUAL))

    // Live totals from the engine (debounced) as the bill is written.
    val draftKey = listOf(party?.id, date, dueDate, number, lines.map { listOf(it.product?.id, it.name, it.qty, it.unit, it.rate, it.discount, it.gstBp) }, pays.map { it.mode to it.amount })
    LaunchedEffect(draftKey, ready) {
        if (!ready) return@LaunchedEffect
        delay(250)
        val (d, errs) = toDraft()
        parseErrors = errs
        quote = if (d == null || d.lines.isEmpty()) null else quoteOf(d)
    }

    if (!ready) {
        BooksNotReady(BooksState.Loading, modifier)
        return
    }

    when (picking) {
        "party" -> PartyPicker(s, if (sale) PartyKind.CUSTOMER else PartyKind.SUPPLIER, allowWalkIn = sale, onDismiss = { picking = null }) { p ->
            party = p
            dueDate = p?.creditDays?.let { date.plusDays(it.toLong()) } ?: dueDate
            picking = null
        }
        "item" -> ProductPicker(
            s, onDismiss = { picking = null },
            onPick = { p ->
                val price = if (sale) p.sellingPricePaise else p.purchasePricePaise
                lines = lines + EditLine(p, p.name, "1", p.primaryUnit, paiseText(price), "", p.gstBp, TaxType.valueOf(p.taxType))
                picking = null
            },
            onOneOff = { name ->
                lines = lines + EditLine(null, name, "1", "NOS", "", "", 0, TaxType.GST)
                picking = null
            },
        )
    }

    val totalPaise = quote?.totals?.grandTotalPaise
    val allErrors = parseErrors + (quote?.errors.orEmpty())

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (reviewing) {
            // ---------------- Review: exactly what the engine will post ----------------
            val q = quote
            val preview = q?.toPreview(
                title = if (sale) (if (businessGstin != null) "TAX INVOICE" else "INVOICE") else "PURCHASE BILL",
                date = date, dueDate = dueDate, partyName = party?.name ?: stringResource(R.string.bill_cash_sale),
                partyGstin = party?.gstin, paidPaise = pays.sumOf { parseRupees(it.amount).getOrNull() ?: 0 },
            )
            Text(stringResource(R.string.bill_review_caption), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
            BooksErrors(postErrors + allErrors)
            preview?.let { InvoicePreview(it, businessName, businessGstin) }
            PrimaryButton(
                label = stringResource(if (sale) R.string.bill_confirm_sale else R.string.bill_confirm_purchase),
                loading = posting,
                enabled = q?.ok == true && allErrors.isEmpty(),
                onClick = {
                    scope.launch {
                        posting = true
                        val (d, _) = toDraft()
                        val id = drafts.save(draftId, kind, d!!).also { draftId = it }
                        val source = drafts.sourceOf(id)
                        val result = if (sale) s.engine.postSale(d.toSaleInput(s, id, source)) else s.engine.postPurchase(d.toPurchaseInput(s, id, source))
                        posting = false
                        when (result) {
                            is PostResult.Posted -> onPosted(result.txn.id)
                            is PostResult.Rejected -> postErrors = result.errors
                        }
                    }
                },
            )
            OutlinedButton(onClick = { reviewing = false; postErrors = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bill_edit_again)) }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        // ---------------- Write ----------------
        SectionCard(stringResource(if (sale) R.string.bill_customer else R.string.bill_supplier)) {
            OutlinedButton(onClick = { picking = "party" }, modifier = Modifier.fillMaxWidth()) {
                Text(party?.name ?: stringResource(if (sale) R.string.bill_cash_sale_choose else R.string.bill_choose_supplier))
            }
            party?.let { p ->
                listOfNotNull(p.gstin?.let { "GSTIN $it" }, p.mobile).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                }
                val balance = remember(p.id) { mutableStateOf<Long?>(null) }
                LaunchedEffect(p.id) { balance.value = if (sale) s.ledger.receivable(p.id) else s.ledger.payable(p.id) }
                balance.value?.takeIf { it != 0L }?.let {
                    Text(stringResource(if (it > 0) R.string.bill_party_balance_due else R.string.bill_party_advance, InvoicePdf.money(kotlin.math.abs(it))),
                        style = MaterialTheme.typography.bodySmall, color = if (it > 0) Danger else ShopAiThemeColors.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DateField(stringResource(R.string.bill_date), date, { it?.let { d -> date = d } })
                DateField(stringResource(R.string.bill_due_date), dueDate, { dueDate = it }, clearable = true)
            }
            ShopTextField(
                stringResource(if (sale) R.string.bill_invoice_no else R.string.bill_supplier_bill_no), number, { number = it.trim() },
                placeholder = if (sale) quote?.number.orEmpty() else "",
                error = (quote?.errors.orEmpty()).firstOrNull { it.field == "number" || it.field == "billNumber" }?.message,
            )
        }

        SectionCard(stringResource(R.string.bill_items)) {
            lines.forEachIndexed { i, l ->
                LineEditor(
                    line = l, slabs = slabs,
                    result = quote?.lines?.getOrNull(i)?.result?.totalPaise,
                    errors = allErrors.filter { it.field == "items[$i]" },
                    onChange = { updated -> lines = lines.toMutableList().also { it[i] = updated } },
                    onRemove = { lines = lines.toMutableList().also { it.removeAt(i) } },
                )
            }
            OutlinedButton(onClick = { picking = "item" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bill_add_item)) }
        }

        SectionCard(stringResource(if (sale) R.string.bill_received_now else R.string.bill_paid_now)) {
            pays.forEachIndexed { i, p ->
                ChoiceChips(PaymentMode.entries.filter { it != PaymentMode.CREDIT }, p.mode, { modeLabel(it) }) { m ->
                    pays = pays.toMutableList().also { it[i] = EditPay(m, p.amount) }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(stringResource(R.string.bill_amount), p.amount, { a -> pays = pays.toMutableList().also { it[i] = EditPay(p.mode, a) } }, modifier = Modifier.weight(1f))
                    totalPaise?.let { total ->
                        TextButton(onClick = {
                            val others = pays.filterIndexed { j, _ -> j != i }.sumOf { parseRupees(it.amount).getOrNull() ?: 0 }
                            pays = pays.toMutableList().also { it[i] = EditPay(p.mode, paiseText((total - others).coerceAtLeast(0))) }
                        }) { Text(stringResource(R.string.bill_full)) }
                    }
                }
            }
            TextButton(onClick = { pays = pays + EditPay(PaymentMode.UPI, "") }) { Text(stringResource(R.string.bill_split_payment)) }
            Text(stringResource(R.string.bill_rest_on_credit), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
        }

        ShopTextField(stringResource(R.string.inv_notes), notes, { notes = it }, singleLine = false)

        // Live totals — the engine's quote, not a calculation of this screen.
        ShopCard {
            quote?.totals?.let { t ->
                if (t.cgstPaise + t.sgstPaise + t.igstPaise + t.cessPaise > 0) Amount(stringResource(R.string.bill_tax), t.cgstPaise + t.sgstPaise + t.igstPaise + t.cessPaise)
                if (t.roundOffPaise != 0L) Amount(stringResource(R.string.bill_round_off), t.roundOffPaise)
                Amount(stringResource(R.string.bill_grand_total), t.grandTotalPaise, strong = true)
            } ?: Text(stringResource(R.string.bill_add_items_hint), color = ShopAiThemeColors.onSurfaceVariant)
            BooksErrors(allErrors.filter { e -> e.field == null || !e.field.startsWith("items[") })
        }
        PrimaryButton(
            label = stringResource(R.string.bill_review),
            enabled = quote?.ok == true && allErrors.isEmpty(),
            onClick = {
                scope.launch {
                    val (d, _) = toDraft()
                    if (d != null) {
                        draftId = drafts.save(draftId, kind, d)
                        reviewing = true
                    }
                }
            },
        )
        if (draftId != null) {
            TextButton(onClick = { scope.launch { drafts.discard(draftId!!); onBack() } }) { Text(stringResource(R.string.bill_discard_draft), color = Danger) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun LineEditor(line: EditLine, slabs: List<Int>, result: Long?, errors: List<BooksError>, onChange: (EditLine) -> Unit, onRemove: () -> Unit) {
    ShopCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.name, fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface, modifier = Modifier.weight(1f))
            result?.let { Text(InvoicePdf.money(it), fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.primary) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.bill_remove), color = Danger) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecimalField(stringResource(R.string.bill_qty), line.qty, { onChange(line.copy(qty = it)) }, modifier = Modifier.weight(1f))
            DecimalField(stringResource(R.string.bill_rate), line.rate, { onChange(line.copy(rate = it)) }, modifier = Modifier.weight(1f))
            DecimalField(stringResource(R.string.bill_disc_pct), line.discount, { onChange(line.copy(discount = it)) }, modifier = Modifier.weight(0.8f))
        }
        val units = listOfNotNull(line.product?.primaryUnit, line.product?.secondaryUnit)
        if (units.size > 1) ChoiceChips(units, line.unit, { it }) { u ->
            // Switching unit re-prices from the list price (engine converts stock).
            onChange(line.copy(unit = u, rate = ""))
        }
        if (line.taxType == TaxType.GST) ChoiceChips(slabs, line.gstBp ?: 0, { "GST ${bpText(it)}%" }) { onChange(line.copy(gstBp = it)) }
        line.product?.hsnCode?.let { Text("HSN $it", style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant) }
        errors.forEach { Text(it.message, color = Danger, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun modeLabel(m: PaymentMode): String = stringResource(
    when (m) {
        PaymentMode.CASH -> R.string.mode_cash
        PaymentMode.UPI -> R.string.mode_upi
        PaymentMode.BANK_TRANSFER -> R.string.mode_bank
        PaymentMode.CARD -> R.string.mode_card
        PaymentMode.CHEQUE -> R.string.mode_cheque
        PaymentMode.CREDIT -> R.string.mode_credit
        PaymentMode.OTHER -> R.string.mode_other
    },
)

/** ₹ text for a paise amount; used by several billing screens. */
fun rupeesText(paise: Long): String = Money.toRupees(paise).toPlainString()
