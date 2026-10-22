package com.shopai.app.ui.screens

import androidx.compose.runtime.Composable
import com.shopai.app.data.AppContainer

/**
 * Add Credit: the three-step money entry (who → how much → is this right; owner, 22 Oct 2026). The entry is saved as this screen
 * always saved it — see [MoneyEntryFlow].
 */
@Composable
fun AddCreditScreen(
    container: AppContainer,
    prefillCustomerId: String? = null,
    prefillCustomerName: String? = null,
    prefillCustomerPhone: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
) = MoneyEntryFlow(
    container = container,
    startCredit = true,
    prefillId = prefillCustomerId,
    prefillName = prefillCustomerName,
    prefillPhone = prefillCustomerPhone,
    onBack = onBack,
    onDone = onDone,
)
