package com.shopai.app.ui.navigation

import androidx.navigation.NavController

val mainTabRoutes = setOf(
    Routes.Home,
    Routes.Discover,
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
    val path = route.substringBefore("?")
    if (path in authRoutes) return false
    if (path == Routes.VoiceEntry) return false
    if (path == Routes.GroupBuyingNew) return false
    if (path == Routes.Profile) return false
    return true
}

/** Pops one screen, or returns to Home if the stack cannot go back. */
fun NavController.navigateUpOrHome() {
    if (!popBackStack()) {
        navigateToHomeAsRoot()
    }
}

/** Clears the entire back stack and sets Home as the only destination. */
fun NavController.navigateToHomeAsRoot() {
    navigate(Routes.Home) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/** Switches bottom tabs without restoring leftover detail screens (e.g. reminder details). */
fun NavController.navigateMainTab(route: String) {
    if (route !in mainTabRoutes) {
        navigate(route)
        return
    }
    popBackStack(Routes.Home, inclusive = false)
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(Routes.Home) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
