package com.shopai.app.ui.navigation

import androidx.navigation.NavController

val mainTabRoutes = setOf(
    Routes.Home,
    Routes.Discover,
    Routes.Customers,
    Routes.Suppliers,
    Routes.More,
)

fun isMainTabRoute(route: String?): Boolean = route in mainTabRoutes

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
