package com.shopai.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.ParsedTransaction
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.SaveTransactionConfirmDialog
import com.shopai.app.ui.components.ScreenContainer
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.components.TransactionSaveType
import com.shopai.app.ui.components.VoiceListeningOrb
import com.shopai.app.ui.components.VoiceOrbState
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.DeviceSpeechRecognizer
import com.shopai.app.util.DeviceTextRecognizer
import com.shopai.app.util.localDateToIsoInstant
import com.shopai.app.util.parseIsoToLocalDate
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun VoiceEntryScreen(
    container: AppContainer,
    onDone: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    var partialText by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<ParsedTransaction?>(null) }
    var queryAnswer by remember { mutableStateOf<String?>(null) }
    var partyName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var ocrProcessing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var orbState by remember { mutableStateOf(VoiceOrbState.Idle) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val speechRecognizer = remember { DeviceSpeechRecognizer(context) }
    val textRecognizer = remember { DeviceTextRecognizer(context) }
    val errorSpeechUnavailable = stringResource(R.string.error_speech_unavailable)
    val errorOcrEmpty = stringResource(R.string.error_ocr_empty)
    val errorOcrFailed = stringResource(R.string.error_ocr_failed)
    val errorCameraPermission = stringResource(R.string.error_camera_permission)
    val ocrStatusProcessing = stringResource(R.string.ocr_status_processing)
    val errorMicPermission = stringResource(R.string.error_mic_permission)
    val errorVoiceParse = stringResource(R.string.error_voice_parse)
    val errorVoiceAsk = stringResource(R.string.error_voice_ask)
    val errorVoiceSave = stringResource(R.string.error_voice_save)
    val errorVoiceNoSpeech = stringResource(R.string.error_voice_no_speech)
    val statusIdle = stringResource(R.string.voice_status_idle)
    val statusListening = stringResource(R.string.voice_status_listening)
    val statusProcessing = stringResource(R.string.voice_status_processing)

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.stopListening()
            textRecognizer.release()
        }
    }

    fun applyParsed(p: ParsedTransaction) {
        parsed = p
        queryAnswer = null
        partyName = p.partyName ?: ""
        amount = p.amount?.toString() ?: ""
        description = p.description ?: ""
        val today = LocalDate.now()
        dueDate = parseIsoToLocalDate(p.dueDate)?.takeIf { !it.isBefore(today) }
    }

    fun parseInput(fromOcr: Boolean = false) {
        val text = inputText.trim()
        if (text.isBlank()) {
            orbState = VoiceOrbState.Idle
            return
        }
        scope.launch {
            parsing = true
            if (!fromOcr) orbState = VoiceOrbState.Processing
            error = null
            queryAnswer = null
            runCatching {
                val result = if (fromOcr) {
                    container.voiceRepository.parseOcrText(text)
                } else {
                    container.voiceRepository.parseVoiceText(text)
                }
                if (result.intent == "ASK_QUERY") {
                    parsed = result
                    runCatching {
                        val answer = container.insightsRepository.askMyBusiness(text)
                        queryAnswer = answer.answer
                        container.naturalTtsSpeaker.speakNatural(answer.answer, "ta-IN")
                    }.onFailure {
                        container.presentApiError(it, errorVoiceAsk, { msg -> error = msg }, { msg -> alertError = msg })
                    }
                } else {
                    applyParsed(result)
                }
            }.onFailure {
                container.presentApiError(it, errorVoiceParse, { msg -> error = msg }, { msg -> alertError = msg })
            }
            parsing = false
            if (!fromOcr) orbState = VoiceOrbState.Idle
        }
    }

    fun runOcrOnBitmap(bitmap: Bitmap) {
        scope.launch {
            ocrProcessing = true
            error = null
            runCatching {
                val result = textRecognizer.recognizeFromBitmap(bitmap)
                if (!result.success) {
                    error = errorOcrEmpty
                } else {
                    inputText = result.text
                    parseInput(fromOcr = true)
                }
            }.onFailure {
                error = errorOcrFailed
            }
            ocrProcessing = false
        }
    }

    fun runOcrOnUri(uri: Uri) {
        scope.launch {
            ocrProcessing = true
            error = null
            runCatching {
                val result = textRecognizer.recognizeFromUri(uri)
                if (!result.success) {
                    error = errorOcrEmpty
                } else {
                    inputText = result.text
                    parseInput(fromOcr = true)
                }
            }.onFailure {
                error = errorOcrFailed
            }
            ocrProcessing = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) runOcrOnUri(uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap != null) runOcrOnBitmap(bitmap)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) cameraLauncher.launch(null) else error = errorCameraPermission
    }

    fun normalizeAudioLevel(rmsdB: Float): Float {
        return ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    fun startListening() {
        if (!speechRecognizer.isAvailable()) {
            error = errorSpeechUnavailable
            return
        }
        error = null
        partialText = null
        orbState = VoiceOrbState.Listening
        audioLevel = 0f
        speechRecognizer.startListening(
            languageTag = "ta-IN",
            onReady = { orbState = VoiceOrbState.Listening },
            onRmsChanged = { audioLevel = normalizeAudioLevel(it) },
            onPartialResult = { partialText = it },
            onResult = { spoken ->
                inputText = spoken
                partialText = spoken
                parseInput()
            },
            onError = { code ->
                orbState = VoiceOrbState.Idle
                audioLevel = 0f
                if (code != SpeechRecognizer.ERROR_CLIENT) {
                    error = when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        -> errorVoiceNoSpeech
                        else -> errorSpeechUnavailable
                    }
                }
            },
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening() else error = errorMicPermission
    }

    fun onOrbClick() {
        when (orbState) {
            VoiceOrbState.Listening -> {
                speechRecognizer.stopListening()
                orbState = VoiceOrbState.Idle
                audioLevel = 0f
            }
            VoiceOrbState.Processing -> Unit
            VoiceOrbState.Idle -> {
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> startListening()
                    else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    val statusText = when (orbState) {
        VoiceOrbState.Idle -> statusIdle
        VoiceOrbState.Listening -> statusListening
        VoiceOrbState.Processing -> statusProcessing
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    ScreenContainer {
        Text(
            stringResource(R.string.voice_title),
            style = MaterialTheme.typography.headlineMedium,
            color = ShopAiThemeColors.primary,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            stringResource(R.string.voice_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
        )

        VoiceListeningOrb(
            state = orbState,
            audioLevel = audioLevel,
            statusText = if (ocrProcessing) ocrStatusProcessing else statusText,
            partialText = if (orbState == VoiceOrbState.Listening) partialText else null,
            onOrbClick = { onOrbClick() },
        )

        Text(
            stringResource(R.string.ocr_scan_bill),
            style = MaterialTheme.typography.labelLarge,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    when {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED -> cameraLauncher.launch(null)
                        else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = !ocrProcessing && orbState != VoiceOrbState.Listening,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text(stringResource(R.string.ocr_camera), modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !ocrProcessing && orbState != VoiceOrbState.Listening,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Photo, contentDescription = null)
                Text(stringResource(R.string.ocr_gallery), modifier = Modifier.padding(start = 6.dp))
            }
        }

        ShopTextField(
            stringResource(R.string.voice_sentence),
            inputText,
            { inputText = it },
            placeholder = stringResource(R.string.voice_sentence_placeholder),
            singleLine = false,
        )

        PrimaryButton(
            label = stringResource(R.string.voice_parse),
            loading = parsing || ocrProcessing,
            enabled = inputText.isNotBlank() && orbState != VoiceOrbState.Listening && !ocrProcessing,
            modifier = Modifier.padding(top = 8.dp),
            onClick = { parseInput() },
        )

        queryAnswer?.let { answer ->
            ShopCard(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.voice_answer),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = ShopAiThemeColors.primary,
                )
                Text(
                    answer,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ShopAiThemeColors.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        parsed?.let { p ->
            if (p.intent != "ASK_QUERY") {
                ShopCard(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.voice_parsed),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = ShopAiThemeColors.primary,
                    )
                    Text(
                        stringResource(R.string.voice_intent, p.intent),
                        color = ShopAiThemeColors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        stringResource(R.string.voice_confidence, (p.confidence * 100).toInt()),
                        color = ShopAiThemeColors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ShopTextField(stringResource(R.string.name_label), partyName, { partyName = it })
                    ShopTextField(
                        stringResource(R.string.amount_label),
                        amount,
                        { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    )
                    ShopTextField(stringResource(R.string.note_label), description, { description = it })
                    FutureDatePickerField(
                        label = stringResource(R.string.due_date_label),
                        selectedDate = dueDate,
                        onDateSelected = { dueDate = it },
                        placeholder = stringResource(R.string.due_date_placeholder),
                        allowEmpty = true,
                    )
                }

                PrimaryButton(
                    label = stringResource(R.string.save),
                    loading = saving,
                    enabled = partyName.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0,
                    onClick = { showConfirmDialog = true },
                )
            }
        }

        if (error != null) {
            Text(error!!, color = Danger, modifier = Modifier.padding(top = 8.dp))
        }
    }

    val parsedTransaction = parsed
    if (showConfirmDialog && parsedTransaction != null && parsedTransaction.intent != "ASK_QUERY") {
        val saveType = if (parsedTransaction.intent == "CREATE_DEBIT") {
            TransactionSaveType.DEBIT
        } else {
            TransactionSaveType.CREDIT
        }
        SaveTransactionConfirmDialog(
            type = saveType,
            partyName = partyName.trim(),
            amount = amount.toDouble(),
            onConfirm = {
                showConfirmDialog = false
                val p = parsedTransaction
                scope.launch {
                    saving = true
                    error = null
                    runCatching {
                        val amt = amount.toDouble()
                        val due = dueDate?.let { localDateToIsoInstant(it) }
                        when (p.intent) {
                            "CREATE_DEBIT" -> container.transactionRepository.createDebit(
                                CreateDebitInput(partyName.trim(), amt, description.ifBlank { null }, due),
                            )
                            else -> container.transactionRepository.createCredit(
                                CreateCreditInput(partyName.trim(), amt, description.ifBlank { null }, due),
                            )
                        }
                        onDone()
                    }.onFailure {
                        container.presentApiError(it, errorVoiceSave, { msg -> error = msg }, { msg -> alertError = msg })
                    }
                    saving = false
                }
            },
            onDismiss = { showConfirmDialog = false },
        )
    }
}
