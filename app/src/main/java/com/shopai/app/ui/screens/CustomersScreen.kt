package com.shopai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.model.PartySummary
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.navigation.Routes
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.formatInr

@Composable
fun CustomersScreen(
    container: AppContainer,
    onNavigate: (String) -> Unit,
) {
    var customers by remember { mutableStateOf<List<PartySummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    val errorLoadCustomers = stringResource(R.string.customers_error)

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            customers = container.partyRepository.getCustomers()
        }.onFailure {
            container.presentApiError(it, errorLoadCustomers, { msg -> error = msg }, { msg -> alertError = msg })
        }
        loading = false
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    MainTabScaffold(activeTab = BottomNavTab.Customers, onNavigate = onNavigate) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.customers_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = ShopAiThemeColors.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.customers_add),
                    color = ShopAiThemeColors.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onNavigate(Routes.AddCredit) },
                )
            }

            when {
                loading -> CircularProgressIndicator(color = ShopAiThemeColors.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
                error != null -> Text(text = error!!, color = Danger)
                customers.isEmpty() -> Text(text = stringResource(R.string.customers_empty), color = ShopAiThemeColors.onSurfaceVariant)
                else -> customers.forEach { customer ->
                    ShopCard {
                        Text(text = customer.name, style = MaterialTheme.typography.titleMedium, color = ShopAiThemeColors.onSurface)
                        Text(
                            text = stringResource(R.string.pending_amount, formatInr(customer.pendingTotal)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ShopAiThemeColors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (customer.phone != null) {
                            Text(
                                text = customer.phone,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShopAiThemeColors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
