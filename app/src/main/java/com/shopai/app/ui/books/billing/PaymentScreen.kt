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
import com.shopai.app.books.billing.AllocationDraft
import com.shopai.app.books.billing.BillDrafts
import com.shopai.app.books.billing.DraftKind
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.billing.MoneyAccounts
import com.shopai.app.books.billing.PaymentDraft
import com.shopai.app.books.billing.toInput
import com.shopai.app.books.data.OpenDoc
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.engine.PaymentQuote
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.TxnSource
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.books.BooksErrors
import com.shopai.app.ui.books.BooksNotReady
import com.shopai.app.ui.books.BooksState
import com.shopai.app.ui.books.ChoiceChips
import com.shopai.app.ui.books.DecimalField
import com.shopai.app.ui.books.SectionCard
import com.shopai.app.ui.books.SwitchRow
import com.shopai.app.ui.books.paiseText
import com.shopai.app.ui.books.parseRupees
import com.shopai.app.ui.books.rememberBooks
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Payment from a customer (in) or to a supplier (out): write → review where it goes → confirm. */
@Composable
fun PaymentScreen(container: AppContainer, incoming: Boolean, partyId: String?, docId: String?, onBack: () -> Unit, onPosted: (String) -> Unit) {
    val books = rememberBooks(container)
    DetailScaffold(title = stringResource(if (incoming) R.string.bill_receive_payment else R.string.bill_make_payment), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> PaymentEditor(books.session, incoming, partyId, docId, modifier) { id ->
                container.kaiBrain.forget()
                onPosted(id)
            }
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun PaymentEditor(s: BooksSession, incoming: Boolean, initialPartyId: String?, docId: String?, modifier: Modifier, onPosted: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val drafts = remember { BillDrafts(s) }
    val kind = if (incoming) DraftKind.PAYMENT_IN else DraftKind.PAYMENT_OUT
    var ready by remember { mutableStateOf(false) }
    var party by remember { mutableStateOf<PartyEntity?>(null) }
    var open by remember { mutableStateOf(emptyList<OpenDoc>()) }
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(PaymentMode.CASH) }
    var reference by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var choose by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(mapOf<String, String>()) }
    var advance by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<PaymentQuote?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<BooksError>()) }
    var draftId by remember { mutableStateOf<String?>(null) }
    var accountName by remember { mutableStateOf("") }

    suspend fun loadOpen(p: PartyEntity?) {
        open = p?.let {
            if (incoming) s.ledger.openInvoices(it.id) else s.ledger.openBills(it.id)
        }.orEmpty()
    }

    LaunchedEffect(Unit) {
        MoneyAccounts.ensureDefaults(s)
        party = initialPartyId?.let { s.dao.party(it) }
        loadOpen(party)
        docId?.let { id ->
            open.firstOrNull { it.id == id }?.let { d ->
                choose = true
                chosen = mapOf(d.id to paiseText(d.outstandingPaise))
                amount = paiseText(d.outstandingPaise)
            }
        }
        ready = true
    }

    fun draft(): PaymentDraft? {
        val amt = parseRupees(amount).getOrNull() ?: return null
        val allocations = if (choose) chosen.mapNotNull { (id, text) -> parseRupees(text).getOrNull()?.takeIf { it > 0 }?.let { AllocationDraft(id, it) } } else null
        return PaymentDraft(kind, party?.id, amt, mode, reference.trim().ifEmpty { null }, date.toString(), allocations, advance)
    }

    LaunchedEffect(party?.id, amount, mode, reference, date, choose, chosen, advance, ready) {
        if (!ready) return@LaunchedEffect
        delay(250)
        accountName = MoneyAccounts.accountFor(s, mode)?.name.orEmpty()
        val d = draft()
        quote = if (d == null || party == null) null else s.engine.quotePayment(d.toInput(s, "preview", TxnSource.MANUAL), incoming)
    }

    if (!ready) {
        BooksNotReady(BooksState.Loading, modifier)
        return
    }
    if (picking) PartyPicker(s, if (incoming) PartyKind.CUSTOMER else PartyKind.SUPPLIER, allowWalkIn = false, onDismiss = { picking = false }) { p ->
        party = p
        chosen = emptyMap()
        picking = false
        scope.launch { loadOpen(p) }
    }

    val due = open.sumOf { it.outstandingPaise }
    val q = quote
    val allErrors = errors + q?.errors.orEmpty()
    val canSave = party != null && q != null && q.errors.isEmpty()
    val day = DateTimeFormatter.ofPattern("dd MMM")

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BooksErrors(allErrors)
        if (reviewing && q != null) {
            ShopCard {
                Text(party?.name.orEmpty(), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                Amount(stringResource(if (incoming) R.string.bill_amount_received else R.string.bill_amount_paid), parseRupees(amount).getOrNull() ?: 0, strong = true)
                Text("${modeLabel(mode)} → $accountName${reference.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
                q.allocations.forEach { a -> Amount(stringResource(R.string.bill_settles, a.doc.number), a.amountPaise) }
                if (q.excessPaise > 0) Amount(stringResource(R.string.bill_kept_as_advance), q.excessPaise)
            }
            PrimaryButton(stringResource(R.string.bill_confirm_payment), {
                scope.launch {
                    posting = true
                    val d = draft()!!
                    val id = drafts.save(draftId, kind, d).also { draftId = it }
                    val input = d.toInput(s, id, drafts.sourceOf(id))
                    val r = if (incoming) s.engine.postPaymentIn(input) else s.engine.postPaymentOut(input)
                    posting = false
                    when (r) {
                        is PostResult.Posted -> onPosted(r.txn.id)
                        is PostResult.Rejected -> errors = r.errors
                    }
                }
            }, loading = posting, enabled = canSave)
            OutlinedButton(onClick = { reviewing = false; errors = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bill_edit_again)) }
            return@Column
        }

        SectionCard(stringResource(if (incoming) R.string.bill_customer else R.string.bill_supplier)) {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Text(party?.name ?: stringResource(if (incoming) R.string.bill_pick_customer else R.string.bill_pick_supplier))
            }
            if (party != null) Text(stringResource(R.string.bill_total_due, InvoicePdf.money(due)), color = ShopAiThemeColors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(stringResource(R.string.bill_payment)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(stringResource(R.string.bill_amount), amount, { amount = it }, modifier = Modifier.weight(1f))
                if (due > 0) TextButton(onClick = { amount = paiseText(due) }) { Text(stringResource(R.string.bill_full)) }
            }
            ChoiceChips(PaymentMode.entries.filter { it != PaymentMode.CREDIT }, mode, { modeLabel(it) }) { mode = it }
            if (accountName.isNotEmpty()) Text(stringResource(R.string.bill_goes_to, accountName), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
            ShopTextField(stringResource(R.string.bill_reference), reference, { reference = it }, placeholder = stringResource(R.string.bill_reference_hint))
            DateField(stringResource(R.string.bill_date), date, { it?.let { d -> date = d } })
        }
        if (open.isNotEmpty()) {
            SectionCard(stringResource(R.string.bill_apply_to)) {
                ChoiceChips(listOf(false, true), choose, { stringResource(if (it) R.string.bill_choose_invoices else R.string.bill_oldest_first) }) { choose = it }
                if (choose) {
                    open.forEach { doc ->
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(doc.number, fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                                Text(
                                    stringResource(R.string.bill_doc_due, LocalDate.ofEpochDay((doc.dueDate ?: doc.date).toLong()).format(day), InvoicePdf.money(doc.outstandingPaise)),
                                    style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant,
                                )
                            }
                            DecimalField("", chosen[doc.id].orEmpty(), { chosen = chosen + (doc.id to it) }, modifier = Modifier.weight(0.8f))
                        }
                    }
                } else {
                    q?.allocations?.forEach { a -> Amount(stringResource(R.string.bill_settles, a.doc.number), a.amountPaise) }
                }
            }
        }
        if ((q?.excessPaise ?: 0) > 0 || q?.errors?.any { it.code == com.shopai.app.books.engine.BooksErrorCode.OVERPAYMENT } == true || open.isEmpty()) {
            SwitchRow(stringResource(R.string.bill_keep_advance), advance, { advance = it }, stringResource(R.string.bill_keep_advance_caption))
        }
        PrimaryButton(stringResource(R.string.bill_review), {
            scope.launch {
                draft()?.let { draftId = drafts.save(draftId, kind, it) }
                reviewing = true
            }
        }, enabled = canSave)
        Spacer(Modifier.height(32.dp))
    }
}
