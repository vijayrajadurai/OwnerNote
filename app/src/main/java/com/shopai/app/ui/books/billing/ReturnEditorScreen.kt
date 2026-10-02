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
import com.shopai.app.books.billing.BillDrafts
import com.shopai.app.books.billing.DraftKind
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.billing.ReturnDraft
import com.shopai.app.books.billing.toInput
import com.shopai.app.books.data.TxnEntity
import com.shopai.app.books.data.TxnItemEntity
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.Quote
import com.shopai.app.books.engine.ReturnLine
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.TxnSource
import com.shopai.app.books.model.TxnType
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.books.BooksErrors
import com.shopai.app.ui.books.BooksNotReady
import com.shopai.app.ui.books.BooksState
import com.shopai.app.ui.books.DecimalField
import com.shopai.app.ui.books.SectionCard
import com.shopai.app.ui.books.parseQty
import com.shopai.app.ui.books.qtyText
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

/** Sale return (credit note) or purchase return (debit note) against an invoice / bill. */
@Composable
fun ReturnEditorScreen(container: AppContainer, originalTxnId: String, onBack: () -> Unit, onPosted: (String) -> Unit) {
    val books = rememberBooks(container)
    DetailScaffold(title = stringResource(R.string.bill_return_title), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> ReturnEditor(books.session, originalTxnId, modifier) { id ->
                container.kaiBrain.forget()
                onPosted(id)
            }
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun ReturnEditor(s: BooksSession, originalTxnId: String, modifier: Modifier, onPosted: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val drafts = remember { BillDrafts(s) }
    var original by remember { mutableStateOf<TxnEntity?>(null) }
    var items by remember { mutableStateOf(emptyList<Pair<TxnItemEntity, Long>>()) } // line, still returnable
    var qty by remember { mutableStateOf(mapOf<Int, String>()) }
    var reason by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var quote by remember { mutableStateOf<Quote?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<BooksError>()) }
    var draftId by remember { mutableStateOf<String?>(null) }
    var businessName by remember { mutableStateOf("") }
    var businessGstin by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(originalTxnId) {
        val o = s.dao.txn(originalTxnId) ?: return@LaunchedEffect
        original = o
        val returnType = if (o.type == TxnType.SALE.name) TxnType.SALE_RETURN.name else TxnType.PURCHASE_RETURN.name
        val returned = s.dao.returnedItems(o.id, returnType).groupBy { it.sourceLineNo }
        items = s.dao.items(o.id).map { it to (it.qtyMilli - returned[it.lineNo].orEmpty().sumOf { r -> r.qtyMilli }) }
        s.dao.business(s.ctx.businessId)?.let { businessName = it.name; businessGstin = it.gstin }
    }
    val o = original ?: run { BooksNotReady(BooksState.Loading, modifier); return }
    val sale = o.type == TxnType.SALE.name
    val kind = if (sale) DraftKind.SALE_RETURN else DraftKind.PURCHASE_RETURN

    fun draft(): ReturnDraft = ReturnDraft(
        kind, o.id, date.toString(),
        qty.mapNotNull { (lineNo, text) -> parseQty(text).getOrNull()?.takeIf { it > 0 }?.let { ReturnLine(lineNo, it) } }.sortedBy { it.lineNo },
        reason,
    )

    LaunchedEffect(qty, reason, date) {
        delay(250)
        val d = draft()
        quote = if (d.lines.isEmpty()) null else s.engine.quoteReturn(d.toInput("preview", TxnSource.MANUAL), sale)
    }
    val allErrors = errors + quote?.errors.orEmpty()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.bill_return_against, o.number, o.partyName ?: ""), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
        BooksErrors(allErrors)
        if (reviewing) {
            quote?.toPreview(if (sale) "CREDIT NOTE" else "DEBIT NOTE", date, null, o.partyName ?: "", o.partyGstin, 0)
                ?.copy(paidPaise = null, balancePaise = null)
                ?.let { InvoicePreview(it, businessName, businessGstin) }
            Text(stringResource(if (sale) R.string.bill_return_effect_sale else R.string.bill_return_effect_purchase), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
            PrimaryButton(stringResource(R.string.bill_confirm_return), {
                scope.launch {
                    posting = true
                    val d = draft()
                    val id = drafts.save(draftId, kind, d).also { draftId = it }
                    val result = if (sale) s.engine.postSaleReturn(d.toInput(id, drafts.sourceOf(id))) else s.engine.postPurchaseReturn(d.toInput(id, drafts.sourceOf(id)))
                    posting = false
                    when (result) {
                        is PostResult.Posted -> onPosted(result.txn.id)
                        is PostResult.Rejected -> errors = result.errors
                    }
                }
            }, loading = posting, enabled = quote?.ok == true)
            OutlinedButton(onClick = { reviewing = false; errors = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bill_edit_again)) }
            return@Column
        }

        SectionCard(stringResource(R.string.bill_return_items)) {
            items.forEach { (item, left) ->
                ShopCard {
                    Text(item.itemName, fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                    Text(
                        stringResource(R.string.bill_return_left, qtyText(item.qtyMilli), qtyText(left), item.unit, InvoicePdf.money(item.ratePaise)),
                        style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant,
                    )
                    if (left > 0) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DecimalField(stringResource(R.string.bill_return_qty), qty[item.lineNo].orEmpty(), { qty = qty + (item.lineNo to it) }, modifier = Modifier.weight(1f))
                            TextButton(onClick = { qty = qty + (item.lineNo to qtyText(left)) }) { Text(stringResource(R.string.bill_all)) }
                        }
                    }
                }
            }
        }
        DateField(stringResource(R.string.bill_date), date, { it?.let { d -> date = d } })
        ShopTextField(stringResource(R.string.bill_return_reason), reason, { reason = it })
        quote?.totals?.let { Amount(stringResource(if (sale) R.string.bill_credit_note_total else R.string.bill_debit_note_total), it.grandTotalPaise, strong = true) }
        PrimaryButton(stringResource(R.string.bill_review), {
            scope.launch {
                draftId = drafts.save(draftId, kind, draft())
                reviewing = true
            }
        }, enabled = quote?.ok == true && reason.isNotBlank())
        Spacer(Modifier.height(32.dp))
    }
}
