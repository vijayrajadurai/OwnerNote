package com.shopai.app.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row table (like business_profile in the RN app's schema) — one
 * device, one owner, one set of Daily Voice Check-in preferences. */
@Entity(tableName = "voice_checkin_prefs")
data class VoiceCheckinPrefsEntity(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val slot8AmEnabled: Boolean = true,
    val slot12PmEnabled: Boolean = true,
    val slot4PmEnabled: Boolean = true,
    val slot8PmEnabled: Boolean = true,
)
