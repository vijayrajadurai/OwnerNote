package com.shopai.app.ui.screens

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.Business
import com.shopai.app.data.model.BusinessCategory
import com.shopai.app.data.model.BusinessInput
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.CategoryChip
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.ShopCoordinates
import com.shopai.app.util.copyPickedImageInto
import com.shopai.app.util.currentShopCoordinates
import com.shopai.app.util.displayIndianPhone
import com.shopai.app.util.hasLocationPermission
import com.shopai.app.util.partyInitialLetter
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<Business?>(null) }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var ownerName by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<BusinessCategory?>(null) }
    var city by remember { mutableStateOf("") }
    var runningSinceYear by remember { mutableStateOf("") }
    var monthlyVolume by remember { mutableStateOf("") }
    var coords by remember { mutableStateOf<ShopCoordinates?>(null) }
    var changingLocation by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val errorLoad = stringResource(R.string.error_load_profile)
    val errorSave = stringResource(R.string.error_save_business)
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

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val previousPath = photoPath
            val destination = container.profilePhotoStore.newPhotoFile()
            val ok = copyPickedImageInto(context, uri, destination)
            if (ok) {
                container.profilePhotoStore.setPhotoPath(destination.absolutePath)
                photoPath = destination.absolutePath
                previousPath?.let { runCatching { java.io.File(it).delete() } }
            }
        }
    }

    LaunchedEffect(Unit) {
        photoPath = container.profilePhotoStore.getPhotoPath()
        runCatching { container.businessRepository.getMyBusiness() }
            .onSuccess { business ->
                existing = business
                if (business != null) {
                    ownerName = business.ownerName
                    businessName = business.businessName
                    category = BusinessCategory.fromApi(business.category)
                    city = business.city
                    runningSinceYear = business.runningSinceYear?.toString().orEmpty()
                    monthlyVolume = business.monthlyVolumeApprox.orEmpty().filter { it.isDigit() }
                    phone = displayIndianPhone(business.phone)
                    val lat = business.latitude
                    val lon = business.longitude
                    if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
                        coords = ShopCoordinates(lat, lon)
                        changingLocation = false
                    } else {
                        changingLocation = true
                    }
                }
            }
            .onFailure {
                container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
            }
        if (phone.isBlank()) {
            phone = displayIndianPhone(container.authRepository.getLoginPhone())
        }
        loaded = true
    }

    val isValid = ownerName.trim().length >= 2 &&
        businessName.trim().length >= 2 &&
        category != null &&
        city.trim().length >= 2 &&
        coords != null

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(title = stringResource(R.string.profile_title), onBack = onBack) { contentModifier ->
        if (!loaded) {
            Box(
                modifier = contentModifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ShopAiThemeColors.primary)
            }
            return@DetailScaffold
        }

        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            ) {
                val photoBitmap = remember(photoPath) {
                    photoPath?.let { path -> runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
                }
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (photoBitmap != null) {
                        Image(
                            bitmap = photoBitmap,
                            contentDescription = stringResource(R.string.profile_photo_change),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = partyInitialLetter(ownerName.ifBlank { stringResource(R.string.home_fallback_name) }),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.profile_photo_change),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                )
            }

            ShopTextField(
                label = stringResource(R.string.business_owner_name),
                value = ownerName,
                onValueChange = { ownerName = it },
                placeholder = stringResource(R.string.business_owner_placeholder),
            )
            ShopTextField(
                label = stringResource(R.string.login_phone_label),
                value = phone,
                onValueChange = {},
                placeholder = stringResource(R.string.login_phone_placeholder),
                readOnly = true,
                enabled = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Text(
                text = stringResource(R.string.profile_phone_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
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
                label = stringResource(R.string.profile_save),
                loading = loading,
                enabled = isValid && !loading,
                onClick = {
                    val selected = category ?: return@PrimaryButton
                    val current = existing
                    val shop = coords
                    scope.launch {
                        loading = true
                        error = null
                        runCatching {
                            existing = container.businessRepository.saveBusiness(
                                BusinessInput(
                                    ownerName = ownerName.trim(),
                                    businessName = businessName.trim(),
                                    category = selected.apiValue,
                                    city = city.trim(),
                                    runningSinceYear = runningSinceYear.toIntOrNull(),
                                    monthlyVolumeApprox = monthlyVolume.toIntOrNull(),
                                    latitude = shop?.latitude ?: current?.latitude,
                                    longitude = shop?.longitude ?: current?.longitude,
                                    areaLabel = current?.areaLabel ?: city.trim(),
                                    locationSource = if (shop != null) "GPS" else current?.locationSource,
                                ),
                            )
                            onBack()
                        }.onFailure {
                            container.presentApiError(it, errorSave, { msg -> error = msg }, { msg -> alertError = msg })
                        }
                        loading = false
                    }
                },
            )
        }
    }
}
