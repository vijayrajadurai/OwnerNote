package com.shopai.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.model.BusinessCategory
import com.shopai.app.data.model.BusinessInput
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.CategoryChip
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ScreenContainer
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.ShopCoordinates
import com.shopai.app.util.currentShopCoordinates
import com.shopai.app.util.hasLocationPermission
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusinessSetupScreen(
    container: AppContainer,
    onComplete: () -> Unit,
) {
    var ownerName by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<BusinessCategory?>(null) }
    var city by remember { mutableStateOf("") }
    var runningSinceYear by remember { mutableStateOf("") }
    var monthlyVolume by remember { mutableStateOf("") }
    var coords by remember { mutableStateOf<ShopCoordinates?>(null) }
    var changingLocation by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val errorSaveBusiness = stringResource(R.string.error_save_business)
    val errorLocation = stringResource(R.string.business_location_needed)

    fun readDeviceLocation() {
        scope.launch {
            locating = true
            val found = currentShopCoordinates(context)
            if (found != null) {
                coords = found
                changingLocation = false
                error = null
            } else if (coords == null) {
                error = errorLocation
            }
            locating = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) readDeviceLocation() else error = errorLocation
    }

    LaunchedEffect(Unit) {
        if (hasLocationPermission(context)) readDeviceLocation()
    }

    val isValid = ownerName.trim().length >= 2 &&
        businessName.trim().length >= 2 &&
        category != null &&
        city.trim().length >= 2 &&
        coords != null

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    ScreenContainer {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.business_setup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = ShopAiThemeColors.primary,
            )
            Text(
                text = stringResource(R.string.business_setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        ShopTextField(
            label = stringResource(R.string.business_owner_name),
            value = ownerName,
            onValueChange = { ownerName = it },
            placeholder = stringResource(R.string.business_owner_placeholder),
        )
        ShopTextField(
            label = stringResource(R.string.business_name),
            value = businessName,
            onValueChange = { businessName = it },
            placeholder = stringResource(R.string.business_name_placeholder),
        )

        Text(
            text = stringResource(R.string.business_category),
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 16.dp),
        ) {
            BusinessCategory.entries.forEach { cat ->
                CategoryChip(
                    label = stringResource(cat.labelRes),
                    selected = category == cat,
                    onClick = { category = cat },
                )
            }
        }

        ShopTextField(
            label = stringResource(R.string.business_city),
            value = city,
            onValueChange = { city = it },
            placeholder = stringResource(R.string.business_city_placeholder),
        )
        if (coords != null && !changingLocation) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.business_location_marked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(end = 8.dp, top = 12.dp),
                )
                TextButton(onClick = { changingLocation = true }) {
                    Text(stringResource(R.string.business_location_change))
                }
            }
        } else {
            Text(
                text = if (coords != null) {
                    stringResource(R.string.business_location_ready)
                } else {
                    stringResource(R.string.business_location_needed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PrimaryButton(
                label = stringResource(R.string.business_use_location),
                loading = locating,
                onClick = {
                    if (hasLocationPermission(context)) {
                        readDeviceLocation()
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
            )
        }
        ShopTextField(
            label = stringResource(R.string.business_since_year),
            value = runningSinceYear,
            onValueChange = { runningSinceYear = it.filter { ch -> ch.isDigit() }.take(4) },
            placeholder = stringResource(R.string.business_since_placeholder),
        )
        ShopTextField(
            label = stringResource(R.string.business_monthly_volume),
            value = monthlyVolume,
            onValueChange = { monthlyVolume = it.filter { ch -> ch.isDigit() } },
            placeholder = stringResource(R.string.business_volume_placeholder),
        )

        if (error != null) {
            Text(text = error!!, color = Danger, modifier = Modifier.padding(bottom = 12.dp))
        }

        PrimaryButton(
            label = stringResource(R.string.business_save_continue),
            loading = loading,
            enabled = isValid,
            onClick = {
                val selected = category ?: return@PrimaryButton
                scope.launch {
                    loading = true
                    error = null
                    val shop = coords
                    if (shop == null) {
                        error = errorLocation
                    } else {
                        runCatching {
                            container.businessRepository.saveBusiness(
                                BusinessInput(
                                    ownerName = ownerName.trim(),
                                    businessName = businessName.trim(),
                                    category = selected.apiValue,
                                    city = city.trim(),
                                    runningSinceYear = runningSinceYear.toIntOrNull(),
                                    monthlyVolumeApprox = monthlyVolume.toIntOrNull(),
                                    latitude = shop.latitude,
                                    longitude = shop.longitude,
                                    areaLabel = city.trim(),
                                    locationSource = "GPS",
                                ),
                            )
                            onComplete()
                        }.onFailure {
                            container.presentApiError(it, errorSaveBusiness, { msg -> error = msg }, { msg -> alertError = msg })
                        }
                    }
                    loading = false
                }
            },
        )
    }
}
