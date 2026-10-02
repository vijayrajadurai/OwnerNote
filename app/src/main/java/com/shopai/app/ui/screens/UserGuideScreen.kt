package com.shopai.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.theme.OnPrimary
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.TextPrimary
import com.shopai.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private data class GuidePage(
    @DrawableRes val imageRes: Int,
    @StringRes val titleRes: Int,
)

private val GuidePages = listOf(
    GuidePage(R.drawable.img_guide_funding, R.string.cd_guide_funding),
    GuidePage(R.drawable.img_guide_kai, R.string.cd_guide_kai),
    GuidePage(R.drawable.img_guide_group, R.string.cd_guide_group),
)

private const val WelcomePageIndex = 3
private val GuideBackground = Color(0xFFEEFDF7)

@Composable
fun UserGuideScreen(
    container: AppContainer,
    onNavigateOnboarding: () -> Unit,
    onNavigateHome: () -> Unit,
) {
    val pageCount = GuidePages.size + 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val onWelcome = pagerState.currentPage == WelcomePageIndex
    val stepIndex = pagerState.currentPage.coerceAtMost(GuidePages.lastIndex)
    val progress = if (onWelcome) 1f else (stepIndex + 1f) / GuidePages.size

    fun finishGuide() {
        scope.launch {
            container.preferencesRepository.setUserGuideCompleted()
            val business = runCatching { container.businessRepository.getMyBusiness() }.getOrNull()
            if (business == null) onNavigateOnboarding() else onNavigateHome()
        }
    }

    fun goNext() {
        scope.launch {
            if (pagerState.currentPage < pageCount - 1) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            } else {
                finishGuide()
            }
        }
    }

    BackHandler {
        if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GuideBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = if (onWelcome) {
                    stringResource(R.string.guide_welcome_title)
                } else {
                    stringResource(R.string.guide_step_of, stepIndex + 1, GuidePages.size)
                },
                style = MaterialTheme.typography.labelLarge,
                fontFamily = OutfitFamily,
                fontWeight = FontWeight.SemiBold,
                color = Primary,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = Primary,
                trackColor = Primary.copy(alpha = 0.16f),
                strokeCap = StrokeCap.Round,
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            if (page == WelcomePageIndex) {
                GuideWelcomePage()
            } else {
                val item = GuidePages[page]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(item.imageRes),
                        contentDescription = stringResource(item.titleRes),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp)),
                    )
                    Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = OutfitFamily,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!onWelcome) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    GuidePages.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .size(if (index == stepIndex) 8.dp else 7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index <= stepIndex) Primary
                                    else Primary.copy(alpha = 0.22f),
                                ),
                        )
                    }
                }
            }

            if (onWelcome) {
                PrimaryButton(
                    label = stringResource(R.string.guide_get_started),
                    onClick = { finishGuide() },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(WelcomePageIndex) }
                    }) {
                        Text(
                            text = stringResource(R.string.guide_skip),
                            color = Primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { goNext() },
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = OnPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.guide_next),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideWelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(88.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.guide_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.guide_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
