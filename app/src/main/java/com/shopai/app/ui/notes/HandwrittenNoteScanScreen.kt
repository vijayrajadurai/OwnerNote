package com.shopai.app.ui.notes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.shopai.app.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.local.room.HandwrittenNoteEntity
import com.shopai.app.data.local.room.NoteTransactionEntity
import com.shopai.app.data.repository.LedgerLine
import com.shopai.app.data.repository.NoteLedger
import com.shopai.app.data.repository.NotePageToSave
import com.shopai.app.data.repository.personKey
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Warning as WarningColor
import com.shopai.app.util.CropFractions
import com.shopai.app.util.HandwritingTextRecognizer
import com.shopai.app.util.ExtractedTransaction
import com.shopai.app.util.HandwritingImageProcessor
import com.shopai.app.util.HandwrittenTransactionParser
import com.shopai.app.util.OcrBox
import com.shopai.app.util.OcrLine
import com.shopai.app.util.ReviewIssue
import com.shopai.app.util.TxnDirection
import com.shopai.app.util.formatLocalDateForDisplay
import com.shopai.app.util.formatRupees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ------------------------------------------------------------ draft state

/** A page picked for this note, before saving. */
@Stable
class DraftPage(
    val key: Long,
    val originalPath: String,
    val hash: String,
    /** Set when this exact image was saved before. */
    val savedBefore: HandwrittenNoteEntity?,
) {
    var rotation by mutableIntStateOf(0)
    var crop by mutableStateOf(CropFractions.FULL)
    var thumbnail by mutableStateOf<Bitmap?>(null)
    var processedPath: String? = null
    var lines: List<OcrLine> = emptyList()
}

/** One transaction row under review. */
@Stable
class DraftRow(
    val key: Long,
    val pageKey: Long?,
    /** What OCR/parsing produced; null for rows added by hand. */
    val extracted: ExtractedTransaction?,
    form: TxnForm,
    issues: Set<ReviewIssue>,
    selected: Boolean,
    /** For rows typed from an unreadable handwritten line: where that line is. */
    val sourceBox: OcrBox? = null,
    val sourceLine: String? = null,
) {
    var form by mutableStateOf(form)
    var issues by mutableStateOf(issues)
    var selected by mutableStateOf(selected)
    var correctedByUser by mutableStateOf(extracted == null)

    val needsReview: Boolean get() = issues.any { it.blocksSave }

    fun toUi(syncLabel: String? = null) = TxnRowUi(
        personName = form.personName.ifBlank { null },
        amount = form.parsedAmount,
        date = form.date,
        direction = form.direction,
        description = form.description.ifBlank { null },
        nameConfidence = extracted?.nameConfidence?.takeUnless { correctedByUser },
        amountConfidence = extracted?.amountConfidence?.takeUnless { correctedByUser },
        dateConfidence = extracted?.dateConfidence?.takeUnless { correctedByUser },
        issues = issues,
        correctedByUser = correctedByUser,
        hasSource = extracted != null || sourceBox != null,
        syncLabel = syncLabel,
    )

    /** The owner saved the form: their values win, uncertainty is resolved. */
    fun applyEdit(newForm: TxnForm) {
        if (newForm != form) correctedByUser = true
        form = newForm
        issues = recomputeIssues(newForm, confirmed = true)
    }

    /** Partly corrected (still incomplete): keep the values, keep it flagged. */
    fun updateDraft(newForm: TxnForm) {
        if (newForm != form) correctedByUser = true
        form = newForm
        issues = recomputeIssues(newForm, confirmed = false)
    }

    /** "Mark as checked": the owner looked at the handwriting and agrees. */
    fun markChecked() {
        correctedByUser = true
        issues = recomputeIssues(form, confirmed = true)
    }

    private fun recomputeIssues(f: TxnForm, confirmed: Boolean): Set<ReviewIssue> = buildSet {
        if (f.personName.isBlank()) add(ReviewIssue.NAME_MISSING)
        if (f.parsedAmount == null) add(ReviewIssue.AMOUNT_MISSING)
        if (f.direction == TxnDirection.UNKNOWN) add(ReviewIssue.TYPE_UNKNOWN)
        if (f.date == null) add(ReviewIssue.DATE_MISSING)
        if (!confirmed) addAll(issues.filter { it == ReviewIssue.NAME_UNCLEAR || it == ReviewIssue.AMOUNT_UNCLEAR || it == ReviewIssue.DATE_UNCLEAR })
    }

    fun toEntity(linkedBillId: Long?): NoteTransactionEntity {
        val e = extracted
        return NoteTransactionEntity(
            sourceNoteId = null,
            personName = form.personName.trim(),
            amount = form.parsedAmount!!.toPlainString(),
            date = form.date?.toString(),
            transactionType = when (form.direction) {
                TxnDirection.CREDIT -> NoteTransactionEntity.TYPE_CREDIT
                TxnDirection.DEBIT -> NoteTransactionEntity.TYPE_DEBIT
                TxnDirection.UNKNOWN -> NoteTransactionEntity.TYPE_UNKNOWN
            },
            description = form.description.trim().ifBlank { null },
            notes = form.notes.trim().ifBlank { null },
            originalOcrText = e?.originalLine ?: sourceLine,
            originalPersonName = e?.personName,
            originalAmountText = e?.amountText,
            originalDate = e?.dateText,
            originalType = e?.direction?.name,
            correctedByUser = correctedByUser,
            nameConfidence = e?.nameConfidence,
            amountConfidence = e?.amountConfidence,
            dateConfidence = e?.dateConfidence,
            typeConfidence = e?.directionConfidence,
            reviewStatus = if (e != null && !e.needsReview && !correctedByUser) NoteTransactionEntity.STATUS_VERIFIED else NoteTransactionEntity.STATUS_USER_VERIFIED,
            boxLeft = (e?.box ?: sourceBox)?.left,
            boxTop = (e?.box ?: sourceBox)?.top,
            boxRight = (e?.box ?: sourceBox)?.right,
            boxBottom = (e?.box ?: sourceBox)?.bottom,
            linkedBillId = linkedBillId,
        )
    }
}

