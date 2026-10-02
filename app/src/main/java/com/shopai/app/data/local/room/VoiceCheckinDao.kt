package com.shopai.app.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VoiceCheckinDao {
    @Query("SELECT * FROM voice_checkin_prefs WHERE id = 1 LIMIT 1")
    suspend fun getPrefs(): VoiceCheckinPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrefs(prefs: VoiceCheckinPrefsEntity)
}
