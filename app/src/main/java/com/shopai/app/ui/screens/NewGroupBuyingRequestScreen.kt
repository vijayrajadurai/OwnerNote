package com.shopai.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.BusinessInput
import com.shopai.app.data.model.CreateGroupBuyingRequestInput
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.CategoryChip
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.ShopCoordinates
import com.shopai.app.util.currentShopCoordinates
import com.shopai.app.util.hasLocationPermission
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class GroupBuyingProduct(@StringRes val labelRes: Int, val id: String)

private val products = listOf(
    GroupBuyingProduct(R.string.gb_product_cement, "cement"),
    GroupBuyingProduct(R.string.gb_product_paint, "paint"),
    GroupBuyingProduct(R.string.gb_product_steel, "steel"),
    GroupBuyingProduct(R.string.gb_product_rice, "rice"),
    GroupBuyingProduct(R.string.gb_product_oil, "oil"),
    GroupBuyingProduct(R.string.gb_product_tiles, "tiles"),
)

private val units = listOf("bags", "kg", "litres", "pieces")
private val radiiKm = listOf(2.0, 5.0, 10.0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewGroupBuyingRequestScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val context = LocalContext.current
    var productId by remember { mutableStateOf("cement") }
    var quantity by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("bags") }
    var requiredDate by remember { mutableStateOf<LocalDate?>(null) }
    var radiusKm by remember { mutableStateOf(5.0) }
    var areaLabel by remember { mutableStateOf("") }
    var coords by remember { mutableStateOf<ShopCoordinates?>(null) }
    var locationSource by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val errorSave = stringResource(R.string.gb_error_save)
    val errorLocation = stringResource(R.string.gb_error_location)

    fun applyCoordinates(value: ShopCoordinates, source: String) {
        coords = value
        locationSource = source
        error = null
    }

    fun readDeviceLocation() {
        scope.launch {
            locating = true
            val found = currentShopCoordinates(context)
            if (found != null) applyCoordinates(found, "GPS") else if (coords == null) error = errorLocation
            locating = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) readDeviceLocation() else error = errorLocation
    }

    LaunchedEffect(Unit) {
        runCatching { container.businessRepository.getMyBusiness() }.onSuccess { business ->
            if (areaLabel.isBlank()) {
                areaLabel = business?.areaLabel?.takeIf { it.isNotBlank() } ?: business?.city.orEmpty()
            }
            val lat = business?.latitude
            val lon = business?.longitude
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
                applyCoordinates(ShopCoordinates(lat, lon), business.locationSource ?: "MANUAL")
            } else if (hasLocationPermission(context)) {
                readDeviceLocation()
            }
        }
    }

    val qty = quantity.toDoubleOrNull()
    val location = coords
    val isValid = qty != null && qty > 0 && requiredDate != null &&
        areaLabel.trim().length >= 2 && location != null

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(title = stringResource(R.string.gb_new_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.gb_new_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Text(
                stringResource(R.string.gb_product),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                products.forEach { product ->
                    CategoryChip(
                        label = stringResource(product.labelRes),
                        selected = productId == product.id,
                        onClick = { productId = product.id },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            ShopTextField(
                label = stringResource(R.string.gb_quantity),
                value = quantity,
                onValueChange = { quantity = it.filter { ch -> ch.isDigit() || ch == '.' } },
                placeholder = "20",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            Text(
                stringResource(R.string.gb_unit),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                units.forEach { option ->
                    CategoryChip(label = option, selected = unit == option, onClick = { unit = option })
                }
            }
            Spacer(Modifier.height(8.dp))

            FutureDatePickerField(
                label = stringResource(R.string.gb_required_date),
                selectedDate = requiredDate,
                onDateSelected = { requiredDate = it },
                placeholder = stringResource(R.string.due_date_placeholder),
                allowEmpty = false,
            )

            Text(
                stringResource(R.string.gb_radius),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                radiiKm.forEach { option ->
                    CategoryChip(
                        label = stringResource(R.string.gb_radius_km, option.toInt()),
                        selected = radiusKm == option,
                        onClick = { radiusKm = option },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            ShopTextField(
                label = stringResource(R.string.gb_area),
                value = areaLabel,
                onValueChange = { areaLabel = it },
                placeholder = stringResource(R.string.gb_area_placeholder),
            )

            Text(
                text = if (location != null) {
                    stringResource(R.string.gb_location_ready)
                } else {
                    stringResource(R.string.gb_location_needed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PrimaryButton(
                label = stringResource(R.string.gb_use_location),
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

            if (error != null) {
                Text(error!!, color = Danger, modifier = Modifier.padding(top = 12.dp))
            }

            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                label = stringResource(R.string.gb_post_request),
                loading = loading,
                enabled = isValid,
                onClick = {
                    val selectedDate = requiredDate ?: return@PrimaryButton
                    val amount = qty ?: return@PrimaryButton
                    val shop = location ?: return@PrimaryButton
                    scope.launch {
                        loading = true
                        error = null
                        runCatching {
                            persistShopLocation(container, shop, areaLabel.trim(), locationSource)
                            container.groupBuyingRepository.createRequest(
                                CreateGroupBuyingRequestInput(
                                    productId = productId,
                                    quantity = amount,
                                    unit = unit,
                                    requiredDate = selectedDate.toString(),
                                    latitude = shop.latitude,
                                    longitude = shop.longitude,
                                    areaLabel = areaLabel.trim(),
                                    radiusKm = radiusKm,
                                ),
                            )
                        }.onSuccess { created ->
                            onCreated(created.id)
                        }.onFailure {
                            container.presentApiError(it, errorSave, { msg -> error = msg }, { msg -> alertError = msg })
                        }
                        loading = false
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private suspend fun persistShopLocation(
    container: AppContainer,
    shop: ShopCoordinates,
    areaLabel: String,
    source: String?,
) {
    val business = container.businessRepository.getMyBusiness() ?: return
    runCatching {
        container.businessRepository.saveBusiness(
            BusinessInput(
                ownerName = business.ownerName,
                businessName = business.businessName,
                category = business.category,
                city = business.city,
                runningSinceYear = business.runningSinceYear,
                monthlyVolumeApprox = business.monthlyVolumeApprox?.toDoubleOrNull()?.toInt(),
                latitude = shop.latitude,
                longitude = shop.longitude,
                areaLabel = areaLabel,
                locationSource = source ?: "GPS",
            ),
        )
    }
}
