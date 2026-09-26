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
import com.shopai.app.ui.navigation.navigateUpOrHome
import com.shopai.app.ui.navigation.shouldShowVoiceEntryFab
import com.shopai.app.ui.screens.AddCreditScreen
import com.shopai.app.ui.screens.AddDebitScreen
import com.shopai.app.ui.screens.AiInsightsScreen
import com.shopai.app.ui.screens.BusinessSetupScreen
import com.shopai.app.ui.screens.CustomerDetailScreen
import com.shopai.app.ui.screens.CustomersScreen
import com.shopai.app.ui.screens.DailyCashNoteScreen
import com.shopai.app.ui.screens.SupplierDetailScreen
import com.shopai.app.ui.screens.DiscoverScreen
import com.shopai.app.ui.screens.FundingQualificationScreen
import com.shopai.app.ui.screens.GroupBuyingResultScreen
import com.shopai.app.ui.screens.GroupBuyingScreen
import com.shopai.app.ui.screens.AskBusinessScreen
import com.shopai.app.ui.screens.HomeScreen
import com.shopai.app.ui.screens.InventoryScreen
import com.shopai.app.ui.screens.LoginScreen
import com.shopai.app.ui.screens.MoreScreen
import com.shopai.app.ui.screens.NewGroupBuyingRequestScreen
import com.shopai.app.ui.screens.OffersScreen
import com.shopai.app.ui.screens.OtpScreen
import com.shopai.app.ui.screens.ProfileEditScreen
import com.shopai.app.ui.screens.RemindersScreen
import com.shopai.app.ui.screens.ReminderDetailScreen
import com.shopai.app.ui.screens.SplashScreen
import com.shopai.app.ui.screens.SuppliersScreen
import com.shopai.app.ui.screens.VoiceEntryScreen
import com.shopai.app.ui.settings.SettingsScreen
import com.shopai.app.ui.subscription.SubscriptionScreen
import com.shopai.app.push.EnsurePushRegistration

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
                onNavigateBack = { navController.navigateUpOrHome() },
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
                onAddCredit = { id, name, phone ->
                    navController.navigate(Routes.addCredit(id, name.takeIf { it.isNotBlank() }, phone))
                },
                onAddDebit = { id, name, phone ->
                    navController.navigate(Routes.addDebit(id, name.takeIf { it.isNotBlank() }, phone))
                },
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
                onOpenCustomer = { customerId -> navController.navigate(Routes.customerDetail(customerId)) },
                onAddCredit = { id, name, phone ->
                    navController.navigate(Routes.addCredit(id, name.takeIf { it.isNotBlank() }, phone))
                },
            )
        }
        composable(
            route = Routes.CustomerDetail,
            arguments = listOf(navArgument("customerId") { type = NavType.StringType }),
        ) { entry ->
            val customerId = entry.arguments?.getString("customerId") ?: return@composable
            CustomerDetailScreen(
                container = container,
                customerId = customerId,
                onBack = { navController.navigateUpOrHome() },
                onAddCredit = { id, name -> navController.navigate(Routes.addCredit(id, name, null)) },
            )
        }
        composable(Routes.Suppliers) {
            SuppliersScreen(
                container = container,
                onNavigate = { route -> navController.navigateMainTab(route) },
                onOpenSupplier = { supplierId -> navController.navigate(Routes.supplierDetail(supplierId)) },
                onAddDebit = { id, name, phone ->
                    navController.navigate(Routes.addDebit(id, name.takeIf { it.isNotBlank() }, phone))
                },
            )
        }
        composable(
            route = Routes.SupplierDetail,
            arguments = listOf(navArgument("supplierId") { type = NavType.StringType }),
        ) { entry ->
            val supplierId = entry.arguments?.getString("supplierId") ?: return@composable
            SupplierDetailScreen(
                container = container,
                supplierId = supplierId,
                onBack = { navController.popBackStack() },
                onAddDebit = { id, name -> navController.navigate(Routes.addDebit(id, name, null)) },
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
        composable(
            route = Routes.AddCredit,
            arguments = listOf(
                navArgument("customerId") { type = NavType.StringType; defaultValue = "" },
                navArgument("customerName") { type = NavType.StringType; defaultValue = "" },
                navArgument("customerPhone") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val customerId = entry.arguments?.getString("customerId")?.takeIf { it.isNotEmpty() }
            val customerName = entry.arguments?.getString("customerName")?.takeIf { it.isNotEmpty() }
            val customerPhone = entry.arguments?.getString("customerPhone")?.takeIf { it.isNotEmpty() }
            AddCreditScreen(
                container = container,
                prefillCustomerId = customerId,
                prefillCustomerName = customerName,
                prefillCustomerPhone = customerPhone,
                onBack = { navController.navigateUpOrHome() },
                onDone = { navController.navigateUpOrHome() },
            )
        }
        composable(
            route = Routes.AddDebit,
            arguments = listOf(
                navArgument("supplierId") { type = NavType.StringType; defaultValue = "" },
                navArgument("supplierName") { type = NavType.StringType; defaultValue = "" },
                navArgument("supplierPhone") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val supplierId = entry.arguments?.getString("supplierId")?.takeIf { it.isNotEmpty() }
            val supplierName = entry.arguments?.getString("supplierName")?.takeIf { it.isNotEmpty() }
            val supplierPhone = entry.arguments?.getString("supplierPhone")?.takeIf { it.isNotEmpty() }
            AddDebitScreen(
                container = container,
                prefillSupplierId = supplierId,
                prefillSupplierName = supplierName,
                prefillSupplierPhone = supplierPhone,
                onBack = { navController.navigateUpOrHome() },
                onDone = { navController.navigateUpOrHome() },
            )
        }
        composable(Routes.VoiceEntry) {
            VoiceEntryScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onDone = { navController.navigateUpOrHome() },
            )
        }
        composable(Routes.Reminders) {
            RemindersScreen(container = container, onBack = { navController.navigateUpOrHome() })
        }
        composable(
            route = Routes.ReminderDetail,
            arguments = listOf(navArgument("reminderId") { type = NavType.StringType }),
        ) { entry ->
            val reminderId = entry.arguments?.getString("reminderId") ?: return@composable
            ReminderDetailScreen(
                container = container,
                reminderId = reminderId,
                onBack = { navController.navigateUpOrHome() },
                onOpenLedger = { kind ->
                    val route = if (kind.equals("PAYMENT", ignoreCase = true)) {
                        Routes.Suppliers
                    } else {
                        Routes.Customers
                    }
                    navController.navigateMainTab(route)
                },
            )
        }
        composable(Routes.DailyCashNote) {
            DailyCashNoteScreen(container = container, onBack = { navController.navigateUpOrHome() })
        }
        composable(Routes.AiInsights) {
            AiInsightsScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onOpenLoanOffer = { opportunityId ->
                    navController.navigate(Routes.fundingQualification(opportunityId))
                },
            )
        }
        composable(Routes.Profile) {
            ProfileEditScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
            )
        }
        composable(Routes.GroupBuying) {
            GroupBuyingScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onNewRequest = { navController.navigate(Routes.GroupBuyingNew) },
                onOpenRequest = { requestId -> navController.navigate(Routes.groupBuyingResult(requestId)) },
            )
        }
        composable(Routes.GroupBuyingNew) {
            NewGroupBuyingRequestScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onCreated = { requestId ->
                    navController.navigate(Routes.groupBuyingResult(requestId)) {
                        popUpTo(Routes.GroupBuying) { inclusive = false }
                    }
                },
            )
        }
        composable(
            route = Routes.GroupBuyingResult,
            arguments = listOf(navArgument("requestId") { type = NavType.StringType }),
        ) { entry ->
            val requestId = entry.arguments?.getString("requestId") ?: return@composable
            GroupBuyingResultScreen(
                container = container,
                requestId = requestId,
                onBack = { navController.navigateUpOrHome() },
            )
        }
        composable(Routes.Inventory) {
            InventoryScreen(container = container, onBack = { navController.navigateUpOrHome() })
        }
        composable(Routes.AskBusiness) {
            AskBusinessScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onOpenVoiceEntry = { navController.navigate(Routes.VoiceEntry) },
            )
        }
        composable(Routes.LocalOffers) {
            OffersScreen(container = container, onBack = { navController.navigateUpOrHome() })
        }
        composable(Routes.Settings) {
            SettingsScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onOpenSubscription = { navController.navigate(Routes.Subscription) },
            )
        }
        composable(Routes.Subscription) {
            SubscriptionScreen(onBack = { navController.navigateUpOrHome() })
        }
        composable(
            route = Routes.FundingQualification,
            arguments = listOf(navArgument("opportunityId") { type = NavType.StringType }),
        ) { entry ->
            val opportunityId = entry.arguments?.getString("opportunityId") ?: return@composable
            FundingQualificationScreen(
                container = container,
                opportunityId = opportunityId,
                onBack = { navController.navigateUpOrHome() },
                onComplete = { navController.navigateToHomeAsRoot() },
            )
        }
        }

        if (shouldShowVoiceEntryFab(currentRoute)) {
            VoiceEntryFab(
                onClick = { navController.navigate(Routes.VoiceEntry) },
                modifier = Modifier.align(Alignment.BottomCenter),
                bottomPadding = if (isMainTabRoute(currentRoute)) 78.dp else 24.dp,
            )
        }

        if (isMainTabRoute(currentRoute) || currentRoute == Routes.BusinessSetup) {
            EnsurePushRegistration(container)
        }
    }
}
