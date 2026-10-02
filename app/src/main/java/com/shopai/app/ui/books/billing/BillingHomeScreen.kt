package com.shopai.app.ui.books.billing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shopai.app.R
import com.shopai.app.books.billing.BillDrafts
import com.shopai.app.books.billing.DraftKind
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.billing.MoneyAccounts
import com.shopai.app.books.data.DraftEntity
import com.shopai.app.books.data.TxnEntity
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.TxnType
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.books.BooksNotReady
import com.shopai.app.ui.books.BooksState
import com.shopai.app.ui.books.ChoiceChips
import com.shopai.app.ui.books.rememberBooks
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class DocFilter(val types: List<TxnType>) {
    ALL(listOf(TxnType.SALE, TxnType.PURCHASE, TxnType.SALE_RETURN, TxnType.PURCHASE_RETURN, TxnType.PAYMENT_IN, TxnType.PAYMENT_OUT)),
    SALES(listOf(TxnType.SALE)),
    PURCHASES(listOf(TxnType.PURCHASE)),
    RETURNS(listOf(TxnType.SALE_RETURN, TxnType.PURCHASE_RETURN)),
    PAYMENTS(listOf(TxnType.PAYMENT_IN, TxnType.PAYMENT_OUT)),
}

/** Sales & purchases: money accounts, new bill / payment, drafts to finish, and every document. */
@Composable
fun BillingHomeScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onNewBill: (DraftKind, String?) -> Unit,
    onResume: (DraftEntity) -> Unit,
    onPayment: (incoming: Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    val books = rememberBooks(container)
    DetailScaffold(title = stringResource(R.string.bill_home_title), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> BillingHome(books.session, modifier, onNewBill, onResume, onPayment, onOpen)
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun BillingHome(
    s: BooksSession,
    modifier: Modifier,
    onNewBill: (DraftKind, String?) -> Unit,
    onResume: (DraftEntity) -> Unit,
    onPayment: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var balances by remember { mutableStateOf(emptyList<Pair<String, Long>>()) }
    var drafts by remember { mutableStateOf(emptyList<DraftEntity>()) }
    var filter by remember { mutableStateOf(DocFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var docs by remember { mutableStateOf(emptyList<Pair<TxnEntity, Long?>>()) }

    // Back from a bill / payment: show the new figures.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        MoneyAccounts.ensureDefaults(s)
        balances = s.dao.moneyAccounts(s.ctx.businessId).map { it.name to s.ledger.moneyBalance(it.id) }
        drafts = BillDrafts(s).open().filter { it.kind == DraftKind.SALE.name || it.kind == DraftKind.PURCHASE.name }
    }
    LaunchedEffect(refresh, filter, query) {
        docs = s.dao.txnsOfTypes(s.ctx.businessId, filter.types.map { it.name }, null, query.trim(), 200, 0).map { t ->
            t to if (t.status == "CONFIRMED" && (t.type == TxnType.SALE.name || t.type == TxnType.PURCHASE.name)) s.ledger.outstanding(t.id) else null
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            ShopCard {
                Text(stringResource(R.string.bill_money_title), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                balances.forEach { (name, paise) -> Amount(name, paise) }
                Text(stringResource(R.string.bill_money_caption), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.bill_new_sale), { onNewBill(DraftKind.SALE, null) }, Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.bill_new_purchase), { onNewBill(DraftKind.PURCHASE, null) }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPayment(true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bill_receive_payment)) }
                OutlinedButton(onClick = { onPayment(false) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bill_make_payment)) }
            }
        }
        if (drafts.isNotEmpty()) {
            item { Text(stringResource(R.string.bill_drafts), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface) }
            items(drafts, key = { "d-${it.id}" }) { d ->
                ShopCard(Modifier.clickable { onResume(d) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(draftLabel(d.kind), fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                        TextButton(onClick = { scope.launch { BillDrafts(s).discard(d.id); refresh++ } }) { Text(stringResource(R.string.bill_discard), color = Danger) }
                    }
                    Text(stringResource(R.string.bill_draft_caption, d.source.lowercase()), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                }
            }
        }
        item {
            ChoiceChips(DocFilter.entries, filter, { filterLabel(it) }) { filter = it }
            ShopTextField(stringResource(R.string.bill_search_docs), query, { query = it })
        }
        if (docs.isEmpty()) item { Text(stringResource(R.string.bill_no_docs), color = ShopAiThemeColors.onSurfaceVariant) }
        items(docs, key = { it.first.id }) { (t, due) -> DocRow(t, due) { onOpen(t.id) } }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun DocRow(t: TxnEntity, due: Long?, onClick: () -> Unit) {
    ShopCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("${typeLabel(t.type)} · ${t.number}", fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                Text(
                    "${t.partyName ?: stringResource(R.string.bill_cash_sale)} · ${LocalDate.ofEpochDay(t.date.toLong()).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))}",
                    style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant,
                )
            }
            Column {
                Text(InvoicePdf.money(t.totalPaise), fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
                when {
                    t.status == "VOID" -> Text(stringResource(R.string.bill_cancelled), color = Danger, style = MaterialTheme.typography.bodySmall)
                    due == null -> Unit
                    due == 0L -> Text(stringResource(R.string.bill_status_paid), color = Success, style = MaterialTheme.typography.bodySmall)
                    due < t.totalPaise -> Text(stringResource(R.string.bill_status_partial, InvoicePdf.money(due)), color = Danger, style = MaterialTheme.typography.bodySmall)
                    else -> Text(stringResource(R.string.bill_status_due), color = Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: String): String = stringResource(
    when (type) {
        TxnType.SALE.name -> R.string.bill_type_sale
        TxnType.PURCHASE.name -> R.string.bill_type_purchase
        TxnType.SALE_RETURN.name -> R.string.bill_type_credit_note
        TxnType.PURCHASE_RETURN.name -> R.string.bill_type_debit_note
        TxnType.PAYMENT_IN.name -> R.string.bill_type_payment_in
        TxnType.PAYMENT_OUT.name -> R.string.bill_type_payment_out
        else -> R.string.bill_type_other
    },
)

@Composable
private fun filterLabel(f: DocFilter): String = stringResource(
    when (f) {
        DocFilter.ALL -> R.string.bill_filter_all
        DocFilter.SALES -> R.string.bill_filter_sales
        DocFilter.PURCHASES -> R.string.bill_filter_purchases
        DocFilter.RETURNS -> R.string.bill_filter_returns
        DocFilter.PAYMENTS -> R.string.bill_filter_payments
    },
)

@Composable
private fun draftLabel(kind: String): String = stringResource(
    when (kind) {
        DraftKind.SALE.name -> R.string.bill_type_sale
        DraftKind.PURCHASE.name -> R.string.bill_type_purchase
        DraftKind.SALE_RETURN.name -> R.string.bill_type_credit_note
        DraftKind.PURCHASE_RETURN.name -> R.string.bill_type_debit_note
        DraftKind.PAYMENT_IN.name -> R.string.bill_type_payment_in
        DraftKind.PAYMENT_OUT.name -> R.string.bill_type_payment_out
        else -> R.string.bill_type_other
    },
)
