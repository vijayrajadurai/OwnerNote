package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.kai.KaiContentCard
import com.shopai.app.ui.more.MoreMenuItem
import com.shopai.app.ui.more.MoreViewModel
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.TextPrimary
import com.shopai.app.ui.theme.TextSecondary

@Composable
fun MoreScreen(
    container: AppContainer,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel: MoreViewModel = viewModel(
        factory = MoreViewModel.Factory(
            container.authRepository,
            container.reminderAlarms,
            container.kaiReminders,
            container.kaiActionLog,
            container.capturedDocumentRepository,
            container.handwrittenNotesRepository,
        ),
    )

    MainTabScaffold(activeTab = BottomNavTab.More, onNavigate = onNavigate) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MoreHeroHeader()
            }
            items(viewModel.primaryItems, key = { it.id }) { item ->
                MoreMenuItem(item = item, onClick = { onNavigate(item.route) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.more_tools_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = ShopAiThemeColors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(viewModel.toolItems, key = { it.id }) { item ->
                MoreMenuItem(item = item, onClick = { onNavigate(item.route) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    onClick = { viewModel.logout(onLogout) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.logout),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreHeroHeader() {
    KaiContentCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f).padding(bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.more_eyebrow),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.more_headline),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = OutfitFamily,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    lineHeight = 34.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.more_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Image(
                painter = painterResource(R.drawable.kai_joyful),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 108.dp, height = 124.dp),
            )
        }
    }
}
