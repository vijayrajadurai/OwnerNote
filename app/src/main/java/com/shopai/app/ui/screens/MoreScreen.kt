package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.shopai.app.ui.components.isRedesignLight
import com.shopai.app.ui.more.MoreMenuItem
import com.shopai.app.ui.more.MoreViewModel
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.ShopAiThemeColors

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
            container.capturedDocumentRepository,
            container.handwrittenNotesRepository,
        ),
    )

    MainTabScaffold(activeTab = BottomNavTab.More, onNavigate = onNavigate) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
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
    val light = isRedesignLight()
    val shape = RoundedCornerShape(28.dp)
    val brush = if (light) {
        Brush.horizontalGradient(listOf(Color(0xFFE8F8F0), Color(0xFFF7FFFB)))
    } else {
        Brush.horizontalGradient(
            listOf(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush)
            .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f).padding(bottom = 10.dp)) {
            Text(
                text = stringResource(R.string.more_eyebrow),
                style = MaterialTheme.typography.labelMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.more_headline),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = OutfitFamily,
                fontWeight = FontWeight.Bold,
                color = ShopAiThemeColors.onSurface,
                lineHeight = 34.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.more_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier.size(width = 132.dp, height = 148.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Image(
                painter = painterResource(R.drawable.kai_joyful),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(148.dp)
                    .offset(y = 8.dp),
            )
        }
    }
}