private enum class Stage { PICK, PROCESSING, FAILED, QUICK_FIX, REVIEW }

/** The steps shown while a note is processed, in order. */
private val processingSteps = listOf(
    R.string.hw_step_uploading,
    R.string.hw_step_processing_image,
    R.string.hw_step_reading,
    R.string.hw_step_extracting,
    R.string.hw_step_names,
    R.string.hw_step_amounts,
    R.string.hw_step_dates,
    R.string.hw_step_classifying,
    R.string.hw_step_preparing,
    R.string.hw_step_completed,
)

private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "capture").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, "note-${System.currentTimeMillis()}.jpg"))
}

// ----------------------------------------------------------------- screen

/**
 * Handwritten Notes → Scan / Add Handwritten Note:
 * pick pages → prepare (rotate/crop) → extract → review/edit → save.
 */
@Composable
fun HandwrittenNoteScanScreen(
    container: AppContainer,
    linkedBillId: Long?,
    onBack: () -> Unit,
    onSaved: (savedCount: Int, syncFailed: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = container.handwrittenNotesRepository
    // Handwriting OCR (ML Kit). Printed bills keep using Tesseract elsewhere.
    val recognizer = remember { HandwritingTextRecognizer() }

    val pages = remember { mutableStateListOf<DraftPage>() }
    val rows = remember { mutableStateListOf<DraftRow>() }
    val unparsed = remember { mutableStateListOf<Pair<Long, OcrLine>>() }
    // Lines OCR could not fully read, for the quick-correction step.
    val quickFix = remember { mutableStateListOf<QuickFixItem>() }
    // The date written on the note (heading), used for lines without their own.
    var noteDate by remember { mutableStateOf<LocalDate?>(null) }
    var stage by remember { mutableStateOf(Stage.PICK) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var stepDetail by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var nextKey by remember { mutableStateOf(1L) }
    fun key(): Long = nextKey.also { nextKey++ }

    // Dialogs
    var cropPage by remember { mutableStateOf<DraftPage?>(null) }
    var previewPage by remember { mutableStateOf<DraftPage?>(null) }
    var editRow by remember { mutableStateOf<DraftRow?>(null) }
    // The unreadable/unparsed line being added by hand: (page key, line).
    var addFromLine by remember { mutableStateOf<Pair<Long, OcrLine>?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteRow by remember { mutableStateOf<DraftRow?>(null) }
    var sourceRow by remember { mutableStateOf<DraftRow?>(null) }
    var showSummary by remember { mutableStateOf(false) }
    var blockedCount by remember { mutableIntStateOf(0) }
    var syncToLedger by rememberSaveable { mutableStateOf(true) }
    var replaceKey by rememberSaveable { mutableStateOf<Long?>(null) }
    var showCameraGuide by remember { mutableStateOf(false) }
    // Pages whose photo looked too poor to read; asked about one at a time.
    val poorPhotos = remember { mutableStateListOf<Long>() }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    val msgDuplicateInDraft = stringResource(R.string.hw_msg_already_added)
    val msgOpenFailed = stringResource(R.string.hw_msg_open_failed)

    // Leaving without saving: remove the copied images.
    DisposableEffect(Unit) {
        onDispose {
            recognizer.close()
            if (!saved) {
                val files = pages.flatMap { listOf(it.originalPath, it.processedPath) }
                container.appScope.launch { repository.deleteFiles(files) }
            }
        }
    }

    // ---- adding pages ----

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = true
            message = null
            for (uri in uris) {
                runCatching {
                    val (path, hash) = repository.storeOriginal(uri)
                    if (pages.any { it.hash == hash }) {
                        repository.deleteFiles(listOf(path))
                        message = msgDuplicateInDraft
                        return@runCatching
                    }
                    val page = DraftPage(key(), path, hash, repository.findSavedPage(hash))
                    val fileUri = Uri.fromFile(File(path))
                    page.thumbnail = withContext(Dispatchers.IO) {
                        HandwritingImageProcessor.load(context, fileUri, maxSide = 900)
                    }
                    // Lightweight quality check; only genuinely poor photos are flagged.
                    page.thumbnail?.let { thumb ->
                        val quality = withContext(Dispatchers.Default) {
                            val (w, h) = HandwritingImageProcessor.imageSize(context, fileUri)
                            HandwritingImageProcessor.quality(thumb, w, h)
                        }
                        if (!quality.acceptable) poorPhotos += page.key
                    }
                    val replaceAt = replaceKey?.let { k -> pages.indexOfFirst { it.key == k } } ?: -1
                    if (replaceAt >= 0) {
                        repository.deleteFiles(listOf(pages[replaceAt].originalPath, pages[replaceAt].processedPath))
                        pages[replaceAt] = page
                        replaceKey = null
                    } else {
                        pages += page
                    }
                }.onFailure { message = msgOpenFailed }
            }
            busy = false
        }
    }

    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addImages(listOf(uri)) else replaceKey = null
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> addImages(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCameraUri?.let(Uri::parse)
        if (taken && uri != null) addImages(listOf(uri)) else replaceKey = null
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = newCaptureUri(context)
            pendingCameraUri = uri.toString()
            camera.launch(uri)
        } else {
            message = context.getString(R.string.error_camera_permission)
        }
    }

    // Shows the framing guide first; the camera opens from its button.
    fun openCamera() {
        showCameraGuide = true
    }

    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val uri = newCaptureUri(context)
            pendingCameraUri = uri.toString()
            camera.launch(uri)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun pickOne() = singlePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun pickMany() = multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // ---- extraction ----

    fun extract() {
        if (pages.isEmpty()) return
        scope.launch {
            stage = Stage.PROCESSING
            stepIndex = 1
            val pageCount = pages.size
            var failedPages = 0
            for ((i, page) in pages.withIndex()) {
                stepIndex = 1
                val pageNumber: Int = i + 1
                stepDetail = context.getString(R.string.hw_step_page, pageNumber, pageCount)
                val result = withContext(Dispatchers.Default) {
                    runCatching { HandwritingImageProcessor.process(context, Uri.fromFile(File(page.originalPath)), page.rotation, page.crop) }.getOrNull()
                }
                if (result == null) {
                    page.lines = emptyList()
                    failedPages++
                    continue
                }
                repository.deleteFiles(listOf(page.processedPath))
                page.processedPath = repository.storeProcessed(result.page)
                stepIndex = 2
                page.lines = runCatching { recognizer.readPage(result.page, result.writingRegion) }.getOrDefault(emptyList())
            }
            for (s in 3..8) { stepIndex = s; stepDetail = "" }
            val extraction = HandwrittenTransactionParser.parse(pages.map { it.lines })
            val today = LocalDate.now()
            noteDate = HandwrittenTransactionParser.noteDate(pages.map { it.lines }, today)
            rows.clear()
            unparsed.clear()
            quickFix.clear()
            extraction.transactions.forEach { t ->
                val page = pages[t.pageIndex]
                rows += DraftRow(
                    key = key(),
                    pageKey = page.key,
                    extracted = t,
                    form = TxnForm(
                        personName = t.personName.orEmpty(),
                        amount = t.amount?.toPlainString().orEmpty(),
                        // No date on the note: today, editable (flagged "date not written").
                        date = t.date ?: today,
                        direction = t.direction,
                        description = t.description.orEmpty(),
                    ),
                    issues = t.issues,
                    // Rows from a page saved before start unticked (avoid duplicates).
                    selected = page.savedBefore == null,
                )
            }
            extraction.unparsedLines.forEach { (pageIndex, line) -> unparsed += pages[pageIndex].key to line }

            // Only real transaction candidates (a person and an amount were read)
            // that still miss something go to the quick check, fully pre-filled.
            // Unreadable lines, the date and background marks get no card; they
            // stay in the collapsed "not understood" list on the review screen.
            rows.filter { it.needsReview }.forEach { row ->
                quickFix += QuickFixItem(
                    key(), row.pageKey, row.extracted?.box, row.extracted?.originalLine, null,
                    ReviewIssue.AMOUNT_UNCLEAR in row.issues, row, null, row.form,
                )
            }
            stepIndex = 9
            // Kai: how many entries are ready and how many need the owner's check.
            if (failedPages < pages.size) {
                container.kaiBrain.announce(
                    com.shopai.app.brain.KaiResponder.noteRead(
                        ready = rows.count { !it.needsReview },
                        needCheck = rows.count { it.needsReview },
                        notRead = unparsed.size,
                        lang = com.shopai.app.brain.KaiLanguage.forAppLocale(),
                    ),
                )
            }
            stage = when {
                failedPages == pages.size -> Stage.FAILED
                quickFix.isNotEmpty() -> Stage.QUICK_FIX
                else -> Stage.REVIEW
            }
        }
    }

    /** Quick check done: completed lines become rows; the rest stay flagged, never dropped. */
    fun finishQuickFix() {
        for (item in quickFix) {
            if (item.skipped) continue
            val row = item.row
            when {
                row != null && item.form.isComplete -> row.applyEdit(item.form)
                row != null -> if (item.edited) row.updateDraft(item.form)
                item.form.isComplete || item.edited -> {
                    rows += DraftRow(
                        key = key(),
                        pageKey = item.pageKey,
                        extracted = null,
                        form = item.form,
                        issues = emptySet(),
                        selected = true,
                        sourceBox = item.box,
                        sourceLine = item.ocrText,
                    ).also { if (item.form.isComplete) it.applyEdit(item.form) else it.updateDraft(item.form) }
                    item.source?.let { s -> unparsed.removeAll { it === s } }
                }
            }
        }
        quickFix.clear()
        stage = Stage.REVIEW
    }

    // ---- saving ----

    fun save() {
        showSummary = false
        scope.launch {
            busy = true
            message = null
            val chosen = rows.filter { it.selected }
            val outcome = runCatching {
                repository.saveAll(
                    pages = pages.map { page ->
                        NotePageToSave(
                            originalImagePath = page.originalPath,
                            processedImagePath = page.processedPath,
                            imageHash = page.hash,
                            extractedText = page.lines.joinToString("\n") { it.text },
                            transactions = chosen.filter { it.pageKey == page.key }.map { it.toEntity(linkedBillId) },
                        )
                    },
                    manual = chosen.filter { it.pageKey == null }.map { it.toEntity(linkedBillId) },
                    syncToLedger = syncToLedger,
                )
            }
            busy = false
            outcome.onSuccess {
                saved = true
                onSaved(it.saved, it.syncFailed)
            }.onFailure {
                message = context.getString(R.string.capture_error_local_save)
            }
        }
    }

    fun confirmSave() {
        val chosen = rows.filter { it.selected }
        blockedCount = chosen.count { it.needsReview }
        showSummary = true
    }

    // ---- layout ----

    DetailScaffold(title = stringResource(R.string.hw_scan_title), onBack = onBack) { contentModifier ->
        when (stage) {
            Stage.PICK -> PickStage(
                modifier = contentModifier,
                pages = pages,
                busy = busy,
                message = message,
                onCamera = { openCamera() },
                onUpload = { pickOne() },
                onUploadMany = { pickMany() },
                onManual = {
                    stage = Stage.REVIEW
                    showAdd = true
                },
                onRotate = { it.rotation = (it.rotation + 90) % 360 },
                onCrop = { cropPage = it },
                onPreview = { previewPage = it },
                onReplace = { page ->
                    replaceKey = page.key
                    pickOne()
                },
                onRetake = { page ->
                    replaceKey = page.key
                    openCamera()
                },
                onRemove = { page ->
                    pages.remove(page)
                    scope.launch { repository.deleteFiles(listOf(page.originalPath, page.processedPath)) }
                },
                onExtract = { extract() },
            )
            Stage.PROCESSING -> ProcessingStage(contentModifier, stepIndex, stepDetail)
            Stage.FAILED -> FailedStage(
                modifier = contentModifier,
                onRetry = { extract() },
                onRetake = {
                    stage = Stage.PICK
                    pages.firstOrNull()?.let { replaceKey = it.key }
                    openCamera()
                },
                onUploadAnother = {
                    stage = Stage.PICK
                    pickOne()
                },
                onManual = {
                    stage = Stage.REVIEW
                    showAdd = true
                },
            )
            Stage.QUICK_FIX -> QuickFixStage(
                modifier = contentModifier,
                items = quickFix,
                pagePath = { pageKey -> pages.firstOrNull { it.key == pageKey }?.processedPath },
                onAddLine = {
                    quickFix += QuickFixItem(key(), null, null, null, null, false, null, null, TxnForm(date = noteDate ?: LocalDate.now()))
                },
                onDone = { finishQuickFix() },
            )
            Stage.REVIEW -> ReviewStage(
                modifier = contentModifier,
                pages = pages,
                rows = rows,
                unparsed = unparsed,
                busy = busy,
                message = message,
                onEdit = { editRow = it },
                onDelete = { deleteRow = it },
                onViewSource = { sourceRow = it },
                onAdd = { addFromLine = null; showAdd = true },
                onAddFromLine = { addFromLine = it; showAdd = true },
                onBackToPages = { stage = Stage.PICK },
                onConfirm = { confirmSave() },
            )
        }
    }

    // ---- dialogs ----

    cropPage?.let { page ->
        CropDialog(
            page = page,
            onDone = { crop ->
                page.crop = crop
                cropPage = null
            },
            onDismiss = { cropPage = null },
        )
    }
    previewPage?.let { page -> PreviewDialog(page) { previewPage = null } }

    if (showCameraGuide) {
        CameraGuideDialog(
            onCapture = {
                showCameraGuide = false
                launchCamera()
            },
            onDismiss = {
                showCameraGuide = false
                replaceKey = null
            },
        )
    }

    // One poor-quality photo at a time: Retake or Use Anyway.
    poorPhotos.firstOrNull()?.let { key ->
        val page = pages.firstOrNull { it.key == key }
        if (page == null) {
            poorPhotos.remove(key)
        } else {
            AlertDialog(
                onDismissRequest = { poorPhotos.remove(key) },
                title = { Text(stringResource(R.string.hw_photo_poor_title)) },
                text = { Text(stringResource(R.string.hw_photo_poor_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        poorPhotos.remove(key)
                        replaceKey = page.key
                        openCamera()
                    }) { Text(stringResource(R.string.hw_retake)) }
                },
                dismissButton = {
                    TextButton(onClick = { poorPhotos.remove(key) }) { Text(stringResource(R.string.hw_use_anyway)) }
                },
            )
        }
    }

    editRow?.let { row ->
        TransactionEditDialog(
            title = stringResource(R.string.hw_edit_title),
            initial = row.form,
            originalLine = row.extracted?.originalLine,
            ocrRead = row.extracted?.let { e ->
                stringResource(
                    R.string.hw_ocr_read,
                    e.personName ?: "—",
                    e.amountText ?: "—",
                    e.dateText ?: "—",
                    directionLabel(e.direction),
                )
            },
            onSave = {
                row.applyEdit(it)
                editRow = null
            },
            onDismiss = { editRow = null },
        )
    }

    if (showAdd) {
        TransactionEditDialog(
            title = stringResource(R.string.hw_add_title),
            // From a not-understood line: only what OCR actually read is filled in.
            initial = remember(addFromLine) {
                addFromLine?.let { prefillForm(it.second, noteDate).first } ?: TxnForm(date = noteDate ?: LocalDate.now())
            },
            header = addFromLine?.let { (pageKey, line) -> { LineImage(pages.firstOrNull { it.key == pageKey }?.processedPath, line.box) } },
            originalLine = addFromLine?.second?.text?.ifBlank { null },
            ocrRead = null,
            onSave = { form ->
                val source = addFromLine
                rows += DraftRow(
                    key = key(),
                    pageKey = source?.first,
                    extracted = null,
                    form = form,
                    issues = emptySet(),
                    selected = true,
                    sourceBox = source?.second?.box,
                    sourceLine = source?.second?.text?.ifBlank { null },
                ).also { it.applyEdit(form) }
                source?.let { s -> unparsed.removeAll { it === s } }
                showAdd = false
                addFromLine = null
            },
            onDismiss = {
                showAdd = false
                addFromLine = null
            },
        )
    }

    deleteRow?.let { row ->
        DeleteTransactionDialog(
            onConfirm = {
                rows.remove(row)
                deleteRow = null
            },
            onDismiss = { deleteRow = null },
            notice = stringResource(R.string.hw_delete_keeps_image),
        )
    }

    sourceRow?.let { row ->
        val page = pages.firstOrNull { it.key == row.pageKey }
        ViewSourceDialog(
            imagePath = page?.processedPath ?: page?.originalPath,
            box = row.extracted?.box ?: row.sourceBox,
            originalLine = row.extracted?.originalLine ?: row.sourceLine,
            row = row.toUi(),
            onDismiss = { sourceRow = null },
        )
    }

    if (showSummary) {
        SaveSummaryDialog(
            rows = rows.filter { it.selected },
            blockedCount = blockedCount,
            syncToLedger = syncToLedger,
            onSyncChange = { syncToLedger = it },
            onBack = { showSummary = false },
            onSave = { save() },
        )
    }
}

