package com.shopai.app.ui.navigation

import androidx.navigation.NavController

val mainTabRoutes = setOf(
    Routes.Home,
    Routes.Customers,
    Routes.Suppliers,
    Routes.More,
)

private val authRoutes = setOf(
    Routes.Splash,
    Routes.Login,
    Routes.Otp,
    Routes.BusinessSetup,
)

fun isMainTabRoute(route: String?): Boolean = route in mainTabRoutes

fun shouldShowVoiceEntryFab(route: String?): Boolean {
    if (route == null) return false
    if (route in authRoutes) return false
    if (route == Routes.VoiceEntry) return false
    return true
}

/** Clears the entire back stack and sets Home as the only destination. */
fun NavController.navigateToHomeAsRoot() {
    navigate(Routes.Home) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/** Switches bottom tabs without building a deep back stack. */
fun NavController.navigateMainTab(route: String) {
    if (route !in mainTabRoutes) {
        navigate(route)
        return
    }
    navigate(route) {
        popUpTo(Routes.Home) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
