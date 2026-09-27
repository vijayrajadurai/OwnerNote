package com.shopai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.shopai.app.ShopAiApplication
import com.shopai.app.data.repository.VoiceCheckinRepository
import com.shopai.app.service.VoiceCheckinService
import com.shopai.app.util.VoiceCheckinSlot
import kotlinx.coroutines.launch

/**
 * Fires when a Daily Voice Check-in alarm goes off — starts the foreground
 * service that actually fetches data and speaks. AlarmManager alarms are
 * one-shot, so this also immediately re-arms tomorrow's alarm for every
 * slot (idempotent for slots not due yet today), the same schedule logic
 * BootCompletedReceiver and the Settings toggles use.
 */
class VoiceCheckinAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slotName = intent.getStringExtra(VoiceCheckinRepository.EXTRA_SLOT) ?: return
        val slot = runCatching { VoiceCheckinSlot.valueOf(slotName) }.getOrNull() ?: return

        val serviceIntent = Intent(context, VoiceCheckinService::class.java).apply {
            putExtra(VoiceCheckinRepository.EXTRA_SLOT, slot.name)
        }
        ContextCompat.startForegroundService(context, serviceIntent)

        val app = context.applicationContext as? ShopAiApplication ?: return
        app.container.appScope.launch {
            val repository = app.container.voiceCheckinRepository
            repository.applySchedule(repository.getPrefs())
        }
    }
}
