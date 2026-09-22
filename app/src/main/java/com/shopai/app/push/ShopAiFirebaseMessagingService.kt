package com.shopai.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.shopai.app.ShopAiApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShopAiFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val app = application as? ShopAiApplication ?: return
        scope.launch { app.container.pushTokenRepository.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(com.shopai.app.R.string.brand_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return
        PushNotifications.show(this, title, body)
    }
}
