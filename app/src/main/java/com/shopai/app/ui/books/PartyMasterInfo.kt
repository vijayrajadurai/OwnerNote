package com.shopai.app.ui.books

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.shopai.app.R
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.tax.GstStates
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.formatInr

/** GSTIN, state, address and credit terms of a party, when the books hold them. */
@Composable
fun PartyMasterInfo(container: AppContainer, partyId: String, refreshKey: Int) {
    var party by remember { mutableStateOf<PartyEntity?>(null) }
    LaunchedEffect(partyId, refreshKey) {
        party = container.books.session()?.let { s -> s.dao.party(partyId) ?: s.dao.partyByBackendId(s.ctx.businessId, partyId) }
    }
    val p = party ?: return
    val lines = listOfNotNull(
        p.gstin?.let { stringResource(R.string.books_info_gstin, it) },
        p.stateCode?.let { GstStates.byCode[it] },
        listOfNotNull(p.address, p.city, p.pincode).joinToString(", ").takeIf { it.isNotBlank() },
        p.whatsapp?.takeIf { it != p.mobile }?.let { stringResource(R.string.books_info_whatsapp, it) },
        p.creditLimitPaise?.let { stringResource(R.string.books_info_credit_limit, formatInr(it / 100.0)) },
        p.creditDays?.let { stringResource(R.string.books_info_credit_days, it) },
        p.notes,
    )
    if (lines.isEmpty()) return
    Column {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant) }
    }
}
