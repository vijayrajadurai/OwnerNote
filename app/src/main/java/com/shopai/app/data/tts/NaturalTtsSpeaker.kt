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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    @Volatile private var mediaPlayer: MediaPlayer? = null

    // Playback lives on its own thread: a busy screen (the first frames of an animation, a heavy layout)
    // never holds the voice back — MediaPlayer's callbacks arrive on this thread, not the main one.
    private val voiceThread by lazy { android.os.HandlerThread("kai-voice").apply { start() } }
    private val voiceHandler by lazy { android.os.Handler(voiceThread.looper) }
    /** Bumped by every stop / new playback: a playback still being set up for an older one never starts. */
    @Volatile private var playGeneration = 0

    // KAI's lip-sync: how open his mouth is (0..1) while the voice is audible,
    // and whether a voice is playing right now. Read-only for the UI.
    private val _mouthLevel = MutableStateFlow(0f)
    val mouthLevel: StateFlow<Float> = _mouthLevel.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private var lipSyncJob: Job? = null
    /** Which voice played the last line: "sarvam" (the natural voice), "device" (Android TTS fallback) or "none". */
    @Volatile var lastEngine: String = "none"
        private set
    /** Why the natural voice was not used last time (null when it was). */
    @Volatile var lastProxyProblem: String? = null
        private set
    @Volatile private var lastFetchError: String? = null

    // Natural-voice lines fetched ahead of time (reminders), kept on disk so they play instantly — also
    // after the app was closed and an alarm starts it again. Only [prefetch]ed lines are kept.
    private val voiceCache by lazy { File(appContext.cacheDir, "tts_cache").apply { mkdirs() } }
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Deferred<File?>>()

    private fun cacheKey(text: String, languageCode: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest("$languageCode|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun cachedFile(text: String, languageCode: String): File? =
        File(voiceCache, cacheKey(text, languageCode) + ".wav").takeIf { it.length() > 44 }

    /**
     * Fetches the natural voice for [texts] now and keeps it, so speaking them later starts at once
     * (no network wait). A line already kept or being fetched is not asked for twice.
     */
    fun prefetch(texts: List<String>, languageCode: String) {
        if (ttsApi == null) return
        val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        scope.launch(Dispatchers.IO) {
            for (t in lines) runCatching { keptAudio(t, languageCode) }
        }
    }

    /** The kept audio for [text], fetching (once) if needed. */
    private suspend fun keptAudio(text: String, languageCode: String): File? {
        cachedFile(text, languageCode)?.let { it.setLastModified(System.currentTimeMillis()); return it }
        val key = cacheKey(text, languageCode)
        val created = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            fetchProxyAudio(text, languageCode)?.let { tmp ->
                val kept = File(voiceCache, "$key.wav")
                if (!tmp.renameTo(kept)) { tmp.copyTo(kept, overwrite = true); tmp.delete() }
                trimVoiceCache()
                kept
            }
        }
        // Someone is already fetching this line: wait for theirs.
        val fetch = inflight.putIfAbsent(key, created)?.also { created.cancel() } ?: created.also { it.start() }
        return try { fetch.await() } finally { inflight.remove(key, fetch) }
    }

    private fun trimVoiceCache() {
        val files = voiceCache.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_KEPT_LINES).forEach { it.delete() }
    }
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
        playGeneration++
        stopLipSync()
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) voiceHandler.post { player.runCatching { if (isPlaying) stop(); release() } }
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

        // A line fetched ahead of time plays at once; one still being fetched is awaited, not asked twice.
        val key = cacheKey(trimmed, languageCode)
        val kept = cachedFile(trimmed, languageCode) ?: inflight[key]?.let { runCatching { it.await() }.getOrNull() }
        val proxyAudio = kept ?: fetchProxyAudio(trimmed, languageCode)
        if (asked != generation) {
            // Stopped while the audio was being fetched: it never plays.
            if (kept == null) proxyAudio?.delete()
            onDone?.invoke()
            return
        }
        if (proxyAudio != null) {
            val played = playWavFile(proxyAudio, useAlarmStream)
            if (kept == null) proxyAudio.delete()
            if (played) {
                lastEngine = "sarvam"
                lastProxyProblem = null
                onDone?.invoke()
                return
            }
            lastProxyProblem = "playback failed"
            logDebug("Proxy audio playback failed; falling back to device TTS.")
        } else {
            lastProxyProblem = if (ttsApi == null) "TTS_PROXY_URL not set in this build" else (lastFetchError ?: "no audio")
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
        lastEngine = if (spokeOnDevice) "device" else "none"
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
                lastFetchError = err.message?.take(120) ?: err.javaClass.simpleName
                logDebug("Proxy TTS request failed: ${err.message}")
            }.onSuccess { lastFetchError = if (it == null) "empty audio" else null }.getOrNull()
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
        val mine = playGeneration
        voiceHandler.post {
            if (mine != playGeneration) { finish(false); return@post }
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
                    if (mine != playGeneration) {
                        // Stopped while it was getting ready: it never plays.
                        prepared.release()
                        finish(false)
                        return@setOnPreparedListener
                    }
                    runCatching {
                        prepared.start()
                        scope.launch { startLipSync(envelope) { runCatching { mediaPlayer?.currentPosition }.getOrNull() } }
                    }
                        .onFailure {
                            logDebug("MediaPlayer start failed: ${it.message}")
                            scope.launch { stopLipSync() }
                            prepared.release()
                            if (mediaPlayer === prepared) mediaPlayer = null
                            finish(false)
                        }
                }
                player.setOnCompletionListener {
                    scope.launch { stopLipSync() }
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                    finish(true)
                }
                player.setOnErrorListener { mp, what, extra ->
                    logDebug("MediaPlayer error what=$what extra=$extra")
                    scope.launch { stopLipSync() }
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
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
        /** Kept natural-voice lines (oldest go first). */
        private const val MAX_KEPT_LINES = 120
        private const val TAG = "NaturalTtsSpeaker"
    }
}
