package com.shopai.app.data.auth

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class FirebasePhoneSendResult {
    data object CodeSent : FirebasePhoneSendResult()
    data class AutoVerified(val idToken: String) : FirebasePhoneSendResult()
}

class FirebasePhoneAuthClient(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    @Volatile
    private var verificationId: String? = null

    @Volatile
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    fun warmupAppVerification() {
        runCatching { auth.initializeRecaptchaConfig() }
    }

    suspend fun sendOtp(activity: Activity, e164Phone: String): FirebasePhoneSendResult {
        if (activity.isFinishing || activity.isDestroyed) {
            throw IllegalStateException("Login screen closed before OTP could be sent.")
        }
        val settled = AtomicBoolean(false)
        val outcome = suspendCancellableCoroutine<SendOutcome> { cont ->
            fun complete(result: Result<SendOutcome>) {
                if (!settled.compareAndSet(false, true)) return
                if (!cont.isActive) return
                result.fold(
                    onSuccess = { cont.resume(it) },
                    onFailure = { cont.resumeWithException(it) },
                )
            }

            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    complete(Result.success(SendOutcome.AutoCredential(credential)))
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    complete(Result.failure(e))
                }

                override fun onCodeSent(
                    id: String,
                    token: PhoneAuthProvider.ForceResendingToken,
                ) {
                    verificationId = id
                    resendToken = token
                    complete(Result.success(SendOutcome.CodeSent))
                }
            }

            val builder = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(e164Phone)
                .setTimeout(120L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
            resendToken?.let { builder.setForceResendingToken(it) }
            PhoneAuthProvider.verifyPhoneNumber(builder.build())
        }

        return when (outcome) {
            SendOutcome.CodeSent -> FirebasePhoneSendResult.CodeSent
            is SendOutcome.AutoCredential -> {
                FirebasePhoneSendResult.AutoVerified(signInAndGetIdToken(outcome.credential))
            }
        }
    }

    suspend fun verifySmsCode(code: String): String {
        val id = verificationId
            ?: throw IllegalStateException("No pending Firebase OTP. Request a new code.")
        val credential = PhoneAuthProvider.getCredential(id, code)
        return signInAndGetIdToken(credential)
    }

    suspend fun signOut() {
        auth.signOut()
        verificationId = null
        resendToken = null
    }

    private suspend fun signInAndGetIdToken(credential: PhoneAuthCredential): String {
        val result = auth.signInWithCredential(credential).await()
        val user = result.user ?: throw IllegalStateException("Firebase sign-in returned no user")
        return user.getIdToken(true).await().token
            ?: throw IllegalStateException("Firebase did not return an ID token")
    }

    private sealed class SendOutcome {
        data object CodeSent : SendOutcome()
        data class AutoCredential(val credential: PhoneAuthCredential) : SendOutcome()
    }
}
