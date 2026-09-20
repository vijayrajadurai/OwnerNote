package com.shopai.app.data.repository

import android.app.Activity
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
) {
    suspend fun hydrate(): String? = tokenStore.getToken()

    suspend fun sendOtp(activity: Activity, phone: String): PhoneOtpSendResult {
        val normalized = phone.filter { it.isDigit() }.takeLast(10)
        val e164 = "+91$normalized"
        tokenStore.setPendingPhone(normalized)
        return when (val firebase = firebasePhoneAuth.sendOtp(activity, e164)) {
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
        return response.data
    }

    suspend fun logout() {
        runCatching { firebasePhoneAuth.signOut() }
        tokenStore.setToken(null)
        tokenStore.setPendingPhone(null)
    }

    fun apiErrorMessage(throwable: Throwable, fallback: String): String =
        apiErrorHandler.resolve(throwable, fallback).message

    private suspend fun exchangeFirebaseToken(idToken: String): AuthResponse {
        val response = api.firebaseLogin(FirebaseLoginRequest(idToken))
        tokenStore.setToken(response.data.token)
        return response.data
    }
}
