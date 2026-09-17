package com.shopai.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shop_ai_prefs")

class TokenStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("auth_token")
    private val phoneKey = stringPreferencesKey("pending_phone")

    val tokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[tokenKey]
    }

    suspend fun getToken(): String? = context.dataStore.data.first()[tokenKey]

    suspend fun setToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token == null) {
                prefs.remove(tokenKey)
            } else {
                prefs[tokenKey] = token
            }
        }
    }

    suspend fun setPendingPhone(phone: String?) {
        context.dataStore.edit { prefs ->
            if (phone == null) {
                prefs.remove(phoneKey)
            } else {
                prefs[phoneKey] = phone
            }
        }
    }

    suspend fun getPendingPhone(): String? = context.dataStore.data.first()[phoneKey]
}
