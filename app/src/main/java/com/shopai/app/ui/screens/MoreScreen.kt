package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.more.MoreMenuItem
import com.shopai.app.ui.more.MoreViewModel
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
            contentPadding = PaddingValues(bottom = 168.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.more_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = ShopAiThemeColors.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
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
                PrimaryButton(
                    label = stringResource(R.string.logout),
                    onClick = { viewModel.logout(onLogout) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}
