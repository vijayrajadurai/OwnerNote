package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.theme.OnPrimary
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.PrimaryLight

@Composable
fun SplashScreen(
    container: AppContainer,
    onNavigateLogin: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateBusinessSetup: () -> Unit,
) {
    LaunchedEffect(Unit) {
        val token = container.authRepository.hydrate()
        if (token == null) {
            onNavigateLogin()
            return@LaunchedEffect
        }
        runCatching { container.pushTokenRepository.registerCurrent() }
        val business = runCatching {
            container.businessRepository.getMyBusiness()
        }.getOrElse {
            onNavigateLogin()
            return@LaunchedEffect
        }
        if (business != null) onNavigateHome() else onNavigateBusinessSetup()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primary)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.brand_name),
            color = OnPrimary,
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
        )
        Text(
            text = stringResource(R.string.splash_tagline),
            color = PrimaryLight,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
