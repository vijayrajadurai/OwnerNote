package com.shopai.app.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.shopai.app.BuildConfig
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Natural Tamil speech via the Sarvam AI Vercel proxy (apps/tts-proxy), with
 * device TextToSpeech fallback — mirrors apps/mobile/src/services/ttsApi.ts.
 * Never throws; failed playback is silent from the user's perspective.
 */
class NaturalTtsSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private val ttsInitMutex = Mutex()
    private var mediaPlayer: MediaPlayer? = null

    // KAI's lip-sync: how open his mouth is (0..1) while the voice is audible,
    // and whether a voice is playing right now. Read-only for the UI.
    private val _mouthLevel = MutableStateFlow(0f)
    val mouthLevel: StateFlow<Float> = _mouthLevel.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private var lipSyncJob: Job? = null
    /** Bumped by [stop]: a request made before it never plays afterwards (its audio may still be on its way). */
    @Volatile private var generation = 0

    /** Drives [mouthLevel] until [stopLipSync]: from the audio's loudness, or a natural talking rhythm. */
    private fun startLipSync(envelope: SpeechEnvelope?, position: () -> Int?) {
        lipSyncJob?.cancel()
        _speaking.value = true
        val started = System.currentTimeMillis()
        lipSyncJob = scope.launch {
            while (isActive) {
                val at = position() ?: (System.currentTimeMillis() - started).toInt()
                _mouthLevel.value = envelope?.levelAt(at) ?: talkingRhythm(at)
                delay(33)
            }
        }
    }

    private fun stopLipSync() {
        lipSyncJob?.cancel()
        lipSyncJob = null
        _mouthLevel.value = 0f
        _speaking.value = false
    }

    // Syllables at roughly 4–5 per second with small pauses, when the audio can't be measured.
    private fun talkingRhythm(millis: Int): Float {
        val t = millis / 1000.0
        val syllable = kotlin.math.abs(kotlin.math.sin(t * Math.PI * 4.6))
        val phrase = 0.55 + 0.45 * kotlin.math.sin(t * Math.PI * 0.9 + 1.3)
        return (syllable * phrase).toFloat().coerceIn(0f, 1f)
    }

    private val proxyUrl = BuildConfig.TTS_PROXY_URL.trim().removeSuffix("/")
    private val proxyKey = BuildConfig.TTS_PROXY_KEY.trim()

    private val ttsApi: TtsProxyApi? = proxyUrl.takeIf { it.isNotEmpty() }?.let { url ->
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("$url/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TtsProxyApi::class.java)
    }

    /** Warm up device TTS so the first Home greeting is not dropped on slow init. */
    fun warmUp() {
        scope.launch { ensureTts() }
    }

    /**
     * Speaks on an app-scoped coroutine so playback is not cancelled when
     * the calling screen leaves composition mid-request.
     *
     * [useAlarmStream] routes playback through STREAM_ALARM instead of the
     * default media stream — the same mechanism alarm-clock apps use to be
     * heard over silent/DND mode. Only the Daily Voice Check-in feature
     * passes true; every other caller keeps today's default (unchanged)
     * media-stream behavior.
     */
    fun speakNatural(
        text: String,
        languageCode: String = "ta-IN",
        fallbackText: String = text,
        fallbackLanguage: String = "ta-IN",
        useAlarmStream: Boolean = false,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        val asked = generation
        scope.launch {
            withContext(NonCancellable) {
                speakNaturalInternal(text, languageCode, fallbackText, fallbackLanguage, useAlarmStream, onStart, onDone, asked)
            }
        }
    }

    /** Stops what is playing and drops every request still being prepared (nothing queued plays later). */
    fun stop() {
        generation++
        halt()
    }

    /** Stops the current playback only (a new playback starting uses this; queued requests stay). */
    private fun halt() {
        stopLipSync()
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        textToSpeech?.stop()
    }

    fun shutdown() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
    }

    private suspend fun speakNaturalInternal(
        text: String,
        languageCode: String,
        fallbackText: String,
        fallbackLanguage: String,
        useAlarmStream: Boolean,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        asked: Int,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone?.invoke()
            return
        }
        onStart?.invoke()

        val proxyAudio = fetchProxyAudio(trimmed, languageCode)
        if (asked != generation) {
            // Stopped while the audio was being fetched: it never plays.
            proxyAudio?.delete()
            onDone?.invoke()
            return
        }
        if (proxyAudio != null) {
            val played = playWavFile(proxyAudio, useAlarmStream)
            proxyAudio.delete()
            if (played) {
                onDone?.invoke()
                return
            }
            logDebug("Proxy audio playback failed; falling back to device TTS.")
        } else {
            logDebug("Proxy TTS unavailable; falling back to device TTS.")
        }

        if (asked != generation) {
            onDone?.invoke()
            return
        }
        val spokeOnDevice = speakWithDeviceTts(
            fallbackText.trim().ifEmpty { trimmed },
            fallbackLanguage,
            useAlarmStream,
        )
        if (!spokeOnDevice) {
            logDebug("Device TTS also failed.")
        }
        onDone?.invoke()
    }

    private suspend fun fetchProxyAudio(text: String, languageCode: String): File? {
        val api = ttsApi ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val key = proxyKey.takeIf { it.isNotEmpty() }
                val response = api.synthesize(TtsRequest(text, languageCode), key)
                val audioBase64 = response.audioBase64?.takeIf { it.isNotEmpty() }
                    ?: return@runCatching null
                val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
                File.createTempFile("tts_", ".wav", appContext.cacheDir).apply {
                    writeBytes(audioBytes)
                }
            }.onFailure { err ->
                logDebug("Proxy TTS request failed: ${err.message}")
            }.getOrNull()
        }
    }

    private suspend fun playWavFile(file: File, useAlarmStream: Boolean): Boolean = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        fun finish(result: Boolean) {
            if (finished.compareAndSet(false, true)) {
                cont.resume(result)
            }
        }

        halt()
        val envelope = runCatching { SpeechEnvelope.fromWav(file.readBytes()) }.getOrNull()
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (useAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { prepared ->
                runCatching {
                    prepared.start()
                    startLipSync(envelope) { runCatching { mediaPlayer?.currentPosition }.getOrNull() }
                }
                    .onFailure {
                        logDebug("MediaPlayer start failed: ${it.message}")
                        stopLipSync()
                        prepared.release()
                        mediaPlayer = null
                        finish(false)
                    }
            }
            player.setOnCompletionListener {
                stopLipSync()
                it.release()
                mediaPlayer = null
                finish(true)
            }
            player.setOnErrorListener { mp, what, extra ->
                logDebug("MediaPlayer error what=$what extra=$extra")
                stopLipSync()
                mp.release()
                mediaPlayer = null
                finish(false)
                true
            }
            player.prepareAsync()
        } catch (err: Exception) {
            logDebug("MediaPlayer setup failed: ${err.message}")
            mediaPlayer = null
            finish(false)
        }
    }

    private suspend fun speakWithDeviceTts(text: String, languageTag: String, useAlarmStream: Boolean): Boolean {
        if (text.isEmpty()) return false
        val engine = ensureTts() ?: return false

        return suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()
            val finished = AtomicBoolean(false)
            fun finish(result: Boolean) {
                if (finished.compareAndSet(false, true)) {
                    cont.resume(result)
                }
            }

            val locale = Locale.forLanguageTag(languageTag)
            val langResult = engine.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }

            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    // Device TTS audio can't be measured: KAI talks in a natural rhythm.
                    override fun onStart(spokenId: String?) {
                        if (spokenId == utteranceId) scope.launch { startLipSync(null) { null } }
                    }
                    override fun onDone(spokenId: String?) {
                        if (spokenId == utteranceId) {
                            scope.launch { stopLipSync() }
                            finish(true)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(spokenId: String?) {
                        if (spokenId == utteranceId) {
                            scope.launch { stopLipSync() }
                            finish(false)
                        }
                    }
                    override fun onError(spokenId: String?, errorCode: Int) {
                        if (spokenId == utteranceId) {
                            scope.launch { stopLipSync() }
                            finish(false)
                        }
                    }
                },
            )
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                if (useAlarmStream) {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                }
            }
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                finish(false)
            }
        }
    }

    private suspend fun ensureTts(): TextToSpeech? = ttsInitMutex.withLock {
        if (textToSpeech != null && ttsReady) return textToSpeech
        suspendCancellableCoroutine { cont ->
            textToSpeech = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    cont.resume(textToSpeech)
                } else {
                    logDebug("TextToSpeech init failed with status=$status")
                    cont.resume(null)
                }
            }
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "NaturalTtsSpeaker"
    }
}
