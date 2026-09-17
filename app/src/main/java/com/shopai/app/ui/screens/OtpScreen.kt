package com.shopai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ScreenContainer
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.launch

@Composable
fun OtpScreen(
    container: AppContainer,
    onNavigateHome: () -> Unit,
    onNavigateBusinessSetup: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errorInvalidOtp = stringResource(R.string.error_invalid_otp)

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    ScreenContainer {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.otp_title),
                style = MaterialTheme.typography.headlineMedium,
                color = ShopAiThemeColors.primary,
            )
            Text(
                text = stringResource(R.string.otp_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        ShopTextField(
            label = stringResource(R.string.otp_label),
            value = code,
            onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
            placeholder = stringResource(R.string.otp_placeholder),
            error = error,
        )

        PrimaryButton(
            label = stringResource(R.string.otp_verify),
            loading = loading,
            enabled = code.length == 6,
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    runCatching {
                        val result = container.authRepository.verifyOtp(code)
                        if (result.isNewUser) {
                            onNavigateBusinessSetup()
                        } else {
                            val business = container.businessRepository.getMyBusiness()
                            if (business != null) onNavigateHome() else onNavigateBusinessSetup()
                        }
                    }.onFailure {
                        container.presentApiError(it, errorInvalidOtp, { msg -> error = msg }, { msg -> alertError = msg })
                    }
                    loading = false
                }
            },
        )
    }
}
