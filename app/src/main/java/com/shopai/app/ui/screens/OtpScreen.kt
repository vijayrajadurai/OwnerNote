package com.shopai.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.AuthResponse
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.repository.PhoneOtpSendResult
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.util.SmsOtpAutofillEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val OtpLength = 6
private const val ResendSeconds = 45

@Composable
fun OtpScreen(
    container: AppContainer,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateBusinessSetup: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }
    var resendEpoch by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(ResendSeconds) }
    val scope = rememberCoroutineScope()
    val errorInvalidOtp = stringResource(R.string.error_invalid_otp)
    val errorSendOtp = stringResource(R.string.error_send_otp)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        phone = container.authRepository.getLoginPhone().orEmpty()
        delay(80)
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(resendEpoch) {
        secondsLeft = ResendSeconds
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }

    suspend fun completeLogin(result: AuthResponse) {
        if (result.isNewUser) {
            onNavigateBusinessSetup()
        } else {
            val business = container.businessRepository.getMyBusiness()
            if (business != null) onNavigateHome() else onNavigateBusinessSetup()
        }
    }

    fun verifyCode(otp: String) {
        if (loading || otp.length != OtpLength) return
        scope.launch {
            loading = true
            error = null
            runCatching {
                completeLogin(container.authRepository.verifyOtp(otp))
            }.onFailure {
                container.presentApiError(it, errorInvalidOtp, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    fun resendOtp() {
        if (resending || secondsLeft > 0 || phone.length < 10) return
        scope.launch {
            resending = true
            error = null
            runCatching {
                when (val result = container.authRepository.sendOtp(phone)) {
                    is PhoneOtpSendResult.CodeSent -> {
                        code = ""
                        resendEpoch++
                    }
                    is PhoneOtpSendResult.SignedIn -> completeLogin(result.auth)
                }
            }.onFailure {
                container.presentApiError(it, errorSendOtp, { msg -> error = msg }, { msg -> alertError = msg })
            }
            resending = false
        }
    }

    SmsOtpAutofillEffect { otp ->
        code = otp
        verifyCode(otp)
    }

    BackHandler(onBack = onNavigateBack)
    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.otp_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (phone.isNotBlank()) {
                stringResource(R.string.otp_sent_to, phone)
            } else {
                stringResource(R.string.otp_subtitle)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )

        OtpDigitBoxes(
            value = code,
            onValueChange = { next ->
                code = next
                error = null
                if (next.length == OtpLength) verifyCode(next)
            },
            focusRequester = focusRequester,
            isError = error != null,
        )

        if (error != null) {
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        PrimaryButton(
            label = stringResource(R.string.otp_verify),
            loading = loading,
            enabled = code.length == OtpLength,
            onClick = { verifyCode(code) },
        )

        Spacer(modifier = Modifier.height(20.dp))
        if (secondsLeft > 0) {
            Text(
                text = stringResource(R.string.otp_resend_in, secondsLeft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.otp_resend),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = !resending) { resendOtp() },
            )
        }

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

@Composable
private fun OtpDigitBoxes(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    isError: Boolean,
) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() }.take(OtpLength)) },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        singleLine = true,
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0f)),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0f)),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(OtpLength) { index ->
                    val char = value.getOrNull(index)?.toString().orEmpty()
                    val active = value.length == index || (value.length == OtpLength && index == OtpLength - 1)
                    val borderColor = when {
                        isError -> MaterialTheme.colorScheme.error
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(width = 1.5.dp, color = borderColor, shape = shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = char,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    )
}
