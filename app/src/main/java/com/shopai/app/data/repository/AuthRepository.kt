package com.shopai.app.data.repository

import com.shopai.app.crash.CrashReporting
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.auth.FirebasePhoneAuthClient
import com.shopai.app.data.auth.FirebasePhoneSendResult
import com.shopai.app.data.local.TokenStore
import com.shopai.app.data.model.AuthResponse
import com.shopai.app.data.model.FirebaseLoginRequest
import com.shopai.app.data.model.SendOtpResponse
import com.shopai.app.data.network.ApiErrorHandler

sealed class PhoneOtpSendResult {
    data class CodeSent(val data: SendOtpResponse) : PhoneOtpSendResult()
    data class SignedIn(val auth: AuthResponse) : PhoneOtpSendResult()
}

class AuthRepository(
    private val api: ShopAiApi,
    private val tokenStore: TokenStore,
    private val apiErrorHandler: ApiErrorHandler,
    private val firebasePhoneAuth: FirebasePhoneAuthClient,
    private val pushTokenRepository: PushTokenRepository,
) {
    fun warmupPhoneVerification() {
        firebasePhoneAuth.warmupAppVerification()
    }

    suspend fun getLoginPhone(): String? = tokenStore.getPendingPhone()

    suspend fun hydrate(): String? {
        val token = tokenStore.getToken()
        CrashReporting.setSession(if (token != null) tokenStore.getPendingPhone() else null)
        return token
    }

    suspend fun sendOtp(phone: String): PhoneOtpSendResult {
        val normalized = phone.filter { it.isDigit() }.takeLast(10)
        val e164 = "+91$normalized"
        tokenStore.setPendingPhone(normalized)
        return when (val firebase = firebasePhoneAuth.sendOtp(e164)) {
            FirebasePhoneSendResult.CodeSent -> {
                PhoneOtpSendResult.CodeSent(SendOtpResponse(phone = normalized, expiresInSeconds = 60))
            }
            is FirebasePhoneSendResult.AutoVerified -> {
                PhoneOtpSendResult.SignedIn(exchangeFirebaseToken(firebase.idToken))
            }
        }
    }

    suspend fun verifyOtp(code: String): AuthResponse {
        tokenStore.getPendingPhone()
            ?: throw IllegalStateException("No pending phone")
        val idToken = firebasePhoneAuth.verifySmsCode(code)
        return exchangeFirebaseToken(idToken)
    }

    suspend fun testLogin(username: String, password: String): AuthResponse {
        val response = api.testLogin(
            com.shopai.app.data.model.TestLoginRequest(username, password),
        )
        tokenStore.setToken(response.data.token)
        runCatching { pushTokenRepository.registerCurrent() }
        return response.data
    }

    suspend fun logout() {
        runCatching { pushTokenRepository.unregister() }
        runCatching { firebasePhoneAuth.signOut() }
        tokenStore.setToken(null)
        tokenStore.setPendingPhone(null)
        CrashReporting.setSession(null)
    }

    fun apiErrorMessage(throwable: Throwable, fallback: String): String =
        apiErrorHandler.resolve(throwable, fallback).message

    private suspend fun exchangeFirebaseToken(idToken: String): AuthResponse {
        val response = api.firebaseLogin(FirebaseLoginRequest(idToken))
        tokenStore.setToken(response.data.token)
        CrashReporting.setSession(tokenStore.getPendingPhone())
        runCatching { pushTokenRepository.registerCurrent() }
        return response.data
    }
}
