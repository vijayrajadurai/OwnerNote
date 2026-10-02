package com.shopai.app.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.data.ProductEntity
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.tax.HsnRules
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.ui.theme.Warning
import com.shopai.app.util.formatInr

/** The Product Master fields the Inventory card does not show, from the books. */
@Composable
fun ProductMasterInfo(container: AppContainer, productId: String, refreshKey: Int) {
    var product by remember { mutableStateOf<ProductEntity?>(null) }
    var stockValuePaise by remember { mutableStateOf(0L) }
    LaunchedEffect(productId, refreshKey) {
        container.books.session()?.let { s ->
            product = s.dao.product(productId)
            stockValuePaise = s.ledger.stockValue(productId)
        }
    }
    val p = product ?: return
    ShopCard {
        Text(stringResource(R.string.books_master_details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
        if (p.archived) Text(stringResource(R.string.books_archived), color = Danger, style = MaterialTheme.typography.bodyMedium)
        p.hsnCode?.let { code ->
            val (label, color) = when {
                p.hsnVerified -> stringResource(R.string.books_hsn_verified) to Success
                p.hsnConfirmedByUser -> stringResource(R.string.books_hsn_owner_confirmed) to Warning
                else -> HsnRules.VERIFICATION_REQUIRED_MESSAGE to Warning
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${if (p.isService) "SAC" else "HSN"} $code", color = ShopAiThemeColors.onSurface)
                Text(label, color = color, style = MaterialTheme.typography.bodySmall)
            }
        }
        Line(stringResource(R.string.books_tax_type), taxTypeLabel(TaxType.valueOf(p.taxType)) + if (p.cessBp > 0) " + ${bpText(p.cessBp)}% cess" else "")
        if (p.priceIncludesTax) Line(stringResource(R.string.books_price_includes_tax), "✓")
        p.secondaryUnit?.let { Line(stringResource(R.string.books_units), "1 $it = ${qtyText(p.conversionMilli)} ${p.primaryUnit}") }
        p.wholesalePricePaise?.let { Line(stringResource(R.string.books_wholesale_price), formatInr(it / 100.0)) }
        p.retailPricePaise?.let { Line(stringResource(R.string.books_retail_price), formatInr(it / 100.0)) }
        p.minSellingPricePaise?.let { Line(stringResource(R.string.books_min_selling_price), formatInr(it / 100.0)) }
        p.reorderLevelMilli?.let { Line(stringResource(R.string.books_reorder_level), "${qtyText(it)} ${p.primaryUnit}") }
        if (p.batchTracked) Line(stringResource(R.string.books_batch_tracked), "✓")
        if (!p.isService) Line(stringResource(R.string.books_stock_value), formatInr(stockValuePaise / 100.0))
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ShopAiThemeColors.onSurface)
    }
}
