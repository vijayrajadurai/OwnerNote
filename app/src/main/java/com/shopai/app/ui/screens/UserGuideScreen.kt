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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.theme.OnPrimary
import com.shopai.app.ui.theme.Primary
import kotlinx.coroutines.launch

private data class GuidePage(
    @DrawableRes val imageRes: Int,
    @StringRes val contentDescriptionRes: Int,
)

private val GuidePages = listOf(
    GuidePage(R.drawable.img_guide_funding, R.string.cd_guide_funding),
    GuidePage(R.drawable.img_guide_kai, R.string.cd_guide_kai),
    GuidePage(R.drawable.img_guide_group, R.string.cd_guide_group),
)

private val GuideBackground = Color(0xFFEEFDF7)

@Composable
fun UserGuideScreen(
    container: AppContainer,
    onNavigateOnboarding: () -> Unit,
    onNavigateHome: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { GuidePages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == GuidePages.lastIndex

    fun finishGuide() {
        scope.launch {
            container.preferencesRepository.setUserGuideCompleted()
            val business = runCatching { container.businessRepository.getMyBusiness() }.getOrNull()
            if (business == null) onNavigateOnboarding() else onNavigateHome()
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            val item = GuidePages[page]
            Image(
                painter = painterResource(item.imageRes),
                contentDescription = stringResource(item.contentDescriptionRes),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                GuidePages.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (index == pagerState.currentPage) 8.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) Primary
                                else Primary.copy(alpha = 0.25f),
                            ),
                    )
                }
            }

            if (lastPage) {
                PrimaryButton(
                    label = stringResource(R.string.guide_get_started),
                    onClick = { finishGuide() },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { finishGuide() }) {
                        Text(
                            text = stringResource(R.string.guide_skip),
                            color = Primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
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
