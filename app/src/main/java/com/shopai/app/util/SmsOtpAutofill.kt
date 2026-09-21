package com.shopai.app.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * Reads a 6-digit OTP from Firebase / carrier SMS bodies without the READ_SMS permission.
 * Uses the SMS User Consent API: the user taps once to allow reading a single incoming message.
 */
fun extractOtpFromMessage(message: String): String? {
    val normalized = message.trim()
    if (normalized.length == 6 && normalized.all { it.isDigit() }) return normalized
    return Regex("""\b(\d{6})\b""").find(normalized)?.groupValues?.get(1)
}

@Composable
fun SmsOtpAutofillEffect(onOtpReceived: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val onOtp = rememberUpdatedState(onOtpReceived)

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE) ?: return@rememberLauncherForActivityResult
        extractOtpFromMessage(message)?.let { onOtp.value(it) }
    }

    DisposableEffect(activity, consentLauncher) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                val status = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_STATUS, Status::class.java) ?: return
                when (status.statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        val consentIntent = BundleCompat.getParcelable(
                            extras,
                            SmsRetriever.EXTRA_CONSENT_INTENT,
                            Intent::class.java,
                        )
                        consentIntent?.let { consentLauncher.launch(it) }
                    }
                    CommonStatusCodes.TIMEOUT -> {
                        SmsRetriever.getClient(activity).startSmsUserConsent(null)
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        SmsRetriever.getClient(activity).startSmsUserConsent(null)

        onDispose {
            runCatching { activity.unregisterReceiver(receiver) }
        }
    }
}

private fun Context.findActivity(): Activity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    error("SMS autofill requires an Activity context")
}
