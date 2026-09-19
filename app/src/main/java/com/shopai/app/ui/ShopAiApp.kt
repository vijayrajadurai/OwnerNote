package com.shopai.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.VoiceEntryFab
import com.shopai.app.ui.navigation.Routes
import com.shopai.app.ui.navigation.isMainTabRoute
import com.shopai.app.ui.navigation.navigateMainTab
import com.shopai.app.ui.navigation.navigateToHomeAsRoot
import com.shopai.app.ui.navigation.shouldShowVoiceEntryFab
import com.shopai.app.ui.screens.AddCreditScreen
import com.shopai.app.ui.screens.AddDebitScreen
import com.shopai.app.ui.screens.AiInsightsScreen
import com.shopai.app.ui.screens.BusinessSetupScreen
import com.shopai.app.ui.screens.CustomersScreen
import com.shopai.app.ui.screens.DiscoverScreen
import com.shopai.app.ui.screens.FundingQualificationScreen
import com.shopai.app.ui.screens.HomeScreen
import com.shopai.app.ui.screens.LoginScreen
import com.shopai.app.ui.screens.MoreScreen
import com.shopai.app.ui.screens.OtpScreen
import com.shopai.app.ui.screens.RemindersScreen
import com.shopai.app.ui.screens.SplashScreen
import com.shopai.app.ui.screens.SuppliersScreen
import com.shopai.app.ui.screens.VoiceEntryScreen
import com.shopai.app.ui.settings.SettingsScreen
import com.shopai.app.ui.subscription.SubscriptionScreen

@Composable
fun ShopAiApp(container: AppContainer) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.Splash,
            modifier = Modifier.fillMaxSize(),
        ) {
        composable(Routes.Splash) {
            SplashScreen(
                container = container,
                onNavigateLogin = {
                    navController.navigate(Routes.Login) {
                        popUpTo(Routes.Splash) { inclusive = true }
                    }
                },
                onNavigateHome = { navController.navigateToHomeAsRoot() },
                onNavigateBusinessSetup = {
                    navController.navigate(Routes.BusinessSetup) {
                        popUpTo(Routes.Splash) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Login) {
            LoginScreen(
                container = container,
                onNavigateOtp = { navController.navigate(Routes.Otp) },
                onNavigateHome = { navController.navigateToHomeAsRoot() },
                onNavigateBusinessSetup = {
                    navController.navigate(Routes.BusinessSetup) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Otp) {
            OtpScreen(
                container = container,
                onNavigateHome = { navController.navigateToHomeAsRoot() },
                onNavigateBusinessSetup = {
                    navController.navigate(Routes.BusinessSetup) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.BusinessSetup) {
            BusinessSetupScreen(
                container = container,
                onComplete = { navController.navigateToHomeAsRoot() },
            )
        }
        composable(Routes.Home) {
            HomeScreen(
                container = container,
                onNavigate = { route -> navController.navigateMainTab(route) },
            )
        }
        composable(Routes.Discover) {
            DiscoverScreen(
                container = container,
                onNavigate = { route -> navController.navigateMainTab(route) },
            )
        }
        composable(Routes.Customers) {
            CustomersScreen(
                container = container,
                onNavigate = { route -> navController.navigateMainTab(route) },
            )
        }
        composable(Routes.Suppliers) {
            SuppliersScreen(
                container = container,
                onNavigate = { route -> navController.navigateMainTab(route) },
            )
        }
        composable(Routes.More) {
            MoreScreen(
                container = container,
                onNavigate = { route -> navController.navigateMainTab(route) },
                onLogout = {
                    navController.navigate(Routes.Login) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.AddCredit) {
            AddCreditScreen(container = container, onDone = { navController.popBackStack() })
        }
        composable(Routes.AddDebit) {
            AddDebitScreen(container = container, onDone = { navController.popBackStack() })
        }
        composable(Routes.VoiceEntry) {
            VoiceEntryScreen(container = container, onDone = { navController.popBackStack() })
        }
        composable(Routes.Reminders) {
            RemindersScreen(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.AiInsights) {
            AiInsightsScreen(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.Settings) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenSubscription = { navController.navigate(Routes.Subscription) },
            )
        }
        composable(Routes.Subscription) {
            SubscriptionScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.FundingQualification,
            arguments = listOf(navArgument("opportunityId") { type = NavType.StringType }),
        ) { entry ->
            val opportunityId = entry.arguments?.getString("opportunityId") ?: return@composable
            FundingQualificationScreen(
                container = container,
                opportunityId = opportunityId,
                onComplete = { navController.navigateToHomeAsRoot() },
            )
        }
        }

        if (shouldShowVoiceEntryFab(currentRoute)) {
            VoiceEntryFab(
                onClick = { navController.navigate(Routes.VoiceEntry) },
                modifier = Modifier.align(Alignment.BottomEnd),
                bottomPadding = if (isMainTabRoute(currentRoute)) 88.dp else 24.dp,
            )
        }
    }
}
