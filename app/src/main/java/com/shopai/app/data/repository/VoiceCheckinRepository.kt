package com.shopai.app.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.shopai.app.data.local.room.VoiceCheckinDao
import com.shopai.app.data.local.room.VoiceCheckinPrefsEntity
import com.shopai.app.receiver.VoiceCheckinAlarmReceiver
import com.shopai.app.util.VoiceCheckinSlot
import java.util.Calendar

/**
 * Owns Daily Voice Check-in preferences (Room-backed, single row) and the 4
 * exact alarms that fire it — schedule/cancel logic lives here so both the
 * Settings screen and BootCompletedReceiver call the exact same code path,
 * never two different re-implementations of "what alarms should exist".
 */
class VoiceCheckinRepository(
    private val appContext: Context,
    private val dao: VoiceCheckinDao,
) {
    suspend fun getPrefs(): VoiceCheckinPrefsEntity = dao.getPrefs() ?: VoiceCheckinPrefsEntity()

    suspend fun setEnabled(enabled: Boolean): VoiceCheckinPrefsEntity {
        val updated = getPrefs().copy(enabled = enabled)
        dao.upsertPrefs(updated)
        applySchedule(updated)
        return updated
    }

    suspend fun setSlotEnabled(slot: VoiceCheckinSlot, enabled: Boolean): VoiceCheckinPrefsEntity {
        val current = getPrefs()
        val updated = when (slot) {
            VoiceCheckinSlot.MORNING_8AM -> current.copy(slot8AmEnabled = enabled)
            VoiceCheckinSlot.NOON_12PM -> current.copy(slot12PmEnabled = enabled)
            VoiceCheckinSlot.EVENING_4PM -> current.copy(slot4PmEnabled = enabled)
            VoiceCheckinSlot.NIGHT_8PM -> current.copy(slot8PmEnabled = enabled)
        }
        dao.upsertPrefs(updated)
        applySchedule(updated)
        return updated
    }

    /** Re-schedules every alarm from stored prefs — called on boot, and
     * whenever a toggle changes. Cancels a slot's alarm if the master
     * switch or that slot's own switch is off. */
    fun applySchedule(prefs: VoiceCheckinPrefsEntity) {
        for (slot in VoiceCheckinSlot.entries) {
            val slotEnabled = prefs.enabled && isSlotEnabled(prefs, slot)
            if (slotEnabled) scheduleSlot(slot) else cancelSlot(slot)
        }
    }

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    private fun isSlotEnabled(prefs: VoiceCheckinPrefsEntity, slot: VoiceCheckinSlot): Boolean = when (slot) {
        VoiceCheckinSlot.MORNING_8AM -> prefs.slot8AmEnabled
        VoiceCheckinSlot.NOON_12PM -> prefs.slot12PmEnabled
        VoiceCheckinSlot.EVENING_4PM -> prefs.slot4PmEnabled
        VoiceCheckinSlot.NIGHT_8PM -> prefs.slot8PmEnabled
    }

    private fun scheduleSlot(slot: VoiceCheckinSlot) {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        if (!canScheduleExactAlarms()) return
        runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextTriggerMillis(slot.hour),
                pendingIntentFor(slot),
            )
        }
    }

    private fun cancelSlot(slot: VoiceCheckinSlot) {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntentFor(slot))
    }

    private fun pendingIntentFor(slot: VoiceCheckinSlot): PendingIntent {
        val intent = Intent(appContext, VoiceCheckinAlarmReceiver::class.java).apply {
            action = ACTION_VOICE_CHECKIN
            putExtra(EXTRA_SLOT, slot.name)
        }
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE_BASE + slot.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextTriggerMillis(hour: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DATE, 1)
        }
        return target.timeInMillis
    }

    companion object {
        const val ACTION_VOICE_CHECKIN = "com.shopai.app.ACTION_VOICE_CHECKIN"
        const val EXTRA_SLOT = "slot"
        private const val REQUEST_CODE_BASE = 9001
    }
}
