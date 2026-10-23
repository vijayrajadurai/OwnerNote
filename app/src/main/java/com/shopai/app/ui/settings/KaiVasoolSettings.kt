package com.shopai.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shopai.app.R
import com.shopai.app.notifications.KaiUpiAccess
import com.shopai.app.ui.components.ShopCard

/**
 * Kai வசூல் in Settings (owner, 23 Oct 2026): on / off, and turning it on — first Kai's plain explanation of what is read
 * (Play's prominent disclosure: only the payment apps, nothing leaves the phone, nothing saved before Confirm), then Android's
 * notification-access page. The status is read again whenever the screen comes back.
 */
@Composable
fun KaiVasoolSettings() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(KaiUpiAccess.granted(context)) }
    var explain by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { granted = KaiUpiAccess.granted(context) }
    }

    Text(stringResource(R.string.kai_upi_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.kai_upi_settings_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
    ShopCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (granted) R.string.kai_upi_settings_on else R.string.kai_upi_settings_off),
                fontWeight = FontWeight.SemiBold,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (granted) {
                TextButton(onClick = { openVasoolAccess(context) }) { Text(stringResource(R.string.kai_upi_settings_turn_off)) }
            } else {
                TextButton(onClick = { explain = true }) { Text(stringResource(R.string.kai_upi_settings_turn_on)) }
            }
        }
        if (!granted) {
            Text(stringResource(R.string.kai_upi_restricted_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.kai_upi_disclosure_title)) },
            text = { Text(stringResource(R.string.kai_upi_disclosure_body)) },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    openVasoolAccess(context)
                }) { Text(stringResource(R.string.kai_upi_disclosure_allow)) }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.kai_upi_disclosure_deny)) } },
        )
    }
}

/** Android's page for OwnerNote's notification access; the list of apps where a phone has no page for one app. */
private fun openVasoolAccess(context: android.content.Context) {
    runCatching { context.startActivity(KaiUpiAccess.settingsIntent(context)) }
        .recoverCatching { context.startActivity(KaiUpiAccess.listIntent()) }
}
