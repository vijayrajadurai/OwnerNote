package com.shopai.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.ParsedTransaction
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.SaveTransactionConfirmDialog
import com.shopai.app.ui.components.ScreenContainer
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.components.TransactionSaveType
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
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
    var parsed by remember { mutableStateOf<ParsedTransaction?>(null) }
    var partyName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val speechPrompt = stringResource(R.string.voice_speech_prompt)
    val errorSpeechUnavailable = stringResource(R.string.error_speech_unavailable)
    val errorMicPermission = stringResource(R.string.error_mic_permission)
    val errorVoiceParse = stringResource(R.string.error_voice_parse)
    val errorVoiceSave = stringResource(R.string.error_voice_save)

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                inputText = spoken
            }
        }
    }

    fun launchSpeechIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, speechPrompt)
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { error = errorSpeechUnavailable }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchSpeechIntent() else error = errorMicPermission
    }

    fun startSpeech() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                launchSpeechIntent()
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun applyParsed(p: ParsedTransaction) {
        parsed = p
        partyName = p.partyName ?: ""
        amount = p.amount?.toString() ?: ""
        description = p.description ?: ""
        val today = LocalDate.now()
        dueDate = parseIsoToLocalDate(p.dueDate)?.takeIf { !it.isBefore(today) }
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
            modifier = Modifier.padding(bottom = 16.dp, top = 4.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { startSpeech() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_speak))
            }
            PrimaryButton(
                label = stringResource(R.string.voice_parse),
                loading = parsing,
                enabled = inputText.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        parsing = true
                        error = null
                        runCatching {
                            applyParsed(container.voiceRepository.parseVoiceText(inputText.trim()))
                        }.onFailure {
                            container.presentApiError(it, errorVoiceParse, { msg -> error = msg }, { msg -> alertError = msg })
                        }
                        parsing = false
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        ShopTextField(
            stringResource(R.string.voice_sentence),
            inputText,
            { inputText = it },
            placeholder = stringResource(R.string.voice_sentence_placeholder),
            singleLine = false,
        )

        parsed?.let { p ->
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

        if (error != null) {
            Text(error!!, color = Danger, modifier = Modifier.padding(top = 8.dp))
        }
    }

    val parsedTransaction = parsed
    if (showConfirmDialog && parsedTransaction != null) {
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