// ------------------------------------------------------------------ stages

@Composable
private fun PickStage(
    modifier: Modifier,
    pages: List<DraftPage>,
    busy: Boolean,
    message: String?,
    onCamera: () -> Unit,
    onUpload: () -> Unit,
    onUploadMany: () -> Unit,
    onManual: () -> Unit,
    onRotate: (DraftPage) -> Unit,
    onCrop: (DraftPage) -> Unit,
    onPreview: (DraftPage) -> Unit,
    onReplace: (DraftPage) -> Unit,
    onRetake: (DraftPage) -> Unit,
    onRemove: (DraftPage) -> Unit,
    onExtract: () -> Unit,
) {
    LazyColumn(modifier = modifier.padding(horizontal = 20.dp)) {
        item {
            Text(stringResource(R.string.hw_pick_caption), color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCamera, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Text(stringResource(R.string.hw_take_photo), modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onUpload, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Text(stringResource(R.string.hw_upload_image), modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onUploadMany, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.hw_upload_multiple))
                }
                OutlinedButton(onClick = onManual, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.hw_enter_manually))
                }
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp).size(24.dp))
            message?.let { Text(it, color = Danger, modifier = Modifier.padding(top = 8.dp)) }
            if (pages.isNotEmpty()) {
                Text(
                    stringResource(R.string.hw_pages_count, pages.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
        }
        items(pages, key = { it.key }) { page ->
            ShopCard(modifier = Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF1F1F1))
                            .clickable { onPreview(page) },
                        contentAlignment = Alignment.Center,
                    ) {
                        page.thumbnail?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.hw_preview),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().rotate(page.rotation.toFloat()),
                            )
                        } ?: CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(R.string.hw_page_n, pages.indexOf(page) + 1), fontWeight = FontWeight.SemiBold)
                        if (page.crop != CropFractions.FULL) Text(stringResource(R.string.hw_cropped), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                        page.savedBefore?.let {
                            Text(
                                stringResource(R.string.hw_saved_before, formatLocalDateForDisplay(Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()), it.totalTransactions),
                                style = MaterialTheme.typography.bodySmall,
                                color = WarningColor,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    IconButton(onClick = { onRotate(page) }) { Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = stringResource(R.string.hw_rotate)) }
                    IconButton(onClick = { onCrop(page) }) { Icon(Icons.Default.Crop, contentDescription = stringResource(R.string.hw_crop)) }
                    IconButton(onClick = { onPreview(page) }) { Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.hw_preview)) }
                    IconButton(onClick = { onRetake(page) }) { Icon(Icons.Default.PhotoCamera, contentDescription = stringResource(R.string.hw_retake)) }
                    IconButton(onClick = { onReplace(page) }) { Icon(Icons.Default.SwapHoriz, contentDescription = stringResource(R.string.hw_replace)) }
                    IconButton(onClick = { onRemove(page) }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.capture_delete), tint = Danger) }
                }
            }
        }
        item {
            if (pages.isNotEmpty()) {
                PrimaryButton(
                    label = stringResource(R.string.hw_extract_data),
                    enabled = !busy,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                    onClick = onExtract,
                )
            }
        }
    }
}

