package com.shopai.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import com.shopai.app.ui.components.AuthBackgroundBrush
import com.shopai.app.ui.components.GradientActionButton
import com.shopai.app.ui.components.KaiSrcSize
import com.shopai.app.ui.components.fadeEdges
import com.shopai.app.ui.components.rememberWelcomeArtwork
import com.shopai.app.ui.theme.Accent
import com.shopai.app.ui.theme.OnPrimary
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.PrimaryLight
import com.shopai.app.ui.theme.TextPrimary
import com.shopai.app.ui.theme.TextSecondary
import com.shopai.app.util.SmsOtpAutofillEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val OtpLength = 6
private const val ResendSeconds = 30

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
    var showSlowHint by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // Survives rotation so arriving here only ever sends one SMS.
    var initialSendStarted by rememberSaveable { mutableStateOf(false) }
    var resendEpoch by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val errorInvalidOtp = stringResource(R.string.error_invalid_otp)
    val errorSendOtp = stringResource(R.string.error_send_otp)
    val focusRequester = remember { FocusRequester() }
    val artwork = rememberWelcomeArtwork()

    LaunchedEffect(resendEpoch) {
        if (resendEpoch == 0) return@LaunchedEffect
        secondsLeft = ResendSeconds
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }

    LaunchedEffect(loading, sending) {
        showSlowHint = false
        if (loading || sending) {
            delay(4_000)
            showSlowHint = true
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

    // Runs as soon as the 6th digit is typed, pasted or autofilled.
    fun verifyCode(otp: String) {
        if (loading || otp.length != OtpLength) return
        scope.launch {
            loading = true
            error = null
            runCatching {
                completeLogin(container.authRepository.verifyOtp(otp))
            }.onFailure {
                container.presentApiError(it, errorInvalidOtp, { msg -> error = msg }, { msg -> alertError = msg })
                // Clear the boxes so the correct code can be typed straight away.
                code = ""
                runCatching { focusRequester.requestFocus() }
            }
            loading = false
        }
    }

    // Used for the first send when the page opens and for "Resend OTP".
    // After a failure secondsLeft stays 0, so "Resend OTP" works as a retry.
    fun sendOtp() {
        if (sending || secondsLeft > 0 || phone.length < 10) return
        scope.launch {
            sending = true
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
            sending = false
        }
    }

    LaunchedEffect(Unit) {
        phone = container.authRepository.getLoginPhone().orEmpty()
        if (!initialSendStarted) {
            initialSendStarted = true
            sendOtp()
        }
        delay(80)
        runCatching { focusRequester.requestFocus() }
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
            .background(AuthBackgroundBrush)
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
                    tint = TextPrimary,
                )
            }
        }

        Text(
            text = stringResource(R.string.otp_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(if (sending) R.string.otp_sending_to else R.string.otp_sent_code_to),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (phone.isNotBlank()) {
            Text(
                text = "+91 " + formatIndianMobile(phone),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = Primary,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        OtpDigitBoxes(
            value = code,
            onValueChange = { next ->
                code = next
                error = null
                if (next.length == OtpLength) verifyCode(next)
            },
            onDone = { verifyCode(code) },
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

        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .height(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                sending -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Primary,
                    strokeWidth = 2.dp,
                )
                secondsLeft > 0 -> {
                    val time = "%02d:%02d".format(secondsLeft / 60, secondsLeft % 60)
                    val full = stringResource(R.string.otp_resend_in, time)
                    val start = full.indexOf(time)
                    Text(
                        text = buildAnnotatedString {
                            append(full)
                            if (start >= 0) {
                                addStyle(
                                    SpanStyle(color = Primary, fontWeight = FontWeight.Bold),
                                    start,
                                    start + time.length,
                                )
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
                else -> Text(
                    text = stringResource(R.string.otp_resend),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { sendOtp() },
                )
            }
        }

        // KAI with the "verified" shield; shrinks when the keyboard opens.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(
                        KaiSrcSize.width.toFloat() / KaiSrcSize.height,
                        matchHeightConstraintsFirst = true,
                    ),
            ) {
                Image(
                    painter = artwork.kai,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .fadeEdges(horizontal = 0.18f, vertical = 0.10f),
                )
                VerifiedShield(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = 4.dp)
                        .size(64.dp),
                )
            }
        }

        GradientActionButton(
            label = stringResource(R.string.otp_verify),
            loading = loading,
            enabled = code.length == OtpLength && !sending,
            onClick = { verifyCode(code) },
        )
        if (showSlowHint) {
            Text(
                text = stringResource(R.string.login_slow_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Text(
            text = stringResource(R.string.powered_by_newonx),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun OtpDigitBoxes(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
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
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(OtpLength) { index ->
                    val char = value.getOrNull(index)?.toString().orEmpty()
                    val active = value.length == index || (value.length == OtpLength && index == OtpLength - 1)
                    val borderColor = when {
                        isError -> MaterialTheme.colorScheme.error
                        active -> Primary
                        else -> PrimaryLight
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.9f)
                            .shadow(elevation = 2.dp, shape = shape, clip = false)
                            .clip(shape)
                            .background(Color.White)
                            .border(width = if (active) 2.dp else 1.5.dp, color = borderColor, shape = shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = char,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                    }
                }
            }
        },
    )
}

// Green shield with a white tick, drawn in code so no extra image is needed.
private val ShieldShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w / 2f, 0f)
    lineTo(w, h * 0.16f)
    lineTo(w, h * 0.48f)
    cubicTo(w, h * 0.76f, w * 0.72f, h * 0.92f, w / 2f, h)
    cubicTo(w * 0.28f, h * 0.92f, 0f, h * 0.76f, 0f, h * 0.48f)
    lineTo(0f, h * 0.16f)
    close()
}

@Composable
private fun VerifiedShield(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .shadow(elevation = 6.dp, shape = ShieldShape, clip = false)
            .clip(ShieldShape)
            .background(Brush.verticalGradient(listOf(Accent, Primary))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = OnPrimary,
            modifier = Modifier.size(36.dp),
        )
    }
}
