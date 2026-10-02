package com.shopai.app.ui.books

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.shopai.app.R
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import java.math.BigDecimal
import java.math.RoundingMode

/** Loading → the books session, or null when the books are not in use yet. */
sealed class BooksState {
    data object Loading : BooksState()
    data object NotReady : BooksState()
    data class Ready(val session: BooksSession) : BooksState()
}

@Composable
fun rememberBooks(container: AppContainer): BooksState {
    var state by remember { mutableStateOf<BooksState>(BooksState.Loading) }
    LaunchedEffect(Unit) {
        state = container.books.session()?.let { BooksState.Ready(it) } ?: BooksState.NotReady
    }
    return state
}

/** Shown when a books screen opens before the one-time import has happened. */
@Composable
fun BooksNotReady(state: BooksState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state is BooksState.Loading) {
            CircularProgressIndicator(color = ShopAiThemeColors.primary)
        } else {
            Text(stringResource(R.string.books_not_ready), color = ShopAiThemeColors.onSurfaceVariant)
        }
    }
}

/** Rupees typed by the owner → paise; blank = null; anything else = error. */
fun parseRupees(text: String): Result<Long?> = runCatching {
    val t = text.trim().replace(",", "")
    if (t.isEmpty()) null else BigDecimal(t).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
}

fun parseQty(text: String): Result<Long?> = runCatching {
    val t = text.trim().replace(",", "")
    if (t.isEmpty()) null else BigDecimal(t).setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact()
}

/** GST % typed or chosen → basis points (18 → 1800, 0.25 → 25). */
fun parsePercentBp(text: String): Result<Int?> = runCatching {
    val t = text.trim()
    if (t.isEmpty()) null else BigDecimal(t).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact()
}

fun paiseText(paise: Long?): String = paise?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() }.orEmpty()

fun qtyText(milli: Long?): String = milli?.let { BigDecimal.valueOf(it, 3).stripTrailingZeros().toPlainString() }.orEmpty()

fun bpText(bp: Int): String = BigDecimal.valueOf(bp.toLong(), 2).stripTrailingZeros().toPlainString()

private val decimalKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)

@Composable
fun DecimalField(label: String, value: String, onValueChange: (String) -> Unit, error: String? = null, modifier: Modifier = Modifier) {
    ShopTextField(
        label = label,
        value = value,
        onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() || ch == '.' }) },
        error = error,
        keyboardOptions = decimalKeyboard,
        modifier = modifier,
    )
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    ShopCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, caption: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = ShopAiThemeColors.onSurface)
            caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The engine's reasons, one per line. */
@Composable
fun BooksErrors(errors: List<BooksError>) {
    if (errors.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        errors.forEach { Text("• ${it.message}", color = Danger, style = MaterialTheme.typography.bodyMedium) }
    }
}

fun List<BooksError>.messageFor(field: String): String? = firstOrNull { it.field == field }?.message

/** Google's code scanner (no camera permission needed). [onResult] gets null when cancelled or unavailable. */
fun scanBarcode(context: Context, onResult: (String?) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE,
        )
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { onResult(it.rawValue?.trim()?.takeIf(String::isNotEmpty)) }
        .addOnCanceledListener { onResult(null) }
        .addOnFailureListener { onResult(null) }
}
