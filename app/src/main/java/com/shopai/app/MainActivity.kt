package com.shopai.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.shopai.app.data.local.AppThemeMode
import com.shopai.app.ui.ShopAiApp
import com.shopai.app.ui.theme.ShopAiTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ShopAiApplication).container
        setContent {
            val themeMode by container.preferencesRepository.themeFlow
                .collectAsState(initial = AppThemeMode.SYSTEM)
            ShopAiTheme(themeMode = themeMode) {
                ShopAiApp(container = container)
            }
        }
    }
}
