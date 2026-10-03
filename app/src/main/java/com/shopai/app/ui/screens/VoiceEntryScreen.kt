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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import com.shopai.app.ui.components.DetailScaffold
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.components.TransactionSaveType
import com.shopai.app.ui.kai.KaiEvent
import com.shopai.app.ui.kai.KaiReaction
import com.shopai.app.ui.kai.KaiScene
import com.shopai.app.ui.kai.KaiStage
import com.shopai.app.ui.kai.KaiState
import com.shopai.app.ui.kai.reaction
import com.shopai.app.brain.Direction
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.KaiReply
import com.shopai.app.brain.KaiResponder
import com.shopai.app.brain.KaiTurn
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.DeviceSpeechRecognizer
import com.shopai.app.util.exceedsMaxLedgerAmount
import com.shopai.app.util.localDateToIsoInstant
import com.shopai.app.util.parseIsoToLocalDate
import java.time.LocalDate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The microphone / request side of Pesunga (KAI shows it). */
private enum class MicState { Idle, Listening, Processing }

@Composable
fun VoiceEntryScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenHandwrittenNotes: () -> Unit = {},
    onScanNoteForBill: (billId: Long) -> Unit = {},
    onOpenNotePerson: (name: String) -> Unit = {},
    /** "Kai, morning work ready pannu" → Kai's Morning Work ([voice] = the owner spoke it). */
    onOpenMorningWork: (voice: Boolean, start: Boolean) -> Unit = { _, _ -> },
) {
    var inputText by remember { mutableStateOf("") }
    // The words just came from the mic (Morning Work then answers by voice).
    var fromMic by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<ParsedTransaction?>(null) }
    var queryAnswer by remember { mutableStateOf<String?>(null) }
    var partyName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val enteredAmount = amount.toDoubleOrNull() ?: 0.0
    val amountTooLarge = exceedsMaxLedgerAmount(enteredAmount)
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var orbState by remember { mutableStateOf(MicState.Idle) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val speechRecognizer = remember { DeviceSpeechRecognizer(context) }

    // KAI: listens → thinks → speaks → reacts → back to idle.
    var kai by remember { mutableStateOf(KaiScene()) }
    var kaiLine by remember { mutableStateOf<String?>(null) }
    val kaiMouth by container.naturalTtsSpeaker.mouthLevel.collectAsState()
    fun kai(event: KaiEvent) { kai = kai.on(event) }
    LaunchedEffect(kai) {
        val hold = kai.holdMillis ?: return@LaunchedEffect
        delay(hold)
        kai(KaiEvent.HoldElapsed)
    }
    val brain = container.kaiBrain
    /** Kai shows and says [reply] — the same words; [then] runs when his voice ends. */
    fun kaiSay(reply: KaiReply, then: KaiEvent = KaiEvent.SpeechEnded) {
        kaiLine = reply.display
        brain.say(reply) { kai(then) }
    }
    val errorSpeechUnavailable = stringResource(R.string.error_speech_unavailable)
    val errorMicPermission = stringResource(R.string.error_mic_permission)
    val errorVoiceParse = stringResource(R.string.error_voice_parse)
    val errorVoiceSave = stringResource(R.string.error_voice_save)
    val errorVoiceNoSpeech = stringResource(R.string.error_voice_no_speech)

    DisposableEffect(Unit) {
        onDispose { speechRecognizer.stopListening() }
    }

    fun applyParsed(p: ParsedTransaction) {
        parsed = p
        queryAnswer = null
        partyName = p.partyName ?: ""
        amount = p.amount?.toString() ?: ""
        description = p.description ?: ""
        val today = LocalDate.now()
        dueDate = parseIsoToLocalDate(p.dueDate)?.takeIf { !it.isBefore(today) }
            ?: today.takeIf { p.billDetected }
    }

    /**
     * Everything the owner says or types goes to Kai's Business Brain: an
     * entry comes back as a proposal (filled into the form to confirm), a
     * question as an answer from the ledger, a gap as one short question.
     */
    fun parseInput() {
        val text = inputText.trim()
        val spoken = fromMic
        fromMic = false
        if (text.isBlank()) {
            orbState = MicState.Idle
            kai(KaiEvent.Cancel)
            return
        }
        // A clear Morning Work request goes to Kai's Morning Work; everything else stays here as before.
        com.shopai.app.brain.morning.MorningCommands.morningRequest(text)?.let { request ->
            orbState = MicState.Idle
            kai(KaiEvent.Cancel)
            onOpenMorningWork(spoken, request == com.shopai.app.brain.morning.MorningCommand.Start)
            return
        }
        scope.launch {
            parsing = true
            orbState = MicState.Processing
            kai(KaiEvent.Think)
            kaiLine = context.getString(R.string.kai_processing)
            error = null
            queryAnswer = null
            val turn = runCatching { brain.hear(text) }.getOrNull()
            when (turn) {
                null -> {
                    kai(KaiEvent.Understood(KaiReaction.CLARIFY))
                    kaiSay(KaiResponder.couldNotLoad(KaiLanguage.detect(text)))
                    error = errorVoiceParse
                }
                is KaiTurn.Proposal -> {
                    applyParsed(turn.transaction)
                    kai(KaiEvent.Understood(turn.reply.mood.reaction()))
                    kaiSay(turn.reply)
                }
                is KaiTurn.Clarify -> {
                    kai(KaiEvent.Understood(KaiReaction.CLARIFY))
                    kaiSay(turn.reply)
                }
                is KaiTurn.Answer -> {
                    parsed = null
                    queryAnswer = turn.reply.display
                    kai(KaiEvent.Understood(turn.reply.mood.reaction()))
                    kaiSay(turn.reply)
                }
            }
            parsing = false
            orbState = MicState.Idle
        }
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
        orbState = MicState.Listening
        audioLevel = 0f
        // KAI stops talking and listens (his voice never competes with the mic).
        container.naturalTtsSpeaker.stop()
        kai(KaiEvent.Listen)
        kaiLine = context.getString(R.string.kai_listening)
        speechRecognizer.startListening(
            languageTag = "ta-IN",
            onReady = { orbState = MicState.Listening },
            onRmsChanged = { audioLevel = normalizeAudioLevel(it) },
            onPartialResult = { partialText = it },
            onResult = { spoken ->
                inputText = spoken
                partialText = spoken
                fromMic = true
                parseInput()
            },
            onError = { code ->
                orbState = MicState.Idle
                audioLevel = 0f
                if (code == SpeechRecognizer.ERROR_CLIENT) kai(KaiEvent.Cancel) else { kai(KaiEvent.Understood(KaiReaction.CLARIFY)); kaiSay(KaiResponder.didNotUnderstand(KaiLanguage.forAppLocale())) }
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
            MicState.Listening -> {
                speechRecognizer.stopListening()
                orbState = MicState.Idle
                audioLevel = 0f
                kai(KaiEvent.Cancel)
            }
            MicState.Processing -> Unit
            MicState.Idle -> {
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> startListening()
                    else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    // What KAI says: his last line while he speaks or reacts, otherwise his state's line.
    val kaiIdleLine = stringResource(R.string.kai_idle)
    val kaiText = when (kai.state) {
        KaiState.IDLE -> kaiIdleLine
        KaiState.LISTENING -> stringResource(R.string.kai_listening)
        KaiState.PROCESSING -> stringResource(R.string.kai_processing)
        else -> kaiLine ?: kaiIdleLine
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(
        title = stringResource(R.string.voice_title),
        onBack = onBack,
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
        Text(
            stringResource(R.string.voice_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // KAI is the speaking interface: tap him to talk.
        KaiStage(
            state = kai.state,
            line = kaiText,
            mouthLevel = kaiMouth,
            speakingAs = kai.speakingAs,
            heard = if (orbState == MicState.Listening) partialText else null,
            enabled = orbState != MicState.Processing,
            onTap = { onOrbClick() },
        )

        // Shop bills and handwritten notes: capture, read, link, and the saved list.
        DocumentCaptureSection(
            container = container,
            onOpenHandwrittenNotes = onOpenHandwrittenNotes,
            onScanNoteForBill = onScanNoteForBill,
            onOpenNotePerson = onOpenNotePerson,
        )

        ShopTextField(
            stringResource(R.string.voice_sentence),
            inputText,
            { inputText = it },
            placeholder = stringResource(R.string.voice_sentence_placeholder),
            singleLine = false,
        )

        PrimaryButton(
            label = stringResource(R.string.voice_parse),
            loading = parsing,
            enabled = inputText.isNotBlank() && orbState != MicState.Listening,
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
                        error = if (amountTooLarge) stringResource(R.string.amount_max_one_crore) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    ShopTextField(stringResource(R.string.note_label), description, { description = it })
                    FutureDatePickerField(
                        label = stringResource(R.string.due_date_label),
                        selectedDate = dueDate,
                        onDateSelected = { dueDate = it },
                        placeholder = stringResource(R.string.due_date_placeholder),
                        allowEmpty = false,
                    )
                }

                PrimaryButton(
                    label = stringResource(R.string.save),
                    loading = saving,
                    enabled = partyName.isNotBlank() &&
                        enteredAmount > 0 &&
                        !amountTooLarge &&
                        description.trim().isNotEmpty() &&
                        dueDate != null,
                    onClick = { showConfirmDialog = true },
                )
            }
        }

        if (error != null) {
            Text(error!!, color = Danger, modifier = Modifier.padding(top = 8.dp))
        }
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
                                CreateDebitInput(
                                    supplierName = partyName.trim(),
                                    amount = amt,
                                    description = description.trim(),
                                    dueDate = due,
                                ),
                                source = com.shopai.app.books.model.TxnSource.VOICE,
                            )
                            else -> container.transactionRepository.createCredit(
                                CreateCreditInput(
                                    customerName = partyName.trim(),
                                    amount = amt,
                                    description = description.trim(),
                                    dueDate = due,
                                ),
                                source = com.shopai.app.books.model.TxnSource.VOICE,
                            )
                        }
                        // Kai: thumbs up and says what was saved — the saved values, a reminder only if one exists.
                        val direction = if (p.intent == "CREATE_DEBIT") Direction.PAYABLE else Direction.RECEIVABLE
                        val reply = brain.saved(partyName.trim(), amt, direction, dueDate, KaiLanguage.detect(inputText.ifBlank { p.rawText }))
                        kai(KaiEvent.Saved)
                        kaiLine = reply.display
                        // Announced app-wide, so Kai finishes speaking on the next screen.
                        brain.announce(reply)
                        delay(1_500)
                        onDone()
                    }.onFailure {
                        kai(KaiEvent.NeedsClarification)
                        container.presentApiError(it, errorVoiceSave, { msg -> error = msg }, { msg -> alertError = msg })
                    }
                    saving = false
                }
            },
            onDismiss = {
                showConfirmDialog = false
                kai(KaiEvent.Cancel)
            },
        )
    }
}
