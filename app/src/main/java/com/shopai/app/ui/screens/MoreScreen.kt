package com.shopai.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.VoiceFabBottomSpacer
import com.shopai.app.ui.navigation.Routes
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.launch

private data class MenuItem(
    @StringRes val labelRes: Int,
    @StringRes val captionRes: Int,
    val route: String,
)

@Composable
fun MoreScreen(
    container: AppContainer,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val menuItems = listOf(
        MenuItem(R.string.more_ai_insights, R.string.more_ai_insights_caption, Routes.AiInsights),
        MenuItem(R.string.profile_title, R.string.profile_subtitle, Routes.Profile),
        MenuItem(R.string.more_group_buying, R.string.more_group_buying_caption, Routes.GroupBuying),
        MenuItem(R.string.more_reminders, R.string.more_reminders_caption, Routes.Reminders),
        MenuItem(R.string.more_voice, R.string.more_voice_caption, Routes.VoiceEntry),
        MenuItem(R.string.more_settings, R.string.more_settings_caption, Routes.Settings),
        MenuItem(R.string.more_subscription, R.string.more_subscription_caption, Routes.Subscription),
    )

    MainTabScaffold(activeTab = BottomNavTab.More, onNavigate = onNavigate) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.more_title),
                style = MaterialTheme.typography.headlineMedium,
                color = ShopAiThemeColors.onSurface,
                fontWeight = FontWeight.Bold,
            )

            menuItems.forEach { item ->
                ShopCard(modifier = Modifier.clickable { onNavigate(item.route) }) {
                    Text(
                        stringResource(item.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = ShopAiThemeColors.onSurface,
                    )
                    Text(
                        stringResource(item.captionRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShopAiThemeColors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            PrimaryButton(
                label = stringResource(R.string.logout),
                onClick = {
                    scope.launch {
                        container.authRepository.logout()
                        onLogout()
                    }
                },
            )
            VoiceFabBottomSpacer()
        }
    }
}
