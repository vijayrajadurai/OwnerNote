package com.shopai.app.ui.kaichat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.shopai.app.R
import com.shopai.app.brain.chat.ProductForm
import com.shopai.app.brain.chat.StockPrefill
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.CameraPermissionFlow
import com.shopai.app.util.CameraStep
import com.shopai.app.util.DeviceTextRecognizer
import com.shopai.app.util.ProductLabelReader
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.util.UUID

/** A fixed cache file for the product photo (full size — a thumbnail is too small to read). */
private fun productPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "capture").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, "product.jpg"))
}

/** Keeps the product's photo with the app (the cache file is reused by the next photo). */
private fun keepPhoto(context: Context, uri: Uri): String? = runCatching {
    val dir = File(context.filesDir, "product_photos").apply { mkdirs() }
    val out = File(dir, UUID.randomUUID().toString() + ".jpg")
    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
    Uri.fromFile(out).toString()
}.getOrNull()

/**
 * STOCK_IN_CAMERA: the camera opens at once ([openCamera]); the phone reads
 * the label on-device (no network) and fills an editable form — name,
 * category, variant, quantity, unit, weight, brand. Nothing is saved until
 * the owner presses Confirm Stock In; then the product is created and its
 * stock goes in through the existing inventory engine (via Kai).
 */
@Composable
fun StockCaptureSheet(
    prefill: StockPrefill,
    openCamera: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ProductForm) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { DeviceTextRecognizer(context) }
    DisposableEffect(Unit) { onDispose { recognizer.release() } }

    var name by rememberSaveable { mutableStateOf(prefill.name) }
    var category by rememberSaveable { mutableStateOf("") }
    var variant by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var pack by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf(prefill.qty?.stripTrailingZeros()?.toPlainString() ?: "") }
    var unit by rememberSaveable { mutableStateOf(prefill.unit ?: "PCS") }
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val readFailed = stringResource(R.string.kai_stock_photo_unreadable)
    val photoUri = remember { productPhotoUri(context) }

    fun read(uri: Uri) {
        scope.launch {
            reading = true
            note = null
            imageUri = keepPhoto(context, uri) ?: imageUri
            val result = runCatching { recognizer.recognizeFromUri(uri) }.getOrNull()
            if (result == null || !result.success || result.text.isBlank()) {
                note = readFailed
            } else {
                val label = ProductLabelReader.read(result.text)
                // What the owner said wins over the photo; the photo only fills what is empty.
                if (name.isBlank()) label.name?.let { name = it }
                if (brand.isBlank()) label.brand?.let { brand = it }
                if (variant.isBlank()) label.variant?.let { variant = it }
                if (weight.isBlank()) label.weight?.let { weight = it }
                if (category.isBlank()) label.category?.let { category = it }
                if (pack.isBlank()) label.packCount?.let { pack = "Pack of $it" }
            }
            reading = false
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> if (taken) read(photoUri) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when (CameraPermissionFlow.onResult(granted, permissionDenied)) {
            CameraStep.OPEN_CAMERA -> {
                permissionDenied = false
                runCatching { camera.launch(photoUri) }
            }
            CameraStep.OPEN_SETTINGS -> runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            else -> permissionDenied = true
        }
    }
    fun takePhoto() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        when (CameraPermissionFlow.onTap(granted)) {
            CameraStep.OPEN_CAMERA -> runCatching { camera.launch(photoUri) }
            else -> permission.launch(Manifest.permission.CAMERA)
        }
    }
    // Straight to the camera (once — not again when the screen is recreated).
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (openCamera && !opened) {
            opened = true
            takePhoto()
        }
    }

    val parsedQty = qty.replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kai_stock_new_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { takePhoto() }, enabled = !reading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (imageUri == null) stringResource(R.string.kai_stock_take_photo) else stringResource(R.string.kai_stock_retake_photo))
                }
                if (permissionDenied) {
                    Text(stringResource(R.string.kai_stock_camera_permission), color = Danger)
                    OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.allow_camera))
                    }
                }
                if (reading) Text(stringResource(R.string.ocr_status_processing), color = ShopAiThemeColors.onSurfaceVariant)
                note?.let { Text(it, color = ShopAiThemeColors.onSurfaceVariant) }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.kai_stock_name)) }, singleLine = true, isError = name.isBlank())
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text(stringResource(R.string.kai_stock_category)) }, singleLine = true)
                OutlinedTextField(value = variant, onValueChange = { variant = it }, label = { Text(stringResource(R.string.kai_stock_variant)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(stringResource(R.string.kai_stock_qty)) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text(stringResource(R.string.kai_stock_weight)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("PCS", "BOX", "PACK", "KG", "LITRE").forEach { u ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                    }
                }
                OutlinedTextField(value = pack, onValueChange = { pack = it }, label = { Text(stringResource(R.string.kai_stock_pack)) }, singleLine = true)
                OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { Text(stringResource(R.string.kai_stock_brand)) }, singleLine = true)
                Text(stringResource(R.string.kai_stock_check_note), color = ShopAiThemeColors.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !reading,
                onClick = {
                    onConfirm(
                        ProductForm(
                            name = name.trim(),
                            category = category.trim(),
                            variant = variant.trim().ifBlank { null },
                            brand = brand.trim().ifBlank { null },
                            unit = unit,
                            weight = weight.trim().ifBlank { null },
                            qty = parsedQty,
                            imageUri = imageUri,
                            said = prefill.said,
                            packSize = pack.trim().ifBlank { null },
                        ),
                    )
                },
            ) { Text(if (parsedQty != null) stringResource(R.string.kai_stock_confirm_in) else stringResource(R.string.kai_stock_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Edit a stock draft before confirming: quantity and unit. */
@Composable
fun StockEditDialog(name: String, qty: BigDecimal, unit: String, onDismiss: () -> Unit, onSave: (BigDecimal, String) -> Unit) {
    var q by remember { mutableStateOf(qty.stripTrailingZeros().toPlainString()) }
    var u by remember { mutableStateOf(unit) }
    val parsed = q.replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(stringResource(R.string.kai_stock_qty)) }, singleLine = true, isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf("PCS", "BOX", "PACK", "KG", "LITRE").forEach { x -> FilterChip(selected = u == x, onClick = { u = x }, label = { Text(x) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let { onSave(it, u) } }, enabled = parsed != null) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
