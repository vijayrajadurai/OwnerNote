package com.shopai.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.shopai.app.R
import com.shopai.app.ShopAiApplication
import com.shopai.app.data.repository.VoiceCheckinRepository
import com.shopai.app.util.VoiceCheckinSlot
import com.shopai.app.util.VoiceCheckinTextBuilder
import com.shopai.app.util.localDateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fetches real business data (never fabricated) and speaks the matching
 * Daily Voice Check-in message via the existing NaturalTtsSpeaker, routed
 * through STREAM_ALARM so it's heard over silent/DND — the same mechanism
 * alarm-clock apps rely on. Stops itself the moment speech finishes.
 */
class VoiceCheckinService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )

        val slot = intent?.getStringExtra(VoiceCheckinRepository.EXTRA_SLOT)
            ?.let { runCatching { VoiceCheckinSlot.valueOf(it) }.getOrNull() }
        if (slot == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        speak(slot)
        return START_NOT_STICKY
    }

    private fun speak(slot: VoiceCheckinSlot) {
        val container = (application as ShopAiApplication).container
        scope.launch {
            val text = runCatching {
                when (slot) {
                    VoiceCheckinSlot.MORNING_8AM, VoiceCheckinSlot.EVENING_4PM -> {
                        val priorities = container.insightsRepository.getPriorities()
                        VoiceCheckinTextBuilder.build(slot, priorities = priorities)
                    }
                    VoiceCheckinSlot.NIGHT_8PM -> {
                        val summary = container.dailyCashRepository.getTodayCashSummary(localDateKey())
                        VoiceCheckinTextBuilder.build(slot, todayCashSummary = summary)
                    }
                    VoiceCheckinSlot.NOON_12PM -> VoiceCheckinTextBuilder.build(slot)
                }
                // A network/DB failure below falls back to a plain greeting —
                // never a stale or guessed figure.
            }.getOrElse { VoiceCheckinTextBuilder.build(slot) }

            requestAlarmAudioFocus()
            container.naturalTtsSpeaker.speakNatural(
                text = text,
                useAlarmStream = true,
                onDone = {
                    abandonAlarmAudioFocus()
                    stopSelf()
                },
            )
        }
    }

    private fun requestAlarmAudioFocus() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAlarmAudioFocus() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.voice_checkin_notification_title))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_checkin_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        abandonAlarmAudioFocus()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_checkin"
        private const val NOTIFICATION_ID = 9101
    }
}
