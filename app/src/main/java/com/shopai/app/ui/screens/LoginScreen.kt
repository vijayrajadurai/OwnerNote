package com.shopai.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.repository.PhoneOtpSendResult
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ScreenContainer
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.launch

private enum class LoginMode(@StringRes val labelRes: Int) {
    Phone(R.string.login_mode_phone),
    Test(R.string.login_mode_test),
}

@Composable
fun LoginScreen(
    container: AppContainer,
    onNavigateOtp: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateBusinessSetup: () -> Unit,
) {
    var mode by remember { mutableStateOf(LoginMode.Phone) }
    var phone by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val errorSendOtp = stringResource(R.string.error_send_otp)
    val errorTestLogin = stringResource(R.string.error_test_login)

    suspend fun goHomeOrSetup(isNewUser: Boolean) {
        if (isNewUser) {
            onNavigateBusinessSetup()
            return
        }
        val business = container.businessRepository.getMyBusiness()
        if (business != null) onNavigateHome() else onNavigateBusinessSetup()
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    ScreenContainer {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.brand_name),
                style = MaterialTheme.typography.titleMedium,
                color = ShopAiThemeColors.primary,
            )
            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        when (mode) {
            LoginMode.Phone -> {
                ShopTextField(
                    label = stringResource(R.string.login_phone_label),
                    value = phone,
                    onValueChange = { phone = it.filter { ch -> ch.isDigit() }.take(10) },
                    placeholder = stringResource(R.string.login_phone_placeholder),
                    error = error,
                )
                PrimaryButton(
                    label = stringResource(R.string.login_send_otp),
                    loading = loading,
                    enabled = phone.length >= 10,
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            runCatching {
                                when (val result = container.authRepository.sendOtp(context.findActivity(), phone)) {
                                    is PhoneOtpSendResult.CodeSent -> onNavigateOtp()
                                    is PhoneOtpSendResult.SignedIn -> goHomeOrSetup(result.auth.isNewUser)
                                }
                            }.onFailure {
                                container.presentApiError(it, errorSendOtp, { msg -> error = msg }, { msg -> alertError = msg })
                            }
                            loading = false
                        }
                    },
                )
            }
            LoginMode.Test -> {
                ShopTextField(
                    label = stringResource(R.string.login_username_label),
                    value = username,
                    onValueChange = { username = it },
                    placeholder = stringResource(R.string.login_username_placeholder),
                )
                ShopTextField(
                    label = stringResource(R.string.login_password_label),
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.login_password_placeholder),
                    error = error,
                )
                PrimaryButton(
                    label = stringResource(R.string.login_test_submit),
                    loading = loading,
                    enabled = username.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            runCatching {
                                val result = container.authRepository.testLogin(username, password)
                                goHomeOrSetup(result.isNewUser)
                            }.onFailure {
                                container.presentApiError(it, errorTestLogin, { msg -> error = msg }, { msg -> alertError = msg })
                            }
                            loading = false
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            LoginMode.entries.forEach { m ->
                Text(
                    text = stringResource(m.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mode == m) ShopAiThemeColors.primary else ShopAiThemeColors.onSurfaceVariant,
                    fontWeight = if (mode == m) FontWeight.Bold else FontWeight.Medium,
                    textDecoration = if (mode == m) TextDecoration.Underline else TextDecoration.None,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable {
                            error = null
                            mode = m
                        },
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    error("Login requires an Activity")
}
