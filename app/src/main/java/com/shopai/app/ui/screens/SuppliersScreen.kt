package com.shopai.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.components.PartyDirectoryBody
import com.shopai.app.ui.theme.LedgerDebit
import com.shopai.app.ui.theme.LedgerDebitMuted
import com.shopai.app.util.normalizeIndianPhone
import com.shopai.app.util.rememberContactPicker

@Composable
fun SuppliersScreen(
    container: AppContainer,
    onNavigate: (String) -> Unit,
    onOpenSupplier: (String) -> Unit,
    onAddDebit: (supplierId: String?, supplierName: String, supplierPhone: String?) -> Unit,
) {
    var suppliers by remember { mutableStateOf<List<PartySummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val errorLoadSuppliers = stringResource(R.string.suppliers_error)
    val contactPickFailed = stringResource(R.string.contacts_pick_failed)

    suspend fun reload() {
        loading = true
        error = null
        runCatching {
            suppliers = container.partyRepository.getSuppliers()
        }.onFailure {
            container.presentApiError(it, errorLoadSuppliers, { msg -> error = msg }, { msg -> alertError = msg })
        }
        loading = false
    }

    LaunchedEffect(reloadKey) {
        reload()
    }

    val pickContact = rememberContactPicker(
        onContactPicked = { contact ->
            val phone = contact.phone?.let(::normalizeIndianPhone)
            val existing = suppliers.firstOrNull { supplier ->
                supplier.name.equals(contact.name, ignoreCase = true) ||
                    (phone != null && supplier.phone == phone)
            }
            if (existing != null) {
                onAddDebit(existing.id, existing.name, existing.phone)
            } else {
                onAddDebit(null, contact.name, phone)
            }
        },
        onPickFailed = { alertError = contactPickFailed },
    )

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    MainTabScaffold(activeTab = BottomNavTab.Suppliers, onNavigate = onNavigate) {
        PartyDirectoryBody(
            title = stringResource(R.string.suppliers_title),
            contactLabel = stringResource(R.string.suppliers_add_contact),
            addLabel = stringResource(R.string.suppliers_add),
            emptyText = stringResource(R.string.suppliers_empty),
            parties = suppliers,
            loading = loading,
            error = error,
            avatarAccent = LedgerDebit,
            avatarAccentMuted = LedgerDebitMuted,
            onContactClick = pickContact,
            onAddClick = { onAddDebit(null, "", null) },
            onPartyClick = onOpenSupplier,
        )
    }
}
