package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.PrimaryButton

// Sampled from the welcome artwork's own background so the real button
// area below the cropped image has no visible seam.
private val WelcomeBackground = Color(0xFFEEFDF7)

@Composable
fun SplashScreen(
    container: AppContainer,
    onNavigateLogin: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateBusinessSetup: () -> Unit,
) {
    // While the saved session is being checked, "Get Started" waits (a tap then
    // would race the automatic navigation and could open Login for a signed-in owner).
    var checking by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val token = container.authRepository.hydrate()
        if (token == null) {
            // No session — stay on the welcome screen; the owner taps
            // "Get Started" themselves instead of being redirected.
            checking = false
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
            .background(WelcomeBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Image(
            painter = painterResource(R.drawable.img_splash_welcome),
            contentDescription = stringResource(R.string.brand_name),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            PrimaryButton(
                label = stringResource(R.string.splash_get_started),
                onClick = onNavigateLogin,
                enabled = !checking,
                loading = checking,
            )
        }
    }
}
