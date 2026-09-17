package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.local.TokenStore
import com.shopai.app.data.model.AuthResponse
import com.shopai.app.data.model.SendOtpResponse
import com.shopai.app.data.network.ApiErrorHandler

class AuthRepository(
    private val api: ShopAiApi,
    private val tokenStore: TokenStore,
    private val apiErrorHandler: ApiErrorHandler,
) {
    suspend fun hydrate(): String? = tokenStore.getToken()

    suspend fun sendOtp(phone: String): SendOtpResponse {
        val normalized = phone.filter { it.isDigit() }.takeLast(10)
        val response = api.sendOtp(com.shopai.app.data.model.SendOtpRequest(normalized))
        tokenStore.setPendingPhone(normalized)
        return response.data
    }

    suspend fun verifyOtp(code: String): AuthResponse {
        val phone = tokenStore.getPendingPhone()
            ?: throw IllegalStateException("No pending phone")
        val response = api.verifyOtp(
            com.shopai.app.data.model.VerifyOtpRequest(phone, code),
        )
        tokenStore.setToken(response.data.token)
        return response.data
    }

    suspend fun testLogin(username: String, password: String): AuthResponse {
        val response = api.testLogin(
            com.shopai.app.data.model.TestLoginRequest(username, password),
        )
        tokenStore.setToken(response.data.token)
        return response.data
    }

    suspend fun logout() {
        tokenStore.setToken(null)
        tokenStore.setPendingPhone(null)
    }

    fun apiErrorMessage(throwable: Throwable, fallback: String): String =
        apiErrorHandler.resolve(throwable, fallback).message
}
