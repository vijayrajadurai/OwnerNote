package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.shopai.app.ui.components.PartyList
import com.shopai.app.ui.components.PartyScreenActions
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.LedgerCredit
import com.shopai.app.ui.theme.LedgerCreditMuted
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.normalizeIndianPhone
import com.shopai.app.util.rememberContactPicker

@Composable
fun CustomersScreen(
    container: AppContainer,
    onNavigate: (String) -> Unit,
    onOpenCustomer: (String) -> Unit,
    onAddCredit: (customerId: String?, customerName: String, customerPhone: String?) -> Unit,
) {
    var customers by remember { mutableStateOf<List<PartySummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val errorLoadCustomers = stringResource(R.string.customers_error)
    val contactPickFailed = stringResource(R.string.contacts_pick_failed)

    suspend fun reload() {
        loading = true
        error = null
        runCatching {
            customers = container.partyRepository.getCustomers()
        }.onFailure {
            container.presentApiError(it, errorLoadCustomers, { msg -> error = msg }, { msg -> alertError = msg })
        }
        loading = false
    }

    LaunchedEffect(reloadKey) {
        reload()
    }

    val pickContact = rememberContactPicker(
        onContactPicked = { contact ->
            val phone = contact.phone?.let(::normalizeIndianPhone)
            val existing = customers.firstOrNull { customer ->
                customer.name.equals(contact.name, ignoreCase = true) ||
                    (phone != null && customer.phone == phone)
            }
            if (existing != null) {
                onAddCredit(existing.id, existing.name, existing.phone)
            } else {
                onAddCredit(null, contact.name, phone)
            }
        },
        onPickFailed = { alertError = contactPickFailed },
    )

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    MainTabScaffold(activeTab = BottomNavTab.Customers, onNavigate = onNavigate) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.customers_title),
                style = MaterialTheme.typography.headlineMedium,
                color = ShopAiThemeColors.onSurface,
                fontWeight = FontWeight.Bold,
            )
            PartyScreenActions(
                contactLabel = stringResource(R.string.customers_add_contact),
                addLabel = stringResource(R.string.customers_add),
                accent = LedgerCredit,
                accentMuted = LedgerCreditMuted,
                onContactClick = pickContact,
                onAddClick = { onAddCredit(null, "", null) },
            )

            when {
                loading -> CircularProgressIndicator(color = ShopAiThemeColors.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
                error != null -> Text(text = error!!, color = Danger)
                customers.isEmpty() -> Text(text = stringResource(R.string.customers_empty), color = ShopAiThemeColors.onSurfaceVariant)
                else -> PartyList(
                    parties = customers,
                    avatarAccent = LedgerCredit,
                    avatarAccentMuted = LedgerCreditMuted,
                    onPartyClick = onOpenCustomer,
                )
            }
        }
    }
}
