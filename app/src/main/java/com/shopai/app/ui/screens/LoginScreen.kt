package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.AuthBackgroundBrush
import com.shopai.app.ui.components.AuthBrandHeader
import com.shopai.app.ui.components.GradientActionButton
import com.shopai.app.ui.components.PoweredByNewonX
import com.shopai.app.ui.theme.Border
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.TextPrimary
import com.shopai.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    container: AppContainer,
    onNavigateOtp: () -> Unit,
) {
    var phone by rememberSaveable { mutableStateOf("") }
    var navigating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val navigateOtp = rememberUpdatedState(onNavigateOtp)

    // The OTP page does the actual sending, so the owner lands there right
    // away instead of waiting here for Firebase.
    fun goToOtp() {
        if (navigating || phone.length < 10) return
        navigating = true
        scope.launch {
            container.authRepository.setLoginPhone(phone)
            navigateOtp.value()
            navigating = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AuthBackgroundBrush)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(100.dp))
        AuthBrandHeader()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.login_title_line1),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.login_title_line2),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Primary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.login_otp_info),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            PhoneNumberField(
                phone = phone,
                onPhoneChange = { phone = it.filter { ch -> ch.isDigit() }.take(10) },
                onDone = { goToOtp() },
            )

            Spacer(modifier = Modifier.height(16.dp))
            GradientActionButton(
                label = stringResource(R.string.login_send_otp),
                loading = navigating,
                enabled = phone.length >= 10,
                onClick = { goToOtp() },
            )
        }

        PoweredByNewonX()
    }
}

@Composable
private fun PhoneNumberField(
    phone: String,
    onPhoneChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val textStyle = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        color = TextPrimary,
        letterSpacing = 0.5.sp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(elevation = 4.dp, shape = shape, clip = false)
            .clip(shape)
            .background(Color.White)
            .border(width = 1.dp, color = Border, shape = shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only +91 is supported (AuthRepository.sendOtp adds it), so the
        // country picker is display-only.
        Text(text = "🇮🇳", fontSize = 24.sp)
        Text(
            text = stringResource(R.string.login_country_code),
            style = textStyle.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 8.dp),
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(20.dp),
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .width(1.dp)
                .height(30.dp)
                .background(Border),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (phone.isEmpty()) {
                Text(
                    text = stringResource(R.string.login_phone_placeholder),
                    style = textStyle.copy(color = TextSecondary.copy(alpha = 0.6f)),
                )
            }
            BasicTextField(
                value = phone,
                onValueChange = onPhoneChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(Primary),
                visualTransformation = IndianMobileTransformation,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// Shows "9876543210" as "98765 43210" without changing the stored value.
private val IndianMobileTransformation = VisualTransformation { text ->
    TransformedText(
        AnnotatedString(formatIndianMobile(text.text)),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = if (offset > 5) offset + 1 else offset
            override fun transformedToOriginal(offset: Int): Int = if (offset > 5) offset - 1 else offset
        },
    )
}

internal fun formatIndianMobile(digits: String): String =
    if (digits.length > 5) digits.substring(0, 5) + " " + digits.substring(5) else digits