@Composable
private fun ProcessingStage(modifier: Modifier, stepIndex: Int, detail: String) {
    Column(modifier = modifier.padding(24.dp)) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        processingSteps.forEachIndexed { index, res ->
            val done = index < stepIndex
            val current = index == stepIndex
            Text(
                text = (if (done) "✓ " else if (current) "• " else "   ") + stringResource(res) + if (current && detail.isNotBlank()) " ($detail)" else "",
                color = when {
                    done -> LedgerCredit
                    current -> ShopAiThemeColors.primary
                    else -> ShopAiThemeColors.onSurfaceVariant
                },
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun FailedStage(modifier: Modifier, onRetry: () -> Unit, onRetake: () -> Unit, onUploadAnother: () -> Unit, onManual: () -> Unit) {
    Column(modifier = modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.hw_failed), style = MaterialTheme.typography.titleMedium, color = Danger, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.hw_failed_tips), color = ShopAiThemeColors.onSurfaceVariant)
        PrimaryButton(label = stringResource(R.string.hw_retry), onClick = onRetry)
        OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hw_retake_image)) }
        OutlinedButton(onClick = onUploadAnother, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hw_upload_another)) }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hw_enter_manually)) }
    }
}

@Composable
private fun ReviewStage(
    modifier: Modifier,
    pages: List<DraftPage>,
    rows: List<DraftRow>,
    unparsed: List<Pair<Long, OcrLine>>,
    busy: Boolean,
    message: String?,
    onEdit: (DraftRow) -> Unit,
    onDelete: (DraftRow) -> Unit,
    onViewSource: (DraftRow) -> Unit,
    onAdd: () -> Unit,
    onAddFromLine: (Pair<Long, OcrLine>) -> Unit,
    onBackToPages: () -> Unit,
    onConfirm: () -> Unit,
) {
    val needsReview = rows.count { it.needsReview }
    var showUnparsed by remember { mutableStateOf(false) }
    val repeatedPeople = rows.filter { it.form.personName.isNotBlank() }
        .groupBy { personKey(it.form.personName) }
        .filter { it.value.size > 1 }
    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        item {
            Text(stringResource(R.string.hw_review_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.hw_review_counts, rows.size, needsReview, rows.count { it.selected }),
                color = if (needsReview > 0) WarningColor else ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (pages.any { it.savedBefore != null }) {
                Text(stringResource(R.string.hw_duplicate_banner), color = WarningColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (rows.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val allSelected = rows.all { it.selected }
                    Checkbox(checked = allSelected, onCheckedChange = { value -> rows.forEach { it.selected = value } })
                    Text(stringResource(R.string.hw_select_all))
                }
            } else {
                Text(stringResource(R.string.hw_no_rows), color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        items(rows, key = { it.key }) { row ->
            TransactionCard(
                row = row.toUi(),
                selected = row.selected,
                onSelectedChange = { row.selected = it },
                onEdit = { onEdit(row) },
                onDelete = { onDelete(row) },
                onViewSource = { onViewSource(row) },
                onMarkChecked = { row.markChecked() },
            )
        }
        if (repeatedPeople.isNotEmpty()) {
            item {
                ShopCard(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.hw_person_totals), fontWeight = FontWeight.SemiBold)
                    repeatedPeople.values.forEach { group ->
                        val credit = group.filter { it.form.direction == TxnDirection.CREDIT }.mapNotNull { it.form.parsedAmount }.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                        val debit = group.filter { it.form.direction == TxnDirection.DEBIT }.mapNotNull { it.form.parsedAmount }.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                        SummaryLine(
                            stringResource(R.string.hw_person_rows, group.first().form.personName.trim(), group.size),
                            formatRupees(credit - debit),
                            if (credit >= debit) LedgerCredit else LedgerDebit,
                        )
                    }
                }
            }
        }
        if (unparsed.isNotEmpty()) {
            item {
                // Collapsed: these are not transactions, only kept so nothing is lost.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.hw_unparsed_title, unparsed.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(R.string.hw_unparsed_caption), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                    }
                    TextButton(onClick = { showUnparsed = !showUnparsed }) {
                        Text(stringResource(if (showUnparsed) R.string.hw_unparsed_hide else R.string.hw_unparsed_show))
                    }
                }
            }
        }
        if (showUnparsed) {
            items(unparsed, key = { "${it.first}-${it.second.text}-${it.second.box}" }) { entry ->
                val (pageKey, line) = entry
                ShopCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    // The handwritten line itself, so it can be typed in even if OCR could not read it.
                    LineImage(pages.firstOrNull { it.key == pageKey }?.processedPath, line.box)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (line.text.isBlank()) stringResource(R.string.hw_line_not_readable) else "“${line.text}”",
                            color = if (line.text.isBlank()) WarningColor else ShopAiThemeColors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onAddFromLine(entry) }) { Text(stringResource(R.string.hw_add_as_transaction)) }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(stringResource(R.string.hw_add_transaction))
            }
            if (pages.isNotEmpty()) {
                TextButton(onClick = onBackToPages, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hw_back_to_pages)) }
            }
            message?.let { Text(it, color = Danger, modifier = Modifier.padding(top = 8.dp)) }
            PrimaryButton(
                label = stringResource(R.string.hw_confirm_save_all),
                loading = busy,
                enabled = rows.any { it.selected },
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                onClick = onConfirm,
            )
        }
    }
}

