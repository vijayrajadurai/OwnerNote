package com.shopai.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.inventory.StockVoiceIntent
import com.shopai.app.data.inventory.StockVoiceResolution
import com.shopai.app.data.inventory.StockVoiceResolver
import com.shopai.app.data.model.InventoryProduct
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.repository.StockChangeResult
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.ConfirmStockChangeDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.components.VoiceListeningOrb
import com.shopai.app.ui.components.VoiceOrbState
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.util.DeviceSpeechRecognizer
import com.shopai.app.util.formatQty
import kotlinx.coroutines.launch

/**
 * Speak or type a stock movement in one sentence ("Cement 50 bags
 * vanginen") instead of Inventory -> product -> Stock In -> quantity ->
 * Save. Parsing is 100% on-device (StockVoiceParser/StockVoiceResolver);
 * the database is only ever touched via the existing
 * InventoryRepository.stockIn/stockOut, and only after the owner confirms
 * the card built from real current-stock numbers.
 */
@Composable
fun VoiceStockEntryScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenInventory: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    var partialText by remember { mutableStateOf<String?>(null) }
    var products by remember { mutableStateOf<List<InventoryProduct>>(emptyList()) }
    var resolution by remember { mutableStateOf<StockVoiceResolution?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var orbState by remember { mutableStateOf(VoiceOrbState.Idle) }
    var audioLevel by remember { mutableFloatStateOf(0f) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val speechRecognizer = remember { DeviceSpeechRecognizer(context) }

    val errorSpeechUnavailable = stringResource(R.string.error_speech_unavailable)
    val errorMicPermission = stringResource(R.string.error_mic_permission)
    val errorVoiceNoSpeech = stringResource(R.string.error_voice_no_speech)
    val errorLoad = stringResource(R.string.inv_error_load)
    val errorSave = stringResource(R.string.inv_error_save)
    val statusIdle = stringResource(R.string.voice_status_idle)
    val statusListening = stringResource(R.string.voice_status_listening)
    val statusProcessing = stringResource(R.string.voice_status_processing)

    DisposableEffect(Unit) {
        onDispose { speechRecognizer.stopListening() }
    }

    fun reloadProducts() {
        scope.launch {
            runCatching { products = container.inventoryRepository.listProducts() }
                .onFailure { container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg }) }
        }
    }

    LaunchedEffect(Unit) { reloadProducts() }

    fun resolveInput() {
        val text = inputText.trim()
        if (text.isBlank()) return
        error = null
        successMessage = null
        resolution = StockVoiceResolver.resolve(text, products)
        orbState = VoiceOrbState.Idle
    }

    fun startListening() {
        if (!speechRecognizer.isAvailable()) {
            error = errorSpeechUnavailable
            return
        }
        error = null
        successMessage = null
        partialText = null
        orbState = VoiceOrbState.Listening
        audioLevel = 0f
        speechRecognizer.startListening(
            languageTag = "ta-IN",
            onReady = { orbState = VoiceOrbState.Listening },
            onRmsChanged = { audioLevel = ((it + 2f) / 12f).coerceIn(0f, 1f) },
            onPartialResult = { partialText = it },
            onResult = { spoken ->
                inputText = spoken
                partialText = spoken
                orbState = VoiceOrbState.Processing
                resolveInput()
            },
            onError = { code ->
                orbState = VoiceOrbState.Idle
                audioLevel = 0f
                if (code != SpeechRecognizer.ERROR_CLIENT) {
                    error = when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> errorVoiceNoSpeech
                        else -> errorSpeechUnavailable
                    }
                }
            },
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startListening() else error = errorMicPermission }

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

    fun confirmStockChange(ready: StockVoiceResolution.ReadyToConfirm) {
        scope.launch {
            saving = true
            error = null
            runCatching {
                if (ready.intent == StockVoiceIntent.STOCK_IN) {
                    container.inventoryRepository.stockIn(ready.product.id, ready.quantity, REASON_VOICE_ENTRY)
                    null
                } else {
                    val result = container.inventoryRepository.stockOut(ready.product.id, ready.quantity, REASON_VOICE_ENTRY)
                    (result as? StockChangeResult.InsufficientStock)?.available
                }
            }.onSuccess { insufficientAvailable ->
                if (insufficientAvailable != null) {
                    resolution = StockVoiceResolution.InsufficientStock(ready.product, ready.quantity, insufficientAvailable)
                } else {
                    val isStockIn = ready.intent == StockVoiceIntent.STOCK_IN
                    val newStock = if (isStockIn) ready.product.currentStock + ready.quantity else ready.product.currentStock - ready.quantity
                    successMessage = if (isStockIn) {
                        context.getString(R.string.stock_voice_success_add, ready.product.name, formatQty(ready.quantity), ready.unit, formatQty(newStock))
                    } else {
                        context.getString(R.string.stock_voice_success_remove, ready.product.name, formatQty(ready.quantity), ready.unit, formatQty(newStock))
                    }
                    val ttsText = if (isStockIn) {
                        context.getString(R.string.stock_voice_tts_add, ready.product.name, "${formatQty(ready.quantity)} ${ready.unit}")
                    } else {
                        context.getString(R.string.stock_voice_tts_remove, ready.product.name, "${formatQty(ready.quantity)} ${ready.unit}")
                    }
                    container.naturalTtsSpeaker.speakNatural(ttsText, "ta-IN")
                    resolution = null
                    inputText = ""
                    reloadProducts()
                }
            }.onFailure {
                container.presentApiError(it, errorSave, { msg -> error = msg }, { msg -> alertError = msg })
            }
            saving = false
        }
    }

    val statusText = when (orbState) {
        VoiceOrbState.Idle -> statusIdle
        VoiceOrbState.Listening -> statusListening
        VoiceOrbState.Processing -> statusProcessing
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    (resolution as? StockVoiceResolution.ReadyToConfirm)?.let { ready ->
        ConfirmStockChangeDialog(
            intent = ready.intent,
            product = ready.product,
            quantity = ready.quantity,
            unit = ready.unit,
            onConfirm = { confirmStockChange(ready) },
            onDismiss = { resolution = null },
        )
    }

    DetailScaffold(title = stringResource(R.string.stock_voice_entry_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.stock_voice_entry_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            VoiceListeningOrb(
                state = orbState,
                audioLevel = audioLevel,
                statusText = statusText,
                partialText = if (orbState == VoiceOrbState.Listening) partialText else null,
                onOrbClick = { onOrbClick() },
            )

            ShopTextField(
                stringResource(R.string.stock_voice_entry_input_label),
                inputText,
                { inputText = it },
                placeholder = stringResource(R.string.stock_voice_entry_placeholder),
                singleLine = false,
                modifier = Modifier.padding(top = 12.dp),
            )

            PrimaryButton(
                label = stringResource(R.string.stock_voice_entry_send),
                loading = saving,
                enabled = inputText.isNotBlank() && orbState != VoiceOrbState.Listening,
                modifier = Modifier.padding(top = 8.dp),
                onClick = { resolveInput() },
            )

            if (error != null) {
                Text(error!!, color = Danger, modifier = Modifier.padding(top = 8.dp))
            }

            successMessage?.let { message ->
                ShopCard(modifier = Modifier.padding(top = 12.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = Success)
                }
            }

            when (val current = resolution) {
                is StockVoiceResolution.NotUnderstood -> ResolutionMessageCard(
                    message = stringResource(R.string.stock_voice_not_understood),
                )
                is StockVoiceResolution.ProductNotFound -> ResolutionMessageCard(
                    message = stringResource(R.string.stock_voice_product_not_found),
                    actionLabel = stringResource(R.string.stock_voice_add_product),
                    onAction = onOpenInventory,
                )
                is StockVoiceResolution.UnitUnknown -> ResolutionMessageCard(
                    message = stringResource(R.string.stock_voice_ask_unit),
                )
                is StockVoiceResolution.InsufficientStock -> ResolutionMessageCard(
                    message = stringResource(
                        R.string.stock_voice_insufficient_stock,
                        formatQty(current.availableQuantity),
                        current.product.unit,
                        formatQty(current.requestedQuantity),
                    ),
                    actionLabel = stringResource(R.string.stock_voice_change_quantity),
                    onAction = { resolution = null },
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun ResolutionMessageCard(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ShopCard(modifier = Modifier.padding(top = 12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = ShopAiThemeColors.onSurface)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            PrimaryButton(label = actionLabel, onClick = onAction)
        }
    }
}

private const val REASON_VOICE_ENTRY = "VOICE_ENTRY"
