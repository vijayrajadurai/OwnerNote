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

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    TAMIL("ta"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: ENGLISH
    }
}

enum class AppThemeMode(val storageValue: String) {
    SYSTEM("system"),
    DARK("dark"),
    ;

    companion object {
        fun fromValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

class UserPreferencesStore(context: Context) {
    private val appContext = context.applicationContext
    private val languageKey = stringPreferencesKey("app_language")
    private val themeKey = stringPreferencesKey("app_theme")

    val languageFlow: Flow<AppLanguage> = appContext.userPrefsDataStore.data.map { prefs ->
        AppLanguage.fromTag(prefs[languageKey])
    }

    val themeFlow: Flow<AppThemeMode> = appContext.userPrefsDataStore.data.map { prefs ->
        AppThemeMode.fromValue(prefs[themeKey])
    }

    suspend fun getLanguage(): AppLanguage =
        AppLanguage.fromTag(appContext.userPrefsDataStore.data.first()[languageKey])

    suspend fun getTheme(): AppThemeMode =
        AppThemeMode.fromValue(appContext.userPrefsDataStore.data.first()[themeKey])

    suspend fun setLanguage(language: AppLanguage) {
        appContext.userPrefsDataStore.edit { prefs ->
            prefs[languageKey] = language.tag
        }
    }

    suspend fun setTheme(theme: AppThemeMode) {
        appContext.userPrefsDataStore.edit { prefs ->
            prefs[themeKey] = theme.storageValue
        }
    }
}