// ----------------------------------------------------------------- dialogs

@Composable
private fun SaveSummaryDialog(
    rows: List<DraftRow>,
    blockedCount: Int,
    syncToLedger: Boolean,
    onSyncChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val summary = NoteLedger.summarize(
        rows.map {
            LedgerLine(
                type = when (it.form.direction) {
                    TxnDirection.CREDIT -> NoteTransactionEntity.TYPE_CREDIT
                    TxnDirection.DEBIT -> NoteTransactionEntity.TYPE_DEBIT
                    TxnDirection.UNKNOWN -> NoteTransactionEntity.TYPE_UNKNOWN
                },
                amount = it.form.parsedAmount,
                date = it.form.date,
                needsReview = it.needsReview,
            )
        },
    )
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.hw_summary_title)) },
        text = {
            Column {
                SummaryLine(stringResource(R.string.hw_summary_total), summary.count.toString())
                SummaryLine(stringResource(R.string.hw_summary_credit_count), summary.creditCount.toString(), LedgerCredit)
                SummaryLine(stringResource(R.string.hw_summary_debit_count), summary.debitCount.toString(), LedgerDebit)
                SummaryLine(stringResource(R.string.hw_summary_total_credit), formatRupees(summary.totalCredit), LedgerCredit)
                SummaryLine(stringResource(R.string.hw_summary_total_debit), formatRupees(summary.totalDebit), LedgerDebit)
                SummaryLine(stringResource(R.string.hw_summary_needs_review), summary.needsReview.toString(), if (summary.needsReview > 0) WarningColor else ShopAiThemeColors.onSurface)
                if (blockedCount > 0) {
                    Text(stringResource(R.string.hw_summary_blocked, blockedCount), color = Danger, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Checkbox(checked = syncToLedger, onCheckedChange = onSyncChange)
                        Text(stringResource(R.string.hw_sync_to_ledger), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = blockedCount == 0) { Text(stringResource(R.string.hw_save_all)) }
        },
        dismissButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.hw_go_back_edit)) } },
    )
}

