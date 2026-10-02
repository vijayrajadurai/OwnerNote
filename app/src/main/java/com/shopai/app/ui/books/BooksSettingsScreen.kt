package com.shopai.app.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.BusinessSetup
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.tax.GstStates
import com.shopai.app.books.tax.Gstin
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import kotlinx.coroutines.launch

/** Business type, GST registration and the billing / stock rules the books follow. */
@Composable
fun BooksSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val books = rememberBooks(container)
    DetailScaffold(title = stringResource(R.string.books_settings), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> BooksSettings(books.session, modifier)
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun BooksSettings(s: BooksSession, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(BusinessType.RETAILER) }
    var gstin by remember { mutableStateOf("") }
    var stateCode by remember { mutableStateOf<String?>(null) }
    var stateMenu by remember { mutableStateOf(false) }
    var negativeStock by remember { mutableStateOf(false) }
    var futureDates by remember { mutableStateOf(false) }
    var batches by remember { mutableStateOf(false) }
    var roundOff by remember { mutableStateOf(true) }
    var rates by remember { mutableStateOf("") }
    var importNote by remember { mutableStateOf<String?>(null) }
    var upiId by remember { mutableStateOf("") }
    var bankDetails by remember { mutableStateOf("") }
    var terms by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(emptyList<BooksError>()) }
    var saved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        s.dao.business(s.ctx.businessId)?.let { b ->
            name = b.name; owner = b.ownerName; phone = b.phone; address = b.address.orEmpty()
            type = BusinessType.valueOf(b.businessType); gstin = b.gstin.orEmpty(); stateCode = b.stateCode
            negativeStock = b.allowNegativeStock; futureDates = b.allowFutureDates; batches = b.batchTracking; roundOff = b.roundOff
            rates = b.gstRatesBp.split(',').mapNotNull { it.trim().toIntOrNull() }.joinToString(", ") { bpText(it) }
            importNote = b.importSummary
            upiId = b.upiId.orEmpty(); bankDetails = b.bankDetails.orEmpty(); terms = b.invoiceTerms.orEmpty()
        }
        loaded = true
    }

    fun save() {
        scope.launch {
            saving = true
            saved = false
            val slabs = rates.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { parsePercentBp(it).getOrNull() }
            if (slabs.any { it == null }) {
                errors = listOf(BooksError(BooksErrorCode.INVALID_GST, "Write the GST rates like 0, 5, 12, 18, 28", "gstRatesBp"))
                saving = false
                return@launch
            }
            val setup = s.masters.setupBusiness(BusinessSetup(name, type, owner, stateCode, gstin, address, phone))
            val settings = if (setup is MasterResult.Ok) {
                s.masters.updateSettings(negativeStock, futureDates, batches, roundOff, slabs.filterNotNull()).let { r ->
                    if (r is MasterResult.Ok) s.masters.updateInvoiceDetails(upiId, bankDetails, terms) else r
                }
            } else {
                setup
            }
            errors = (settings as? MasterResult.Rejected)?.errors.orEmpty()
            saved = errors.isEmpty()
            saving = false
        }
    }

    if (!loaded) {
        BooksNotReady(BooksState.Loading, modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BooksErrors(errors)
        if (saved) Text(stringResource(R.string.books_saved), color = Success)
        SectionCard(stringResource(R.string.books_section_business)) {
            ShopTextField(stringResource(R.string.books_business_name), name, { name = it }, error = errors.messageFor("name"))
            ShopTextField(stringResource(R.string.books_address), address, { address = it }, singleLine = false)
            Text(stringResource(R.string.books_business_type), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
            ChoiceChips(BusinessType.entries, type, { businessTypeLabel(it) }) { type = it }
            Text(
                stringResource(if (type.inventory) R.string.books_inventory_on else R.string.books_inventory_off),
                style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant,
            )
        }
        SectionCard(stringResource(R.string.books_section_gst)) {
            ShopTextField(
                stringResource(R.string.books_gstin), gstin, { gstin = it.uppercase().filter(Char::isLetterOrDigit).take(15) },
                error = errors.messageFor("gstin") ?: if (gstin.length == 15 && !Gstin.isValid(gstin)) stringResource(R.string.books_gstin_invalid) else null,
            )
            Text(stringResource(R.string.books_state_caption), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
            OutlinedButton(onClick = { stateMenu = true }) { Text(stateCode?.let { "${GstStates.byCode[it]} ($it)" } ?: stringResource(R.string.books_choose_state)) }
            DropdownMenu(expanded = stateMenu, onDismissRequest = { stateMenu = false }) {
                GstStates.byCode.forEach { (code, label) -> DropdownMenuItem(text = { Text("$label ($code)") }, onClick = { stateCode = code; stateMenu = false }) }
            }
            ShopTextField(stringResource(R.string.books_gst_rates), rates, { rates = it }, error = errors.messageFor("gstRatesBp"))
            Text(stringResource(R.string.books_gst_rates_caption), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
        }
        SectionCard(stringResource(R.string.books_section_rules)) {
            SwitchRow(stringResource(R.string.books_allow_negative_stock), negativeStock, { negativeStock = it })
            SwitchRow(stringResource(R.string.books_allow_future_dates), futureDates, { futureDates = it })
            SwitchRow(stringResource(R.string.books_batch_tracking), batches, { batches = it }, stringResource(R.string.books_batch_tracking_caption))
            SwitchRow(stringResource(R.string.books_round_off), roundOff, { roundOff = it })
        }
        SectionCard(stringResource(R.string.books_section_invoice)) {
            ShopTextField(stringResource(R.string.books_upi_id), upiId, { upiId = it.trim() }, placeholder = "shop@upi", error = errors.messageFor("upiId"))
            ShopTextField(stringResource(R.string.books_bank_details), bankDetails, { bankDetails = it }, singleLine = false, placeholder = stringResource(R.string.books_bank_details_hint))
            ShopTextField(stringResource(R.string.books_invoice_terms), terms, { terms = it }, singleLine = false)
        }
        importNote?.let { Text(stringResource(R.string.books_imported_note, it), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant) }
        PrimaryButton(label = stringResource(R.string.save), loading = saving, enabled = name.isNotBlank(), onClick = ::save)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun businessTypeLabel(t: BusinessType): String = stringResource(
    when (t) {
        BusinessType.RETAILER -> R.string.books_type_retailer
        BusinessType.WHOLESALER -> R.string.books_type_wholesaler
        BusinessType.DISTRIBUTOR -> R.string.books_type_distributor
        BusinessType.SERVICE -> R.string.books_type_service
        BusinessType.PRODUCT_AND_SERVICE -> R.string.books_type_product_service
    },
)
