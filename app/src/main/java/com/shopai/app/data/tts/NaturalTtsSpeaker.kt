package com.shopai.app.data.tts

import android.content.Context
import android.media.AudioAttributes
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
     */
    fun speakNatural(
        text: String,
        languageCode: String = "ta-IN",
        fallbackText: String = text,
        fallbackLanguage: String = "ta-IN",
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        scope.launch {
            withContext(NonCancellable) {
                speakNaturalInternal(text, languageCode, fallbackText, fallbackLanguage, onStart, onDone)
            }
        }
    }

    fun stop() {
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
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone?.invoke()
            return
        }
        onStart?.invoke()

        val proxyAudio = fetchProxyAudio(trimmed, languageCode)
        if (proxyAudio != null) {
            val played = playWavFile(proxyAudio)
            proxyAudio.delete()
            if (played) {
                onDone?.invoke()
                return
            }
            logDebug("Proxy audio playback failed; falling back to device TTS.")
        } else {
            logDebug("Proxy TTS unavailable; falling back to device TTS.")
        }

        val spokeOnDevice = speakWithDeviceTts(
            fallbackText.trim().ifEmpty { trimmed },
            fallbackLanguage,
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

    private suspend fun playWavFile(file: File): Boolean = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        fun finish(result: Boolean) {
            if (finished.compareAndSet(false, true)) {
                cont.resume(result)
            }
        }

        stop()
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { prepared ->
                runCatching { prepared.start() }
                    .onFailure {
                        logDebug("MediaPlayer start failed: ${it.message}")
                        prepared.release()
                        mediaPlayer = null
                        finish(false)
                    }
            }
            player.setOnCompletionListener {
                it.release()
                mediaPlayer = null
                finish(true)
            }
            player.setOnErrorListener { mp, what, extra ->
                logDebug("MediaPlayer error what=$what extra=$extra")
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

    private suspend fun speakWithDeviceTts(text: String, languageTag: String): Boolean {
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
                    override fun onStart(spokenId: String?) = Unit
                    override fun onDone(spokenId: String?) {
                        if (spokenId == utteranceId) finish(true)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(spokenId: String?) {
                        if (spokenId == utteranceId) finish(false)
                    }
                    override fun onError(spokenId: String?, errorCode: Int) {
                        if (spokenId == utteranceId) finish(false)
                    }
                },
            )
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
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
