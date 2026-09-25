package com.shopai.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class ShopCoordinates(
    val latitude: Double,
    val longitude: Double,
)

fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

fun lastKnownShopCoordinates(context: Context): ShopCoordinates? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    val best = providers
        .mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        .maxByOrNull { it.time } ?: return null
    if (!best.isValidShopCoordinate()) return null
    return ShopCoordinates(best.latitude, best.longitude)
}

suspend fun currentShopCoordinates(context: Context, timeoutMs: Long = 8_000): ShopCoordinates? {
    lastKnownShopCoordinates(context)?.let { return it }
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!location.isValidShopCoordinate()) return
                    if (continuation.isActive) {
                        runCatching { manager.removeUpdates(this) }
                        continuation.resume(ShopCoordinates(location.latitude, location.longitude))
                    }
                }
            }
            continuation.invokeOnCancellation {
                runCatching { manager.removeUpdates(listener) }
            }
            val looper = Looper.getMainLooper()
            var requested = false
            try {
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).forEach { provider ->
                    if (manager.isProviderEnabled(provider)) {
                        manager.requestLocationUpdates(provider, 0L, 0f, listener, looper)
                        requested = true
                    }
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            if (!requested && continuation.isActive) continuation.resume(null)
        }
    } ?: lastKnownShopCoordinates(context)
}

fun Location.isValidShopCoordinate(): Boolean {
    if (latitude == 0.0 && longitude == 0.0) return false
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
    return true
}

fun displayIndianPhone(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val digits = raw.filter { it.isDigit() }.takeLast(10)
    return if (digits.length == 10) digits else raw
}
