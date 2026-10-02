package com.shopai.app.data.repository

import com.google.firebase.messaging.FirebaseMessaging
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.local.TokenStore
import com.shopai.app.data.model.RegisterFcmTokenRequest
import com.shopai.app.data.model.UnregisterFcmTokenRequest
import kotlinx.coroutines.tasks.await

class PushTokenRepository(
    private val api: ShopAiApi,
    private val tokenStore: TokenStore,
) {
    suspend fun registerCurrent() {
        if (tokenStore.getToken() == null) return
        val fcmToken = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        register(fcmToken)
    }

    suspend fun register(fcmToken: String) {
        if (tokenStore.getToken() == null) {
            tokenStore.setFcmToken(fcmToken)
            return
        }
        tokenStore.setFcmToken(fcmToken)
        runCatching {
            api.registerFcmToken(RegisterFcmTokenRequest(token = fcmToken, platform = "ANDROID"))
        }
    }

    suspend fun unregister() {
        val fcmToken = tokenStore.getFcmToken() ?: return
        runCatching { api.unregisterFcmToken(UnregisterFcmTokenRequest(token = fcmToken)) }
        tokenStore.setFcmToken(null)
    }
}
