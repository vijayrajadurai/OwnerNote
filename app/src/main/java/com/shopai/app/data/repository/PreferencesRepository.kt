package com.shopai.app.data.repository

import com.shopai.app.data.local.AppLanguage
import com.shopai.app.data.local.AppThemeMode
import com.shopai.app.data.local.UserPreferencesStore
import kotlinx.coroutines.flow.Flow

class PreferencesRepository(private val store: UserPreferencesStore) {
    val languageFlow: Flow<AppLanguage> = store.languageFlow
    val themeFlow: Flow<AppThemeMode> = store.themeFlow

    suspend fun getLanguage(): AppLanguage = store.getLanguage()
    suspend fun getTheme(): AppThemeMode = store.getTheme()
    suspend fun setLanguage(language: AppLanguage) = store.setLanguage(language)
    suspend fun setTheme(theme: AppThemeMode) = store.setTheme(theme)
}