/** Framing guide shown before the camera opens (the system camera can't show an overlay). */
@Composable
private fun CameraGuideDialog(onCapture: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hw_guide_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Phone screen with the paper fitting inside the frame.
                Canvas(modifier = Modifier.size(width = 150.dp, height = 190.dp)) {
                    drawRect(Color(0xFF455A64), style = Stroke(width = 6f))
                    val inset = 24f
                    drawRect(Color(0xFFFFFFFF), Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2))
                    drawRect(Color(0xFF1E6B4E), Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), style = Stroke(width = 5f))
                    for (i in 1..5) {
                        val y = inset + i * (size.height - inset * 2) / 6f
                        drawLine(Color(0xFF90A4AE), Offset(inset + 14f, y), Offset(size.width - inset - 14f, y), strokeWidth = 3f)
                    }
                }
                Text(stringResource(R.string.hw_guide_fit), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.hw_guide_parallel), color = ShopAiThemeColors.onSurfaceVariant)
                Text(stringResource(R.string.hw_guide_shadows), color = ShopAiThemeColors.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onCapture) { Text(stringResource(R.string.hw_guide_capture)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Picture of one handwritten line (from the processed page and the line's box). */
@Composable
fun LineImage(pagePath: String?, box: OcrBox?, height: Dp = 72.dp) {
    val page = rememberFileBitmap(pagePath, maxSide = 2000)
    val strip = remember(page, box) {
        if (page == null || box == null) null else {
            val left = (box.left * page.width).toInt().coerceIn(0, page.width - 1)
            val top = (box.top * page.height).toInt().coerceIn(0, page.height - 1)
            val right = (box.right * page.width).toInt().coerceIn(left + 1, page.width)
            val bottom = (box.bottom * page.height).toInt().coerceIn(top + 1, page.height)
            Bitmap.createBitmap(page, left, top, right - left, bottom - top)
        }
    }
    strip?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = stringResource(R.string.hw_original_line),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White),
        )
    }
}

