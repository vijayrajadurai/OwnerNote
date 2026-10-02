package com.shopai.app.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.shopai.app.R
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.PartyOpeningInput
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.integration.BooksRejectedException
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.CustomerType
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.tax.GstStates
import com.shopai.app.books.tax.Gstin
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** Customer / Supplier Master: create or edit a party (the same parties the Customers / Suppliers tabs list). */
@Composable
fun PartyFormScreen(container: AppContainer, kind: PartyKind, partyId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val books = rememberBooks(container)
    val title = when {
        kind == PartyKind.CUSTOMER && partyId == null -> R.string.books_customer_new
        kind == PartyKind.CUSTOMER -> R.string.books_customer_edit
        partyId == null -> R.string.books_supplier_new
        else -> R.string.books_supplier_edit
    }
    DetailScaffold(title = stringResource(title), onBack = onBack) { modifier ->
        when (books) {
            is BooksState.Ready -> PartyForm(books.session, kind, partyId, modifier) { id ->
                container.kaiBrain.forget()
                onSaved(id)
            }
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun PartyForm(s: BooksSession, kind: PartyKind, partyId: String?, modifier: Modifier, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(partyId == null) }
    var name by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var whatsapp by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var gstin by remember { mutableStateOf("") }
    var stateCode by remember { mutableStateOf<String?>(null) }
    var stateMenu by remember { mutableStateOf(false) }
    var city by remember { mutableStateOf("") }
    var pincode by remember { mutableStateOf("") }
    var customerType by remember { mutableStateOf<CustomerType?>(null) }
    var creditLimit by remember { mutableStateOf("") }
    var creditDays by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    var openingInTheirFavour by remember { mutableStateOf(false) }
    var backendId by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableStateOf(emptyList<BooksError>()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(partyId) {
        partyId?.let { s.dao.party(it) }?.let { p ->
            name = p.name; mobile = p.mobile.orEmpty(); whatsapp = p.whatsapp.orEmpty(); address = p.address.orEmpty()
            gstin = p.gstin.orEmpty(); stateCode = p.stateCode; city = p.city.orEmpty(); pincode = p.pincode.orEmpty()
            customerType = p.customerType?.let(CustomerType::valueOf); creditLimit = paiseText(p.creditLimitPaise)
            creditDays = p.creditDays?.toString().orEmpty(); notes = p.notes.orEmpty(); backendId = p.backendId
        }
        loaded = true
    }

    val gstinClean = Gstin.normalize(gstin)
    val gstinValid = gstinClean.isEmpty() || Gstin.isValid(gstinClean)
    // A valid GSTIN carries the state; fill it in.
    LaunchedEffect(gstinClean) { if (gstinClean.length == 15 && Gstin.isValid(gstinClean)) stateCode = Gstin.stateCode(gstinClean) }

    fun save() {
        scope.launch {
            saving = true
            val parse = mutableListOf<BooksError>()
            val limit = parseRupees(creditLimit).getOrElse { parse += BooksError(BooksErrorCode.INVALID_AMOUNT, "Check the credit limit", "creditLimitPaise"); null }
            val days = creditDays.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: run { parse += BooksError(BooksErrorCode.INVALID_FIELD, "Check the credit days", "creditDays"); null } }
            val openingPaise = parseRupees(opening).getOrElse { parse += BooksError(BooksErrorCode.INVALID_AMOUNT, "Check the opening balance", "amountPaise"); null }
            if (parse.isNotEmpty()) {
                errors = parse
                saving = false
                return@launch
            }
            val input = PartyInput(
                kind = kind, name = name, mobile = mobile, whatsapp = whatsapp, address = address, gstin = gstin,
                stateCode = stateCode, city = city, pincode = pincode, customerType = customerType,
                creditLimitPaise = limit, creditDays = days, notes = notes, backendId = backendId,
            )
            try {
                var savedId = ""
                s.db.withTransaction {
                    val party = when (val r = if (partyId == null) s.masters.createParty(input) else s.masters.updateParty(partyId, input)) {
                        is MasterResult.Ok -> r.value
                        is MasterResult.Rejected -> throw BooksRejectedException(r.errors)
                    }
                    if (partyId == null && (openingPaise ?: 0) > 0) {
                        val posted = s.engine.postPartyOpening(
                            PartyOpeningInput(party.id, openingPaise!!, inPartysFavour = openingInTheirFavour, date = LocalDate.now(), meta = PostMeta(UUID.randomUUID().toString())),
                        )
                        if (posted is PostResult.Rejected) throw BooksRejectedException(posted.errors)
                    }
                    savedId = party.id
                }
                errors = emptyList()
                onSaved(savedId)
            } catch (e: BooksRejectedException) {
                errors = e.errors
            }
            saving = false
        }
    }

    if (!loaded) {
        BooksNotReady(BooksState.Loading, modifier)
        return
    }

    val phoneKeyboard = KeyboardOptions(keyboardType = KeyboardType.Phone)
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BooksErrors(errors)
        SectionCard(stringResource(R.string.books_section_contact)) {
            ShopTextField(stringResource(R.string.books_party_name), name, { name = it }, error = errors.messageFor("name"))
            ShopTextField(stringResource(R.string.books_mobile), mobile, { mobile = it }, keyboardOptions = phoneKeyboard, error = errors.messageFor("mobile"))
            ShopTextField(stringResource(R.string.books_whatsapp), whatsapp, { whatsapp = it }, keyboardOptions = phoneKeyboard, error = errors.messageFor("whatsapp"))
            if (mobile.isNotBlank() && whatsapp.isBlank()) TextButton(onClick = { whatsapp = mobile }) { Text(stringResource(R.string.books_same_as_mobile)) }
            ShopTextField(stringResource(R.string.books_address), address, { address = it }, singleLine = false)
            ShopTextField(stringResource(R.string.books_city), city, { city = it })
            ShopTextField(stringResource(R.string.books_pincode), pincode, { pincode = it.filter(Char::isDigit).take(6) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), error = errors.messageFor("pincode"))
        }
        SectionCard(stringResource(R.string.books_section_gst)) {
            ShopTextField(
                stringResource(R.string.books_gstin), gstin, { gstin = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(15) },
                error = errors.messageFor("gstin") ?: if (!gstinValid && gstinClean.length == 15) stringResource(R.string.books_gstin_invalid) else null,
            )
            if (gstinClean.length == 15 && gstinValid) Text(stringResource(R.string.books_gstin_valid), color = Success, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.books_state), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
            OutlinedButton(onClick = { stateMenu = true }) { Text(stateCode?.let { "${GstStates.byCode[it]} ($it)" } ?: stringResource(R.string.books_choose_state)) }
            DropdownMenu(expanded = stateMenu, onDismissRequest = { stateMenu = false }) {
                DropdownMenuItem(text = { Text("—") }, onClick = { stateCode = null; stateMenu = false })
                GstStates.byCode.forEach { (code, label) -> DropdownMenuItem(text = { Text("$label ($code)") }, onClick = { stateCode = code; stateMenu = false }) }
            }
            errors.messageFor("stateCode")?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
            if (kind == PartyKind.CUSTOMER) {
                Text(stringResource(R.string.books_customer_type), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
                ChoiceChips(CustomerType.entries, customerType, { customerTypeLabel(it) }) { customerType = it }
            }
        }
        SectionCard(stringResource(R.string.books_section_credit)) {
            if (kind == PartyKind.CUSTOMER) DecimalField(stringResource(R.string.books_credit_limit), creditLimit, { creditLimit = it }, errors.messageFor("creditLimitPaise"))
            ShopTextField(
                stringResource(if (kind == PartyKind.CUSTOMER) R.string.books_credit_days else R.string.books_credit_period), creditDays,
                { creditDays = it.filter(Char::isDigit).take(4) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), error = errors.messageFor("creditDays"),
            )
            if (partyId == null) {
                DecimalField(stringResource(R.string.books_opening_balance), opening, { opening = it }, errors.messageFor("amountPaise"))
                if (opening.isNotBlank()) {
                    val (owesMe, iOwe) = if (kind == PartyKind.CUSTOMER) R.string.books_opening_they_owe to R.string.books_opening_advance_from else R.string.books_opening_i_owe to R.string.books_opening_advance_to
                    ChoiceChips(listOf(false, true), openingInTheirFavour, { stringResource(if (it) iOwe else owesMe) }) { openingInTheirFavour = it }
                }
            }
            ShopTextField(stringResource(R.string.inv_notes), notes, { notes = it }, singleLine = false)
        }
        PrimaryButton(label = stringResource(R.string.save), loading = saving, enabled = name.isNotBlank(), onClick = ::save)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun customerTypeLabel(t: CustomerType): String = stringResource(
    when (t) {
        CustomerType.RETAIL -> R.string.books_ctype_retail
        CustomerType.BUSINESS -> R.string.books_ctype_business
        CustomerType.B2B -> R.string.books_ctype_b2b
        CustomerType.B2C -> R.string.books_ctype_b2c
    },
)
