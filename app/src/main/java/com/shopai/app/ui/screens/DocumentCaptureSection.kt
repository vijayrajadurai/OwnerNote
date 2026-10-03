package com.shopai.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import com.shopai.app.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.local.room.CapturedDocumentEntity
import com.shopai.app.data.local.room.NoteTransactionEntity
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.repository.CapturedDocuments
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.components.TransactionSaveType
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.BillNotesFormatter
import com.shopai.app.util.BillTextParser
import com.shopai.app.util.DeviceTextRecognizer
import com.shopai.app.util.DocumentKind
import com.shopai.app.util.ScanHandoff
import com.shopai.app.util.formatLocalDateForDisplay
import com.shopai.app.util.formatRupees
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class CaptureMode { SHOP_BILL, HANDWRITTEN_NOTE, MANUAL_BILL }

/** Where the camera writes the full-size photo (shared via the app's FileProvider). */
private fun capturePhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "capture").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, "document.jpg"))
}

// ---- Labels shown in the dropdowns ----

private fun shownDate(iso: String?): String =
    iso?.let { runCatching { formatLocalDateForDisplay(LocalDate.parse(it)) }.getOrNull() } ?: "—"

private fun shownAmount(amount: String?): String =
    amount?.toBigDecimalOrNull()?.let { BillNotesFormatter.rupees(it) } ?: "—"

@Composable
private fun billLabel(bill: CapturedDocumentEntity): String =
    stringResource(R.string.capture_bill_label, bill.name, shownDate(bill.date), shownAmount(bill.amount))

@Composable
private fun noteLabel(note: NoteTransactionEntity): String =
    stringResource(R.string.capture_note_label, note.personName, note.amount?.toBigDecimalOrNull()?.let { formatRupees(it) } ?: "—", shownDate(note.date))