@Composable
private fun PreviewDialog(page: DraftPage, onDismiss: () -> Unit) {
    val bitmap = remember(page.thumbnail, page.rotation, page.crop) {
        page.thumbnail?.let { HandwritingImageProcessor.crop(HandwritingImageProcessor.rotate(it, page.rotation), page.crop) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hw_preview)) },
        text = {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_close)) } },
    )
}

/** Drag the corners to crop the page (applied to the rotated image). */
@Composable
private fun CropDialog(page: DraftPage, onDone: (CropFractions) -> Unit, onDismiss: () -> Unit) {
    val bitmap = remember(page.thumbnail, page.rotation) { page.thumbnail?.let { HandwritingImageProcessor.rotate(it, page.rotation) } }
    var crop by remember { mutableStateOf(page.crop) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hw_crop)) },
        text = {
            Column {
                Text(stringResource(R.string.hw_crop_hint), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                if (bitmap != null) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(420.dp).padding(top = 8.dp)) {
                        val density = LocalDensity.current
                        val maxW = with(density) { maxWidth.toPx() }
                        val maxH = with(density) { maxHeight.toPx() }
                        val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
                        val drawnW = bitmap.width * scale
                        val drawnH = bitmap.height * scale
                        val ox = (maxW - drawnW) / 2f
                        val oy = (maxH - drawnH) / 2f
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(bitmap) {
                                    var corner = 0
                                    detectDragGestures(
                                        onDragStart = { p ->
                                            val fx = ((p.x - ox) / drawnW).coerceIn(0f, 1f)
                                            val fy = ((p.y - oy) / drawnH).coerceIn(0f, 1f)
                                            val c = crop
                                            val corners = listOf(c.left to c.top, c.right to c.top, c.left to c.bottom, c.right to c.bottom)
                                            corner = corners.indices.minBy { i -> (corners[i].first - fx) * (corners[i].first - fx) + (corners[i].second - fy) * (corners[i].second - fy) }
                                        },
                                        onDrag = { change, _ ->
                                            val fx = ((change.position.x - ox) / drawnW).coerceIn(0f, 1f)
                                            val fy = ((change.position.y - oy) / drawnH).coerceIn(0f, 1f)
                                            val c = crop
                                            val min = 0.08f
                                            crop = when (corner) {
                                                0 -> c.copy(left = fx.coerceAtMost(c.right - min), top = fy.coerceAtMost(c.bottom - min))
                                                1 -> c.copy(right = fx.coerceAtLeast(c.left + min), top = fy.coerceAtMost(c.bottom - min))
                                                2 -> c.copy(left = fx.coerceAtMost(c.right - min), bottom = fy.coerceAtLeast(c.top + min))
                                                else -> c.copy(right = fx.coerceAtLeast(c.left + min), bottom = fy.coerceAtLeast(c.top + min))
                                            }
                                        },
                                    )
                                },
                        ) {
                            val l = ox + crop.left * drawnW
                            val t = oy + crop.top * drawnH
                            val r = ox + crop.right * drawnW
                            val b = oy + crop.bottom * drawnH
                            val shade = Color(0x88000000)
                            drawRect(shade, Offset(ox, oy), Size(drawnW, t - oy))
                            drawRect(shade, Offset(ox, b), Size(drawnW, oy + drawnH - b))
                            drawRect(shade, Offset(ox, t), Size(l - ox, b - t))
                            drawRect(shade, Offset(r, t), Size(ox + drawnW - r, b - t))
                            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 3f))
                            listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { drawCircle(Color.White, 18f, it) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(crop) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { crop = CropFractions.FULL }) { Text(stringResource(R.string.hw_crop_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
