package com.shopai.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Device-local only — there is no backend field or upload endpoint for a
 * profile photo yet, so this never leaves the device and won't follow the
 * owner to a reinstall or another device.
 */
class ProfilePhotoStore(private val context: Context) {
    private val photoPathKey = stringPreferencesKey("profile_photo_path")

    val photoPathFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[photoPathKey] }

    suspend fun getPhotoPath(): String? = context.dataStore.data.first()[photoPathKey]

    suspend fun setPhotoPath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path == null) prefs.remove(photoPathKey) else prefs[photoPathKey] = path
        }
    }

    fun newPhotoFile(): File = File(context.filesDir, "profile_photo_${System.currentTimeMillis()}.jpg")
}
