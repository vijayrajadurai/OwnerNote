package com.shopai.app.ui.screens

import androidx.compose.runtime.Composable
import com.shopai.app.data.AppContainer

/**
 * Add Debit: the three-step money entry (who → how much → is this right; owner, 22 Oct 2026). The entry is saved as this screen
 * always saved it — see [MoneyEntryFlow].
 */
@Composable
fun AddDebitScreen(
    container: AppContainer,
    prefillSupplierId: String? = null,
    prefillSupplierName: String? = null,
    prefillSupplierPhone: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
) = MoneyEntryFlow(
    container = container,
    startCredit = false,
    prefillId = prefillSupplierId,
    prefillName = prefillSupplierName,
    prefillPhone = prefillSupplierPhone,
    onBack = onBack,
    onDone = onDone,
)
