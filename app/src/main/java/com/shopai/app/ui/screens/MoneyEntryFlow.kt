package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.CreatePartyInput
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.util.AmountWords
import com.shopai.app.util.exceedsMaxLedgerAmount
import com.shopai.app.util.formatInr
import com.shopai.app.util.localDateToIsoInstant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add Credit / Add Debit in three steps, one question each (owner, 22 Oct 2026, preview approved): who (they owe me / I owe them,
 * the shop's people as big faces with their balance, a search, or a new name) → how much (a big number pad, the amount said in
 * words, quick +₹100 / +₹500 …) → is this right ("Kumar owes you ₹2,500", the balance before → after, when it's due, WhatsApp for a
 * credit). Saving is the same as before: the same entry, the same due date rule, the same Kai message — this is the screen only.
 */
@Composable
fun MoneyEntryFlow(
    container: AppContainer,
    startCredit: Boolean,
    prefillId: String?,
    prefillName: String?,
    prefillPhone: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val tamil = Locale.getDefault().language == "ta"
    val locked = !prefillName.isNullOrBlank() || !prefillId.isNullOrBlank()
    var credit by remember { mutableStateOf(startCredit) }
    var step by remember { mutableStateOf(if (locked) 2 else 1) }
    var search by remember { mutableStateOf("") }
    var who by remember { mutableStateOf<Picked?>(if (locked) Picked(prefillId, prefillName.orEmpty(), 0.0) else null) }
    var amount by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var dueOther by remember { mutableStateOf(false) }
    var whatsApp by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var customers by remember { mutableStateOf<List<PartySummary>>(emptyList()) }
    var suppliers by remember { mutableStateOf<List<PartySummary>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val errorSaveTransaction = stringResource(R.string.error_save_transaction)

    LaunchedEffect(Unit) {
        customers = runCatching { container.partyRepository.getCustomers() }.getOrDefault(emptyList())
        suppliers = runCatching { container.partyRepository.getSuppliers() }.getOrDefault(emptyList())
        // A party opened from its own page: its balance for the "before → after" line.
        if (locked) {
            val list = if (startCredit) customers else suppliers
            list.firstOrNull { it.id == prefillId || (prefillId == null && it.name.equals(prefillName, ignoreCase = true)) }
                ?.let { p -> who = Picked(prefillId, prefillName ?: p.name, p.pendingTotal) }
        }
    }

    val entered = amount.toDoubleOrNull() ?: 0.0
    val tooLarge = exceedsMaxLedgerAmount(entered)
    val party = who
    val canSave = party != null && (locked || party.name.trim().length >= 2) && entered > 0 && !tooLarge && dueDate != null

    fun back() {
        when {
            step == 3 -> step = 2
            step == 2 && !locked -> step = 1
            else -> onBack()
        }
    }

    /** The entry, saved exactly as the Add Credit / Add Debit screens always saved it. */
    fun save() {
        val p = party ?: return
        val due = dueDate ?: return
        scope.launch {
            loading = true
            error = null
            runCatching {
                val name = p.name.trim()
                var id = p.id
                if (id == null && !prefillPhone.isNullOrBlank()) {
                    id = if (credit) container.partyRepository.createCustomer(CreatePartyInput(name = name, phone = prefillPhone)).id
                    else container.partyRepository.createSupplier(CreatePartyInput(name = name, phone = prefillPhone)).id
                }
                val saved = if (credit) container.transactionRepository.createCredit(
                    CreateCreditInput(customerId = id, customerName = if (id == null) name else null, amount = entered, description = null,
                        dueDate = localDateToIsoInstant(due)),
                ) else container.transactionRepository.createDebit(
                    CreateDebitInput(supplierId = id, supplierName = if (id == null) name else null, amount = entered, description = null,
                        dueDate = localDateToIsoInstant(due)),
                )
                // Kai reacts from the saved values (the database is the source of truth).
                val brain = container.kaiBrain
                container.appScope.launch {
                    brain.announce(
                        brain.saved(
                            name = name,
                            amount = saved.amount.toDoubleOrNull() ?: entered,
                            direction = if (credit) com.shopai.app.brain.Direction.RECEIVABLE else com.shopai.app.brain.Direction.PAYABLE,
                            dueDate = com.shopai.app.util.parseIsoToLocalDate(saved.dueDate) ?: due,
                            lang = com.shopai.app.brain.KaiLanguage.forAppLocale(),
                        ),
                    )
                }
                // Asked for: the payment reminder card to WhatsApp — opened, the owner sends it.
                if (credit && whatsApp) runCatching {
                    val business = runCatching { container.businessRepository.getMyBusiness() }.getOrNull()
                    val sender = com.shopai.app.util.formatPaymentReminderSender(
                        business?.businessName?.trim().orEmpty().ifBlank { business?.ownerName?.trim().orEmpty() },
                        com.shopai.app.util.displayIndianPhone(business?.phone),
                    )
                    val amountLabel = formatInr(entered)
                    val dueLabel = com.shopai.app.util.formatReminderShareDate(localDateToIsoInstant(due))
                    val caption = com.shopai.app.util.buildPaymentReminderShareText(name, amountLabel, dueLabel, sender)
                    val bitmap = withContext(Dispatchers.Default) { com.shopai.app.util.createPaymentReminderCardBitmap(context, amountLabel, dueLabel, sender) }
                    com.shopai.app.util.sharePaymentReminderToWhatsApp(context, bitmap, caption)
                }
                onDone()
            }.onFailure {
                container.presentApiError(it, errorSaveTransaction, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })
    // The phone's back goes one step back, as the arrow does.
    androidx.activity.compose.BackHandler(enabled = step > (if (locked) 2 else 1)) { back() }

    val title = when (step) {
        1 -> stringResource(R.string.money_who_title)
        2 -> stringResource(R.string.money_amount_title)
        else -> stringResource(R.string.money_check_title)
    }
    DetailScaffold(title = title, onBack = { back() }) { contentModifier ->
        Column(contentModifier.fillMaxWidth()) {
            // How far: three bars and "2 / 3".
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                (1..3).forEach { n ->
                    Box(Modifier.padding(end = 4.dp).width(28.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(if (n <= step) Green else Line))
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.money_step, step, 3), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            when (step) {
                1 -> WhoStep(credit, { credit = it; who = null }, search, { search = it }, if (credit) customers else suppliers) { picked ->
                    who = picked
                    step = 2
                }
                2 -> AmountStep(party, amount, { amount = it }, tooLarge, tamil, onWho = { if (!locked) step = 1 }) { if (entered > 0 && !tooLarge) step = 3 }
                else -> CheckStep(
                    credit = credit, party = party, entered = entered, dueDate = dueDate, dueOther = dueOther,
                    onDue = { d, other -> dueDate = d; dueOther = other }, whatsApp = whatsApp, onWhatsApp = { whatsApp = it },
                    error = error, canSave = canSave, loading = loading, onSave = { save() },
                )
            }
        }
    }
}

/** Who the entry is with: an existing party (with its balance) or a new name. */
private data class Picked(val id: String?, val name: String, val owes: Double)

@Composable
private fun WhoStep(
    credit: Boolean, onCredit: (Boolean) -> Unit, search: String, onSearch: (String) -> Unit, people: List<PartySummary>,
    onPick: (Picked) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // They owe me / I owe them, in words — never "credit / debit" alone.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WayCard(stringResource(R.string.money_they_owe), stringResource(R.string.money_they_owe_sub), Icons.Filled.ArrowDownward, Orange, OrangeWash,
                selected = credit, modifier = Modifier.weight(1f)) { onCredit(true) }
            WayCard(stringResource(R.string.money_i_owe), stringResource(R.string.money_i_owe_sub), Icons.Filled.ArrowUpward, Purple, PurpleWash,
                selected = !credit, modifier = Modifier.weight(1f)) { onCredit(false) }
        }
        OutlinedTextField(
            value = search, onValueChange = onSearch, singleLine = true,
            placeholder = { Text(stringResource(R.string.money_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Text(stringResource(if (credit) R.string.money_customers else R.string.money_suppliers), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val q = search.trim()
        val shown = (if (q.isEmpty()) people.sortedWith(compareByDescending<PartySummary> { it.pendingTotal }.thenBy { it.name.lowercase() }).take(11)
        else people.filter { it.name.contains(q, ignoreCase = true) }.take(29))
        val exact = people.any { it.name.equals(q, ignoreCase = true) }
        val newName = if (q.length >= 2 && !exact) q else null
        // The people, three to a row, and last the new-name tile (null): the one typed, added as a new customer / supplier when
        // saved (as before).
        val tiles: List<PartySummary?> = shown + listOf(null)
        tiles.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { c, p ->
                    Box(Modifier.weight(1f)) {
                        if (p == null) NewFace(newName, stringResource(if (credit) R.string.money_new_customer else R.string.money_new_supplier)) {
                            newName?.let { onPick(Picked(null, it, 0.0)) }
                        } else Face(p.name, if (p.pendingTotal > 0.005) stringResource(if (credit) R.string.money_owes_you else R.string.money_you_owe, rupees(p.pendingTotal))
                            else stringResource(R.string.money_clear), r * 3 + c) { onPick(Picked(p.id, p.name, p.pendingTotal)) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun WayCard(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, wash: Color, selected: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.heightIn(min = 108.dp).clip(shape).background(if (selected) wash.copy(alpha = 0.45f) else Color.White)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) Ink else Line, shape).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(wash), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(title, color = Label, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Text(sub, color = Body, fontSize = 12.sp)
    }
}

@Composable
private fun Face(name: String, sub: String, index: Int, onClick: () -> Unit) {
    val (wash, ink) = faceColors[index % faceColors.size]
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(wash), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase(), color = ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(name, color = Label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(sub, color = Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NewFace(typed: String?, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = typed != null, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).border(2.dp, if (typed != null) Blue else Line, CircleShape), contentAlignment = Alignment.Center) {
            Text("+", color = if (typed != null) Blue else Muted, fontSize = 28.sp)
        }
        Text(typed ?: stringResource(R.string.money_new), color = if (typed != null) Blue else Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(if (typed != null) sub else stringResource(R.string.money_new_type_name), color = Muted, fontSize = 11.5.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AmountStep(party: Picked?, amount: String, onAmount: (String) -> Unit, tooLarge: Boolean, tamil: Boolean, onWho: () -> Unit, onNext: () -> Unit) {
    val entered = amount.toDoubleOrNull() ?: 0.0
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        party?.let { p ->
            Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Tile).clickable(onClick = onWho).padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(faceColors[0].first), contentAlignment = Alignment.Center) {
                    Text(p.name.trim().take(1).uppercase(), color = faceColors[0].second, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(8.dp))
                Text(p.name, color = Label, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // The amount big, and said in words — an extra zero is seen at once.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("₹ ", color = Muted, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(if (entered > 0) grouped(amount) else "0", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (entered > 0) Label else Grey)
            }
            Text(if (tooLarge) stringResource(R.string.amount_max_one_crore) else AmountWords.of(entered, tamil),
                color = if (tooLarge) Red else Body, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(100, 500, 1000, 2000).forEach { add ->
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).border(1.5.dp, Line, RoundedCornerShape(22.dp))
                    .clickable { onAmount(plain((amount.toDoubleOrNull() ?: 0.0) + add)) }, contentAlignment = Alignment.Center) {
                    Text("+" + formatInr(add.toDouble()), color = Label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "del")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(14.dp)).background(Tile).clickable { onAmount(padPress(amount, k)) },
                        contentAlignment = Alignment.Center) {
                        if (k == "del") Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.money_delete), tint = Label)
                        else Text(k, color = Label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        BigButton(if (entered > 0 && !tooLarge) stringResource(R.string.money_next) else stringResource(R.string.money_enter_amount),
            enabled = entered > 0 && !tooLarge, onClick = onNext)
    }
}

@Composable
private fun CheckStep(
    credit: Boolean, party: Picked?, entered: Double, dueDate: LocalDate?, dueOther: Boolean, onDue: (LocalDate?, Boolean) -> Unit,
    whatsApp: Boolean, onWhatsApp: (Boolean) -> Unit, error: String?, canSave: Boolean, loading: Boolean, onSave: () -> Unit,
) {
    val name = party?.name.orEmpty()
    val today = LocalDate.now()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The entry as one sentence, and what it does to the balance.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (credit) OrangeWash.copy(alpha = 0.45f) else PurpleWash.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(faceColors[0].first), contentAlignment = Alignment.Center) {
                Text(name.trim().take(1).uppercase(), color = faceColors[0].second, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(stringResource(if (credit) R.string.money_sentence_credit else R.string.money_sentence_debit, name, rupees(entered)),
                color = Label, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 29.sp)
            party?.let { p -> Text(stringResource(R.string.money_balance_change, rupees(p.owes), rupees(p.owes + entered)), color = Body, fontSize = 14.5.sp) }
        }
        // When it's due — required, as before: 7 / 15 / 30 days or another date.
        Text(stringResource(if (credit) R.string.money_due_credit else R.string.money_due_debit), color = Label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(7L, 15L, 30L).forEach { days ->
                val d = today.plusDays(days)
                Chip(stringResource(R.string.money_due_days, days.toInt()), selected = !dueOther && dueDate == d, modifier = Modifier.weight(1f)) { onDue(d, false) }
            }
            Chip(stringResource(R.string.money_due_other), selected = dueOther, modifier = Modifier.weight(1.3f)) { onDue(null, true) }
        }
        if (dueOther) FutureDatePickerField(
            label = stringResource(R.string.due_date_label), selectedDate = dueDate, onDateSelected = { onDue(it, true) },
            placeholder = stringResource(R.string.due_date_placeholder), allowEmpty = false,
        )
        if (credit) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tile).clickable { onWhatsApp(!whatsApp) }
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.money_whatsapp, name), color = Label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.money_whatsapp_sub), color = Body, fontSize = 12.5.sp)
            }
            Switch(checked = whatsApp, onCheckedChange = onWhatsApp)
        }
        error?.let { Text(it, color = Red, fontSize = 13.sp) }
        BigButton(if (dueDate == null) stringResource(R.string.money_pick_due) else stringResource(R.string.money_save), enabled = canSave && !loading, onClick = onSave)
        Text(stringResource(R.string.money_nothing_before_save), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (selected) Ink else Tile).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Color.White else Label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(if (enabled) Ink else Line).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Color.White else Muted, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** The number after a key: a digit, "." or "del" (at most 9 digits and two decimals). */
internal fun padPress(value: String, key: String): String = when (key) {
    "del" -> value.dropLast(1)
    "." -> if (value.contains('.')) value else value.ifEmpty { "0" } + "."
    else -> when {
        value.contains('.') && value.substringAfter('.').length >= 2 -> value
        value.filter(Char::isDigit).length >= 9 -> value
        value == "0" -> key
        else -> value + key
    }
}

private fun plain(x: Double): String = java.math.BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()

/** ₹ with Indian grouping, paise shown only when there are paise (formatInr rounds them away). */
private fun rupees(x: Double): String = if (x % 1.0 == 0.0) formatInr(x) else "₹" + java.text.NumberFormat.getNumberInstance(Locale("en", "IN"))
    .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(x)

/** The typed number with Indian grouping, its decimals as typed ("125000.5" → "1,25,000.5"). */
private fun grouped(typed: String): String {
    val whole = typed.substringBefore('.').toLongOrNull() ?: 0L
    val g = java.text.NumberFormat.getNumberInstance(Locale("en", "IN")).format(whole)
    return if (typed.contains('.')) g + "." + typed.substringAfter('.') else g
}

private val faceColors = listOf(
    Color(0xFFFDE2C4) to Color(0xFF7A3E00), Color(0xFFD7E9FF) to Color(0xFF0A4A94), Color(0xFFE3F5EC) to Color(0xFF0A6B4B),
    Color(0xFFEEEAF7) to Color(0xFF4B3A8C), Color(0xFFFCE4EC) to Color(0xFF9B1B4B), Color(0xFFFFF3C4) to Color(0xFF6B5300),
)
private val Ink = Color(0xFF1A1530)
private val Label = Color(0xFF1C1C1E)
private val Body = Color(0xFF4A4A4F)
private val Muted = Color(0xFF5C5C61)
private val Grey = Color(0xFFAEAEB2)
private val Line = Color(0xFFE5E5EA)
private val Tile = Color(0xFFF2F2F7)
private val Green = Color(0xFF0E7F59)
private val Blue = Color(0xFF0A5DB8)
private val Red = Color(0xFFC4281C)
private val Orange = Color(0xFFA35200)
private val OrangeWash = Color(0xFFFFF0DD)
private val Purple = Color(0xFF4B3A8C)
private val PurpleWash = Color(0xFFEEEAF7)
