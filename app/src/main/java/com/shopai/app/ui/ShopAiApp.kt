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
import com.shopai.app.ui.screens.ProductDetailScreen
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
import com.shopai.app.ui.screens.VoiceStockEntryScreen
import com.shopai.app.ui.settings.SettingsScreen
import com.shopai.app.ui.subscription.SubscriptionScreen
import com.shopai.app.push.EnsurePushRegistration
import com.shopai.app.R
import com.shopai.app.ui.notes.HandwrittenNoteScanScreen
import com.shopai.app.ui.notes.HandwrittenNotesScreen
import com.shopai.app.ui.notes.HandwrittenPersonScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext

/** Passes "N transactions saved" from the scan screen back to the notes list. */
private const val HW_SAVED_KEY = "hw_saved_message"

@Composable
fun ShopAiApp(container: AppContainer) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // A tapped Kai reminder notification opens Kai Chat on it (once past login).
    val openReminder by com.shopai.app.notifications.KaiReminderInbox.pending.collectAsState()
    androidx.compose.runtime.LaunchedEffect(openReminder, currentRoute) {
        val signedIn = currentRoute != null && currentRoute !in setOf(Routes.Splash, Routes.Login, Routes.Otp, Routes.BusinessSetup)
        if (openReminder != null && signedIn && currentRoute != Routes.KaiChat) navController.navigate(Routes.KaiChat)
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.Splash,
            modifier = Modifier.fillMaxSize(),
        ) {
        composable(Routes.Splash) {
            // The session check can finish after the owner already tapped "Get Started":
            // only the first one navigates (a second navigate from a popped Splash crashes).
            val onSplash = { navController.currentDestination?.route == Routes.Splash }
            SplashScreen(
                container = container,
                onNavigateLogin = {
                    if (onSplash()) {
                        navController.navigate(Routes.Login) {
                            popUpTo(Routes.Splash) { inclusive = true }
                        }
                    }
                },
                onNavigateHome = { if (onSplash()) navController.navigateToHomeAsRoot() },
                onNavigateBusinessSetup = {
                    if (onSplash()) {
                        navController.navigate(Routes.BusinessSetup) {
                            popUpTo(Routes.Splash) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(Routes.Login) {
            LoginScreen(
                container = container,
                onNavigateOtp = {
                    navController.navigate(Routes.Otp) { launchSingleTop = true }
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
                onNewCustomer = { navController.navigate(Routes.partyForm("CUSTOMER")) },
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
                onEdit = { id -> navController.navigate(Routes.partyForm("CUSTOMER", id)) },
                onNewBill = { id -> navController.navigate(Routes.billEditor("SALE", id)) },
                onPayment = { id -> navController.navigate(Routes.payment("IN", id)) },
            )
        }
        composable(Routes.Suppliers) {
            SuppliersScreen(
                container = container,
                onNavigate = { route -> navController.navigateMainTab(route) },
                onOpenSupplier = { supplierId -> navController.navigate(Routes.supplierDetail(supplierId)) },
                onNewSupplier = { navController.navigate(Routes.partyForm("SUPPLIER")) },
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
                onEdit = { id -> navController.navigate(Routes.partyForm("SUPPLIER", id)) },
                onNewBill = { id -> navController.navigate(Routes.billEditor("PURCHASE", id)) },
                onPayment = { id -> navController.navigate(Routes.payment("OUT", id)) },
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
                onOpenHandwrittenNotes = { navController.navigate(Routes.HandwrittenNotes) },
                onScanNoteForBill = { billId -> navController.navigate(Routes.handwrittenScan(billId)) },
                onOpenNotePerson = { name -> navController.navigate(Routes.handwrittenPerson(name)) },
                // The bill photo turned out handwritten: the handwriting reader takes it.
                onHandwrittenDetected = { navController.navigate(Routes.handwrittenScan()) },
                // A spoken reminder that needs a choice continues in Kai Chat.
                onOpenKaiChat = { navController.navigate(Routes.KaiChat) },
            )
        }
        composable(Routes.HandwrittenNotes) { entry ->
            val saved by entry.savedStateHandle.getStateFlow<String?>(HW_SAVED_KEY, null).collectAsState()
            HandwrittenNotesScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onScan = { navController.navigate(Routes.handwrittenScan()) },
                onOpenPerson = { name -> navController.navigate(Routes.handwrittenPerson(name)) },
                savedMessage = saved,
            )
        }
        composable(
            route = Routes.HandwrittenScan,
            arguments = listOf(navArgument("billId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            val context = LocalContext.current
            HandwrittenNoteScanScreen(
                container = container,
                linkedBillId = entry.arguments?.getLong("billId")?.takeIf { it > 0 },
                onBack = { navController.navigateUpOrHome() },
                // The note photo is a printed bill: the Shop bill reader (on the Speak screen) takes it.
                onPrintedBillDetected = {
                    if (!navController.popBackStack(Routes.VoiceEntry, inclusive = false)) {
                        navController.navigate(Routes.VoiceEntry) {
                            popUpTo(Routes.HandwrittenScan) { inclusive = true }
                        }
                    }
                },
                onSaved = { count, failed ->
                    // The ledger changed: Kai re-reads it, and confirms the save.
                    container.kaiBrain.forget()
                    if (count > 0) {
                        container.kaiBrain.announce(
                            com.shopai.app.brain.KaiResponder.photoSaved(count, com.shopai.app.brain.KaiLanguage.forAppLocale()),
                        )
                    }
                    val message = if (failed > 0) {
                        context.getString(R.string.hw_saved_sync_failed, count, failed)
                    } else {
                        context.getString(R.string.hw_saved_message, count)
                    }
                    // Back to the notes list (opening it if we came from elsewhere).
                    val hub = runCatching { navController.getBackStackEntry(Routes.HandwrittenNotes) }.getOrNull()
                    if (hub != null) {
                        hub.savedStateHandle[HW_SAVED_KEY] = message
                        navController.popBackStack(Routes.HandwrittenNotes, inclusive = false)
                    } else {
                        navController.navigate(Routes.HandwrittenNotes) {
                            popUpTo(Routes.HandwrittenScan) { inclusive = true }
                        }
                        navController.currentBackStackEntry?.savedStateHandle?.set(HW_SAVED_KEY, message)
                    }
                },
            )
        }
        composable(
            route = Routes.HandwrittenPerson,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            HandwrittenPersonScreen(
                container = container,
                name = entry.arguments?.getString("name").orEmpty(),
                onBack = { navController.navigateUpOrHome() },
            )
        }
        composable(Routes.Reminders) {
            RemindersScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onOpenReminder = { id -> navController.navigate(Routes.reminderDetail(id)) },
            )
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
            InventoryScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onOpenProduct = { productId -> navController.navigate(Routes.productDetail(productId)) },
                onNewProduct = { navController.navigate(Routes.productForm()) },
                onOpenVoiceStockEntry = { navController.navigate(Routes.VoiceStockEntry) },
            )
        }
        composable(
            route = Routes.ProductDetail,
            arguments = listOf(navArgument("productId") { type = NavType.StringType }),
        ) { entry ->
            val productId = entry.arguments?.getString("productId") ?: return@composable
            ProductDetailScreen(
                container = container,
                productId = productId,
                onBack = { navController.navigateUpOrHome() },
                onEdit = { id -> navController.navigate(Routes.productForm(id)) },
            )
        }
        composable(
            route = Routes.ProductForm,
            arguments = listOf(navArgument("productId") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val productId = entry.arguments?.getString("productId")?.takeIf { it.isNotEmpty() }
            com.shopai.app.ui.books.ProductFormScreen(
                container = container,
                productId = productId,
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.popBackStack()
                    if (productId == null) navController.navigate(Routes.productDetail(id))
                },
            )
        }
        composable(
            route = Routes.PartyForm,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("partyId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val kind = com.shopai.app.books.model.PartyKind.valueOf(entry.arguments?.getString("kind") ?: "CUSTOMER")
            val partyId = entry.arguments?.getString("partyId")?.takeIf { it.isNotEmpty() }
            com.shopai.app.ui.books.PartyFormScreen(
                container = container,
                kind = kind,
                partyId = partyId,
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.popBackStack()
                    if (partyId == null) {
                        navController.navigate(
                            if (kind == com.shopai.app.books.model.PartyKind.CUSTOMER) Routes.customerDetail(id) else Routes.supplierDetail(id),
                        )
                    }
                },
            )
        }
        composable(Routes.HsnMaster) {
            com.shopai.app.ui.books.HsnMasterScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onOpenProduct = { id -> navController.navigate(Routes.productDetail(id)) },
            )
        }
        composable(Routes.Billing) {
            com.shopai.app.ui.books.billing.BillingHomeScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onNewBill = { kind, partyId -> navController.navigate(Routes.billEditor(kind.name, partyId)) },
                onResume = { d -> navController.navigate(Routes.billEditor(d.kind, draftId = d.id)) },
                onPayment = { incoming -> navController.navigate(Routes.payment(if (incoming) "IN" else "OUT")) },
                onOpen = { id -> navController.navigate(Routes.booksDocument(id)) },
            )
        }
        composable(
            route = Routes.BillEditor,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("partyId") { type = NavType.StringType; defaultValue = "" },
                navArgument("draftId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            com.shopai.app.ui.books.billing.BillEditorScreen(
                container = container,
                kind = com.shopai.app.books.billing.DraftKind.valueOf(entry.arguments?.getString("kind") ?: "SALE"),
                partyId = entry.arguments?.getString("partyId")?.takeIf { it.isNotEmpty() },
                draftId = entry.arguments?.getString("draftId")?.takeIf { it.isNotEmpty() },
                onBack = { navController.popBackStack() },
                onPosted = { id ->
                    navController.popBackStack()
                    navController.navigate(Routes.booksDocument(id))
                },
            )
        }
        composable(
            route = Routes.BooksDocument,
            arguments = listOf(navArgument("txnId") { type = NavType.StringType }),
        ) { entry ->
            val txnId = entry.arguments?.getString("txnId") ?: return@composable
            com.shopai.app.ui.books.billing.DocumentScreen(
                container = container,
                txnId = txnId,
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(Routes.booksDocument(id)) },
                onPayment = { incoming, partyId, docId -> navController.navigate(Routes.payment(if (incoming) "IN" else "OUT", partyId, docId)) },
                onReturn = { id -> navController.navigate(Routes.returnEditor(id)) },
            )
        }
        composable(
            route = Routes.ReturnEditor,
            arguments = listOf(navArgument("txnId") { type = NavType.StringType }),
        ) { entry ->
            val txnId = entry.arguments?.getString("txnId") ?: return@composable
            com.shopai.app.ui.books.billing.ReturnEditorScreen(
                container = container,
                originalTxnId = txnId,
                onBack = { navController.popBackStack() },
                onPosted = { id ->
                    navController.popBackStack()
                    navController.navigate(Routes.booksDocument(id))
                },
            )
        }
        composable(
            route = Routes.Payment,
            arguments = listOf(
                navArgument("direction") { type = NavType.StringType },
                navArgument("partyId") { type = NavType.StringType; defaultValue = "" },
                navArgument("docId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            com.shopai.app.ui.books.billing.PaymentScreen(
                container = container,
                incoming = entry.arguments?.getString("direction") != "OUT",
                partyId = entry.arguments?.getString("partyId")?.takeIf { it.isNotEmpty() },
                docId = entry.arguments?.getString("docId")?.takeIf { it.isNotEmpty() },
                onBack = { navController.popBackStack() },
                onPosted = { id ->
                    navController.popBackStack()
                    navController.navigate(Routes.booksDocument(id))
                },
            )
        }
        composable(Routes.BooksSettings) {
            com.shopai.app.ui.books.BooksSettingsScreen(container = container, onBack = { navController.navigateUpOrHome() })
        }
        composable(Routes.VoiceStockEntry) {
            VoiceStockEntryScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                onOpenInventory = {
                    navController.navigate(Routes.Inventory) {
                        popUpTo(Routes.Inventory) { inclusive = true }
                    }
                },
            )
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
        composable(Routes.KaiChat) {
            com.shopai.app.ui.kaichat.KaiChatScreen(
                container = container,
                onBack = { navController.navigateUpOrHome() },
                // "Indha bill add pannu": the existing Shop bill scanner (OCR → draft → review → confirm).
                onOpenScanner = { navController.navigate(Routes.VoiceEntry) },
            )
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

        // Kai speaks up after manual entries, bills and notes (app-wide, never permanent).
        com.shopai.app.ui.kai.KaiAnnouncementOverlay(container, Modifier.align(Alignment.TopCenter))

        if (isMainTabRoute(currentRoute) || currentRoute == Routes.BusinessSetup) {
            EnsurePushRegistration(container)
        }
    }
}
