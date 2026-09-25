package com.shopai.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.GroupBuyingInboxItem
import com.shopai.app.data.model.GroupBuyingRequest
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopAlertDialog
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.formatDisplayDate
import com.shopai.app.util.displayIndianPhone
import kotlinx.coroutines.launch

@Composable
fun GroupBuyingScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onNewRequest: () -> Unit,
    onOpenRequest: (String) -> Unit,
) {
    var requests by remember { mutableStateOf<List<GroupBuyingRequest>>(emptyList()) }
    var inbox by remember { mutableStateOf<List<GroupBuyingInboxItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var cancelTarget by remember { mutableStateOf<GroupBuyingRequest?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var interestTarget by remember { mutableStateOf<GroupBuyingInboxItem?>(null) }
    var interestQty by remember { mutableStateOf("") }
    var responding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errorLoad = stringResource(R.string.gb_error_load)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                requests = container.groupBuyingRepository.listRequests()
                inbox = container.groupBuyingRepository.listInbox()
            }.onFailure {
                container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    cancelTarget?.let { target ->
        ShopAlertDialog(
            onDismissRequest = { if (!cancelling) cancelTarget = null },
            title = { Text(stringResource(R.string.gb_cancel_title)) },
            text = { Text(stringResource(R.string.gb_cancel_body)) },
            confirmButton = {
                TextButton(
                    enabled = !cancelling,
                    onClick = {
                        scope.launch {
                            cancelling = true
                            runCatching { container.groupBuyingRepository.cancel(target.id) }
                                .onSuccess { cancelTarget = null; reload() }
                                .onFailure {
                                    container.presentApiError(
                                        it,
                                        errorLoad,
                                        { msg -> error = msg },
                                        { msg -> alertError = msg },
                                    )
                                }
                            cancelling = false
                        }
                    },
                ) { Text(stringResource(R.string.gb_cancel_confirm)) }
            },
            dismissButton = {
                TextButton(enabled = !cancelling, onClick = { cancelTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    interestTarget?.let { target ->
        ShopAlertDialog(
            onDismissRequest = { if (!responding) interestTarget = null },
            title = { Text(stringResource(R.string.gb_interested_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.gb_interested_body, target.request.shopName, target.request.unit))
                    OutlinedTextField(
                        value = interestQty,
                        onValueChange = { interestQty = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text(stringResource(R.string.gb_your_quantity)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !responding && interestQty.toDoubleOrNull()?.let { it > 0 } == true,
                    onClick = {
                        val qty = interestQty.toDoubleOrNull() ?: return@TextButton
                        scope.launch {
                            responding = true
                            runCatching {
                                container.groupBuyingRepository.respondToInvite(target.id, interested = true, quantity = qty)
                            }.onSuccess {
                                interestTarget = null
                                reload()
                            }.onFailure {
                                container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
                            }
                            responding = false
                        }
                    },
                ) { Text(stringResource(R.string.gb_interested)) }
            },
            dismissButton = {
                TextButton(enabled = !responding, onClick = { interestTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    DetailScaffold(title = stringResource(R.string.gb_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.gb_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
            )
            PrimaryButton(label = stringResource(R.string.gb_new_request), onClick = onNewRequest)

            if (!loading && inbox.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.gb_inbox_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ShopAiThemeColors.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
                inbox.forEach { item ->
                    ShopCard {
                        Text(
                            text = item.request.shopName.ifBlank { item.request.ownerName },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ShopAiThemeColors.onSurface,
                        )
                        if (item.request.ownerName.isNotBlank() && item.request.ownerName != item.request.shopName) {
                            Text(
                                text = item.request.ownerName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShopAiThemeColors.onSurfaceVariant,
                            )
                        }
                        val requesterPhone = displayIndianPhone(item.request.phone)
                        if (requesterPhone.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.gb_shop_phone, requesterPhone),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShopAiThemeColors.onSurface,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.gb_inbox_need,
                                item.request.productId.replaceFirstChar { it.uppercase() },
                                formatGroupBuyingQty(item.request.quantity),
                                item.request.unit,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ShopAiThemeColors.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = stringResource(R.string.gb_needed_by, formatDisplayDate(item.request.requiredDate)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.gb_match_distance, item.distanceKm.toString()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                        Text(
                            text = groupBuyingInviteStatusLabel(item.status),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (item.status == "INTERESTED" && item.quantity != null) {
                            Text(
                                text = stringResource(
                                    R.string.gb_your_qty_line,
                                    formatGroupBuyingQty(item.quantity),
                                    item.request.unit,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShopAiThemeColors.onSurface,
                            )
                        }
                        if (item.status == "PENDING") {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    interestQty = ""
                                    interestTarget = item
                                }) {
                                    Text(stringResource(R.string.gb_interested))
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            responding = true
                                            runCatching {
                                                container.groupBuyingRepository.respondToInvite(
                                                    item.id,
                                                    interested = false,
                                                )
                                            }.onSuccess { reload() }
                                                .onFailure {
                                                    container.presentApiError(
                                                        it,
                                                        errorLoad,
                                                        { msg -> error = msg },
                                                        { msg -> alertError = msg },
                                                    )
                                                }
                                            responding = false
                                        }
                                    },
                                    enabled = !responding,
                                ) {
                                    Text(stringResource(R.string.gb_not_interested))
                                }
                            }
                        }
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(error!!, color = ShopAiThemeColors.onSurface)
                requests.isEmpty() -> Text(
                    stringResource(R.string.gb_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShopAiThemeColors.onSurfaceVariant,
                )
                else -> requests.forEach { request ->
                    ShopCard(modifier = Modifier.clickable { onOpenRequest(request.id) }) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = request.productId.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = ShopAiThemeColors.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = groupBuyingStatusLabel(request.status),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.gb_request_qty,
                                formatGroupBuyingQty(request.quantity),
                                request.unit,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ShopAiThemeColors.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = stringResource(R.string.gb_needed_by, formatDisplayDate(request.requiredDate)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                        Text(
                            text = request.areaLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShopAiThemeColors.onSurfaceVariant,
                        )
                        if (request.status == "ACTIVE" || request.status == "MATCHED") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { cancelTarget = request }) {
                                Text(stringResource(R.string.gb_cancel_confirm))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun groupBuyingStatusLabel(status: String): String = stringResource(
    when (status) {
        "ACTIVE" -> R.string.gb_status_active
        "MATCHED" -> R.string.gb_status_matched
        "JOINED" -> R.string.gb_status_joined
        "CLOSED" -> R.string.gb_status_closed
        "CANCELLED" -> R.string.gb_status_cancelled
        else -> R.string.gb_status_active
    },
)

@Composable
fun groupBuyingInviteStatusLabel(status: String): String = stringResource(
    when (status) {
        "INTERESTED" -> R.string.gb_status_interested
        "NOT_INTERESTED" -> R.string.gb_status_not_interested
        "POSTED" -> R.string.gb_status_posted
        else -> R.string.gb_status_pending
    },
)

fun formatGroupBuyingQty(quantity: Double): String =
    if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
