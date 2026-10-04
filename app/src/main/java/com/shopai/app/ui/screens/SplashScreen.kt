package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.AuthBackgroundBrush
import com.shopai.app.ui.components.AuthBrandHeader
import com.shopai.app.ui.components.PoweredByNewonX
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.PrimaryLight
import com.shopai.app.ui.theme.TextSecondary
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
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(100.dp))
        AuthBrandHeader(
            compact = true,
            tagline = stringResource(R.string.splash_tagline),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val kaiHeight = LocalConfiguration.current.screenHeightDp.dp / 2
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Image(
                    painter = painterResource(R.drawable.kai_point),
                    contentDescription = stringResource(R.string.kai_content_description),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(kaiHeight),
                )
                Box(
                    modifier = Modifier
                        .offset(y = (-14).dp)
                        .width(kaiHeight * 0.88f)
                        .height(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .graphicsLayer { scaleY = 0.5f }
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xCC0A2E20),
                                        Color(0x99154F39),
                                        Color(0x551E6B4E),
                                        Color(0x00154F39),
                                    ),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(22.dp)
                            .graphicsLayer { scaleY = 0.42f }
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xE60A2E20),
                                        Color(0x99154F39),
                                        Color(0x00154F39),
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.splash_loading),
            fontFamily = OutfitFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = Primary,
            trackColor = PrimaryLight,
        )
        PoweredByNewonX(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
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
