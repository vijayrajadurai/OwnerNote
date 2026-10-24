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
        start(Request(languageTag, onReady, onRmsChanged, onPartialResult, onResult, onError, onResults, biasing), attempt = 0)
    }

    private class Request(
        val languageTag: String,
        val onReady: () -> Unit,
        val onRmsChanged: (Float) -> Unit,
        val onPartialResult: (String) -> Unit,
        val onResult: (String) -> Unit,
        val onError: (Int) -> Unit,
        val onResults: ((List<String>) -> Unit)?,
        val biasing: List<String>,
    )

    /** Which start is current: a callback from a recognizer that was replaced or stopped is ignored. */
    private var generation = 0

    /**
     * [attempt] 0: with the shop's words and five guesses. 1: plain, the way the mic started before 21 Oct 2026 — used only when
     * the phone's speech service stopped the first start at once with nothing heard (owner's video, 23 Oct 2026; [MicStart]).
     */
    private fun start(req: Request, attempt: Int) {
        stopListening()
        active = true
        val mine = ++generation
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var heardSpeech = false
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(
                object : RecognitionListener {
                    private fun current() = mine == generation

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (current()) req.onReady()
                    }

                    override fun onBeginningOfSpeech() {
                        heardSpeech = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        if (current()) req.onRmsChanged(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        if (!current()) return
                        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                        if (MicStart.retryPlain(error, attempt, heardSpeech, elapsed)) {
                            android.util.Log.w(TAG, "mic stopped at once (error $error after ${elapsed} ms) — starting again plainly")
                            // Not inside the old recognizer's own callback; and not if the owner stopped the mic meanwhile.
                            android.os.Handler(android.os.Looper.getMainLooper()).post { if (mine == generation) start(req, attempt = 1) }
                            return
                        }
                        android.util.Log.w(TAG, "mic error $error (attempt $attempt, ${elapsed} ms, speech heard: $heardSpeech)")
                        active = false
                        req.onError(error)
                    }

                    override fun onResults(results: Bundle?) {
                        if (!current()) return
                        active = false
                        val guesses = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty().filter { it.isNotBlank() }
                        val spoken = guesses.firstOrNull()
                        if (!spoken.isNullOrBlank()) {
                            if (req.onResults != null) req.onResults.invoke(guesses) else req.onResult(spoken)
                        } else {
                            req.onError(SpeechRecognizer.ERROR_NO_MATCH)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!current()) return
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            heardSpeech = true
                            req.onPartialResult(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                },
            )
        }

        val plain = attempt > 0
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, req.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Up to five guesses of the line: Kai Chat takes the one it reads best (the others use the first, as before).
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, if (plain) 1 else 5)
            // "Ammu", "Sri Balaji Agencies", "Colgate": names the phone otherwise hears as other words (owner, 21 Oct 2026). Phones that
            // don't support it ignore it — and a phone that stops the mic over it gets the plain start (above).
            if (!plain && req.biasing.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(req.biasing.distinct().take(MAX_BIASING)))
            }
            // A shop owner pauses mid-line ("Kumar … 2000 kuduthan"): wait a little longer before taking the line as finished.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        active = false
        generation++
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
        const val TAG = "KaiMic"
    }
}