/**
 * Camera capture for shop bills, the list of saved bills with the
 * handwritten-note transactions linked to each, and the way into the
 * Handwritten Notes module.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentCaptureSection(
    container: AppContainer,
    onOpenHandwrittenNotes: () -> Unit,
    onScanNoteForBill: (billId: Long) -> Unit,
    onOpenNotePerson: (name: String) -> Unit,
    onHandwrittenDetected: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { DeviceTextRecognizer(context) }
    DisposableEffect(Unit) { onDispose { recognizer.release() } }

    val documents by container.capturedDocumentRepository.observe()
        .collectAsState(initial = CapturedDocuments(emptyList(), emptyList()))
    val noteRows by container.handwrittenNotesRepository.observeTransactions().collectAsState(initial = emptyList())
    val notesByBill = remember(noteRows) { noteRows.filter { it.linkedBillId != null }.groupBy { it.linkedBillId!! } }
    // Saveable: Android may recreate the screen while the camera app is open.
    var mode by rememberSaveable { mutableStateOf(CaptureMode.SHOP_BILL) }
    val bill = remember { BillEntryState() }
    var selectedBillId by remember { mutableStateOf<Long?>(null) }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var billSaving by remember { mutableStateOf(false) }

    val errorOcrEmpty = stringResource(R.string.error_ocr_empty)
    val errorOcrFailed = stringResource(R.string.error_ocr_failed)
    val errorCameraPermission = stringResource(R.string.error_camera_permission)
    val errorSave = stringResource(R.string.error_voice_save)
    val errorLocalSave = stringResource(R.string.capture_error_local_save)
    val billDescriptionFormat = stringResource(R.string.bill_entry_description)
    val billDetailsFormat = stringResource(R.string.bill_saved_details)
    val billPaymentFailedFormat = stringResource(R.string.bill_payment_failed)
    val paidOnBillNote = stringResource(R.string.bill_paid_on_bill_note)
    val billSavedMessage = stringResource(R.string.capture_bill_saved)

    // ---- Reading a photo ----

    suspend fun fillBillFromText(text: String, ocrConfidence: Int = -1) {
        val local = BillTextParser.parse(text)
        // Debug builds only: the OCR text and what was read from it, for checking real bills.
        if (com.shopai.app.BuildConfig.DEBUG) {
            android.util.Log.d("BillOcr", "OCR text:\n$text")
            android.util.Log.d("BillOcr", "parsed: shop=${local.merchantName} total=${local.total} customer=${local.customerName} date=${local.date} paid=${local.paid}")
        }
        // The server's reading only fills a name/total the phone missed.
        val server = if (local.merchantName == null || !local.totalFromLabel) {
            withTimeoutOrNull(8_000L) {
                runCatching { container.voiceRepository.parseOcrText(text) }.getOrNull()
            }
        } else {
            null
        }
        // Who issued the bill decides Credit (I sold) or Debit (I bought), from my own details.
        val me = container.books.session()?.let { s -> s.dao.business(s.ctx.businessId) }?.let { com.shopai.app.util.MyBusiness(it.name, it.gstin, it.phone) }
            ?: runCatching { container.businessRepository.getMyBusiness() }.getOrNull()?.let { com.shopai.app.util.MyBusiness(it.businessName, null, it.phone) }
            ?: com.shopai.app.util.MyBusiness(null, null, null)
        bill.applyScan(local, server?.partyName, server?.amount, com.shopai.app.util.BillDirection.decide(text, local, me), ocrConfidence)
        // Kai: what he read on the bill, and whether the total needs a check.
        container.kaiBrain.announce(
            com.shopai.app.brain.KaiResponder.billRead(
                shop = bill.shopName.trim().ifBlank { null },
                total = bill.parsedTotal?.toDouble(),
                totalSure = local.totalFromLabel,
                lang = com.shopai.app.brain.KaiLanguage.forAppLocale(),
            ),
        )
    }

    val printedHandoffMessage = stringResource(R.string.scan_handoff_printed)

    /**
     * Reads a bill photo. A photo the printed-bill reader cannot make sense of
     * (handwriting) goes to the handwriting reader instead — unless it was
     * just sent here from there.
     */
    fun runOcr(uri: Uri, fromHandoff: Boolean = false) {
        scope.launch {
            processing = true
            error = null
            info = null
            runCatching {
                val result = recognizer.recognizeFromUri(uri)
                val handwritten = !fromHandoff && DocumentKind.looksHandwritten(
                    result.text,
                    result.meanConfidence,
                    foundLabelledTotal = BillTextParser.parse(result.text).totalFromLabel,
                )
                val copy = if (handwritten) DocumentKind.handoffCopy(context, uri) else null
                when {
                    copy != null -> {
                        ScanHandoff.sendToHandwritten(copy)
                        onHandwrittenDetected()
                    }
                    !result.success || result.text.isBlank() -> error = errorOcrEmpty
                    else -> {
                        fillBillFromText(result.text, result.meanConfidence)
                        if (fromHandoff) info = printedHandoffMessage
                    }
                }
            }.onFailure { error = errorOcrFailed }
            processing = false
        }
    }

    // A printed bill photographed under "Handwritten note" arrives here.
    val handedOver by ScanHandoff.pendingShopBill.collectAsState()
    LaunchedEffect(handedOver) {
        val uri = ScanHandoff.takeShopBill() ?: return@LaunchedEffect
        mode = CaptureMode.SHOP_BILL
        bill.reset()
        runOcr(uri, fromHandoff = true)
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runOcr(uri)
    }
    // Full-size photo into a fixed cache file (a preview thumbnail is too
    // small to read). Fixed path, so it survives the screen being recreated.
    val photoUri = remember { capturePhotoUri(context) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) runOcr(photoUri)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(photoUri) else error = errorCameraPermission
    }

    fun openCamera() {
        error = null
        info = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraLauncher.launch(photoUri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Creates the ledger entry for the full bill total (debit for a supplier
     * bill, credit for a customer bill), then records what was already paid
     * so only the balance stays pending.
     */
    fun saveBill() {
        val total = bill.parsedTotal ?: return
        val paid = bill.parsedPaid ?: return
        val date = bill.billDate ?: return
        if (!bill.canSave || billSaving) return
        scope.launch {
            billSaving = true
            error = null
            val party = bill.partyName
            val shop = bill.shopName.trim()
            val type = bill.type
            val description = billDescriptionFormat.format(shop.ifBlank { party }, formatLocalDateForDisplay(date))
            val due = bill.dueDate?.let { com.shopai.app.util.localDateToIsoInstant(it) }
            val transactionId = runCatching {
                when (type) {
                    TransactionSaveType.DEBIT -> container.transactionRepository.createDebit(
                        CreateDebitInput(supplierName = party, amount = total.toDouble(), description = description, dueDate = due),
                        source = com.shopai.app.books.model.TxnSource.OCR,
                    ).id
                    TransactionSaveType.CREDIT -> container.transactionRepository.createCredit(
                        CreateCreditInput(customerName = party, amount = total.toDouble(), description = description, dueDate = due),
                        source = com.shopai.app.books.model.TxnSource.OCR,
                    ).id
                }
            }.getOrElse {
                // Nothing saved; the form keeps everything so the owner can retry.
                container.presentApiError(it, errorSave, { msg -> error = msg }, { msg -> alertError = msg })
                billSaving = false
                return@launch
            }

            // Record the paid part. Fully paid → mark paid; partly → add a payment.
            val paymentRecorded = when {
                paid.signum() == 0 -> true
                paid.compareTo(total) == 0 -> runCatching {
                    if (type == TransactionSaveType.DEBIT) container.transactionRepository.markDebitPaid(transactionId)
                    else container.transactionRepository.markCreditPaid(transactionId)
                }.isSuccess
                else -> runCatching {
                    if (type == TransactionSaveType.DEBIT) container.transactionRepository.addDebitPayment(transactionId, paid.toDouble(), paidOnBillNote)
                    else container.transactionRepository.addCreditPayment(transactionId, paid.toDouble(), paidOnBillNote)
                }.isSuccess
            }

            // Keep the bill on this phone too, for the saved list and linking notes.
            val savedId = runCatching {
                container.capturedDocumentRepository.save(
                    CapturedDocumentEntity(
                        kind = CapturedDocumentEntity.KIND_BILL,
                        name = shop.ifBlank { party },
                        amount = total.toPlainString(),
                        date = date.toString(),
                        ledgerTransactionId = transactionId,
                        ledgerType = type.name,
                        details = billDetailsFormat.format(
                            party,
                            BillNotesFormatter.rupees(paid),
                            BillNotesFormatter.rupees(total - paid),
                        ),
                    ),
                )
            }.getOrNull()

            bill.reset()
            selectedBillId = savedId
            when {
                !paymentRecorded -> error = billPaymentFailedFormat.format(BillNotesFormatter.rupees(paid))
                savedId == null -> error = errorLocalSave
                else -> info = billSavedMessage
            }
            // Kai: what is still pending on the saved bill (from the saved values).
            if (paymentRecorded) {
                val pending = (total - paid).toDouble()
                val lang = com.shopai.app.brain.KaiLanguage.forAppLocale()
                val brain = container.kaiBrain
                brain.announce(
                    if (pending > 0.005) {
                        brain.saved(
                            name = party,
                            amount = pending,
                            direction = if (type == TransactionSaveType.DEBIT) com.shopai.app.brain.Direction.PAYABLE else com.shopai.app.brain.Direction.RECEIVABLE,
                            dueDate = bill.dueDate,
                            lang = lang,
                        )
                    } else {
                        com.shopai.app.brain.KaiResponder.photoSaved(1, lang)
                    },
                )
            }
            billSaving = false
        }
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    Text(
        stringResource(R.string.ocr_scan_bill),
        style = MaterialTheme.typography.labelLarge,
        color = ShopAiThemeColors.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    SavedEntriesDropdown(
        documents = documents,
        notesByBill = notesByBill,
        onSelectBill = {
            selectedBillId = it.id
            info = null
        },
        onSelectNote = { onOpenNotePerson(it.personName) },
    )

    documents.bills.firstOrNull { it.bill.id == selectedBillId }?.let { group ->
        // Live status from the books: what has been paid / returned against it since, and the due date.
        val status by androidx.compose.runtime.produceState<com.shopai.app.util.PaymentStatus?>(null, group.bill.ledgerTransactionId, documents) {
            value = liveBillStatus(container, group.bill.ledgerTransactionId)
        }
        BillDetailsCard(
            bill = group.bill,
            status = status,
            notes = notesByBill[group.bill.id].orEmpty(),
            onAddNote = { onScanNoteForBill(group.bill.id) },
            onOpenNote = { onOpenNotePerson(it.personName) },
            onSaveEdits = { name, date ->
                scope.launch {
                    runCatching {
                        container.capturedDocumentRepository.save(group.bill.copy(name = name, date = date?.toString()))
                    }.onFailure { error = errorLocalSave }
                }
            },
            onClose = { selectedBillId = null },
        )
    }

    CaptureModeDropdown(
        mode = mode,
        onModeChange = { newMode ->
            error = null
            info = null
            when (newMode) {
                // Picking "Shop Bill" goes straight to the camera.
                CaptureMode.SHOP_BILL -> {
                    mode = newMode
                    bill.reset()
                    openCamera()
                }
                CaptureMode.MANUAL_BILL -> {
                    mode = newMode
                    if (!bill.visible) bill.startManual()
                }
                // Handwritten notes have their own module (multi-page, one row per person).
                CaptureMode.HANDWRITTEN_NOTE -> onOpenHandwrittenNotes()
            }
        },
    )

    if (mode == CaptureMode.SHOP_BILL) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { openCamera() }, enabled = !processing, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text(stringResource(R.string.ocr_camera), modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !processing,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Photo, contentDescription = null)
                Text(stringResource(R.string.ocr_gallery), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    if (processing) {
        Text(stringResource(R.string.ocr_status_processing), color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
    error?.let { Text(it, color = Danger, modifier = Modifier.padding(top = 8.dp)) }
    info?.let { Text(it, color = ShopAiThemeColors.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }

    if (bill.visible) {
        BillEntrySection(state = bill, saving = billSaving, onSave = { saveBill() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureModeDropdown(mode: CaptureMode, onModeChange: (CaptureMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun labelRes(m: CaptureMode) = when (m) {
        CaptureMode.SHOP_BILL -> R.string.capture_mode_shop_bill
        CaptureMode.HANDWRITTEN_NOTE -> R.string.capture_mode_handwritten_note
        CaptureMode.MANUAL_BILL -> R.string.bill_mode_manual
    }
    fun icon(m: CaptureMode): ImageVector = when (m) {
        CaptureMode.SHOP_BILL -> Icons.Default.DocumentScanner
        CaptureMode.HANDWRITTEN_NOTE -> Icons.Default.EditNote
        CaptureMode.MANUAL_BILL -> Icons.Default.Edit
    }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = stringResource(labelRes(mode)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.capture_mode_label)) },
            leadingIcon = { Icon(icon(mode), contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CaptureMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes(option))) },
                    leadingIcon = { Icon(icon(option), contentDescription = null) },
                    onClick = {
                        open = false
                        onModeChange(option)
                    },
                )
            }
        }
    }
}

/** Every saved bill with the handwritten-note transactions linked to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedEntriesDropdown(
    documents: CapturedDocuments,
    notesByBill: Map<Long, List<NoteTransactionEntity>>,
    onSelectBill: (CapturedDocumentEntity) -> Unit,
    onSelectNote: (NoteTransactionEntity) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val count = documents.bills.size + documents.bills.sumOf { notesByBill[it.bill.id].orEmpty().size }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = stringResource(R.string.capture_saved_count, count),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.capture_saved_label)) },
            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (documents.bills.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.capture_saved_empty)) }, onClick = {}, enabled = false)
            }
            documents.bills.forEach { group ->
                DropdownMenuItem(
                    text = { Text(billLabel(group.bill), fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null) },
                    onClick = {
                        open = false
                        onSelectBill(group.bill)
                    },
                )
                notesByBill[group.bill.id].orEmpty().forEach { n ->
                    DropdownMenuItem(
                        text = { Text("↳ " + noteLabel(n)) },
                        modifier = Modifier.padding(start = 24.dp),
                        onClick = {
                            open = false
                            onSelectNote(n)
                        },
                    )
                }
            }
        }
    }
}

/** A saved bill: its details, linked note transactions, and "add note". */
@Composable
private fun BillDetailsCard(
    bill: CapturedDocumentEntity,
    status: com.shopai.app.util.PaymentStatus?,
    notes: List<NoteTransactionEntity>,
    onAddNote: () -> Unit,
    onOpenNote: (NoteTransactionEntity) -> Unit,
    onSaveEdits: (name: String, date: LocalDate?) -> Unit,
    onClose: () -> Unit,
) {
    var editing by remember(bill.id) { mutableStateOf(false) }
    var editName by remember(bill.id) { mutableStateOf(bill.name) }
    var editDate by remember(bill.id) {
        mutableStateOf(bill.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }
    val billAmount = bill.amount.toBigDecimalOrNull()

    ShopCard(modifier = Modifier.padding(top = 12.dp)) {
        Text(billLabel(bill), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.primary)
        Text(
            stringResource(if (bill.ledgerType == TransactionSaveType.CREDIT.name) R.string.bill_type_customer else R.string.bill_type_supplier),
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // Paid / Partially paid / Upcoming / Overdue, as the books stand now.
        status?.let { com.shopai.app.ui.components.PaymentStatusChip(it) } ?: bill.details?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = ShopAiThemeColors.onSurface, modifier = Modifier.padding(top = 8.dp))
        }

        if (editing) {
            ShopTextField(stringResource(R.string.bill_entry_name), editName, { editName = it })
            FutureDatePickerField(
                label = stringResource(R.string.bill_date),
                selectedDate = editDate,
                onDateSelected = { editDate = it },
                allowEmpty = false,
                pastOnly = true,
            )
            // The amount is already in the ledger, which cannot be edited from the app.
            Text(
                stringResource(R.string.capture_bill_amount_locked, shownAmount(bill.amount)),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    label = stringResource(R.string.save),
                    enabled = editName.isNotBlank() && editDate != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSaveEdits(editName.trim(), editDate)
                        editing = false
                    },
                )
                OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }

        Text(
            stringResource(R.string.capture_linked_notes, notes.size),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )
        notes.forEach { n ->
            Text(
                "↳ " + noteLabel(n),
                color = ShopAiThemeColors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenNote(n) }
                    .padding(vertical = 6.dp),
            )
        }
        if (notes.isNotEmpty() && billAmount != null) {
            val notesTotal = notes.mapNotNull { it.amount?.toBigDecimalOrNull() }.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
            Text(
                stringResource(R.string.capture_notes_vs_bill, BillNotesFormatter.rupees(notesTotal), BillNotesFormatter.rupees(billAmount)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (notesTotal > billAmount) Danger else ShopAiThemeColors.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onAddNote) { Text(stringResource(R.string.capture_add_note_to_bill), fontWeight = FontWeight.SemiBold) }
            if (!editing) TextButton(onClick = { editing = true }) { Text(stringResource(R.string.capture_edit)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.capture_close)) }
        }
    }
}

/** A saved bill's status from the books (null before the one-time import or for old backend entries). */
private suspend fun liveBillStatus(container: AppContainer, txnId: String?): com.shopai.app.util.PaymentStatus? {
    val s = container.books.session() ?: return null
    val txn = txnId?.let { s.dao.txn(it) }?.takeIf { it.status == "CONFIRMED" } ?: return null
    val outstanding = s.ledger.outstanding(txn.id)
    val total = java.math.BigDecimal.valueOf(txn.totalPaise, 2)
    return com.shopai.app.util.PaymentStatus.of(total, total - java.math.BigDecimal.valueOf(outstanding, 2), txn.dueDate?.let { LocalDate.ofEpochDay(it.toLong()) })
}
