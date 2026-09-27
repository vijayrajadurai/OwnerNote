package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.CreateLocalOfferInput
import com.shopai.app.data.model.LocalOffer
import com.shopai.app.data.model.OfferWithDistanceDto
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.offers.DEFAULT_OFFER_RADIUS_KM
import com.shopai.app.data.offers.DEFAULT_OFFER_TYPE
import com.shopai.app.data.offers.OFFER_CATEGORIES
import com.shopai.app.data.offers.OFFER_RADII_KM
import com.shopai.app.data.offers.OFFER_STATUS_ACTIVE
import com.shopai.app.data.offers.formatDistanceLabel
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.CategoryChip
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopAlertDialog
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import kotlinx.coroutines.launch

private enum class OffersTab { MINE, NEARBY }

@Composable
fun OffersScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(OffersTab.MINE) }
    var myOffers by remember { mutableStateOf<List<LocalOffer>>(emptyList()) }
    var nearbyOffers by remember { mutableStateOf<List<OfferWithDistanceDto>>(emptyList()) }
    var radiusKm by remember { mutableStateOf(DEFAULT_OFFER_RADIUS_KM) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var showNewOffer by remember { mutableStateOf(false) }
    var endTarget by remember { mutableStateOf<LocalOffer?>(null) }
    var ending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errorLoad = stringResource(R.string.offer_error_load)
    val errorSave = stringResource(R.string.offer_error_save)

    fun reloadMine() {
        scope.launch {
            loading = true
            error = null
            runCatching { myOffers = container.offersRepository.listMyOffers() }
                .onFailure { container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg }) }
            loading = false
        }
    }

    fun reloadNearby() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                val business = container.businessRepository.getMyBusiness()
                val latitude = business?.latitude
                val longitude = business?.longitude
                if (latitude == null || longitude == null) {
                    nearbyOffers = emptyList()
                } else {
                    nearbyOffers = container.offersRepository.getNearbyOffers(latitude, longitude, radiusKm.toDouble())
                }
            }.onFailure { container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg }) }
            loading = false
        }
    }

    fun reload() {
        if (tab == OffersTab.MINE) reloadMine() else reloadNearby()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tab, radiusKm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        reload()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    if (showNewOffer) {
        NewOfferDialog(
            onDismiss = { showNewOffer = false },
            onSave = { input ->
                scope.launch {
                    runCatching { container.offersRepository.createOffer(input) }
                        .onSuccess { showNewOffer = false; reloadMine() }
                        .onFailure {
                            container.presentApiError(it, errorSave, { msg -> alertError = msg }, { msg -> alertError = msg })
                        }
                }
            },
        )
    }

    endTarget?.let { target ->
        ShopAlertDialog(
            onDismissRequest = { if (!ending) endTarget = null },
            title = { Text(stringResource(R.string.offer_end_title)) },
            text = { Text(stringResource(R.string.offer_end_body)) },
            confirmButton = {
                TextButton(
                    enabled = !ending,
                    onClick = {
                        scope.launch {
                            ending = true
                            runCatching { container.offersRepository.endOffer(target.id) }
                                .onSuccess { endTarget = null; reloadMine() }
                                .onFailure {
                                    container.presentApiError(it, errorLoad, { msg -> alertError = msg }, { msg -> alertError = msg })
                                }
                            ending = false
                        }
                    },
                ) { Text(stringResource(R.string.offer_end_confirm)) }
            },
            dismissButton = {
                TextButton(enabled = !ending, onClick = { endTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    DetailScaffold(title = stringResource(R.string.offer_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.offer_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(
                    label = stringResource(R.string.offer_tab_mine),
                    selected = tab == OffersTab.MINE,
                    onClick = { tab = OffersTab.MINE },
                )
                CategoryChip(
                    label = stringResource(R.string.offer_tab_nearby),
                    selected = tab == OffersTab.NEARBY,
                    onClick = { tab = OffersTab.NEARBY },
                )
            }

            if (tab == OffersTab.MINE) {
                PrimaryButton(label = stringResource(R.string.offer_new_offer), onClick = { showNewOffer = true })
            } else {
                Text(
                    text = stringResource(R.string.offer_nearby_radius),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OFFER_RADII_KM.forEach { km ->
                        CategoryChip(
                            label = stringResource(R.string.offer_radius_km, km),
                            selected = km == radiusKm,
                            onClick = { radiusKm = km },
                        )
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(error!!, color = ShopAiThemeColors.onSurface)
                tab == OffersTab.MINE && myOffers.isEmpty() -> Text(
                    stringResource(R.string.offer_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
                tab == OffersTab.NEARBY && nearbyOffers.isEmpty() -> Text(
                    stringResource(R.string.offer_no_nearby),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
                tab == OffersTab.MINE -> myOffers.forEach { offer ->
                    ShopCard {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = offer.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = ShopAiThemeColors.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (offer.status == OFFER_STATUS_ACTIVE) {
                                    stringResource(R.string.offer_status_active)
                                } else {
                                    stringResource(R.string.offer_status_ended)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (offer.status == OFFER_STATUS_ACTIVE) Success else Danger,
                            )
                        }
                        Text(
                            text = offer.category,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.offer_stats_line, offer.viewCount, offer.directionsCount, offer.callCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (offer.status == OFFER_STATUS_ACTIVE) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { endTarget = offer }) {
                                Text(stringResource(R.string.offer_end_confirm))
                            }
                        }
                    }
                }
                else -> nearbyOffers.forEach { match ->
                    ShopCard {
                        Text(
                            text = match.offer.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ShopAiThemeColors.onSurface,
                        )
                        if (match.offer.businessName.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.offer_from_shop, match.offer.businessName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShopAiThemeColors.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.offer_distance_line,
                                formatDistanceLabel(match.distanceKm),
                                match.offer.timeWindowLabel,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NewOfferDialog(
    onDismiss: () -> Unit,
    onSave: (CreateLocalOfferInput) -> Unit,
) {
    var category by remember { mutableStateOf(OFFER_CATEGORIES.first()) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    ShopAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.offer_new_offer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShopTextField(label = stringResource(R.string.offer_category), value = category, onValueChange = { category = it })
                ShopTextField(label = stringResource(R.string.offer_offer_title_label), value = title, onValueChange = { title = it })
                ShopTextField(
                    label = stringResource(R.string.offer_description),
                    value = description,
                    onValueChange = { description = it },
                )
                ShopTextField(
                    label = stringResource(R.string.offer_price),
                    value = price,
                    onValueChange = { price = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (price.toDoubleOrNull() ?: -1.0) >= 0.0,
                onClick = {
                    onSave(
                        CreateLocalOfferInput(
                            category = category.trim(),
                            offerType = DEFAULT_OFFER_TYPE,
                            title = title.trim(),
                            description = description.trim().takeIf { it.isNotBlank() },
                            price = price.toDoubleOrNull() ?: 0.0,
                            todayOnly = true,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.offer_post)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
