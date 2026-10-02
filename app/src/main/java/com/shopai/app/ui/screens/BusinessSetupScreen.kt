package com.shopai.app.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.BusinessCategory
import com.shopai.app.data.model.BusinessInput
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.CategoryChip
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ScreenContainer
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.ShopCoordinates
import com.shopai.app.util.currentShopCoordinates
import com.shopai.app.util.hasLocationPermission
import kotlinx.coroutines.launch
import java.time.Year

private const val SetupStepCount = 3

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusinessSetupScreen(
    container: AppContainer,
    onComplete: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var ownerName by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<BusinessCategory?>(null) }
    var city by remember { mutableStateOf("") }
    var pincode by remember { mutableStateOf("") }
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
    val thisYear = remember { Year.now().value }

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

    val step1Done = ownerName.trim().length >= 2 &&
        businessName.trim().length >= 2 &&
        category != null
    val step2Done = city.trim().length >= 2 &&
        Regex("^[1-9][0-9]{5}$").matches(pincode.trim()) &&
        coords != null
    val year = runningSinceYear.toIntOrNull()
    val step3Done = year != null && year in 1950..thisYear && monthlyVolume.trim().isNotEmpty()
    val canSave = step1Done && step2Done && step3Done

    BackHandler(enabled = step > 0) { step -= 1 }

    fun requestShopLocation() {
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
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    ScreenContainer {
        Text(
            text = stringResource(R.string.guide_step_of, step + 1, SetupStepCount),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.SemiBold,
            color = Primary,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (step + 1f) / SetupStepCount },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = Primary,
            trackColor = Primary.copy(alpha = 0.16f),
            strokeCap = StrokeCap.Round,
        )
        Text(
            text = stringResource(R.string.business_setup_title),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = OutfitFamily,
            color = ShopAiThemeColors.primary,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = stringResource(
                when (step) {
                    0 -> R.string.business_setup_step1_subtitle
                    1 -> R.string.business_setup_step2_subtitle
                    else -> R.string.business_setup_step3_subtitle
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        when (step) {
            0 -> {
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
            }
            1 -> {
                ShopTextField(
                    label = stringResource(R.string.business_city),
                    value = city,
                    onValueChange = { city = it },
                    placeholder = stringResource(R.string.business_city_placeholder),
                )
                ShopTextField(
                    label = stringResource(R.string.business_pincode),
                    value = pincode,
                    onValueChange = { pincode = it.filter { ch -> ch.isDigit() }.take(6) },
                    placeholder = stringResource(R.string.business_pincode_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                        onClick = { requestShopLocation() },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            else -> {
                ShopTextField(
                    label = stringResource(R.string.business_since_year),
                    value = runningSinceYear,
                    onValueChange = { runningSinceYear = it.filter { ch -> ch.isDigit() }.take(4) },
                    placeholder = stringResource(R.string.business_since_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                ShopTextField(
                    label = stringResource(R.string.business_monthly_volume),
                    value = monthlyVolume,
                    onValueChange = { monthlyVolume = it.filter { ch -> ch.isDigit() } },
                    placeholder = stringResource(R.string.business_volume_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        if (error != null) {
            Text(text = error!!, color = Danger, modifier = Modifier.padding(bottom = 12.dp))
        }

        Spacer(Modifier.height(8.dp))
        if (step < 2) {
            PrimaryButton(
                label = stringResource(R.string.guide_next),
                enabled = if (step == 0) step1Done else step2Done,
                onClick = {
                    error = null
                    step += 1
                },
            )
        } else {
            PrimaryButton(
                label = stringResource(R.string.business_save_continue),
                loading = loading,
                enabled = canSave,
                onClick = {
                    val selected = category ?: return@PrimaryButton
                    val shop = coords ?: return@PrimaryButton
                    scope.launch {
                        loading = true
                        error = null
                        runCatching {
                            val cityName = city.trim()
                            val pin = pincode.trim()
                            container.businessRepository.saveBusiness(
                                BusinessInput(
                                    ownerName = ownerName.trim(),
                                    businessName = businessName.trim(),
                                    category = selected.apiValue,
                                    city = cityName,
                                    runningSinceYear = year,
                                    monthlyVolumeApprox = monthlyVolume.toIntOrNull(),
                                    latitude = shop.latitude,
                                    longitude = shop.longitude,
                                    areaLabel = "$cityName, $pin",
                                    locationSource = "GPS",
                                ),
                            )
                            onComplete()
                        }.onFailure {
                            container.presentApiError(it, errorSaveBusiness, { msg -> error = msg }, { msg -> alertError = msg })
                        }
                        loading = false
                    }
                },
            )
        }
        if (step > 0) {
            OutlinedButton(
                onClick = { step -= 1 },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}
