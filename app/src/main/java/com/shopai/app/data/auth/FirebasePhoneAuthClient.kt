package com.shopai.app.data.auth

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
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

    suspend fun sendOtp(activity: Activity, e164Phone: String): FirebasePhoneSendResult {
        val outcome = suspendCancellableCoroutine<SendOutcome> { cont ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    if (cont.isActive) cont.resume(SendOutcome.AutoCredential(credential))
                }

                override fun onVerificationFailed(exception: FirebaseException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }

                override fun onCodeSent(
                    verificationId: String,
                    forceResendingToken: PhoneAuthProvider.ForceResendingToken,
                ) {
                    this@FirebasePhoneAuthClient.verificationId = verificationId
                    resendToken = forceResendingToken
                    if (cont.isActive) cont.resume(SendOutcome.CodeSent)
                }
            }

            val builder = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(e164Phone)
                .setTimeout(60L, TimeUnit.SECONDS)
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

    fun signOut() {
        auth.signOut()
        verificationId = null
        resendToken = null
    }

    private suspend fun signInAndGetIdToken(credential: PhoneAuthCredential): String {
        val authResult: AuthResult = auth.signInWithCredential(credential).awaitTask()
        val user = authResult.user ?: throw IllegalStateException("Firebase sign-in returned no user")
        val tokenResult: GetTokenResult = user.getIdToken(true).awaitTask()
        return tokenResult.getToken()
            ?: throw IllegalStateException("Firebase did not return an ID token")
    }

    private sealed class SendOutcome {
        data object CodeSent : SendOutcome()
        data class AutoCredential(val credential: PhoneAuthCredential) : SendOutcome()
    }
}

private suspend fun <T> Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (!cont.isActive) return@addOnCompleteListener
            val error = task.exception
            when {
                error != null -> cont.resumeWithException(error)
                task.isCanceled -> cont.cancel()
                else -> cont.resume(task.result)
            }
        }
    }
