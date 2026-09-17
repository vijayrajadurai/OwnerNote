package com.shopai.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.shopai.app.data.AppContainer
import com.shopai.app.data.local.AppThemeMode
import kotlinx.coroutines.runBlocking

class ShopAiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.naturalTtsSpeaker.warmUp()
        runBlocking {
            val language = container.preferencesRepository.getLanguage()
            val theme = container.preferencesRepository.getTheme()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
            AppCompatDelegate.setDefaultNightMode(
                when (theme) {
                    AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                },
            )
        }
    }
}
