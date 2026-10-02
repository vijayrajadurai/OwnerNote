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
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!spoken.isNullOrBlank()) {
                            onResult(spoken)
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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
}
