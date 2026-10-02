package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.GroupBuyingMatchesResponse
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
fun GroupBuyingResultScreen(
    container: AppContainer,
    requestId: String,
    onBack: () -> Unit,
) {
    var result by remember { mutableStateOf<GroupBuyingMatchesResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var joining by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf(false) }
    var confirmJoin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val errorLoad = stringResource(R.string.gb_error_matches)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                val matches = container.groupBuyingRepository.listMatches(requestId)
                val ownStatus = runCatching {
                    container.groupBuyingRepository.listRequests().firstOrNull { it.id == requestId }?.status
                }.getOrNull()
                result = matches.copy(requestStatus = ownStatus ?: matches.requestStatus)
            }.onFailure {
                container.presentApiError(it, errorLoad, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    LaunchedEffect(requestId) { reload() }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    if (confirmJoin) {
        ShopAlertDialog(
            onDismissRequest = { if (!joining) confirmJoin = false },
            title = { Text(stringResource(R.string.gb_join_title)) },
            text = { Text(stringResource(R.string.gb_join_body)) },
            confirmButton = {
                TextButton(
                    enabled = !joining,
                    onClick = {
                        scope.launch {
                            joining = true
                            runCatching { container.groupBuyingRepository.join(requestId) }
                                .onSuccess {
                                    joined = true
                                    confirmJoin = false
                                    reload()
                                }
                                .onFailure {
                                    container.presentApiError(
                                        it,
                                        errorLoad,
                                        { msg -> error = msg },
                                        { msg -> alertError = msg },
                                    )
                                }
                            joining = false
                        }
                    },
                ) { Text(stringResource(R.string.gb_join_confirm)) }
            },
            dismissButton = {
                TextButton(enabled = !joining, onClick = { confirmJoin = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    DetailScaffold(title = stringResource(R.string.gb_results_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(error!!, color = ShopAiThemeColors.onSurface)
                else -> {
                    val data = result
                    if (data != null) {
                        ShopCard {
                            Text(
                                stringResource(R.string.gb_totals_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = ShopAiThemeColors.onSurface,
                            )
                            Text(
                                stringResource(
                                    R.string.gb_totals_line,
                                    formatGroupBuyingQty(data.totals.totalQuantity),
                                    data.totals.unit,
                                    data.totals.businessCount,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = ShopAiThemeColors.onSurface,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                stringResource(R.string.gb_privacy_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShopAiThemeColors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }

                        if (data.matches.isEmpty()) {
                            Text(
                                stringResource(R.string.gb_no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShopAiThemeColors.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                stringResource(R.string.gb_nearby_shops),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ShopAiThemeColors.onSurface,
                            )
                            data.matches.forEach { match ->
                                ShopCard {
                                    Text(
                                        match.shopName.ifBlank { match.areaLabel },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ShopAiThemeColors.onSurface,
                                    )
                                    if (match.ownerName.isNotBlank() && match.ownerName != match.shopName) {
                                        Text(
                                            match.ownerName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = ShopAiThemeColors.onSurfaceVariant,
                                        )
                                    }
                                    val matchPhone = displayIndianPhone(match.phone)
                                    if (matchPhone.isNotBlank()) {
                                        Text(
                                            stringResource(R.string.gb_shop_phone, matchPhone),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = ShopAiThemeColors.onSurface,
                                        )
                                    }
                                    Text(
                                        groupBuyingInviteStatusLabel(match.interestStatus),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    if (match.interestStatus == "INTERESTED" || match.interestStatus == "POSTED") {
                                        Text(
                                            stringResource(
                                                R.string.gb_match_qty,
                                                formatGroupBuyingQty(match.quantity),
                                                match.unit,
                                            ),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = ShopAiThemeColors.onSurface,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                    Text(
                                        match.areaLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ShopAiThemeColors.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.gb_match_distance, match.distanceKm.toString()),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ShopAiThemeColors.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.gb_needed_by, formatDisplayDate(match.requiredDate)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ShopAiThemeColors.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        if (joined || data.requestStatus.equals("JOINED", ignoreCase = true)) {
                            Text(
                                stringResource(R.string.gb_joined_done),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (data.matches.isNotEmpty()) {
                            PrimaryButton(
                                label = stringResource(R.string.gb_join_cta),
                                loading = joining,
                                onClick = { confirmJoin = true },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
