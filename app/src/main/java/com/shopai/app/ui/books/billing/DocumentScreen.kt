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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.billing.InvoiceDocument
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.billing.InvoiceShare
import com.shopai.app.books.billing.shareCaption
import com.shopai.app.books.data.OpenDoc
import com.shopai.app.books.data.TxnEntity
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.TxnType
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.books.BooksErrors
import com.shopai.app.ui.books.BooksNotReady
import com.shopai.app.ui.books.BooksState
import com.shopai.app.ui.books.rememberBooks
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopAlertDialog
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One posted document, read back from the books. */
@Composable
fun DocumentScreen(
    container: AppContainer,
    txnId: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPayment: (incoming: Boolean, partyId: String, docId: String?) -> Unit,
    onReturn: (originalTxnId: String) -> Unit,
) {
    val books = rememberBooks(container)
    DetailScaffold(title = stringResource(R.string.bill_document), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> Document(books.session, txnId, modifier, onOpen, onPayment, onReturn) { container.kaiBrain.forget() }
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun Document(
    s: BooksSession,
    txnId: String,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onPayment: (Boolean, String, String?) -> Unit,
    onReturn: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var doc by remember { mutableStateOf<InvoiceDocument?>(null) }
    var credits by remember { mutableStateOf(emptyList<OpenDoc>()) }
    var paysTo by remember { mutableStateOf(emptyList<Pair<TxnEntity, Long>>()) }
    var children by remember { mutableStateOf(emptyList<TxnEntity>()) }
    var errors by remember { mutableStateOf(emptyList<BooksError>()) }
    var cancelling by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(txnId, refresh) {
        val d = InvoiceDocument.load(s, txnId)
        doc = d
        if (d == null) return@LaunchedEffect
        val t = d.txn
        credits = if (t.partyId != null && d.balancePaise > 0 && (t.type == TxnType.SALE.name || t.type == TxnType.PURCHASE.name)) {
            val types = if (t.type == TxnType.SALE.name) listOf(TxnType.PAYMENT_IN.name, TxnType.SALE_RETURN.name) else listOf(TxnType.PAYMENT_OUT.name, TxnType.PURCHASE_RETURN.name)
            s.dao.unappliedCredits(s.ctx.businessId, t.partyId, types)
        } else emptyList()
        paysTo = s.dao.allocationsFrom(t.id).mapNotNull { a -> s.dao.txn(a.toTxnId)?.let { it to a.amountPaise } }
        children = s.dao.linkedTxns(t.id).filter { it.type == TxnType.SALE_RETURN.name || it.type == TxnType.PURCHASE_RETURN.name }
    }

    val d = doc ?: run { BooksNotReady(BooksState.Loading, modifier); return }
    val t = d.txn
    val type = d.type
    val isBill = type == TxnType.SALE || type == TxnType.PURCHASE

    if (cancelling) CancelDialog(onDismiss = { cancelling = false }) { reason ->
        scope.launch {
            when (val r = s.engine.void(t.id, reason)) {
                is PostResult.Posted -> { errors = emptyList(); onChanged(); refresh++ }
                is PostResult.Rejected -> errors = r.errors
            }
            cancelling = false
        }
    }

    fun withPdf(action: (java.io.File) -> Unit) {
        scope.launch {
            working = true
            val file = withContext(Dispatchers.IO) { InvoicePdf.render(context, d) }
            working = false
            action(file)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BooksErrors(errors)
        InvoicePreview(d.toPreview(), d.business.name, d.business.gstin)

        // Share / print the same PDF.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.bill_share_whatsapp), { withPdf { InvoiceShare.whatsapp(context, it, d.party?.mobile, shareCaption(d)) } }, Modifier.weight(1f), loading = working)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { withPdf { InvoiceShare.print(context, it) } }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bill_print)) }
            OutlinedButton(onClick = { withPdf { InvoiceShare.other(context, it, shareCaption(d)) } }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bill_share_other)) }
        }

        if (!d.isVoid && isBill && t.partyId != null) {
            if (d.balancePaise > 0) {
                PrimaryButton(
                    stringResource(if (type == TxnType.SALE) R.string.bill_receive_payment else R.string.bill_make_payment),
                    { onPayment(type == TxnType.SALE, t.partyId, t.id) },
                )
            }
            credits.forEach { c ->
                val amount = minOf(c.outstandingPaise, d.balancePaise)
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val r = s.engine.applyCredit(c.id, t.id, amount)) {
                            is PostResult.Posted -> { onChanged(); refresh++ }
                            is PostResult.Rejected -> errors = r.errors
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bill_apply_credit, InvoicePdf.money(amount), c.number)) }
            }
        }
        if (!d.isVoid && isBill && d.items.any { it.productId != null || it.isService }) {
            OutlinedButton(onClick = { onReturn(t.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (type == TxnType.SALE) R.string.bill_sale_return else R.string.bill_purchase_return))
            }
        }

        // Money applied to this document, and what this payment / note settled.
        if (d.applied.isNotEmpty()) {
            ShopCard {
                Text(stringResource(R.string.bill_history), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                d.applied.forEach { a ->
                    TextButton(onClick = { onOpen(a.txnId) }) {
                        Text("${a.number} · ${LocalDate.ofEpochDay(a.date.toLong()).format(DateTimeFormatter.ofPattern("dd MMM"))} · ${InvoicePdf.money(a.amountPaise)}")
                    }
                }
            }
        }
        if (paysTo.isNotEmpty() || children.isNotEmpty() || d.original != null) {
            ShopCard {
                Text(stringResource(R.string.bill_linked), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                d.original?.let { o -> TextButton(onClick = { onOpen(o.id) }) { Text(stringResource(R.string.bill_against, o.number)) } }
                paysTo.forEach { (doc, amount) -> TextButton(onClick = { onOpen(doc.id) }) { Text("${doc.number} · ${InvoicePdf.money(amount)}") } }
                children.forEach { c -> TextButton(onClick = { onOpen(c.id) }) { Text("${c.number} · ${InvoicePdf.money(c.totalPaise)}") } }
                if (t.isAdvance && type in listOf(TxnType.PAYMENT_IN, TxnType.PAYMENT_OUT)) {
                    Text(stringResource(R.string.bill_advance_left, InvoicePdf.money(t.totalPaise - paysTo.sumOf { it.second })), color = ShopAiThemeColors.primary)
                }
            }
        }

        if (d.isVoid) {
            Text(stringResource(R.string.bill_cancelled_reason, t.voidReason.orEmpty()), color = Danger)
        } else {
            TextButton(onClick = { cancelling = true }) { Text(stringResource(R.string.bill_cancel_document), color = Danger) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CancelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bill_cancel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.bill_cancel_body), style = MaterialTheme.typography.bodyMedium)
                ShopTextField(stringResource(R.string.bill_cancel_reason), reason, { reason = it })
            }
        },
        confirmButton = { TextButton(enabled = reason.isNotBlank(), onClick = { onConfirm(reason.trim()) }) { Text(stringResource(R.string.bill_cancel_document), color = Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
