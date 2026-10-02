package com.shopai.app.ui.books.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.billing.InvoiceDocument
import com.shopai.app.books.billing.InvoicePdf
import com.shopai.app.books.billing.RupeesInWords
import com.shopai.app.books.engine.Quote
import com.shopai.app.books.model.Qty
import com.shopai.app.books.tax.GstStates
import com.shopai.app.ui.books.bpText
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What an invoice / bill / note shows, whether still a quote or already posted. */
data class PreviewModel(
    val title: String,
    val number: String,
    val date: LocalDate,
    val dueDate: LocalDate?,
    val partyName: String,
    val partyGstin: String?,
    val placeOfSupply: String?,
    val lines: List<PreviewLine>,
    val grossPaise: Long,
    val discountPaise: Long,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessPaise: Long,
    val roundOffPaise: Long,
    val totalPaise: Long,
    /** Paid with the bill (quote) or applied so far (posted). */
    val paidPaise: Long?,
    val returnedPaise: Long = 0,
    val balancePaise: Long?,
    val cancelled: Boolean = false,
)

data class PreviewLine(val name: String, val hsn: String?, val qty: String, val ratePaise: Long, val taxablePaise: Long, val gst: String, val totalPaise: Long)

fun Quote.toPreview(title: String, date: LocalDate, dueDate: LocalDate?, partyName: String, partyGstin: String?, paidPaise: Long): PreviewModel? {
    val t = totals ?: return null
    return PreviewModel(
        title = title, number = number.orEmpty(), date = date, dueDate = dueDate, partyName = partyName, partyGstin = partyGstin,
        placeOfSupply = placeOfSupply,
        lines = lines.map {
            PreviewLine(it.name, it.hsn, "${Qty.toDecimal(it.qtyMilli).toPlainString()} ${it.unit}", it.ratePaise, it.result.taxablePaise,
                if (it.taxType == "GST") "${bpText(it.gstBp)}%" else "—", it.result.totalPaise)
        },
        grossPaise = t.grossPaise, discountPaise = t.discountPaise, taxablePaise = t.taxablePaise, cgstPaise = t.cgstPaise,
        sgstPaise = t.sgstPaise, igstPaise = t.igstPaise, cessPaise = t.cessPaise, roundOffPaise = t.roundOffPaise,
        totalPaise = t.grandTotalPaise, paidPaise = paidPaise, balancePaise = t.grandTotalPaise - paidPaise,
    )
}

fun InvoiceDocument.toPreview(): PreviewModel = PreviewModel(
    title = title, number = txn.number, date = LocalDate.ofEpochDay(txn.date.toLong()),
    dueDate = txn.dueDate?.let { LocalDate.ofEpochDay(it.toLong()) }, partyName = party?.name ?: txn.partyName ?: "Cash sale",
    partyGstin = party?.gstin ?: txn.partyGstin, placeOfSupply = txn.placeOfSupply,
    lines = items.map {
        PreviewLine(it.itemName, it.hsnCode, "${Qty.toDecimal(it.qtyMilli).toPlainString()} ${it.unit}", it.ratePaise, it.taxablePaise,
            if (it.taxType == "GST") "${bpText(it.gstBp)}%" else "—", it.totalPaise)
    },
    grossPaise = txn.grossPaise, discountPaise = txn.discountPaise, taxablePaise = txn.taxablePaise, cgstPaise = txn.cgstPaise,
    sgstPaise = txn.sgstPaise, igstPaise = txn.igstPaise, cessPaise = txn.cessPaise, roundOffPaise = txn.roundOffPaise,
    totalPaise = txn.totalPaise, paidPaise = paidPaise, returnedPaise = returnedPaise, balancePaise = balancePaise, cancelled = isVoid,
)

private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy")

@Composable
fun InvoicePreview(m: PreviewModel, businessName: String, businessGstin: String?) {
    ShopCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(businessName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                businessGstin?.let { Text("GSTIN $it", style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant) }
            }
            Column {
                Text(m.title, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.primary, textAlign = TextAlign.End)
                Text(m.number, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurface, textAlign = TextAlign.End)
                Text(m.date.format(dateFormat), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
            }
        }
        if (m.cancelled) Text(stringResource(R.string.bill_cancelled), color = Danger, fontWeight = FontWeight.Bold)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
        Text(m.partyName, fontWeight = FontWeight.SemiBold, color = ShopAiThemeColors.onSurface)
        listOfNotNull(
            m.partyGstin?.let { "GSTIN $it" },
            m.placeOfSupply?.let { stringResource(R.string.bill_place_of_supply, GstStates.byCode[it] ?: it) },
            m.dueDate?.let { stringResource(R.string.bill_due_on, it.format(dateFormat)) },
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant) }

        if (m.lines.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
            m.lines.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurface)
                        Text(
                            listOfNotNull(l.hsn?.let { "HSN $it" }, "${l.qty} × ${InvoicePdf.money(l.ratePaise)}", "GST ${l.gst}").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant,
                        )
                    }
                    Text(InvoicePdf.money(l.totalPaise), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurface)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
            Amount(stringResource(R.string.bill_subtotal), m.grossPaise)
            if (m.discountPaise != 0L) Amount(stringResource(R.string.bill_discount), -m.discountPaise)
            Amount(stringResource(R.string.bill_taxable), m.taxablePaise)
            if (m.cgstPaise != 0L) Amount("CGST", m.cgstPaise)
            if (m.sgstPaise != 0L) Amount("SGST", m.sgstPaise)
            if (m.igstPaise != 0L) Amount("IGST", m.igstPaise)
            if (m.cessPaise != 0L) Amount("Cess", m.cessPaise)
            if (m.roundOffPaise != 0L) Amount(stringResource(R.string.bill_round_off), m.roundOffPaise)
        }
        Amount(stringResource(R.string.bill_grand_total), m.totalPaise, strong = true)
        Text(RupeesInWords.of(m.totalPaise), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
        m.paidPaise?.let { Amount(stringResource(R.string.bill_paid_so_far), it) }
        if (m.returnedPaise != 0L) Amount(stringResource(R.string.bill_returns), m.returnedPaise)
        m.balancePaise?.let { Amount(stringResource(R.string.bill_balance_due), it, strong = true, color = if (it > 0) Danger else ShopAiThemeColors.primary) }
    }
}

@Composable
fun Amount(label: String, paise: Long, strong: Boolean = false, color: Color = ShopAiThemeColors.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (strong) color else ShopAiThemeColors.onSurfaceVariant, fontWeight = if (strong) FontWeight.Bold else null)
        Text(InvoicePdf.money(paise), style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = if (strong) FontWeight.Bold else null)
    }
}
