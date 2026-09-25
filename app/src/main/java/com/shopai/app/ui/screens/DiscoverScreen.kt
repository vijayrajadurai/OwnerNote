package com.shopai.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.DiscoverItem
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.components.EyebrowLabel
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.VoiceFabBottomSpacer
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.ui.theme.Warning

private data class DiscoverCategory(val key: String, @StringRes val labelRes: Int)

private val categories = listOf(
    DiscoverCategory("ALL", R.string.filter_all),
    DiscoverCategory("PRODUCT", R.string.discover_filter_products),
    DiscoverCategory("OFFER", R.string.discover_filter_offers),
    DiscoverCategory("EVENT", R.string.discover_filter_events),
    DiscoverCategory("CELEBRATION", R.string.discover_filter_celebrations),
)

@Composable
fun DiscoverScreen(
    container: AppContainer,
    onNavigate: (String) -> Unit,
) {
    var items by remember { mutableStateOf<List<DiscoverItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf("ALL") }
    val errorLoadDiscover = stringResource(R.string.discover_error)

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            items = container.discoverRepository.getFeed()
        }.onFailure {
            container.presentApiError(it, errorLoadDiscover, { msg -> error = msg }, { msg -> alertError = msg })
        }
        loading = false
    }

    val filtered = items.filter { category == "ALL" || it.type == category }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    MainTabScaffold(activeTab = BottomNavTab.Discover, onNavigate = onNavigate) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.discover_title),
                style = MaterialTheme.typography.headlineMedium,
                color = ShopAiThemeColors.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.discover_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = category == cat.key,
                        onClick = { category = cat.key },
                        label = { Text(stringResource(cat.labelRes)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                loading -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = Primary)
                    }
                }
                error != null -> {
                    Text(error!!, color = Danger, style = MaterialTheme.typography.bodyMedium)
                }
                filtered.isEmpty() -> {
                    Text(
                        stringResource(R.string.discover_empty),
                        color = ShopAiThemeColors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        filtered.forEach { item ->
                            DiscoverFeedCard(item)
                        }
                    }
                }
            }
            VoiceFabBottomSpacer()
        }
    }
}

@Composable
private fun DiscoverFeedCard(item: DiscoverItem) {
    val typeColor = when (item.type) {
        "PRODUCT" -> Success
        "OFFER" -> Warning
        "EVENT" -> Primary
        "CELEBRATION" -> Danger
        else -> ShopAiThemeColors.onSurfaceVariant
    }
    val typeLabel = when (item.type) {
        "PRODUCT" -> stringResource(R.string.discover_type_product)
        "OFFER" -> stringResource(R.string.discover_type_offer)
        "EVENT" -> stringResource(R.string.discover_type_event)
        "CELEBRATION" -> stringResource(R.string.discover_type_celebration)
        else -> item.type
    }

    ShopCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EyebrowLabel(text = typeLabel)
            item.priceLabel?.let { price ->
                Text(
                    text = price,
                    style = MaterialTheme.typography.labelMedium,
                    color = typeColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = ShopAiThemeColors.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
        )
        item.detail?.let { detail ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = ShopAiThemeColors.onSurfaceVariant,
            )
        }
    }
}
