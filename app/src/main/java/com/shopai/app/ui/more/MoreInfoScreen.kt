package com.shopai.app.ui.more

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard

@Composable
fun MoreInfoScreen(
    page: String,
    onBack: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spec = moreInfoSpec(page)

    DetailScaffold(title = stringResource(spec.titleRes), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShopCard {
                Text(
                    text = stringResource(spec.bodyRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            when (page) {
                MoreMenuPages.Notifications -> {
                    PrimaryButton(
                        label = stringResource(R.string.more_reminders),
                        onClick = onOpenReminders,
                    )
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.more_settings))
                    }
                }
                else -> Unit
            }
        }
    }
}

private data class MoreInfoSpec(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

private fun moreInfoSpec(page: String): MoreInfoSpec = when (page) {
    MoreMenuPages.Help -> MoreInfoSpec(R.string.more_help_support, R.string.more_help_body)
    MoreMenuPages.About -> MoreInfoSpec(R.string.more_about_us, R.string.more_about_body)
    MoreMenuPages.Privacy -> MoreInfoSpec(R.string.privacy_policy, R.string.privacy_policy_full)
    MoreMenuPages.Notifications -> MoreInfoSpec(R.string.more_notifications, R.string.more_notifications_body)
    else -> MoreInfoSpec(R.string.more_title, R.string.more_help_body)
}
