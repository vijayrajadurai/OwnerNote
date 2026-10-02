package com.shopai.app.ui.settings

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopai.app.data.local.AppLanguage
import com.shopai.app.data.local.AppThemeMode
import com.shopai.app.data.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppVersionInfo(
    val versionName: String,
    val versionCode: Long,
)

data class SettingsUiState(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val theme: AppThemeMode = AppThemeMode.SYSTEM,
    val versionInfo: AppVersionInfo? = null,
)

class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val packageManager: PackageManager,
    private val packageName: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.languageFlow.collect { language ->
                _uiState.update { it.copy(language = language) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.themeFlow.collect { theme ->
                _uiState.update { it.copy(theme = theme) }
            }
        }
        loadVersionInfo()
    }

    private fun loadVersionInfo() {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            _uiState.update {
                it.copy(
                    versionInfo = AppVersionInfo(
                        versionName = info.versionName ?: "—",
                        versionCode = versionCode,
                    ),
                )
            }
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            preferencesRepository.setLanguage(language)
            withContext(Dispatchers.Main) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
            }
        }
    }

    fun setTheme(theme: AppThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setTheme(theme)
            AppCompatDelegate.setDefaultNightMode(
                when (theme) {
                    AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                },
            )
        }
    }

    class Factory(
        private val preferencesRepository: PreferencesRepository,
        private val packageManager: PackageManager,
        private val packageName: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(preferencesRepository, packageManager, packageName) as T
    }
}
