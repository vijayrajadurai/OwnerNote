package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.AuthBackgroundBrush
import com.shopai.app.ui.components.AuthBrandHeader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private const val SplashHoldMs = 3_000L

private sealed interface SplashDest {
    data object Login : SplashDest
    data object Home : SplashDest
    data object UserGuide : SplashDest
    data object BusinessSetup : SplashDest
}

@Composable
fun SplashScreen(
    container: AppContainer,
    onNavigateLogin: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateUserGuide: () -> Unit,
    onNavigateBusinessSetup: () -> Unit,
) {
    LaunchedEffect(Unit) {
        val dest = coroutineScope {
            val resolved = async { resolveSplashDest(container) }
            delay(SplashHoldMs)
            resolved.await()
        }
        when (dest) {
            SplashDest.Login -> onNavigateLogin()
            SplashDest.Home -> onNavigateHome()
            SplashDest.UserGuide -> onNavigateUserGuide()
            SplashDest.BusinessSetup -> onNavigateBusinessSetup()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AuthBackgroundBrush)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(28.dp))
        AuthBrandHeader(tagline = stringResource(R.string.splash_tagline))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Image(
                painter = painterResource(R.drawable.kai_point),
                contentDescription = stringResource(R.string.kai_content_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private suspend fun resolveSplashDest(container: AppContainer): SplashDest {
    val token = container.authRepository.hydrate()
    if (token == null) return SplashDest.Login
    runCatching { container.pushTokenRepository.registerCurrent() }
    if (!container.preferencesRepository.hasCompletedUserGuide()) return SplashDest.UserGuide
    val business = runCatching {
        container.businessRepository.getMyBusiness()
    }.getOrElse { return SplashDest.Login }
    return if (business != null) SplashDest.Home else SplashDest.BusinessSetup
}
