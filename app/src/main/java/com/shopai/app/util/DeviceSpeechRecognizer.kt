package com.shopai.app.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * In-app speech recognition without the system Google voice popup
 * ([RecognizerIntent] activity). Uses [SpeechRecognizer] on the main thread.
 */
class DeviceSpeechRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private var active = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening(
        languageTag: String = "ta-IN",
        onReady: () -> Unit = {},
        onRmsChanged: (Float) -> Unit = {},
        onPartialResult: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (Int) -> Unit,
        /** All the guesses, the phone's best first — given instead of [onResult] when set (Kai Chat picks the one it reads best). */
        onResults: ((List<String>) -> Unit)? = null,
        /** The shop's own words (its people's and products' names): the recognizer leans to them where the phone allows it (Android 13+). */
        biasing: List<String> = emptyList(),
    ) {
        if (!isAvailable()) {
            onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        stopListening()
        active = true
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onReady()
                    }

                    override fun onBeginningOfSpeech() = Unit

                    override fun onRmsChanged(rmsdB: Float) {
                        onRmsChanged(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        active = false
                        onError(error)
                    }

                    override fun onResults(results: Bundle?) {
                        active = false
                        val guesses = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty().filter { it.isNotBlank() }
                        val spoken = guesses.firstOrNull()
                        if (!spoken.isNullOrBlank()) {
                            if (onResults != null) onResults(guesses) else onResult(spoken)
                        } else {
                            onError(SpeechRecognizer.ERROR_NO_MATCH)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            onPartialResult(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                },
            )
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Up to five guesses of the line: Kai Chat takes the one it reads best (the others use the first, as before).
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // "Ammu", "Sri Balaji Agencies", "Colgate": names the phone otherwise hears as other words (owner, 21 Oct 2026). Phones that
            // don't support it ignore it.
            if (biasing.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing.distinct().take(MAX_BIASING)))
            }
            // A shop owner pauses mid-line ("Kumar … 2000 kuduthan"): wait a little longer before taking the line as finished.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        active = false
        speechRecognizer?.let { recognizer ->
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.cancel() }
            recognizer.destroy()
        }
        speechRecognizer = null
    }

    fun isListening(): Boolean = active

    private companion object {
        /** The recognizer reads a short list best: the shop's most used names first. */
        const val MAX_BIASING = 100
    }
}
