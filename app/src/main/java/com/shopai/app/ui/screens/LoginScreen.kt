package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.repository.PhoneOtpSendResult
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.util.findActivity
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    container: AppContainer,
    onNavigateOtp: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateBusinessSetup: () -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val errorSendOtp = stringResource(R.string.error_send_otp)
    val navigateOtp = rememberUpdatedState(onNavigateOtp)
    val navigateHome = rememberUpdatedState(onNavigateHome)
    val navigateSetup = rememberUpdatedState(onNavigateBusinessSetup)

    suspend fun goHomeOrSetup(isNewUser: Boolean) {
        if (isNewUser) {
            navigateSetup.value()
            return
        }
        val business = container.businessRepository.getMyBusiness()
        if (business != null) navigateHome.value() else navigateSetup.value()
    }

    fun sendOtp() {
        if (loading || phone.length < 10) return
        container.appScope.launch {
            loading = true
            error = null
            runCatching {
                when (val result = container.authRepository.sendOtp(context.findActivity(), phone)) {
                    is PhoneOtpSendResult.CodeSent -> navigateOtp.value()
                    is PhoneOtpSendResult.SignedIn -> goHomeOrSetup(result.auth.isNewUser)
                }
            }.onFailure {
                container.presentApiError(it, errorSendOtp, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Image(
            painter = painterResource(R.drawable.ic_logo_dummy),
            contentDescription = stringResource(R.string.brand_name),
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape),
        )
        Text(
            text = stringResource(R.string.brand_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { ch -> ch.isDigit() }.take(10) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            placeholder = {
                Text(
                    text = stringResource(R.string.login_phone_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingIcon = {
                Text(
                    text = stringResource(R.string.login_country_code),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            ),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        if (error != null) {
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        PrimaryButton(
            label = stringResource(R.string.login_send_otp),
            loading = loading,
            enabled = phone.length >= 10,
            onClick = { sendOtp() },
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.powered_by_newonx),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
