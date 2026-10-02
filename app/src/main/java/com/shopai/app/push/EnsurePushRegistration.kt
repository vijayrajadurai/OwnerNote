package com.shopai.app.push

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.shopai.app.data.AppContainer
import kotlinx.coroutines.launch

@Composable
fun EnsurePushRegistration(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        scope.launch { container.pushTokenRepository.registerCurrent() }
    }

    LaunchedEffect(Unit) {
        PushNotifications.ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@LaunchedEffect
            }
        }
        container.pushTokenRepository.registerCurrent()
    }
}
